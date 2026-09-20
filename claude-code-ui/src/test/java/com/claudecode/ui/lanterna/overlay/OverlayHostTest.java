package com.claudecode.ui.lanterna.overlay;

import static org.junit.jupiter.api.Assertions.*;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OverlayHostTest {

    @Test
    void activeOverlayConsumesEvenAnUnhandledKey() {
        OverlayHost host = new OverlayHost();
        TestOverlay overlay = new TestOverlay();
        overlay.active = true;
        overlay.consume = false;
        host.register(overlay);
        AtomicBoolean deliver = new AtomicBoolean(true);

        boolean routed = host.route(new KeyStroke(KeyType.PASTE), deliver);

        assertTrue(routed);
        assertFalse(deliver.get());
    }

    @Test
    void activationDuringCallbackDoesNotReplayTheOpeningKeyIntoTheNextOverlay() {
        OverlayHost host = new OverlayHost();
        TestOverlay next = new TestOverlay();
        TestOverlay opener = new TestOverlay();
        opener.onKey = () -> {
            opener.active = false;
            next.active = true;
        };
        opener.active = true;
        host.register(opener);
        host.register(next);
        AtomicBoolean deliver = new AtomicBoolean(true);

        host.route(new KeyStroke(KeyType.ENTER), deliver);

        assertEquals(1, opener.handled.get());
        assertEquals(0, next.handled.get());
        assertFalse(deliver.get());
    }

    @Test
    void directRouteSkipsInactiveOverlaysAndConsumesForTheActiveOverlay() {
        OverlayHost host = new OverlayHost();
        TestOverlay overlay = new TestOverlay();
        host.register(overlay);

        assertFalse(host.routeDirect(new KeyStroke(KeyType.ARROW_DOWN)));

        overlay.active = true;
        assertTrue(host.routeDirect(new KeyStroke(KeyType.ARROW_DOWN)));
        assertEquals(1, overlay.handled.get());
    }

    @Test
    void sealingRejectsLateOverlayRegistration() {
        OverlayHost host = new OverlayHost();
        host.register(new TestOverlay());

        host.seal();

        assertTrue(host.isSealed());
        assertThrows(IllegalStateException.class, () -> host.register(new TestOverlay()));
        assertThrows(IllegalStateException.class,
            () -> host.registerAll(List.of(new TestOverlay())));
    }

    @Test
    void theMouseWheelFallsThroughToTheScrollHandlerByDefault() {
        // The wheel is a terminal gesture, not a keybinding: a pending question card in the
        // released client still lets the user scroll the transcript behind it. The host must
        // decline the event outright — and leave `deliver` untouched — so the window router
        // reaches its own MOUSE_EVENT branch.
        OverlayHost host = new OverlayHost();
        TestOverlay overlay = new TestOverlay();
        overlay.active = true;
        host.register(overlay);

        for (MouseActionType type : List.of(MouseActionType.SCROLL_UP, MouseActionType.SCROLL_DOWN)) {
            AtomicBoolean deliver = new AtomicBoolean(true);
            assertFalse(host.route(wheel(type), deliver), type + " must not be claimed");
            assertTrue(deliver.get(), type + " must leave the delivery flag alone");
        }
        assertEquals(0, overlay.handled.get(), "the overlay never sees the wheel");
    }

    @Test
    void anOverlayWithItsOwnScrollableBodyKeepsTheWheel() {
        OverlayHost host = new OverlayHost();
        TestOverlay overlay = new TestOverlay();
        overlay.active = true;
        overlay.wantsWheel = true;
        host.register(overlay);
        AtomicBoolean deliver = new AtomicBoolean(true);

        assertTrue(host.route(wheel(MouseActionType.SCROLL_DOWN), deliver));
        assertFalse(deliver.get());
        assertEquals(1, overlay.handled.get());
    }

    @Test
    void aNonWheelMouseEventStaysExclusiveToTheActiveOverlay() {
        OverlayHost host = new OverlayHost();
        TestOverlay overlay = new TestOverlay();
        overlay.active = true;
        host.register(overlay);
        AtomicBoolean deliver = new AtomicBoolean(true);

        assertTrue(host.route(new MouseAction(MouseActionType.CLICK_DOWN, 1,
            new TerminalPosition(3, 4)), deliver));
        assertFalse(deliver.get());
        assertEquals(1, overlay.handled.get());
    }

    private static MouseAction wheel(MouseActionType type) {
        return new MouseAction(type, 0, new TerminalPosition(1, 1));
    }

    private static final class TestOverlay implements InlineOverlay {
        private final AtomicInteger handled = new AtomicInteger();
        private boolean active;
        private boolean consume = true;
        private boolean wantsWheel;
        private Runnable onKey = () -> {};

        @Override public boolean isActive() { return active; }

        @Override public boolean consumesMouseWheel() { return wantsWheel; }

        @Override public void handleKey(KeyStroke key, AtomicBoolean deliver) {
            handled.incrementAndGet();
            onKey.run();
            if (consume) deliver.set(false);
        }
    }
}
