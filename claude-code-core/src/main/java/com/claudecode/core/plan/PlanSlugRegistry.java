package com.claudecode.core.plan;

import org.apache.commons.lang3.StringUtils;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Process-wide session-to-plan-slug cache shared by transcript persistence and
 * plan-file tools.
 *
 * <ul>
 *   <li>process state keyed by
 *       session id.</li>
 *   <li>{@code setPlanSlug}/
 *       {@code clearPlanSlug} — lazy generation, up to ten collision retries,
 *       resume restoration, and explicit clearing.</li>
 * </ul>
 */
public final class PlanSlugRegistry {

    private static final int MAX_SLUG_RETRIES = 10;
    private static final Map<String, String> SLUGS = new ConcurrentHashMap<>();

    private PlanSlugRegistry() {}

    public static Optional<String> get(String sessionId) {
        if (StringUtils.isBlank(sessionId)) return Optional.empty();
        return Optional.ofNullable(SLUGS.get(sessionId));
    }

    public static String getOrCreate(String sessionId, Supplier<String> generator,
                                     Predicate<String> alreadyExists) {
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return SLUGS.computeIfAbsent(sessionId, _ -> {
            String slug = null;
            for (int attempt = 0; attempt < MAX_SLUG_RETRIES; attempt++) {
                slug = generator.get();
                if (!alreadyExists.test(slug)) break;
            }
            return slug;
        });
    }

    public static void set(String sessionId, String slug) {
        if (StringUtils.isBlank(sessionId) || slug == null || StringUtils.isBlank(slug)) return;
        SLUGS.put(sessionId, slug);
    }

    public static void clear(String sessionId) {
        if (sessionId != null) SLUGS.remove(sessionId);
    }

    public static void clearAll() {
        SLUGS.clear();
    }
}
