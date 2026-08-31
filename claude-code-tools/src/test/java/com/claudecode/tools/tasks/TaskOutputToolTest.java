package com.claudecode.tools.tasks;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.tools.ValidationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.AfterEach;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TaskOutputToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    @AfterEach
    void resetTaskOutputPaths() {
        TaskOutputPaths.resetForTest();
    }

    private static ToolExecutionContext ctx() {
        return ToolExecutionContext.of(new AbortController(), "test");
    }

    @Test
    void schema_hasCorrectFields() {
        var schema = new TaskOutputTool(TaskStore.inMemory()).inputSchema();
        var props = schema.get("properties");
        assertTrue(props.has("task_id"));
        assertTrue(props.has("block"));
        assertTrue(props.has("timeout"));
    }

    @Test
    void antChannelDisablesDeprecatedOutputTool() {
        TaskOutputTool tool = new TaskOutputTool(TaskStore.inMemory(),
            key ->Strings.CS.equals( "USER_TYPE", key) ? "ant" : null);
        assertFalse(tool.isEnabled());

        TaskOutputTool external = new TaskOutputTool(TaskStore.inMemory(), _ -> null);
        assertTrue(external.isEnabled());
    }

    @Test
    void missingTaskId_returnsError() {
        ObjectNode n = M.createObjectNode();
        n.put("block", false);
        String r = new TaskOutputTool(TaskStore.inMemory()).call(n, ctx());
        assertTrue(Strings.CS.startsWith(r, "Error: task_id"), r);
    }

    @Test
    void unknownTask_returnsError() {
        ObjectNode n = M.createObjectNode();
        n.put("task_id", "nonexistent");
        n.put("block", false);
        String r = new TaskOutputTool(TaskStore.inMemory()).call(n, ctx());
        assertTrue(Strings.CS.startsWith(r, "Error: No task found"), r);
    }

    @Test
    void semanticValidation_matchesTsBeforeCall(@TempDir Path dir) {
        TaskStore store = new TaskStore(dir, "test");
        TaskOutputTool tool = new TaskOutputTool(store);

        ValidationResult missing = tool.validateInput(M.createObjectNode(), ctx());
        ValidationResult.Invalid missingError = assertInstanceOf(
            ValidationResult.Invalid.class, missing);
        assertEquals("Task ID is required", missingError.message());

        ObjectNode unknownInput = M.createObjectNode().put("task_id", "missing");
        ValidationResult unknown = tool.validateInput(unknownInput, ctx());
        ValidationResult.Invalid unknownError = assertInstanceOf(
            ValidationResult.Invalid.class, unknown);
        assertEquals("No task found with ID: missing", unknownError.message());
    }

    @Test
    void knownTask_nonBlocking_returnsStatus(@TempDir Path dir) {
        TaskStore store = new TaskStore(dir, "test");
        TaskState task = store.create(TaskType.LOCAL_BASH, "echo hello");
        store.updateStatus(task.id(), TaskStatus.RUNNING);
        store.updateStatus(task.id(), TaskStatus.COMPLETED);

        ObjectNode n = M.createObjectNode();
        n.put("task_id", task.id());
        n.put("block", false);

        String r = new TaskOutputTool(store).call(n, ctx());
        assertTrue(Strings.CS.contains(r, "<task_id>" + task.id()), r);
        assertTrue(Strings.CS.contains(r, "<retrieval_status>"), r);
        assertTrue(Strings.CS.contains(r, "<status>"), r);
        assertTrue(store.get(task.id()).orElseThrow().notified(),
            "terminal non-blocking retrieval must mark the task notified");
    }

    @Test
    void mapResult_preservesTsStructuredTaskOutput(@TempDir Path dir) throws Exception {
        TaskStore store = new TaskStore(dir, "test");
        TaskState task = store.create(TaskType.LOCAL_BASH, "echo hello");
        store.updateStatus(task.id(), TaskStatus.RUNNING);
        store.updateStatus(task.id(), TaskStatus.COMPLETED);
        TaskOutputPaths.configureForTest(dir, "test-session", dir.resolve("project"));
        Path outputFile = TaskOutputPaths.outputPath(task.id());
        Files.createDirectories(outputFile.getParent());
        Files.writeString(outputFile, "hello\n");

        TaskOutputTool tool = new TaskOutputTool(store);
        ObjectNode input = M.createObjectNode().put("task_id", task.id()).put("block", false);
        ToolExecutionContext context = ctx();
        var invocation = tool.callWithResult(input, context);
        String text = invocation.rawResult();
        var mapped = invocation.mappedResult();

        var payload = (JsonNode) mapped.toolUseResult();
        assertEquals("success", payload.path("retrieval_status").asText());
        assertEquals(task.id(), payload.path("task").path("task_id").asText());
        assertEquals("local_bash", payload.path("task").path("task_type").asText());
        assertEquals("hello\n", payload.path("task").path("output").asText());
        assertTrue(Strings.CS.contains(mapped.content().getFirst().toString(), "<retrieval_status>success"));
    }

    @Test
    void blockingWait_usesTaskStoreCompletionSignalInsteadOfPolling(@TempDir Path dir) throws Exception {
        CountingTaskStore store = new CountingTaskStore(dir, "test");
        TaskState task = store.create(TaskType.LOCAL_BASH, "wait");
        store.updateStatus(task.id(), TaskStatus.RUNNING);

        ObjectNode n = M.createObjectNode();
        n.put("task_id", task.id());
        n.put("block", true);
        n.put("timeout", 5_000);

        CompletableFuture<String> waiting = CompletableFuture.supplyAsync(
            () -> new TaskOutputTool(store).call(n, ctx()));
        assertTrue(store.awaitInitialLookup(), "TaskOutput should enter its blocking wait");
        assertFalse(waiting.isDone());
        store.updateStatus(task.id(), TaskStatus.COMPLETED);

        String result = waiting.get(1, TimeUnit.SECONDS);
        assertTrue(Strings.CS.contains(result, "<retrieval_status>success</retrieval_status>"), result);
        int lookupsBeforeAssertion = store.getCalls();
        assertEquals(1, lookupsBeforeAssertion,
            "TaskOutput should perform its initial lookup once, then await TaskStore's signal");
        assertTrue(store.get(task.id()).orElseThrow().notified(),
            "terminal blocking retrieval must mark the task notified");
    }

    @Test
    void blockingWait_emitsWaitingProgressBeforeCompletion(@TempDir Path dir) throws Exception {
        TaskStore store = new TaskStore(dir, "test");
        TaskState task = store.create(TaskType.LOCAL_AGENT, "research task");
        store.updateStatus(task.id(), TaskStatus.RUNNING);
        AtomicReference<ToolExecutionContext.ProgressUpdate> progress = new AtomicReference<>();
        ToolExecutionContext context = ToolExecutionContext.of(
            new AbortController(), "test", progress::set);

        ObjectNode input = M.createObjectNode()
            .put("task_id", task.id())
            .put("block", true)
            .put("timeout", 5_000);
        CompletableFuture<String> waiting = CompletableFuture.supplyAsync(
            () -> new TaskOutputTool(store).call(input, context));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (progress.get() == null && System.nanoTime() < deadline) {
            Thread.yield();
        }
        ToolExecutionContext.ProgressUpdate update = progress.get();
        assertNotNull(update, "TS emits waiting_for_task before polling");
        assertEquals("waiting_for_task", update.dataType());
        assertTrue(Strings.CS.contains(update.message(), "research task"), update.message());

        store.updateStatus(task.id(), TaskStatus.COMPLETED);
        assertTrue(Strings.CS.contains(waiting.get(1, TimeUnit.SECONDS), "<retrieval_status>success</retrieval_status>"));
    }

    @Test
    void completedTask_withOutputFile_includesContent(@TempDir Path dir) throws Exception {
        TaskStore store = new TaskStore(dir, "test");
        TaskState task = store.create(TaskType.LOCAL_BASH, "echo hello");
        // Must go PENDING → RUNNING → COMPLETED
        store.updateStatus(task.id(), TaskStatus.RUNNING);
        store.updateStatus(task.id(), TaskStatus.COMPLETED);

        TaskOutputPaths.configureForTest(dir, "test-session", dir.resolve("project"));
        Path outputFile = TaskOutputPaths.outputPath(task.id());
        Files.createDirectories(outputFile.getParent());
        Files.writeString(outputFile, "hello world\n");

        ObjectNode n = M.createObjectNode();
        n.put("task_id", task.id());
        n.put("block", false);

        String r = new TaskOutputTool(store).call(n, ctx());
        assertTrue(Strings.CS.contains(r, "<status>completed"), r);
        assertTrue(Strings.CS.contains(r, "<retrieval_status>success"), r);
    }

    @Test
    void outputOverTaskMaxOutputLength_isTailTruncatedWithHeader(@TempDir Path dir) throws Exception {
        TaskStore store = new TaskStore(dir, "test");
        TaskState task = store.create(TaskType.LOCAL_BASH, "echo big");
        store.updateStatus(task.id(), TaskStatus.RUNNING);
        store.updateStatus(task.id(), TaskStatus.COMPLETED);

        TaskOutputPaths.configureForTest(dir, "test-session", dir.resolve("project"));
        Path outputFile = TaskOutputPaths.outputPath(task.id());
        Files.createDirectories(outputFile.getParent());
        String big = "HEAD-" + "x".repeat(2_000) + "-TAIL";
        Files.writeString(outputFile, big);

        ObjectNode n = M.createObjectNode();
        n.put("task_id", task.id());
        n.put("block", false);

        TaskOutputTool tool = new TaskOutputTool(store,
            name -> Strings.CS.equals("TASK_MAX_OUTPUT_LENGTH", name) ? "500" : null);
        ToolExecutionContext ctx = ToolExecutionContext.builder(new AbortController(), "test").workingDirectory(dir.toString()).build();
        String r = tool.call(n, ctx);

        assertTrue(Strings.CS.contains(r, "[Truncated. Full output: " + outputFile + "]"), r);
        assertTrue(Strings.CS.contains(r, "-TAIL"), r);       // tail kept
        assertFalse(Strings.CS.contains(r, "HEAD-"), r);      // head dropped
    }

    @Test
    void outputUnderTaskMaxOutputLength_isNotTruncated(@TempDir Path dir) throws Exception {
        TaskStore store = new TaskStore(dir, "test");
        TaskState task = store.create(TaskType.LOCAL_BASH, "echo small");
        store.updateStatus(task.id(), TaskStatus.RUNNING);
        store.updateStatus(task.id(), TaskStatus.COMPLETED);

        TaskOutputPaths.configureForTest(dir, "test-session", dir.resolve("project"));
        Path outputFile = TaskOutputPaths.outputPath(task.id());
        Files.createDirectories(outputFile.getParent());
        Files.writeString(outputFile, "small output\n");

        ObjectNode n = M.createObjectNode();
        n.put("task_id", task.id());
        n.put("block", false);

        TaskOutputTool tool = new TaskOutputTool(store,
            name -> Strings.CS.equals("TASK_MAX_OUTPUT_LENGTH", name) ? "500" : null);
        ToolExecutionContext ctx = ToolExecutionContext.builder(new AbortController(), "test").workingDirectory(dir.toString()).build();
        String r = tool.call(n, ctx);

        assertTrue(Strings.CS.contains(r, "<output>\nsmall output\n</output>"), r);
        assertFalse(Strings.CS.contains(r, "[Truncated."), r);
    }

    @Test
    void descriptionAndPromptMatchReleased197Wire() {
        String d = new TaskOutputTool().description();
        assertEquals("[Deprecated] — prefer Read on the task output file path", d);
        String prompt = new TaskOutputTool().prompt(null);
        assertEquals("""
            DEPRECATED: Background tasks return their output file path in the tool result, and you receive a <task-notification> with the same path when the task completes.
            - For bash tasks: prefer using the Read tool on that output file path — it contains stdout/stderr.
            - For local_agent tasks: use the Agent tool result directly. Do NOT Read the .output file — it is a symlink to the full subagent conversation transcript (JSONL) and will overflow your context window.
            - For remote_agent tasks: prefer using the Read tool on the output file path — it contains the streamed remote session output (same as bash).

            - Retrieves output from a running or completed task (background shell, agent, or remote session)
            - Takes a task_id parameter identifying the task
            - Returns the task output along with status information
            - Use block=true (default) to wait for task completion
            - Use block=false for non-blocking check of current status
            - Task IDs can be found using the /tasks command
            - Works with all task types: background shells, async agents, and remote sessions""",
            prompt);
        assertFalse(Strings.CS.contains(d, "For bash tasks"), d);
    }

    @Test
    void isReadOnly_isTrue() {
        assertTrue(new TaskOutputTool().isReadOnly());
    }

    @Test
    void isConcurrencySafe_isTrue() {

        assertTrue(new TaskOutputTool().isConcurrencySafe());
    }

    @Test
    void schema_usesStrictObject() {
        var schema = new TaskOutputTool(TaskStore.inMemory()).inputSchema();
        assertTrue(schema.has("additionalProperties"), "z.strictObject → additionalProperties");
        assertFalse(schema.get("additionalProperties").asBoolean());
    }

    @Test
    void localAgent_usesFinalMessageInsteadOfDiskFile() {

        // (agentTask.result) is surfaced instead of the raw .output transcript.
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.LOCAL_AGENT, "research");
        store.updateStatus(task.id(), TaskStatus.RUNNING);
        store.updateStatus(task.id(), TaskStatus.COMPLETED);
        store.updateFinalMessage(task.id(), "the clean final answer");

        ObjectNode n = M.createObjectNode();
        n.put("task_id", task.id());
        n.put("block", false);

        String r = new TaskOutputTool(store).call(n, ctx());
        assertTrue(Strings.CS.contains(r, "the clean final answer"), r);
        // The clean answer is delivered as the <output> block, not a disk read.
        assertTrue(Strings.CS.contains(r, "<output>\nthe clean final answer\n</output>"), r);
    }

    private static final class CountingTaskStore extends TaskStore {
        private final AtomicInteger getCalls = new AtomicInteger();
        private final CountDownLatch initialLookup = new CountDownLatch(1);

        CountingTaskStore(Path baseDir, String taskListId) {
            super(baseDir, taskListId);
        }

        @Override
        public Optional<TaskState> get(String taskId) {
            getCalls.incrementAndGet();
            initialLookup.countDown();
            return super.get(taskId);
        }

        boolean awaitInitialLookup() throws InterruptedException {
            return initialLookup.await(1, TimeUnit.SECONDS);
        }

        int getCalls() {
            return getCalls.get();
        }
    }
}
