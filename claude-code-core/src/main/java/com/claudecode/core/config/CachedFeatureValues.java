package com.claudecode.core.config;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;

/**
 * Typed reads of the locally cached remote feature values.
 *
 * <p>The released client resolves {@code tengu_*} switches through its remote
 * configuration channel; those values are persisted into
 * {@code cachedGrowthBookFeatures} inside the global config, which is the only
 * channel available to this port. A missing cache therefore means "use the
 * caller's default", exactly like an unresolved remote value.
 *
 * <ul>
 *   <li>{@code cachedGrowthBookFeatures} cache reads for
 *       boolean and numeric {@code tengu_*} switches.</li>
 * </ul>
 */
public final class CachedFeatureValues {

    private CachedFeatureValues() {}

    /** Returns the cached boolean value, or {@code fallback} when absent/not a boolean. */
    public static boolean bool(String name, boolean fallback) {
        JsonNode value = raw(name);
        return value != null && value.isBoolean() ? value.asBoolean() : fallback;
    }

    /** Returns the cached numeric value, or {@code null} when absent/non-numeric. */
    public static Long number(String name) {
        JsonNode value = raw(name);
        return value != null && value.isNumber() ? value.asLong() : null;
    }

    private static JsonNode raw(String name) {
        try {
            if (!Files.isRegularFile(ClaudePaths.GLOBAL_JSON)) return null;
            JsonNode global = JsonUtils.readJson(ClaudePaths.GLOBAL_JSON);
            return global == null ? null
                : global.path("cachedGrowthBookFeatures").get(name);
        } catch (Exception _) {
            return null;
        }
    }
}
