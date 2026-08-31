package com.claudecode.tools.files;

import java.util.Locale;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.config.ClaudePaths;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class PluginCacheGlobExclusionsTest {

    @Test
    void computeEmitsExclusionPerOrphanedVersion(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(RipGrepUtil.isAvailable());
        Path cache = dir.resolve("cache");
        // v1 is orphaned (has .orphaned_at); v2 is live (no marker).
        Path v1 = cache.resolve("market/a/1.0.0");
        Path v2 = cache.resolve("market/a/2.0.0");
        Files.createDirectories(v1);
        Files.createDirectories(v2);
        Files.writeString(v1.resolve(".orphaned_at"), "12345");
        Files.writeString(v2.resolve("index.js"), "console.log('live')");

        List<String> exclusions = PluginCacheGlobExclusions.compute(cache);

        assertEquals(List.of("!**/market/a/1.0.0/**"), exclusions,
            "only the orphaned version directory should be excluded");
    }

    @Test
    void computeReturnsEmptyWhenNoOrphans(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(RipGrepUtil.isAvailable());
        Path cache = dir.resolve("cache");
        Files.createDirectories(cache.resolve("market/a/1.0.0"));
        Files.writeString(cache.resolve("market/a/1.0.0/index.js"), "x");

        List<String> exclusions = PluginCacheGlobExclusions.compute(cache);
        assertTrue(exclusions.isEmpty(), "no .orphaned_at → no exclusions");
    }

    @Test
    void pathsOverlapDetectsAncestor() {
        Path cache = Path.of("/home/u/.claude/plugins/cache");
        assertTrue(PluginCacheGlobExclusions.pathsOverlap(cache, cache.resolve("market/a/1.0.0")),
            "child of cache overlaps");
        assertTrue(PluginCacheGlobExclusions.pathsOverlap(cache, cache), "identical overlaps");
        assertFalse(PluginCacheGlobExclusions.pathsOverlap(cache, Path.of("/home/u/project")),
            "unrelated dir does not overlap");
    }

    @Test
    void pathsOverlap_rootOverlapsEverything() {

        assertTrue(PluginCacheGlobExclusions.pathsOverlap(Path.of("/"), Path.of("/home/u")));
        assertTrue(PluginCacheGlobExclusions.pathsOverlap(Path.of("/home/u"), Path.of("/")));
    }

    @Test
    void pathsOverlap_caseSensitiveOnNonWindows() {

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Assumptions.assumeFalse(Strings.CS.contains(os, "win"));
        assertFalse(PluginCacheGlobExclusions.pathsOverlap(
            Path.of("/home/User"), Path.of("/home/user")));
    }

    @Test
    void defaultCacheDirEndsWithPluginsCache() {
        // No CLAUDE_CODE_PLUGIN_CACHE_DIR / CLAUDE_CONFIG_DIR in the test env → ~/.claude/plugins/cache.
        Path dir = PluginCacheGlobExclusions.pluginCacheDir();
        assertTrue(dir.isAbsolute(), "plugin cache dir must be absolute");
        assertTrue(Strings.CS.endsWith(dir.toString(), "plugins/cache"),
            "default cache dir is <configHome>/plugins/cache: " + dir);
        if (System.getenv("CLAUDE_CODE_PLUGIN_CACHE_DIR") == null) {
            assertTrue(dir.startsWith(ClaudePaths.CLAUDE_HOME));
        }
    }

}
