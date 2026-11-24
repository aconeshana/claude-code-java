package com.claudecode.core.metrics;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Short-TTL (7-day), SHA-256 content-addressed disk cache for {@code System Prompt + Tool Schemas}
 * snapshots under {@code $CLAUDE_CONFIG_DIR/cache/trajectory-headers/<sha256>.json}.
 *
 * <p>Keeps session {@code .jsonl} files tiny (~250 B per step via {@code headerSha256} pointer)
 * while enabling rich System Prompt and Tool Schema diffing in the WebUI Trajectory view.
 */
@Explanation("7-day content-addressed cache for Trajectory System Prompt and Tool Schema headers")
public final class TrajectoryHeaderCache {

    public static final Duration DEFAULT_TTL = Duration.ofDays(7);
    private static final long SWEEP_INTERVAL_MS = Duration.ofMinutes(10).toMillis();
    private static final Set<PosixFilePermission> OWNER_DIR_PERMS = EnumSet.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE);

    private final Path cacheDir;
    private final Duration ttl;
    private final LongSupplier clock;
    private final AtomicLong lastSweepMs = new AtomicLong(0L);

    public record ToolSpec(String name, String description, JsonNode parameters) {}

    public record HeaderSnapshot(
            String sha256,
            long createdAt,
            String systemPrompt,
            List<ToolSpec> tools) {}

    public TrajectoryHeaderCache() {
        this(defaultCacheDir(), DEFAULT_TTL, System::currentTimeMillis);
    }

    public TrajectoryHeaderCache(Path cacheDir, Duration ttl, LongSupplier clock) {
        this.cacheDir = cacheDir;
        this.ttl = ttl != null ? ttl : DEFAULT_TTL;
        this.clock = clock != null ? clock : System::currentTimeMillis;
    }

    public static Path defaultCacheDir() {
        return ClaudePaths.currentClaudeHome().resolve("cache").resolve("trajectory-headers");
    }

    /**
     * Stores (or refreshes TTL on hit) the normalized system prompt + tool definitions and returns
     * its 64-character lowercase SHA-256 hex digest, or {@code null} on I/O failure.
     */
    public String store(String systemPrompt, List<ToolSpec> tools) {
        String normalizedSystem = systemPrompt != null ? systemPrompt : "";
        List<ToolSpec> normalizedTools = tools != null ? tools : List.of();
        if (normalizedSystem.isEmpty() && normalizedTools.isEmpty()) {
            return null;
        }
        try {
            ObjectNode canonical = buildCanonicalPayload(normalizedSystem, normalizedTools);
            byte[] canonicalBytes = JsonUtils.getMapper().writeValueAsBytes(canonical);
            String sha256 = sha256Hex(canonicalBytes);
            long now = clock.getAsLong();
            maybeEvictExpired(now);

            ensureOwnerDir(cacheDir);
            Path target = cacheDir.resolve(sha256 + ".json");
            if (Files.isRegularFile(target)) {
                try {
                    Files.setLastModifiedTime(target, FileTime.fromMillis(now));
                } catch (IOException _) {
                    // Best-effort touch; existing file remains readable.
                }
                return sha256;
            }

            canonical.put("sha256", sha256);
            canonical.put("createdAt", now);
            Path temp = Files.createTempFile(cacheDir, sha256.substring(0, 12) + "-", ".tmp");
            try {
                Files.writeString(temp, canonical.toString(), StandardCharsets.UTF_8);
                Files.setLastModifiedTime(temp, FileTime.fromMillis(now));
                try {
                    Files.move(temp, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException _) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
            return sha256;
        } catch (IOException _) {
            return null;
        }
    }

    /** Loads a cached header snapshot if present and within the 7-day TTL window. */
    public Optional<HeaderSnapshot> load(String sha256) {
        if (!isValidSha256(sha256)) {
            return Optional.empty();
        }
        Path target = cacheDir.resolve(sha256 + ".json");
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        long now = clock.getAsLong();
        try {
            long lastMod = Files.getLastModifiedTime(target).toMillis();
            if (now - lastMod > ttl.toMillis()) {
                Files.deleteIfExists(target);
                return Optional.empty();
            }
            JsonNode root = JsonUtils.getMapper().readTree(
                Files.readString(target, StandardCharsets.UTF_8));
            if (root == null || !root.isObject()) {
                return Optional.empty();
            }
            String system = root.path("system").asText("");
            long createdAt = root.path("createdAt").asLong(lastMod);
            List<ToolSpec> tools = new ArrayList<>();
            JsonNode toolsNode = root.get("tools");
            if (toolsNode != null && toolsNode.isArray()) {
                for (JsonNode item : toolsNode) {
                    if (!item.isObject()) continue;
                    tools.add(new ToolSpec(
                        item.path("name").asText(""),
                        item.hasNonNull("description") ? item.get("description").asText() : null,
                        item.get("parameters")));
                }
            }
            return Optional.of(new HeaderSnapshot(sha256, createdAt, system, List.copyOf(tools)));
        } catch (IOException _) {
            return Optional.empty();
        }
    }

    /** Evicts cached header blobs older than {@link #ttl}. Returns the number of deleted files. */
    public int evictExpired() {
        return evictExpiredAt(clock.getAsLong());
    }

    private void maybeEvictExpired(long now) {
        long prev = lastSweepMs.get();
        if (now - prev < SWEEP_INTERVAL_MS) {
            return;
        }
        if (lastSweepMs.compareAndSet(prev, now)) {
            evictExpiredAt(now);
        }
    }

    private int evictExpiredAt(long now) {
        if (!Files.isDirectory(cacheDir)) {
            return 0;
        }
        int removed = 0;
        long cutoff = now - ttl.toMillis();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(cacheDir, "*.json")) {
            for (Path entry : stream) {
                try {
                    if (Files.getLastModifiedTime(entry).toMillis() < cutoff) {
                        if (Files.deleteIfExists(entry)) {
                            removed++;
                        }
                    }
                } catch (IOException _) {
                    // Ignore concurrent deletion.
                }
            }
        } catch (IOException _) {
            // Best-effort sweep.
        }
        return removed;
    }

    private static ObjectNode buildCanonicalPayload(String systemPrompt, List<ToolSpec> tools) {
        ObjectNode root = JsonUtils.getMapper().createObjectNode();
        root.put("system", systemPrompt);
        ArrayNode arr = root.putArray("tools");
        for (ToolSpec tool : tools) {
            ObjectNode t = arr.addObject();
            t.put("name", StringUtils.defaultString(tool.name()));
            if (tool.description() != null) {
                t.put("description", tool.description());
            }
            if (tool.parameters() != null && !tool.parameters().isNull()) {
                t.set("parameters", tool.parameters());
            }
        }
        return root;
    }

    private static void ensureOwnerDir(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            return;
        }
        Files.createDirectories(dir);
        try {
            Files.setPosixFilePermissions(dir, OWNER_DIR_PERMS);
        } catch (UnsupportedOperationException _) {
            // Non-POSIX filesystem (e.g. Windows NTFS).
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static boolean isValidSha256(String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        for (int i = 0; i < 64; i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return !Strings.CS.contains(value, "/");
    }
}
