package com.claudecode.ui.lanterna.features.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.message.SystemMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;


class AutoModeEntryWarningControllerTest {

    @Test
    void enteringAutoSchedulesAndEmitsTheReleasedNoticeExactlyOnce() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicBoolean seen = new AtomicBoolean();
        List<SystemMessage> messages = new ArrayList<>();
        AutoModeEntryWarningController controller = new AutoModeEntryWarningController(
            seen::get, () -> false, () -> seen.set(true), messages::add, scheduler);

        controller.onPermissionModeChanged("auto");

        assertEquals(800L, scheduler.delayMs);
        assertTrue(messages.isEmpty());
        scheduler.runPending();

        assertTrue(seen.get());
        assertEquals(1, messages.size());
        SystemMessage notice = messages.getFirst();
        assertEquals("informational", notice.subtype());
        assertEquals("notice", notice.level());
        assertEquals(AutoModeEntryWarningController.DESCRIPTION, notice.content());

        controller.onPermissionModeChanged("default");
        controller.onPermissionModeChanged("auto");
        scheduler.runPending();
        assertEquals(1, messages.size(), "the warning is resolved once per process session");
    }

    @Test
    void leavingAutoBeforeTheDebounceCancelsAndAllowsALaterRetry() {
        ManualScheduler scheduler = new ManualScheduler();
        List<SystemMessage> messages = new ArrayList<>();
        AutoModeEntryWarningController controller = new AutoModeEntryWarningController(
            () -> false, () -> false, () -> { }, messages::add, scheduler);

        controller.onPermissionModeChanged("auto");
        controller.onPermissionModeChanged("plan");
        scheduler.runPending();
        assertTrue(messages.isEmpty());

        controller.onPermissionModeChanged("auto");
        scheduler.runPending();
        assertEquals(1, messages.size());
    }

    @Test
    void priorGlobalNoticeOrTrustedSkipSettingSuppressesTheMessage() {
        for (boolean seen : List.of(false, true)) {
            ManualScheduler scheduler = new ManualScheduler();
            List<SystemMessage> messages = new ArrayList<>();
            AutoModeEntryWarningController controller = new AutoModeEntryWarningController(
                () -> seen, () -> !seen, () -> { }, messages::add, scheduler);

            controller.onPermissionModeChanged("auto");
            scheduler.runPending();

            assertTrue(messages.isEmpty());
        }
    }

    private static final class ManualScheduler implements AutoModeEntryWarningController.Scheduler {
        private Runnable pending;
        private long delayMs = -1L;
        private boolean cancelled;

        @Override
        public AutoModeEntryWarningController.Cancellable schedule(Runnable task, long delayMs) {
            this.pending = task;
            this.delayMs = delayMs;
            this.cancelled = false;
            return () -> cancelled = true;
        }

        void runPending() {
            Runnable task = pending;
            pending = null;
            if (task != null && !cancelled) task.run();
        }
    }
}
