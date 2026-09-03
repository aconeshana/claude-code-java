package com.claudecode.ui.lanterna.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.ThinkingBlock;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the 30 second salvage window and the shape of the virtual assistant message that
 * ESC appends. All timing is injected, so nothing here sleeps.
 */
class InterruptedThinkingCacheTest {

    @Test
    void thinkingStaysReadableUntilTheWindowElapses() {
        InterruptedThinkingCache cache = new InterruptedThinkingCache();
        cache.store("deep thought", 0L);

        assertEquals("deep thought", cache.peekFresh(0L));
        assertEquals("deep thought", cache.peekFresh(InterruptedThinkingCache.TTL_MILLIS - 1));
    }

    @Test
    void thinkingExpiresExactlyAtTheWindowBoundary() {
        InterruptedThinkingCache cache = new InterruptedThinkingCache();
        cache.store("deep thought", 0L);

        assertNull(cache.peekFresh(InterruptedThinkingCache.TTL_MILLIS));
        assertNull(cache.peekFresh(InterruptedThinkingCache.TTL_MILLIS * 2));
    }

    @Test
    void readingIsNonDestructive() {
        InterruptedThinkingCache cache = new InterruptedThinkingCache();
        cache.store("deep thought", 0L);

        assertEquals("deep thought", cache.peekFresh(10L));
        assertEquals("deep thought", cache.peekFresh(20L));
    }

    @Test
    void storeRestartsTheWindowAndReplacesTheBody() {
        InterruptedThinkingCache cache = new InterruptedThinkingCache();
        cache.store("first", 0L);
        cache.store("second", 25_000L);

        assertEquals("second", cache.peekFresh(50_000L));
        assertNull(cache.peekFresh(55_000L));
    }

    @Test
    void emptyBodiesReadBackAsAbsent() {
        InterruptedThinkingCache cache = new InterruptedThinkingCache();
        assertNull(cache.peekFresh(0L));

        cache.store(null, 0L);
        assertNull(cache.peekFresh(0L));

        cache.store("   \n\t ", 0L);
        assertNull(cache.peekFresh(0L));
    }

    @Test
    void readTrimsTheBody() {
        InterruptedThinkingCache cache = new InterruptedThinkingCache();
        cache.store("\n  padded reasoning  \n", 0L);

        assertEquals("padded reasoning", cache.peekFresh(0L));
    }

    @Test
    void virtualMessageCarriesOneUnsignedThinkingBlock() {
        SDKMessage.Assistant salvaged =
            InterruptedThinkingCache.virtualThinkingMessage("rescued reasoning");

        assertEquals(Boolean.TRUE, salvaged.message().isVirtual());
        List<?> blocks = salvaged.message().message().content();
        assertEquals(1, blocks.size());
        ThinkingBlock block = assertInstanceOf(ThinkingBlock.class, blocks.getFirst());
        assertEquals("rescued reasoning", block.thinking());
        assertEquals("", block.signature());
    }

    @Test
    void virtualMessagesGetDistinctIds() {
        String first = InterruptedThinkingCache.virtualThinkingMessage("a").message().uuid();
        String second = InterruptedThinkingCache.virtualThinkingMessage("a").message().uuid();

        assertTrue(StringUtils.isNotBlank(first));
        assertNotEquals(first, second);
    }
}
