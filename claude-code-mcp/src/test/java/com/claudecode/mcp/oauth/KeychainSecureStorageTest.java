package com.claudecode.mcp.oauth;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit-level coverage of {@link KeychainSecureStorage} using an in-memory
 * fake {@link KeychainSecureStorage.CommandRunner}. Real keychain integration
 * is out of scope for CI (would require a macOS runner + login keychain
 * unlock); the CLI-runner seam is enough to prove the state machine.
 */
class KeychainSecureStorageTest {

    // ── Fake CommandRunner ──────────────────────────────────────────────────

    static final class FakeRunner implements KeychainSecureStorage.CommandRunner {
        String storedHex;                 // simulated keychain entry
        int   nextExitCodeOverride = -1;  // -1 = auto
        boolean simulateReadFailure = false;
        final List<String[]> runCalls = new ArrayList<>();
        final List<String>   stdinPayloads = new ArrayList<>();

        @Override
        public KeychainSecureStorage.CommandResult run(String... argv) {
            runCalls.add(argv);
            String verb = argv.length > 1 ? argv[1] : "";
            return switch (verb) {
                case "find-generic-password" -> {
                    if (simulateReadFailure) {
                        yield new KeychainSecureStorage.CommandResult(1, "", "read failed");
                    }
                    if (storedHex == null) {
                        yield new KeychainSecureStorage.CommandResult(44, "", "not found");
                    }
                    yield new KeychainSecureStorage.CommandResult(0, storedHex + "\n", "");
                }
                case "add-generic-password" -> {
                    // argv path — pull hex from -X
                    for (int i = 0; i < argv.length - 1; i++) {
                        if (Strings.CS.equals("-X", argv[i])) {
                            storedHex = argv[i + 1];
                            break;
                        }
                    }
                    yield new KeychainSecureStorage.CommandResult(0, "", "");
                }
                case "delete-generic-password" -> {
                    if (storedHex == null) {
                        yield new KeychainSecureStorage.CommandResult(44, "", "could not be found");
                    }
                    storedHex = null;
                    yield new KeychainSecureStorage.CommandResult(0, "", "");
                }
                default -> new KeychainSecureStorage.CommandResult(1, "", "unknown verb: " + verb);
            };
        }

        @Override
        public KeychainSecureStorage.CommandResult runWithStdin(String stdin, String... argv) {
            stdinPayloads.add(stdin);
            // Extract hex from the "add-generic-password ... -X \"HEX\"" line.
            int idx = stdin.indexOf("-X \"");
            if (idx >= 0) {
                int start = idx + 4;
                int end = stdin.indexOf('"', start);
                if (end > start) storedHex = stdin.substring(start, end);
            }
            return new KeychainSecureStorage.CommandResult(0, "", "");
        }
    }

    // ── Tests ───────────────────────────────────────────────────────────────

    @Test
    void update_thenRead_roundTripsData() {
        FakeRunner runner = new FakeRunner();
        KeychainSecureStorage storage = new KeychainSecureStorage(runner, "svc", "user");

        var entry = new SecureStorageData.McpOAuthEntry(
            "github", "https://api.githubcopilot.com/mcp/",
            "cid", null, "at", "rt", 1_000L, "https://as/token", "read");
        storage.update(new SecureStorageData(Map.of("k", entry), null, null));

        // First call goes through cache — we cleared it before update but
        // update itself re-primes the cache with the same data. Force a cache
        // miss to hit the CLI path.
        storage.clearCache();

        Optional<SecureStorageData> read = storage.read();
        assertTrue(read.isPresent());
        assertEquals("at", read.get().mcpOAuth().get("k").accessToken());
        // First run() call was the update's cache-clear check, then read() spawned find-generic-password
        assertTrue(runner.runCalls.stream().anyMatch(a -> a.length > 1 && Strings.CS.equals("find-generic-password", a[1])));
    }

    @Test
    void read_isCached_within30sTtl() {
        FakeRunner runner = new FakeRunner();
        // Prime the store with something so read() has a value to cache.
        KeychainSecureStorage storage = new KeychainSecureStorage(runner, "svc", "user");
        storage.update(new SecureStorageData(Map.of("k",
            new SecureStorageData.McpOAuthEntry("srv", null, null, null, "at", null, 0L, null, null)),
            null, null));

        int callsBeforeRead = runner.runCalls.size();
        storage.read();
        storage.read();
        storage.read();
        // Two of the three reads should be served from cache — no additional
        // find-generic-password spawns beyond the first (if any).
        long finds = runner.runCalls.stream()
            .skip(callsBeforeRead)
            .filter(a -> a.length > 1 && Strings.CS.equals("find-generic-password", a[1]))
            .count();
        assertTrue(finds <= 1, "expected at most 1 find-generic-password after update, got " + finds);
    }

    @Test
    void read_stalesGracefullyOnTransientFailure() {
        FakeRunner runner = new FakeRunner();
        KeychainSecureStorage storage = new KeychainSecureStorage(runner, "svc", "user");
        // Load and then clear cache so next read hits CLI.
        var entry = new SecureStorageData.McpOAuthEntry(
            "srv", null, null, null, "goodToken", null, 0L, null, null);
        storage.update(new SecureStorageData(Map.of("k", entry), null, null));
        storage.clearCache();
        storage.read();  // Should populate cache with real data

        // Simulate transient failure on next spawn. Cache TTL still valid
        // (well within 30s) so this shouldn't even hit the runner.
        runner.simulateReadFailure = true;
        Optional<SecureStorageData> stillGood = storage.read();
        assertTrue(stillGood.isPresent());
        assertEquals("goodToken", stillGood.get().mcpOAuth().get("k").accessToken());
    }

    @Test
    void update_prefersStdinPath_forSmallPayloads() {
        FakeRunner runner = new FakeRunner();
        KeychainSecureStorage storage = new KeychainSecureStorage(runner, "svc", "user");
        var entry = new SecureStorageData.McpOAuthEntry(
            "srv", null, null, null, "at", null, 0L, null, null);
        storage.update(new SecureStorageData(Map.of("k", entry), null, null));

        assertFalse(runner.stdinPayloads.isEmpty(),
            "small payload should go through security -i stdin");
        assertTrue(Strings.CS.startsWith(runner.stdinPayloads.getFirst(), "add-generic-password -U"));
    }

    @Test
    void update_switchesToArgv_forOversizedPayloads() {
        FakeRunner runner = new FakeRunner();
        KeychainSecureStorage storage = new KeychainSecureStorage(runner, "svc", "user");

        // Build an entry so large that its hex-encoded form pushes past the
        // stdin line limit. Access token is a good vehicle — one field, no schema constraint.
        String bigToken = "a".repeat(3_000);
        var entry = new SecureStorageData.McpOAuthEntry(
            "srv", null, null, null, bigToken, null, 0L, null, null);
        storage.update(new SecureStorageData(Map.of("k", entry), null, null));

        boolean argvUsed = runner.runCalls.stream().anyMatch(a ->
            a.length > 1 && Strings.CS.equals("add-generic-password", a[1]));
        assertTrue(argvUsed, "oversized payload must fall back to argv (stdin buffer would truncate)");
    }

    @Test
    void delete_returnsTrue_whenEntryAlreadyMissing() {
        FakeRunner runner = new FakeRunner();
        KeychainSecureStorage storage = new KeychainSecureStorage(runner, "svc", "user");
        assertTrue(storage.delete(), "delete on empty keychain must be idempotent");
    }

    @Test
    void serviceName_includesCredentialsSuffix() {

        // Java's OAUTH_FILE_SUFFIX is empty since we don't ship channels — so we
        // expect "Claude Code-credentials" for the default config dir.
        String prev = System.getenv("CLAUDE_CONFIG_DIR");
        assumeNoConfigDir(prev);
        String actual = KeychainSecureStorage.defaultServiceName();
        assertEquals("Claude Code-credentials", actual);
    }

    private static void assumeNoConfigDir(String prev) {
        // The service-name test is only meaningful with the default config dir.
        // Skip if the developer's env has CLAUDE_CONFIG_DIR set.
        Assumptions.assumeTrue(StringUtils.isBlank(prev),
            "CLAUDE_CONFIG_DIR is set — service name test skipped");
    }
}
