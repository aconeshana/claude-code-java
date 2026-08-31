package com.claudecode.core.prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Resolves and caches {@link SystemPromptSection} instances into their concrete string bodies.
 */
public final class SystemPromptSectionResolver {

    private static final ConcurrentMap<String, Optional<String>> CACHE = new ConcurrentHashMap<>();

    private SystemPromptSectionResolver() {}

    /**
     * Resolve all sections to their string values, honoring the cache for
     * non-{@code cacheBreak} sections and always recomputing others.
     *
     * @return list of resolved values, in the same order as {@code sections}.
     *         Individual entries may be {@code null} when a section's
     *         {@code compute} returns {@code null} — callers should filter
     *         nulls before concatenating.
     */
    public static List<String> resolve(List<SystemPromptSection> sections) {
        List<String> out = new ArrayList<>(sections.size());
        for (SystemPromptSection s : sections) {
            if (!s.cacheBreak()) {
                Optional<String> hit = CACHE.get(s.name());
                if (hit != null) {
                    out.add(hit.orElse(null));
                    continue;
                }
            }
            String value = s.compute().get();
            if (!s.cacheBreak()) {
                CACHE.put(s.name(), Optional.ofNullable(value));
            }
            out.add(value);
        }
        return out;
    }

    /**
     * Drop all cached section values.
     */
    public static void clearAll() {
        CACHE.clear();
    }

    /**
     * Number of currently-cached entries. For diagnostics / tests only.
     */
    public static int cacheSize() {
        return CACHE.size();
    }
}
