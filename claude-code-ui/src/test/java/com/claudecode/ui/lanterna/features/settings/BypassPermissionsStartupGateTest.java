package com.claudecode.ui.lanterna.features.settings;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;




class BypassPermissionsStartupGateTest {

    @Test
    void ordinaryModeSkipsDialog() {
        FakeView view = new FakeView();
        AtomicBoolean ready = new AtomicBoolean();
        BypassPermissionsStartupGate gate = new BypassPermissionsStartupGate(
            () -> false, () -> false, () -> {}, view);

        gate.start(() -> ready.set(true), (_, _) -> {});

        assertTrue(ready.get());
        assertFalse(view.prompted);
    }

    @Test
    void priorTrustedAcknowledgementSkipsDialog() {
        FakeView view = new FakeView();
        AtomicBoolean ready = new AtomicBoolean();
        BypassPermissionsStartupGate gate = new BypassPermissionsStartupGate(
            () -> true, () -> true, () -> {}, view);

        gate.start(() -> ready.set(true), (_, _) -> {});

        assertTrue(ready.get());
        assertFalse(view.prompted);
    }

    @Test
    void acceptPersistsUserAcknowledgementBeforeContinuing() {
        FakeView view = new FakeView();
        AtomicBoolean persisted = new AtomicBoolean();
        AtomicBoolean ready = new AtomicBoolean();
        AtomicReference<String> exit = new AtomicReference<>();
        BypassPermissionsStartupGate gate = new BypassPermissionsStartupGate(
            () -> true, () -> false, () -> persisted.set(true), view);

        gate.start(() -> {
            assertTrue(persisted.get());
            ready.set(true);
        }, (reason, code) -> exit.set(reason + ":" + code));
        view.accept.run();

        assertTrue(ready.get());
        assertNull(exit.get());
    }

    @Test
    void declineAndEscapeUseReleasedExitCodes() {
        FakeView declineView = new FakeView();
        AtomicReference<String> declineExit = new AtomicReference<>();
        new BypassPermissionsStartupGate(() -> true, () -> false, () -> {}, declineView)
            .start(() -> {}, (reason, code) -> declineExit.set(reason + ":" + code));
        declineView.decline.run();
        assertEquals("Bypass permissions declined by user:1", declineExit.get());

        FakeView escapeView = new FakeView();
        AtomicReference<String> escapeExit = new AtomicReference<>();
        new BypassPermissionsStartupGate(() -> true, () -> false, () -> {}, escapeView)
            .start(() -> {}, (reason, code) -> escapeExit.set(reason + ":" + code));
        escapeView.escape.run();
        assertEquals("Bypass permissions dialog cancelled by user:0", escapeExit.get());
    }

    private static final class FakeView implements BypassPermissionsStartupGate.View {
        boolean prompted;
        Runnable accept;
        Runnable decline;
        Runnable escape;

        @Override
        public void prompt(Runnable onAccept, Runnable onDecline, Runnable onEscape) {
            prompted = true;
            accept = onAccept;
            decline = onDecline;
            escape = onEscape;
        }
    }
}
