package com.claudecode.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.cli.CliResumeTargetResolver.Target;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolExecutor;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.engine.ToolResultBudget;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.UserMessage;
import com.claudecode.runtime.query.DefaultQuerySession;
import com.claudecode.runtime.query.QuerySessionSpec;
import com.claudecode.runtime.session.PreparedSessionResume;
import com.claudecode.runtime.session.SessionLifecycle;
import com.claudecode.runtime.session.SessionResumeRequest;
import com.claudecode.session.SessionInfo;
import com.claudecode.session.SessionManager;
import com.claudecode.session.SessionStorage;
import com.claudecode.session.TranscriptReadCounter;
import com.claudecode.session.TranscriptRecorder;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests the output-safe, immutable result boundary for startup session restoration. */
class CliSessionRestoreCoordinatorTest {

    private static final String USAGE =
        "Error: --resume requires a valid session ID or session title when used with --print. "
            + "Usage: claude -p --resume <session-id|title>";
    private static final String ALPHA = "11111111-1111-4111-8111-111111111111";
    private static final String BETA = "22222222-2222-4222-8222-222222222222";

    @TempDir
    Path transcripts;

    @Test
    void continueUsesSessionManagerLastModifiedOrderRatherThanCreationTime() {
        SessionInfo mostRecentlyTouched = new SessionInfo(
            "touched-last", 2_000L, Instant.parse("2025-01-01T00:00:00Z"),
            1, null, null, null, null);
        SessionInfo newestCreatedButOlderTouched = new SessionInfo(
            "created-last", 1_000L, Instant.parse("2025-02-01T00:00:00Z"),
            1, null, null, null, null);

        assertEquals("touched-last", CliSessionRestoreCoordinator.latestSessionId(
            List.of(mostRecentlyTouched, newestCreatedButOlderTouched)));
        assertNull(CliSessionRestoreCoordinator.latestSessionId(List.of()));
        assertNull(CliSessionRestoreCoordinator.latestSessionId(null));
    }


    @Test
    void anAbsentFlagRestoresNothingAtAll() {
        Launch launch = new Launch(true);

        CliSessionRestoreCoordinator.Restoration restoration =
            CliSessionRestoreCoordinator.restore(launch.request(), new Target.Absent());

        assertFalse(restoration.restored());
        assertFalse(restoration.pickerRequested());
        launch.assertSilent();
    }

    @Test
    void interactiveStartupDefersAValuelessResumeToThePicker() {
        Launch launch = new Launch(true);

        CliSessionRestoreCoordinator.Restoration restoration =
            CliSessionRestoreCoordinator.restore(launch.request(), new Target.Valueless());

        assertTrue(restoration.pickerRequested());
        assertFalse(restoration.restored());
        assertNull(restoration.sessionId());
        assertNull(restoration.pickerSearchTerm(), "a bare -r seeds no search term");
        launch.assertSilent();
    }

    /**
     * The picker is a REPL surface.
     */
    @Test
    void nonInteractiveStartupRejectsAValuelessResume() {
        Launch launch = new Launch(false);

        launch.assertAborts(new Target.Valueless());

        assertEquals(USAGE + "\n", launch.errors());
        assertEquals("", launch.notices());
    }

    /**
     * An unresolvable title is not fatal interactively.
     */
    @Test
    void anUnknownTitleSeedsThePickersSearchBoxInsteadOfFailing() {
        Launch launch = new Launch(true);

        CliSessionRestoreCoordinator.Restoration restoration =
            CliSessionRestoreCoordinator.restore(
                launch.request(), new Target.UnknownTitle("nightly run"));

        assertTrue(restoration.pickerRequested());
        assertEquals("nightly run", restoration.pickerSearchTerm());
        launch.assertSilent();
    }

    @Test
    void headlessNamesTheValueItCouldNeitherParseNorMatch() {
        Launch launch = new Launch(false);

        launch.assertAborts(new Target.UnknownTitle("nightly run"));

        assertEquals(USAGE + ". Provided value \"nightly run\" is not a UUID and does not match "
            + "any session title.\n", launch.errors());
    }

    @Test
    void anAmbiguousTitleAlsoReachesThePickerCarryingItsQuery() {
        Launch launch = new Launch(true);

        CliSessionRestoreCoordinator.Restoration restoration =
            CliSessionRestoreCoordinator.restore(launch.request(), ambiguous());

        assertTrue(restoration.pickerRequested());
        assertEquals("nightly run", restoration.pickerSearchTerm());
        launch.assertSilent();
    }

    /** Headless has no picker, so the candidate ids themselves are the only way forward. */
    @Test
    void headlessListsEveryCandidateOfAnAmbiguousTitleWithItsModifiedTime() {
        Launch launch = new Launch(false);

        launch.assertAborts(ambiguous());

        assertEquals("""
            Error: --resume "nightly run" matches 2 sessions. Pass one of these session IDs to \
            disambiguate:
              11111111-1111-4111-8111-111111111111  (modified 2026-01-02T03:04:05.678Z)
              22222222-2222-4222-8222-222222222222  (modified 2026-01-01T00:00:00.000Z)
            """, launch.errors());
    }

    /**
     * A well-formed UUID with no log behind it is fatal in both modes — the picker is not a
     * fallback for it, because the user named one specific session. Interactive startup keeps
     * the notice on stdout.
     */
    @Test
    void aMissingSessionIdAbortsInBothModesOnTheirRespectiveChannels() {
        String expected = "No conversation found with session ID: " + ALPHA + "\n";

        Launch interactive = new Launch(true);
        interactive.assertAborts(new Target.MissingSessionId(ALPHA));
        assertEquals(expected, interactive.notices());
        assertEquals("", interactive.errors());

        Launch headless = new Launch(false);
        headless.assertAborts(new Target.MissingSessionId(ALPHA));
        assertEquals(expected, headless.errors());
        assertEquals("", headless.notices());
    }

    @Test
    void anUnreadableTranscriptFileAbortsInBothModesOnTheirRespectiveChannels() {
        String expected = "Unable to load transcript from file: /tmp/gone.jsonl\n";

        Launch interactive = new Launch(true);
        interactive.assertAborts(new Target.UnreadableTranscriptFile("/tmp/gone.jsonl"));
        assertEquals(expected, interactive.notices());
        assertEquals("", interactive.errors());

        Launch headless = new Launch(false);
        headless.assertAborts(new Target.UnreadableTranscriptFile("/tmp/gone.jsonl"));
        assertEquals(expected, headless.errors());
        assertEquals("", headless.notices());
    }

    /**
     * A transcript named outright needs neither the picker nor the title search, so.
     */
    @Test
    void aNamedTranscriptIsLoadedInBothModesRatherThanRejectedHeadlessly() throws Exception {
        Path transcript = transcripts.resolve(ALPHA + ".jsonl");
        Files.writeString(transcript, "");
        Target target = new Target.TranscriptFile(transcript, ALPHA, transcript.toString());

        for (boolean interactiveStartup : List.of(true, false)) {
            Launch launch = new Launch(interactiveStartup);
            assertThrows(NullPointerException.class,
                () -> CliSessionRestoreCoordinator.restore(launch.request(), target),
                "the transcript must reach the loader instead of being reported and refused");
            assertEquals("", launch.errors());
            assertEquals("", launch.notices());
        }
    }

    private static Target ambiguous() {
        return new Target.AmbiguousTitle("nightly run", List.of(
            new CliResumeTargetResolver.TitleMatch(
                ALPHA, Instant.parse("2026-01-02T03:04:05.678Z")),
            new CliResumeTargetResolver.TitleMatch(
                BETA, Instant.parse("2026-01-01T00:00:00Z"))));
    }

    /** One launch's two output channels, so every assertion can tell them apart. */
    private static final class Launch {
        private final StringWriter notices = new StringWriter();
        private final StringWriter errors = new StringWriter();
        private final CliSessionRestoreCoordinator.Request request;

        Launch(boolean interactiveStartup) {
            this.request = new CliSessionRestoreCoordinator.Request(
                null, "/tmp", null, false, false, null,
                !interactiveStartup, interactiveStartup,
                null, "text", null, CliOutput.borrowed(new PrintWriter(notices, true)),
                CliOutput.borrowed(new PrintWriter(errors, true)));
        }

        CliSessionRestoreCoordinator.Request request() {
            return request;
        }

        String notices() {
            return notices.toString();
        }

        String errors() {
            return errors.toString();
        }

        void assertSilent() {
            assertEquals("", notices(), "a resolvable launch reports nothing");
            assertEquals("", errors(), "a resolvable launch reports nothing");
        }

        void assertAborts(Target target) {
            CliLaunchAbort abort = assertThrows(CliLaunchAbort.class,
                () -> CliSessionRestoreCoordinator.restore(request, target));
            assertEquals(1, abort.exitCode());
        }
    }

    @Test
    void printRecoveryIsDeferredOnlyWhenThereIsAnImmediatePrompt() {
        assertTrue(CliSessionRestoreCoordinator.shouldDeferRecoveryTranscript(true, "prompt"));
        assertFalse(CliSessionRestoreCoordinator.shouldDeferRecoveryTranscript(true, null));
        assertFalse(CliSessionRestoreCoordinator.shouldDeferRecoveryTranscript(false, "prompt"));
        assertTrue(CliSessionRestoreCoordinator.shouldPersistRecoveryTranscript(true, false));
        assertFalse(CliSessionRestoreCoordinator.shouldPersistRecoveryTranscript(true, true));
        assertFalse(CliSessionRestoreCoordinator.shouldPersistRecoveryTranscript(false, false));
    }

    @Test
    void noticesNeverContaminateMachineOutput() {
        StringWriter print = new StringWriter();
        CliSessionRestoreCoordinator.writeSessionRestoreNotice(
            CliOutput.borrowed(new PrintWriter(print, true)), true, "text", "restored");
        assertEquals("", print.toString());

        StringWriter stream = new StringWriter();
        CliSessionRestoreCoordinator.writeSessionRestoreNotice(
            CliOutput.borrowed(new PrintWriter(stream, true)), false, "stream-json", "restored");
        assertEquals("", stream.toString());
    }

    @Test
    void restorationSwitchesIdentityAndSlugBeforeLoadingMessages() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/claudecode/cli/CliSessionRestoreCoordinator.java"));

        assertInOrder(source,
            "restorer.preSwitch();",
            "request.engine().conversation().switchToSession(sessionId);",
            "request.transcriptRecorder().restoreSessionSlug(sessionId, bundle.slug());",
            "request.transcriptRecorder().restoreSessionMetadata(sessionId, messages, bundle);",
            "restoreToolResultBudget(",
            "request.engine().conversation().loadMessages(messages);",
            "restorer.postSwitch(",
            "CostStatePersistence.restoreForSession(sessionId, Path.of(request.cwd()));");
    }

    @Test
    void toolResultBudgetRestoreUsesTheActiveQueryKey() {
        RecordingToolExecutor tools = new RecordingToolExecutor();
        StreamingClient client = new StreamingClient() {
            @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                return Collections.emptyIterator();
            }

            @Override public String getModel() {
                return "test-model";
            }
        };
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(client)
            .toolExecutor(tools)
            .workingDirectory("/workspace")
            .agentId("agent-1")
            .build());
        engine.switchToSession(ALPHA);
        List<Message> messages = List.of(
            new UserMessage("user-1", MessageContent.ofText("hello")));
        List<ToolResultBudget.Replacement> replacements = List.of(
            new ToolResultBudget.Replacement("tool-1", "preview"));

        CliSessionRestoreCoordinator.restoreToolResultBudget(
            engine, ALPHA, messages, replacements);

        assertEquals(messages, tools.messages);
        assertEquals(replacements, tools.replacements);
        assertEquals(ALPHA, tools.sessionId);
        assertEquals("/workspace", tools.workingDirectory);
        assertEquals("agent-1", tools.agentId);
    }

    @Test
    void startupRestoreAndUiMetadataFollowUpReadTranscriptOnlyOnce() throws Exception {
        Path transcript = writeRichTranscript(transcripts.resolve(ALPHA + ".jsonl"), ALPHA);
        RecordingToolExecutor tools = new RecordingToolExecutor();
        DefaultQuerySession engine = newTestEngine(tools);
        TranscriptRecorder recorder = new TranscriptRecorder(
            new SessionManager(transcripts.toString()), new SessionStorage());
        CliOutput silent = CliOutput.borrowed(new PrintWriter(new StringWriter(), true));
        CliSessionRestoreCoordinator.Request request = new CliSessionRestoreCoordinator.Request(
            engine, transcripts.toString(), ALPHA, false, false, null, false, true,
            null, null, recorder, silent, silent);
        CliInteractiveSessionAdapter uiAdapter = new CliInteractiveSessionAdapter();

        try (TranscriptReadCounter.Scope reads = TranscriptReadCounter.track()) {
            CliSessionRestoreCoordinator.Restoration result =
                CliSessionRestoreCoordinator.restore(request, new Target.Session(ALPHA, transcript));
            assertTrue(result.restored());

            // Simulate ReplStartupPreparation.loadSessionMetadata() immediately after restore.
            var uiMeta = uiAdapter.scanMetadata(transcript);
            assertEquals("Rich Title", uiMeta.customTitle());
            assertEquals("v1-tag", uiMeta.tag());

            assertEquals(1, reads.countFor(transcript),
                () -> "Startup restore + UI metadata follow-up must read transcript once, got: "
                    + reads.snapshot());
        }
        assertEquals(150L, engine.execution().getTotalUsage().inputTokens());
        assertEquals(45L, engine.execution().getTotalUsage().outputTokens());
    }

    @Test
    void interactiveResumeLifecycleAndMetadataFollowUpReadTranscriptOnlyOnce() throws Exception {
        Path transcript = writeRichTranscript(transcripts.resolve(BETA + ".jsonl"), BETA);
        RecordingToolExecutor tools = new RecordingToolExecutor();
        DefaultQuerySession engine = newTestEngine(tools);
        SessionLifecycle lifecycle = CliSessionRestoreCoordinator.newSessionLifecycle(
            engine, null, null, null);
        CliInteractiveSessionAdapter uiAdapter = new CliInteractiveSessionAdapter();

        try (TranscriptReadCounter.Scope reads = TranscriptReadCounter.track()) {
            PreparedSessionResume prepared = lifecycle.prepare(
                new SessionResumeRequest(BETA, transcript, transcripts.toString()),
                transcripts.toString());
            lifecycle.activate(prepared, _ -> { });

            // Simulate SessionController.refreshAfterResume() scanning metadata right after switch.
            var uiMeta = uiAdapter.scanMetadata(transcript);
            assertEquals("Rich Title", uiMeta.customTitle());
            assertEquals("v1-tag", uiMeta.tag());

            assertEquals(1, reads.countFor(transcript),
                () -> "Interactive /resume switch + UI metadata follow-up must read transcript once, got: "
                    + reads.snapshot());
        }
        assertEquals(150L, engine.execution().getTotalUsage().inputTokens());
        assertEquals(45L, engine.execution().getTotalUsage().outputTokens());
    }

    @Test
    void metadataCacheInvalidatesWhenTranscriptChangesOnDisk() throws Exception {
        Path transcript = writeRichTranscript(transcripts.resolve("cache-invalidation.jsonl"), ALPHA);
        CliInteractiveSessionAdapter uiAdapter = new CliInteractiveSessionAdapter();

        try (TranscriptReadCounter.Scope reads = TranscriptReadCounter.track()) {
            assertEquals("Rich Title", uiAdapter.scanMetadata(transcript).customTitle());
            assertEquals("Rich Title", uiAdapter.scanMetadata(transcript).customTitle());
            assertEquals(1, reads.countFor(transcript), "Second read on unchanged file should hit cache");

            Files.writeString(transcript,
                "{\"type\":\"custom-title\",\"sessionId\":\"" + ALPHA + "\",\"customTitle\":\"Updated Title\"}\n",
                StandardOpenOption.APPEND);
            assertEquals("Updated Title", uiAdapter.scanMetadata(transcript).customTitle());
            assertEquals(2, reads.countFor(transcript), "Appended file must invalidate cache and re-scan");
        }
    }

    private DefaultQuerySession newTestEngine(RecordingToolExecutor tools) {
        StreamingClient client = new StreamingClient() {
            @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                return Collections.emptyIterator();
            }

            @Override public String getModel() {
                return "test-model";
            }
        };
        return new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(client)
            .toolExecutor(tools)
            .workingDirectory(transcripts.toString())
            .build());
    }

    private static Path writeRichTranscript(Path transcript, String sessionId) throws Exception {
        String content = String.join("\n",
            "{\"type\":\"user\",\"uuid\":\"u-1\",\"sessionId\":\"" + sessionId
                + "\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"message\":{\"role\":\"user\",\"content\":\"hello\"}}",
            "{\"type\":\"assistant\",\"uuid\":\"a-1\",\"parentUuid\":\"u-1\",\"sessionId\":\"" + sessionId
                + "\",\"timestamp\":\"2026-01-01T00:00:01Z\",\"slug\":\"plan-slug\","
                + "\"message\":{\"model\":\"claude-sonnet\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],\"stop_reason\":\"end_turn\","
                + "\"usage\":{\"input_tokens\":150,\"output_tokens\":45,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0}}}",
            "{\"type\":\"content-replacement\",\"sessionId\":\"" + sessionId
                + "\",\"replacements\":[{\"kind\":\"tool-result\",\"toolUseId\":\"tu-1\",\"replacement\":\"[replaced]\"}]}",
            "{\"type\":\"file-history-snapshot\",\"messageId\":\"u-1\","
                + "\"snapshot\":{\"messageId\":\"u-1\",\"trackedFileBackups\":{},\"timestamp\":\"2026-01-01T00:00:00Z\"},\"isSnapshotUpdate\":false}",
            "{\"type\":\"mode\",\"sessionId\":\"" + sessionId + "\",\"mode\":\"normal\"}",
            "{\"type\":\"permission-mode\",\"sessionId\":\"" + sessionId + "\",\"permissionMode\":\"default\"}",
            "{\"type\":\"custom-title\",\"sessionId\":\"" + sessionId + "\",\"customTitle\":\"Rich Title\"}",
            "{\"type\":\"tag\",\"sessionId\":\"" + sessionId + "\",\"tag\":\"v1-tag\"}",
            "{\"type\":\"last-prompt\",\"sessionId\":\"" + sessionId + "\",\"lastPrompt\":\"hello\",\"leafUuid\":\"a-1\"}"
        ) + "\n";
        Files.writeString(transcript, content);
        return transcript;
    }

    private static final class RecordingToolExecutor implements ToolExecutor {
        private List<Message> messages;
        private List<ToolResultBudget.Replacement> replacements;
        private String sessionId;
        private String workingDirectory;
        private String agentId;

        @Override
        public ToolResult execute(
                String toolName, JsonNode input, ToolExecutionContext context) {
            return null;
        }

        @Override
        public void restoreToolResultBudget(
                List<Message> messages,
                List<ToolResultBudget.Replacement> replacements,
                String sessionId,
                String workingDirectory,
                String agentId) {
            this.messages = messages;
            this.replacements = replacements;
            this.sessionId = sessionId;
            this.workingDirectory = workingDirectory;
            this.agentId = agentId;
        }
    }

    private static void assertInOrder(String source, String... fragments) {
        int previous = -1;
        for (String fragment : fragments) {
            int current = source.indexOf(fragment);
            assertTrue(current > previous, () -> "restoration operation out of order: " + fragment);
            previous = current;
        }
    }
}
