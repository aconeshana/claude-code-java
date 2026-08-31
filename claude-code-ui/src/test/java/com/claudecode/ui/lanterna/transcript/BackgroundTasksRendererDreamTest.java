package com.claudecode.ui.lanterna.transcript;

import com.claudecode.tools.tasks.DreamTaskDetails;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskStore;
import com.claudecode.tools.tasks.TaskType;
import com.claudecode.tools.tasks.ForegroundShellTask;
import com.claudecode.tools.tasks.TaskOutputPaths;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.nio.file.Path;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BackgroundTasksRendererDreamTest {

    @Test
    void agentDetailTitleUsesRaw197AgentTypeWhileKeepingDescription() {
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        TaskState task = registry.store().create(TaskType.LOCAL_AGENT,
            "调查终端 UI 换行渲染 bug 与工具调用间距问题");
        registry.store().updateAgentType(task.id(), "general-purpose");

        assertEquals(
            "general-purpose › 调查终端 UI 换行渲染 bug 与工具调用间距问题",
            new BackgroundTasksRenderer(registry).agentDetailTitle(task));
    }

    @Test
    void agentDetailTitleMatches197FallbacksWhenMetadataIsMissing() {
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        TaskState task = registry.store().create(TaskType.LOCAL_AGENT, "");

        assertEquals("agent › Async agent",
            new BackgroundTasksRenderer(registry).agentDetailTitle(task));
    }

    @Test
    void agentDetailUsesOriginalPromptInsteadOfShortDescription() {
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        TaskState task = registry.store().create(TaskType.LOCAL_AGENT, "inspect UI");
        registry.store().updatePrompt(task.id(), "Inspect the terminal UI rendering in detail");

        assertEquals("Inspect the terminal UI rendering in detail",
            new BackgroundTasksRenderer(registry).agentPrompt(task));
    }

    @Test
    void visibleDreamTurns_filtersEmptyTextAndKeepsSixMostRecent() {
        List<DreamTaskDetails.DreamTurn> turns = IntStream.range(0, 9)
            .mapToObj(i -> new DreamTaskDetails.DreamTurn(i == 4 ? "" : "turn-" + i, i))
            .toList();
        DreamTaskDetails details = new DreamTaskDetails(
            DreamTaskDetails.DreamPhase.UPDATING, 3, List.of("MEMORY.md"), turns);

        assertEquals(List.of("turn-2", "turn-3", "turn-5", "turn-6", "turn-7", "turn-8"),
            BackgroundTasksRenderer.visibleDreamTurns(details).stream()
                .map(DreamTaskDetails.DreamTurn::text)
                .toList());
    }

    @Test
    void shellStatusIncludesReleasedExitCodeAnnotation() {
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        TaskState task = registry.store().create(TaskType.LOCAL_BASH, "false");
        registry.store().updateStatus(task.id(), TaskStatus.RUNNING);
        registry.store().updateExitCode(task.id(), 7);
        registry.store().updateStatus(task.id(), TaskStatus.FAILED);

        assertEquals("failed (exit code: 7)",
            BackgroundTasksRenderer.statusText(registry.get(task.id()).orElseThrow()));
    }

    @Test
    void shellDetailsUseOutputOfForegroundProcessAfterCtrlB() {
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        TaskState task = registry.store().create(TaskType.LOCAL_BASH, "long build");
        registry.store().updateStatus(task.id(), TaskStatus.RUNNING);
        Path output = Path.of(System.getProperty("java.io.tmpdir"), task.id() + ".output");
        registry.registerShellForeground(new ForegroundShellTask(
            task, registry.store(), output, () -> {}));

        assertEquals(output, new BackgroundTasksRenderer(registry).shellOutputPath(task.id()));
    }

    @Test
    void completedShellStillResolvesCanonicalOutputAfterLiveHandleIsReleased() {
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        TaskState task = registry.store().create(TaskType.LOCAL_BASH, "build");

        assertEquals(TaskOutputPaths.outputPath(task.id()),
            new BackgroundTasksRenderer(registry).shellOutputPath(task.id()));
    }
}
