package com.claudecode.mcp.oauth;


import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;


import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.config.ClaudePaths;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * macOS-only {@link SecureStorage} that persists credentials in the login keychain via the {@code
 * security(1)} CLI.
 */
public final class KeychainSecureStorage implements SecureStorage {

    private static final Logger LOG = LoggerFactory.getLogger(KeychainSecureStorage.class);
    static final long CACHE_TTL_MS = 30_000;


    // Anything longer must go through argv, or `security -i` truncates mid-line
    // and silently leaves the previous entry intact.
    private static final int SECURITY_STDIN_LINE_LIMIT = 4096 - 64;

    private final CommandRunner runner;
    private final String serviceName;
    private final String username;

    private final AtomicReference<CacheEntry> cache = new AtomicReference<>(CacheEntry.EMPTY);

    public KeychainSecureStorage() {
        this(new SecurityCliRunner(),
            defaultServiceName(),
            defaultUsername());
    }

    KeychainSecureStorage(CommandRunner runner, String serviceName, String username) {
        this.runner = runner;
        this.serviceName = serviceName;
        this.username = username;
    }

    @Override
    public String name() { return "keychain"; }

    @Override
    public Optional<SecureStorageData> read() {
        CacheEntry prev = cache.get();
        if (prev.freshEnough()) return Optional.ofNullable(prev.data);

        CommandResult result = runner.run(
            "security", "find-generic-password", "-a", username, "-w", "-s", serviceName);
        if (result.exitCode == 0 && result.stdout != null && !StringUtils.isBlank(result.stdout)) {
            SecureStorageData decoded = decodeStdout(result.stdout);
            cache.set(new CacheEntry(decoded, System.currentTimeMillis()));
            return Optional.ofNullable(decoded);
        }
        // Stale-while-error: keep serving previous data if the spawn just failed

        if (prev.data != null) {
            LOG.warn("[keychain] read failed (exit={}); serving stale cache", result.exitCode);
            cache.set(new CacheEntry(prev.data, System.currentTimeMillis()));
            return Optional.of(prev.data);
        }
        cache.set(new CacheEntry(null, System.currentTimeMillis()));
        return Optional.empty();
    }

    @Override
    public Optional<String> update(SecureStorageData data) {
        clearCache();
        try {
            byte[] jsonBytes = JsonUtils.toPrettyJson(
                    SecureStorageCodec.encode(data, JsonUtils.getMapper().createObjectNode()))
                    .getBytes(StandardCharsets.UTF_8);
            String hex = toHex(jsonBytes);

            String command = "add-generic-password -U -a \"" + username
                + "\" -s \"" + serviceName + "\" -X \"" + hex + "\"\n";

            CommandResult result;
            if (command.length() <= SECURITY_STDIN_LINE_LIMIT) {
                // stdin path: process monitors see only "security -i", never the token.
                result = runner.runWithStdin(command,
                    "security", "-i");
            } else {
                LOG.warn("Keychain payload ({}B JSON) exceeds security -i stdin limit; using argv",
                    jsonBytes.length);
                result = runner.run("security",
                    "add-generic-password", "-U", "-a", username, "-s", serviceName, "-X", hex);
            }

            if (result.exitCode != 0) {
                LOG.warn("[keychain] update failed exit={} stderr={}", result.exitCode, result.stderr);
                throw new RuntimeException("security add-generic-password failed: " + result.stderr);
            }
            cache.set(new CacheEntry(data, System.currentTimeMillis()));
            return Optional.empty();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Keychain update failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean delete() {
        clearCache();
        CommandResult result = runner.run(
            "security", "delete-generic-password", "-a", username, "-s", serviceName);
        return result.exitCode == 0 || Strings.CS.contains(result.stderr, "could not be found");
    }

    /**
     * Explicit invalidation. Called from update/delete internally, exposed
     * for cross-process invalidation triggers (e.g. another CC instance's
     * {@code /login} completes and this instance needs to notice).
     */
    public void clearCache() {
        cache.set(CacheEntry.EMPTY);
    }

    private SecureStorageData decodeStdout(String stdout) {
        try {
            String trimmed = stdout.trim();
            String jsonText;
            // Historic entries were plain JSON; recent ones are hex-encoded. Detect
            // and handle both — a leading '{' means JSON, else assume hex.
            if (Strings.CS.startsWith(trimmed, "{")) {
                jsonText = trimmed;
            } else {
                jsonText = new String(fromHex(trimmed), StandardCharsets.UTF_8);
            }
            JsonNode node = JsonUtils.getMapper().readTree(jsonText);
            return SecureStorageCodec.decode(node);
        } catch (Exception e) {
            LOG.warn("Failed to decode keychain payload: {}", e.getMessage());
            return null;
        }
    }

    // ── Naming helpers ──────────────────────────────────────────────────────

    static String defaultServiceName() {

        //   "Claude Code" + OAUTH_FILE_SUFFIX + "-credentials" [+ dirhash]

        String base = "Claude Code";
        String credentials = "-credentials";
        String dirHash = "";
        String override = System.getenv("CLAUDE_CONFIG_DIR");
        if (StringUtils.isNotBlank(override)) {

            // rather than the raw environment variable bytes.
            dirHash = "-" + sha256Hex(ClaudePaths.CLAUDE_HOME.toString()).substring(0, 8);
        }
        return base + credentials + dirHash;
    }

    static String defaultUsername() {
        String u = System.getenv("USER");
        if (StringUtils.isNotBlank(u)) return u;
        u = System.getProperty("user.name");
        return (StringUtils.isNotBlank(u)) ? u : "claude-code-user";
    }

    private static String sha256Hex(String s) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return toHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] fromHex(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                + Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }

    // ── Cache entry ─────────────────────────────────────────────────────────

    private record CacheEntry(SecureStorageData data, long cachedAt) {
        static final CacheEntry EMPTY = new CacheEntry(null, 0);
        boolean freshEnough() {
            return cachedAt != 0 && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS;
        }
    }

    // ── Command runner (test seam) ──────────────────────────────────────────

    /**
     * Test seam so the storage can be exercised without spawning
     * {@code security(1)}. Real subprocess execution lives in
     * {@link SecurityCliRunner}.
     */
    interface CommandRunner {
        CommandResult run(String... argv);
        CommandResult runWithStdin(String stdin, String... argv);
    }

    record CommandResult(int exitCode, String stdout, String stderr) {}

    /**
     * Runs {@code security(1)} via {@link ProcessBuilder}. Blocks the calling
     * thread; caller is expected to be off the request-hot path (or hitting
     * the cache).
     */
    static final class SecurityCliRunner implements CommandRunner {
        @Override
        public CommandResult run(String... argv) {
            try {
                Process p = new ProcessBuilder(argv).redirectErrorStream(false).start();
                p.getOutputStream().close();
                String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                int code = p.waitFor();
                return new CommandResult(code, stdout, stderr);
            } catch (Exception e) {
                LOG.debug("security spawn failed: {}", e.getMessage());
                return new CommandResult(-1, "", e.getMessage());
            }
        }

        @Override
        public CommandResult runWithStdin(String stdin, String... argv) {
            try {
                Process p = new ProcessBuilder(argv).redirectErrorStream(false).start();
                try (OutputStream out = p.getOutputStream()) {
                    out.write(stdin.getBytes(StandardCharsets.UTF_8));
                }
                String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                int code = p.waitFor();
                return new CommandResult(code, stdout, stderr);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                LOG.debug("security stdin spawn failed: {}", e.getMessage());
                return new CommandResult(-1, "", e.getMessage());
            }
        }
    }
}
