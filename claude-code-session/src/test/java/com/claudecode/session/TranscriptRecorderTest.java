package com.claudecode.session;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.CompactMetadata;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageFactory;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.PreservedMessages;
import com.claudecode.core.message.PreservedSegment;
import com.claudecode.core.message.ProgressMessage;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.engine.ToolResultBudget;
import com.claudecode.core.engine.RequestMessageNormalizer;
import com.claudecode.core.plan.PlanSlugRegistry;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TranscriptRecorder fire-and-forget async writes.
 */
class TranscriptRecorderTest {

    private static int indexOfType(List<JsonNode> lines, String type) {
        for (int i = 0; i < lines.size(); i++) {
            if (type.equals(lines.get(i).path("type").asText())) return i;
        }
        return -1;
    }

    @TempDir
    Path tempDir;

    private SessionManager sessionManager;
    private TranscriptRecorder recorder;
    private SessionStorage storage;

    @BeforeEach
    void setUp() {
        sessionManager = new SessionManager(tempDir, "/test/project");
        storage = new SessionStorage();
        recorder = new TranscriptRecorder(sessionManager, storage);
    }

    @Test
    void recordTranscriptWritesAsynchronously() throws InterruptedException {
        String sessionId = sessionManager.createSession();
        UserMessage msg = new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("Async test"));

        recorder.recordTranscript(sessionId, msg);

        // Give the virtual thread time to complete
        Thread.sleep(500);

        Path sessionFile = sessionManager.getSessionFile(sessionId);
        List<Message> messages = storage.readMessages(sessionFile);
        assertEquals(1, messages.size());
        assertEquals(msg.uuid(), messages.getFirst().uuid());
    }

    @Test
    void teamInfoIsStampedOnEveryRecordedMessageAndFrozenBeforeAsyncWrite() throws Exception {
        String sessionId = sessionManager.createSession();
        var current = new AtomicReference<>(
            new TeamInfo("search-team", "researcher"));
        recorder.setTeamInfoResolver(_ -> current.get());

        recorder.record(sessionId,
            new UserMessage("team-user", MessageContent.ofText("investigate")));
        current.set(new TeamInfo("other-team", "reviewer"));
        assertTrue(recorder.awaitPendingWrites(sessionId, 2_000));

        JsonNode row = JsonUtils.readJsonLines(sessionManager.getSessionFile(sessionId)).getFirst();
        assertEquals("search-team", row.path("teamName").asText());
        assertEquals("researcher", row.path("agentName").asText());
    }

    @Test
    void blankTeamInfoFieldsAreOmittedFromTranscriptRows() throws Exception {
        String sessionId = sessionManager.createSession();
        recorder.setTeamInfoResolver(_ -> new TeamInfo("  ", null));

        recorder.record(sessionId,
            new UserMessage("plain-user", MessageContent.ofText("ordinary prompt")));
        assertTrue(recorder.awaitPendingWrites(sessionId, 2_000));

        JsonNode row = JsonUtils.readJsonLines(sessionManager.getSessionFile(sessionId)).getFirst();
        assertFalse(row.has("teamName"));
        assertFalse(row.has("agentName"));
    }

    @Test
    void tombstoneRemovalReseedsTheNextPersistedParent() throws Exception {
        String sessionId = sessionManager.createSession();
        UserMessage first = new UserMessage("first", MessageContent.ofText("one"));
        UserMessage abandoned = new UserMessage("abandoned", MessageContent.ofText("partial"));
        UserMessage retry = new UserMessage("retry", MessageContent.ofText("retry"));

        recorder.record(sessionId, first);
        recorder.record(sessionId, abandoned);
        recorder.remove(sessionId, abandoned.uuid());
        recorder.record(sessionId, retry);
        assertTrue(recorder.awaitPendingWrites(sessionId, 2_000));

        Path file = sessionManager.getSessionFile(sessionId);
        List<JsonNode> rows = JsonUtils.readJsonLines(file);
        assertEquals(List.of("first", "retry"), rows.stream()
            .filter(row -> row.hasNonNull("uuid"))
            .map(row -> row.path("uuid").asText()).toList());
        JsonNode retryRow = rows.stream()
            .filter(row -> Strings.CS.equals("retry", row.path("uuid").asText()))
            .findFirst().orElseThrow();
        assertEquals("first", retryRow.path("parentUuid").asText(),
            "the retry must not chain from the row the tombstone deleted");
    }

    @Test
    void conversationRewindForksTheNextPromptFromTheRetainedTail() throws Exception {
        String sessionId = sessionManager.createSession();
        UserMessage first = new UserMessage("first", MessageContent.ofText("one"));
        AssistantMessage firstAnswer = new AssistantMessage(
            "first-answer", AssistantContent.of(List.of(new TextBlock("answer one"))));
        UserMessage abandoned = new UserMessage("abandoned", MessageContent.ofText("two"));
        AssistantMessage abandonedAnswer = new AssistantMessage(
            "abandoned-answer", AssistantContent.of(List.of(new TextBlock("answer two"))));
        UserMessage retry = new UserMessage("retry", MessageContent.ofText("retry two"));

        recorder.record(sessionId, first);
        recorder.record(sessionId, firstAnswer);
        recorder.record(sessionId, abandoned);
        recorder.record(sessionId, abandonedAnswer);
        recorder.rewindConversation(sessionId, List.of(first, firstAnswer));
        recorder.record(sessionId, retry);
        assertTrue(recorder.awaitPendingWrites(sessionId, 2_000));

        JsonNode retryRow = JsonUtils.readJsonLines(sessionManager.getSessionFile(sessionId)).stream()
            .filter(row -> Strings.CS.equals("retry", row.path("uuid").asText()))
            .findFirst().orElseThrow();
        assertEquals("first-answer", retryRow.path("parentUuid").asText(),
            "the first post-rewind prompt must fork from the retained conversation tail");
    }

    @Test
    void forkContextReferenceIsSerializedBeforeTheSidechainPrompt() throws Exception {
        String sessionId = sessionManager.createSession();
        String agentId = "a1234567890abcdef";
        TranscriptRecorder sidechain = new TranscriptRecorder(
            sessionManager, storage, "/test/project", true, agentId);

        sidechain.recordForkContextRef(
            sessionId, agentId, sessionId, "parent-last", 7);
        sidechain.record(sessionId,
            new UserMessage("fork-prompt", MessageContent.ofText("do the forked work")));
        assertTrue(sidechain.awaitPendingWrites(sessionId, 2_000));

        List<JsonNode> rows = JsonUtils.readJsonLines(
            sessionManager.getAgentTranscriptPath(sessionId, agentId));
        assertEquals(List.of("fork-context-ref", "user"), rows.stream()
            .map(row -> row.path("type").asText()).toList());
        JsonNode reference = rows.getFirst();
        assertEquals(agentId, reference.path("agentId").asText());
        assertEquals(sessionId, reference.path("parentSessionId").asText());
        assertEquals("parent-last", reference.path("parentLastUuid").asText());
        assertEquals(7, reference.path("contextLength").asInt());
    }

    @Test
    void incrementalRecordingHidesReplWrapperAndPersistsPromotedNativeCalls() throws Exception {
        String sessionId = sessionManager.createSession();
        AssistantMessage replWrapper = new AssistantMessage(
            "repl-wrapper", AssistantContent.of("api-wrapper", List.of(
                new ToolUseBlock("repl-1", "REPL", JsonUtils.getMapper().createObjectNode())),
                Usage.EMPTY), false, null, Instant.parse("2026-08-25T00:00:00Z"),
            null, null, null, null, null, null, null, null, null, null);
        AssistantMessage nestedAssistant = new AssistantMessage(
            "nested-assistant", AssistantContent.of("api-native", List.of(
                new ToolUseBlock("native-1", "Bash", JsonUtils.getMapper().createObjectNode())),
                Usage.EMPTY), false, null, Instant.parse("2026-08-25T00:00:01Z"),
            null, null, null, null, null, null, Boolean.TRUE, null, null, null);
        UserMessage replResult = new UserMessage(
            "repl-result", MessageContent.ofBlocks(List.of(
                new ToolResultBlock("repl-1", List.of(new TextBlock("hidden")), false))),
            false, false, null, MessageOrigin.USER, null,
            Instant.parse("2026-08-25T00:00:02Z"), null, null, null, null, null,
            null, null, null);
        UserMessage nativeResult = new UserMessage(
            "native-result", MessageContent.ofBlocks(List.of(
                new ToolResultBlock("native-1", List.of(new TextBlock("visible")), false))),
            false, false, null, MessageOrigin.USER, null,
            Instant.parse("2026-08-25T00:00:03Z"), null, null, null, null, null,
            Boolean.TRUE, null, null);

        recorder.record(sessionId, replWrapper);
        recorder.record(sessionId, nestedAssistant);
        recorder.record(sessionId, replResult);
        recorder.record(sessionId, nativeResult);
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));

        List<JsonNode> rows = JsonUtils.readJsonLines(sessionManager.getSessionFile(sessionId));
        assertEquals(List.of("nested-assistant", "native-result"), rows.stream()
            .filter(row -> row.hasNonNull("uuid"))
            .map(row -> row.path("uuid").asText())
            .toList());
        assertFalse(rows.getFirst().has("isVirtual"));
        assertFalse(rows.get(1).has("isVirtual"));
        assertEquals("nested-assistant", rows.get(1).path("parentUuid").asText());
        assertFalse(Strings.CS.contains(
            Files.readString(sessionManager.getSessionFile(sessionId)), "repl-1"));

        List<Message> resumed = storage.loadTranscriptFromFile(
            sessionManager.getSessionFile(sessionId)).messages();
        JsonNode wire = JsonUtils.getMapper().valueToTree(
            RequestMessageNormalizer.normalizeForApi(resumed, false, false));
        assertEquals(List.of("assistant", "user"), List.of(
            wire.get(0).path("role").asText(), wire.get(1).path("role").asText()));
        assertEquals("native-1", wire.get(0).path("content").get(0).path("id").asText());
        assertEquals("native-1",
            wire.get(1).path("content").get(0).path("tool_use_id").asText());
    }

    @Test
    void recordTranscriptDoesNotThrowOnError() {
        // Use a non-existent session with a path that can't be created
        // The recorder should log the error but not throw
        assertDoesNotThrow(() -> {
            // Record to a session whose directory doesn't exist yet — this should still work
            // because appendMessage creates directories
            String sessionId = "nonexistent-" + UUID.randomUUID();
            UserMessage msg = new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("test"));
            recorder.recordTranscript(sessionId, msg);
            // Give time for async write
            Thread.sleep(500);
        });
    }

    @Test
    void releaseSessionStateClearsOnlyTheTargetTranscriptCaches() throws Exception {
        String firstSession = sessionManager.createSession();
        String secondSession = sessionManager.createSession();
        recorder.recordTranscript(firstSession, new UserMessage(
            UUID.randomUUID().toString(), MessageContent.ofText("first")));
        recorder.recordTranscript(secondSession, new UserMessage(
            UUID.randomUUID().toString(), MessageContent.ofText("second")));
        recorder.cacheLastPrompt(firstSession, "first prompt");
        recorder.cacheLastPrompt(secondSession, "second prompt");
        recorder.recordPromptStart(firstSession, "typed");
        recorder.recordPromptStart(secondSession, "typed");
        assertTrue(recorder.awaitPendingWrites(firstSession, 5_000));
        assertTrue(recorder.awaitPendingWrites(secondSession, 5_000));
        assertTrue(recorder.hasCachedStateForTests(firstSession));
        assertTrue(recorder.hasCachedStateForTests(secondSession));

        assertTrue(recorder.releaseSessionState(firstSession, 5_000));

        assertFalse(recorder.hasCachedStateForTests(firstSession));
        assertTrue(recorder.hasCachedStateForTests(secondSession),
            "leaving one session must not disturb another active transcript");
    }

    @Test
    void cachedSessionNameDoesNotCreateFreshMetadataOnlyTranscript() throws Exception {
        String sessionId = sessionManager.createSession();
        Path sessionFile = sessionManager.getSessionFile(sessionId);

        recorder.cacheSessionTitle("Named session");
        recorder.activateSessionMetadata(sessionId, false);

        assertFalse(Files.exists(sessionFile));
        assertTrue(recorder.releaseSessionState(sessionId, 5_000));
        assertFalse(Files.exists(sessionFile));
    }

    @Test
    void progressDoesNotMaterializeANamedFreshTranscript() throws Exception {
        String sessionId = sessionManager.createSession();
        Path sessionFile = sessionManager.getSessionFile(sessionId);
        recorder.cacheSessionTitle("Named session");
        recorder.activateSessionMetadata(sessionId, false);

        recorder.record(sessionId, new ProgressMessage("progress-1", "tick"));

        assertTrue(recorder.awaitPendingWrites(sessionId, 1_000));
        assertFalse(Files.exists(sessionFile));
    }

    @Test
    void firstMaterializationWritesNameAndAgentSettingBeforeTurnMetadata() throws Exception {
        String sessionId = sessionManager.createSession();

        recorder.cacheSessionTitle("Named session");
        recorder.cacheAgentSetting("reviewer");
        recorder.activateSessionMetadata(sessionId, false);
        recorder.prepareSessionMaterialization(sessionId);
        recorder.recordMode(sessionId, "normal");
        recorder.recordQueueOperation(sessionId, "enqueue", "hello");
        recorder.recordTranscript(sessionId,
            new UserMessage("named-user", MessageContent.ofText("hello")));
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));

        List<JsonNode> lines = JsonUtils.readJsonLines(
            sessionManager.getSessionFile(sessionId));
        assertEquals(List.of(
            "custom-title", "agent-name", "agent-setting", "mode",
            "queue-operation", "user"),
            lines.stream().map(line -> line.path("type").asText()).toList());
        assertEquals("Named session", lines.getFirst().path("customTitle").asText());
        assertEquals("Named session", lines.get(1).path("agentName").asText());
        assertEquals("reviewer", lines.get(2).path("agentSetting").asText());
    }

    @Test
    void resumedSessionPersistsCliNameWithoutWaitingForANewMessage() throws Exception {
        String sessionId = sessionManager.createSession();
        Path sessionFile = sessionManager.getSessionFile(sessionId);
        storage.appendMessage(sessionFile,
            new UserMessage("existing-user", MessageContent.ofText("before")));

        recorder.cacheSessionTitle("CLI name");
        recorder.activateSessionMetadata(sessionId, true);
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));

        List<JsonNode> lines = JsonUtils.readJsonLines(sessionFile);
        assertEquals("custom-title", lines.get(lines.size() - 2).path("type").asText());
        assertEquals("CLI name", lines.get(lines.size() - 2).path("customTitle").asText());
        assertEquals("agent-name", lines.getLast().path("type").asText());
        assertEquals("CLI name", lines.getLast().path("agentName").asText());
    }

    @Test
    void cliNameWinsOverRestoredMetadataAndReleasePreventsLeakage() throws Exception {
        String resumedSession = sessionManager.createSession();
        Path resumedFile = sessionManager.getSessionFile(resumedSession);
        ObjectNode oldTitle = JsonUtils.getMapper().createObjectNode();
        oldTitle.put("type", "custom-title");
        oldTitle.put("customTitle", "Old title");
        oldTitle.put("sessionId", resumedSession);
        storage.appendCustomEntry(resumedFile, oldTitle);
        ObjectNode oldAgentName = JsonUtils.getMapper().createObjectNode();
        oldAgentName.put("type", "agent-name");
        oldAgentName.put("agentName", "Old agent");
        oldAgentName.put("sessionId", resumedSession);
        storage.appendCustomEntry(resumedFile, oldAgentName);

        recorder.cacheSessionTitle("CLI name");
        recorder.activateSessionMetadata(resumedSession, true);
        assertTrue(recorder.awaitPendingWrites(resumedSession, 5_000));
        assertEquals("CLI name", sessionManager.readCustomTitle(resumedSession));
        assertEquals("CLI name", sessionManager.readAgentName(resumedSession));
        assertTrue(recorder.releaseSessionState(resumedSession, 5_000));

        String nextSession = sessionManager.createSession();
        recorder.activateSessionMetadata(nextSession, false);
        recorder.prepareSessionMaterialization(nextSession);
        recorder.recordTranscript(nextSession,
            new UserMessage("next-user", MessageContent.ofText("next")));
        assertTrue(recorder.awaitPendingWrites(nextSession, 5_000));

        List<JsonNode> nextLines = JsonUtils.readJsonLines(
            sessionManager.getSessionFile(nextSession));
        assertEquals(List.of("user"),
            nextLines.stream().map(line -> line.path("type").asText()).toList());
    }

    @Test
    void contentReplacementRecordsArePersistedAsMetadata() throws Exception {
        String sessionId = sessionManager.createSession();
        recorder.recordContentReplacements(sessionId, List.of(
            new ToolResultBudget.Replacement("tool-1", "<persisted-output>preview</persisted-output>")));

        assertTrue(recorder.awaitPendingWrites(sessionId, 2_000));
        Path sessionFile = sessionManager.getSessionFile(sessionId);
        List<ToolResultBudget.Replacement> records = storage.readContentReplacements(sessionFile);
        assertEquals(List.of(new ToolResultBudget.Replacement(
            "tool-1", "<persisted-output>preview</persisted-output>")), records);
    }

    @Test
    void multipleAsyncWritesAllPersisted() throws InterruptedException {
        String sessionId = sessionManager.createSession();
        int count = 5;

        for (int i = 0; i < count; i++) {
            UserMessage msg = new UserMessage(
                    UUID.randomUUID().toString(),
                    MessageContent.ofText("Message " + i)
            );
            recorder.recordTranscript(sessionId, msg);
        }

        // Wait for all virtual threads to complete
        Thread.sleep(2000);

        Path sessionFile = sessionManager.getSessionFile(sessionId);
        List<Message> messages = storage.readMessages(sessionFile);
        assertEquals(count, messages.size());
    }

    @Test
    void awaitPendingWritesDrainsTheHeadlessTranscriptQueue() throws Exception {
        String sessionId = sessionManager.createSession();
        int count = 8;
        String lastUuid = null;

        for (int i = 0; i < count; i++) {
            lastUuid = UUID.randomUUID().toString();
            recorder.recordTranscript(sessionId, new UserMessage(
                lastUuid, MessageContent.ofText("Message " + i)));
        }
        recorder.recordLastPrompt(sessionId, "/compact");

        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000),
            "headless shutdown must be able to wait until every queued JSONL entry is durable");

        Path sessionFile = sessionManager.getSessionFile(sessionId);
        assertEquals(count, storage.readMessages(sessionFile).size());
        List<JsonNode> lines = JsonUtils.readJsonLines(sessionFile);
        JsonNode leafOnly = lines.getLast();
        assertEquals("last-prompt", leafOnly.path("type").asText());
        assertFalse(leafOnly.has("lastPrompt"));
        assertEquals(lastUuid, leafOnly.path("leafUuid").asText());
    }

    @Test
    void stampsCurrentBranchButDoesNotInventAPlanSlug() throws Exception {
        String sessionId = sessionManager.createSession();
        TranscriptRecorder branchRecorder = new TranscriptRecorder(
            sessionManager, storage, "/test/project", false, null,
            () -> "feature/wire-parity");

        branchRecorder.recordTranscript(sessionId,
            new UserMessage("u-branch", MessageContent.ofText("one")));
        branchRecorder.recordTranscript(sessionId,
            new AssistantMessage("a-branch", AssistantContent.of(
                List.of(new TextBlock("two")))));
        assertTrue(branchRecorder.awaitPendingWrites(sessionId, 5_000));

        List<JsonNode> lines = JsonUtils.readJsonLines(
            sessionManager.getSessionFile(sessionId));
        assertEquals("feature/wire-parity", lines.getFirst().path("gitBranch").asText());
        assertEquals("feature/wire-parity", lines.get(1).path("gitBranch").asText());
        assertFalse(lines.getFirst().has("slug"),
            "2.1.197 only stamps slug after getPlanSlug() has populated the plan cache");
        assertFalse(lines.get(1).has("slug"));
    }

    @Test
    void resumedRecorderReusesSlugAlreadyPresentInTranscript() throws Exception {
        String sessionId = sessionManager.createSession();
        Path file = sessionManager.getSessionFile(sessionId);
        storage.appendMessageWithParent(file,
            new UserMessage("u-existing", MessageContent.ofText("before")),
            sessionId, "/test/project", false, null, "HEAD",
            "official-existing-slug", null, null);
        TranscriptRecorder resumed = new TranscriptRecorder(
            sessionManager, storage, "/test/project", false, null, () -> "HEAD");

        resumed.recordTranscript(sessionId,
            new AssistantMessage("a-new", AssistantContent.of(
                List.of(new TextBlock("after")))));
        assertTrue(resumed.awaitPendingWrites(sessionId, 5_000));

        List<JsonNode> lines = JsonUtils.readJsonLines(file);
        assertEquals("official-existing-slug", lines.getLast().path("slug").asText());
    }

    @Test
    void resumedRecorderRestoresSlugBeforePlanFileLookup() {
        String sessionId = sessionManager.createSession();
        Path file = sessionManager.getSessionFile(sessionId);
        storage.appendMessageWithParent(file,
            new UserMessage("u-restore", MessageContent.ofText("before")),
            sessionId, "/test/project", false, null, "HEAD",
            "restored-plan-slug", null, null);
        PlanSlugRegistry.clear(sessionId);

        TranscriptRecorder resumed = new TranscriptRecorder(
            sessionManager, storage, "/test/project", false, null, () -> "HEAD");
        resumed.restoreSessionSlug(sessionId);

        assertEquals("restored-plan-slug", PlanSlugRegistry.get(sessionId).orElseThrow());
        PlanSlugRegistry.clear(sessionId);
    }

    @Test
    void sidechainRecorderWritesToAgentSubdirectoryNotMainSessionFile() throws Exception {
        String parentSessionId = sessionManager.createSession();
        String agentId = "a1111111111111111";
        Path agentFile = sessionManager.getAgentTranscriptPath(parentSessionId, agentId);
        TranscriptRecorder sidechainRecorder = new TranscriptRecorder(
            sessionManager, storage, "/test/project", /* isSidechain */ true, agentId,
            agentFile, "general-purpose");

        UserMessage msg = new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("sub-agent turn"));
        sidechainRecorder.record(parentSessionId, msg);
        sidechainRecorder.record(parentSessionId,
            new AssistantMessage(UUID.randomUUID().toString(),
                AssistantContent.of(List.of(new TextBlock("done")))));
        assertTrue(sidechainRecorder.awaitPendingWrites(parentSessionId, 5_000));

        assertTrue(Files.exists(agentFile), "sidechain file should exist: " + agentFile);
        assertFalse(Files.exists(sessionManager.getSessionFile(parentSessionId)),
            "main session file must not be created by a sidechain-only write");

        String line = Files.readString(agentFile);
        assertTrue(Strings.CS.contains(line, "\"isSidechain\":true"), "expected isSidechain:true, got: " + line);
        assertTrue(Strings.CS.contains(line, "\"agentId\":\"" + agentId + "\""), "expected agentId stamp, got: " + line);
        assertTrue(Strings.CS.contains(line, "\"sessionId\":\"" + parentSessionId + "\""),
            "sidechain entries still carry the shared PARENT session id, got: " + line);
        assertTrue(Strings.CS.contains(line, "\"attributionAgent\":\"general-purpose\""),
            "sidechain assistant entries carry the selected agent type, got: " + line);
    }

    @Test
    void parentChainAdvancesAndToolResultUsesSourceAssistant() throws InterruptedException {
        String sessionId = sessionManager.createSession();
        recorder.recordTranscript(sessionId,
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("hi")));
        recorder.recordTranscript(sessionId,
            new AssistantMessage(UUID.randomUUID().toString(),
                AssistantContent.of(List.of(new TextBlock("hello")))));
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));

        Path sessionFile = sessionManager.getSessionFile(sessionId);
        List<Message> after = storage.readMessages(sessionFile);
        assertEquals(2, after.size());
        String userUuid = after.getFirst().uuid();
        String assistantUuid = after.get(1).uuid();
        // first message has no parent; assistant chains to the user
        assertTrue(after.getFirst().parentUuid().isEmpty());
        assertEquals(userUuid, after.get(1).parentUuid().orElse(null));

        // tool result WITHOUT explicit source: chains to the last assistant (running parent)
        UserMessage tr1 = new UserMessage(UUID.randomUUID().toString(),
            MessageContent.ofToolResult("t1", List.of(new TextBlock("ok")), false),
            false, false, "r", MessageOrigin.USER, null, Instant.now(), null, null, null, null);
        recorder.recordTranscript(sessionId, tr1);
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));
        List<Message> m2 = storage.readMessages(sessionFile);
        assertEquals(3, m2.size());
        assertEquals(assistantUuid, m2.get(2).parentUuid().orElse(null));

        // tool result WITH explicit sourceToolAssistantUUID overrides the running parent
        UserMessage tr2 = new UserMessage(UUID.randomUUID().toString(),
            MessageContent.ofToolResult("t2", List.of(new TextBlock("ok2")), false),
            false, false, "r2", MessageOrigin.USER, null, Instant.now(), null, null, null,
            "external-assistant-uuid");
        recorder.recordTranscript(sessionId, tr2);
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));
        List<Message> m3 = storage.readMessages(sessionFile);
        assertEquals(4, m3.size());
        assertEquals("external-assistant-uuid", m3.get(3).parentUuid().orElse(null));
    }

    @Test
    void compactBoundaryHasNullParent() throws InterruptedException {
        String sessionId = sessionManager.createSession();
        recorder.recordTranscript(sessionId,
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("hi")));
        Thread.sleep(100);
        recorder.recordTranscript(sessionId, MessageFactory.createCompactBoundaryMessage("manual", 100));
        Thread.sleep(200);

        Path sessionFile = sessionManager.getSessionFile(sessionId);
        List<Message> messages = storage.readMessages(sessionFile);
        assertEquals(2, messages.size());
        assertInstanceOf(SystemMessage.class, messages.get(1));

        assertTrue(messages.get(1).parentUuid().isEmpty());
    }

    @Test
    void duplicateUuidIsSkipped() throws InterruptedException {
        String sessionId = sessionManager.createSession();
        UserMessage msg = new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("once"));
        recorder.recordTranscript(sessionId, msg);
        Thread.sleep(100);
        recorder.recordTranscript(sessionId, msg); // identical uuid
        Thread.sleep(200);

        Path sessionFile = sessionManager.getSessionFile(sessionId);
        assertEquals(1, storage.readMessages(sessionFile).size());
    }

    @Test
    void resumeSeedsChainFromExistingFile() throws InterruptedException {
        String sessionId = sessionManager.createSession();
        Path sessionFile = sessionManager.getSessionFile(sessionId);
        // Pre-populate the file (simulating a resumed session) with an explicit chain.
        storage.appendMessageWithParent(sessionFile,
            new UserMessage("pre-user", MessageContent.ofText("prior")),
            sessionId, "/test", false, null, null, null, null);
        storage.appendMessageWithParent(sessionFile,
            new AssistantMessage("pre-asst", AssistantContent.of(List.of(new TextBlock("prior")))),
            sessionId, "/test", false, null, null, null, "pre-user");

        // A newly recorded message must continue the on-disk chain (seedState).
        recorder.recordTranscript(sessionId,
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("continuation")));
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));

        List<Message> messages = storage.readMessages(sessionFile);
        assertEquals(3, messages.size());
        assertEquals("pre-user", messages.get(1).parentUuid().orElse(null));
        assertEquals("pre-asst", messages.get(2).parentUuid().orElse(null));
    }

    @Test
    void compactBoundaryWritesLogicalParentUuid() throws Exception {
        String sessionId = sessionManager.createSession();
        Path sessionFile = sessionManager.getSessionFile(sessionId);
        String userUuid = UUID.randomUUID().toString();
        String asstUuid = UUID.randomUUID().toString();
        // Pre-populate the kept messages synchronously — at compact time the retained
        // messages are already on disk and only the new boundary marker is recorded async.
        storage.appendMessageWithParent(sessionFile,
            new UserMessage(userUuid, MessageContent.ofText("u")),
            sessionId, "/test", false, null, null, null, null);
        storage.appendMessageWithParent(sessionFile,
            new AssistantMessage(asstUuid, AssistantContent.of(List.of(new TextBlock("a")))),
            sessionId, "/test", false, null, null, null, userUuid);

        // Only the boundary is recorded async (like compact's new marker).
        recorder.recordTranscript(sessionId,
            new SystemMessage(UUID.randomUUID().toString(), "compact_boundary", "info", "compacted"));
        Thread.sleep(300);

        List<String> lines = Files.readAllLines(sessionFile);
        assertEquals(3, lines.size());

        ObjectMapper mapper = new ObjectMapper();
        JsonNode boundary = mapper.readTree(lines.get(2));
        assertEquals("system", boundary.get("type").asText());
        assertEquals("compact_boundary", boundary.get("subtype").asText());
        assertTrue(boundary.get("parentUuid").isNull());
        assertEquals(asstUuid, boundary.get("logicalParentUuid").asText());


        assertNull(mapper.readTree(lines.getFirst()).get("logicalParentUuid"));
        assertNull(mapper.readTree(lines.get(1)).get("logicalParentUuid"));
    }

    @Test
    void partialCompactBoundaryPrefersItsExplicit197LogicalParentOverPreservedTail()
            throws Exception {
        String sessionId = sessionManager.createSession();
        Path sessionFile = sessionManager.getSessionFile(sessionId);
        storage.appendMessageWithParent(sessionFile,
            new UserMessage("current-parent", MessageContent.ofText("u")),
            sessionId, "/test", false, null, null, null, null);
        CompactMetadata metadata = new CompactMetadata("manual", 42L)
            .withPreserved(
                new PreservedSegment("kept-head", "summary-anchor", "kept-tail"),
                new PreservedMessages("summary-anchor", List.of("kept-head", "kept-tail"),
                    List.of("kept-head", "kept-tail")));
        SystemMessage boundary = new SystemMessage(
            "partial-boundary", "compact_boundary", "info", "Conversation compacted",
            "summarized-parent", Instant.now(), metadata);

        recorder.recordTranscript(sessionId, boundary);
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));

        JsonNode row = JsonUtils.readJsonLines(sessionFile).getLast();
        assertEquals("summarized-parent", row.path("logicalParentUuid").asText(),
            "up_to uses the last summarized message, not the kept suffix tail");
    }

    @Test
    void compactBoundaryReappendsTheLatestSessionModeImmediatelyBeforeIt() throws Exception {
        String sessionId = sessionManager.createSession();
        Path sessionFile = sessionManager.getSessionFile(sessionId);
        storage.appendMessageWithParent(sessionFile,
            new UserMessage("u-before-mode", MessageContent.ofText("u")),
            sessionId, "/test", false, null, null, null, null);
        storage.appendMode(sessionFile, sessionId, "normal");

        recorder.recordTranscript(sessionId,
            new SystemMessage("compact-with-mode", "compact_boundary", "info", "compacted"));
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));

        List<String> lines = Files.readAllLines(sessionFile);
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(4, lines.size());
        assertEquals("mode", mapper.readTree(lines.get(2)).path("type").asText());
        assertEquals("normal", mapper.readTree(lines.get(2)).path("mode").asText());
        assertEquals("compact_boundary", mapper.readTree(lines.get(3)).path("subtype").asText());
    }

    @Test
    void successfulCompactWritesLastPromptBeforeBoundaryWithoutEndingPromptIdentity() throws Exception {
        String sessionId = sessionManager.createSession();
        Path sessionFile = sessionManager.getSessionFile(sessionId);

        recorder.recordPromptStart(sessionId, "sdk");
        recorder.recordTranscript(sessionId,
            new UserMessage("compact-trigger", MessageContent.ofText("AUTO_COMPACT_TRIGGER")));
        recorder.recordPreCompactLastPrompt(sessionId, "AUTO_COMPACT_TRIGGER");
        recorder.recordTranscript(sessionId,
            MessageFactory.createCompactBoundaryMessage("auto", 12_008));
        recorder.recordTranscript(sessionId,
            new UserMessage("compact-summary", MessageContent.ofText("summary")));
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));

        List<JsonNode> lines = JsonUtils.readJsonLines(sessionFile);
        assertEquals(4, lines.size());
        assertEquals("compact-trigger", lines.getFirst().path("uuid").asText());

        JsonNode lastPrompt = lines.get(1);
        assertEquals("last-prompt", lastPrompt.path("type").asText());
        assertEquals("AUTO_COMPACT_TRIGGER", lastPrompt.path("lastPrompt").asText());
        assertEquals("compact-trigger", lastPrompt.path("leafUuid").asText());

        JsonNode boundary = lines.get(2);
        assertEquals("compact_boundary", boundary.path("subtype").asText());
        assertTrue(boundary.path("parentUuid").isNull());
        assertEquals("compact-trigger", boundary.path("logicalParentUuid").asText());

        JsonNode summary = lines.get(3);
        assertEquals("compact-summary", summary.path("uuid").asText());
        assertEquals(boundary.path("uuid").asText(), summary.path("parentUuid").asText());
        assertEquals(lines.getFirst().path("promptId").asText(), summary.path("promptId").asText(),
            "reAppendSessionMetadata must not end the active prompt turn");
    }

    @Test
    void autoCompactRefreshesNativeMetadataBeforeBoundary() throws Exception {
        String sessionId = sessionManager.createSession();
        Path sessionFile = sessionManager.getSessionFile(sessionId);
        ObjectMapper mapper = JsonUtils.getMapper();

        recorder.recordAiTitle(sessionId, "Wire title");
        recorder.recordMode(sessionId, "normal");
        recorder.recordPermissionMode(sessionId, "bypassPermissions");
        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.put("messageId", "auto-user");
        snapshot.putObject("trackedFileBackups");
        storage.insertFileHistorySnapshot(sessionFile, "auto-user", snapshot, false);
        recorder.recordTranscript(sessionId,
            new UserMessage("auto-user", MessageContent.ofText("auto compact")));
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));

        recorder.prepareAutoCompactMetadata(sessionId, "auto compact");
        recorder.recordTranscript(sessionId,
            MessageFactory.createCompactBoundaryMessage("auto", 12_008));
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));

        List<JsonNode> lines = JsonUtils.readJsonLines(sessionFile);
        List<String> tailTypes = lines.subList(lines.size() - 5, lines.size()).stream()
            .map(line -> line.path("type").asText())
            .toList();
        assertEquals(List.of(
            "last-prompt", "ai-title", "mode", "permission-mode", "system"), tailTypes);
        assertEquals(1, lines.stream()
            .filter(line -> Strings.CS.equals("file-history-snapshot", line.path("type").asText()))
            .count(), "auto compact must not duplicate the turn's file snapshot");
        assertEquals("compact_boundary", lines.getLast().path("subtype").asText());
    }

    @Test
    void autoCompactSkipsEveryAlreadyRecordedPreservedMessage() throws Exception {
        String sessionId = sessionManager.createSession();
        String assistantUuid = "auto-preserved-assistant";
        String durationUuid = "auto-preserved-duration";
        String triggerUuid = "auto-trigger-user";
        recorder.recordTranscript(sessionId, new AssistantMessage(
            assistantUuid, AssistantContent.of(List.of(new TextBlock("before")))));
        recorder.recordTranscript(sessionId, new SystemMessage(
            durationUuid, "turn_duration", "info", "duration"));
        UserMessage trigger = new UserMessage(
            triggerUuid, MessageContent.ofText("trigger auto compact"));
        recorder.recordTranscript(sessionId, trigger);

        PreservedSegment segment = new PreservedSegment(
            assistantUuid, "auto-anchor", triggerUuid);
        PreservedMessages preserved = new PreservedMessages(
            "auto-anchor",
            List.of(assistantUuid, durationUuid, triggerUuid),
            List.of(assistantUuid, durationUuid, triggerUuid));
        CompactMetadata metadata = new CompactMetadata(
            "auto", 12_008L, 10L, segment, preserved, 800L, 11_208L,
            null, null, null, null);
        SystemMessage boundary = new SystemMessage(
            "auto-boundary", "compact_boundary", "info", "Conversation compacted",
            null, Instant.now(), metadata);
        recorder.recordTranscript(sessionId, boundary);
        recorder.recordTranscript(sessionId,
            new UserMessage("auto-summary", MessageContent.ofText("summary")));
        recorder.recordTranscript(sessionId,
            new AssistantMessage(assistantUuid,
                AssistantContent.of(List.of(new TextBlock("before")))));
        recorder.recordTranscript(sessionId,
            new SystemMessage(durationUuid, "turn_duration", "info", "duration"));
        recorder.recordTranscript(sessionId, trigger);
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));

        List<JsonNode> lines = JsonUtils.readJsonLines(
            sessionManager.getSessionFile(sessionId));
        List<JsonNode> triggerRows = lines.stream()
            .filter(line -> triggerUuid.equals(line.path("uuid").asText()))
            .toList();
        assertEquals(1, triggerRows.size());
        JsonNode summary = lines.stream()
            .filter(line -> Strings.CS.equals("auto-summary", line.path("uuid").asText()))
            .findFirst().orElseThrow();
        assertEquals("auto-boundary", summary.path("parentUuid").asText());
        assertEquals(1, lines.stream()
            .filter(line -> assistantUuid.equals(line.path("uuid").asText())).count());
        assertEquals(1, lines.stream()
            .filter(line -> durationUuid.equals(line.path("uuid").asText())).count());
    }

    @Test
    void sdkCliMetadataEntriesPreserveQueueOrder() throws Exception {
        String sessionId = sessionManager.createSession();
        String userUuid = UUID.randomUUID().toString();
        String assistantUuid = UUID.randomUUID().toString();

        recorder.recordQueueOperation(sessionId, "enqueue", "hello");
        recorder.recordQueueOperation(sessionId, "dequeue", null);
        recorder.recordTranscript(sessionId,
            new UserMessage(userUuid, MessageContent.ofText("hello")));
        recorder.recordTranscript(sessionId,
            new AssistantMessage(assistantUuid,
                AssistantContent.of(List.of(new TextBlock("OK")))));
        recorder.recordLastPrompt(sessionId, "hello");
        Thread.sleep(500);

        List<String> lines = Files.readAllLines(sessionManager.getSessionFile(sessionId));
        assertEquals(5, lines.size());
        ObjectMapper mapper = new ObjectMapper();
        JsonNode enqueue = mapper.readTree(lines.getFirst());
        JsonNode dequeue = mapper.readTree(lines.get(1));
        JsonNode lastPrompt = mapper.readTree(lines.get(4));
        assertEquals("queue-operation", enqueue.path("type").asText());
        assertEquals("enqueue", enqueue.path("operation").asText());
        assertEquals("hello", enqueue.path("content").asText());
        assertEquals("dequeue", dequeue.path("operation").asText());
        assertFalse(dequeue.has("content"));
        assertEquals("last-prompt", lastPrompt.path("type").asText());
        assertEquals("hello", lastPrompt.path("lastPrompt").asText());
        assertEquals(assistantUuid, lastPrompt.path("leafUuid").asText());
    }

    @Test
    void lastPromptMetadataFlattensTrimsAndTruncatesLikeReleasedCli() throws Exception {
        String multilineSession = sessionManager.createSession();
        recorder.recordLastPrompt(multilineSession, "  first line\nsecond line\n");
        assertTrue(recorder.awaitPendingWrites(multilineSession, 5_000));

        JsonNode multiline = JsonUtils.readJsonLines(
            sessionManager.getSessionFile(multilineSession)).getFirst();
        assertEquals("first line second line", multiline.path("lastPrompt").asText());

        String longSession = sessionManager.createSession();
        recorder.recordLastPrompt(longSession, "x".repeat(199) + " \ntruncated tail");
        assertTrue(recorder.awaitPendingWrites(longSession, 5_000));

        JsonNode truncated = JsonUtils.readJsonLines(
            sessionManager.getSessionFile(longSession)).getFirst();
        assertEquals("x".repeat(199) + "…", truncated.path("lastPrompt").asText());
    }

    @Test
    void interactiveLastPromptIsCachedUntilSessionMetadataFlush() throws Exception {
        String sessionId = sessionManager.createSession();
        recorder.recordTranscript(sessionId,
            new UserMessage("interactive-user", MessageContent.ofText("hello")));
        recorder.cacheLastPrompt(sessionId, "interactive prompt");
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));

        List<JsonNode> beforeFlush = JsonUtils.readJsonLines(
            sessionManager.getSessionFile(sessionId));
        assertTrue(beforeFlush.stream().noneMatch(line ->Strings.CS.equals(
            "last-prompt", line.path("type").asText())));

        recorder.flushCachedLastPrompt(sessionId);
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));
        JsonNode flushed = JsonUtils.readJsonLines(
            sessionManager.getSessionFile(sessionId)).getLast();
        assertEquals("last-prompt", flushed.path("type").asText());
        assertEquals("interactive prompt", flushed.path("lastPrompt").asText());
        assertEquals("interactive-user", flushed.path("leafUuid").asText());
    }

    @Test
    void resumedSessionRestoresMissingFallbackThenWritesLeafOnlyAtShutdown() throws Exception {
        String sessionId = sessionManager.createSession();
        recorder.recordTranscript(sessionId,
            new UserMessage("seed-user", MessageContent.ofText("Seed TTY resume WIRE197")));
        recorder.recordTranscript(sessionId,
            new SystemMessage("seed-duration", "turn_duration", "info", "duration"));
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));

        TranscriptRecorder resumed = new TranscriptRecorder(sessionManager, storage);
        List<Message> restored = storage.readMessages(sessionManager.getSessionFile(sessionId));
        int sourceLineCount = JsonUtils.readJsonLines(
            sessionManager.getSessionFile(sessionId)).size();
        resumed.restoreSessionMetadata(sessionId, restored);
        assertTrue(resumed.awaitPendingWrites(sessionId, 5_000));

        List<JsonNode> restoredLines = JsonUtils.readJsonLines(
            sessionManager.getSessionFile(sessionId));
        assertEquals(sourceLineCount + 1, restoredLines.size());
        JsonNode fallback = restoredLines.getLast();
        assertEquals("last-prompt", fallback.path("type").asText());
        assertEquals("Seed TTY resume WIRE197", fallback.path("lastPrompt").asText());
        assertEquals("seed-duration", fallback.path("leafUuid").asText());

        resumed.flushCachedLastPrompt(sessionId);
        assertTrue(resumed.awaitPendingWrites(sessionId, 5_000));
        JsonNode checkpoint = JsonUtils.readJsonLines(
            sessionManager.getSessionFile(sessionId)).getLast();
        assertEquals("last-prompt", checkpoint.path("type").asText());
        assertFalse(checkpoint.has("lastPrompt"));
        assertEquals("seed-duration", checkpoint.path("leafUuid").asText());

        resumed.recordTranscript(sessionId,
            new UserMessage("next-user", MessageContent.ofText("next prompt")));
        resumed.recordLastPrompt(sessionId, "next prompt");
        resumed.flushCachedLastPrompt(sessionId);
        assertTrue(resumed.awaitPendingWrites(sessionId, 5_000));
        List<JsonNode> afterNextTurn = JsonUtils.readJsonLines(
            sessionManager.getSessionFile(sessionId));
        assertEquals(3, afterNextTurn.stream()
            .filter(line -> Strings.CS.equals("last-prompt", line.path("type").asText()))
            .count(), "fallback, resumed leaf checkpoint, and new prompt are each written once");
        assertEquals("next prompt", afterNextTurn.getLast().path("lastPrompt").asText());
    }

    @Test
    void resumedSessionPrefersPersistedLastPromptOverFirstPromptFallback() throws Exception {
        String sessionId = sessionManager.createSession();
        recorder.recordTranscript(sessionId,
            new UserMessage("first-user", MessageContent.ofText("first prompt")));
        recorder.recordLastPrompt(sessionId, "most recent prompt");
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));

        TranscriptRecorder resumed = new TranscriptRecorder(sessionManager, storage);
        List<Message> restored = storage.readMessages(sessionManager.getSessionFile(sessionId));
        resumed.restoreSessionMetadata(sessionId, restored);
        assertTrue(resumed.awaitPendingWrites(sessionId, 5_000));

        JsonNode checkpoint = JsonUtils.readJsonLines(
            sessionManager.getSessionFile(sessionId)).getLast();
        assertEquals("most recent prompt", checkpoint.path("lastPrompt").asText());
        assertEquals("first-user", checkpoint.path("leafUuid").asText());
        assertEquals(1, JsonUtils.readJsonLines(sessionManager.getSessionFile(sessionId)).stream()
            .filter(line -> Strings.CS.equals("last-prompt", line.path("type").asText()))
            .count(), "an already-persisted last-prompt is not duplicated during restore");

        resumed.flushCachedLastPrompt(sessionId);
        assertTrue(resumed.awaitPendingWrites(sessionId, 5_000));
        JsonNode leafOnly = JsonUtils.readJsonLines(
            sessionManager.getSessionFile(sessionId)).getLast();
        assertEquals("last-prompt", leafOnly.path("type").asText());
        assertFalse(leafOnly.has("lastPrompt"));
        assertEquals("first-user", leafOnly.path("leafUuid").asText());
    }

    @Test
    void flushWithoutCachedPromptDoesNotInventLeafOnlyCheckpoint() throws Exception {
        String sessionId = sessionManager.createSession();
        recorder.recordTranscript(sessionId,
            new UserMessage("interactive-user", MessageContent.ofText("hello")));
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));

        recorder.flushCachedLastPrompt(sessionId);
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));

        List<JsonNode> lines = JsonUtils.readJsonLines(
            sessionManager.getSessionFile(sessionId));
        assertEquals(1, lines.size());
        assertEquals("user", lines.getFirst().path("type").asText());
    }

    @Test
    void shutdownFlushDoesNotDuplicateAlreadyMaterializedPrompt() throws Exception {
        String sessionId = sessionManager.createSession();
        recorder.recordTranscript(sessionId,
            new UserMessage("resume-user", MessageContent.ofText("resume prompt")));
        recorder.recordLastPrompt(sessionId, "resume prompt");
        // Model the UI completion/shutdown race: a late cache update for the
        // same completed turn must not append a second EOF checkpoint.
        recorder.cacheLastPrompt(sessionId, "resume prompt");
        recorder.flushCachedLastPrompt(sessionId);
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));

        long checkpoints = JsonUtils.readJsonLines(sessionManager.getSessionFile(sessionId)).stream()
            .filter(line -> Strings.CS.equals("last-prompt", line.path("type").asText()))
            .count();
        assertEquals(1, checkpoints);
    }

    @Test
    void slashCommandLastPromptWritesLeafOnlyEntry() throws Exception {

        String sessionId = sessionManager.createSession();
        recorder.recordTranscript(sessionId,
            new UserMessage("slash-user", MessageContent.ofText("before compact")));
        recorder.recordLastPrompt(sessionId, "/compact preserve auth details");
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));

        List<JsonNode> lines = JsonUtils.readJsonLines(
            sessionManager.getSessionFile(sessionId));
        assertEquals(2, lines.size());
        JsonNode leafOnly = lines.getLast();
        assertEquals("last-prompt", leafOnly.path("type").asText());
        assertFalse(leafOnly.has("lastPrompt"));
        assertEquals("slash-user", leafOnly.path("leafUuid").asText());
    }

    @Test
    void manualCompactRefreshesSnapshotAndAdvancesLeafByInsertionOrder() throws Exception {
        String sessionId = sessionManager.createSession();
        Path sessionFile = sessionManager.getSessionFile(sessionId);
        ObjectMapper mapper = JsonUtils.getMapper();

        recorder.recordAiTitle(sessionId, "Compact probe");
        recorder.recordMode(sessionId, "normal");
        recorder.recordPermissionMode(sessionId, "bypassPermissions");
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));

        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.put("messageId", "old-user");
        snapshot.putObject("trackedFileBackups");
        snapshot.put("timestamp", "2026-08-06T10:26:44.000Z");
        storage.insertFileHistorySnapshot(
            sessionFile, "old-user", snapshot, false);

        recorder.recordTranscript(sessionId,
            new UserMessage("old-user", MessageContent.ofText("before compact")));
        recorder.cacheLastPrompt(sessionId, "before compact");
        recorder.prepareManualCompactMetadata(sessionId, "compact-command");
        recorder.recordTranscript(sessionId, new UserMessage(
            "compact-command", MessageContent.ofText("<command-name>/compact</command-name>"),
            false, false, null, MessageOrigin.USER, null,
            Instant.parse("2026-08-06T10:26:45Z"), null, null, null));
        recorder.recordTranscript(sessionId, new UserMessage(
            "post-attachment-tail", MessageContent.ofText("tail"),
            true, false, null, MessageOrigin.USER, null,
            Instant.parse("2026-08-06T10:26:44Z"), null, null, null));
        recorder.flushCachedLastPrompt(sessionId);
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));

        List<JsonNode> lines = JsonUtils.readJsonLines(sessionFile);
        List<JsonNode> snapshots = lines.stream()
            .filter(line -> Strings.CS.equals(
                "file-history-snapshot", line.path("type").asText()))
            .toList();
        assertEquals(2, snapshots.size());
        JsonNode refreshed = snapshots.getLast();
        assertEquals("compact-command", refreshed.path("messageId").asText());
        assertEquals("compact-command", refreshed.path("snapshot").path("messageId").asText());
        assertFalse(refreshed.has("sessionId"));

        List<JsonNode> lastPrompts = lines.stream()
            .filter(line -> Strings.CS.equals("last-prompt", line.path("type").asText()))
            .toList();
        assertEquals("old-user", lastPrompts.getFirst().path("leafUuid").asText(),
            "the pre-compact metadata checkpoint must not race forward into compact output");
        JsonNode lastPrompt = lastPrompts.getLast();
        assertEquals("last-prompt", lastPrompt.path("type").asText());
        assertEquals("post-attachment-tail", lastPrompt.path("leafUuid").asText(),
            "an older timestamp appended later still becomes the resumable session leaf");
    }

    @Test
    void resumedLocalCompactRestoresPermissionAndCreatesCommandSnapshotWithoutDuplicateMode()
            throws Exception {
        String sessionId = sessionManager.createSession();
        recorder.recordTranscript(sessionId, new UserMessage(
            "sdk-user", MessageContent.ofText("seed"), false, false, null,
            MessageOrigin.USER, null, Instant.now(), null,
            "bypassPermissions", sessionId, null, null));
        recorder.recordTranscript(sessionId, new AssistantMessage(
            "sdk-assistant", AssistantContent.of(List.of(new TextBlock("OK")))));
        recorder.recordMode(sessionId, "normal");
        recorder.prepareManualCompactMetadata(sessionId, "compact-command");
        recorder.recordTranscript(sessionId,
            MessageFactory.createCompactBoundaryMessage("manual", 2));
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));

        List<JsonNode> lines = JsonUtils.readJsonLines(
            sessionManager.getSessionFile(sessionId));
        assertEquals(1, lines.stream()
            .filter(line -> Strings.CS.equals("mode", line.path("type").asText())).count());
        JsonNode permission = lines.stream()
            .filter(line -> Strings.CS.equals("permission-mode", line.path("type").asText()))
            .findFirst().orElseThrow();
        assertEquals("bypassPermissions", permission.path("permissionMode").asText());
        JsonNode snapshot = lines.stream()
            .filter(line -> Strings.CS.equals("file-history-snapshot", line.path("type").asText()))
            .findFirst().orElseThrow();
        assertEquals("compact-command", snapshot.path("messageId").asText());
        assertEquals("compact-command", snapshot.path("snapshot").path("messageId").asText());
        assertTrue(snapshot.path("snapshot").path("trackedFileBackups").isObject());
    }

    @Test
    void recoveredChainAndRestoredModeAreNotReappendedBeforeCompact() throws Exception {
        String sessionId = sessionManager.createSession();
        UserMessage seedUser = new UserMessage(
            "seed-user", MessageContent.ofText("seed"));
        AssistantMessage seedAssistant = new AssistantMessage(
            "seed-assistant", AssistantContent.of(List.of(new TextBlock("OK"))));
        recorder.recordTranscript(sessionId, seedUser);
        recorder.recordTranscript(sessionId, seedAssistant);
        recorder.recordLastPrompt(sessionId, "seed");
        assertTrue(recorder.releaseSessionState(sessionId, 5_000));

        recorder.restoreSessionMetadata(sessionId, List.of(seedUser, seedAssistant));
        recorder.recordRestoredMode(sessionId, "normal");
        recorder.recordTranscript(sessionId,
            new UserMessage("recovery-user", MessageContent.ofText(
                "Continue from where you left off.")));
        recorder.recordTranscript(sessionId, new AssistantMessage(
            "recovery-assistant", AssistantContent.of(
                List.of(new TextBlock("No response requested.")))));
        recorder.recordTranscript(sessionId,
            MessageFactory.createCompactBoundaryMessage("manual", 2));
        recorder.flushCachedLastPrompt(sessionId);
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));

        List<JsonNode> lines = JsonUtils.readJsonLines(
            sessionManager.getSessionFile(sessionId));
        assertEquals(1, lines.stream()
            .filter(line -> Strings.CS.equals("mode", line.path("type").asText())).count());
        assertEquals(1, lines.stream()
            .filter(line -> Strings.CS.equals(
                "last-prompt", line.path("type").asText())).count(),
            "the recovered chain replaces the restored leaf-only checkpoint state");
    }

    @Test
    void resumedLocalCompactUsesPinnedInvocationPermissionAfterLastPrompt() throws Exception {
        String sessionId = sessionManager.createSession();
        recorder.recordTranscript(sessionId, new UserMessage(
            "sdk-user", MessageContent.ofText("seed"), false, false, null,
            MessageOrigin.USER, null, Instant.now(), null,
            "default", sessionId, null, null));
        recorder.recordTranscript(sessionId, new AssistantMessage(
            "sdk-assistant", AssistantContent.of(List.of(new TextBlock("OK")))));
        recorder.recordMode(sessionId, "normal");
        recorder.cacheLastPrompt(sessionId, "seed");
        recorder.cachePermissionMode(sessionId, "bypassPermissions");
        recorder.prepareManualCompactMetadata(sessionId, "compact-command");
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));

        List<JsonNode> lines = JsonUtils.readJsonLines(
            sessionManager.getSessionFile(sessionId));
        int lastPrompt = indexOfType(lines, "last-prompt");
        int permission = indexOfType(lines, "permission-mode");
        assertTrue(lastPrompt >= 0 && permission > lastPrompt);
        assertEquals("bypassPermissions",
            lines.get(permission).path("permissionMode").asText());
    }

    @Test
    void oneHeadlessTurnSharesPromptIdAcrossRecoveredAndLiveUserMessages() throws Exception {
        String sessionId = sessionManager.createSession();
        UserMessage recovery = new UserMessage(
            UUID.randomUUID().toString(),
            MessageContent.ofBlocks(List.of(new TextBlock("Continue from where you left off."))),
            true, false, null, MessageOrigin.USER, null, Instant.now(),
            null, null, null, null);
        UserMessage live = new UserMessage(
            UUID.randomUUID().toString(), MessageContent.ofText("live prompt"));

        recorder.recordQueueOperation(sessionId, "enqueue", "live prompt");
        recorder.recordPromptStart(sessionId, "sdk");
        recorder.recordQueueOperation(sessionId, "dequeue", null);
        recorder.recordTranscript(sessionId, recovery);
        recorder.recordTranscript(sessionId, live);
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));

        List<String> lines = Files.readAllLines(sessionManager.getSessionFile(sessionId));
        ObjectMapper mapper = new ObjectMapper();
        String recoveryPromptId = mapper.readTree(lines.get(2)).path("promptId").asText();
        String livePromptId = mapper.readTree(lines.get(3)).path("promptId").asText();
        assertFalse(StringUtils.isBlank(recoveryPromptId));
        assertEquals(recoveryPromptId, livePromptId,
            "TS getPromptId() stamps every user message recorded in the same turn");
        assertEquals(livePromptId, recorder.currentPromptId(sessionId));
    }

    @Test
    void interactiveTypedTurnSharesPromptIdAndOmitsPromptSourceFromToolResults() throws Exception {
        String sessionId = sessionManager.createSession();
        UserMessage typed = new UserMessage(
            "typed-user", MessageContent.ofText("hello"));
        UserMessage toolResult = new UserMessage(
            "tool-result",
            MessageContent.ofBlocks(List.of(new ToolResultBlock(
                "toolu_197", List.of(new TextBlock("approved")), false))),
            false, false, Map.of("plan", "ok"), MessageOrigin.USER,
            null, Instant.now(), null, null, sessionId, "assistant-tool-use");

        recorder.recordPromptStart(sessionId, "typed");
        recorder.recordTranscript(sessionId, typed);
        recorder.recordTranscript(sessionId, toolResult);
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));

        List<JsonNode> lines = JsonUtils.readJsonLines(
            sessionManager.getSessionFile(sessionId));
        JsonNode typedLine = lines.getFirst();
        JsonNode resultLine = lines.get(1);
        assertEquals("typed", typedLine.path("promptSource").asText());
        assertEquals("human", typedLine.path("origin").path("kind").asText());
        assertEquals(typedLine.path("promptId").asText(), resultLine.path("promptId").asText());
        assertFalse(resultLine.has("promptSource"));
        assertFalse(resultLine.has("origin"));
    }

    @Test
    void planSlugRegistryIsStampedOnConversationRows() throws Exception {
        String sessionId = sessionManager.createSession();
        PlanSlugRegistry.set(sessionId, "calm-building-harbor");
        try {
            recorder.recordTranscript(sessionId,
                new UserMessage("slug-user", MessageContent.ofText("plan")));
            assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));

            JsonNode line = JsonUtils.readJsonLines(
                sessionManager.getSessionFile(sessionId)).getFirst();
            assertEquals("calm-building-harbor", line.path("slug").asText());
        } finally {
            PlanSlugRegistry.clear(sessionId);
        }
    }

    @Test
    void queuedRowsKeepThePlanSlugSnapshotFromRecordTime() throws Exception {
        String sessionId = sessionManager.createSession();
        Path file = sessionManager.getSessionFile(sessionId);
        PlanSlugRegistry.clear(sessionId);
        try {
            SessionFileLock.withLock(file, () -> {
                recorder.recordTranscript(sessionId,
                    new UserMessage("before-plan-slug", MessageContent.ofText("before")));
                PlanSlugRegistry.set(sessionId, "glittery-foraging-puzzle");
            });
            recorder.recordTranscript(sessionId,
                new AssistantMessage("after-plan-slug", AssistantContent.of(
                    List.of(new TextBlock("after")))));
            assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));

            List<JsonNode> lines = JsonUtils.readJsonLines(file);
            assertFalse(lines.getFirst().has("slug"),
                "a later plan lookup must not retroactively stamp an already-queued row");
            assertEquals("glittery-foraging-puzzle", lines.get(1).path("slug").asText());
        } finally {
            PlanSlugRegistry.clear(sessionId);
        }
    }

    @Test
    void resumedSessionModeIsQueuedAfterLastPromptLikeOfficialCli() throws Exception {
        String sessionId = sessionManager.createSession();

        recorder.recordLastPrompt(sessionId, "resume prompt");
        recorder.recordMode(sessionId, "normal");
        Thread.sleep(500);

        List<String> lines = Files.readAllLines(sessionManager.getSessionFile(sessionId));
        assertEquals(2, lines.size());

        ObjectMapper mapper = new ObjectMapper();
        JsonNode lastPrompt = mapper.readTree(lines.getFirst());
        JsonNode mode = mapper.readTree(lines.get(1));
        assertEquals("last-prompt", lastPrompt.path("type").asText());
        assertEquals("resume prompt", lastPrompt.path("lastPrompt").asText());
        assertEquals("mode", mode.path("type").asText());
        assertEquals("normal", mode.path("mode").asText());
        assertEquals(sessionId, mode.path("sessionId").asText());
    }

    @Test
    void permissionModeChangeIsQueuedAfterLastPrompt() throws Exception {
        String sessionId = sessionManager.createSession();

        recorder.recordLastPrompt(sessionId, "approved plan");
        recorder.recordPermissionMode(sessionId, "default");
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));

        List<JsonNode> lines = JsonUtils.readJsonLines(
            sessionManager.getSessionFile(sessionId));
        assertEquals(2, lines.size());
        assertEquals("last-prompt", lines.getFirst().path("type").asText());
        assertEquals("permission-mode", lines.get(1).path("type").asText());
        assertEquals("default", lines.get(1).path("permissionMode").asText());
        assertEquals(sessionId, lines.get(1).path("sessionId").asText());
    }

    @Test
    void headlessUserPermissionDoesNotCountAsNativePermissionMetadata() throws Exception {
        String sessionId = sessionManager.createSession();
        recorder.recordTranscript(sessionId, new UserMessage(
            "sdk-user", MessageContent.ofText("seed"), false, false, null,
            MessageOrigin.USER, null, Instant.now(), null,
            "bypassPermissions", sessionId, null, null));
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));

        assertFalse(recorder.hasPersistedPermissionMode(sessionId));
        recorder.recordPermissionMode(sessionId, "bypassPermissions");
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));
        assertTrue(recorder.hasPersistedPermissionMode(sessionId));
    }

    @Test
    void generatedTitleUsesReleasedAiTitleMetadataShape() throws Exception {
        String sessionId = sessionManager.createSession();

        recorder.recordAiTitle(sessionId, "Wire title");
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));

        JsonNode line = JsonUtils.readJsonLines(
            sessionManager.getSessionFile(sessionId)).getFirst();
        assertEquals("ai-title", line.path("type").asText());
        assertEquals("Wire title", line.path("aiTitle").asText());
        assertEquals(sessionId, line.path("sessionId").asText());
        assertEquals(3, line.size(), "ai-title is metadata-only and carries no timestamp/cwd stamp");
    }

    @Test
    void turnDurationUsesReleasedSystemTranscriptShape() throws Exception {
        String sessionId = sessionManager.createSession();

        recorder.recordTranscript(sessionId, MessageFactory.createTurnDurationMessage(155L, 8));
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));

        JsonNode line = JsonUtils.readJsonLines(
            sessionManager.getSessionFile(sessionId)).getFirst();
        assertEquals("system", line.path("type").asText());
        assertEquals("turn_duration", line.path("subtype").asText());
        assertEquals(155L, line.path("durationMs").asLong());
        assertEquals(8, line.path("messageCount").asInt());
        assertFalse(line.path("isMeta").asBoolean(true));
        assertFalse(line.has("level"));
        assertFalse(line.has("content"));
    }
}
