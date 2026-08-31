package com.claudecode.runtime.query;

import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.WorkingDirectoryController;

import com.claudecode.core.prompt.SystemPromptRuntime;
import com.claudecode.core.state.CwdState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.*;

class QueryEngineWorkingDirectoryTest {

    private static final StreamingClient NOOP_CLIENT = new StreamingClient() {
        @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
            return List.<StreamingEvent>of().iterator();
        }
        @Override public String getModel() { return "test-model"; }
    };

    private final String originalUserDir = System.getProperty("user.dir");

    @AfterEach
    void restoreProcessState() {
        System.setProperty("user.dir", originalUserDir);
        CwdState.clearForTesting();
    }

    @Test
    void mainEngineControllerUpdatesLiveCwdAndIncludesAdditionalDirectories(@TempDir Path root)
            throws Exception {
        Path project = Files.createDirectory(root.resolve("project")).toRealPath();
        Path child = Files.createDirectory(project.resolve("child")).toRealPath();
        Path additional = Files.createDirectory(root.resolve("additional")).toRealPath();
        CwdState.setOriginalCwd(project);
        QuerySessionSpec config = QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .workingDirectory(project.toString())
            .promptRuntimeSupplier(() -> new SystemPromptRuntime(
                null, false, null, false, List.of(), List.of(additional.toString()),
                false, null, null))
            .build();
        DefaultQuerySession engine = new DefaultQuerySession(config);

        WorkingDirectoryController controller = engine.workingDirectoryController();
        assertTrue(controller.mutable());
        assertEquals(List.of(project, additional), controller.allowedDirectories());

        controller.update(project, child);

        assertEquals(child.toString(), config.workingDirectory());
        assertEquals(child.toString(), System.getProperty("user.dir"));
        assertEquals(project.toString(), config.initialWorkingDirectory());
    }

    @Test
    void subAgentControllerCannotMutateItsIsolatedCwd(@TempDir Path project) {
        QuerySessionSpec config = QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .workingDirectory(project.toString())
            .agentId("agent-1")
            .build();
        DefaultQuerySession engine = new DefaultQuerySession(config);
        WorkingDirectoryController controller = engine.workingDirectoryController();

        assertFalse(controller.mutable());
        controller.update(project, project.resolve("child"));
        assertEquals(project.toString(), config.workingDirectory());
    }

}
