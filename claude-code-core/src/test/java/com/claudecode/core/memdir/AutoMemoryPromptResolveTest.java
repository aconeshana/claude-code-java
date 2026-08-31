package com.claudecode.core.memdir;

import org.apache.commons.lang3.Strings;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link AutoMemoryPrompt#resolveAutoMemPath} — the shared resolver the memory writer and
 * the team-memory secret guard both use (so they never check divergent directories).
 */
class AutoMemoryPromptResolveTest {

    @AfterEach
    void resetInjectedDir() {
        // resolveAutoMemPath reads an injected static override; clear it so it
        // never leaks into neighbouring tests.
        AutoMemoryPrompt.setAutoMemoryDirectory(null);
    }

    @Test
    void shortCwd_noHashSuffix(@TempDir Path tmp) {
        Path memDir = AutoMemoryPrompt.resolveAutoMemPath(tmp);
        Path key = memDir.getParent().getFileName();
        String s = key.toString();
        // A short (untruncated) project key equals the sanitized cwd verbatim —
        // i.e. no hash suffix was appended.
        String expected = tmp.toAbsolutePath().toString().replaceAll("[^a-zA-Z0-9]", "-");
        assertEquals(expected, s);
    }

    @Test
    void longCwd_truncatesAndAppendsBase36Hash(@TempDir Path tmp) throws IOException {
        // 230 'a's → the sanitized full path exceeds MAX_SANITIZED_LENGTH (200)
        // once the /private/var/folders prefix is included. Kept under the
        // filesystem NAME_MAX (255 bytes) for the temp dir name itself.
        Path longCwd = Files.createTempDirectory(tmp, "a".repeat(230));
        Path memDir = AutoMemoryPrompt.resolveAutoMemPath(longCwd);
        Path key = memDir.getParent().getFileName();
        String s = key.toString();
        assertTrue(s.length() > 200, "expected truncated+hash key, got len=" + s.length());

        String expected = longCwd.toAbsolutePath().toString().replaceAll("[^a-zA-Z0-9]", "-");
        assertTrue(expected.length() > 200);
        // Truncated prefix must be exactly the first 200 sanitized chars, then a
        // single '-' separator, then a base-36 hash (digits + a-z, no '-').
        assertEquals(expected.substring(0, 200), s.substring(0, 200));
        String hashSuffix = s.substring(200);
        assertTrue(hashSuffix.matches("^-[0-9a-z]+$"), "hash suffix form: " + hashSuffix);
    }

    @Test
    void injectedDirectory_isNfcNormalizedAndTakesPrecedence() {
        // Decomposed "e" + combining acute (U+0301) must compose to "é" (U+00E9).
        AutoMemoryPrompt.setAutoMemoryDirectory("/tmp/cafe\u0301");
        Path memDir = AutoMemoryPrompt.resolveAutoMemPath(Path.of("/ignored/cwd"));
        String str = memDir.toString();
        assertTrue(Normalizer.isNormalized(str, Normalizer.Form.NFC),
            "resolved path must be NFC-normalized: " + str);
        assertFalse(Strings.CS.contains(str, "\u0301"),
            "combining mark must be composed away: " + str);
        // resolveAutoMemPath returns the dir WITH a trailing separator (matching

        // the composed café form.
        assertTrue(Strings.CS.endsWith(stripTrailingSep(str), "caf\u00e9"), "composed form expected: " + str);
    }

    @Test
    void injectedDirectory_overridesGitRootComputation(@TempDir Path tmp) {
        AutoMemoryPrompt.setAutoMemoryDirectory(tmp.toString());
        Path memDir = AutoMemoryPrompt.resolveAutoMemPath(Path.of("/some/other/cwd"));

// getAutoMemPathSetting returning the full validated path. The resolver
        // appends a trailing separator (".../memory/"), so strip it before comparing.
        assertEquals(Normalizer.normalize(tmp.toString(), Normalizer.Form.NFC),
            stripTrailingSep(memDir.toString()));
    }

    // ── CLAUDE_COWORK_MEMORY_PATH_OVERRIDE validation (fix 1) ────────────────

    @Test
    void validateMemoryOverride_rejectsRelative() {
        assertNull(AutoMemoryPrompt.validateMemoryOverride("../evil"));
        assertNull(AutoMemoryPrompt.validateMemoryOverride("relative/dir"));
    }

    @Test
    void validateMemoryOverride_rejectsRootAndNearRoot() {
        assertNull(AutoMemoryPrompt.validateMemoryOverride("/"));
        assertNull(AutoMemoryPrompt.validateMemoryOverride("/a"));
    }

    @Test
    void validateMemoryOverride_rejectsWindowsDriveRoot() {
        assertNull(AutoMemoryPrompt.validateMemoryOverride("C:"));
    }

    @Test
    void validateMemoryOverride_rejectsUnc() {
        assertNull(AutoMemoryPrompt.validateMemoryOverride("\\\\server\\share"));
        assertNull(AutoMemoryPrompt.validateMemoryOverride("//server/share"));
    }

    @Test
    void validateMemoryOverride_rejectsNul() {
        assertNull(AutoMemoryPrompt.validateMemoryOverride("/foo\u0000bar"));
    }

    @Test
    void validateMemoryOverride_acceptsAndStripsTrailingSep() {
        assertEquals("/tmp/mem", AutoMemoryPrompt.validateMemoryOverride("/tmp/mem"));
        assertEquals("/tmp/mem", AutoMemoryPrompt.validateMemoryOverride("/tmp/mem/"));
        assertEquals("/tmp/mem", AutoMemoryPrompt.validateMemoryOverride("/tmp/mem\\"));
    }

    @Test
    void envOverride_validTakesPrecedence() {
        Assumptions.assumeTrue(trySetEnv("CLAUDE_COWORK_MEMORY_PATH_OVERRIDE", "/tmp/override_mem"));
        try {
            Path memDir = AutoMemoryPrompt.resolveAutoMemPath(Path.of("/some/cwd"));
            // Override wins over git-root computation and is NFC-normalized. The
            // resolver appends a trailing separator, so strip it before comparing.
            assertEquals(Normalizer.normalize("/tmp/override_mem", Normalizer.Form.NFC),
                stripTrailingSep(memDir.toString()));
        } finally {
            trySetEnv("CLAUDE_COWORK_MEMORY_PATH_OVERRIDE", null);
        }
    }

    @Test
    void envOverride_invalidIgnoredFallsThroughToGitRoot(@TempDir Path tmp) {
        Assumptions.assumeTrue(trySetEnv("CLAUDE_COWORK_MEMORY_PATH_OVERRIDE", "../evil"));
        try {
            // Malformed override must be ignored, not used → resolves via cwd.
            Path memDir = AutoMemoryPrompt.resolveAutoMemPath(tmp);
            assertFalse(Strings.CS.contains(memDir.toString(), "evil"),
                "invalid override must not be used: " + memDir);
            String expected = tmp.toAbsolutePath().toString().replaceAll("[^a-zA-Z0-9]", "-");
            assertEquals(expected, memDir.getParent().getFileName().toString());
        } finally {
            trySetEnv("CLAUDE_COWORK_MEMORY_PATH_OVERRIDE", null);
        }
    }

    /** Strips trailing path separators so assertions can compare the directory
     *  body without the trailing '/' the resolver appends. */
    private static String stripTrailingSep(String s) {
        return s.replaceAll("[/\\\\]+$", "");
    }

    /** Best-effort env-var setter via ProcessEnvironment reflection. Returns
     *  false (so the test can be skipped) if the JDK doesn't expose it. */
    private static boolean trySetEnv(String key, String value) {
        try {
            Class<?> pe = Class.forName("java.lang.ProcessEnvironment");
            for (String field : new String[] {"theCaseInsensitiveEnvironment", "theEnvironment"}) {
                try {
                    Field f = pe.getDeclaredField(field);
                    f.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    Map<String, String> map = (Map<String, String>) f.get(null);
                    if (value == null) map.remove(key);
                    else map.put(key, value);
                    return true;
                } catch (NoSuchFieldException _) {
                    // try the next field name
                }
            }
        } catch (Exception _) {
            return false;
        }
        return false;
    }
}
