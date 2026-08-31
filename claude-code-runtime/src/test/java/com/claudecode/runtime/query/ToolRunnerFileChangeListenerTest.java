package com.claudecode.runtime.query;

import com.claudecode.core.engine.FileChangeListener;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.ToolExecutor;
import com.claudecode.core.engine.ToolResult;

import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the {@link FileChangeListener} notification {@link ConcurrentToolRunner}
 * fires after a successful {@code Write}/{@code Edit} — the hook LSP passive
 * diagnostics rely on to send {@code didOpen}/{@code didChange}.
 */
class ToolRunnerFileChangeListenerTest {

    private static final StreamingClient NOOP_CLIENT = new StreamingClient() {
        @Override
        public Iterator<StreamingEvent> createStream(StreamRequest request) {
            return Collections.emptyIterator();
        }
        @Override
        public String getModel() { return "test-model"; }
    };

    private static DefaultQuerySession newEngine(ToolExecutor executor) {
        return new DefaultQuerySession(QuerySessionSpec.builder().llmClient(NOOP_CLIENT).toolExecutor(executor).build());
    }

    private static JsonNode inputWithFilePath(String filePath) {
        ObjectNode node = JsonUtils.getMapper().createObjectNode();
        node.put("file_path", filePath);
        return node;
    }

    private static ToolUseBlock tub(String name, JsonNode input) {
        return new ToolUseBlock("tu-1", name, input);
    }

    private static void run(DefaultQuerySession engine, ToolUseBlock block) {
        new ConcurrentToolRunner().run(List.of(block), engine, false, 1, _ -> {});
    }

    @Test
    void successfulEdit_notifiesListenerWithPathAndToolName() {
        DefaultQuerySession engine = newEngine((_, _, _) -> ToolResult.success("ok"));
        AtomicReference<Path> notifiedPath = new AtomicReference<>();
        AtomicReference<String> notifiedTool = new AtomicReference<>();
        engine.setFileChangeListener((path, toolName) -> { notifiedPath.set(path); notifiedTool.set(toolName); });

        run(engine, tub("Edit", inputWithFilePath("/tmp/Foo.java")));

        assertEquals(Path.of("/tmp/Foo.java"), notifiedPath.get());
        assertEquals("Edit", notifiedTool.get());
    }

    @Test
    void successfulWrite_notifiesListener() {
        DefaultQuerySession engine = newEngine((_, _, _) -> ToolResult.success("ok"));
        AtomicReference<String> notifiedTool = new AtomicReference<>();
        engine.setFileChangeListener((_, toolName) -> notifiedTool.set(toolName));

        run(engine, tub("Write", inputWithFilePath("/tmp/Bar.ts")));

        assertEquals("Write", notifiedTool.get());
    }

    @Test
    void failedEdit_doesNotNotifyListener() {
        DefaultQuerySession engine = newEngine((_, _, _) -> ToolResult.error("boom"));
        AtomicReference<Path> notified = new AtomicReference<>();
        engine.setFileChangeListener((path, _) -> notified.set(path));

        run(engine, tub("Edit", inputWithFilePath("/tmp/Foo.java")));

        assertNull(notified.get());
    }

    @Test
    void unrelatedTool_doesNotNotifyListener() {
        DefaultQuerySession engine = newEngine((_, _, _) -> ToolResult.success("ok"));
        AtomicReference<Path> notified = new AtomicReference<>();
        engine.setFileChangeListener((path, _) -> notified.set(path));

        run(engine, tub("Bash", inputWithFilePath("/tmp/Foo.java")));

        assertNull(notified.get());
    }

    @Test
    void noListenerInstalled_doesNotThrow() {
        DefaultQuerySession engine = newEngine((_, _, _) -> ToolResult.success("ok"));
        // fileChangeListener intentionally left unset (null)

        assertDoesNotThrow(() -> run(engine, tub("Edit", inputWithFilePath("/tmp/Foo.java"))));
    }

    @Test
    void missingFilePathInInput_doesNotThrowOrNotify() {
        DefaultQuerySession engine = newEngine((_, _, _) -> ToolResult.success("ok"));
        AtomicReference<Path> notified = new AtomicReference<>();
        engine.setFileChangeListener((path, _) -> notified.set(path));

        ObjectNode emptyInput = JsonUtils.getMapper().createObjectNode();
        assertDoesNotThrow(() -> run(engine, tub("Edit", emptyInput)));
        assertNull(notified.get());
    }
}
