package com.claudecode.tools.files;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.io.PathUtils;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.config.ClaudePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;


public final class PluginCacheGlobExclusions {

    private static final Logger log = LoggerFactory.getLogger(PluginCacheGlobExclusions.class);

    private static final String ORPHANED_AT_FILENAME = ".orphaned_at";

    /** Session-scoped cache. Frozen once computed; cleared explicitly on /reload-plugins. */
    private static final AtomicReference<List<String>> cached = new AtomicReference<>(null);

    private PluginCacheGlobExclusions() {}

    /**
     * Returns ripgrep exclusion globs that exclude each orphaned plugin version directory, or an
     * empty list when the search path does not overlap the plugin cache (or on any failure).
     *
     * @param searchPath the directory being searched, used to skip exclusions when it does not
     *                    overlap the plugin cache; may be {@code null}
     */
    public static List<String> getExclusions(String searchPath) {
        Path cachePath = pluginCacheDir();
        if (searchPath != null && !pathsOverlap(Path.of(searchPath), cachePath)) {
            return List.of();
        }
        List<String> cachedVal = cached.get();
        if (cachedVal != null) {
            return cachedVal;
        }
        List<String> computed = compute(cachePath);
        cached.compareAndSet(null, computed);
        return cached.get();
    }

    /** Clears the session cache; call when plugins are reloaded. */
    public static void clear() {
        cached.set(null);
    }

    /**
     * Scans the plugin cache for {@code .orphaned_at} markers and builds exclusion globs.
     * Package-private for unit testing without depending on environment variables.
     */
    static List<String> compute(Path cachePath) {
        try {
            if (!Files.isDirectory(cachePath)) {
                return List.of();
            }
            List<String> args = List.of("rg", "--files", "--hidden", "--no-ignore",
                "--max-depth", "4", "--glob", ORPHANED_AT_FILENAME);
            List<String> markers = RipGrepUtil.run(args, cachePath);
            List<String> exclusions = new ArrayList<>();
            for (String marker : markers) {
                Path versionDir = Path.of(marker).getParent();
                if (versionDir == null) {
                    continue;
                }
                // ripgrep may return absolute or relative — normalize to relative to cachePath.
                Path rel = versionDir.isAbsolute() ? cachePath.relativize(versionDir) : versionDir;
                // ripgrep glob patterns always use forward slashes, even on Windows.
                String posixRelative = rel.toString().replace('\\', '/');
                exclusions.add("!**/" + posixRelative + "/**");
            }
            return exclusions;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.debug("[PLUGIN-CACHE] exclusion scan failed: {}", e.toString());
            return List.of();
        }
    }

    /**
     * Resolves the plugin cache directory (the {@code <plugins>/cache} dir that holds versioned plugin
     * installs).
     */
    static Path pluginCacheDir() {
        String env = SubprocessEnvironment.get("CLAUDE_CODE_PLUGIN_CACHE_DIR");
        if (StringUtils.isNotBlank(env)) {

            return Path.of(PathUtils.expandTilde(env)).resolve("cache");
        }
        return ClaudePaths.CLAUDE_HOME.resolve(getPluginsDirectoryName()).resolve("cache");
    }

/**
     * Directory name under config home: {@code cowork_plugins} when cowork is enabled, else {@code
     * plugins}.
     */
    private static String getPluginsDirectoryName() {

        // plumbed into this static helper, so we honor the env-var override, which is the
        // standalone-reproducible path.
        return useCoworkPlugins() ? "cowork_plugins" : "plugins";
    }

    private static boolean useCoworkPlugins() {
        return RipGrepUtil.envTruthy("CLAUDE_CODE_USE_COWORK_PLUGINS", false);
    }

    /**
     * Whether two paths overlap (one is an ancestor of the other).
     */
    static boolean pathsOverlap(Path a, Path b) {
        Path na = normalizeForCompare(a);
        Path nb = normalizeForCompare(b);
        String sep = File.separator;
        if (na.toString().equals(sep) || nb.toString().equals(sep)) {
            return true;
        }
        if (na.equals(nb)) {
            return true;
        }
        String sa = na.toString() + sep;
        String sb = nb.toString() + sep;
        return Strings.CS.startsWith(sa, sb) || Strings.CS.startsWith(sb, sa);
    }

    private static Path normalizeForCompare(Path p) {
        Path n = p.toAbsolutePath().normalize();
        if (Strings.CI.contains(System.getProperty("os.name"), "win")) {
            return Path.of(n.toString().toLowerCase(Locale.ROOT));
        }
        return n;
    }
}
