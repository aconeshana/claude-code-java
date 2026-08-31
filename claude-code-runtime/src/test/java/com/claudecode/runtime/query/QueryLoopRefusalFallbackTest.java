package com.claudecode.runtime.query;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.engine.MessageCompactor;
import com.claudecode.core.engine.RefusalFallbackPrompt;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.SubmitOptions;
import com.claudecode.core.engine.TranscriptSink;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.RefusalFallbackAnnouncement;
import com.claudecode.core.message.RefusalFallbackDecision;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.StopDetails;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.model.ModelCatalog;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.runtime.session.MessagesDeserializer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A turn the model's own safeguards refused is replayed against another model
 * rather than handed back to the user as a dead end.
 *
 * <ul>
 *   <li>the {@code fallback_request} branch: the refused
 *       rows are tombstoned, the loop model becomes the refusal fallback target,
 *       the turn is retried, and — on the main thread only — an announcement row
 *       names both the fallback and the wire uuids that were withdrawn.</li>
 *   <li>the ordering of
 *       {@code Ida}'s suppression reasons, which is what makes a subagent and a
 *       host that cannot ask both retry silently.</li>
 * </ul>
 *
 * <p>Only the models whose safeguards do the flagging have a target, so these
 * tests start from the fable family; a refusal from any other model has nowhere
 * to go and ends the turn.
 */
class QueryLoopRefusalFallbackTest {

    private static final String FLAGGING_MODEL = ModelCatalog.FABLE.modelId();

    /** Records which model each request asked for, in order. */
    private static final class RecordingDeps implements QueryDeps {
        private final List<String> requestedModels = new ArrayList<>();
        private final List<List<StreamingClient.StreamingEvent>> remaining;

        private RecordingDeps(List<List<StreamingClient.StreamingEvent>> attempts) {
            this.remaining = new ArrayList<>(attempts);
        }

        @Override
        public Iterator<StreamingClient.StreamingEvent> callModel(
                StreamingClient.StreamRequest request) {
            requestedModels.add(request.model());
            if (remaining.isEmpty()) throw new AssertionError("unexpected extra request");
            return remaining.removeFirst().iterator();
        }

        @Override
        public MessageCompactor.MicrocompactResult microcompact(List<Message> messages) {
            return new MessageCompactor.MicrocompactResult(messages);
        }

        @Override
        public boolean shouldAutoCompact(List<Message> messages, String model,
                                         String querySource) {
            return false;
        }

        @Override
        public QueryDeps.AutoCompactResult autocompact(
                List<Message> messages, String model, String querySource,
                AutoCompactTrackingState tracking, String customInstructions,
                long snipTokensFreed) {
            return new QueryDeps.AutoCompactResult(null, null);
        }

        @Override
        public String uuid() {
            return UUID.randomUUID().toString();
        }
    }

    private static List<StreamingClient.StreamingEvent> turn(String id, String text,
                                                             String stopReason,
                                                             StopDetails stopDetails) {
        return turn(id, text, stopReason, stopDetails, null);
    }

    private static List<StreamingClient.StreamingEvent> turn(String id, String text,
                                                             String stopReason,
                                                             StopDetails stopDetails,
                                                             String requestId) {
        return List.of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                id, FLAGGING_MODEL, List.of(), new Usage(10, 0, 0, 0), null, requestId),
            new StreamingClient.StreamingEvent.ContentBlockStartEvent(0, "text", null, null),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", text),
            new StreamingClient.StreamingEvent.ContentBlockStopEvent(0),
            new StreamingClient.StreamingEvent.MessageDeltaEvent(
                stopReason, null, new Usage(0, 5, 0, 0), null, stopDetails),
            new StreamingClient.StreamingEvent.MessageStopEvent());
    }

    private static List<StreamingClient.StreamingEvent> refused(String id, String text) {
        return turn(id, text, "refusal", new StopDetails("cyber", "Flagged for review."));
    }

    private static List<StreamingClient.StreamingEvent> endTurn(String id, String text) {
        return turn(id, text, "end_turn", null);
    }

    private static List<StreamingClient.StreamingEvent> refusedToolUse(String id) {
        return List.of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                id, FLAGGING_MODEL, List.of(), new Usage(10, 0, 0, 0)),
            new StreamingClient.StreamingEvent.ContentBlockStartEvent(
                0, "tool_use", "toolu-refused", "Bash"),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(
                0, "input_json_delta", "{\"command\":\"pwd\"}"),
            new StreamingClient.StreamingEvent.ContentBlockStopEvent(0),
            new StreamingClient.StreamingEvent.MessageDeltaEvent(
                "refusal", null, new Usage(0, 5, 0, 0), null,
                new StopDetails("cyber", "Flagged for review.")),
            new StreamingClient.StreamingEvent.MessageStopEvent());
    }

    private static DefaultQuerySession session(String model, String agentId) {
        return new DefaultQuerySession(QuerySessionSpec.builder()
            // Required by the spec but never consulted: every request in this test
            // goes through the QueryDeps.callModel seam.
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingClient.StreamingEvent> createStream(
                        StreamingClient.StreamRequest request) {
                    throw new AssertionError("requests must go through QueryDeps");
                }

                @Override
                public String getModel() {
                    return model;
                }
            })
            .systemPrompt("Be helpful")
            .model(model)
            .agentId(agentId)
            .build());
    }

    private static List<SDKMessage> run(DefaultQuerySession engine, RecordingDeps deps) {
        return run(engine, deps, List.of(new UserMessage(
            UUID.randomUUID().toString(), MessageContent.ofText("Hi there"))));
    }

    private static List<SDKMessage> run(DefaultQuerySession engine, RecordingDeps deps,
                                        List<Message> history) {
        return run(engine, deps, history, SubmitOptions.DEFAULT, engine.getConfig().model());
    }

    private static List<SDKMessage> run(DefaultQuerySession engine, RecordingDeps deps,
                                        List<Message> history, SubmitOptions options,
                                        String turnModel) {
        engine.getMutableMessages().addAll(history);
        QueryParams params = QueryParams.builder()
            .messages(history)
            .systemPrompt("Be helpful")
            .model(turnModel)
            .querySource("user")
            .deps(deps)
            .build();
        List<SDKMessage> drained = new ArrayList<>();
        Iterator<SDKMessage> iter = new QueryLoop(engine, params, null, options);
        while (iter.hasNext()) drained.add(iter.next());
        return drained;
    }

    private static SystemMessage announcement(List<SDKMessage> messages) {
        return messages.stream()
            .filter(SDKMessage.System.class::isInstance)
            .map(m -> ((SDKMessage.System) m).message())
            .filter(m -> Strings.CS.equals("model_refusal_fallback", m.subtype()))
            .findFirst()
            .orElse(null);
    }

    private static List<String> retractedUuids(List<SDKMessage> messages) {
        return messages.stream()
            .filter(SDKMessage.Tombstone.class::isInstance)
            .map(m -> ((SDKMessage.Tombstone) m).replacedUuid())
            .toList();
    }

    @Test
    void aRefusalIsReplayedOnTheOpusFamilyAndTheRefusedRowsAreWithdrawn() {
        DefaultQuerySession engine = session(FLAGGING_MODEL, null);
        RecordingDeps deps = new RecordingDeps(List.of(
            refused("msg-1", "I can't help with that"),
            endTurn("msg-2", "The retried answer")));

        List<SDKMessage> messages = run(engine, deps);

        assertEquals(List.of(FLAGGING_MODEL, ModelCatalog.OPUS.modelId()),
            deps.requestedModels, "the retry asks the opus family");
        String refusedUuid = messages.stream()
            .filter(SDKMessage.Assistant.class::isInstance)
            .map(m -> ((SDKMessage.Assistant) m).message().uuid())
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected the refused assistant block"));
        assertEquals(List.of(refusedUuid), retractedUuids(messages),
            "the refused row is withdrawn exactly once: " + messages);
        boolean stranded = engine.getMutableMessages().stream()
            .anyMatch(m -> m instanceof AssistantMessage assistant
                && assistant.message().content().stream()
                    .anyMatch(b -> Strings.CS.contains(b.toString(), "I can't help with that")));
        assertFalse(stranded, "the refused row must not be resent: "
            + engine.getMutableMessages());
    }

    @Test
    void aRefusedToolUseIsRetractedBeforeAnyToolExecutionCanStart() {
        DefaultQuerySession engine = session(FLAGGING_MODEL, null);
        RecordingDeps deps = new RecordingDeps(List.of(
            refusedToolUse("msg-tool-refused"),
            endTurn("msg-2", "The retried answer")));

        List<SDKMessage> messages = run(engine, deps);

        assertEquals(List.of(FLAGGING_MODEL, ModelCatalog.OPUS.modelId()),
            deps.requestedModels);
        assertEquals(1, retractedUuids(messages).size(),
            "the streamed tool_use assistant row is removed as one logical message");
        assertTrue(engine.getMutableMessages().stream()
            .noneMatch(message -> message instanceof UserMessage user
                && user.message().blocks().stream()
                    .anyMatch(ToolResultBlock.class::isInstance)),
            "QueryLoop handles refusal before ToolRunner.run, so no tool result can exist");
    }

    @Test
    void aMainThreadRefusalMakesTheFallbackModelStickyForTheSession() {
        DefaultQuerySession engine = session(FLAGGING_MODEL, null);
        RecordingDeps deps = new RecordingDeps(List.of(
            refused("msg-1", "I can't help with that"),
            endTurn("msg-2", "The retried answer")));

        run(engine, deps);

        assertEquals(ModelCatalog.OPUS.modelId(), engine.getConfig().model());
        assertNull(engine.getConfig().modelPreference(),
            "the fallback writer must not replace the user's model-picker setting");

        RecordingDeps nextTurn = new RecordingDeps(List.of(
            endTurn("msg-3", "The next answer")));
        run(engine, nextTurn);
        assertEquals(List.of(ModelCatalog.OPUS.modelId()), nextTurn.requestedModels,
            "the next turn starts on the session fallback model without another refusal");
    }

    @Test
    void aSubagentRefusalDoesNotChangeTheParentSessionModel() {
        DefaultQuerySession engine = session(FLAGGING_MODEL, "agent-1");
        RecordingDeps deps = new RecordingDeps(List.of(
            refused("msg-1", "I can't help with that"),
            endTurn("msg-2", "The retried answer")));

        run(engine, deps);

        assertEquals(FLAGGING_MODEL, engine.getConfig().model());
    }

    @Test
    void aOneTurnModelOverrideDoesNotReplaceTheSessionPreference() {
        DefaultQuerySession engine = session(ModelCatalog.SONNET.modelId(), null);
        RecordingDeps deps = new RecordingDeps(List.of(
            refused("msg-1", "I can't help with that"),
            endTurn("msg-2", "The retried answer")));
        SubmitOptions options = SubmitOptions.DEFAULT.withPromptOverrides(FLAGGING_MODEL, null);

        run(engine, deps, List.of(new UserMessage(
            UUID.randomUUID().toString(), MessageContent.ofText("Hi there"))),
            options, FLAGGING_MODEL);

        assertEquals(ModelCatalog.SONNET.modelId(), engine.getConfig().model());
        assertEquals(List.of(FLAGGING_MODEL, ModelCatalog.OPUS.modelId()),
            deps.requestedModels);
    }

    @Test
    void aRefusalFromAModelWithNoFallbackTargetEndsTheTurn() {
        DefaultQuerySession engine = session(ModelCatalog.OPUS.modelId(), null);
        RecordingDeps deps = new RecordingDeps(List.of(
            refused("msg-1", "I can't help with that")));

        List<SDKMessage> messages = run(engine, deps);

        assertEquals(List.of(ModelCatalog.OPUS.modelId()), deps.requestedModels,
            "opus is not a flagging source, so there is nothing to retry on");
        assertTrue(retractedUuids(messages).isEmpty(),
            "with no retry the rows stay where they are: " + messages);
    }

    @Test
    void aRefusalWithNowhereToGoLeavesTheRefusalErrorLineInTheTranscript() {
        DefaultQuerySession engine = session(ModelCatalog.OPUS.modelId(), null);
        RecordingDeps deps = new RecordingDeps(List.of(
            refused("msg-1", "I can't help with that")));

        List<SDKMessage> messages = run(engine, deps);

        SystemMessage noFallback = messages.stream()
            .filter(SDKMessage.System.class::isInstance)
            .map(message -> ((SDKMessage.System) message).message())
            .filter(message -> Strings.CS.equals(
                "model_refusal_no_fallback", message.subtype()))
            .findFirst()
            .orElseThrow();
        assertEquals(ModelCatalog.OPUS.modelId(), noFallback.originalModel());
        assertEquals("cyber", noFallback.apiRefusalCategory());

        AssistantMessage errorRow = engine.getMutableMessages().stream()
            .filter(AssistantMessage.class::isInstance)
            .map(AssistantMessage.class::cast)
            .filter(AssistantMessage::isApiErrorMessage)
            .reduce((_, second) -> second)
            .orElseThrow(() -> new AssertionError(
                "expected a refusal error row: " + engine.getMutableMessages()));

        assertEquals("refusal", errorRow.message().stopReason());
        assertEquals(new StopDetails("cyber", "Flagged for review."),
            errorRow.message().stopDetails());
        String text = ((TextBlock) errorRow.message().content().getFirst()).text();
        assertEquals("""
            API Error: Opus 5's safeguards flagged this message for a cybersecurity \
            topic. If your work requires this access, you can apply for an exemption: \
            https://claude.com/form/cyber-use-case
            Send feedback with /feedback or learn more: \
            https://support.claude.com/en/articles/15363606""", text);
    }

    @Test
    void aRefusalErrorCarriesTheRealHttpRequestId() {
        DefaultQuerySession engine = session(ModelCatalog.OPUS.modelId(), null);
        RecordingDeps deps = new RecordingDeps(List.of(turn(
            "msg-1", "I can't help with that", "refusal",
            new StopDetails("other", "Flagged for review."), "req_refusal_197")));

        run(engine, deps);

        AssistantMessage errorRow = engine.getMutableMessages().stream()
            .filter(AssistantMessage.class::isInstance)
            .map(AssistantMessage.class::cast)
            .filter(AssistantMessage::isApiErrorMessage)
            .reduce((_, second) -> second)
            .orElseThrow();
        assertEquals("req_refusal_197", errorRow.requestId());
        assertTrue(Strings.CS.endsWith(
            ((TextBlock) errorRow.message().content().getFirst()).text(),
            "Request ID: req_refusal_197"));
    }

    @Test
    void aSubagentRetriesWithoutAskingEvenWithTheSettingTurnedOff() {
        DefaultQuerySession engine = session(FLAGGING_MODEL, "agent-1");
        engine.getConfig().setSwitchModelsOnFlag(false);
        RecordingDeps deps = new RecordingDeps(List.of(
            refused("msg-1", "I can't help with that"),
            endTurn("msg-2", "The retried answer")));

        run(engine, deps);

        assertEquals(List.of(FLAGGING_MODEL, ModelCatalog.OPUS.modelId()),
            deps.requestedModels,
            "a subagent has no user to ask, so the setting never comes up");
    }

    @Test
    void aMainThreadTurnRetriesWhileNoHostCanCarryTheQuestion() {
        DefaultQuerySession engine = session(FLAGGING_MODEL, null);
        engine.getConfig().setSwitchModelsOnFlag(false);
        RecordingDeps deps = new RecordingDeps(List.of(
            refused("msg-1", "I can't help with that"),
            endTurn("msg-2", "The retried answer")));

        run(engine, deps);

        assertEquals(List.of(FLAGGING_MODEL, ModelCatalog.OPUS.modelId()),
            deps.requestedModels,
            "no dialog port exists yet, which released also resolves to the retry");
    }

    @Test
    void theAnnouncementNamesTheNewModelTheWithdrawnRowsAndTheRefusedTurn() {
        DefaultQuerySession engine = session(FLAGGING_MODEL, null);
        RecordingDeps deps = new RecordingDeps(List.of(
            turn("msg-1", "I can't help with that", "refusal",
                new StopDetails("cyber", "Flagged for review."), "req-announcement-197"),
            endTurn("msg-2", "The retried answer")));
        UserMessage asked = new UserMessage("user-asked", MessageContent.ofText("Hi there"));

        List<SDKMessage> messages = run(engine, deps, List.of(asked));

        SystemMessage row = announcement(messages);
        assertNotNull(row, "the main thread is told its turn moved: " + messages);
        assertEquals("warning", row.level());
        assertEquals(RefusalFallbackAnnouncement.text(
            FLAGGING_MODEL, ModelCatalog.OPUS.modelId(), "cyber"), row.content());
        assertEquals(retractedUuids(messages), row.retractedMessageUuids(),
            "every row the stream took back is named, and only those");
        assertEquals("user-asked", row.refusedUserMessageUuid());
        assertEquals("retry", row.direction());
        assertEquals("refusal", row.trigger());
        assertEquals(FLAGGING_MODEL, row.originalModel());
        assertEquals(ModelCatalog.OPUS.modelId(), row.fallbackModel());
        assertEquals("req-announcement-197", row.requestId());
        assertEquals("cyber", row.apiRefusalCategory());
        assertEquals("Flagged for review.", row.apiRefusalExplanation());
    }

    @Test
    void aSubagentSwitchesModelsWithoutTellingAnybody() {
        DefaultQuerySession engine = session(FLAGGING_MODEL, "agent-1");
        RecordingDeps deps = new RecordingDeps(List.of(
            refused("msg-1", "I can't help with that"),
            endTurn("msg-2", "The retried answer")));

        List<SDKMessage> messages = run(engine, deps);

        assertNull(announcement(messages),
            "a sidechain has no banner, so its transcript can hold no retraction");
    }

    @Test
    void aResumedSessionDropsTheRefusedRowsTheAnnouncementTookBack() {
        DefaultQuerySession engine = session(FLAGGING_MODEL, null);
        RecordingTranscript transcript = new RecordingTranscript();
        engine.setTranscriptSink(transcript);
        RecordingDeps deps = new RecordingDeps(List.of(
            refused("msg-1", "I can't help with that"),
            endTurn("msg-2", "The retried answer")));

        run(engine, deps, List.of(
            new UserMessage("user-asked", MessageContent.ofText("Hi there"))));

        assertTrue(transcript.recorded.stream().anyMatch(m -> m instanceof AssistantMessage a
                && Strings.CS.contains(a.message().content().toString(),
                    "I can't help with that")),
            "the refused row reached disk before the refusal was handled, which is "
                + "the whole reason the announcement has to name it");

        List<Message> resumed = MessagesDeserializer.deserialize(transcript.recorded);

        assertFalse(resumed.stream().anyMatch(m -> m instanceof AssistantMessage a
                && Strings.CS.contains(a.message().content().toString(),
                    "I can't help with that")),
            "the ghost must not come back on resume: " + resumed);
        assertTrue(resumed.stream().anyMatch(m -> m instanceof SystemMessage s
                && Strings.CS.equals("model_refusal_fallback", s.subtype())),
            "the announcement itself survives its own retraction list: " + resumed);
        assertTrue(resumed.stream().anyMatch(m -> m instanceof AssistantMessage a
                && Strings.CS.contains(a.message().content().toString(),
                    "The retried answer")),
            "the answer the retry produced is what the user resumes into: " + resumed);
    }

    @Test
    void theUserIsAskedOnlyOnceTheyHaveTurnedTheSilentSwitchOff() {
        DefaultQuerySession engine = session(FLAGGING_MODEL, null);
        engine.getConfig().setSwitchModelsOnFlag(false);
        RecordingPrompt prompt = new RecordingPrompt(
            RefusalFallbackDecision.Choice.RETRY_FALLBACK);
        engine.setRefusalFallbackPrompt(prompt);
        RecordingDeps deps = new RecordingDeps(List.of(
            refused("msg-1", "I can't help with that"),
            endTurn("msg-2", "The retried answer")));

        List<SDKMessage> messages = run(engine, deps);

        assertEquals(1, prompt.asked.size(), "the question is put exactly once");
        RefusalFallbackPrompt.Request asked = prompt.asked.getFirst();
        assertEquals(FLAGGING_MODEL, asked.refusedModel());
        assertEquals(ModelCatalog.OPUS.modelId(), asked.fallbackModel());
        assertEquals("cyber", asked.category());
        assertNull(asked.guidanceText(),
            "a first-party deployment has its fallback mapping built in");
        assertEquals(retractedUuids(messages), asked.retractedMessageUuids(),
            "the dialog is told which rows answering it will cost");
        assertEquals(List.of(FLAGGING_MODEL, ModelCatalog.OPUS.modelId()),
            deps.requestedModels, "answering with the switch retries as before");
    }

    @Test
    void anSdkHostWithoutTheDialogCapabilityCancelsWhenTheSettingIsOff() {
        DefaultQuerySession engine = session(FLAGGING_MODEL, null);
        engine.getConfig().setSwitchModelsOnFlag(false);
        engine.setRefusalFallbackPrompt(new UnsupportedPrompt());
        RecordingDeps deps = new RecordingDeps(List.of(
            refused("msg-1", "I can't help with that")));

        List<SDKMessage> messages = run(engine, deps);

        assertEquals(List.of(FLAGGING_MODEL), deps.requestedModels,
            "an SDK consumer that did not declare the dialog kind cannot consent to retry");
        assertEquals(1, retractedUuids(messages).size());
        assertTrue(engine.getMutableMessages().stream()
            .anyMatch(message -> message instanceof AssistantMessage assistant
                && assistant.isApiErrorMessage()));
    }

    @Test
    void aHostThatCanAskIsStillNotAskedWhileTheSilentSwitchIsOn() {
        DefaultQuerySession engine = session(FLAGGING_MODEL, null);
        RecordingPrompt prompt = new RecordingPrompt(
            RefusalFallbackDecision.Choice.CANCELLED);
        engine.setRefusalFallbackPrompt(prompt);
        RecordingDeps deps = new RecordingDeps(List.of(
            refused("msg-1", "I can't help with that"),
            endTurn("msg-2", "The retried answer")));

        run(engine, deps);

        assertTrue(prompt.asked.isEmpty(),
            "the shipped default switches models without interrupting anyone");
        assertEquals(List.of(FLAGGING_MODEL, ModelCatalog.OPUS.modelId()),
            deps.requestedModels);
    }

    @Test
    void editingThePromptHandsTheTurnBackWithoutAnythingToReadOrResend() {
        DefaultQuerySession engine = session(FLAGGING_MODEL, null);
        engine.getConfig().setSwitchModelsOnFlag(false);
        engine.setRefusalFallbackPrompt(
            new RecordingPrompt(RefusalFallbackDecision.Choice.EDIT_PROMPT));
        RecordingDeps deps = new RecordingDeps(List.of(
            refused("msg-1", "I can't help with that")));

        List<SDKMessage> messages = run(engine, deps);

        assertEquals(List.of(FLAGGING_MODEL), deps.requestedModels,
            "the user asked to rewrite the prompt, not to move models");
        assertEquals(1, retractedUuids(messages).size(),
            "the refused row is withdrawn all the same: " + messages);
        assertEquals("refusal-fallback-edit", engine.getAbortController().getReason(),
            "the REPL recognizes this reason and restores the prompt for editing");
        assertTrue(engine.getMutableMessages().stream()
                .noneMatch(m -> m instanceof AssistantMessage a && a.isApiErrorMessage()),
            "the prompt is coming back, so there is no dead end to explain: "
                + engine.getMutableMessages());
        assertFalse(Strings.CS.contains(messages.toString(), "Request interrupted"),
            "released suppresses the interruption row for this abort reason: " + messages);
    }

    @Test
    void cancellingTheDialogEndsTheTurnWithTheRefusalTheUserHasToRead() {
        DefaultQuerySession engine = session(FLAGGING_MODEL, null);
        engine.getConfig().setSwitchModelsOnFlag(false);
        engine.setRefusalFallbackPrompt(
            new RecordingPrompt(RefusalFallbackDecision.Choice.CANCELLED));
        RecordingDeps deps = new RecordingDeps(List.of(
            refused("msg-1", "I can't help with that")));

        List<SDKMessage> messages = run(engine, deps);

        assertEquals(List.of(FLAGGING_MODEL), deps.requestedModels);
        assertEquals(1, retractedUuids(messages).size(),
            "the refused row goes even though nothing replaces it: " + messages);
        assertFalse(engine.getAbortController().isAborted(),
            "declining the switch is not an interruption");
        AssistantMessage errorRow = engine.getMutableMessages().stream()
            .filter(AssistantMessage.class::isInstance)
            .map(AssistantMessage.class::cast)
            .filter(AssistantMessage::isApiErrorMessage)
            .reduce((_, second) -> second)
            .orElseThrow(() -> new AssertionError(
                "expected the refusal error row: " + engine.getMutableMessages()));
        assertEquals("refusal", errorRow.message().stopReason());
    }

    @Test
    void disablingTheLaneLeavesTheRefusedTurnShowingNothingAtAll() {
        SubprocessEnvironment.updateRuntime(
            Map.of("CLAUDE_CODE_DISABLE_REFUSAL_FALLBACK", "1"));
        try {
            DefaultQuerySession engine = session(FLAGGING_MODEL, null);
            engine.getConfig().setSwitchModelsOnFlag(false);
            RecordingPrompt prompt = new RecordingPrompt(
                RefusalFallbackDecision.Choice.RETRY_FALLBACK);
            engine.setRefusalFallbackPrompt(prompt);
            RecordingDeps deps = new RecordingDeps(List.of(
                refused("msg-1", "I can't help with that")));

            List<SDKMessage> messages = run(engine, deps);

            assertEquals(List.of(FLAGGING_MODEL), deps.requestedModels,
                "the lane is off, so the turn is not replayed anywhere");
            assertTrue(prompt.asked.isEmpty(), "and nobody is asked about it");
            assertTrue(retractedUuids(messages).isEmpty(),
                "released drops the frame with no else branch, so nothing is "
                    + "withdrawn either: " + messages);
            assertNull(announcement(messages));
            assertTrue(engine.getMutableMessages().stream()
                    .noneMatch(m -> m instanceof AssistantMessage a && a.isApiErrorMessage()),
                "the refused turn ends silently — the error row belongs to the "
                    + "no-target path: " + engine.getMutableMessages());
        } finally {
            SubprocessEnvironment.clearRuntimeOverrides();
        }
    }

    @Test
    void disablingTheLaneStillExplainsARefusalThatHadNowhereToGo() {
        SubprocessEnvironment.updateRuntime(
            Map.of("CLAUDE_CODE_DISABLE_REFUSAL_FALLBACK", "1"));
        try {
            DefaultQuerySession engine = session(ModelCatalog.OPUS.modelId(), null);
            RecordingDeps deps = new RecordingDeps(List.of(
                refused("msg-1", "I can't help with that")));

            run(engine, deps);


            assertTrue(engine.getMutableMessages().stream()
                    .anyMatch(m -> m instanceof AssistantMessage a && a.isApiErrorMessage()),
                "expected the refusal error row: " + engine.getMutableMessages());
        } finally {
            SubprocessEnvironment.clearRuntimeOverrides();
        }
    }

    private static final class UnsupportedPrompt implements RefusalFallbackPrompt {
        @Override
        public RefusalFallbackDecision.Choice ask(Request request) {
            throw new AssertionError("an unsupported SDK dialog must never be requested");
        }

        @Override
        public boolean consumerSupportsDialog() {
            return false;
        }
    }

    /** Answers the dialog the way a test wants and remembers what it was shown. */
    private static final class RecordingPrompt implements RefusalFallbackPrompt {
        private final RefusalFallbackDecision.Choice answer;
        private final List<Request> asked = new ArrayList<>();

        private RecordingPrompt(RefusalFallbackDecision.Choice answer) {
            this.answer = answer;
        }

        @Override
        public RefusalFallbackDecision.Choice ask(Request request) {
            asked.add(request);
            return answer;
        }
    }

    /** Collects what the loop persisted, in order, so it can be replayed. */
    private static final class RecordingTranscript implements TranscriptSink {
        private final List<Message> recorded = new ArrayList<>();

        @Override
        public void record(String sessionId, Message message) {
            recorded.add(message);
        }
    }
}
