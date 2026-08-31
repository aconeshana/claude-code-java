package com.claudecode.tools.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.serialization.JsonUtils;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentProgressTrackerTest {

    @Test
    void keepsLatestInputAndCacheWhileAccumulatingOutputAcrossAssistantMessages() {
        AgentProgressTracker tracker = new AgentProgressTracker();

        tracker.recordMessage(assistant("a1", "tool-1", new Usage(100, 10, 20, 30)));
        tracker.recordMessage(assistant("a2", "tool-2", new Usage(200, 5, 40, 50)));

        assertEquals(new AgentProgressTracker.Snapshot(305, 2), tracker.snapshot());
    }

    @Test
    void finalizedUsageReplacesTheSameAssistantSnapshotWithoutDuplicatingToolsOrOutput() {
        AgentProgressTracker tracker = new AgentProgressTracker();
        tracker.recordMessage(assistant("a1", "tool-1", new Usage(100, 2, 20, 30)));

        tracker.recordUsage("a1", new Usage(120, 7, 25, 35));
        tracker.recordUsage("a1", new Usage(120, 7, 25, 35));

        assertEquals(new AgentProgressTracker.Snapshot(187, 1), tracker.snapshot());
        AssistantMessage updated = tracker.messageWithAggregatedUsage("a1");
        assertNotNull(updated);
        assertEquals(187, updated.message().usage().inputTokens()
            + updated.message().usage().outputTokens()
            + updated.message().usage().cacheCreationInputTokens()
            + updated.message().usage().cacheReadInputTokens());
    }

    @Test
    void usageOnlyFinalizationCanRefreshAnAgentThatDidNotCallTools() {
        AgentProgressTracker tracker = new AgentProgressTracker();

        tracker.recordUsage("a1", new Usage(80, 9, 10, 11));

        AssistantMessage projected = tracker.messageWithAggregatedUsage("a1");
        assertNotNull(projected);
        assertEquals(List.of(), projected.message().content());
        assertEquals(new AgentProgressTracker.Snapshot(110, 0), tracker.snapshot());
    }

    private static AssistantMessage assistant(String uuid, String toolUseId, Usage usage) {
        var input = JsonUtils.getMapper().createObjectNode();
        input.put("file_path", "/tmp/example");
        return new AssistantMessage(uuid, AssistantContent.of(
            "message-" + uuid,
            List.of(new ToolUseBlock(toolUseId, "Read", input)), usage));
    }
}
