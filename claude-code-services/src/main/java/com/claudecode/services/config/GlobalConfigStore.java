package com.claudecode.services.config;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.io.FileUtils;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;


public final class GlobalConfigStore {

    private static final long FRESHNESS_POLL_MS = 1_000L;
    private static final Object DEFAULT_CACHE_MONITOR = new Object();
    private static final ScheduledExecutorService FRESHNESS_WATCHER =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "global-config-freshness");
            thread.setDaemon(true);
            return thread;
        });
    private static volatile CachedConfig defaultCache;
    private static volatile boolean freshnessWatcherStarted;

    private record FileStamp(boolean exists, long modifiedMillis, long size) {}
    private record CachedConfig(ObjectNode root, FileStamp stamp) {}


    public static final String API_KEY_FIELD = "primaryApiKey";

    /** Legacy field name retained as a read-only fallback. */
    public static final String LEGACY_API_KEY_FIELD = "apiKey";

    /**
     * In-process monitor serializing every read-modify-write.
     */
    static final Object WRITE_MONITOR = new Object();

    private GlobalConfigStore() {}

    /**
     * Reads one immutable-in-practice whole-file snapshot for multi-key consumers.
     * Missing, malformed, or non-object files produce an empty object, matching
     * the typed accessors' default-value behavior.
     */
    public static ObjectNode snapshot(Path path) {
        if (isDefaultPath(path)) return defaultSnapshot().deepCopy();
        return snapshotUncached(path);
    }

    private static ObjectNode snapshotUncached(Path path) {
        if (!Files.isRegularFile(path)) return JsonUtils.getMapper().createObjectNode();
        try {
            JsonNode root = JsonUtils.readJson(path);
            return root != null && root.isObject()
                ? ((ObjectNode) root).deepCopy()
                : JsonUtils.getMapper().createObjectNode();
        } catch (Exception _) {
            return JsonUtils.getMapper().createObjectNode();
        }
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        return getBoolean(ClaudePaths.GLOBAL_JSON, key, defaultValue);
    }

    public static boolean getBoolean(Path path, String key, boolean defaultValue) {
        JsonNode node = readKey(path, key);
        return node == null || !node.isBoolean() ? defaultValue : node.asBoolean();
    }

    public static String getString(String key, String defaultValue) {
        return getString(ClaudePaths.GLOBAL_JSON, key, defaultValue);
    }

    public static String getString(Path path, String key, String defaultValue) {
        JsonNode node = readKey(path, key);
        return node == null || !node.isTextual() ? defaultValue : node.asText();
    }

    public static int getInt(String key, int defaultValue) {
        return getInt(ClaudePaths.GLOBAL_JSON, key, defaultValue);
    }

    public static int getInt(Path path, String key, int defaultValue) {
        JsonNode node = readKey(path, key);
        return node == null || !node.isInt() ? defaultValue : node.asInt();
    }

    private static JsonNode readKey(Path path, String key) {
        JsonNode node = (isDefaultPath(path) ? defaultSnapshot() : snapshotUncached(path)).get(key);
        return node == null ? null : node.deepCopy();
    }

    private static ObjectNode defaultSnapshot() {
        CachedConfig cached = defaultCache;
        if (cached != null) return cached.root();
        synchronized (DEFAULT_CACHE_MONITOR) {
            cached = defaultCache;
            if (cached == null) {
                Path path = ClaudePaths.GLOBAL_JSON;
                cached = new CachedConfig(snapshotUncached(path), fileStamp(path));
                defaultCache = cached;
                startFreshnessWatcherLocked();
            }
            return cached.root();
        }
    }

    private static void startFreshnessWatcherLocked() {
        if (freshnessWatcherStarted) return;
        freshnessWatcherStarted = true;
        FRESHNESS_WATCHER.scheduleWithFixedDelay(
            GlobalConfigStore::refreshDefaultCache,
            FRESHNESS_POLL_MS, FRESHNESS_POLL_MS, TimeUnit.MILLISECONDS);
    }

    private static void refreshDefaultCache() {
        try {
            CachedConfig before = defaultCache;
            if (before == null) return;
            Path path = ClaudePaths.GLOBAL_JSON;
            FileStamp observed = fileStamp(path);
            if (observed.equals(before.stamp())) return;
            ObjectNode refreshed = readValidObject(path, observed.exists());
            if (refreshed == null) return; // retain last good config while an external write is incomplete/malformed
            synchronized (DEFAULT_CACHE_MONITOR) {
                CachedConfig current = defaultCache;
                if (current != before && current != null
                        && !current.stamp().equals(observed)) return;
                defaultCache = new CachedConfig(refreshed, observed);
            }
        } catch (RuntimeException _) {
            // Best-effort background freshness must never affect UI reads.
        }
    }

    private static ObjectNode readValidObject(Path path, boolean exists) {
        if (!exists) return JsonUtils.getMapper().createObjectNode();
        try {
            JsonNode root = JsonUtils.readJson(path);
            return root != null && root.isObject() ? ((ObjectNode) root).deepCopy() : null;
        } catch (Exception _) {
            return null;
        }
    }

    private static FileStamp fileStamp(Path path) {
        try {
            if (!Files.isRegularFile(path)) return new FileStamp(false, 0L, 0L);
            return new FileStamp(true, FileUtils.modificationTimeMillis(path), Files.size(path));
        } catch (IOException _) {
            return new FileStamp(false, 0L, 0L);
        }
    }

    private static boolean isDefaultPath(Path path) {
        if (path == null) return false;
        return path.toAbsolutePath().normalize()
            .equals(ClaudePaths.GLOBAL_JSON.toAbsolutePath().normalize());
    }

    private static void writeThroughDefaultCache(Path path, ObjectNode root) {
        if (!isDefaultPath(path)) return;
        synchronized (DEFAULT_CACHE_MONITOR) {
            defaultCache = new CachedConfig(root.deepCopy(), fileStamp(path));
            startFreshnessWatcherLocked();
        }
    }

    /**
     * Reads the stored API key ({@code primaryApiKey} field, falling back to the
     * legacy {@code apiKey} field) from {@link ClaudePaths#GLOBAL_JSON}. This is the
     * single source of truth for stored-key resolution; {@code ApiKeyResolver} (core)
     * consumes it via a supplier so it stays storage-agnostic.
     */
    public static Optional<String> getApiKey() {
        JsonNode primary = readKey(ClaudePaths.GLOBAL_JSON, API_KEY_FIELD);
        if (primary != null && primary.isTextual() && !StringUtils.isBlank(primary.asText())) {
            return Optional.of(primary.asText());
        }
        JsonNode legacy = readKey(ClaudePaths.GLOBAL_JSON, LEGACY_API_KEY_FIELD);
        if (legacy != null && legacy.isTextual() && !StringUtils.isBlank(legacy.asText())) {
            return Optional.of(legacy.asText());
        }
        return Optional.empty();
    }

    /** Reads a complex (object/array) value as a raw {@link JsonNode} from the given path, or null. */
    public static JsonNode getNode(Path path, String key) {
        return readKey(path, key);
    }


    public static Map<String, String> getEnvironment() {
        JsonNode env = readKey(ClaudePaths.GLOBAL_JSON, "env");
        if (env == null || !env.isObject()) return Map.of();
        Map<String, String> values = new LinkedHashMap<>();
        env.fields().forEachRemaining(entry -> {
            values.put(entry.getKey(), javascriptString(entry.getValue(), false));
        });
        return Collections.unmodifiableMap(values);
    }

    /** String conversion with recursive comma-joining for array elements. */
    private static String javascriptString(JsonNode value, boolean arrayElement) {
        if (value == null || value.isNull()) return arrayElement ? "" : "null";
        if (value.isTextual()) return value.asText();
        if (value.isBoolean()) return Boolean.toString(value.asBoolean());
        if (value.isNumber()) return value.asText();
        if (value.isArray()) {
            List<String> parts = new ArrayList<>();
            value.forEach(item -> parts.add(javascriptString(item, true)));
            return String.join(",", parts);
        }
        return "[object Object]";
    }

    public static Map<String, Double> getSkillUsageScores() {
        return getSkillUsageScores(ClaudePaths.GLOBAL_JSON, System.currentTimeMillis());
    }


    public static Map<String, Double> getSkillUsageScores(Path path, long nowEpochMs) {
        JsonNode usage = readKey(path, "skillUsage");
        if (usage == null || !usage.isObject()) return Map.of();
        Map<String, Double> scores = new LinkedHashMap<>();
        usage.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            JsonNode countNode = value == null ? null : value.get("usageCount");
            JsonNode lastUsedNode = value == null ? null : value.get("lastUsedAt");
            if (countNode == null || !countNode.isNumber()
                    || lastUsedNode == null || !lastUsedNode.isNumber()) return;
            double usageCount = countNode.asDouble();
            long lastUsedAt = lastUsedNode.asLong();
            if (!Double.isFinite(usageCount) || usageCount < 0 || lastUsedAt <= 0) return;
            double ageDays = Math.max(0.0, (nowEpochMs - lastUsedAt) / 86_400_000.0);
            double decay = Math.pow(0.5, ageDays / 7.0);
            scores.put(entry.getKey(), usageCount * Math.max(decay, 0.1));
        });
        return Collections.unmodifiableMap(scores);
    }

    /** Sets {@code key} to {@code value} in {@link ClaudePaths#GLOBAL_JSON}, or removes it when {@code value} is {@code null}. */
    public static void set(String key, Object value) {
        set(ClaudePaths.GLOBAL_JSON, key, value);
    }

    /**
     * Sets {@code key} to {@code value} (any Jackson-serializable value), or removes
     * it when {@code value} is {@code null}. Read-modify-write over the whole file,
     * preserving unrelated keys.
     */
    public static void set(Path path, String key, Object value) {
        synchronized (WRITE_MONITOR) {
            try {
                Files.createDirectories(path.getParent());
                Map<String, Object> root = new LinkedHashMap<>();
                if (Files.isReadable(path)) {
                    JsonNode existing = JsonUtils.readJson(path);
                    if (existing != null && existing.isObject()) {
                        existing.fields().forEachRemaining(e -> root.put(e.getKey(), e.getValue()));
                    }
                }
                if (value == null) {
                    if (!root.containsKey(key)) {
                        return;
                    }
                    root.remove(key);
                } else {
                    JsonNode updatedValue = JsonUtils.getMapper().valueToTree(value);
                    Object currentValue = root.get(key);
                    if (currentValue instanceof JsonNode currentNode
                            && currentNode.equals(updatedValue)) {
                        return;
                    }
                    root.put(key, value);
                }
                ObjectNode node = (ObjectNode) JsonUtils.getMapper().valueToTree(root);
                writeAtomicLocked(path, node);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to write " + path, e);
            }
        }
    }

    /** Deep-updates one {@code projects[projectKey]} entry while preserving all siblings. */
    public static void updateProjectEntry(
            Path path, String projectKey, UnaryOperator<ObjectNode> updater) {
        synchronized (WRITE_MONITOR) {
            try {
                Files.createDirectories(path.getParent());
                Path lockPath = path.resolveSibling(path.getFileName() + ".lock");
                try (LockHolder ignored = lock(lockPath)) {
                    ObjectNode root = JsonUtils.getMapper().createObjectNode();
                    if (Files.isRegularFile(path)) {
                        JsonNode existing = JsonUtils.readJson(path);
                        if (!(existing instanceof ObjectNode object)) {
                            throw new IOException("Global config is not a JSON object: " + path);
                        }
                        root = object.deepCopy();
                    }
                    JsonNode currentProjects = root.get("projects");
                    ObjectNode projects = currentProjects instanceof ObjectNode object
                        ? object.deepCopy() : JsonUtils.getMapper().createObjectNode();
                    JsonNode currentEntry = projects.get(projectKey);
                    ObjectNode entry = currentEntry instanceof ObjectNode object
                        ? object.deepCopy() : JsonUtils.getMapper().createObjectNode();
                    projects.set(projectKey, updater.apply(entry));
                    root.set("projects", projects);
                    ObjectNode updatedRoot = root;
                    FileUtils.atomicReplace(path,
                        temp -> JsonUtils.writeJson(temp, updatedRoot, true));
                    InternalWrites.markInternalWrite(path);
                    writeThroughDefaultCache(path, root);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to update project config " + path, e);
            }
        }
    }


    public static void writeAtomicLocked(Path file, ObjectNode root) throws IOException {
        Files.createDirectories(file.getParent());
        try (LockHolder ignored = lock(file.resolveSibling(file.getFileName() + ".lock"))) {
            FileUtils.atomicReplace(file, temp -> JsonUtils.writeJson(temp, root, true));
        }
        InternalWrites.markInternalWrite(file);
        writeThroughDefaultCache(file, root);
    }

    /**
     * Acquires an exclusive lock file, retrying for a few seconds.
     */
    private static LockHolder lock(Path lockPath) throws IOException {
        long deadline = System.currentTimeMillis() + 3000;
        Exception last = null;
        while (System.currentTimeMillis() < deadline) {
            FileChannel ch = null;
            try {
                ch = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
                FileLock lk = ch.tryLock();
                if (lk != null) {
                    return new LockHolder(ch, lk);
                }
                ch.close();
            } catch (OverlappingFileLockException e) {
                last = e;
                if (ch != null) {
                    try {
                        ch.close();
                    } catch (IOException _) {
                        // best-effort
                    }
                }
            }
            try {

// provides the same companion-lock exclusion. Java's FileChannel.lock has
// no timeout parameter, and tryLock exposes no release notification that
                // could replace polling while retaining this port's explicit three-second
                // acquisition window. This bounded 40 ms sleep is intentional contention
                // backoff, not an unbounded busy-spin; interruption is restored and stops
                // acquisition below.
                //noinspection BusyWait
                Thread.sleep(40);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IOException("Could not acquire lock for " + lockPath, last);
    }

    /**
     * Holds the lock channel open for the lock's lifetime.
     */
    private record LockHolder(FileChannel channel, FileLock lock) implements AutoCloseable {

        @Override
        public void close() throws IOException {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }
}
