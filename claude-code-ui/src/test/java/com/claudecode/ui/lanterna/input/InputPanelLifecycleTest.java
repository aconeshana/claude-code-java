package com.claudecode.ui.lanterna.input;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.googlecode.lanterna.gui2.Container;
import com.googlecode.lanterna.gui2.Panel;
import java.lang.reflect.Field;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;

/**
 * Characterisation tests for InputPanel's attached-only footer refresh lifecycle.
 */
class InputPanelLifecycleTest {

    @Test
    void detachedPanelDoesNotScheduleFooterRefresh() throws Exception {
        assertNull(refreshFuture(new InputPanel()));
    }

    @Test
    void repeatedAttachmentUsesOneRefreshTask_andDetachCancelsIt() throws Exception {
        InputPanel panel = new InputPanel();
        Container parent = new Panel();

        panel.onAdded(parent);
        ScheduledFuture<?> first = refreshFuture(panel);
        assertNotNull(first);

        panel.onAdded(parent);
        assertSame(first, refreshFuture(panel), "a second attach must not schedule another tick");

        panel.onRemoved(parent);
        assertTrue(first.isCancelled(), "detaching must cancel the attached-only refresh task");
        assertNull(refreshFuture(panel));
    }

    @Test
    void reattachmentCreatesOneNewRefreshTask() throws Exception {
        InputPanel panel = new InputPanel();
        Container parent = new Panel();

        panel.onAdded(parent);
        ScheduledFuture<?> first = refreshFuture(panel);
        try {
            panel.onRemoved(parent);
            assertTrue(first.isCancelled());

            panel.onAdded(parent);
            ScheduledFuture<?> second = refreshFuture(panel);
            assertNotNull(second);
            assertNotSame(first, second);
            assertFalse(second.isCancelled());
        } finally {
            ScheduledFuture<?> active = refreshFuture(panel);
            if (active != null) panel.onRemoved(parent);
        }
    }

    private static ScheduledFuture<?> refreshFuture(InputPanel panel) throws Exception {
        Field field = InputPanel.class.getDeclaredField("pillRefreshFuture");
        field.setAccessible(true);
        return (ScheduledFuture<?>) field.get(panel);
    }
}
