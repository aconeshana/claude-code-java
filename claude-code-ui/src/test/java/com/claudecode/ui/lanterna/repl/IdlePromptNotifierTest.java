package com.claudecode.ui.lanterna.repl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class IdlePromptNotifierTest {
    @Test
    void doesNotFireBeforeAUserRequestAndFiresOnceAfterCompletedTurn() throws Exception {
        CountDownLatch fired = new CountDownLatch(1);
        try (IdlePromptNotifier notifier = new IdlePromptNotifier(10, fired::countDown, () -> true)) {
            notifier.turnCompleted();
            assertFalse(fired.await(30, TimeUnit.MILLISECONDS));

            notifier.userInteracted();
            notifier.turnCompleted();
            assertTrue(fired.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void interactionCancelsPendingNotification() throws Exception {
        CountDownLatch fired = new CountDownLatch(1);
        try (IdlePromptNotifier notifier = new IdlePromptNotifier(50, fired::countDown, () -> true)) {
            notifier.userInteracted();
            notifier.turnCompleted();
            notifier.cancel();
            assertFalse(fired.await(100, TimeUnit.MILLISECONDS));
        }
    }
}
