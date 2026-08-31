package com.claudecode.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.benmanes.caffeine.cache.Cache;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class MarkdownRendererCachePolicyTest {

    @Test
    void defaultProductionRendererIsShared() {
        assertSame(MarkdownRenderer.shared(), MarkdownRenderer.shared());
    }

    @Test
    void defaultCacheUsesAWeightedMemoryBudget() throws Exception {
        Cache<?, ?> cache = renderCache(new MarkdownRenderer());
        var eviction = cache.policy().eviction().orElseThrow();

        assertTrue(eviction.isWeighted(),
            "entry-count LRU allows a few huge ANSI strings to dominate the heap");
        assertTrue(eviction.getMaximum() <= 16L * 1024 * 1024,
            "the shared renderer should have a small, explicit heap budget");
    }

    @Test
    void oversizedRenderedValueBypassesTheCache() throws Exception {
        MarkdownRenderer renderer = new MarkdownRenderer();
        Cache<?, ?> cache = renderCache(renderer);
        String markdown = "# " + "x".repeat(140_000);

        renderer.render(markdown);
        cache.cleanUp();

        assertEquals(0, cache.estimatedSize(),
            "a single rendered value above 256 KiB should not enter the cache");
    }

    @Test
    void smallMarkdownStillBenefitsFromCaching() throws Exception {
        MarkdownRenderer renderer = new MarkdownRenderer();
        Cache<?, ?> cache = renderCache(renderer);

        renderer.render("**cache me**");
        renderer.render("**cache me**");
        cache.cleanUp();

        assertEquals(1, cache.estimatedSize());
    }

    private static Cache<?, ?> renderCache(MarkdownRenderer renderer) throws Exception {
        Field field = MarkdownRenderer.class.getDeclaredField("renderCache");
        field.setAccessible(true);
        return (Cache<?, ?>) field.get(renderer);
    }
}
