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
import com.fasterxml.jackson.databind.JsonNode;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import com.claudecode.session.SessionInfo;
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
            "request.transcriptRecorder().restoreSessionSlug(sessionId);",
            "request.transcriptRecorder().restoreSessionMetadata(sessionId, messages);",
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
