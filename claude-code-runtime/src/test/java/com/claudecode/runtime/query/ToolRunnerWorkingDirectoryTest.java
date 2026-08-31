package com.claudecode.runtime.query;

import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolExecutor;
import com.claudecode.core.engine.ToolResult;

import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.model.PermissionModeKind;
import com.claudecode.core.serialization.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Covers {@link ConcurrentToolRunner} threading {@link QuerySessionSpec#workingDirectory}
 * through to the {@link ToolExecutionContext} passed to each tool call —
 * rather than letting {@link ToolExecutionContext#of} fall back to the
 * process-wide {@code System.getProperty("user.dir")} internally. Today the
 * two values are kept in sync by convention (the CLI mutates
 * {@code System.setProperty("user.dir", ...)} in place rather than updating
 * {@code QuerySessionSpec}), so this doesn't yet misbehave in production —
 * but any caller that explicitly sets a {@code QuerySessionSpec.workingDirectory}
 * different from the process cwd (sub-agents, {@code /resume}, tests) must
 * still see that value honored by tool execution.
 */
class ToolRunnerWorkingDirectoryTest {

    private static final StreamingClient NOOP_CLIENT = new StreamingClient() {
        @Override
        public Iterator<StreamingEvent> createStream(StreamRequest request) {
            return Collections.emptyIterator();
        }
        @Override
        public String getModel() { return "test-model"; }
    };

    @Test
    void toolExecutionContext_usesConfiguredWorkingDirectory_notProcessCwd(@TempDir Path configuredDir) {
        String processCwd = System.getProperty("user.dir");
        assertNotEquals(processCwd, configuredDir.toString(),
            "test precondition: @TempDir must differ from the process cwd");

        AtomicReference<String> observedWorkingDirectory = new AtomicReference<>();
        ToolExecutor capturingExecutor = (_, _, ctx) -> {
            observedWorkingDirectory.set(ctx.workingDirectory());
            return ToolResult.success("ok");
        };

        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .toolExecutor(capturingExecutor)
            .workingDirectory(configuredDir.toString())
            .build());

        ToolUseBlock tub = new ToolUseBlock("tu-1", "Read", JsonUtils.getMapper().createObjectNode());
        new ConcurrentToolRunner().run(List.of(tub), engine, false, 1, _ -> {});

        assertEquals(configuredDir.toString(), observedWorkingDirectory.get(),
            "ConcurrentToolRunner must pass the engine's configured workingDirectory, not System.getProperty(\"user.dir\")");
    }

    @Test
    void toolExecutionContext_carriesCurrentPermissionModeSnapshot() {
        AtomicReference<PermissionModeKind> observedMode = new AtomicReference<>();
        ToolExecutor capturingExecutor = (_, _, ctx) -> {
            observedMode.set(ctx.currentPermissionMode());
            return ToolResult.success("ok");
        };
        QuerySessionSpec config = QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .toolExecutor(capturingExecutor)
            .build();
        config.setPermissionModeSupplier(() -> PermissionModeKind.BYPASS_PERMISSIONS);
        DefaultQuerySession engine = new DefaultQuerySession(config);

        ToolUseBlock tub = new ToolUseBlock("tu-permission", "Agent",
            JsonUtils.getMapper().createObjectNode());
        new ConcurrentToolRunner().run(List.of(tub), engine, false, 1, _ -> {});

        assertEquals(PermissionModeKind.BYPASS_PERMISSIONS, observedMode.get());
    }
}
