package com.claudecode.ui.lanterna.input;

import com.googlecode.lanterna.TerminalSize;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WindowInputRouterTest {

    @Test
    void resizeInvokesTheProjectionRefreshCallback() {
        AtomicInteger refreshes = new AtomicInteger();
        WindowInputRouter router = new WindowInputRouter(
            null, null, null, null, () -> { }, null, refreshes::incrementAndGet);

        router.onResized(null, TerminalSize.of(80, 24), TerminalSize.of(100, 30));

        assertEquals(1, refreshes.get());
    }
}
