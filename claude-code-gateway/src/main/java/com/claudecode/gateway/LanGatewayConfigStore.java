package com.claudecode.gateway;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.io.FileUtils;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Atomic, {@code 0600}-permissioned persistence for {@code $CLAUDE_CONFIG_DIR/gateway.json}:
 * LAN model-sharing settings, named device keys ({@code sk-poco-...}), and cumulative per-key
 * token usage.
 *
 * <p>Uses Jackson's tree model ({@link ObjectNode}) directly so GraalVM native images require no
 * additional reflection registrations.
 */
@Explanation("Persists LAN model gateway settings and per-device sk-poco-... keys")
public final class LanGatewayConfigStore {

    public static final String KEY_PREFIX = "sk-poco-";
    private static final int FORMAT_VERSION = 1;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    /** Immutable snapshot of the persisted LAN gateway configuration. */
    public record LanGatewayConfig(
            boolean enabled,
            boolean exposeDefaultProvider,
            String tierStandInModel,
            boolean preventMacSleep,
            List<String> allowedCidrs,
            List<GatewayKey> keys) {

        public static LanGatewayConfig defaults() {
            return new LanGatewayConfig(false, false, "", true, List.of(), List.of());
        }
    }

    /** One named device API key allowed to call the stateless LAN model proxy. */
    public record GatewayKey(
            String id,
            String name,
            String secret,
            boolean enabled,
            List<String> allowedModels,
            String createdAt,
            String lastUsedAt,
            long totalRequests,
            long inputTokens,
            long outputTokens) {

        public String maskedSecret() {
            if (secret == null || secret.length() < 12) return "sk-poco-****";
            return KEY_PREFIX + "…" + secret.substring(secret.length() - 4);
        }

        public boolean allowsModel(String modelName) {
            if (allowedModels == null || allowedModels.isEmpty() || allowedModels.contains("*")) {
                return true;
            }
            if (StringUtils.isBlank(modelName)) return false;
            return allowedModels.stream().anyMatch(m -> Strings.CS.equals(m, modelName));
        }
    }

    private final Path file;
    private LanGatewayConfig cached;

    public LanGatewayConfigStore(Path file) {
        this.file = file == null ? null : file.toAbsolutePath().normalize();
    }

    /** Ephemeral store backed purely by memory, used when persistence is not wired (e.g. unit tests). */
    public static LanGatewayConfigStore inMemory() {
        LanGatewayConfigStore store = new LanGatewayConfigStore(null);
        store.cached = LanGatewayConfig.defaults();
        return store;
    }

    public Path file() {
        return file;
    }

    public synchronized LanGatewayConfig load() {
        if (cached != null) return cached;
        if (file == null || !Files.exists(file)) {
            cached = LanGatewayConfig.defaults();
            return cached;
        }
        try {
            JsonNode root = JsonUtils.getMapper().readTree(file.toFile());
            cached = parseConfig(root);
            return cached;
        } catch (Exception _) {
            cached = LanGatewayConfig.defaults();
            return cached;
        }
    }

    /** Updates top-level LAN gateway settings; auto-creates a default key on first enable if empty. */
    public synchronized LanGatewayConfig updateSettings(
            Boolean enabled,
            Boolean exposeDefaultProvider,
            String tierStandInModel,
            Boolean preventMacSleep,
            List<String> allowedCidrs) {
        LanGatewayConfig cur = load();
        boolean nextEnabled = enabled != null ? enabled : cur.enabled();
        boolean nextExposeDefault = exposeDefaultProvider != null
            ? exposeDefaultProvider : cur.exposeDefaultProvider();
        String nextStandIn = tierStandInModel != null
            ? tierStandInModel.trim() : cur.tierStandInModel();
        boolean nextPreventSleep = preventMacSleep != null
            ? preventMacSleep : cur.preventMacSleep();
        List<String> nextCidrs = allowedCidrs != null
            ? sanitizeStringList(allowedCidrs) : cur.allowedCidrs();
        List<GatewayKey> nextKeys = new ArrayList<>(cur.keys());
        if (nextEnabled && nextKeys.isEmpty()) {
            nextKeys.add(mintNewKey("默认局域网密钥", List.of()));
        }
        LanGatewayConfig updated = new LanGatewayConfig(
            nextEnabled, nextExposeDefault, nextStandIn, nextPreventSleep,
            List.copyOf(nextCidrs), List.copyOf(nextKeys));
        saveInternal(updated);
        return updated;
    }

    /** Mints and persists a new {@code sk-poco-...} device key. */
    public synchronized GatewayKey createKey(String name, List<String> allowedModels) {
        String cleanName = StringUtils.defaultIfBlank(StringUtils.trimToNull(name), "未命名设备");
        LanGatewayConfig cur = load();
        GatewayKey created = mintNewKey(cleanName, sanitizeStringList(allowedModels));
        List<GatewayKey> nextKeys = new ArrayList<>(cur.keys());
        nextKeys.add(created);
        saveInternal(new LanGatewayConfig(
            cur.enabled(), cur.exposeDefaultProvider(), cur.tierStandInModel(),
            cur.preventMacSleep(), cur.allowedCidrs(), List.copyOf(nextKeys)));
        return created;
    }

    /** Updates a key's name, enabled state, or allowed model list. */
    public synchronized Optional<GatewayKey> updateKey(
            String keyId, String name, Boolean enabled, List<String> allowedModels) {
        if (StringUtils.isBlank(keyId)) return Optional.empty();
        LanGatewayConfig cur = load();
        List<GatewayKey> nextKeys = new ArrayList<>(cur.keys().size());
        GatewayKey updatedKey = null;
        for (GatewayKey k : cur.keys()) {
            if (Strings.CS.equals(k.id(), keyId)) {
                String nextName = StringUtils.isNotBlank(name)
                    ? name.trim() : k.name();
                boolean nextEnabled = enabled != null ? enabled : k.enabled();
                List<String> nextModels = allowedModels != null
                    ? sanitizeStringList(allowedModels) : k.allowedModels();
                updatedKey = new GatewayKey(
                    k.id(), nextName, k.secret(), nextEnabled, nextModels,
                    k.createdAt(), k.lastUsedAt(), k.totalRequests(),
                    k.inputTokens(), k.outputTokens());
                nextKeys.add(updatedKey);
            } else {
                nextKeys.add(k);
            }
        }
        if (updatedKey == null) return Optional.empty();
        saveInternal(new LanGatewayConfig(
            cur.enabled(), cur.exposeDefaultProvider(), cur.tierStandInModel(),
            cur.preventMacSleep(), cur.allowedCidrs(), List.copyOf(nextKeys)));
        return Optional.of(updatedKey);
    }

    /** Deletes a key by ID. */
    public synchronized boolean deleteKey(String keyId) {
        if (StringUtils.isBlank(keyId)) return false;
        LanGatewayConfig cur = load();
        List<GatewayKey> remaining = cur.keys().stream()
            .filter(k -> !Strings.CS.equals(k.id(), keyId))
            .toList();
        if (remaining.size() == cur.keys().size()) return false;
        saveInternal(new LanGatewayConfig(
            cur.enabled(), cur.exposeDefaultProvider(), cur.tierStandInModel(),
            cur.preventMacSleep(), cur.allowedCidrs(), remaining));
        return true;
    }

    /**
     * Constant-time lookup of a candidate {@code sk-poco-...} secret. Returns the matching key
     * (whether enabled or disabled so callers can distinguish disabled keys from unknown ones).
     */
    public synchronized Optional<GatewayKey> findBySecret(String candidateSecret) {
        if (!looksLikeGatewayKey(candidateSecret)) return Optional.empty();
        byte[] candidateBytes = candidateSecret.getBytes(StandardCharsets.UTF_8);
        GatewayKey match = null;
        for (GatewayKey key : load().keys()) {
            byte[] storedBytes = key.secret().getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(candidateBytes, storedBytes)) {
                match = key;
            }
        }
        return Optional.ofNullable(match);
    }

    /** Records one completed proxy request's token usage against {@code keyId}. */
    public synchronized void recordUsage(String keyId, long inputTokens, long outputTokens) {
        if (StringUtils.isBlank(keyId)) return;
        LanGatewayConfig cur = load();
        List<GatewayKey> nextKeys = new ArrayList<>(cur.keys().size());
        boolean changed = false;
        String now = Instant.now().toString();
        for (GatewayKey k : cur.keys()) {
            if (Strings.CS.equals(k.id(), keyId)) {
                nextKeys.add(new GatewayKey(
                    k.id(), k.name(), k.secret(), k.enabled(), k.allowedModels(),
                    k.createdAt(), now,
                    k.totalRequests() + 1L,
                    k.inputTokens() + Math.max(0L, inputTokens),
                    k.outputTokens() + Math.max(0L, outputTokens)));
                changed = true;
            } else {
                nextKeys.add(k);
            }
        }
        if (changed) {
            saveInternal(new LanGatewayConfig(
                cur.enabled(), cur.exposeDefaultProvider(), cur.tierStandInModel(),
                cur.preventMacSleep(), cur.allowedCidrs(), List.copyOf(nextKeys)));
        }
    }

    public static boolean looksLikeGatewayKey(String value) {
        return value != null && value.length() >= KEY_PREFIX.length() + 16
            && Strings.CS.startsWith(value, KEY_PREFIX);
    }

    private static GatewayKey mintNewKey(String name, List<String> allowedModels) {
        byte[] randomBytes = new byte[20];
        RANDOM.nextBytes(randomBytes);
        String secret = KEY_PREFIX + HexFormat.of().formatHex(randomBytes);
        String id = "gk_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return new GatewayKey(
            id, name, secret, true, List.copyOf(allowedModels),
            Instant.now().toString(), null, 0L, 0L, 0L);
    }

    private static List<String> sanitizeStringList(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        Set<String> cleaned = new LinkedHashSet<>();
        for (String item : raw) {
            if (StringUtils.isNotBlank(item)) cleaned.add(item.trim());
        }
        return List.copyOf(cleaned);
    }

    private static LanGatewayConfig parseConfig(JsonNode root) {
        if (root == null || !root.isObject()) return LanGatewayConfig.defaults();
        boolean enabled = root.path("enabled").asBoolean(false);
        boolean exposeDefault = root.path("exposeDefaultProvider").asBoolean(false);
        String standIn = root.path("tierStandInModel").asText("").trim();
        boolean preventSleep = root.path("preventMacSleep").asBoolean(true);
        List<String> cidrs = new ArrayList<>();
        if (root.path("allowedCidrs").isArray()) {
            for (JsonNode c : root.path("allowedCidrs")) {
                if (c.isTextual() && StringUtils.isNotBlank(c.asText())) {
                    cidrs.add(c.asText().trim());
                }
            }
        }
        List<GatewayKey> keys = new ArrayList<>();
        if (root.path("keys").isArray()) {
            for (JsonNode k : root.path("keys")) {
                String id = k.path("id").asText("").trim();
                String secret = k.path("secret").asText("").trim();
                if (id.isEmpty() || !looksLikeGatewayKey(secret)) continue;
                String name = StringUtils.defaultIfBlank(k.path("name").asText("").trim(), "设备密钥");
                boolean keyEnabled = k.path("enabled").asBoolean(true);
                List<String> models = new ArrayList<>();
                if (k.path("allowedModels").isArray()) {
                    for (JsonNode m : k.path("allowedModels")) {
                        if (m.isTextual() && StringUtils.isNotBlank(m.asText())) {
                            models.add(m.asText().trim());
                        }
                    }
                }
                String createdAt = k.path("createdAt").asText("");
                String lastUsedAt = k.hasNonNull("lastUsedAt") ? k.path("lastUsedAt").asText(null) : null;
                long totalReqs = k.path("totalRequests").asLong(0L);
                long inTok = k.path("inputTokens").asLong(0L);
                long outTok = k.path("outputTokens").asLong(0L);
                keys.add(new GatewayKey(
                    id, name, secret, keyEnabled, List.copyOf(models),
                    createdAt, lastUsedAt, totalReqs, inTok, outTok));
            }
        }
        return new LanGatewayConfig(
            enabled, exposeDefault, standIn, preventSleep, List.copyOf(cidrs), List.copyOf(keys));
    }

    private void saveInternal(LanGatewayConfig next) {
        cached = next;
        ObjectNode root = JsonUtils.getMapper().createObjectNode();
        root.put("version", FORMAT_VERSION);
        root.put("enabled", next.enabled());
        root.put("exposeDefaultProvider", next.exposeDefaultProvider());
        root.put("tierStandInModel", next.tierStandInModel());
        root.put("preventMacSleep", next.preventMacSleep());
        ArrayNode cidrs = root.putArray("allowedCidrs");
        next.allowedCidrs().forEach(cidrs::add);
        ArrayNode keys = root.putArray("keys");
        for (GatewayKey k : next.keys()) {
            ObjectNode kn = keys.addObject();
            kn.put("id", k.id());
            kn.put("name", k.name());
            kn.put("secret", k.secret());
            kn.put("enabled", k.enabled());
            ArrayNode mods = kn.putArray("allowedModels");
            k.allowedModels().forEach(mods::add);
            kn.put("createdAt", StringUtils.defaultIfBlank(k.createdAt(), Instant.now().toString()));
            if (k.lastUsedAt() != null) kn.put("lastUsedAt", k.lastUsedAt());
            else kn.putNull("lastUsedAt");
            kn.put("totalRequests", k.totalRequests());
            kn.put("inputTokens", k.inputTokens());
            kn.put("outputTokens", k.outputTokens());
        }

        if (file == null || file.getParent() == null) return;
        try {
            FileUtils.atomicReplace(file, temp -> {
                JsonUtils.getMapper().writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), root);
                applyOwnerOnlyPermissions(temp);
            });
            applyOwnerOnlyPermissions(file);
        } catch (Exception _) {
            // Keep in-memory state if disk write fails on read-only test fs.
        }
    }

    private static void applyOwnerOnlyPermissions(Path target) {
        try {
            Files.setPosixFilePermissions(target, OWNER_ONLY);
        } catch (UnsupportedOperationException | IOException _) {
            // Windows / non-POSIX filesystems rely on user profile directory ACLs.
        }
    }
}
