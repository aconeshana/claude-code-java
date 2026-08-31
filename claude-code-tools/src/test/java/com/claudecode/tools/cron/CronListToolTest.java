package com.claudecode.tools.cron;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.tools.tasks.teammate.TeammateContext;
import com.claudecode.tools.tasks.teammate.TeammateContextHolder;
import com.claudecode.tools.ToolTexts;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class CronListToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void resetState() {
        TeammateContextHolder.clear();
        CronStore.resetForTest();
    }

    @Test
    void teammateOnlySeesOwnSessionCrons() {
        String own = CronStore.add("*/5 * * * *", "mine", true, false, "agent-a");
        String other = CronStore.add("*/7 * * * *", "theirs", true, false, "agent-b");
        CronStore.add("0 9 * * *", "leader", true, false, null);
        TeammateContextHolder.set(TeammateContext.builder().agentId("agent-a").build());

        String result = new CronListTool().call(MAPPER.createObjectNode(), ctx());

        assertTrue(Strings.CS.contains(result, own), result);
        assertFalse(Strings.CS.contains(result, other), result);
        assertFalse(Strings.CS.contains(result, "leader"), result);
    }

    @Test
    void leaderSeesAllCrons() {
        String first = CronStore.add("*/5 * * * *", "a", true, false, "agent-a");
        String second = CronStore.add("*/7 * * * *", "b", true, false, "agent-b");

        String result = new CronListTool().call(MAPPER.createObjectNode(), ctx());

        assertTrue(Strings.CS.contains(result, first), result);
        assertTrue(Strings.CS.contains(result, second), result);
    }

    @Test
    void promptTracksTheDurableGate() {
        CronListTool tool = new CronListTool(() -> true, () -> false);
        assertEquals(ToolTexts.description("CronList"), tool.description());
        assertEquals("List scheduled cron jobs", tool.description());
        String prompt = tool.prompt(null);

        assertEquals(ToolTexts.prompt("CronList", "session-only"), prompt);
        assertTrue(Strings.CS.endsWith(prompt, "in this session."), prompt);
        assertFalse(Strings.CS.contains(prompt, "scheduled_tasks.json"), prompt);

        CronListTool durable = new CronListTool(() -> true, () -> true);
        assertEquals(ToolTexts.prompt("CronList", "durable"), durable.prompt(null));
    }

    private ToolExecutionContext ctx() {
        return ToolExecutionContext.of(new AbortController(), "test");
    }
}
