package com.claudecode.tools.monitor;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.StructuredToolOutput;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.queue.MessageQueueManager;
import com.claudecode.core.queue.QueuePriority;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.tools.tasks.TaskNotificationBridge;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskStore;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.claudecode.tools.sandbox.NoopSandboxBackend;
import com.claudecode.tools.ValidationResult;

class MonitorToolTest {

    @AfterEach
    void resetRegistry() {
        TaskRegistry.resetGlobalForTest();
    }

    @Test
    void schemaDescriptionAndFlagsMatchOfficial197Wire() throws Exception {
        MonitorTool tool = new MonitorTool(() -> true, new NoopSandboxBackend(),
            new TaskRegistry(TaskStore.inMemory()));

        assertEquals("Monitor", tool.name());
        assertTrue(Strings.CS.startsWith(tool.description(), "Start a background monitor that streams events from a long-running script."));
        assertTrue(Strings.CS.contains(tool.description(), "Each stdout line is an event"));
        assertEquals("abcbc10e2b814c861a18bd02072cf677a0e8d1a112d54e9e58ba323a614295ad",
            hex(MessageDigest.getInstance("SHA-256").digest(
                tool.description().getBytes(StandardCharsets.UTF_8))));
        var schema = tool.inputSchema();
        assertEquals("https://json-schema.org/draft/2020-12/schema", schema.path("$schema").asText());
        assertEquals(List.of("description", "timeout_ms", "persistent", "command", "ws"),
            fieldNames(schema.path("properties").fieldNames()));
        assertEquals(1000, schema.path("properties").path("timeout_ms").path("minimum").asInt());
        assertEquals(300000, schema.path("properties").path("timeout_ms").path("default").asInt());
        assertEquals("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$",
            schema.path("properties").path("ws").path("properties")
                .path("protocols").path("items").path("pattern").asText());
        assertEquals(3, schema.path("required").size());
        assertEquals(List.of("url", "protocols"),
            fieldNames(schema.path("properties").path("ws").path("properties").fieldNames()));
        assertFalse(schema.path("properties").path("ws").has("additionalProperties"),
            "nested ws mirrors TS z.object passthrough; only the root is strict");
        assertFalse(schema.path("additionalProperties").asBoolean(true));
        assertTrue(tool.shouldDefer());
        assertTrue(tool.isConcurrencySafe());
        assertEquals(10_000, tool.maxResultSizeChars());
    }

    @Test
    void validatesExactlyOneSourceAndTimeoutCeiling() {
        MonitorTool tool = new MonitorTool(() -> true, new NoopSandboxBackend(),
            new TaskRegistry(TaskStore.inMemory()));
        ToolExecutionContext context = ToolExecutionContext.of(new AbortController(), "session");

        assertInstanceOf(ValidationResult.Invalid.class,
            tool.validateInput(baseInput(), context));
        ObjectNode both = baseInput().put("command", "echo ok");
        both.putObject("ws").put("url", "wss://example.com/events");
        assertInstanceOf(ValidationResult.Invalid.class, tool.validateInput(both, context));
        ObjectNode tooLong = baseInput().put("command", "echo ok").put("timeout_ms", 3_600_001);
        assertInstanceOf(ValidationResult.Invalid.class, tool.validateInput(tooLong, context));
        tooLong.put("persistent", true);
        assertInstanceOf(ValidationResult.Valid.class, tool.validateInput(tooLong, context));

        ObjectNode hiddenControl = baseInput().put("command", "echo ok\u0007");
        assertInstanceOf(ValidationResult.Invalid.class,
            tool.validateInput(hiddenControl, context));
        ObjectNode duplicateProtocols = baseInput();
        duplicateProtocols.putObject("ws").put("url", "wss://example.com/events")
            .putArray("protocols").add("v1").add("v1");
        assertInstanceOf(ValidationResult.Invalid.class,
            tool.validateInput(duplicateProtocols, context));
    }

    @Test
    void commandPermissionsReuseBashAndWebSocketsAsk() {
        MonitorTool tool = new MonitorTool(() -> true, new NoopSandboxBackend(),
            new TaskRegistry(TaskStore.inMemory()));
        ToolPermissionContext permissions = ToolPermissionContext.of(Path.of("."));

        assertInstanceOf(PermissionDecision.Allow.class,
            tool.checkPermissions(baseInput().put("command", "pwd"), permissions));
        ObjectNode websocket = baseInput();
        websocket.putObject("ws").put("url", "wss://example.com/events");
        assertInstanceOf(PermissionDecision.Ask.class,
            tool.checkPermissions(websocket, permissions));
    }

    @Test
    void commandSourceReturnsOfficialPayloadAndStreamsEvents() throws Exception {
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        MessageQueueManager queue = new MessageQueueManager();
        registry.setMessageQueue(queue);
        TaskRegistry.setGlobalForTest(registry);
        new TaskNotificationBridge(queue, registry).register();
        MonitorTool tool = new MonitorTool(() -> true, new NoopSandboxBackend(), registry);

        StructuredToolOutput output = tool.call(baseInput().put("command",
            "printf 'one\\n'; sleep 0.3; printf 'two\\n'"),
            ToolExecutionContext.of(new AbortController(), "session").withToolUseId("toolu_monitor"));

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) output.toolUseResult();
        String taskId = (String) payload.get("taskId");
        assertTrue(Strings.CS.startsWith(taskId, "b"));
        assertEquals(9, taskId.length());
        assertEquals(3000L, payload.get("timeoutMs"));
        assertEquals(false, payload.get("persistent"));
        SDKMessage.TaskStarted started = assertInstanceOf(
            SDKMessage.TaskStarted.class, queue.drainSdkEvents().getFirst());
        assertEquals(taskId, started.taskId());
        assertEquals("toolu_monitor", started.toolUseId());
        assertEquals("wire events", started.description());
        assertEquals("local_bash", started.taskType());
        assertEquals("Monitor started (task " + taskId
            + ", timeout 3000ms). You will be notified on each event. Keep working — do not poll or sleep. "
            + "Events may arrive while you are waiting for the user — an event is not their reply.", output.text());

        registry.store().awaitTerminal(taskId, Duration.ofSeconds(3));
        long notificationDeadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (queue.size() < 3 && System.nanoTime() < notificationDeadline) {
            Thread.sleep(10);
        }
        List<String> notifications = new ArrayList<>();
        while (queue.hasCommands()) {
            var queued = queue.dequeue();
            assertEquals(QueuePriority.NEXT, queued.priority());
            notifications.add(queued.text());
        }
        assertTrue(notifications.stream().anyMatch(text -> Strings.CS.contains(text, "<event>one</event>")));
        assertTrue(notifications.stream().anyMatch(text -> Strings.CS.contains(text, "<event>two</event>")));
        assertTrue(notifications.stream().anyMatch(text -> Strings.CS.contains(text, 
            "<summary>Monitor \"wire events\" stream ended</summary>")),
            notifications::toString);
        assertEquals(TaskStatus.COMPLETED, registry.store().get(taskId).orElseThrow().status());
    }

    @Test
    void persistentPayloadUsesOfficialZeroTimeoutSentinel() {
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        registry.setMessageQueue(new MessageQueueManager());
        MonitorTool tool = new MonitorTool(() -> true, new NoopSandboxBackend(), registry);

        StructuredToolOutput output = tool.call(baseInput()
                .put("command", "sleep 30").put("persistent", true),
            ToolExecutionContext.of(new AbortController(), "session"));

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) output.toolUseResult();
        assertEquals(0L, payload.get("timeoutMs"));
        assertEquals(true, payload.get("persistent"));
        assertTrue(registry.killTask((String) payload.get("taskId")));
    }

    private static ObjectNode baseInput() {
        ObjectNode input = JsonUtils.getMapper().createObjectNode();
        input.put("description", "wire events");
        input.put("timeout_ms", 3000);
        input.put("persistent", false);
        return input;
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }

    private static List<String> fieldNames(Iterator<String> names) {
        List<String> result = new ArrayList<>();
        names.forEachRemaining(result::add);
        return result;
    }
}
