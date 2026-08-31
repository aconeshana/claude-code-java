package com.claudecode.services.memory;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolExecutor;
import com.claudecode.core.engine.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;


class MemoryExtractionToolExecutorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ToolExecutor ALWAYS_SUCCEEDS = new ToolExecutor() {
        @Override
        public ToolResult execute(String toolName, JsonNode input,
                                   ToolExecutionContext context) {
            return ToolResult.success("ok: " + toolName);
        }
        @Override
        public List<StreamingClient.StreamRequest.ToolDef> getToolDefinitions() {
            return List.of(
                new StreamingClient.StreamRequest.ToolDef("Read", "", MAPPER.createObjectNode()),
                new StreamingClient.StreamRequest.ToolDef("Grep", "", MAPPER.createObjectNode()),
                new StreamingClient.StreamRequest.ToolDef("Bash", "", MAPPER.createObjectNode()),
                new StreamingClient.StreamRequest.ToolDef("Edit", "", MAPPER.createObjectNode()),
                new StreamingClient.StreamRequest.ToolDef("Agent", "", MAPPER.createObjectNode()));
        }
    };

    private ToolExecutionContext ctx() {
        return ToolExecutionContext.builder(new AbortController(), "test-session").workingDirectory("/tmp").build();
    }

    @Test
    void allowsReadGrepGlobUnconditionally(@TempDir Path memDir) {
        var exec = new MemoryExtractionToolExecutor(ALWAYS_SUCCEEDS, memDir);
        for (String tool : List.of("Read", "Grep", "Glob")) {
            ToolResult result = exec.execute(tool, MAPPER.createObjectNode(), ctx());
            assertFalse(result.isError(), tool + " should be allowed");
        }
    }

    @Test
    void allowsReadOnlyBashCommands(@TempDir Path memDir) {
        var exec = new MemoryExtractionToolExecutor(ALWAYS_SUCCEEDS, memDir);
        for (String cmd : List.of("ls -la", "find . -name x", "cat foo.md", "ls && cat foo.md")) {
            ObjectNode input = MAPPER.createObjectNode().put("command", cmd);
            ToolResult result = exec.execute("Bash", input, ctx());
            assertFalse(result.isError(), cmd + " should be allowed");
        }
    }

    @Test
    void deniesWriteCapableBashCommands(@TempDir Path memDir) {
        var exec = new MemoryExtractionToolExecutor(ALWAYS_SUCCEEDS, memDir);
        for (String cmd : List.of("rm -rf /", "ls && rm foo", "echo x > foo.md; cat foo.md")) {
            ObjectNode input = MAPPER.createObjectNode().put("command", cmd);
            ToolResult result = exec.execute("Bash", input, ctx());
            assertTrue(result.isError(), cmd + " should be denied");
        }
    }

    @Test
    void allowsEditWriteWithinMemoryDir(@TempDir Path memDir) {
        var exec = new MemoryExtractionToolExecutor(ALWAYS_SUCCEEDS, memDir);
        ObjectNode input = MAPPER.createObjectNode().put("file_path", memDir.resolve("user_role.md").toString());
        ToolResult result = exec.execute("Edit", input, ctx());
        assertFalse(result.isError());
    }

    @Test
    void deniesEditWriteOutsideMemoryDir(@TempDir Path memDir) {
        var exec = new MemoryExtractionToolExecutor(ALWAYS_SUCCEEDS, memDir);
        ObjectNode input = MAPPER.createObjectNode().put("file_path", "/etc/passwd");
        ToolResult result = exec.execute("Write", input, ctx());
        assertTrue(result.isError());
    }

    @Test
    void deniesOtherTools(@TempDir Path memDir) {
        var exec = new MemoryExtractionToolExecutor(ALWAYS_SUCCEEDS, memDir);
        ToolResult result = exec.execute("Agent", MAPPER.createObjectNode(), ctx());
        assertTrue(result.isError());
    }

    @Test
    void toolDefinitionsPreserveFullCatalogForCache(@TempDir Path memDir) {
        var exec = new MemoryExtractionToolExecutor(ALWAYS_SUCCEEDS, memDir);
        List<String> names = exec.getToolDefinitions().stream()
            .map(StreamingClient.StreamRequest.ToolDef::name).toList();
        assertTrue(names.containsAll(List.of("Read", "Grep", "Bash", "Edit")));
        assertTrue(names.contains("Agent"));
    }

    @Test
    void toolDefinitionsForwardTheRequestPromptContext(@TempDir Path memDir) {
        AtomicReference<ToolExecutionContext> captured = new AtomicReference<>();
        ToolExecutor delegate = new ToolExecutor() {
            @Override
            public ToolResult execute(
                    String toolName, JsonNode input, ToolExecutionContext context) {
                return ToolResult.success("ok");
            }

            @Override
            public List<StreamingClient.StreamRequest.ToolDef> getToolDefinitions(
                    ToolExecutionContext context) {
                captured.set(context);
                return List.of(new StreamingClient.StreamRequest.ToolDef(
                    "Agent", "context:" + context.currentModel(), null));
            }
        };
        var exec = new MemoryExtractionToolExecutor(delegate, memDir);
        ToolExecutionContext context = ctx().toBuilder()
            .currentModel("gpt-5.6-sol")
            .build();

        List<StreamingClient.StreamRequest.ToolDef> definitions =
            exec.getToolDefinitions(context);

        assertSame(context, captured.get());
        assertEquals("context:gpt-5.6-sol", definitions.getFirst().description());
    }

    @Test
    void isReadOnlyCommandRejectsNullOrBlank() {
        assertFalse(MemoryExtractionToolExecutor.isReadOnlyCommand(null));
        assertFalse(MemoryExtractionToolExecutor.isReadOnlyCommand(""));
        assertFalse(MemoryExtractionToolExecutor.isReadOnlyCommand("   "));
    }

    @Test
    void isReadOnlyCommandHandlesSudoPrefix() {
        assertTrue(MemoryExtractionToolExecutor.isReadOnlyCommand("sudo cat foo.md"));
    }
}
