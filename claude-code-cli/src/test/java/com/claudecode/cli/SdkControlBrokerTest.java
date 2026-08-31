package com.claudecode.cli;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.claudecode.core.engine.PermissionAskCallback;
import com.claudecode.core.engine.PermissionAskContext;
import com.claudecode.core.engine.PermissionUpdate;
import com.claudecode.core.engine.RefusalFallbackPrompt;
import com.claudecode.runtime.query.DefaultQuerySession;
import com.claudecode.runtime.query.QuerySessionSpec;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.RefusalFallbackDecision;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.queue.QueuedCommand;
import com.claudecode.core.model.PermissionModeKind;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.PermissionRule;
import com.claudecode.permissions.RuleSource;
import com.claudecode.services.config.PermissionSettings;
import com.claudecode.services.config.SettingsPaths;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SdkControlBrokerTest {

    private static final ObjectMapper MAPPER = JsonUtils.getMapper();

    private static final StreamingClient NOOP_CLIENT = new StreamingClient() {
        @Override public Iterator<StreamingClient.StreamingEvent> createStream(StreamingClient.StreamRequest request) {
            return Collections.emptyIterator();
        }
        @Override public String getModel() { return "test-model"; }
    };

    private static DefaultQuerySession newEngine() {
        return new DefaultQuerySession(QuerySessionSpec.builder().llmClient(NOOP_CLIENT).build());
    }

    @Test
    void refusalDialogUsesDeclaredSdkCapabilityAndMapsTheChoice() throws Exception {
        StringWriter buffer = new StringWriter();
        SdkControlBroker broker = new SdkControlBroker(new PrintWriter(buffer, true),
            newEngine(), new PermissionGate(), System.getProperty("user.dir"));
        broker.configureSupportedDialogKinds(MAPPER.createArrayNode()
            .add(RefusalFallbackPrompt.DIALOG_KIND));
        assertTrue(broker.consumerSupportsDialog());

        AtomicReference<RefusalFallbackDecision.Choice> answer = new AtomicReference<>();
        Thread thread = Thread.startVirtualThread(() -> answer.set(broker.ask(
            new RefusalFallbackPrompt.Request("claude-fable-5", "claude-opus-4-8",
                "cyber", "Configure provider fallback", List.of("wire-1")))));

        JsonNode envelope = waitForFirstLine(buffer);
        JsonNode request = envelope.path("request");
        assertEquals("request_user_dialog", request.path("subtype").asText());
        assertEquals("refusal_fallback_prompt", request.path("dialog_kind").asText());
        assertEquals("claude-fable-5", request.path("payload").path("originalModel").asText());
        assertEquals("claude-opus-4-8", request.path("payload").path("fallbackModel").asText());
        assertEquals("cyber", request.path("payload").path("apiRefusalCategory").asText());
        assertEquals("Configure provider fallback",
            request.path("payload").path("guidanceText").asText());
        assertEquals("wire-1",
            request.path("payload").path("retractedMessageUuids").get(0).asText());

        ObjectNode response = MAPPER.createObjectNode().put("type", "control_response");
        ObjectNode responseEnvelope = response.putObject("response");
        responseEnvelope.put("subtype", "success");
        responseEnvelope.put("request_id", envelope.path("request_id").asText());
        responseEnvelope.putObject("response")
            .put("behavior", "completed")
            .put("result", "edit_prompt");
        broker.onControlResponse(response);
        thread.join();

        assertEquals(RefusalFallbackDecision.Choice.EDIT_PROMPT, answer.get());
    }

    @Test
    void refusalDialogWaitsUntilEveryRetractedRowWasWritten() throws Exception {
        StringWriter buffer = new StringWriter();
        SdkControlBroker broker = new SdkControlBroker(new PrintWriter(buffer, true),
            newEngine(), new PermissionGate(), System.getProperty("user.dir"), true);
        broker.configureSupportedDialogKinds(MAPPER.createArrayNode()
            .add(RefusalFallbackPrompt.DIALOG_KIND));
        AtomicReference<RefusalFallbackDecision.Choice> answer = new AtomicReference<>();

        Thread thread = Thread.startVirtualThread(() -> answer.set(broker.ask(
            new RefusalFallbackPrompt.Request("from", "to", null, null,
                List.of("assistant-refused", "tool-result-user")))));
        for (int i = 0; i < 100 && thread.getState() != Thread.State.WAITING; i++) {
            Thread.sleep(5);
        }
        assertTrue(StringUtils.isBlank(buffer.toString()),
            "request_user_dialog must not overtake rows it will retract");

        broker.onAssistantMessageWritten(new AssistantMessage(
            "assistant-refused", AssistantContent.of(List.of(new TextBlock("partial")))));
        assertTrue(StringUtils.isBlank(buffer.toString()),
            "the dialog must wait for every retracted row, not only assistants");

        broker.onUserMessageWritten(new UserMessage(
            "tool-result-user", MessageContent.ofText("tool result")));
        JsonNode request = waitForFirstLine(buffer);
        ObjectNode response = MAPPER.createObjectNode().put("type", "control_response");
        ObjectNode responseEnvelope = response.putObject("response");
        responseEnvelope.put("subtype", "success");
        responseEnvelope.put("request_id", request.path("request_id").asText());
        responseEnvelope.putObject("response").put("behavior", "cancelled");
        broker.onControlResponse(response);
        thread.join();

        assertEquals(RefusalFallbackDecision.Choice.CANCELLED, answer.get());
    }

    @Test
    void refusalDialogHandlesOutputAcknowledgedBeforeWaiterRegistration() throws Exception {
        StringWriter buffer = new StringWriter();
        SdkControlBroker broker = new SdkControlBroker(new PrintWriter(buffer, true),
            newEngine(), new PermissionGate(), System.getProperty("user.dir"), true);
        broker.configureSupportedDialogKinds(MAPPER.createArrayNode()
            .add(RefusalFallbackPrompt.DIALOG_KIND));
        broker.onAssistantMessageWritten(new AssistantMessage(
            "assistant-refused", AssistantContent.of(List.of(new TextBlock("partial")))));
        AtomicReference<RefusalFallbackDecision.Choice> answer = new AtomicReference<>();

        Thread thread = Thread.startVirtualThread(() -> answer.set(broker.ask(
            new RefusalFallbackPrompt.Request("from", "to", null, null,
                List.of("assistant-refused")))));
        JsonNode request = waitForFirstLine(buffer);
        ObjectNode response = MAPPER.createObjectNode().put("type", "control_response");
        ObjectNode responseEnvelope = response.putObject("response");
        responseEnvelope.put("subtype", "success");
        responseEnvelope.put("request_id", request.path("request_id").asText());
        responseEnvelope.putObject("response").put("behavior", "cancelled");
        broker.onControlResponse(response);
        thread.join();

        assertEquals(RefusalFallbackDecision.Choice.CANCELLED, answer.get());
    }

    @Test
    void abortingBeforeRetractedRowsFlushCancelsWithoutPublishingADialog() throws Exception {
        StringWriter buffer = new StringWriter();
        DefaultQuerySession engine = newEngine();
        SdkControlBroker broker = new SdkControlBroker(new PrintWriter(buffer, true),
            engine, new PermissionGate(), System.getProperty("user.dir"), true);
        broker.configureSupportedDialogKinds(MAPPER.createArrayNode()
            .add(RefusalFallbackPrompt.DIALOG_KIND));
        AtomicReference<RefusalFallbackDecision.Choice> answer = new AtomicReference<>();

        Thread thread = Thread.startVirtualThread(() -> answer.set(broker.ask(
            new RefusalFallbackPrompt.Request("from", "to", null, null,
                List.of("assistant-refused")))));
        for (int i = 0; i < 100 && thread.getState() != Thread.State.WAITING; i++) {
            Thread.sleep(5);
        }

        engine.getAbortController().abort("user-cancel");
        thread.join();

        assertEquals(RefusalFallbackDecision.Choice.CANCELLED, answer.get());
        assertTrue(StringUtils.isBlank(buffer.toString()),
            "an unpublished dialog has no control request to cancel");
    }

    @Test
    void closingBeforeRetractedRowsFlushReleasesTheDialogWaiter() throws Exception {
        StringWriter buffer = new StringWriter();
        SdkControlBroker broker = new SdkControlBroker(new PrintWriter(buffer, true),
            newEngine(), new PermissionGate(), System.getProperty("user.dir"), true);
        broker.configureSupportedDialogKinds(MAPPER.createArrayNode()
            .add(RefusalFallbackPrompt.DIALOG_KIND));
        AtomicReference<RefusalFallbackDecision.Choice> answer = new AtomicReference<>();

        Thread thread = Thread.startVirtualThread(() -> answer.set(broker.ask(
            new RefusalFallbackPrompt.Request("from", "to", null, null,
                List.of("assistant-refused")))));
        for (int i = 0; i < 100 && thread.getState() != Thread.State.WAITING; i++) {
            Thread.sleep(5);
        }

        broker.close();
        thread.join();

        assertEquals(RefusalFallbackDecision.Choice.CANCELLED, answer.get());
        assertTrue(StringUtils.isBlank(buffer.toString()));
    }

    @Test
    void abortingARefusalDialogCancelsTheControlRequestAndReturnsCancelled() throws Exception {
        StringWriter buffer = new StringWriter();
        DefaultQuerySession engine = newEngine();
        SdkControlBroker broker = new SdkControlBroker(new PrintWriter(buffer, true),
            engine, new PermissionGate(), System.getProperty("user.dir"));
        broker.configureSupportedDialogKinds(MAPPER.createArrayNode()
            .add(RefusalFallbackPrompt.DIALOG_KIND));

        AtomicReference<RefusalFallbackDecision.Choice> answer = new AtomicReference<>();
        Thread thread = Thread.startVirtualThread(() -> answer.set(broker.ask(
            new RefusalFallbackPrompt.Request("from", "to", null, null, List.of()))));
        JsonNode request = waitForFirstLine(buffer);

        engine.getAbortController().abort("user-cancel");
        thread.join();

        assertEquals(RefusalFallbackDecision.Choice.CANCELLED, answer.get());
        String[] lines = buffer.toString().trim().split("\\R");
        assertEquals(2, lines.length);
        JsonNode cancel = MAPPER.readTree(lines[1]);
        assertEquals("control_cancel_request", cancel.path("type").asText());
        assertEquals(request.path("request_id").asText(), cancel.path("request_id").asText());
    }

    @Test
    void dialogCapabilityDeclarationFiltersInvalidAndOversizedKindsLike197() {
        SdkControlBroker broker = new SdkControlBroker(
            new PrintWriter(new StringWriter(), true), newEngine(), new PermissionGate(),
            System.getProperty("user.dir"));
        ArrayNode kinds = MAPPER.createArrayNode();
        kinds.add(7);
        kinds.add("");
        kinds.add("x".repeat(65));
        kinds.add(RefusalFallbackPrompt.DIALOG_KIND);

        broker.configureSupportedDialogKinds(kinds);

        assertTrue(broker.consumerSupportsDialog());
    }

    @Test
    void refusalDialogTimeoutSettlesLocallyLike197() throws Exception {
        SubprocessEnvironment.updateRuntime(
            Map.of("CLAUDE_CODE_USER_DIALOG_TIMEOUT_MS", "5"));
        try {
            StringWriter buffer = new StringWriter();
            SdkControlBroker broker = new SdkControlBroker(new PrintWriter(buffer, true),
                newEngine(), new PermissionGate(), System.getProperty("user.dir"));
            broker.configureSupportedDialogKinds(MAPPER.createArrayNode()
                .add(RefusalFallbackPrompt.DIALOG_KIND));
            AtomicReference<RefusalFallbackDecision.Choice> answer = new AtomicReference<>();

            Thread thread = Thread.startVirtualThread(() -> answer.set(broker.ask(
                new RefusalFallbackPrompt.Request("from", "to", null, null, List.of()))));
            waitForFirstLine(buffer);
            thread.join();

            assertEquals(RefusalFallbackDecision.Choice.CANCELLED, answer.get());
            assertEquals(1, buffer.toString().trim().split("\\R").length,
                "197 injects a local cancelled response on timeout; only abort/close sends cancel");
        } finally {
            SubprocessEnvironment.clearRuntimeOverrides();
        }
    }

    private static JsonNode waitForFirstLine(StringWriter buffer) throws Exception {
        for (int i = 0; i < 200; i++) {
            String output = buffer.toString().trim();
            if (!output.isEmpty()) return MAPPER.readTree(output.split("\\R")[0]);
            Thread.sleep(5);
        }
        throw new AssertionError("control_request should have been written");
    }

    private static ObjectNode controlResponse(String subtype, String requestId, String toolUseId, String behavior) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "control_response");
        ObjectNode response = root.putObject("response");
        response.put("subtype", subtype);
        if (requestId != null) response.put("request_id", requestId);
        ObjectNode inner = response.putObject("response");
        if (toolUseId != null) inner.put("toolUseID", toolUseId);
        if (behavior != null) inner.put("behavior", behavior);
        return root;
    }

    @Test
    void matchingSuccessResponseCompletesPendingAskAsAllow() throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);
        DefaultQuerySession engine = newEngine();
        SdkControlBroker broker = new SdkControlBroker(out, engine, new PermissionGate(), System.getProperty("user.dir"));

        AtomicReference<PermissionAskCallback.Result> result = new AtomicReference<>();
        Thread t = Thread.startVirtualThread(() ->
            result.set(broker.askPermission(PermissionAskContext.simple("Bash", MAPPER.createObjectNode(), "tuA"))));

        // Wait for the control_request to be written, then extract its request_id.
        String requestId = null;
        for (int i = 0; i < 200 && requestId == null; i++) {
            String s = sw.toString().trim();
            if (!s.isEmpty()) {
                JsonNode req = MAPPER.readTree(s.split("\n")[0]);
                requestId = req.path("request_id").asText(null);
            } else {
                Thread.sleep(5);
            }
        }
        assertNotNull(requestId, "control_request should have been written");

        ObjectNode response = controlResponse("success", requestId, "tuA", "allow");
        ((ObjectNode) response.path("response").path("response")).putObject("updatedInput");
        broker.onControlResponse(response);
        t.join();

        PermissionAskCallback.Result r = result.get();
        assertNotNull(r);
        assertTrue(r.allowed());
        assertNull(r.updatedInput(), "an empty updatedInput object means use the original tool input");
    }

    @Test
    void elicitationUsesGenericControlRequestAndReturnsControllerPayload() throws Exception {
        StringWriter sw = new StringWriter();
        SdkControlBroker broker = new SdkControlBroker(new PrintWriter(sw, true),
            newEngine(), new PermissionGate(), System.getProperty("user.dir"));
        ObjectNode params = MAPPER.createObjectNode();
        params.put("message", "Choose a value");
        params.put("mode", "form");
        params.putObject("requestedSchema").put("type", "object");
        AtomicReference<JsonNode> result = new AtomicReference<>();
        Thread thread = Thread.startVirtualThread(() ->
            result.set(broker.askElicitation("sdk-server", params)));

        JsonNode request = null;
        for (int i = 0; i < 200 && request == null; i++) {
            String value = sw.toString().trim();
            if (!value.isEmpty()) request = MAPPER.readTree(value.split("\\R")[0]);
            else Thread.sleep(5);
        }
        assertNotNull(request);
        assertEquals("elicitation", request.path("request").path("subtype").asText());
        assertEquals("sdk-server", request.path("request").path("mcp_server_name").asText());
        assertTrue(request.path("request").path("requested_schema").isObject());

        ObjectNode response = MAPPER.createObjectNode();
        response.put("type", "control_response");
        ObjectNode envelope = response.putObject("response");
        envelope.put("subtype", "success");
        envelope.put("request_id", request.path("request_id").asText());
        envelope.putObject("response").put("action", "accept")
            .putObject("content").put("value", "yes");
        broker.onControlResponse(response);
        thread.join();
        assertEquals("accept", result.get().path("action").asText());
        assertEquals("yes", result.get().path("content").path("value").asText());
    }

    @Test
    void elicitationWaitsUntilItsMcpAssistantToolUseWasWritten() throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw, true);
        SdkControlBroker broker = new SdkControlBroker(
            out, newEngine(), new PermissionGate(), System.getProperty("user.dir"), true);
        String toolUseId = "toolu_mcp_elicitation_order";
        ObjectNode params = MAPPER.createObjectNode();
        params.put("message", "Choose a value");
        params.put("mode", "form");
        params.putObject("requestedSchema").put("type", "object");
        AtomicReference<JsonNode> result = new AtomicReference<>();

        Thread requestThread = Thread.startVirtualThread(() -> result.set(
            broker.askElicitation("wire-elicit", params, Set.of(toolUseId))));

        for (int i = 0; i < 200
                && StringUtils.isBlank(sw.toString())
                && requestThread.getState() != Thread.State.WAITING; i++) {
            Thread.sleep(5);
        }
        assertTrue(StringUtils.isBlank(sw.toString()),
            "elicitation must not overtake the assistant MCP tool_use output");

        broker.onAssistantMessageWritten(new AssistantMessage(
            "assistant-elicitation-order",
            AssistantContent.of(List.of(new ToolUseBlock(
                toolUseId, "mcp__wire-elicit__echo", MAPPER.createObjectNode())))));

        JsonNode request = null;
        for (int i = 0; i < 200 && request == null; i++) {
            String value = sw.toString().trim();
            if (!value.isEmpty()) request = MAPPER.readTree(value.split("\\R")[0]);
            else Thread.sleep(5);
        }
        assertNotNull(request, "elicitation should publish after the assistant row");
        assertTrue(request.path("request").path("tool_use_id").isMissingNode(),
            "ordering metadata is internal and must not alter the 2.1.197 wire request");

        ObjectNode response = MAPPER.createObjectNode().put("type", "control_response");
        ObjectNode envelope = response.putObject("response");
        envelope.put("subtype", "success");
        envelope.put("request_id", request.path("request_id").asText());
        envelope.putObject("response").put("action", "cancel");
        broker.onControlResponse(response);
        requestThread.join();

        assertEquals("cancel", result.get().path("action").asText());
    }

    @Test
    void matchingErrorResponseCompletesPendingAskAsDeny() throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);
        DefaultQuerySession engine = newEngine();
        SdkControlBroker broker = new SdkControlBroker(out, engine, new PermissionGate(), System.getProperty("user.dir"));

        AtomicReference<PermissionAskCallback.Result> result = new AtomicReference<>();
        Thread t = Thread.startVirtualThread(() ->
            result.set(broker.askPermission(PermissionAskContext.simple("Bash", MAPPER.createObjectNode(), "tuB"))));

        String requestId = null;
        for (int i = 0; i < 200 && requestId == null; i++) {
            String s = sw.toString().trim();
            if (!s.isEmpty()) {
                requestId = MAPPER.readTree(s.split("\n")[0]).path("request_id").asText(null);
            } else {
                Thread.sleep(5);
            }
        }
        assertNotNull(requestId);

        broker.onControlResponse(controlResponse("error", requestId, null, null));
        t.join();

        PermissionAskCallback.Result r = result.get();
        assertNotNull(r);
        assertFalse(r.allowed());
    }

    @Test
    void unmatchedSuccessEnqueuesOrphanedPermissionAndDeduplicates() {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);
        DefaultQuerySession engine = newEngine();
        // Seed an unresolved tool_use so findUnresolvedToolUse succeeds.
        engine.getMutableMessages().add(new AssistantMessage("amX",
            AssistantContent.of(List.of(new ToolUseBlock("tuX", "Bash", MAPPER.createObjectNode())))));
        SdkControlBroker broker = new SdkControlBroker(out, engine, new PermissionGate(), System.getProperty("user.dir"));

        // First orphaned response for tuX → should enqueue one command.
        broker.onControlResponse(controlResponse("success", "unknown-req-1", "tuX", "allow"));
        // Duplicate (idempotent) → must NOT enqueue a second time.
        broker.onControlResponse(controlResponse("success", "unknown-req-2", "tuX", "allow"));

        List<QueuedCommand> drained = engine.getMessageQueue().dequeueAllMatching(
            c -> Strings.CS.equals("orphaned-permission", c.mode()));
        assertEquals(1, drained.size());
        QueuedCommand cmd = drained.getFirst();
        assertNotNull(cmd.orphanedPermission());
        assertEquals("tuX", cmd.orphanedPermission().toolUseId());
    }

    @Test
    void orphanedResponseForUnknownToolUseEnqueuesNothing() {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);
        DefaultQuerySession engine = newEngine();
        SdkControlBroker broker = new SdkControlBroker(out, engine, new PermissionGate(), System.getProperty("user.dir"));

        broker.onControlResponse(controlResponse("success", "unknown-req", "never-seen", "allow"));

        List<QueuedCommand> drained = engine.getMessageQueue().dequeueAllMatching(
            c -> Strings.CS.equals("orphaned-permission", c.mode()));
        assertTrue(drained.isEmpty());
    }

    @Test
    void bashControlRequestMatchesReleased197Shape() throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);
        DefaultQuerySession engine = new DefaultQuerySession(
            QuerySessionSpec.builder().llmClient(NOOP_CLIENT).agentId("agent-7").build());
        SdkControlBroker broker = new SdkControlBroker(out, engine, new PermissionGate(), System.getProperty("user.dir"));

        ObjectNode input = MAPPER.createObjectNode();
        input.put("command", "rm -f /private/tmp/cc197-can-use-tool-nonexistent");
        List<PermissionUpdate> typedSuggestions = List.of(
            new PermissionUpdate.AddDirectories(
                List.of("/private/tmp"), PermissionUpdate.Destination.SESSION),
            new PermissionUpdate.SetMode(
                PermissionModeKind.ACCEPT_EDITS, PermissionUpdate.Destination.SESSION));
        PermissionAskContext ctx = PermissionAskContext.builder("Bash", input)
            .toolUseId("tuC")
            .blockedPath("/private/tmp/cc197-can-use-tool-nonexistent")
            .suggestions(typedSuggestions)
            .build();
        Thread t = Thread.startVirtualThread(() -> broker.askPermission(ctx));

        String reqJson = null;
        for (int i = 0; i < 200 && reqJson == null; i++) {
            String s = sw.toString().trim();
            if (!s.isEmpty()) reqJson = s.split("\n")[0];
            else Thread.sleep(5);
        }
        assertNotNull(reqJson, "control_request should have been written");

        JsonNode request = MAPPER.readTree(reqJson).path("request");
        assertEquals("can_use_tool", request.path("subtype").asText());
        assertEquals("Bash", request.path("tool_name").asText());
        assertEquals("Bash", request.path("display_name").asText());
        assertEquals("rm -f /private/tmp/cc197-can-use-tool-nonexistent",
            request.path("description").asText());
        assertEquals("tuC", request.path("tool_use_id").asText());
        assertEquals("/private/tmp/cc197-can-use-tool-nonexistent",
            request.path("blocked_path").asText());
        assertEquals("agent-7", request.path("agent_id").asText(null));
        assertTrue(request.path("permission_suggestions").isArray(), "permission_suggestions must be an array");
        assertEquals(3, request.path("permission_suggestions").size());

        JsonNode rule = request.path("permission_suggestions").get(0);
        assertEquals("addRules", rule.path("type").asText());
        assertEquals("Bash", rule.path("rules").get(0).path("toolName").asText());
        assertEquals("rm -f /private/tmp/cc197-can-use-tool-nonexistent",
            rule.path("rules").get(0).path("ruleContent").asText());
        assertEquals("allow", rule.path("behavior").asText());
        assertEquals("localSettings", rule.path("destination").asText());

        JsonNode directory = request.path("permission_suggestions").get(1);
        assertEquals("addDirectories", directory.path("type").asText());
        assertEquals("/private/tmp", directory.path("directories").get(0).asText());
        assertEquals("session", directory.path("destination").asText());
        JsonNode mode = request.path("permission_suggestions").get(2);
        assertEquals("setMode", mode.path("type").asText());
        assertEquals("acceptEdits", mode.path("mode").asText());
        assertEquals("session", mode.path("destination").asText());
// decision_reason must be absent for the simple (null) decision type.
        assertTrue(request.path("decision_reason").isMissingNode());
        assertTrue(request.path("title").isMissingNode());

        broker.close();
        t.join();
    }

    @Test
    void longBashDescriptionUsesReleased197FiftyCharacterPreview() throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);
        DefaultQuerySession engine = newEngine();
        SdkControlBroker broker = new SdkControlBroker(
            out, engine, new PermissionGate(), System.getProperty("user.dir"));

        String command = "touch /private/tmp/cc-resume-perm-deny-int-marker-v1";
        assertEquals(52, command.length(), "fixture must cross the released preview limit");
        ObjectNode input = MAPPER.createObjectNode().put("command", command);
        Thread t = Thread.startVirtualThread(() -> broker.askPermission(
            PermissionAskContext.simple("Bash", input, "tuLongDescription")));

        String reqJson = null;
        for (int i = 0; i < 200 && reqJson == null; i++) {
            String value = sw.toString().trim();
            if (!value.isEmpty()) reqJson = value.split("\n")[0];
            else Thread.sleep(5);
        }
        assertNotNull(reqJson, "control_request should have been written");

        JsonNode request = MAPPER.readTree(reqJson).path("request");
        assertEquals("touch /private/tmp/cc-resume-perm-deny-int-marker…",
            request.path("description").asText());
        assertEquals(command, request.path("input").path("command").asText(),
            "only the user-facing preview is truncated");

        broker.close();
        t.join();
    }

    @Test
    void matchedControlResponseIsEchoedBeforePermissionAskContinues() throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw, true);
        DefaultQuerySession engine = newEngine();
        SdkControlBroker broker = new SdkControlBroker(
            out, engine, new PermissionGate(), System.getProperty("user.dir"));

        AtomicReference<PermissionAskCallback.Result> result = new AtomicReference<>();
        Thread t = Thread.startVirtualThread(() -> result.set(
            broker.askPermission(PermissionAskContext.simple(
                "Bash", MAPPER.createObjectNode().put("command", "rm -f /tmp/x"), "tuEcho"))));

        String requestId = null;
        for (int i = 0; i < 200 && requestId == null; i++) {
            String s = sw.toString().trim();
            if (!s.isEmpty()) {
                requestId = MAPPER.readTree(s.split("\n")[0]).path("request_id").asText(null);
            } else {
                Thread.sleep(5);
            }
        }
        assertNotNull(requestId);
        ObjectNode response = controlResponse("success", requestId, "tuEcho", "allow");

        CliHeadlessOutput.handleSdkControlResponse(response, broker, out);
        t.join();

        String[] lines = sw.toString().trim().split("\n");
        assertEquals(2, lines.length);
        assertEquals(response, MAPPER.readTree(lines[1]));
        assertTrue(result.get().allowed());
    }

    @Test
    void matchedGenericControlResponseIsNotEchoedWhenReplayIsDisabled() throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw, true);
        DefaultQuerySession engine = newEngine();
        SdkControlBroker broker = new SdkControlBroker(
            out, engine, new PermissionGate(), System.getProperty("user.dir"));

        AtomicReference<JsonNode> result = new AtomicReference<>();
        Thread t = Thread.startVirtualThread(() -> result.set(
            broker.askMcpMessage("sdk-wire", MAPPER.createObjectNode()
                .put("jsonrpc", "2.0").put("id", 7).put("method", "tools/list"))));

        String requestId = null;
        for (int i = 0; i < 200 && requestId == null; i++) {
            String s = sw.toString().trim();
            if (!s.isEmpty()) {
                requestId = MAPPER.readTree(s.split("\n")[0]).path("request_id").asText(null);
            } else {
                Thread.sleep(5);
            }
        }
        assertNotNull(requestId);
        ObjectNode root = MAPPER.createObjectNode().put("type", "control_response");
        ObjectNode response = root.putObject("response");
        response.put("subtype", "success");
        response.put("request_id", requestId);
        response.putObject("response").putObject("mcp_response")
            .put("jsonrpc", "2.0").put("id", 7).putObject("result");

        CliHeadlessOutput.handleSdkControlResponse(root, broker, out, false);
        t.join();

        assertEquals(1, sw.toString().trim().split("\n").length);
        assertEquals(7, result.get().path("id").asInt());
    }

    @Test
    void sdkMcpToolCallWaitsUntilItsAssistantToolUseWasWritten() throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw, true);
        DefaultQuerySession engine = newEngine();
        SdkControlBroker broker = new SdkControlBroker(
            out, engine, new PermissionGate(), System.getProperty("user.dir"), true);

        String toolUseId = "toolu_sdk_order";
        ObjectNode mcpMessage = MAPPER.createObjectNode();
        mcpMessage.put("jsonrpc", "2.0");
        mcpMessage.put("id", 2);
        mcpMessage.put("method", "tools/call");
        ObjectNode params = mcpMessage.putObject("params");
        params.put("name", "sdk_echo");
        params.putObject("arguments").put("marker", "WIRE_SDK_MCP");
        params.putObject("_meta").put("claudecode/toolUseId", toolUseId);

        AtomicReference<JsonNode> result = new AtomicReference<>();
        Thread requestThread = Thread.startVirtualThread(() ->
            result.set(broker.askMcpMessage("sdk-wire", mcpMessage)));

        for (int i = 0; i < 200
                && StringUtils.isBlank(sw.toString())
                && requestThread.getState() != Thread.State.WAITING; i++) {
            Thread.sleep(5);
        }
        assertTrue(StringUtils.isBlank(sw.toString()),
            "tools/call must not overtake the assistant tool_use output");

        broker.onAssistantMessageWritten(new AssistantMessage(
            "assistant-sdk-order",
            AssistantContent.of(List.of(new ToolUseBlock(
                toolUseId, "mcp__sdk-wire__sdk_echo", MAPPER.createObjectNode())))));

        String requestId = null;
        for (int i = 0; i < 200 && requestId == null; i++) {
            String value = sw.toString().trim();
            if (!value.isEmpty()) {
                requestId = MAPPER.readTree(value.split("\\R")[0])
                    .path("request_id").asText(null);
            } else {
                Thread.sleep(5);
            }
        }
        assertNotNull(requestId, "tools/call should be published after assistant output");

        ObjectNode response = MAPPER.createObjectNode().put("type", "control_response");
        ObjectNode envelope = response.putObject("response");
        envelope.put("subtype", "success");
        envelope.put("request_id", requestId);
        envelope.putObject("response").putObject("mcp_response")
            .put("jsonrpc", "2.0").put("id", 2).putObject("result");
        broker.onControlResponse(response);
        requestThread.join();

        assertEquals(2, result.get().path("id").asInt());
    }

    @Test
    void lifecycleHookCallbackDoesNotWaitForItsSyntheticToolUseId() throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw, true);
        SdkControlBroker broker = new SdkControlBroker(
            out, newEngine(), new PermissionGate(), System.getProperty("user.dir"), true);

        ObjectNode input = MAPPER.createObjectNode();
        input.put("hook_event_name", "UserPromptSubmit");
        input.put("prompt", "wire prompt");
        AtomicReference<JsonNode> result = new AtomicReference<>();
        Thread requestThread = Thread.startVirtualThread(() -> result.set(
            broker.askHookCallback("wire-hook", input, "lifecycle-hook-id")));

        JsonNode request = null;
        for (int i = 0; i < 200 && request == null; i++) {
            String value = sw.toString().trim();
            if (!value.isEmpty()) request = MAPPER.readTree(value.split("\\R")[0]);
            else Thread.sleep(5);
        }
        assertNotNull(request,
            "a lifecycle hook id is correlation metadata, not an assistant tool_use barrier");
        assertEquals("hook_callback", request.path("request").path("subtype").asText());
        assertEquals("lifecycle-hook-id",
            request.path("request").path("tool_use_id").asText());
        assertTrue(request.path("request").path("input").path("tool_use_id").isMissingNode());

        ObjectNode response = MAPPER.createObjectNode().put("type", "control_response");
        ObjectNode envelope = response.putObject("response");
        envelope.put("subtype", "success");
        envelope.put("request_id", request.path("request_id").asText());
        envelope.putObject("response").put("continue", true);
        broker.onControlResponse(response);
        requestThread.join();

        assertTrue(result.get().path("continue").asBoolean());
    }

    @Test
    void toolHookCallbackWaitsUntilItsAssistantToolUseWasWritten() throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw, true);
        SdkControlBroker broker = new SdkControlBroker(
            out, newEngine(), new PermissionGate(), System.getProperty("user.dir"), true);

        String toolUseId = "toolu_hook_order";
        ObjectNode input = MAPPER.createObjectNode();
        input.put("hook_event_name", "PreToolUse");
        input.put("tool_use_id", toolUseId);
        AtomicReference<JsonNode> result = new AtomicReference<>();
        Thread requestThread = Thread.startVirtualThread(() -> result.set(
            broker.askHookCallback("wire-tool-hook", input, toolUseId)));

        for (int i = 0; i < 200
                && StringUtils.isBlank(sw.toString())
                && requestThread.getState() != Thread.State.WAITING; i++) {
            Thread.sleep(5);
        }
        assertTrue(StringUtils.isBlank(sw.toString()),
            "a tool hook callback must not overtake its assistant tool_use output");

        broker.onAssistantMessageWritten(new AssistantMessage(
            "assistant-hook-order",
            AssistantContent.of(List.of(new ToolUseBlock(
                toolUseId, "Bash", MAPPER.createObjectNode())))));

        JsonNode request = null;
        for (int i = 0; i < 200 && request == null; i++) {
            String value = sw.toString().trim();
            if (!value.isEmpty()) request = MAPPER.readTree(value.split("\\R")[0]);
            else Thread.sleep(5);
        }
        assertNotNull(request, "tool hook should publish after the assistant row");

        ObjectNode response = MAPPER.createObjectNode().put("type", "control_response");
        ObjectNode envelope = response.putObject("response");
        envelope.put("subtype", "success");
        envelope.put("request_id", request.path("request_id").asText());
        envelope.putObject("response").put("continue", true);
        broker.onControlResponse(response);
        requestThread.join();

        assertTrue(result.get().path("continue").asBoolean());
    }

    @Test
    void fireAndForgetMcpNotificationDoesNotBecomeAShutdownCancellation() throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw, true);
        SdkControlBroker broker = new SdkControlBroker(
            out, newEngine(), new PermissionGate(), System.getProperty("user.dir"));
        ObjectNode notification = MAPPER.createObjectNode()
            .put("jsonrpc", "2.0").put("method", "notifications/cancelled");
        notification.putObject("params").put("requestId", 2);

        broker.sendMcpMessage("sdk-wire", notification);
        broker.close();

        String[] lines = sw.toString().trim().split("\n");
        assertEquals(1, lines.length);
        JsonNode request = MAPPER.readTree(lines[0]);
        assertEquals("mcp_message", request.path("request").path("subtype").asText());
        assertEquals("notifications/cancelled",
            request.path("request").path("message").path("method").asText());
    }

    @Test
    void closeEmitsControlCancelRequest() throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);
        DefaultQuerySession engine = newEngine();
        SdkControlBroker broker = new SdkControlBroker(out, engine, new PermissionGate(), System.getProperty("user.dir"));

        AtomicReference<PermissionAskCallback.Result> result = new AtomicReference<>();
        Thread t = Thread.startVirtualThread(() ->
            result.set(broker.askPermission(PermissionAskContext.simple("Bash", MAPPER.createObjectNode(), "tuD"))));

        String requestId = null;
        for (int i = 0; i < 200 && requestId == null; i++) {
            String s = sw.toString().trim();
            if (!s.isEmpty()) requestId = MAPPER.readTree(s.split("\n")[0]).path("request_id").asText(null);
            else Thread.sleep(5);
        }
        assertNotNull(requestId);

        broker.close();
        t.join();

        PermissionAskCallback.Result r = result.get();
        assertNotNull(r);
        assertFalse(r.allowed());
        // The shutdown must have cancelled the outstanding request.
        JsonNode cancel = MAPPER.readTree(sw.toString().split("\n")[1]);
        assertEquals("control_cancel_request", cancel.path("type").asText());
        assertEquals(requestId, cancel.path("request_id").asText());
    }

    @Test
    void invalidBehaviorTreatedAsDeny() throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);
        DefaultQuerySession engine = newEngine();
        SdkControlBroker broker = new SdkControlBroker(out, engine, new PermissionGate(), System.getProperty("user.dir"));

        AtomicReference<PermissionAskCallback.Result> result = new AtomicReference<>();
        Thread t = Thread.startVirtualThread(() ->
            result.set(broker.askPermission(PermissionAskContext.simple("Bash", MAPPER.createObjectNode(), "tuE"))));

        String requestId = null;
        for (int i = 0; i < 200 && requestId == null; i++) {
            String s = sw.toString().trim();
            if (!s.isEmpty()) requestId = MAPPER.readTree(s.split("\n")[0]).path("request_id").asText(null);
            else Thread.sleep(5);
        }
        assertNotNull(requestId);

        broker.onControlResponse(controlResponse("success", requestId, "tuE", "weird"));
        t.join();

        PermissionAskCallback.Result r = result.get();
        assertNotNull(r);
        assertFalse(r.allowed());
    }

    @Test
    void blockedPathIsNotInferredFromOrdinaryToolInput() throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);
        DefaultQuerySession engine = newEngine();
        SdkControlBroker broker = new SdkControlBroker(out, engine, new PermissionGate(), System.getProperty("user.dir"));

        ObjectNode input = MAPPER.createObjectNode();
        input.put("file_path", "/tmp/foo.txt");
        Thread t = Thread.startVirtualThread(() ->
            broker.askPermission(PermissionAskContext.builder("Write", input)
                .toolUseId("tuBP")
                .build()));

        String reqJson = null;
        for (int i = 0; i < 200 && reqJson == null; i++) {
            String s = sw.toString().trim();
            if (!s.isEmpty()) reqJson = s.split("\n")[0];
            else Thread.sleep(5);
        }
        assertNotNull(reqJson, "control_request should have been written");

        JsonNode request = MAPPER.readTree(reqJson).path("request");
        assertTrue(request.path("blocked_path").isMissingNode());

        broker.close();
        t.join();
    }

    @Test
    void blockedPathPrefersSafetyCheckPathOverInput() throws Exception {
        // When a path-safety check produced a resolved blocked_path, it must take
        // priority over the best-effort file_path extraction from the tool input.
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);
        DefaultQuerySession engine = newEngine();
        SdkControlBroker broker = new SdkControlBroker(out, engine, new PermissionGate(), System.getProperty("user.dir"));

        ObjectNode input = MAPPER.createObjectNode();
        input.put("file_path", "/tmp/from-input.txt");
        Thread t = Thread.startVirtualThread(() ->
            broker.askPermission(PermissionAskContext.builder("Bash", input)
                .toolUseId("tuBP")
                .blockedPath("/resolved/blocked.txt")
                .build()));

        String reqJson = null;
        for (int i = 0; i < 200 && reqJson == null; i++) {
            String s = sw.toString().trim();
            if (!s.isEmpty()) reqJson = s.split("\n")[0];
            else Thread.sleep(5);
        }
        assertNotNull(reqJson, "control_request should have been written");

        JsonNode request = MAPPER.readTree(reqJson).path("request");
        assertEquals("/resolved/blocked.txt", request.path("blocked_path").asText(null));

        broker.close();
        t.join();
    }

    @Test
    void allowWithUpdatedPermissionsAppliesAddRules() throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);
        DefaultQuerySession engine = newEngine();
        PermissionGate gate = new PermissionGate();
        SdkControlBroker broker = new SdkControlBroker(out, engine, gate, System.getProperty("user.dir"));

        AtomicReference<PermissionAskCallback.Result> result = new AtomicReference<>();
        Thread t = Thread.startVirtualThread(() ->
            result.set(broker.askPermission(PermissionAskContext.simple("Bash", MAPPER.createObjectNode(), "tuUP"))));

        String requestId = null;
        for (int i = 0; i < 200 && requestId == null; i++) {
            String s = sw.toString().trim();
            if (!s.isEmpty()) requestId = MAPPER.readTree(s.split("\n")[0]).path("request_id").asText(null);
            else Thread.sleep(5);
        }
        assertNotNull(requestId);

        // success + allow + updatedPermissions: addRules Bash(git:*)
        ObjectNode resp = MAPPER.createObjectNode();
        resp.put("type", "control_response");
        ObjectNode response = resp.putObject("response");
        response.put("subtype", "success");
        response.put("request_id", requestId);
        ObjectNode inner = response.putObject("response");
        inner.put("behavior", "allow");
        inner.put("toolUseID", "tuUP");
        ArrayNode updates = MAPPER.createArrayNode();
        ObjectNode upd = MAPPER.createObjectNode();
        upd.put("type", "addRules");
        upd.put("behavior", "allow");
        upd.put("destination", "projectSettings");
        ArrayNode rules = MAPPER.createArrayNode();
        ObjectNode r = MAPPER.createObjectNode();
        r.put("toolName", "Bash");
        r.put("ruleContent", "git:*");
        rules.add(r);
        upd.set("rules", rules);
        updates.add(upd);
        inner.set("updatedPermissions", updates);
        broker.onControlResponse(resp);
        t.join();

        assertTrue(result.get().allowed());
        boolean found = gate.currentContext().rules().stream()
            .anyMatch(rule -> Strings.CS.equals("Bash", rule.toolName())
                && rule.pattern().map(p -> Strings.CS.equals("git:*", p)).orElse(false));
        assertTrue(found, "gate should contain the injected Bash(git:*) allow rule");
    }

    @Test
    void allowWithUpdatedPermissionsPersistsToProjectSettings() throws Exception {
        Path tempDir = Files.createTempDirectory("sdk-perm-test");
        String cwd = tempDir.toString();
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);
        DefaultQuerySession engine = newEngine();
        PermissionGate gate = new PermissionGate();
        SdkControlBroker broker = new SdkControlBroker(out, engine, gate, cwd);

        AtomicReference<PermissionAskCallback.Result> result = new AtomicReference<>();
        Thread t = Thread.startVirtualThread(() ->
            result.set(broker.askPermission(PermissionAskContext.simple("Bash", MAPPER.createObjectNode(), "tuPERS"))));

        String requestId = null;
        for (int i = 0; i < 200 && requestId == null; i++) {
            String s = sw.toString().trim();
            if (!s.isEmpty()) requestId = MAPPER.readTree(s.split("\n")[0]).path("request_id").asText(null);
            else Thread.sleep(5);
        }
        assertNotNull(requestId);

        // success + allow + updatedPermissions(addRules, destination projectSettings)
        ObjectNode resp = MAPPER.createObjectNode();
        resp.put("type", "control_response");
        ObjectNode response = resp.putObject("response");
        response.put("subtype", "success");
        response.put("request_id", requestId);
        ObjectNode inner = response.putObject("response");
        inner.put("behavior", "allow");
        inner.put("toolUseID", "tuPERS");
        ArrayNode updates = MAPPER.createArrayNode();
        ObjectNode upd = MAPPER.createObjectNode();
        upd.put("type", "addRules");
        upd.put("behavior", "allow");
        upd.put("destination", "projectSettings");
        ArrayNode rules = MAPPER.createArrayNode();
        ObjectNode r = MAPPER.createObjectNode();
        r.put("toolName", "Bash");
        r.put("ruleContent", "git:*");
        rules.add(r);
        upd.set("rules", rules);
        updates.add(upd);
        inner.set("updatedPermissions", updates);
        broker.onControlResponse(resp);
        t.join();

        assertTrue(result.get().allowed());
        Path settingsPath = SettingsPaths.projectSettingsPath(cwd);
        assertTrue(Files.exists(settingsPath), "project settings.json should be written");
        List<PermissionRule> persisted = PermissionSettings.loadPermissionRulesFromFile(settingsPath, RuleSource.PROJECT_SETTINGS);
        boolean found = persisted.stream()
            .anyMatch(rule -> Strings.CS.equals("Bash", rule.toolName())
                && rule.pattern().map(p -> Strings.CS.equals("git:*", p)).orElse(false)
                && rule.behavior() == PermissionBehavior.ALLOW);
        assertTrue(found, "project settings should contain the persisted Bash(git:*) allow rule");
    }

    @Test
    void denyWithInterruptAbortsEngine() throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);
        DefaultQuerySession engine = newEngine();
        SdkControlBroker broker = new SdkControlBroker(out, engine, new PermissionGate(), System.getProperty("user.dir"));

        AtomicReference<PermissionAskCallback.Result> result = new AtomicReference<>();
        Thread t = Thread.startVirtualThread(() ->
            result.set(broker.askPermission(PermissionAskContext.simple("Bash", MAPPER.createObjectNode(), "tuINT"))));

        String requestId = null;
        for (int i = 0; i < 200 && requestId == null; i++) {
            String s = sw.toString().trim();
            if (!s.isEmpty()) requestId = MAPPER.readTree(s.split("\n")[0]).path("request_id").asText(null);
            else Thread.sleep(5);
        }
        assertNotNull(requestId);

        ObjectNode resp = MAPPER.createObjectNode();
        resp.put("type", "control_response");
        ObjectNode response = resp.putObject("response");
        response.put("subtype", "success");
        response.put("request_id", requestId);
        ObjectNode inner = response.putObject("response");
        inner.put("behavior", "deny");
        inner.put("message", "nope");
        inner.put("interrupt", true);
        broker.onControlResponse(resp);
        t.join();

        assertFalse(result.get().allowed());
        assertTrue(result.get().directDenial());
        assertEquals("nope", result.get().feedback());
        assertTrue(engine.getAbortController().isAborted(), "deny+interrupt must abort the engine");
    }

    @Test
    void orphanedDenyWithInterruptDoesNotAbortImmediately() throws Exception {

        // rejection tool_result is emitted first (honored in OrphanedPermissionExecutor).
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);
        DefaultQuerySession engine = newEngine();
        // Seed an unresolved tool_use so the orphaned path can recover it.
        ToolUseBlock tub = new ToolUseBlock("tuORPH", "Bash", MAPPER.createObjectNode());
        engine.getMutableMessages().add(
            new AssistantMessage("am-tuORPH", AssistantContent.of(List.of(tub))));
        SdkControlBroker broker = new SdkControlBroker(out, engine, new PermissionGate(), System.getProperty("user.dir"));

        ObjectNode resp = MAPPER.createObjectNode();
        resp.put("type", "control_response");
        ObjectNode response = resp.putObject("response");
        response.put("subtype", "success");
        response.put("request_id", "no-such-pending-id"); // forces orphaned recovery
        ObjectNode inner = response.putObject("response");
        inner.put("behavior", "deny");
        inner.put("toolUseID", "tuORPH");
        inner.put("message", "denied by controller");
        inner.put("interrupt", true);
        broker.onControlResponse(resp);

        assertFalse(engine.getAbortController().isAborted(),
            "orphaned deny+interrupt must not abort immediately at the broker");
    }

    @Test
    void allowWithUpdatedPermissionsAppliesAddDirectories() throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);
        DefaultQuerySession engine = newEngine();
        PermissionGate gate = new PermissionGate();
        SdkControlBroker broker = new SdkControlBroker(out, engine, gate, System.getProperty("user.dir"));

        AtomicReference<PermissionAskCallback.Result> result = new AtomicReference<>();
        Thread t = Thread.startVirtualThread(() ->
            result.set(broker.askPermission(PermissionAskContext.simple("Bash", MAPPER.createObjectNode(), "tuDIR"))));

        String requestId = null;
        for (int i = 0; i < 200 && requestId == null; i++) {
            String s = sw.toString().trim();
            if (!s.isEmpty()) requestId = MAPPER.readTree(s.split("\n")[0]).path("request_id").asText(null);
            else Thread.sleep(5);
        }
        assertNotNull(requestId);

        ObjectNode resp = MAPPER.createObjectNode();
        resp.put("type", "control_response");
        ObjectNode response = resp.putObject("response");
        response.put("subtype", "success");
        response.put("request_id", requestId);
        ObjectNode inner = response.putObject("response");
        inner.put("behavior", "allow");
        inner.put("toolUseID", "tuDIR");
        ArrayNode updates = MAPPER.createArrayNode();
        ObjectNode upd = MAPPER.createObjectNode();
        upd.put("type", "addDirectories");
        upd.put("destination", "projectSettings");
        ArrayNode dirs = MAPPER.createArrayNode();
        dirs.add("/tmp/sdk-extra-dir");
        upd.set("directories", dirs);
        updates.add(upd);
        inner.set("updatedPermissions", updates);
        broker.onControlResponse(resp);
        t.join();

        assertTrue(result.get().allowed());
        assertTrue(gate.currentContext().additionalDirs().containsKey(Path.of("/tmp/sdk-extra-dir")),
            "addDirectories must apply in-session");
        assertEquals(RuleSource.PROJECT_SETTINGS,
            gate.currentContext().additionalDirs().get(Path.of("/tmp/sdk-extra-dir")),
            "directory source must mirror the update destination");
    }

    @Test
    void allowWithSessionDestinationAppliesInSessionSource() throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);
        DefaultQuerySession engine = newEngine();
        PermissionGate gate = new PermissionGate();
        SdkControlBroker broker = new SdkControlBroker(out, engine, gate, System.getProperty("user.dir"));

        AtomicReference<PermissionAskCallback.Result> result = new AtomicReference<>();
        Thread t = Thread.startVirtualThread(() ->
            result.set(broker.askPermission(PermissionAskContext.simple("Bash", MAPPER.createObjectNode(), "tuSES"))));

        String requestId = null;
        for (int i = 0; i < 200 && requestId == null; i++) {
            String s = sw.toString().trim();
            if (!s.isEmpty()) requestId = MAPPER.readTree(s.split("\n")[0]).path("request_id").asText(null);
            else Thread.sleep(5);
        }
        assertNotNull(requestId);

        ObjectNode resp = MAPPER.createObjectNode();
        resp.put("type", "control_response");
        ObjectNode response = resp.putObject("response");
        response.put("subtype", "success");
        response.put("request_id", requestId);
        ObjectNode inner = response.putObject("response");
        inner.put("behavior", "allow");
        inner.put("toolUseID", "tuSES");
        ArrayNode updates = MAPPER.createArrayNode();
        ObjectNode upd = MAPPER.createObjectNode();
        upd.put("type", "addRules");
        upd.put("behavior", "allow");
        upd.put("destination", "session");
        ArrayNode rules = MAPPER.createArrayNode();
        ObjectNode r = MAPPER.createObjectNode();
        r.put("toolName", "Bash");
        r.put("ruleContent", "git:*");
        rules.add(r);
        upd.set("rules", rules);
        updates.add(upd);
        inner.set("updatedPermissions", updates);
        broker.onControlResponse(resp);
        t.join();

        assertTrue(result.get().allowed());
        boolean sessionScoped = gate.currentContext().rules().stream()
            .anyMatch(rule -> Strings.CS.equals("Bash", rule.toolName())
                && rule.pattern().map(p -> Strings.CS.equals("git:*", p)).orElse(false)
                && rule.source() == RuleSource.SESSION);
        assertTrue(sessionScoped, "session-destination rules must be stored under SESSION source in-memory");
    }

    @Test
    void allowWithUpdatedPermissionsReplacesAndRemovesOnlyMatchingRuleBucket() throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);
        DefaultQuerySession engine = newEngine();
        PermissionGate gate = new PermissionGate();
        gate.addRules(List.of(
            PermissionRule.withPattern("Bash", PermissionBehavior.ALLOW,
                RuleSource.PROJECT_SETTINGS, "old:*"),
            PermissionRule.withPattern("Bash", PermissionBehavior.ALLOW,
                RuleSource.SESSION, "session:*"),
            PermissionRule.withPattern("Bash", PermissionBehavior.DENY,
                RuleSource.PROJECT_SETTINGS, "blocked:*")));
        SdkControlBroker broker = new SdkControlBroker(out, engine, gate, System.getProperty("user.dir"));

        AtomicReference<PermissionAskCallback.Result> result = new AtomicReference<>();
        Thread t = Thread.startVirtualThread(() ->
            result.set(broker.askPermission(PermissionAskContext.simple(
                "Bash", MAPPER.createObjectNode(), "tuREPLACE"))));

        String requestId = null;
        for (int i = 0; i < 200 && requestId == null; i++) {
            String s = sw.toString().trim();
            if (!s.isEmpty()) requestId = MAPPER.readTree(s.split("\\R")[0]).path("request_id").asText(null);
            else Thread.sleep(5);
        }
        assertNotNull(requestId);

        ObjectNode resp = controlResponse("success", requestId, "tuREPLACE", "allow");
        ObjectNode inner = resp.path("response").with("response");
        ArrayNode updates = MAPPER.createArrayNode();

        ObjectNode replace = MAPPER.createObjectNode();
        replace.put("type", "replaceRules");
        replace.put("behavior", "allow");
        replace.put("destination", "projectSettings");
        replace.putArray("rules").addObject()
            .put("toolName", "Bash").put("ruleContent", "new:*");
        updates.add(replace);

        ObjectNode remove = MAPPER.createObjectNode();
        remove.put("type", "removeRules");
        remove.put("behavior", "deny");
        remove.put("destination", "projectSettings");
        remove.putArray("rules").addObject()
            .put("toolName", "Bash").put("ruleContent", "blocked:*");
        updates.add(remove);
        inner.set("updatedPermissions", updates);

        broker.onControlResponse(resp);
        t.join();

        assertTrue(result.get().allowed());
        List<PermissionRule> rules = gate.currentContext().rules();
        assertTrue(rules.stream().anyMatch(r -> r.source() == RuleSource.PROJECT_SETTINGS
            && r.behavior() == PermissionBehavior.ALLOW
            && r.pattern().map("new:*"::equals).orElse(false)));
        assertTrue(rules.stream().noneMatch(r -> r.source() == RuleSource.PROJECT_SETTINGS
            && r.behavior() == PermissionBehavior.ALLOW
            && r.pattern().map("old:*"::equals).orElse(false)));
        assertTrue(rules.stream().noneMatch(r -> r.source() == RuleSource.PROJECT_SETTINGS
            && r.behavior() == PermissionBehavior.DENY
            && r.pattern().map("blocked:*"::equals).orElse(false)));
        assertTrue(rules.stream().anyMatch(r -> r.source() == RuleSource.SESSION
            && r.behavior() == PermissionBehavior.ALLOW
            && r.pattern().map("session:*"::equals).orElse(false)),
            "replace/remove must not touch another destination or behavior bucket");
    }

    @Test
    void malformedUpdatedPermissionsAreIgnoredAsOneInvalidField() throws Exception {
        StringWriter sw = new StringWriter();
        DefaultQuerySession engine = newEngine();
        PermissionGate gate = new PermissionGate();
        SdkControlBroker broker = new SdkControlBroker(new PrintWriter(sw), engine, gate,
            System.getProperty("user.dir"));
        AtomicReference<PermissionAskCallback.Result> result = new AtomicReference<>();
        Thread t = Thread.startVirtualThread(() -> result.set(
            broker.askPermission(PermissionAskContext.simple(
                "Bash", MAPPER.createObjectNode(), "tuBADUP"))));

        String requestId = null;
        for (int i = 0; i < 200 && requestId == null; i++) {
            String s = sw.toString().trim();
            if (!s.isEmpty()) requestId = MAPPER.readTree(s.split("\\R")[0]).path("request_id").asText(null);
            else Thread.sleep(5);
        }
        assertNotNull(requestId);

        ObjectNode response = controlResponse("success", requestId, "tuBADUP", "allow");
        ObjectNode inner = (ObjectNode) response.path("response").path("response");
        ArrayNode updates = MAPPER.createArrayNode();
        updates.addObject().put("type", "addRules")
            .put("behavior", "allow").put("destination", "session")
            .putArray("rules").addObject().put("toolName", "Bash");
        updates.addObject().put("type", "not-a-permission-update");
        inner.set("updatedPermissions", updates);

        broker.onControlResponse(response);
        t.join();

        assertTrue(result.get().allowed());
        assertTrue(gate.currentContext().rules().isEmpty(),
            "TS schema catch ignores the whole malformed updatedPermissions array");
    }

    @Test
    void emptyReplaceRulesClearsPersistedBehaviorBucket() throws Exception {
        Path cwd = Files.createTempDirectory("sdk-perm-empty-replace");
        Path settingsPath = SettingsPaths.projectSettingsPath(cwd.toString());
        Files.createDirectories(settingsPath.getParent());
        Files.writeString(settingsPath, "{\"permissions\":{\"allow\":[\"Bash(old:*)\"]}}");

        PermissionSettings.replacePermissionRules(cwd.toString(), PermissionBehavior.ALLOW,
            List.of(), RuleSource.PROJECT_SETTINGS);

        assertTrue(PermissionSettings.loadPermissionRulesFromFile(
            settingsPath, RuleSource.PROJECT_SETTINGS).isEmpty());
    }
}
