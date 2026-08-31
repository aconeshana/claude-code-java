package com.claudecode.tools.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.model.ModelNames;
import com.claudecode.core.message.ProgressMessage;
import com.claudecode.tools.ToolUseRenderContext;
import com.claudecode.tools.ToolUseTag;
import com.claudecode.tools.agent.AgentTool;
import com.claudecode.tools.files.FileReadTool;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleasedToolUseTagParityTest {

    @AfterEach
    void resetTaskOutputPaths() {
        TaskOutputPaths.resetForTest();
    }

    @Test
    void taskOutputRendersTaskIdAsDimTag() {
        var input = JsonNodeFactory.instance.objectNode().put("task_id", "task-123");

        var tag = new TaskOutputTool(TaskStore.inMemory())
            .renderToolUseTag(input, ToolUseRenderContext.empty()).orElseThrow();

        assertEquals("task-123", tag.text());
        assertEquals(ToolUseTag.Tone.DIM, tag.tone());
    }

    @Test
    void readRendersOnlyCanonicalAgentOutputTaskIds(@TempDir Path tempDir) {
        TaskOutputPaths.configureForTest(
            tempDir.resolve("claude-42"), "session-123", tempDir.resolve("project"));
        FileReadTool tool = new FileReadTool();
        Path valid = TaskOutputPaths.outputDirectory().resolve("agent_A-7.output");

        var validInput = JsonNodeFactory.instance.objectNode()
            .put("file_path", valid.toString());
        assertEquals("agent_A-7", tool.renderToolUseTag(
            validInput, ToolUseRenderContext.empty()).orElseThrow().text());

        var wrongDirectory = JsonNodeFactory.instance.objectNode()
            .put("file_path", tempDir.resolve("tasks/agent_A-7.output").toString());
        assertTrue(tool.renderToolUseTag(
            wrongDirectory, ToolUseRenderContext.empty()).isEmpty());

        var invalidId = JsonNodeFactory.instance.objectNode()
            .put("file_path", TaskOutputPaths.outputDirectory()
                .resolve("not.valid.output").toString());
        assertTrue(tool.renderToolUseTag(
            invalidId, ToolUseRenderContext.empty()).isEmpty());
    }

    @Test
    void agentUsesResolvedResultModelInsteadOfRequestedAlias() {
        AgentTool tool = new AgentTool();
        var input = JsonNodeFactory.instance.objectNode().put("model", "haiku");
        String resolvedModel = ModelNames.defaultOpusModel();
        ToolUseRenderContext context = new ToolUseRenderContext(
            "toolu_agent", Map.of("resolvedModel", resolvedModel), List.of(),
            ModelNames.defaultMainLoopModel());

        assertEquals(ModelNames.displayName(resolvedModel),
            tool.renderToolUseTag(input, context).orElseThrow().text());

        input.put("model", "inherit");
        assertTrue(tool.renderToolUseTag(input, context).isEmpty());
    }

    @Test
    void agentPrefersLatestResolvedProgressModelOverResult() {
        AgentTool tool = new AgentTool();
        var input = JsonNodeFactory.instance.objectNode().put("model", "haiku");
        ProgressMessage older = progress("claude-haiku-4-5");
        ProgressMessage latest = progress(ModelNames.defaultOpusModel());
        ToolUseRenderContext context = new ToolUseRenderContext(
            "toolu_agent", Map.of("resolvedModel", ModelNames.defaultMainLoopModel()),
            List.of(older, latest), ModelNames.defaultMainLoopModel());

        assertEquals(ModelNames.displayName(ModelNames.defaultOpusModel()),
            tool.renderToolUseTag(input, context).orElseThrow().text());
    }

    private static ProgressMessage progress(String resolvedModel) {
        return new ProgressMessage("progress", "", null, Instant.now(),
            "toolu_agent", null, new ProgressMessage.ProgressData(
                "agent_progress", null, null, null, null, null, null, null,
                true, null, null, "agent", null, null, null, null, null,
                resolvedModel));
    }
}
