package com.claudecode.tools.cron;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ScheduledTaskHooksTest {

    @AfterEach
    void resetStore() {
        CronStore.resetForTest();
    }

    @Test
    void stopHookSnapshotMatchesOfficial197ShapeAndPromptClipping() {
        String prompt = "😀" + "x".repeat(1_010);
        CronStore.add("5 9 * * *", prompt, false, false, null,
            123L, "loop", "1234abcd");

        assertEquals(List.of(Map.of(
            "id", "1234abcd",
            "schedule", "5 9 * * *",
            "recurring", false,
            "prompt", "😀" + "x".repeat(998) + "… [+12 chars]")),
            ScheduledTaskHooks.snapshot());
    }

    @Test
    void stopHookSnapshotExcludesProjectDurableTasks() {
        CronStore.add("5 9 * * *", "session", false, false, null,
            123L, null, "session1");
        CronStore.add("10 9 * * *", "durable", true, true, null,
            124L, null, "durable1", null, false);

        assertEquals(List.of(Map.of(
            "id", "session1",
            "schedule", "5 9 * * *",
            "recurring", false,
            "prompt", "session")),
            ScheduledTaskHooks.snapshot());
    }
}
