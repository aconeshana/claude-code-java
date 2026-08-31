package com.claudecode.tools.agent;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.FileStateCache;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.queue.MessageQueueManager;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;


class AgentToolAsyncTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static void awaitTrue(BooleanSupplier condition) {
        Instant deadline = Instant.now().plusSeconds(10);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                fail("condition did not become true within 10s");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting for condition");
            }
        }
    }

/** A factory whose runSubAgent blocks until the test releases it. */
    private static class BlockingFactory implements SubAgentFactory {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean interrupted = new AtomicBoolean(false);

        @Override
        public SubAgentResult runSubAgent(SubAgentRequest request) {
            started.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
            return SubAgentResult.of("finished: " + request.prompt());
        }
    }

    /** Production-like factory that exposes the child request-dispatch boundary. */
    private static final class StartupAwareFactory extends BlockingFactory {
        final CountDownLatch allowFirstRequest = new CountDownLatch(1);

        @Override
        public boolean supportsFirstModelRequestSignal() {
            return true;
        }

        @Override
        public SubAgentResult runSubAgent(SubAgentRequest request) {
            started.countDown();
            try {
                allowFirstRequest.await(30, TimeUnit.SECONDS);
                request.beforeFirstModelRequest().run();
                request.awaitParentToolResultEmission().run();
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
            return SubAgentResult.of("finished: " + request.prompt());
        }
    }

    @Test
    void runInBackground_returnsOnlyAfterStartupAwareChildDispatchesFirstRequest(
            @TempDir Path outputDir) throws Exception {
        StartupAwareFactory factory = new StartupAwareFactory();
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        AgentTool tool = new AgentTool(factory, registry, outputDir);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("description", "background task");
        input.put("prompt", "do something slow");
        input.put("run_in_background", true);

        AtomicReference<ToolResult> result = new AtomicReference<>();
        CountDownLatch returned = new CountDownLatch(1);
        Thread caller = Thread.ofVirtual().start(() -> {
            result.set(tool.call(input, ToolExecutionContext.of(
                new AbortController(), "test-session")));
            returned.countDown();
        });

        assertTrue(factory.started.await(5, TimeUnit.SECONDS));
        assertFalse(returned.await(100, TimeUnit.MILLISECONDS),
            "async launch must not overtake the child's first model request");
        factory.allowFirstRequest.countDown();
        assertTrue(returned.await(5, TimeUnit.SECONDS),
            "launch should return once the child request is dispatched");
        assertNotNull(result.get());
        assertEquals(1L, factory.release.getCount(),
            "launch must not wait for the child response or completion");
        assertNotNull(result.get().afterResultEmitted());
        result.get().afterResultEmitted().run();
        @SuppressWarnings("unchecked")
        Map<String, Object> launch = (Map<String, Object>) result.get().toolUseResult();
        String agentId = (String) launch.get("agentId");

        factory.release.countDown();
        awaitTrue(() -> registry.get(agentId)
            .map(task -> task.status() == TaskStatus.COMPLETED).orElse(false));
        caller.join();
    }

    @Test
    void runInBackground_returnsImmediately_withoutWaitingForSubAgent(@TempDir Path outputDir) throws Exception {
        BlockingFactory factory = new BlockingFactory();
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        AgentTool tool = new AgentTool(factory, registry, outputDir);
        MessageQueueManager queue = new MessageQueueManager();
        ToolExecutionContext context = ToolExecutionContext
            .builder(new AbortController(), "test-session")
            .workingDirectory(outputDir.toString())
            .fileStateCache(new FileStateCache())
            .messageQueueManager(queue)
            .nestedMemoryAttachmentTriggers(Set.of())
            .loadedNestedMemoryPaths(Set.of())
            .currentModel("claude-sonnet-4-6")
            .toolUseId("toolu_agent_bg")
            .enabledTools(List.of("Read", "Bash"))
            .build();

        ObjectNode input = MAPPER.createObjectNode();
        input.put("description", "background task");
        input.put("prompt", "do something slow");
        input.put("run_in_background", true);

        long start = System.currentTimeMillis();
        ToolResult toolResult = tool.call(input, context);
        String result = text(toolResult);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 2000, "call() should return immediately, took " + elapsed + "ms");
        @SuppressWarnings("unchecked")
        Map<String, Object> launch = (Map<String, Object>) toolResult.toolUseResult();
        String agentId = (String) launch.get("agentId");
        Path outputFile = Path.of((String) launch.get("outputFile"));
        assertEquals("async_launched", launch.get("status"));
        assertEquals(Boolean.TRUE, launch.get("isAsync"));
        assertEquals("background task", launch.get("description"));
        assertEquals("general-purpose",
            registry.store().agentType(agentId).orElseThrow(),
            "omitting subagent_type selects the same general-purpose definition as 197");
        assertEquals("do something slow", launch.get("prompt"));
        assertEquals("claude-sonnet-4-6", launch.get("resolvedModel"));
        assertEquals(Boolean.TRUE, launch.get("canReadOutputFile"));
        assertEquals(outputDir.resolve(agentId + ".output"), outputFile);
        assertEquals("Async agent launched successfully.\n"
            + "agentId: " + agentId + " (internal ID - do not mention to user. Use SendMessage with to: '"
            + agentId + "', summary: '<5-10 word recap>' to continue this agent.)\n"
            + "The agent is working in the background. You will be notified automatically when it completes.\n"
            + "Do not duplicate this agent's work — avoid working with the same files or topics it is using.\n"
            + "output_file: " + outputFile + "\n"
            + "Do NOT Read or tail this file via the shell tool — it is the full subagent JSONL transcript "
            + "and reading it will overflow your context. If the user asks for progress, say the agent is "
            + "still running; you'll get a completion notification.", result);

        List<SDKMessage> launchEvents = queue.drainSdkEvents();
        assertEquals(1, launchEvents.size(),
            "released background Agent launch exposes task_started only; the sidechain prompt stays internal");
        SDKMessage.TaskStarted startedEvent = assertInstanceOf(
            SDKMessage.TaskStarted.class, launchEvents.getFirst());
        assertEquals(agentId, startedEvent.taskId());
        assertEquals("toolu_agent_bg", startedEvent.toolUseId());
        assertEquals("background task", startedEvent.description());
        assertEquals("local_agent", startedEvent.taskType());
        assertEquals("do something slow", startedEvent.prompt());
        assertNull(startedEvent.subagentType());

        // Factory must actually have been invoked (real async execution, not a stub).
        assertTrue(factory.started.await(5, TimeUnit.SECONDS), "sub-agent should have started running");
        assertEquals(1, registry.listBackground().size());
        assertEquals(TaskStatus.RUNNING, registry.listBackground().getFirst().status());
        assertEquals(agentId, registry.listBackground().getFirst().id(),
            "released 2.1.197 uses one id for task state, output, and agent routing");
        assertEquals("toolu_agent_bg", registry.listBackground().getFirst().toolUseId().orElseThrow());

        // Let it finish and verify the output file gets written.
        factory.release.countDown();
        String taskId = registry.listBackground().getFirst().id();
        awaitTrue(() -> registry.get(taskId).map(t -> t.status() == TaskStatus.COMPLETED).orElse(false));

        awaitTrue(() -> Files.isRegularFile(outputFile));
        String content = Files.readString(outputFile);
        assertTrue(Strings.CS.contains(content, "finished: do something slow"), content);
    }

    @Test
    void runInBackground_kill_interruptsRunnerAndMarksKilled(@TempDir Path outputDir) throws Exception {
        BlockingFactory factory = new BlockingFactory();
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        AgentTool tool = new AgentTool(factory, registry, outputDir);

        ObjectNode input = MAPPER.createObjectNode();
        input.put("description", "background task");
        input.put("prompt", "do something slow");
        input.put("run_in_background", true);

        tool.call(input, ToolExecutionContext.of(new AbortController(), "test-session"));
        assertTrue(factory.started.await(5, TimeUnit.SECONDS));

        String taskId = registry.listBackground().getFirst().id();
        boolean killed = registry.killAgent(taskId);

        assertTrue(killed);
        assertEquals(TaskStatus.KILLED, registry.get(taskId).get().status());
        awaitTrue(factory.interrupted::get);
        awaitTrue(() -> registry.getAgentHandle(taskId)
            .map(handle -> !handle.isRunnerAlive()).orElse(true));
    }

    @Test
    void synchronousCall_stillReturnsFormattedResult() {
        SubAgentFactory factory = _ -> SubAgentResult.of("sync result");
        AgentTool tool = new AgentTool(factory);

        ObjectNode input = MAPPER.createObjectNode();
        input.put("description", "sync task");
        input.put("prompt", "test");

        String result = text(tool.call(input,
            ToolExecutionContext.of(new AbortController(), "test-session")));

        assertFalse(Strings.CS.startsWith(result, "Done ("), result);
        assertTrue(Strings.CS.contains(result, "sync result"), result);
        assertTrue(Strings.CS.contains(result, "<usage>"), result);
    }

    @Test
    void synchronousAgent_parentAbortPropagatesAndUnblocksForegroundWait(
            @TempDir Path outputDir) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch childAborted = new CountDownLatch(1);
        AtomicReference<String> abortReason = new AtomicReference<>();
        SubAgentFactory factory = request -> {
            request.abortController().onAbort(() -> {
                abortReason.set(request.abortController().getReason());
                childAborted.countDown();
            });
            started.countDown();
            try {
                childAborted.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            return AgentExecutionResult.builder("Interrupted")
                .termination(SubAgentTermination.INTERRUPTED)
                .terminalError("Interrupted by user")
                .build();
        };
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        AgentTool tool = new AgentTool(factory, registry, outputDir);
        AbortController parent = new AbortController();
        ObjectNode input = MAPPER.createObjectNode();
        input.put("description", "foreground task");
        input.put("prompt", "inspect until cancelled");

        CountDownLatch returned = new CountDownLatch(1);
        Thread caller = Thread.ofVirtual().start(() -> {
            tool.call(input, ToolExecutionContext.of(parent, "test-session"));
            returned.countDown();
        });

        assertTrue(started.await(5, TimeUnit.SECONDS));
        parent.abort("user-cancel");

        assertTrue(childAborted.await(5, TimeUnit.SECONDS),
            "foreground child must observe the parent turn abort");
        assertEquals("user-cancel", abortReason.get());
        assertTrue(returned.await(5, TimeUnit.SECONDS),
            "AgentTool must not remain stuck in Delegating after Ctrl+C");
        caller.join();
    }

    @Test
    void synchronousAgent_canBeBackgroundedInPlaceWithoutRestarting(@TempDir Path outputDir)
            throws Exception {
        BlockingFactory factory = new BlockingFactory();
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        AgentTool tool = new AgentTool(factory, registry, outputDir);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("description", "foreground task");
        input.put("prompt", "inspect once");

        AtomicReference<ToolResult> returnedResult = new AtomicReference<>();
        CountDownLatch returned = new CountDownLatch(1);
        Thread caller = Thread.ofVirtual().start(() -> {
            returnedResult.set(tool.call(input,
                ToolExecutionContext.of(new AbortController(), "test-session")));
            returned.countDown();
        });

        assertTrue(factory.started.await(5, TimeUnit.SECONDS));
        assertFalse(returned.await(100, TimeUnit.MILLISECONDS));
        List<TaskState> foreground = registry.listForegroundBackgroundable();
        assertEquals(1, foreground.size());
        String taskId = foreground.getFirst().id();
        assertTrue(registry.listBackground().isEmpty());

        assertTrue(registry.backgroundAgent(taskId));
        assertTrue(returned.await(5, TimeUnit.SECONDS),
            "foreground tool call should return as soon as its live task is backgrounded");
        @SuppressWarnings("unchecked")
        Map<String, Object> launch = (Map<String, Object>) returnedResult.get().toolUseResult();
        assertEquals("async_launched", launch.get("status"));
        assertEquals(taskId, launch.get("agentId"));
        assertEquals(1L, factory.release.getCount(),
            "backgrounding must not interrupt or restart the already-running agent");
        assertEquals(1, registry.listBackground().size());
        assertEquals(taskId, registry.listBackground().getFirst().id());

        factory.release.countDown();
        awaitTrue(() -> registry.get(taskId)
            .map(task -> task.status() == TaskStatus.COMPLETED).orElse(false));
        caller.join();
    }

    @Test
    void backgroundedForegroundAgentConsumesQueuedContinuationAtTurnBoundary(
            @TempDir Path outputDir) throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        List<String> prompts = new CopyOnWriteArrayList<>();
        SubAgentFactory factory = request -> {
            int call = calls.incrementAndGet();
            prompts.add(request.prompt());
            if (call == 1) {
                firstStarted.countDown();
                try {
                    assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return SubAgentResult.of("first turn");
            }
            secondFinished.countDown();
            return SubAgentResult.of("second turn");
        };
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        AgentTool tool = new AgentTool(factory, registry, outputDir);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("description", "foreground continuation");
        input.put("prompt", "initial prompt");

        AtomicReference<ToolResult> launch = new AtomicReference<>();
        Thread caller = Thread.ofVirtual().start(() -> launch.set(tool.call(input,
            ToolExecutionContext.of(new AbortController(), "test-session"))));
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
        String taskId = registry.listForegroundBackgroundable().getFirst().id();
        assertTrue(registry.backgroundAgent(taskId));
        caller.join();
        assertTrue(registry.queueAgentMessage(taskId, "follow up"));

        releaseFirst.countDown();
        assertTrue(secondFinished.await(5, TimeUnit.SECONDS));
        awaitTrue(() -> registry.get(taskId)
            .map(task -> task.status() == TaskStatus.COMPLETED).orElse(false));
        assertEquals(List.of("initial prompt", "follow up"), prompts);
    }

    @Test
    void synchronousAgent_autoBackgroundsAfterConfiguredDelay(@TempDir Path outputDir)
            throws Exception {
        BlockingFactory factory = new BlockingFactory();
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        AgentTool tool = new AgentTool(factory, registry, outputDir, 25L);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("description", "auto background");
        input.put("prompt", "continue same execution");

        AtomicReference<ToolResult> returnedResult = new AtomicReference<>();
        CountDownLatch returned = new CountDownLatch(1);
        Thread caller = Thread.ofVirtual().start(() -> {
            returnedResult.set(tool.call(input,
                ToolExecutionContext.of(new AbortController(), "test-session")));
            returned.countDown();
        });

        assertTrue(factory.started.await(5, TimeUnit.SECONDS));
        assertTrue(returned.await(5, TimeUnit.SECONDS));
        @SuppressWarnings("unchecked")
        Map<String, Object> launch = (Map<String, Object>) returnedResult.get().toolUseResult();
        assertEquals("async_launched", launch.get("status"));
        assertEquals(1, registry.listBackground().size());
        assertEquals(1L, factory.release.getCount());

        String taskId = registry.listBackground().getFirst().id();
        factory.release.countDown();
        awaitTrue(() -> registry.get(taskId)
            .map(task -> task.status() == TaskStatus.COMPLETED).orElse(false));
        caller.join();
    }

    @Test
    void completedForegroundAgentCancelsConfiguredAutoBackgroundTimer(@TempDir Path outputDir)
            throws Exception {
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        AgentTool tool = new AgentTool(_ -> SubAgentResult.of("done"),
            registry, outputDir, 100L);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("description", "fast foreground");
        input.put("prompt", "finish before timer");

        ToolResult result = tool.call(input,
            ToolExecutionContext.of(new AbortController(), "test-session"));
        Thread.sleep(200L);

        assertTrue(Strings.CS.contains(text(result), "done"));
        assertTrue(registry.listForegroundBackgroundable().isEmpty());
        assertTrue(registry.listBackground().isEmpty(),
            "a stale 120s-style timer must not background an already completed agent");
    }

    @Test
    void foregroundAgentPublishesStructuredInitialProgress(@TempDir Path outputDir)
            throws Exception {
        BlockingFactory factory = new BlockingFactory();
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        AgentTool tool = new AgentTool(factory, registry, outputDir);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("description", "structured progress");
        input.put("prompt", "inspect progress");
        List<ToolExecutionContext.ProgressUpdate> updates = new CopyOnWriteArrayList<>();
        ToolExecutionContext base = ToolExecutionContext.of(
            new AbortController(), "test-session");
        ToolExecutionContext context = base.toBuilder()
            .progressSink(updates::add)
            .build();

        Thread caller = Thread.ofVirtual().start(() -> tool.call(input, context));
        assertTrue(factory.started.await(5, TimeUnit.SECONDS));
        awaitTrue(() -> updates.stream().anyMatch(update ->
            Strings.CS.equals("agent_progress", update.dataType())));
        ToolExecutionContext.ProgressUpdate initial = updates.stream()
            .filter(update -> Strings.CS.equals("agent_progress", update.dataType()))
            .findFirst().orElseThrow();
        assertEquals("inspect progress", initial.prompt());
        assertNotNull(initial.agentMessage());
        assertNotNull(initial.agentId());

        assertTrue(registry.backgroundAgent(initial.agentId()));
        caller.join();
        factory.release.countDown();
        awaitTrue(() -> registry.get(initial.agentId())
            .map(task -> task.status() == TaskStatus.COMPLETED).orElse(false));
    }

    @Test
    void disabledBackgroundTasks_keepSynchronousAgentUnregistered() {
        SubprocessEnvironment.replaceSettings(Map.of(
            "CLAUDE_CODE_DISABLE_BACKGROUND_TASKS", "1"));
        try {
            TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
            AgentTool tool = new AgentTool(_ -> SubAgentResult.of("done"), registry, null);
            ObjectNode input = MAPPER.createObjectNode();
            input.put("description", "strict foreground");
            input.put("prompt", "finish now");

            ToolResult result = tool.call(input,
                ToolExecutionContext.of(new AbortController(), "test-session"));

            assertTrue(Strings.CS.contains(text(result), "done"));
            assertTrue(registry.listForegroundBackgroundable().isEmpty());
            assertTrue(registry.listBackground().isEmpty());
        } finally {
            SubprocessEnvironment.clearSettings();
        }
    }

    @Test
    void finalizedChildUsageRefreshesTheForegroundAgentProgressCard() {
        SubprocessEnvironment.replaceSettings(Map.of(
            "CLAUDE_CODE_DISABLE_BACKGROUND_TASKS", "1"));
        try {
            List<ToolExecutionContext.ProgressUpdate> updates = new CopyOnWriteArrayList<>();
            AgentTool tool = new AgentTool(request -> {
                var childInput = JsonUtils.getMapper().createObjectNode();
                childInput.put("file_path", "alpha.java");
                AssistantMessage child = new AssistantMessage("assistant-1",
                    AssistantContent.of("message-1",
                        List.of(new ToolUseBlock("child-read", "Read", childInput)),
                        new Usage(100, 2, 20, 30)));
                request.progressCallback().onAgentMessage(child, request.agentId());
                request.progressCallback().onAgentUsage(
                    "assistant-1", new Usage(120, 7, 25, 35));
                return SubAgentResult.of("done");
            });
            ObjectNode input = MAPPER.createObjectNode();
            input.put("description", "usage progress");
            input.put("prompt", "inspect usage");
            ToolExecutionContext context = ToolExecutionContext
                .builder(new AbortController(), "test-session")
                .progressSink(updates::add)
                .build();

            tool.call(input, context);

            AssistantMessage finalized = updates.stream()
                .filter(update -> update.agentMessage() instanceof AssistantMessage)
                .map(update -> (AssistantMessage) update.agentMessage())
                .reduce((_, right) -> right).orElseThrow();
            assertEquals(187, finalized.message().usage().inputTokens()
                + finalized.message().usage().outputTokens()
                + finalized.message().usage().cacheCreationInputTokens()
                + finalized.message().usage().cacheReadInputTokens());
        } finally {
            SubprocessEnvironment.clearSettings();
        }
    }

    private static String text(ToolResult result) {
        return result.content().stream()
            .filter(TextBlock.class::isInstance)
            .map(TextBlock.class::cast)
            .map(TextBlock::text)
            .reduce((left, right) -> left + "\n" + right)
            .orElse("");
    }
}
