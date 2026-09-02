package com.claudecode.tools.tasks;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.feature.FeatureGate;
import com.claudecode.runtime.tasks.TaskBoardPort;
import com.claudecode.tools.agent.NoOpSubAgentFactory;
import com.claudecode.tools.agent.SubAgentRequest;
import com.claudecode.tools.agent.SubAgentResult;
import com.claudecode.tools.tasks.teammate.TeammateContext;
import com.claudecode.tools.tasks.teammate.TeammateContextHolder;
import com.claudecode.tools.tasks.teammate.TeammateMailbox;
import com.fasterxml.jackson.databind.JsonNode;
import com.claudecode.tools.tasks.teammate.AgentTeamsEnabled;
import com.claudecode.tools.ValidationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TaskSystemTest {

    @AfterEach
    void resetTaskTestState() {
        AgentTeamsEnabled.resetForTest();
        TodoStore.setTaskLifecycleHooks(null);
        TeamTaskListRegistry.instance().clearForTest();
        TeammateContextHolder.clear();
        TeammateMailbox.instance().clearAll();
    }

    // --- TaskIdGenerator ---

    @Test
    void generatedIdStartsWithTypePrefix() {
        String id = TaskIdGenerator.generate(TaskType.LOCAL_BASH);
        assertTrue(Strings.CS.startsWith(id, "b"));
        assertEquals(9, id.length()); // 1 prefix + 8 random
    }

    @Test
    void generatedIdsAreUnique() {
        String id1 = TaskIdGenerator.generate(TaskType.LOCAL_AGENT);
        String id2 = TaskIdGenerator.generate(TaskType.LOCAL_AGENT);
        assertNotEquals(id1, id2);
    }

    @Test
    void extractTypeFromId() {
        assertEquals(TaskType.LOCAL_BASH, TaskIdGenerator.extractType("b12345678"));
        assertEquals(TaskType.LOCAL_AGENT, TaskIdGenerator.extractType("a12345678"));
        assertEquals(TaskType.IN_PROCESS_TEAMMATE, TaskIdGenerator.extractType("t12345678"));
    }

    @Test
    void extractTypeFromInvalidIdThrows() {
        assertThrows(IllegalArgumentException.class, () -> TaskIdGenerator.extractType("z12345678"));
        assertThrows(IllegalArgumentException.class, () -> TaskIdGenerator.extractType(null));
    }

    // --- TaskStateMachine ---

    @Test
    void validTransitions() {
        assertTrue(TaskStateMachine.isValidTransition(TaskStatus.PENDING, TaskStatus.RUNNING));
        assertTrue(TaskStateMachine.isValidTransition(TaskStatus.PENDING, TaskStatus.KILLED));
        assertTrue(TaskStateMachine.isValidTransition(TaskStatus.RUNNING, TaskStatus.COMPLETED));
        assertTrue(TaskStateMachine.isValidTransition(TaskStatus.RUNNING, TaskStatus.FAILED));
        assertTrue(TaskStateMachine.isValidTransition(TaskStatus.RUNNING, TaskStatus.KILLED));
        assertTrue(TaskStateMachine.isValidTransition(TaskStatus.RUNNING, TaskStatus.PAUSED));
    }

    @Test
    void invalidTransitions() {
        assertFalse(TaskStateMachine.isValidTransition(TaskStatus.COMPLETED, TaskStatus.RUNNING));
        assertFalse(TaskStateMachine.isValidTransition(TaskStatus.FAILED, TaskStatus.RUNNING));
        assertFalse(TaskStateMachine.isValidTransition(TaskStatus.KILLED, TaskStatus.RUNNING));
        assertFalse(TaskStateMachine.isValidTransition(TaskStatus.PAUSED, TaskStatus.RUNNING));
        assertFalse(TaskStateMachine.isValidTransition(TaskStatus.PENDING, TaskStatus.COMPLETED));
    }

    @Test
    void validateTransitionThrowsOnInvalid() {
        assertThrows(IllegalStateException.class,
            () -> TaskStateMachine.validateTransition(TaskStatus.COMPLETED, TaskStatus.RUNNING));
    }

    // --- TaskState ---

    @Test
    void taskStateCreation() {
        TaskState task = TaskState.create(TaskType.LOCAL_BASH, "Run tests");
        assertEquals(TaskStatus.PENDING, task.status());
        assertEquals(TaskType.LOCAL_BASH, task.type());
        assertEquals("Run tests", task.description());
        assertTrue(Strings.CS.startsWith(task.id(), "b"));
    }

    @Test
    void taskStateTransition() {
        TaskState task = TaskState.create(TaskType.LOCAL_BASH, "Run tests");
        TaskState running = task.withStatus(TaskStatus.RUNNING);
        assertEquals(TaskStatus.RUNNING, running.status());

        TaskState completed = running.withStatus(TaskStatus.COMPLETED);
        assertEquals(TaskStatus.COMPLETED, completed.status());
        assertTrue(completed.endTime().isPresent());
    }

    @Test
    void taskStateInvalidTransitionThrows() {
        TaskState task = TaskState.create(TaskType.LOCAL_BASH, "Run tests");
        assertThrows(IllegalStateException.class, () -> task.withStatus(TaskStatus.COMPLETED));
    }

    // --- TaskStore ---

    @Test
    void taskStoreCrud() {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.LOCAL_BASH, "Build project");
        assertEquals(1, store.size());

        assertTrue(store.get(task.id()).isPresent());
        assertEquals(1, store.list().size());

        store.updateStatus(task.id(), TaskStatus.RUNNING);
        assertEquals(TaskStatus.RUNNING, store.get(task.id()).get().status());
    }

    @Test
    void taskStoreCompletionNotification() {
        TaskStore store = TaskStore.inMemory();
        List<TaskState> completed = new ArrayList<>();
        store.onCompletion(completed::add);

        TaskState task = store.create(TaskType.LOCAL_BASH, "Build");
        store.updateStatus(task.id(), TaskStatus.RUNNING);
        assertTrue(completed.isEmpty());

        store.updateStatus(task.id(), TaskStatus.COMPLETED);
        assertEquals(1, completed.size());
        assertEquals(TaskStatus.COMPLETED, completed.getFirst().status());
    }

    @Test
    void taskStoreListByStatus() {
        TaskStore store = TaskStore.inMemory();
        store.create(TaskType.LOCAL_BASH, "Task 1");
        TaskState t2 = store.create(TaskType.LOCAL_AGENT, "Task 2");
        store.updateStatus(t2.id(), TaskStatus.RUNNING);

        assertEquals(1, store.listByStatus(TaskStatus.PENDING).size());
        assertEquals(1, store.listByStatus(TaskStatus.RUNNING).size());
    }

    // --- Task CRUD Tools ---

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolExecutionContext testContext() {
        return ToolExecutionContext.of(new AbortController(), "test-session");
    }

    @Test
    void taskCreateToolCreatesTask() {
        TodoStore store = TodoStore.inMemory();
        var tool = new TaskCreateTool(store);

        ObjectNode input = MAPPER.createObjectNode();
        input.put("subject", "Run tests");
        input.put("description", "Run the full test suite");

        String result = tool.call(input, testContext());
        assertTrue(Strings.CS.startsWith(result, "Task #"));
        assertEquals(1, store.list().size());
    }

    @Test
    void taskCreateToolIsNotConcurrencySafeInReleased197() {
        assertFalse(new TaskCreateTool(TodoStore.inMemory()).isConcurrencySafe());
    }

    @Test
    void taskCreateExpandsOnlyAfterSuccessfulHooks() throws Exception {
        TodoStore store = TodoStore.inMemory();
        TaskBoardService service = new TaskBoardService(store, () -> "test-session");
        AtomicInteger intents = new AtomicInteger();
        AutoCloseable subscription = service.subscribeIntents(_ -> intents.incrementAndGet());
        var tool = new TaskCreateTool(service);
        ObjectNode input = MAPPER.createObjectNode()
            .put("subject", "Run tests")
            .put("description", "Run the full test suite");

        tool.call(input, testContext());

        assertEquals(1, intents.get());
        assertEquals(List.of("Run tests"), service.snapshot().tasks().stream()
            .map(TaskBoardPort.TaskItem::subject).toList());
        subscription.close();
    }

    @Test
    void taskCreateBlockingHookRollsBackWithoutExpanding() throws Exception {
        TodoStore store = TodoStore.inMemory();
        TaskBoardService service = new TaskBoardService(store, () -> "test-session");
        AtomicInteger intents = new AtomicInteger();
        AutoCloseable subscription = service.subscribeIntents(_ -> intents.incrementAndGet());
        TodoStore.setTaskLifecycleHooks(new TaskLifecycleHooks() {
            @Override public boolean hasTaskCreatedHook() { return true; }
            @Override public List<String> dispatchTaskCreated(
                    String taskId, String subject, String description) {
                return List.of("blocked");
            }
            @Override public boolean hasTaskCompletedHook() { return false; }
            @Override public List<String> dispatchTaskCompleted(
                    String taskId, String subject, String description) {
                return List.of();
            }
        });
        var tool = new TaskCreateTool(service);
        ObjectNode input = MAPPER.createObjectNode()
            .put("subject", "Run tests")
            .put("description", "Run the full test suite");

        RuntimeException error = assertThrows(RuntimeException.class,
            () -> tool.call(input, testContext()));

        assertEquals("blocked", error.getMessage());
        assertTrue(store.list().isEmpty());
        assertEquals(0, intents.get());
        subscription.close();
    }

    @Test
    void taskCreateBlockingHookDoesNotPublishWhenRollbackCannotDeleteLikeReleased197()
            throws Exception {
        TodoStore store = TodoStore.inMemory();
        TaskBoardService service = new TaskBoardService(store, () -> "test-session");
        AtomicInteger snapshots = new AtomicInteger();
        AutoCloseable subscription = service.subscribe(_ -> snapshots.incrementAndGet());
        TodoStore.setTaskLifecycleHooks(new TaskLifecycleHooks() {
            @Override public boolean hasTaskCreatedHook() { return true; }
            @Override public List<String> dispatchTaskCreated(
                    String taskId, String subject, String description) {
                assertTrue(store.delete(taskId));
                return List.of("blocked");
            }
            @Override public boolean hasTaskCompletedHook() { return false; }
            @Override public List<String> dispatchTaskCompleted(
                    String taskId, String subject, String description) {
                return List.of();
            }
        });
        var tool = new TaskCreateTool(service);
        ObjectNode input = MAPPER.createObjectNode()
            .put("subject", "Run tests")
            .put("description", "Run the full test suite");

        assertThrows(RuntimeException.class, () -> tool.call(input, testContext()));

        assertEquals(0, snapshots.get());
        subscription.close();
    }

    @Test
    void taskLifecycleHooksReceiveReleasedTeammateAndTeamContext() {
        TodoStore store = TodoStore.inMemory();
        TeamTaskListRegistry.instance().register("alpha", store);
        TaskBoardService service = new TaskBoardService(store, () -> "test-session");
        List<String> observed = new ArrayList<>();
        TodoStore.setTaskLifecycleHooks(new TaskLifecycleHooks() {
            @Override public boolean hasTaskCreatedHook() { return true; }
            @Override public List<String> dispatchTaskCreated(
                    String taskId, String subject, String description) {
                return List.of();
            }
            @Override public List<String> dispatchTaskCreated(
                    String taskId, String subject, String description,
                    String teammateName, String teamName) {
                observed.add("created:" + teammateName + ":" + teamName);
                return List.of();
            }
            @Override public boolean hasTaskCompletedHook() { return true; }
            @Override public List<String> dispatchTaskCompleted(
                    String taskId, String subject, String description) {
                return List.of();
            }
            @Override public List<String> dispatchTaskCompleted(
                    String taskId, String subject, String description,
                    String teammateName, String teamName) {
                observed.add("completed:" + teammateName + ":" + teamName);
                return List.of();
            }
        });
        TeammateContext teammate = TeammateContext.builder()
            .agentId("agent-a")
            .teamId("alpha")
            .name("reviewer")
            .build();

        TeammateContextHolder.runWithContext(teammate, () -> {
            ObjectNode createInput = MAPPER.createObjectNode()
                .put("subject", "Implement")
                .put("description", "");
            new TaskCreateTool(service).call(createInput, testContext());
            ObjectNode updateInput = MAPPER.createObjectNode()
                .put("taskId", "1")
                .put("status", "completed");
            new TaskUpdateTool(service).call(updateInput, testContext());
        });

        assertEquals(List.of("created:reviewer:alpha", "completed:reviewer:alpha"), observed);
        service.close();
    }

    @Test
    void taskListToolListsTasks() {
        TodoStore store = TodoStore.inMemory();
        store.create("Task 1", "First task", null, null);
        var tool = new TaskListTool(store);

        String result = tool.call(MAPPER.createObjectNode(), testContext());
        assertTrue(Strings.CS.contains(result, "Task 1"));
    }

    @Test
    void taskGetToolGetsTask() {
        TodoStore store = TodoStore.inMemory();
        Task task = store.create("Task 1", "First task", null, null);
        var tool = new TaskGetTool(store);

        ObjectNode input = MAPPER.createObjectNode();
        input.put("taskId", task.id());

        String result = tool.call(input, testContext());
        assertTrue(Strings.CS.contains(result, task.id()));
    }

    @Test
    void taskStopToolKillsTask() {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.LOCAL_BASH, "Task 1");
        store.updateStatus(task.id(), TaskStatus.RUNNING);
        var tool = new TaskStopTool(store);

        ObjectNode input = MAPPER.createObjectNode();
        input.put("task_id", task.id());

        String result = tool.call(input, testContext());
        assertTrue(Strings.CS.contains(result, "stopped"));
        assertEquals(TaskStatus.KILLED, store.get(task.id()).get().status());
    }

    @Test
    void taskStopValidation_matchesTsPreconditions() {
        TaskStore store = TaskStore.inMemory();
        var tool = new TaskStopTool(store);

        assertInstanceOf(ValidationResult.Invalid.class,
            tool.validateInput(MAPPER.createObjectNode(), testContext()));

        ObjectNode unknown = MAPPER.createObjectNode().put("task_id", "missing");
        ValidationResult.Invalid unknownError = assertInstanceOf(ValidationResult.Invalid.class,
            tool.validateInput(unknown, testContext()));
        assertEquals("No task found with ID: missing", unknownError.message());

        TaskState task = store.create(TaskType.LOCAL_BASH, "done");
        store.updateStatus(task.id(), TaskStatus.RUNNING);
        store.updateStatus(task.id(), TaskStatus.COMPLETED);
        ObjectNode completed = MAPPER.createObjectNode().put("task_id", task.id());
        ValidationResult.Invalid completedError = assertInstanceOf(ValidationResult.Invalid.class,
            tool.validateInput(completed, testContext()));
        assertEquals("Task " + task.id() + " is not running (status: completed)",
            completedError.message());
    }

    @Test
    void taskStopToolKillsLiveHandleViaRegistry() {
// Background tasks live in TaskRegistry.global's in-memory store and
        // their live process handle is registered there. TaskStopTool must route
        // the kill through the registry so the underlying task is actually
        // terminated (HIGH fix #1) — not merely have its status flipped.
        TaskRegistry registry = TaskRegistry.global();
        TaskStore store = registry.store();
        TaskState task = store.create(TaskType.LOCAL_BASH, "sleep 1000");
        store.updateStatus(task.id(), TaskStatus.RUNNING);
// Register a live handle; kill flips status to KILLED (no real process
        // here, so the destroy is skipped, but the transition must still happen).
        registry.registerShell(new LocalShellTask(task, "sleep 1000", store));

        var tool = new TaskStopTool(store);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("task_id", task.id());

        String result = tool.call(input, testContext());

        assertTrue(Strings.CS.contains(result, "\"task_type\":\"local_bash\""), result);
        assertTrue(Strings.CS.contains(result, "\"task_id\":\"" + task.id() + "\""), result);
        // The kill actually propagated through the registry to the handle.
        assertEquals(TaskStatus.KILLED, store.get(task.id()).get().status());
    }

    @Test
    void taskUpdateToolUpdatesDescription() {
        TodoStore store = TodoStore.inMemory();
        Task task = store.create("Task 1", "Old desc", null, null);
        var tool = new TaskUpdateTool(store);

        ObjectNode input = MAPPER.createObjectNode();
        input.put("taskId", task.id());
        input.put("description", "New desc");

        String result = tool.call(input, testContext());
        assertTrue(Strings.CS.contains(result, "description"));
        assertEquals("New desc", store.get(task.id()).get().description());
    }

    @Test
    void taskUpdateMissingTaskStillExpandsWithoutPublishingSnapshot() throws Exception {
        TodoStore store = TodoStore.inMemory();
        TaskBoardService service = new TaskBoardService(store, () -> "test-session");
        AtomicInteger intents = new AtomicInteger();
        AtomicInteger snapshots = new AtomicInteger();
        AutoCloseable intentSubscription = service.subscribeIntents(_ -> intents.incrementAndGet());
        AutoCloseable snapshotSubscription = service.subscribe(_ -> snapshots.incrementAndGet());
        var tool = new TaskUpdateTool(service);
        ObjectNode input = MAPPER.createObjectNode()
            .put("taskId", "missing")
            .put("status", "completed");

        var result = tool.callWithResult(input, testContext());

        assertEquals("Task not found", result.rawResult());
        assertEquals(1, intents.get());
        assertEquals(0, snapshots.get());
        intentSubscription.close();
        snapshotSubscription.close();
    }

    @Test
    void taskUpdateDoesNotAutoAssignTeamLeadWhenNoTeammateContext() {
        AgentTeamsEnabled.setEnabledForTest(true);
        TodoStore store = TodoStore.inMemory();
        Task task = store.create("Task 1", "Work", null, null);
        var tool = new TaskUpdateTool(store);
        ObjectNode input = MAPPER.createObjectNode()
            .put("taskId", task.id())
            .put("status", "in_progress");

        tool.call(input, testContext());
        assertTrue(store.get(task.id()).orElseThrow().owner().isEmpty(),
            "TS getAgentName() is undefined for the leader, so no owner is assigned");
    }

    @Test
    void emptyOwnerIsPersistedButNotRenderedOrNotified() {
        AgentTeamsEnabled.setEnabledForTest(true);
        TodoStore store = TodoStore.inMemory();
        Task task = store.create("Task 1", "Work", null, null);
        ObjectNode input = MAPPER.createObjectNode()
            .put("taskId", task.id())
            .put("owner", "");

        new TaskUpdateTool(store).call(input, testContext());

        assertEquals("", store.get(task.id()).orElseThrow().owner().orElseThrow());
        assertEquals("#1 [pending] Task 1", new TaskListTool(store).call(
            MAPPER.createObjectNode(), testContext()));
        assertNull(TeammateMailbox.instance().poll(""));
    }

    @Test
    void emptyOwnerIsFalsyForReleased197AutomaticTeammateOwnership() {
        AgentTeamsEnabled.setEnabledForTest(true);
        TodoStore store = TodoStore.inMemory();
        Task task = store.create("Task 1", "Work", null, null);
        store.update(task.id(), task.withOwner(""));
        TeamTaskListRegistry.instance().register("team-1", store);
        TeammateContextHolder.set(TeammateContext.builder()
            .agentId("agent-1")
            .teamId("team-1")
            .name("reviewer")
            .abortController(new AbortController())
            .build());
        ObjectNode input = MAPPER.createObjectNode()
            .put("taskId", task.id())
            .put("status", "in_progress");

        new TaskUpdateTool(store).call(input, testContext());

        assertEquals("reviewer", store.get(task.id()).orElseThrow()
            .owner().orElseThrow());
    }

    @Test
    void taskUpdateToolPreservesStatusChangeInStructuredResult() {
        TodoStore store = TodoStore.inMemory();
        Task task = store.create("Task 1", "Old desc", null, null);
        var tool = new TaskUpdateTool(store);

        ObjectNode input = MAPPER.createObjectNode();
        input.put("taskId", task.id());
        input.put("status", "completed");

        ToolExecutionContext context = testContext();
        var invocation = tool.callWithResult(input, context);
        String text = invocation.rawResult();
        var mapped = invocation.mappedResult();
        assertNotNull(mapped);
        var payload = (JsonNode) mapped.toolUseResult();
        assertTrue(payload.path("success").asBoolean());
        assertEquals("pending", payload.path("statusChange").path("from").asText());
        assertEquals("completed", payload.path("statusChange").path("to").asText());
        assertEquals("status", payload.path("updatedFields").get(0).asText());
    }

    @Test
    void released197TaskUpdateDoesNotAddJavaSpecificVerificationNudge() {
        FeatureGate.withFlags(() -> {
            TodoStore store = TodoStore.inMemory();
            Task first = store.create("Task 1", "one", null, null);
            Task second = store.create("Task 2", "two", null, null);
            Task third = store.create("Task 3", "three", null, null);
            var tool = new TaskUpdateTool(store);
            ToolExecutionContext context = testContext();
            for (Task task : List.of(first, second, third)) {
                ObjectNode input = MAPPER.createObjectNode();
                input.put("taskId", task.id());
                input.put("status", "completed");
                var invocation = tool.callWithResult(input, context);
                String text = invocation.rawResult();
                var mapped = invocation.mappedResult();
                assertNotNull(mapped);
                assertFalse(Strings.CS.contains(
                    mapped.content().getFirst().toString(), "verification agent"));
                assertFalse(((JsonNode) mapped.toolUseResult())
                    .has("verificationNudgeNeeded"));
            }
        }, FeatureGate.Flag.VERIFICATION_AGENT_NUDGE);
    }

    @Test
    void completionNudgeUsesReleased197AgentIdTruthiness() {
        AgentTeamsEnabled.setEnabledForTest(true);
        TodoStore store = TodoStore.inMemory();
        Task task = store.create("Task 1", "one", null, null);
        ObjectNode input = MAPPER.createObjectNode()
            .put("taskId", task.id())
            .put("status", "completed");
        ToolExecutionContext emptyAgent = ToolExecutionContext.builder(
                new AbortController(), "test-session")
            .agentId("")
            .build();

        var mapped = new TaskUpdateTool(store)
            .callWithResult(input, emptyAgent)
            .mappedResult();

        assertNotNull(mapped);
        assertFalse(Strings.CS.contains(
            mapped.content().getFirst().toString(), "Call TaskList now"));
    }

    @Test
    void taskUpdateAutoClassifierUsesJavaScriptTruthinessForWhitespaceFields() {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("taskId", "id");
        input.put("status", " ");
        input.put("subject", "\t");

        assertEquals("id   \t",
            new TaskUpdateTool(TodoStore.inMemory()).toAutoClassifierInput(input));
    }

    @Test
    void taskUpdateAutoClassifierRepairsReleased197TaskIdAliases() {
        ObjectNode aliased = MAPPER.createObjectNode();
        aliased.put("task_id", "17");
        aliased.put("status", "in_progress");

        assertEquals("17 in_progress",
            new TaskUpdateTool(TodoStore.inMemory()).toAutoClassifierInput(aliased));

        ObjectNode unicodeBlankAlias = MAPPER.createObjectNode();
        unicodeBlankAlias.put("id", "\u00a0\ufeff");
        assertEquals("",
            new TaskUpdateTool(TodoStore.inMemory())
                .toAutoClassifierInput(unicodeBlankAlias));

        ObjectNode javaOnlyWhitespaceAlias = MAPPER.createObjectNode();
        javaOnlyWhitespaceAlias.put("id", "\u001c");
        assertEquals("\u001c",
            new TaskUpdateTool(TodoStore.inMemory())
                .toAutoClassifierInput(javaOnlyWhitespaceAlias));
    }

    // --- LocalAgentTask ---

    @Test
    void localAgentTaskProgress() {
        TaskState task = TaskState.create(TaskType.LOCAL_AGENT, "Agent task");
        var agent = new LocalAgentTask(task);
        assertEquals(0.0, agent.getProgress());

        agent.updateProgress(0.5, "processing");
        assertEquals(0.5, agent.getProgress());
        assertEquals("processing", agent.getCurrentStep());

        agent.complete(SubAgentResult.of("done"));
        assertEquals(1.0, agent.getProgress());
    }

    // --- InProcessTeammateTask ---

    @Test
    void inProcessTeammateStartStop() {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        var teammate = new InProcessTeammateTask(
            task, store, new NoOpSubAgentFactory(),
            SubAgentRequest.builder().prompt("explore").parentContext(testContext()).build(),
            TeammateContext.builder().agentId(task.id()).abortController(new AbortController()).build());
        assertFalse(teammate.isActive());

        teammate.start();
        assertTrue(teammate.isActive());

        teammate.stop();
        assertFalse(teammate.isActive());
    }
}
