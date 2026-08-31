package com.claudecode.ui.lanterna.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.tools.cron.CronScheduler;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScheduledTaskInteractionRouterTest {

    @Test
    void leadTaskUsesTheNormalInteractiveHandler() {
        List<String> lead = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        ScheduledTaskInteractionRouter router = new ScheduledTaskInteractionRouter(
            (_, _) -> false, removed::add, task -> lead.add(task.id()));

        router.route(task(null));

        assertEquals(List.of("cron-1"), lead);
        assertTrue(removed.isEmpty());
    }

    @Test
    void teammateTaskIsInjectedWithoutSurfacingInLeadTranscript() {
        List<String> lead = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> delivered = new ArrayList<>();
        ScheduledTaskInteractionRouter router = new ScheduledTaskInteractionRouter(
            (agentId, prompt) -> {
                delivered.add(agentId + ":" + prompt);
                return true;
            }, removed::add, task -> lead.add(task.id()));

        router.route(task("agent-7"));

        assertEquals(List.of("agent-7:raw prompt"), delivered);
        assertTrue(lead.isEmpty());
        assertTrue(removed.isEmpty());
    }

    @Test
    void orphanedTeammateTaskIsRemovedWithoutSurfacingInLeadTranscript() {
        List<String> lead = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        ScheduledTaskInteractionRouter router = new ScheduledTaskInteractionRouter(
            (_, _) -> false, removed::add, task -> lead.add(task.id()));

        router.route(task("gone-agent"));

        assertEquals(List.of("cron-1"), removed);
        assertTrue(lead.isEmpty());
    }

    private static CronScheduler.FiredTask task(String agentId) {
        return new CronScheduler.FiredTask(
            "cron-1", "raw prompt", "resolved prompt", null, false, agentId);
    }
}
