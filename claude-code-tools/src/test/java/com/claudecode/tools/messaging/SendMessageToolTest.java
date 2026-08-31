package com.claudecode.tools.messaging;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.tools.agent.AgentContinuationService;
import com.claudecode.tools.agent.SubAgentResult;
import com.claudecode.tools.tasks.teammate.Mail;
import com.claudecode.tools.tasks.teammate.MailTypes;
import com.claudecode.tools.tasks.teammate.TeammateMailbox;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.claudecode.tools.tasks.LocalAgentTask;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskStore;
import com.claudecode.tools.tasks.TaskType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import com.claudecode.tools.ValidationResult;

class SendMessageToolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ToolExecutionContext ctx() {
        return ToolExecutionContext.of(new AbortController(), "test-session");
    }

    @Test
    void summarySchemaMatches197MaxLength() {
        JsonNode summary = new SendMessageTool().inputSchema().at("/properties/summary");
        assertEquals(200, summary.path("maxLength").asInt(),
            "2.1.197 SendMessage summary carries maxLength:200 (G-P0-TTY wire capture)");
    }

    @Test
    void modelVisibleContractMatches197() {
        SendMessageTool tool = new SendMessageTool();
        JsonNode schema = tool.inputSchema();

        assertEquals("Recipient: teammate name",
            schema.at("/properties/to/description").asText());
        assertEquals("string", schema.at("/properties/message/type").asText());
        assertFalse(schema.at("/properties/message/anyOf").isArray(),
            "197 advertises message as a plain string; structured protocol messages "
                + "are still accepted at runtime (validateInput/call), not in the schema");

        assertTrue(Strings.CS.startsWith(tool.description(), "# SendMessage"),
            "description() must derive from the 197 baseline, got: " + tool.description());
        assertEquals(tool.prompt(null), tool.description(),
            "wire prompt() must equal the permission description()");
        assertEquals("send messages to agent teammates", tool.searchHint());
    }

    @Test
    void classifierProjectionMatchesCurrentTs() {
        SendMessageTool tool = new SendMessageTool();
        ObjectNode plain = mapper.createObjectNode().put("to", "researcher").put("message", "inspect");
        assertEquals("to researcher: inspect", tool.toAutoClassifierInput(plain));
        ObjectNode structured = mapper.createObjectNode().put("to", "team-lead");
        structured.set("message", mapper.createObjectNode().put("type", "shutdown_response")
            .put("request_id", "r1").put("approve", true));
        assertEquals("shutdown_response approve r1", tool.toAutoClassifierInput(structured));
    }

    @Test
    void classifierProjectionTreatsMissingInputAsInvalidAndNeutral() {
        assertEquals("", new SendMessageTool().toAutoClassifierInput(null));
    }

    @Test
    void readOnlyAndValidationFollowTsInputSemantics() {
        SendMessageTool tool = new SendMessageTool();
        ObjectNode plain = mapper.createObjectNode()
            .put("to", "researcher").put("summary", "assign work").put("message", "start");
        assertTrue(tool.isReadOnly(plain));
        assertInstanceOf(ValidationResult.Valid.class,
            tool.validateInput(plain, ctx()));

        ObjectNode structured = mapper.createObjectNode().put("to", "team-lead");
        structured.set("message", mapper.createObjectNode()
            .put("type", "shutdown_response").put("request_id", "r1").put("approve", false));
        var invalid = tool.validateInput(structured, ctx());
        assertInstanceOf(ValidationResult.Invalid.class, invalid);
        assertTrue(Strings.CS.contains(((ValidationResult.Invalid) invalid).message(),
            "reason is required"));
        assertFalse(tool.isReadOnly(structured));
    }

    @Test
    void jsonSuccessPreservesRoutingMetadataForTheUi() {
        SendMessageTool tool = new SendMessageTool();
        ObjectNode data = mapper.createObjectNode().put("success", true)
            .put("message", "Message sent");
        data.putObject("routing").put("target", "@researcher");
        ToolResult mapped = tool.mapResult(data.toString(), null, ctx());
        assertNotNull(mapped);
        assertEquals(data, mapped.toolUseResult());
        assertEquals("{\"success\":true,\"message\":\"Message sent\"}",
            ((TextBlock) mapped.content().getFirst()).text());
        assertFalse(mapped.isError());
    }

    @Test
    void basicQueueAcknowledgementDoesNotPersistToolUseResult() {
        SendMessageTool tool = new SendMessageTool();
        ObjectNode data = mapper.createObjectNode().put("success", true)
            .put("message", "Message queued for delivery to researcher at its next tool round.");

        ToolResult mapped = tool.mapResult(data.toString(), null, ctx());

        assertNull(mapped.toolUseResult());
        assertEquals(data.toString(), ((TextBlock) mapped.content().getFirst()).text());
    }

    /**
     * Fix 5 (HIGH): {@code to: "*"} must actually broadcast, not return a false
     * "Message sent" success. The broadcast delivers to every live teammate inbox
     * (except the sender) and reports the recipient count.
     */
    @Test
    void broadcastDeliversToAllTeammates() throws JsonProcessingException {
        TeammateMailbox mb = TeammateMailbox.instance();
        mb.clearAll();
        try {
            // Seed one teammate inbox so broadcast has a recipient, then drain the
// seed so the real broadcast message is what poll returns.
            mb.send(Mail.of(MailTypes.USER_MESSAGE, "leader", "teammate1", "seed"));
            mb.poll("teammate1");

            SendMessageTool tool = new SendMessageTool();
            ObjectNode input = mapper.createObjectNode();
            input.put("to", "*");
            input.put("message", "hello all");
            input.put("summary", "broadcast");

            String result = tool.call(input, ctx());
            JsonNode data = mapper.readTree(result);
            assertTrue(data.get("success").asBoolean());
            assertTrue(Strings.CS.contains(data.get("message").asText(), "1 teammate"),
                "recipient count must be reported: " + data.get("message").asText());

            Mail delivered = mb.poll("teammate1");
            assertNotNull(delivered, "broadcast should deliver to teammate1");
            assertEquals("hello all", delivered.payload());
        } finally {
            mb.clearAll();
        }
    }

    /**
     * Fix 7 (LOW): a string message without a summary must be rejected.
     */
    @Test
    void stringMessageWithoutSummaryIsRejected() {
        SendMessageTool tool = new SendMessageTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("to", "someone");
        input.put("message", "hello");
        String result = tool.call(input, ctx());
        assertTrue(Strings.CS.contains(result, "summary is required"), result);
    }

    @Test
    void inventedTaskFileRecipientIsRejected() {
        ObjectNode input = mapper.createObjectNode();
        input.put("to", "task:123");
        input.put("message", "hello");
        input.put("summary", "send to task");

        String result = new SendMessageTool().call(input, ctx());

        assertTrue(Strings.CS.contains(result, "No recipient found"), result);
    }

    @Test
    void modelSuppliedAgentNameResolvesToTheRunningLocalAgent() throws Exception {
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        TaskRegistry.setGlobalForTest(registry);
        try {
            TaskState state = registry.store().createWithId(
                "agent-123", TaskType.LOCAL_AGENT, "research", null);
            registry.store().updateStatus(state.id(), TaskStatus.RUNNING);
            registry.registerAgent(new LocalAgentTask(state, registry.store()));
            registry.registerAgentName("researcher", state.id());

            ObjectNode input = mapper.createObjectNode();
            input.put("to", "researcher");
            input.put("message", "start on task 1");
            input.put("summary", "assign task");

            JsonNode result = mapper.readTree(new SendMessageTool().call(input, ctx()));

            assertTrue(result.path("success").asBoolean(), result.toString());
            assertEquals(List.of("start on task 1"),
                registry.drainAgentMessages(state.id()));
        } finally {
            TaskRegistry.resetGlobalForTest();
        }
    }

    @Test
    void modelSuppliedAgentNameResolvesToATerminalAgentResume(@TempDir Path temp) throws Exception {
        String agentId = "a1234567890abcdef";
        Path transcript = temp.resolve("agent-" + agentId + ".jsonl");
        ObjectNode persisted = (ObjectNode) JsonUtils.getMapper().valueToTree(
            new UserMessage("prior", MessageContent.ofText("original audit")));
        persisted.put("isSidechain", true);
        persisted.put("agentId", agentId);
        persisted.put("sessionId", "test-session");
        persisted.putNull("parentUuid");
        Files.writeString(transcript, JsonUtils.getMapper().writeValueAsString(persisted)
            + System.lineSeparator(), StandardCharsets.UTF_8);
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        registry.registerAgentName("researcher", agentId);
        TaskRegistry.setGlobalForTest(registry);
        try {
            AgentContinuationService continuation = new AgentContinuationService(
                _ -> SubAgentResult.of("continued"), registry, (_, _) -> transcript,
                (_, _) -> temp.resolve(agentId + ".output"));
            ObjectNode input = mapper.createObjectNode()
                .put("to", "researcher")
                .put("message", "continue the audit")
                .put("summary", "continue audit");

            JsonNode result = mapper.readTree(
                new SendMessageTool(continuation).call(input, ctx()));

            assertTrue(result.path("success").asBoolean(), result.toString());
            assertFalse(result.has("routing"),
                "2.1.197 agent-resume acknowledgements do not carry UI routing metadata");
            assertTrue(registry.get(agentId).isPresent());
        } finally {
            TaskRegistry.resetGlobalForTest();
        }
    }

    @Test
    void userStoppedAgentResumeReturnsStructuredFailureInsteadOfToolError() throws Exception {
        String agentId = "a1234567890abcdef";
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        TaskState state = registry.store().createWithId(
            agentId, TaskType.LOCAL_AGENT, "research", null);
        registry.store().updateStatus(state.id(), TaskStatus.RUNNING);
        registry.store().updateStatus(state.id(), TaskStatus.KILLED);
        registry.registerAgentName("researcher", agentId);
        TaskRegistry.setGlobalForTest(registry);
        try {
            AgentContinuationService continuation = new AgentContinuationService(
                _ -> SubAgentResult.of("unexpected"), registry,
                (_, _) -> Path.of("missing-transcript.jsonl"),
                (_, _) -> Path.of("unused-output"));
            ObjectNode input = mapper.createObjectNode()
                .put("to", "researcher")
                .put("message", "continue the audit")
                .put("summary", "continue audit");
            SendMessageTool tool = new SendMessageTool(continuation);

            String rawResult = tool.call(input, ctx());
            JsonNode result = mapper.readTree(rawResult);
            ToolResult mapped = tool.mapResult(rawResult, input, ctx());

            assertFalse(result.path("success").asBoolean(true), result.toString());
            assertTrue(Strings.CS.contains(result.path("message").asText(),
                "stopped by the user"), result.toString());
            assertNotNull(mapped);
            assertFalse(mapped.isError(),
                "2.1.197 reports resume business failures as normal tool results");
        } finally {
            TaskRegistry.resetGlobalForTest();
        }
    }

    /**
     * Fix 6 (MED): a structured object message must not be rejected as missing.
     */
    @Test
    void objectMessageIsAccepted() throws JsonProcessingException {
        TeammateMailbox mb = TeammateMailbox.instance();
        mb.clearAll();
        try {
            mb.send(Mail.of(MailTypes.USER_MESSAGE, "leader", "teammate1", "seed"));
            mb.poll("teammate1");

            SendMessageTool tool = new SendMessageTool();
            ObjectNode input = mapper.createObjectNode();
            input.put("to", "teammate1");
            input.put("summary", "a plan response");
            ObjectNode body = mapper.createObjectNode();
            body.put("type", "plan_approval_response");
            body.put("request_id", "req-1");
            body.put("approve", true);
            input.set("message", body);

            String result = tool.call(input, ctx());
            JsonNode data = mapper.readTree(result);
            assertTrue(data.get("success").asBoolean());
            Mail delivered = mb.poll("teammate1");
            assertNotNull(delivered);
            // The serialized object must be preserved as the payload so the
            // protocol type survives routing.
            assertTrue(Strings.CS.contains(delivered.payload(), "plan_approval_response"));
        } finally {
            mb.clearAll();
        }
    }
}
