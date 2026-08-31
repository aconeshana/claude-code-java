package com.claudecode.session;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.serialization.JsonUtils;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.AutoModeReminderAttachment;
import com.claudecode.core.message.CompactMetadata;
import com.claudecode.core.message.FileContentAttachment;
import com.claudecode.core.message.GroupedToolUseMessage;
import com.claudecode.core.message.HookResultMessage;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageFactory;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.PreservedMessages;
import com.claudecode.core.message.PreservedSegment;
import com.claudecode.core.message.ProgressMessage;
import com.claudecode.core.message.RefusalFallbackAnnouncement;
import com.claudecode.core.message.StopDetails;
import com.claudecode.core.message.SummarizeMetadata;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.TombstoneMessage;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.ToolUseSummaryMessage;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.engine.ToolResultBudget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SessionStorage JSONL read/write and the shared JsonUtils ObjectMapper configuration.
 */
class SessionStorageTest {

    @TempDir
    Path tempDir;

    private SessionStorage storage;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = JsonUtils.getMapper();
        storage = new SessionStorage(mapper);
    }

    // ---- Roundtrip tests: write → read → verify equality ----

    @Test
    void roundtripUserMessage() {
        Path file = tempDir.resolve("user.jsonl");
        UserMessage msg = new UserMessage(
                UUID.randomUUID().toString(),
                MessageContent.ofText("Hello, world!"),
                false, false, null, MessageOrigin.USER,
                null, Instant.now(), null, null, null
        );

        storage.appendMessage(file, msg);
        List<Message> messages = storage.readMessages(file);

        assertEquals(1, messages.size());
        assertInstanceOf(UserMessage.class, messages.getFirst());
        UserMessage restored = (UserMessage) messages.getFirst();
        assertEquals(msg.uuid(), restored.uuid());
        assertEquals("user", restored.type());
        assertTrue(restored.message().isText());
        assertEquals("Hello, world!", restored.message().text());
    }

    @Test
    void appendModeUsesOfficialResumeMetadataShape() throws Exception {
        Path file = tempDir.resolve("mode.jsonl");

        storage.appendMode(file, "session-1", "normal");

        assertEquals(
            mapper.readTree("{\"type\":\"mode\",\"mode\":\"normal\",\"sessionId\":\"session-1\"}"),
            mapper.readTree(Files.readString(file).trim()));
    }

    @Test
    void writesReleasedInformationalSystemMessageWithExplicitNonMetaFlag() throws Exception {
        Path file = tempDir.resolve("informational.jsonl");
        SystemMessage notice = new SystemMessage(
            "notice-uuid", "informational", "notice", "Auto mode notice",
            null, Instant.parse("2026-08-02T01:03:37.433Z"), null);

        storage.appendMessageWithParent(file, notice, "session-1", "/tmp/project",
            false, null, "HEAD", null, null);

        JsonNode line = mapper.readTree(Files.readString(file).trim());
        assertEquals("system", line.path("type").asText());
        assertEquals("informational", line.path("subtype").asText());
        assertEquals("notice", line.path("level").asText());
        assertFalse(line.path("isMeta").asBoolean(true));
        assertTrue(line.has("isMeta"), "2.1.197 persists isMeta:false explicitly");
    }

    @Test
    void writesOfficialUserTranscriptShape() throws Exception {
        Path file = tempDir.resolve("official-user.jsonl");
        UserMessage msg = new UserMessage(
            "user-uuid", MessageContent.ofText("hello"), false, false, null,
            MessageOrigin.USER, null, Instant.parse("2026-07-28T00:00:00Z"),
            null, "bypassPermissions", "session-1", null);

        storage.appendMessageWithParent(file, msg, "session-1", "/tmp/project",
            false, null, "main", null, null);

        var line = mapper.readTree(Files.readString(file).trim());
        assertEquals("user", line.get("type").asText());
        assertEquals("user", line.get("message").get("role").asText());
        assertEquals("hello", line.get("message").get("content").asText());
        assertTrue(line.hasNonNull("promptId"));
        assertEquals("sdk", line.get("promptSource").asText());
        assertFalse(line.has("isMeta"), "false Java-only defaults must not pollute official JSONL");
        assertFalse(line.has("isCompactSummary"));
        assertFalse(line.has("origin"));

        UserMessage restored = (UserMessage) storage.readMessages(file).getFirst();
        assertEquals("hello", restored.message().text(),
            "the official write shape must remain readable by Java resume");
    }

    @Test
    void syntheticToolInterruptionDoesNotClaimSdkPromptSource() throws Exception {
        Path file = tempDir.resolve("official-tool-interruption.jsonl");
        UserMessage interruption = MessageFactory.createUserInterruptionMessage(true);
        assertNull(interruption.origin());

        storage.appendMessageWithParent(file, interruption,
            "session-1", "/tmp/project", false, null, "HEAD",
            null, null, "tool-result-parent", "prompt-1", "sdk");

        JsonNode line = mapper.readTree(Files.readString(file).trim());
        assertEquals("prompt-1", line.path("promptId").asText());
        assertEquals(MessageConstants.INTERRUPT_MESSAGE_FOR_TOOL_USE,
            line.path("message").path("content").get(0).path("text").asText());
        assertFalse(line.has("promptSource"),
            "released 2.1.197 treats the generated interruption row as synthetic, not SDK input");
    }

    @Test
    void readsOfficial197TypedUserOriginObject() throws Exception {
        Path file = tempDir.resolve("official-typed-user.jsonl");
        Files.writeString(file, """
            {"parentUuid":null,"isSidechain":false,"promptId":"prompt-1","type":"user","message":{"role":"user","content":"TTY seed prompt"},"uuid":"user-1","timestamp":"2026-08-01T16:27:40.404Z","permissionMode":"default","origin":{"kind":"human"},"promptSource":"typed","userType":"external","entrypoint":"cli","cwd":"/tmp/project","sessionId":"session-1","version":"2.1.197","gitBranch":"HEAD"}
            """, StandardCharsets.UTF_8);

        List<Message> messages = storage.readMessages(file);

        assertEquals(1, messages.size(),
            "official typed user rows must not be dropped during TTY resume/continue");
        UserMessage restored = assertInstanceOf(UserMessage.class, messages.getFirst());
        assertEquals("TTY seed prompt", restored.message().text());
        assertEquals(MessageOrigin.USER, restored.origin());
    }

    @Test
    void planClearHandoffRoundTripsAutoContinuationOriginAndPlanContent() throws Exception {
        Path file = tempDir.resolve("plan-clear-handoff.jsonl");
        UserMessage msg = new UserMessage(
            "user-plan", MessageContent.ofText("Implement the following plan:\nPlan body"),
            false, false, null, MessageOrigin.AUTO_CONTINUATION, null,
            Instant.parse("2026-08-27T00:00:00Z"), null, "acceptEdits", "session-1",
            null, null, null, null, null, "Plan body");

        storage.appendMessageWithParent(file, msg, "session-1", "/tmp/project",
            false, null, "main", null, null);

        JsonNode line = mapper.readTree(Files.readString(file).trim());
        assertEquals("auto-continuation", line.path("origin").path("kind").asText());
        assertEquals("Plan body", line.path("planContent").asText());
        UserMessage restored = assertInstanceOf(UserMessage.class,
            storage.readMessages(file).getFirst());
        assertEquals(MessageOrigin.AUTO_CONTINUATION, restored.origin());
        assertEquals("Plan body", restored.planContent());
    }

    @Test
    void subagentSidechainUserCarriesPromptIdWithoutPromptSource() throws Exception {
        Path file = tempDir.resolve("official-sidechain-user.jsonl");
        UserMessage msg = new UserMessage(
            "child-user", MessageContent.ofText("inspect"), false, false, null,
            MessageOrigin.USER, null, Instant.now(), null, "bypassPermissions");

        storage.appendMessageWithParent(file, msg, "session-1", "/tmp/project",
            true, "a0123456789abcdef", "main", null, null, null,
            "c89702eb-b449-4d7d-a041-017aff847bd5", null);

        JsonNode line = mapper.readTree(Files.readString(file).trim());
        assertEquals("c89702eb-b449-4d7d-a041-017aff847bd5",
            line.path("promptId").asText());
        assertFalse(line.has("promptSource"),
            "released 2.1.197 sidechain prompts do not claim SDK provenance");
        assertFalse(line.has("permissionMode"),
            "released sidechain initial prompts do not copy the parent permission mode");
        assertTrue(line.path("isSidechain").asBoolean());
        assertEquals("a0123456789abcdef", line.path("agentId").asText());
    }

    @Test
    void postCompactFileAttachmentUsesReleasedStructuredRawPayload() throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("project"));
        Path source = project.resolve("probe.txt");
        Files.writeString(source, "alpha\nbeta\n");
        Path transcript = tempDir.resolve("file-attachment.jsonl");
        AttachmentMessage message = new AttachmentMessage(
            "attachment-1", new FileContentAttachment(source.toString(), "1\talpha\n2\tbeta\n3\t"));

        storage.appendMessageWithParent(transcript, message, "session-1",
            project.toString(), false, null, "HEAD", null, null, null);

        JsonNode line = mapper.readTree(Files.readString(transcript).trim());
        JsonNode attachment = line.path("attachment");
        assertEquals("probe.txt", attachment.path("displayPath").asText());
        assertEquals("alpha\nbeta\n", attachment.path("content").path("file")
            .path("content").asText());
        assertEquals(3, attachment.path("content").path("file").path("numLines").asInt());
    }

    @Test
    void writesOfficial197CompactBoundaryMetadataShape() throws Exception {
        Path file = tempDir.resolve("official-compact-boundary.jsonl");
        CompactMetadata metadata = new CompactMetadata(
            "auto", 12_008L, 78L,
            new PreservedSegment("a2", "summary", "u3"),
            new PreservedMessages("summary", List.of("a2", "u3"), List.of("a2", "u3")),
            637L, 11_371L, null, null, null, null);
        SystemMessage boundary = new SystemMessage(
            "boundary", "compact_boundary", "info", "Conversation compacted",
            null, Instant.parse("2026-07-30T03:34:51.215Z"), metadata);

        storage.appendMessageWithParent(file, boundary, "session-1", "/tmp/project",
            false, null, "main", null, null);

        var line = mapper.readTree(Files.readString(file).trim());
        assertEquals("Conversation compacted", line.get("content").asText());
        var compact = line.get("compactMetadata");
        assertEquals("auto", compact.get("trigger").asText());
        assertEquals(12_008L, compact.get("preTokens").asLong());
        assertEquals(78L, compact.get("durationMs").asLong());
        assertEquals(637L, compact.get("postTokens").asLong());
        assertEquals(11_371L, compact.get("cumulativeDroppedTokens").asLong());
        assertEquals("a2", compact.get("preservedSegment").get("headUuid").asText());
        assertEquals("summary", compact.get("preservedMessages").get("anchorUuid").asText());
        assertTrue(line.has("isMeta"));
        assertFalse(line.get("isMeta").asBoolean(),
            "2.1.197 writes compact boundaries with an explicit isMeta:false field");
        assertFalse(compact.has("pre_tokens"), "native JSONL uses internal camelCase, not SDK snake_case");

        SystemMessage restored = (SystemMessage) storage.readMessages(file).getFirst();
        assertEquals(metadata, restored.compactMetadata());
    }

    @Test
    void roundTripsARefusalFallbackRetractionList() throws Exception {
        Path file = tempDir.resolve("refusal-fallback.jsonl");
        SystemMessage announcement = RefusalFallbackAnnouncement.row(
            "ann-1", "claude-fable-5", "claude-opus-4-8",
            new StopDetails("cyber", "Flagged by policy."), "req-197",
            List.of("f47ac10b-58cc-4372-a567-000000000001"), "user-1");

        storage.appendMessageWithParent(file, announcement, "session-1", "/tmp/project",
            false, null, "main", null, null);

        var line = mapper.readTree(Files.readString(file).trim());
        assertFalse(line.has("retracted_message_uuids"),
            "native JSONL uses internal camelCase, not the SDK's snake_case");
        assertEquals("f47ac10b-58cc-4372-a567-000000000001",
            line.get("retractedMessageUuids").get(0).asText());
        assertFalse(line.path("isMeta").asBoolean(true));
        assertTrue(line.has("isMeta"));
        assertEquals("retry", line.path("direction").asText());
        assertEquals("refusal", line.path("trigger").asText());
        assertEquals("claude-fable-5", line.path("originalModel").asText());
        assertEquals("claude-opus-4-8", line.path("fallbackModel").asText());
        assertEquals("req-197", line.path("requestId").asText());
        assertEquals("cyber", line.path("apiRefusalCategory").asText());
        assertEquals("Flagged by policy.", line.path("apiRefusalExplanation").asText());

        SystemMessage restored = (SystemMessage) storage.readMessages(file).getFirst();
        assertEquals(List.of("f47ac10b-58cc-4372-a567-000000000001"),
            restored.retractedMessageUuids());
        assertEquals("req-197", restored.requestId());
        assertEquals("Flagged by policy.", restored.apiRefusalExplanation());
    }

    @Test
    void systemRowsWithoutRetractionsOmitTheKeyEntirely() throws Exception {
        Path file = tempDir.resolve("plain-system.jsonl");
        SystemMessage notice = new SystemMessage("n-1", "informational", "warning", "heads up");

        storage.appendMessageWithParent(file, notice, "session-1", "/tmp/project",
            false, null, "main", null, null);

        var line = mapper.readTree(Files.readString(file).trim());
        assertFalse(line.has("retractedMessageUuids"),
            "the new field must not widen every existing system row");
    }

    @Test
    void writesOfficial197CompactSummaryTranscriptShape() throws Exception {
        Path file = tempDir.resolve("official-compact-summary.jsonl");
        UserMessage summary = new UserMessage(
            "summary-uuid", MessageContent.ofText("compacted context"),
            false, true, null, MessageOrigin.COMPACT_SUMMARY,
            null, Instant.parse("2026-07-30T08:33:07.470Z"),
            null, null, null, null);

        storage.appendMessageWithParent(file, summary, "session-1", "/tmp/project",
            false, null, "main", null, "boundary-uuid");

        JsonNode line = mapper.readTree(Files.readString(file).trim());
        assertTrue(line.path("isCompactSummary").asBoolean());
        assertTrue(line.path("isVisibleInTranscriptOnly").asBoolean(),
            "full compact summaries are transcript-only in the released 2.1.197 JSONL");
        assertFalse(line.has("origin"), "MessageOrigin.COMPACT_SUMMARY is Java-internal and must not leak");
        assertFalse(line.has("promptSource"), "synthetic compact summaries are not SDK-submitted prompts");
        assertTrue(line.hasNonNull("promptId"));
    }

    @Test
    void writesOfficial197PartialCompactSummaryTranscriptShape() throws Exception {
        Path file = tempDir.resolve("official-partial-compact-summary.jsonl");
        UserMessage summary = new UserMessage(
            "summary-uuid", MessageContent.ofText("compacted context"),
            false, true, null, MessageOrigin.COMPACT_SUMMARY,
            null, Instant.parse("2026-07-30T08:33:07.470Z"),
            null, null, null, null, null, null, null, null, null,
            new SummarizeMetadata(5, "focus on rewind", "from"));

        storage.appendMessageWithParent(file, summary, "session-1", "/tmp/project",
            false, null, "main", null, "boundary-uuid");

        JsonNode line = mapper.readTree(Files.readString(file).trim());
        assertEquals(5, line.path("summarizeMetadata").path("messagesSummarized").asInt());
        assertEquals("focus on rewind",
            line.path("summarizeMetadata").path("userContext").asText());
        assertEquals("from", line.path("summarizeMetadata").path("direction").asText());
        assertFalse(line.has("isVisibleInTranscriptOnly"),
            "partial compact summaries with kept messages render in the normal conversation");
    }

    @Test
    void localCommandTranscriptRowsDoNotClaimSdkPromptSource() throws Exception {
        Path file = tempDir.resolve("official-local-command.jsonl");
        List<String> texts = List.of(
            "<local-command-caveat>Caveat</local-command-caveat>",
            "<command-name>/compact</command-name>\n<command-message>compact</command-message>\n<command-args></command-args>",
            "<local-command-stdout>Compacted</local-command-stdout>");

        String parent = null;
        for (int i = 0; i < texts.size(); i++) {
            UserMessage message = new UserMessage(
                "local-" + i, MessageContent.ofText(texts.get(i)),
                i == 0, false, null, MessageOrigin.USER,
                null, Instant.parse("2026-07-30T08:33:07.385Z"),
                null, null, null, null);
            storage.appendMessageWithParent(file, message, "session-1", "/tmp/project",
                false, null, "main", null, parent);
            parent = message.uuid();
        }

        for (JsonNode line : JsonUtils.readJsonLines(file)) {
            assertFalse(line.has("promptSource"),
                "local command rows are generated by the CLI, not submitted through the SDK prompt channel");
            assertFalse(line.has("origin"),
                "released 2.1.197 local command rows do not carry human prompt provenance");
            assertTrue(line.hasNonNull("promptId"));
        }
    }

    @Test
    void roundtripAssistantMessage() {
        Path file = tempDir.resolve("assistant.jsonl");
        AssistantMessage msg = new AssistantMessage(
                UUID.randomUUID().toString(),
                AssistantContent.of("api-msg-1", List.of(new TextBlock("I can help with that."))),
                false, null, Instant.now()
        );

        storage.appendMessage(file, msg);
        List<Message> messages = storage.readMessages(file);

        assertEquals(1, messages.size());
        assertInstanceOf(AssistantMessage.class, messages.getFirst());
        AssistantMessage restored = (AssistantMessage) messages.getFirst();
        assertEquals(msg.uuid(), restored.uuid());
        assertEquals("assistant", restored.type());
        assertEquals(1, restored.message().content().size());
        assertInstanceOf(TextBlock.class, restored.message().content().getFirst());
        assertEquals("I can help with that.", ((TextBlock) restored.message().content().getFirst()).text());
    }

    @Test
    void writesOfficialAssistantTranscriptEnvelope() throws Exception {
        Path file = tempDir.resolve("official-assistant.jsonl");
        AssistantMessage msg = new AssistantMessage(
            "assistant-uuid",
            AssistantContent.apiResponse(
                "msg_197_text_01", List.of(new TextBlock("OK")),
                new Usage(1_000, 1, 0, 0),
                "claude-sonnet-4-6", "end_turn", null),
            false, null, Instant.parse("2026-07-30T06:00:00Z"));

        storage.appendMessageWithParent(file, msg, "session-1", "/tmp/project",
            false, null, "main", null, "user-uuid");

        JsonNode line = mapper.readTree(Files.readString(file).trim());
        JsonNode message = line.path("message");
        assertEquals("message", message.path("type").asText());
        assertEquals("assistant", message.path("role").asText());
        assertEquals("claude-sonnet-4-6", message.path("model").asText());
        assertEquals("end_turn", message.path("stop_reason").asText());
        assertTrue(message.path("stop_sequence").isNull());
        assertTrue(message.path("stop_details").isNull());
        assertEquals("standard", message.path("usage").path("service_tier").asText());
        assertEquals(0, message.path("usage").path("cache_creation")
            .path("ephemeral_1h_input_tokens").asInt());
        assertTrue(message.path("usage").path("iterations").isArray());
        assertEquals("standard", message.path("usage").path("speed").asText());
        assertFalse(line.has("isApiErrorMessage"),
            "false Java-only defaults must not pollute official assistant rows");

        AssistantMessage restored = assertInstanceOf(
            AssistantMessage.class, storage.readMessages(file).getFirst());
        assertEquals("claude-sonnet-4-6", restored.message().model());
        assertEquals("end_turn", restored.message().stopReason());
    }

    @Test
    void writesOfficial197SkillInvocationMetadataShapes() throws Exception {
        Path file = tempDir.resolve("official-skill-invocation.jsonl");
        Instant timestamp = Instant.parse("2026-07-30T06:00:00Z");
        UserMessage toolResult = new UserMessage(
            "tool-result-uuid",
            MessageContent.ofBlocks(List.of(new ToolResultBlock(
                "toolu_197_skill_probe", List.of(new TextBlock(
                    "Launching skill: wire-skills:wire-probe")), false))),
            false, false,
            Map.of("success", true, "commandName", "wire-skills:wire-probe"),
            MessageOrigin.USER, null, timestamp, null, null, null,
            "assistant-tool-use-uuid", null);
        UserMessage injected = new UserMessage(
            "injected-uuid", MessageContent.ofBlocks(List.of(new TextBlock("skill body"))),
            true, false, null, MessageOrigin.USER, null, timestamp,
            null, null, null, null, "toolu_197_skill_probe");
        AssistantMessage attributed = new AssistantMessage(
            "assistant-result-uuid",
            AssistantContent.apiResponse(
                "msg_197_text_03", List.of(new TextBlock("OK")), Usage.EMPTY,
                "claude-sonnet-4-6", "end_turn", null),
            false, null, timestamp, "wire-skills:wire-probe", "wire-skills");

        storage.appendMessageWithParent(file, toolResult, "session-1", "/tmp/project",
            false, null, "main", null, "assistant-tool-use-uuid");
        storage.appendMessageWithParent(file, injected, "session-1", "/tmp/project",
            false, null, "main", null, "tool-result-uuid");
        storage.appendMessageWithParent(file, attributed, "session-1", "/tmp/project",
            false, null, "main", null, "injected-uuid");

        List<JsonNode> lines = JsonUtils.readJsonLines(file);
        JsonNode resultLine = lines.getFirst();
        JsonNode resultBlock = resultLine.path("message").path("content").get(0);
        assertTrue(resultBlock.path("content").isTextual(),
            "released JSONL uses the scalar form for a single text tool result");
        assertEquals("Launching skill: wire-skills:wire-probe",
            resultBlock.path("content").asText());
        assertEquals("wire-skills:wire-probe",
            resultLine.path("toolUseResult").path("commandName").asText());
        assertEquals("assistant-tool-use-uuid",
            resultLine.path("sourceToolAssistantUUID").asText());

        JsonNode injectedLine = lines.get(1);
        assertTrue(injectedLine.path("isMeta").asBoolean());
        assertEquals("toolu_197_skill_probe", injectedLine.path("sourceToolUseID").asText());
        assertFalse(injectedLine.has("promptSource"),
            "tool-injected Skill bodies are internal meta messages, not typed prompts");
        assertFalse(injectedLine.has("origin"),
            "tool-injected Skill bodies must not be stamped as human input");

        JsonNode assistantLine = lines.get(2);
        assertEquals("wire-skills:wire-probe", assistantLine.path("attributionSkill").asText());
        assertEquals("wire-skills", assistantLine.path("attributionPlugin").asText());

        UserMessage restoredResult = assertInstanceOf(
            UserMessage.class, storage.readMessages(file).getFirst());
        ToolResultBlock restoredBlock = assertInstanceOf(
            ToolResultBlock.class, restoredResult.message().blocks().getFirst());
        assertEquals("Launching skill: wire-skills:wire-probe",
            ((TextBlock) restoredBlock.content().getFirst()).text());
    }

    @Test
    void writesOfficial197McpToolResultAndAssistantAttributionShapes() throws Exception {
        Path file = tempDir.resolve("official-mcp-invocation.jsonl");
        Instant timestamp = Instant.parse("2026-07-31T12:45:19Z");
        var mcpContent = mapper.createArrayNode();
        mcpContent.addObject().put("type", "text").put("text", "echo:WIRE197_RECONNECT");
        UserMessage toolResult = new UserMessage(
            "tool-result-uuid",
            MessageContent.ofBlocks(List.of(new ToolResultBlock(
                "toolu_197_mcp_probe", List.of(new TextBlock(
                    "echo:WIRE197_RECONNECT")), false))),
            false, false, mcpContent,
            MessageOrigin.USER, null, timestamp, null, null, null,
            "assistant-tool-use-uuid", null);
        AssistantMessage attributed = new AssistantMessage(
            "assistant-result-uuid",
            AssistantContent.apiResponse(
                "msg_197_text_02", List.of(new TextBlock("OK")), Usage.EMPTY,
                "claude-sonnet-4-6", "end_turn", null),
            false, null, timestamp, null, null,
            "wire-reconnect", "echo_marker");

        storage.appendMessageWithParent(file, toolResult, "session-1", "/tmp/project",
            false, null, "main", null, "assistant-tool-use-uuid");
        storage.appendMessageWithParent(file, attributed, "session-1", "/tmp/project",
            false, null, "main", null, "tool-result-uuid");

        List<JsonNode> lines = JsonUtils.readJsonLines(file);
        JsonNode resultBlock = lines.getFirst().path("message").path("content").get(0);
        assertTrue(resultBlock.path("content").isArray(),
            "MCP content-block arrays must not use the ordinary scalar text shortcut");
        assertEquals("echo:WIRE197_RECONNECT",
            resultBlock.path("content").get(0).path("text").asText());
        assertEquals("wire-reconnect", lines.get(1).path("attributionMcpServer").asText());
        assertEquals("echo_marker", lines.get(1).path("attributionMcpTool").asText());

        UserMessage restored = assertInstanceOf(
            UserMessage.class, storage.readMessages(file).getFirst());
        Path replayFile = tempDir.resolve("official-mcp-invocation-replayed.jsonl");
        storage.appendMessageWithParent(replayFile, restored, "session-1", "/tmp/project",
            false, null, "main", null, "assistant-tool-use-uuid");
        JsonNode replayedResult = JsonUtils.readJsonLines(replayFile).getFirst()
            .path("message").path("content").get(0);
        assertTrue(replayedResult.path("content").isArray(),
            "resumed MCP results deserialize toolUseResult arrays as List and must remain arrays");
    }

    @Test
    void preservesExplicitSingleTextToolResultBlockArrays() throws Exception {
        Path file = tempDir.resolve("explicit-tool-result-blocks.jsonl");
        UserMessage toolResult = new UserMessage(
            "tool-result-uuid",
            MessageContent.ofBlocks(List.of(new ToolResultBlock(
                "toolu_197_send_message", List.of(new TextBlock("{\"success\":true}")),
                false, false, true))),
            false, false, null, MessageOrigin.USER,
            null, Instant.parse("2026-08-12T20:00:00Z"), null, null, null,
            "assistant-tool-use-uuid", null);

        storage.appendMessageWithParent(file, toolResult, "session-1", "/tmp/project",
            true, "agent-1", "HEAD", null, "assistant-tool-use-uuid");

        JsonNode content = JsonUtils.readJsonLines(file).getFirst()
            .path("message").path("content").get(0).path("content");
        assertTrue(content.isArray(),
            "tools with an explicit block-array mapping must retain it in JSONL");
        assertEquals("{\"success\":true}", content.get(0).path("text").asText());
    }

    @Test
    void structuredJsonArrayDoesNotForceTranscriptContentBlockArray() throws Exception {
        Path file = tempDir.resolve("structured-array-tool-result.jsonl");
        var resources = mapper.createArrayNode();
        resources.addObject().put("name", "wire-list").put("uri", "wire://resource/list");
        UserMessage toolResult = new UserMessage(
            "tool-result-uuid",
            MessageContent.ofBlocks(List.of(new ToolResultBlock(
                "toolu-list", List.of(new TextBlock(resources.toString())), false))),
            false, false, resources, MessageOrigin.USER,
            null, Instant.parse("2026-08-12T20:00:00Z"), null, null, null,
            "assistant-tool-use-uuid", null);

        storage.appendMessageWithParent(file, toolResult, "session-1", "/tmp/project",
            false, null, "HEAD", null, "assistant-tool-use-uuid");

        JsonNode content = JsonUtils.readJsonLines(file).getFirst()
            .path("message").path("content").get(0).path("content");
        assertTrue(content.isTextual());
        assertEquals(resources.toString(), content.asText());
    }

    @Test
    void internalMetaUserMessageDoesNotInheritSdkPromptSource() throws Exception {
        Path file = tempDir.resolve("official-internal-meta.jsonl");
        UserMessage pageImage = new UserMessage(
            "page-image-uuid",
            MessageContent.ofBlocks(List.of(new ImageBlock(
                JsonNodeFactory.instance.objectNode()
                    .put("type", "base64")
                    .put("media_type", "image/jpeg")
                    .put("data", "/9j/2Q==")))),
            true, false, null, null, null,
            Instant.parse("2026-07-30T06:00:00Z"), null, null, null, null, null);

        storage.appendMessageWithParent(file, pageImage, "session-1", "/tmp/project",
            false, null, "main", null, "tool-result-uuid");

        JsonNode line = mapper.readTree(Files.readString(file).trim());
        assertTrue(line.path("isMeta").asBoolean());
        assertFalse(line.has("promptSource"),
            "tool-injected PDF/image messages are internal, not SDK-submitted prompts");
        assertFalse(line.has("origin"));
    }

    @Test
    void syntheticRecoveryAssistantUsesTheOfficial197TranscriptEnvelope() throws Exception {
        Path file = tempDir.resolve("synthetic-recovery.jsonl");
        Usage syntheticUsage = new Usage(
            0, 0, 0, 0,
            Usage.ServerToolUse.ZERO,
            null,
            Usage.CacheCreation.ZERO,
            null,
            null,
            null);
        AssistantMessage sentinel = new AssistantMessage(
            "synthetic-assistant-uuid",
            AssistantContent.apiResponse(
                "synthetic-message-id",
                List.of(new TextBlock("No response requested.")),
                syntheticUsage,
                "<synthetic>",
                "stop_sequence",
                ""));

        storage.appendMessageWithParent(file, sentinel,
            "dddddddd-dddd-4ddd-8ddd-dddddddddddd", tempDir.toString(),
            false, null, "HEAD", "sleepy-stirring-eagle", null,
            "attachment-tail");

        JsonNode message = JsonUtils.readJsonLines(file).getFirst().path("message");
        assertEquals("synthetic-message-id", message.path("id").asText());
        assertEquals("<synthetic>", message.path("model").asText());
        assertEquals("stop_sequence", message.path("stop_reason").asText());
        assertEquals("", message.path("stop_sequence").asText());
        assertTrue(message.path("container").isNull());
        assertTrue(message.path("stop_details").isNull());
        assertTrue(message.path("context_management").isNull());

        JsonNode usage = message.path("usage");
        assertEquals(0, usage.path("input_tokens").asInt());
        assertEquals(0, usage.path("output_tokens").asInt());
        assertTrue(usage.path("service_tier").isNull());
        assertTrue(usage.path("inference_geo").isNull());
        assertTrue(usage.path("iterations").isNull());
        assertTrue(usage.path("speed").isNull());
        assertEquals(0, usage.path("server_tool_use").path("web_search_requests").asInt());
        assertEquals(0, usage.path("server_tool_use").path("web_fetch_requests").asInt());
        assertEquals(0, usage.path("cache_creation").path("ephemeral_1h_input_tokens").asInt());
        assertEquals(0, usage.path("cache_creation").path("ephemeral_5m_input_tokens").asInt());
    }

    @Test
    void roundtripSystemMessage() {
        Path file = tempDir.resolve("system.jsonl");
        SystemMessage msg = new SystemMessage(
                UUID.randomUUID().toString(), "local_command", "info", "Command executed"
        );

        storage.appendMessage(file, msg);
        List<Message> messages = storage.readMessages(file);

        assertEquals(1, messages.size());
        assertInstanceOf(SystemMessage.class, messages.getFirst());
        SystemMessage restored = (SystemMessage) messages.getFirst();
        assertEquals(msg.uuid(), restored.uuid());
        assertEquals("local_command", restored.subtype());
        assertEquals("info", restored.level());
    }

    @Test
    void roundtripProgressMessage() {
        Path file = tempDir.resolve("progress.jsonl");
        ProgressMessage msg = new ProgressMessage(UUID.randomUUID().toString(), "Working...");

        storage.appendMessage(file, msg);
        List<Message> messages = storage.readMessages(file);

        assertEquals(1, messages.size());
        assertInstanceOf(ProgressMessage.class, messages.getFirst());
        assertEquals("Working...", ((ProgressMessage) messages.getFirst()).content());
    }

    @Test
    void roundtripAttachmentMessage() {
        Path file = tempDir.resolve("attachment.jsonl");
        AttachmentMessage msg = new AttachmentMessage(
                UUID.randomUUID().toString(),
                new FileContentAttachment("/tmp/foo.txt", "file content here"));

        storage.appendMessage(file, msg);
        List<Message> messages = storage.readMessages(file);

        assertEquals(1, messages.size());
        assertInstanceOf(AttachmentMessage.class, messages.getFirst());
        assertEquals(new FileContentAttachment("/tmp/foo.txt", "file content here"),
                ((AttachmentMessage) messages.getFirst()).payload());
    }

    @Test
    void writesReleased197AutoModeAttachmentWithoutRuntimeReminderType() throws Exception {
        Path file = tempDir.resolve("auto-mode-attachment.jsonl");
        AttachmentMessage msg = new AttachmentMessage(
            "auto-mode-uuid", new AutoModeReminderAttachment("sparse"));

        storage.appendMessageWithParent(file, msg, "session-1", "/tmp/project",
            false, null, "main", null, null);

        JsonNode attachment = mapper.readTree(Files.readString(file).trim()).get("attachment");
        assertEquals(mapper.readTree("{\"type\":\"auto_mode\"}"), attachment,
            "2.1.197 keeps full/sparse as runtime rendering state but omits it from JSONL");

        AttachmentMessage restored = assertInstanceOf(
            AttachmentMessage.class, storage.readMessages(file).getFirst());
        AutoModeReminderAttachment restoredPayload = assertInstanceOf(
            AutoModeReminderAttachment.class, restored.payload());
        assertEquals("full", restoredPayload.reminderType(),
            "released JSONL without reminderType must remain resumable");
    }

    @Test
    void roundtripHookResultMessage() {
        Path file = tempDir.resolve("hook.jsonl");
        HookResultMessage msg = new HookResultMessage(
                UUID.randomUUID().toString(), "pre_tool_use", "hook output"
        );

        storage.appendMessage(file, msg);
        List<Message> messages = storage.readMessages(file);

        assertEquals(1, messages.size());
        assertInstanceOf(HookResultMessage.class, messages.getFirst());
        HookResultMessage restored = (HookResultMessage) messages.getFirst();
        assertEquals("pre_tool_use", restored.hookName());
    }

    @Test
    void roundtripToolUseSummaryMessage() {
        Path file = tempDir.resolve("toolsummary.jsonl");
        ToolUseSummaryMessage msg = new ToolUseSummaryMessage(
                UUID.randomUUID().toString(), "Ran ls command", List.of("tu-1", "tu-2")
        );

        storage.appendMessage(file, msg);
        List<Message> messages = storage.readMessages(file);

        assertEquals(1, messages.size());
        assertInstanceOf(ToolUseSummaryMessage.class, messages.getFirst());
        ToolUseSummaryMessage restored = (ToolUseSummaryMessage) messages.getFirst();
        assertEquals("Ran ls command", restored.summary());
        assertEquals(List.of("tu-1", "tu-2"), restored.precedingToolUseIds());
    }

    @Test
    void roundtripTombstoneMessage() {
        Path file = tempDir.resolve("tombstone.jsonl");
        TombstoneMessage msg = new TombstoneMessage(UUID.randomUUID().toString(), "replaced-uuid-1");

        storage.appendMessage(file, msg);
        List<Message> messages = storage.readMessages(file);

        assertEquals(1, messages.size());
        assertInstanceOf(TombstoneMessage.class, messages.getFirst());
        assertEquals("replaced-uuid-1", ((TombstoneMessage) messages.getFirst()).replacedUuid());
    }

    @Test
    void roundtripGroupedToolUseMessage() {
        Path file = tempDir.resolve("grouped.jsonl");
        GroupedToolUseMessage msg = new GroupedToolUseMessage(
                UUID.randomUUID().toString(),
                List.of("tu-1", "tu-2"),
                List.of("Bash", "FileRead")
        );

        storage.appendMessage(file, msg);
        List<Message> messages = storage.readMessages(file);

        assertEquals(1, messages.size());
        assertInstanceOf(GroupedToolUseMessage.class, messages.getFirst());
        GroupedToolUseMessage restored = (GroupedToolUseMessage) messages.getFirst();
        assertEquals(List.of("tu-1", "tu-2"), restored.toolUseIds());
        assertEquals(List.of("Bash", "FileRead"), restored.toolNames());
    }

    // ---- ContentBlock subtype tests ----

    @Test
    void roundtripAssistantWithToolUseBlock() {
        Path file = tempDir.resolve("tooluse.jsonl");
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("command", "ls -la");

        AssistantMessage msg = new AssistantMessage(
                UUID.randomUUID().toString(),
                AssistantContent.of("api-1", List.of(
                        new TextBlock("Let me check that."),
                        new ToolUseBlock("tu-123", "Bash", input)
                )),
                false, null, Instant.now()
        );

        storage.appendMessage(file, msg);
        List<Message> messages = storage.readMessages(file);

        assertEquals(1, messages.size());
        AssistantMessage restored = (AssistantMessage) messages.getFirst();
        assertEquals(2, restored.message().content().size());
        assertInstanceOf(TextBlock.class, restored.message().content().getFirst());
        assertInstanceOf(ToolUseBlock.class, restored.message().content().get(1));
        ToolUseBlock toolUse = (ToolUseBlock) restored.message().content().get(1);
        assertEquals("tu-123", toolUse.id());
        assertEquals("Bash", toolUse.name());
        assertEquals("ls -la", toolUse.input().get("command").asText());
    }

    @Test
    void roundtripAssistantWithThinkingBlock() {
        Path file = tempDir.resolve("thinking.jsonl");
        AssistantMessage msg = new AssistantMessage(
                UUID.randomUUID().toString(),
                AssistantContent.of(List.of(
                        new ThinkingBlock("Let me think about this..."),
                        new TextBlock("Here's my answer.")
                )),
                false, null, Instant.now()
        );

        storage.appendMessage(file, msg);
        List<Message> messages = storage.readMessages(file);

        assertEquals(1, messages.size());
        AssistantMessage restored = (AssistantMessage) messages.getFirst();
        assertEquals(2, restored.message().content().size());
        assertInstanceOf(ThinkingBlock.class, restored.message().content().getFirst());
        assertInstanceOf(TextBlock.class, restored.message().content().get(1));
    }

    @Test
    void roundtripToolResultBlock() {
        Path file = tempDir.resolve("toolresult.jsonl");
        UserMessage msg = new UserMessage(
                UUID.randomUUID().toString(),
                MessageContent.ofToolResult("tu-123",
                        List.of(new TextBlock("file contents here")), false),
                false, false, null, MessageOrigin.TOOL_RESULT,
                null, Instant.now(), null, null, null
        );

        storage.appendMessage(file, msg);
        List<Message> messages = storage.readMessages(file);

        assertEquals(1, messages.size());
        UserMessage restored = (UserMessage) messages.getFirst();
        assertFalse(restored.message().isText());
        assertEquals(1, restored.message().blocks().size());
        assertInstanceOf(ToolResultBlock.class, restored.message().blocks().getFirst());
        ToolResultBlock result = (ToolResultBlock) restored.message().blocks().getFirst();
        assertEquals("tu-123", result.toolUseId());
        assertFalse(result.isError());
    }

    // ---- Multiple messages ----

    @Test
    void roundtripMultipleMessages() {
        Path file = tempDir.resolve("multi.jsonl");
        UserMessage user = new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("Hi"));
        AssistantMessage assistant = new AssistantMessage(
                UUID.randomUUID().toString(),
                AssistantContent.of(List.of(new TextBlock("Hello!")))
        );
        SystemMessage system = new SystemMessage(
                UUID.randomUUID().toString(), "info", "info", "Session started"
        );

        storage.appendMessage(file, user);
        storage.appendMessage(file, assistant);
        storage.appendMessage(file, system);

        List<Message> messages = storage.readMessages(file);
        assertEquals(3, messages.size());
        assertInstanceOf(UserMessage.class, messages.getFirst());
        assertInstanceOf(AssistantMessage.class, messages.get(1));
        assertInstanceOf(SystemMessage.class, messages.get(2));
    }

    // ---- Malformed line handling ----

    @Test
    void malformedLinesAreSkipped() throws IOException {
        Path file = tempDir.resolve("malformed.jsonl");
        UserMessage valid = new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("Valid"));
        String validJson = mapper.writeValueAsString(valid);

        String content = validJson + "\n"
                + "this is not json\n"
                + "{\"broken\": true\n"  // missing closing brace
                + validJson + "\n";
        Files.writeString(file, content, StandardCharsets.UTF_8);

        List<Message> messages = storage.readMessages(file);
        assertEquals(2, messages.size());
        assertInstanceOf(UserMessage.class, messages.getFirst());
        assertInstanceOf(UserMessage.class, messages.get(1));
    }

    @Test
    void emptyLinesAreSkipped() throws IOException {
        Path file = tempDir.resolve("empty-lines.jsonl");
        UserMessage msg = new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("Test"));
        String json = mapper.writeValueAsString(msg);

        Files.writeString(file, "\n" + json + "\n\n\n" + json + "\n", StandardCharsets.UTF_8);

        List<Message> messages = storage.readMessages(file);
        assertEquals(2, messages.size());
    }

    @Test
    void readFromNonExistentFileReturnsEmptyList() {
        Path file = tempDir.resolve("does-not-exist.jsonl");
        List<Message> messages = storage.readMessages(file);
        assertTrue(messages.isEmpty());
    }

    // ---- Timestamp stamping (regression guard for /branch history clone) ----



    @Test
    void appendMessage_preservesOriginalTimestampWhenMessageAlreadyHasOne() {
        Path file = tempDir.resolve("preserved-ts.jsonl");
        Instant original = Instant.parse("2020-01-01T00:00:00Z");
        UserMessage historical = new UserMessage(
                "hist-uuid", MessageContent.ofText("old message"),
                false, false, null, MessageOrigin.USER,
                null, original, null, null, null
        );

        storage.appendMessage(file, historical);

        List<Message> reloaded = storage.readMessages(file);
        assertEquals(1, reloaded.size());
        assertEquals(Optional.of(original), reloaded.getFirst().timestamp(),
            "cloning a historical message (e.g. /branch) must not overwrite its original timestamp");
    }

    @Test
    void appendMessage_stampsNowWhenMessageHasNoTimestamp() {
        Path file = tempDir.resolve("fresh-ts.jsonl");
        UserMessage fresh = new UserMessage(
                "fresh-uuid", MessageContent.ofText("new message"),
                false, false, null, MessageOrigin.USER,
                null, null, null, null, null
        );
        Instant before = Instant.now();

        storage.appendMessage(file, fresh);

        List<Message> reloaded = storage.readMessages(file);
        Instant stamped = reloaded.getFirst().timestamp().orElseThrow();
        assertFalse(stamped.isBefore(before.minusSeconds(5)),
            "a message with no timestamp of its own should be stamped ~now on append");
    }

    // ---- JSONL format verification ----

    @Test
    void appendedMessagesAreOnePerLine() throws IOException {
        Path file = tempDir.resolve("format.jsonl");
        storage.appendMessage(file, new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("A")));
        storage.appendMessage(file, new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("B")));

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        // Each line should be valid JSON (no embedded newlines)
        for (String line : lines) {
            assertFalse(line.isEmpty());
            assertDoesNotThrow(() -> mapper.readTree(line));
        }
    }

    // ---- ObjectMapper configuration tests ----

    @Test
    void objectMapperSkipsNullFields() throws Exception {
        UserMessage msg = new UserMessage(
                "test-uuid", MessageContent.ofText("hi"),
                false, false, null, MessageOrigin.USER,
                null, null, null, null, null
        );
        String json = mapper.writeValueAsString(msg);
        // null fields like parentUuid and timestamp should not appear
        assertFalse(Strings.CS.contains(json, "parentUuidValue"));
        assertFalse(Strings.CS.contains(json, "timestampValue"));
    }

    @Test
    void objectMapperIgnoresUnknownProperties() throws Exception {
        // JSON with an extra unknown field should still deserialize
        String json = "{\"type\":\"system\",\"uuid\":\"u1\",\"subtype\":\"info\","
                + "\"level\":\"info\",\"content\":\"test\",\"unknownField\":\"ignored\"}";
        Message msg = mapper.readValue(json, Message.class);
        assertInstanceOf(SystemMessage.class, msg);
        assertEquals("u1", msg.uuid());
    }

    // ---- Metadata-entry skip list ----


    // (mode / permission-mode / file-history-snapshot / agent-name / …) into
// the same JSONL. isMetadataEntry must strip every one before Jackson
    // sees the line — otherwise resume floods the console with "not a valid
    // subtype" warnings and (worse) loses ordering information around
// dropped lines. See  branches ~1159-1215.

    @Test
    void isMetadataEntrySkipsAllTsMetadataTypes() {
        String[] metadataTypes = {
            "custom-title", "ai-title", "last-prompt", "summary", "tag",
            "task-summary", "agent-name", "agent-color", "agent-setting",
            "pr-link", "file-history-snapshot", "attribution-snapshot",
            "speculation-accept", "mode", "permission-mode", "worktree-state",
            "content-replacement", "marble-origami-commit",
            "marble-origami-snapshot", "queue-operation",
            "parent-session"
        };
        for (String t : metadataTypes) {
            String line = "{\"type\":\"" + t + "\",\"sessionId\":\"s\"}";
            assertTrue(SessionStorage.isMetadataEntry(line),
                "expected metadata type to be filtered: " + t);
        }
    }

    @Test
    void isMetadataEntryKeepsTranscriptMessageTypes() {
        // Real Message subtypes must reach Jackson so they deserialize.
        for (String t : new String[]{"user", "assistant", "system", "attachment",
                "progress", "hook_result", "tool_use_summary", "tombstone",
                "grouped_tool_use"}) {
            String line = "{\"type\":\"" + t + "\",\"uuid\":\"u1\"}";
            assertFalse(SessionStorage.isMetadataEntry(line),
                "expected transcript type to be kept: " + t);
        }
    }

    @Test
    void readMessagesSilentlySkipsMixedTsMetadataJsonl() throws Exception {

        // with 2 real messages. Java must load exactly the 2 messages and
        // emit zero warnings for the metadata (all filtered pre-parse).
        Path file = tempDir.resolve("mixed.jsonl");
        List<String> lines = List.of(
            "{\"type\":\"last-prompt\",\"lastPrompt\":\"x\",\"sessionId\":\"s\"}",
            "{\"type\":\"mode\",\"mode\":\"normal\",\"sessionId\":\"s\"}",
            "{\"type\":\"user\",\"uuid\":\"u1\",\"message\":{\"role\":\"user\",\"content\":\"hi\"}}",
            "{\"type\":\"permission-mode\",\"permissionMode\":\"bypassPermissions\",\"sessionId\":\"s\"}",
            "{\"type\":\"file-history-snapshot\",\"messageId\":\"m\",\"snapshot\":{}}",
            "{\"type\":\"parent-session\",\"parentSessionId\":\"p\",\"relation\":\"clear\",\"sessionId\":\"s\"}",
            "{\"type\":\"system\",\"uuid\":\"s1\",\"subtype\":\"info\",\"level\":\"info\",\"content\":\"ok\"}"
        );
        Files.write(file, lines);

        List<Message> loaded = storage.readMessages(file);
        assertEquals(2, loaded.size(),
            "should load exactly the 2 transcript messages and skip 5 metadata entries");
        assertEquals("u1", loaded.getFirst().uuid());
        assertEquals("s1", loaded.get(1).uuid());
    }

    // ---- worktree-state ----

    @Test
    void worktreeState_noEntry_scanReturnsNull() {
        Path file = tempDir.resolve("no-worktree.jsonl");
        assertNull(storage.scanWorktreeState(file));
    }

    @Test
    void worktreeState_roundtrip_writeThenRead() {
        Path file = tempDir.resolve("worktree.jsonl");
        ObjectNode session = mapper.createObjectNode();
        session.put("originalCwd", "/repo");
        session.put("worktreePath", "/repo/.claude/worktrees/feature-x");
        session.put("worktreeName", "feature-x");
        session.put("worktreeBranch", "worktree-feature-x");

        storage.appendWorktreeState(file, "sess-1", session);

        SessionStorage.WorktreeStateEntry entry = storage.scanWorktreeState(file);
        assertNotNull(entry);
        assertEquals("sess-1", entry.sessionId());
        assertNotNull(entry.worktreeSessionJson());
        assertEquals("/repo", entry.worktreeSessionJson().get("originalCwd").asText());
        assertEquals("worktree-feature-x", entry.worktreeSessionJson().get("worktreeBranch").asText());
    }

    @Test
    void worktreeState_nullEntry_recordsExit() {
        Path file = tempDir.resolve("worktree-exit.jsonl");
        ObjectNode session = mapper.createObjectNode();
        session.put("originalCwd", "/repo");
        storage.appendWorktreeState(file, "sess-1", session);

        storage.appendWorktreeState(file, "sess-1", null);

        SessionStorage.WorktreeStateEntry entry = storage.scanWorktreeState(file);
        assertNotNull(entry, "an exit entry still exists — it's the null worktreeSession that signals exit");
        assertNull(entry.worktreeSessionJson());
    }

    @Test
    void worktreeState_lastWins_acrossMultipleEntries() {
        Path file = tempDir.resolve("worktree-multi.jsonl");
        ObjectNode first = mapper.createObjectNode();
        first.put("worktreeName", "first");
        storage.appendWorktreeState(file, "sess-1", first);

        ObjectNode second = mapper.createObjectNode();
        second.put("worktreeName", "second");
        storage.appendWorktreeState(file, "sess-1", second);

        SessionStorage.WorktreeStateEntry entry = storage.scanWorktreeState(file);
        assertEquals("second", entry.worktreeSessionJson().get("worktreeName").asText());
    }

    @Test
    void worktreeState_isFilteredFromReadMessages() throws Exception {
        Path file = tempDir.resolve("worktree-filtered.jsonl");
        ObjectNode session = mapper.createObjectNode();
        session.put("worktreeName", "x");
        storage.appendWorktreeState(file, "sess-1", session);
        assertTrue(storage.readMessages(file).isEmpty(),
            "worktree-state entries must not be deserialized as transcript messages");
    }

    // ---- file-history-snapshot (/rewind "Restore code") ----

    @Test
    void scanFileHistorySnapshots_readsAppendedEntries_inOrder() {
        Path file = tempDir.resolve("fh.jsonl");
        ObjectNode snap1 = mapper.createObjectNode();
        snap1.put("messageId", "msg-1");
        storage.insertFileHistorySnapshot(file, "msg-1", snap1, false);

        ObjectNode snap2 = mapper.createObjectNode();
        snap2.put("messageId", "msg-2");
        storage.insertFileHistorySnapshot(file, "msg-2", snap2, false);

        List<SessionStorage.FileHistorySnapshotEntry> entries = storage.scanFileHistorySnapshots(file);
        assertEquals(2, entries.size());
        assertEquals("msg-1", entries.getFirst().messageId());
        assertFalse(entries.getFirst().isSnapshotUpdate());
        assertEquals("msg-2", entries.get(1).messageId());
        assertEquals("msg-1", entries.getFirst().snapshotJson().get("messageId").asText());
    }

    @Test
    void scanFileHistorySnapshots_capturesIsSnapshotUpdateFlag() {
        Path file = tempDir.resolve("fh-update.jsonl");
        ObjectNode snap = mapper.createObjectNode();
        storage.insertFileHistorySnapshot(file, "msg-1", snap, true);

        List<SessionStorage.FileHistorySnapshotEntry> entries = storage.scanFileHistorySnapshots(file);
        assertEquals(1, entries.size());
        assertTrue(entries.getFirst().isSnapshotUpdate());
    }

    @Test
    void scanFileHistorySnapshots_missingFile_returnsEmptyList() {
        assertTrue(storage.scanFileHistorySnapshots(tempDir.resolve("does-not-exist.jsonl")).isEmpty());
    }

    @Test
    void readMessages_skipsFileHistorySnapshotLines() throws Exception {
        Path file = tempDir.resolve("fh-filtered.jsonl");
        ObjectNode snap = mapper.createObjectNode();
        snap.put("messageId", "msg-1");
        storage.insertFileHistorySnapshot(file, "msg-1", snap, false);

        assertTrue(storage.readMessages(file).isEmpty(),
            "file-history-snapshot entries must not be deserialized as transcript messages");
    }

    @Test
    void readContentReplacements_readsOnlyToolResultRecords() throws Exception {
        Path file = tempDir.resolve("replacements.jsonl");
        ObjectNode entry = mapper.createObjectNode();
        entry.put("type", "content-replacement");
        entry.put("sessionId", "session-1");
        entry.putArray("replacements")
            .addObject().put("kind", "tool-result").put("toolUseId", "tool-1")
            .put("replacement", "preview-1");
        Files.writeString(file, mapper.writeValueAsString(entry) + "\n", StandardCharsets.UTF_8);

        List<ToolResultBudget.Replacement> records = storage.readContentReplacements(file);
        assertEquals(List.of(new ToolResultBudget.Replacement("tool-1", "preview-1")), records);
        assertTrue(storage.readMessages(file).isEmpty());
    }

    @Test
    void agentMetadata_roundTripsThroughTranscriptSiblingSidecar() throws Exception {
        Path transcript = tempDir.resolve("subagents/agent-a123.jsonl");

        storage.writeAgentMetadata(transcript,
            new AgentMetadata("Explore", "/tmp/agent-worktree", "inspect storage", true));

        Path metadata = tempDir.resolve("subagents/agent-a123.meta.json");
        assertEquals(
            "{\"agentType\":\"Explore\",\"worktreePath\":\"/tmp/agent-worktree\",\"description\":\"inspect storage\",\"stoppedByUser\":true}",
            Files.readString(metadata));
        assertEquals(
            new AgentMetadata("Explore", "/tmp/agent-worktree", "inspect storage", true),
            storage.readAgentMetadata(transcript).orElseThrow());
    }

    @Test
    void agentMetadata_omitsOptionalFieldsAndMissingSidecarReturnsEmpty() throws Exception {
        Path transcript = tempDir.resolve("subagents/agent-a123.jsonl");

        assertTrue(storage.readAgentMetadata(transcript).isEmpty());
        storage.writeAgentMetadata(transcript,
            new AgentMetadata("general-purpose", null, null));

        assertEquals("{\"agentType\":\"general-purpose\"}",
            Files.readString(tempDir.resolve("subagents/agent-a123.meta.json")));
    }

    @Test
    void agentMetadata_roundTripsDepthSnapshot() throws Exception {
        Path transcript = tempDir.resolve("subagents/agent-depth.jsonl");
        AgentMetadata metadata = new AgentMetadata(
            "general-purpose", null, "nested", false, 2, 5);

        storage.writeAgentMetadata(transcript, metadata);

        assertEquals(metadata, storage.readAgentMetadata(transcript).orElseThrow());
        assertEquals(
            "{\"agentType\":\"general-purpose\",\"description\":\"nested\",\"spawnDepth\":2,\"subagentMaxDepth\":5}",
            Files.readString(tempDir.resolve("subagents/agent-depth.meta.json")));
    }

    @Test
    void agentMetadata_corruptSidecarIsNotSilentlyTreatedAsMissing() throws Exception {
        Path transcript = tempDir.resolve("subagents/agent-a123.jsonl");
        Files.createDirectories(transcript.getParent());
        Files.writeString(tempDir.resolve("subagents/agent-a123.meta.json"), "not-json");

        assertThrows(IllegalArgumentException.class,
            () -> storage.readAgentMetadata(transcript));
    }
}
