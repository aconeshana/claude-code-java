package com.claudecode.commands.impl.context;


import org.apache.commons.lang3.Strings;

import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.CommandOutputChannel;
import com.claudecode.commands.CommandResultDisplay;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.engine.MessageCompactor;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.services.compact.CompactService;
import com.claudecode.services.compact.CompactException;
import com.claudecode.services.compact.CompactSummarizer;
import com.claudecode.services.compact.LlmCompactSummarizer;
import com.claudecode.core.message.TokenEstimator;
import com.claudecode.runtime.query.DefaultQuerySession;
import com.claudecode.runtime.query.QuerySessionSpec;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@code /compact <instructions>} actually steers the summarization
 * prompt, the PreCompact/PostCompact hook payload, and — since PreCompact/
 * PostCompact hooks gained {@code WithOutcome} variants — that hook-emitted
 * {@code additionalContext} is merged into the summarization instructions
 * and folded into the success message (regression guard for two independent
 * gaps: the original 2026-07-08 /clear review found {@code customInstructions}
 * was computed but never passed anywhere; a later pass found PreCompact
 * hook instructions were fired but always discarded).
 */
class CompactCommandTest {

    private static final class AbortAwareStreamingClient implements StreamingClient {
        boolean called;

        @Override
        public Iterator<StreamingEvent> createStream(StreamRequest request) {
            called = true;
            request.abortController().throwIfAborted();
            return List.<StreamingEvent>of(
                new StreamingEvent.MessageStartEvent(
                    "compact-message", request.model(), List.of(), Usage.EMPTY),
                new StreamingEvent.ContentBlockStartEvent(0, "text", null, null),
                new StreamingEvent.ContentBlockDeltaEvent(
                    0, "text_delta", "Summary of the conversation."),
                new StreamingEvent.ContentBlockStopEvent(0),
                new StreamingEvent.MessageDeltaEvent("end_turn", Usage.EMPTY),
                new StreamingEvent.MessageStopEvent()
            ).iterator();
        }

        @Override
        public String getModel() {
            return "test-model";
        }
    }

    
    private static class CapturingSummarizer implements CompactSummarizer {
        String lastPrompt;
        List<Message> lastMessages;

        @Override
        public String summarize(List<Message> messages, String compactPrompt) {
            lastMessages = List.copyOf(messages);
            lastPrompt = compactPrompt;
            return "Summary of the conversation.";
        }
    }

    /** Like {@link CapturingSummarizer} but also returns a real API {@link Usage}. */
    private static final class UsageAwareSummarizer extends CapturingSummarizer {
        private final Usage usage;

        UsageAwareSummarizer(Usage usage) { this.usage = usage; }

        @Override
        public CompactSummarizer.SummaryResult summarizeWithUsage(List<Message> messages, String compactPrompt) {
            lastPrompt = compactPrompt;
            return new CompactSummarizer.SummaryResult("Summary of the conversation.", usage);
        }
    }

    /** Captures dispatchPreCompactWithOutcome/dispatchPostCompactWithOutcome arguments. */
    private static class CapturingHooks implements HookDispatcher {
        String preCustomInstructions;
        String postCompactSummary;
        long preTokenCount;
        long postTokenCount;
        boolean preCalled;
        boolean postCalled;
        String preAdditionalContext;
        String postAdditionalContext;
        String preUserDisplayMessage;
        String postUserDisplayMessage;

        @Override public boolean dispatchPreToolUse(String t, JsonNode i, String id) { return true; }
        @Override public void dispatchPostToolUse(String t, JsonNode i, JsonNode o, String id) {}
        @Override public void dispatchUserPromptSubmit(String prompt) {}
        @Override public void dispatchSessionStart(String trigger) {}
        @Override public void dispatchStop(String reason) {}

        @Override
        public HookOutcome dispatchPreCompactWithOutcome(String trigger, String customInstructions, long preTokenCount) {
            preCalled = true;
            this.preCustomInstructions = customInstructions;
            this.preTokenCount = preTokenCount;
            return preAdditionalContext == null
                ? HookOutcome.PROCEED
                : new HookOutcome(true, preAdditionalContext, List.of(), false, null,
                    preUserDisplayMessage);
        }

        @Override
        public HookOutcome dispatchPostCompactWithOutcome(String trigger, String compactSummary, long postTokenCount) {
            postCalled = true;
            this.postCompactSummary = compactSummary;
            this.postTokenCount = postTokenCount;
            return postAdditionalContext == null
                ? HookOutcome.PROCEED
                : new HookOutcome(true, postAdditionalContext, List.of(), false, null,
                    postUserDisplayMessage);
        }
    }

    private static List<Message> someMessages() {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("hello")));
        messages.add(new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("world")));
        return messages;
    }

    private static CommandContext ctx(MessageCompactor compactor, HookDispatcher hooks,
                                       List<Message> messages, Consumer<List<Message>> loadMessages) {
        return ctx(compactor, hooks, messages, loadMessages, false);
    }

    private static CommandContext ctx(MessageCompactor compactor, HookDispatcher hooks,
                                       List<Message> messages, Consumer<List<Message>> loadMessages,
                                       boolean verbose) {
        return CommandContext.builder(
                "test-model", () -> messages, () -> {}, _ -> {},
                () -> Usage.EMPTY, _ -> 0.0,
                System.getProperty("user.dir"), false)
            .loadMessages(loadMessages)
            .currentSessionId(() -> "session-1")
            .compactService(() -> compactor)
            .hookDispatcher(hooks)
            .verboseSupplier(() -> verbose)
            .build();
    }

    @Test
    void emptyConversation_matchesTsErrorText() {
        CommandResult result = new CompactCommand().execute(
            ctx(new CompactService(TokenEstimator.getInstance(), new CapturingSummarizer(), true),
                new CapturingHooks(), List.of(), _ -> {}), "");

        assertEquals("No messages to compact", result.output());
        assertEquals(CommandOutputChannel.STDERR, result.outputChannel());
        assertEquals(CommandResultDisplay.LOCAL, result.display());
        assertEquals("", result.headlessOutput());
    }

    @Test
    void customInstructions_reachTheSummarizationPrompt() {
        CapturingSummarizer summarizer = new CapturingSummarizer();
        CompactService compactService = new CompactService(TokenEstimator.getInstance(), summarizer, true);
        List<Message> loaded = new ArrayList<>();

        CommandResult r = new CompactCommand().execute(
            ctx(compactService, new CapturingHooks(), someMessages(), loaded::addAll),
            "focus on the auth bug, drop unrelated file edits");

        assertTrue(Strings.CS.contains(summarizer.lastPrompt, "focus on the auth bug, drop unrelated file edits"),
            "user's /compact <instructions> must reach the LLM summarization prompt; got: "
                + summarizer.lastPrompt);
        assertTrue(Strings.CS.startsWith(r.output(), "Compacted"));
        assertEquals("", r.headlessOutput(),
            "2.1.197 keeps compact's displayText in the UI/transcript but returns an empty SDK result");
    }

    @Test
    void manualCompactAfterInterruptedTurnStartsWithFreshCancellationState() {
        AbortAwareStreamingClient client = new AbortAwareStreamingClient();
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(client)
            .model("test-model")
            .build());
        CompactService compactService = new CompactService(
            TokenEstimator.getInstance(),
            new LlmCompactSummarizer(client, () -> engine),
            true);
        engine.interrupt();

        CommandResult result = new CompactCommand().execute(
            ctx(compactService, new CapturingHooks(), someMessages(), _ -> {}), "");

        assertTrue(client.called);
        assertTrue(Strings.CS.startsWith(result.output(), "Compacted"),
            "manual /compact must not inherit the preceding turn's user-cancel: "
                + result.output());
    }

    @Test
    void verboseMode_omitsFullSummaryShortcutHint() {
        CapturingSummarizer summarizer = new CapturingSummarizer();
        CompactService compactService = new CompactService(TokenEstimator.getInstance(), summarizer, true);

        CommandResult result = new CompactCommand().execute(
            ctx(compactService, new CapturingHooks(), someMessages(), _ -> {}, true), "");

        assertEquals("Compacted ", result.output());
        assertFalse(Strings.CS.contains(result.output(), "ctrl+o"));
    }

    @Test
    void successfulManualCompact_suppressesWarningUntilNextTokenResponse() {
        CapturingSummarizer summarizer = new CapturingSummarizer();
        CompactService compactService = new CompactService(TokenEstimator.getInstance(), summarizer, true);
        compactService.suppressCompactWarning();

        new CompactCommand().execute(
            ctx(compactService, new CapturingHooks(), someMessages(), _ -> {}), "");

        assertTrue(compactService.isCompactWarningSuppressed(),
            "TS /compact calls suppressCompactWarning after a successful manual compaction");
    }

    @Test
    void postTranscriptCallbackRunsAfterAllCompactRows() {
        CapturingSummarizer summarizer = new CapturingSummarizer();
        CompactService compactService = new CompactService(
            TokenEstimator.getInstance(), summarizer, true);
        List<String> events = new ArrayList<>();
        CommandContext context = CommandContext.builder(
                "test-model", CompactCommandTest::someMessages, () -> {}, _ -> {},
                () -> Usage.EMPTY, _ -> 0.0, System.getProperty("user.dir"), false)
            .loadMessages(_ -> {})
            .currentSessionId(() -> "session-1")
            .compactService(() -> compactService)
            .hookDispatcher(new CapturingHooks())
            .postCompactCallback(() -> events.add("metadata"))
            .transcriptRecorder(_ -> events.add("row"))
            .postCompactTranscriptCallback(() -> events.add("checkpoint"))
            .build();

        new CompactCommand().execute(context, "");

        assertEquals("metadata", events.getFirst());
        assertEquals("checkpoint", events.getLast());
        assertTrue(events.subList(1, events.size() - 1).stream()
            .allMatch("row"::equals));
    }

    @Test
    void knownCompactionErrors_areReturnedWithoutInventedFailureFooter() {
        MessageCompactor failing = new CompactService(
                TokenEstimator.getInstance(), new CapturingSummarizer(), true) {
            @Override public CompactionResult compactConversation(List<Message> messages, boolean auto) {
                throw new CompactException("Not enough messages to compact.");
            }
            @Override public CompactionResult compactConversation(List<Message> messages, boolean auto,
                                                                   String instructions) {
                throw new CompactException("Not enough messages to compact.");
            }
        };

        CommandResult result = new CompactCommand().execute(
            ctx(failing, new CapturingHooks(), someMessages(), _ -> {}), "");

        assertEquals("Not enough messages to compact.", result.output());
        assertFalse(Strings.CS.contains(result.output(), "history is unchanged"));
        assertEquals(CommandOutputChannel.STDERR, result.outputChannel());
        assertEquals(CommandResultDisplay.LOCAL, result.display());
    }

    @Test
    void manualCompactOmitsLatestTerminalAssistantFromSummaryFork() {
        CapturingSummarizer summarizer = new CapturingSummarizer();
        CompactService compactService = new CompactService(TokenEstimator.getInstance(), summarizer, true);
        UserMessage user = new UserMessage("u1", MessageContent.ofText("do the work"));
        AssistantMessage terminal = new AssistantMessage("a1",
            AssistantContent.of(List.of(new TextBlock("OK"))));
        List<Message> loaded = new ArrayList<>();

        new CompactCommand().execute(
            ctx(compactService, new CapturingHooks(), List.of(user, terminal), loaded::addAll), "");

        assertEquals(List.of(user), summarizer.lastMessages,
            "the interactive slash-command snapshot in 2.1.197 omits the just-rendered terminal assistant");
        int summaryIndex = loaded.indexOf(loaded.stream()
            .filter(UserMessage.class::isInstance)
            .map(UserMessage.class::cast)
            .filter(UserMessage::isCompactSummary)
            .findFirst().orElseThrow());
        assertEquals(terminal, loaded.get(summaryIndex + 1),
            "the omitted terminal assistant is preserved after the compact summary");
    }

    @Test
    void manualCompactUsesTheCompactionAwareMessageLoader() {
        CompactService compactService = new CompactService(
            TokenEstimator.getInstance(), new CapturingSummarizer(), true);
        AtomicBoolean genericLoader = new AtomicBoolean(false);
        AtomicBoolean compactLoader = new AtomicBoolean(false);
        CommandContext context = CommandContext.builder(
                "test-model", CompactCommandTest::someMessages, () -> {}, _ -> {},
                () -> Usage.EMPTY, _ -> 0.0, System.getProperty("user.dir"), false)
            .loadMessages(_ -> genericLoader.set(true))
            .loadCompactedMessages(_ -> compactLoader.set(true))
            .compactService(() -> compactService)
            .hookDispatcher(new CapturingHooks())
            .build();

        new CompactCommand().execute(context, "");

        assertTrue(compactLoader.get());
        assertFalse(genericLoader.get());
    }

    @Test
    void manualCompactCountsTerminalAssistantUsageBeforeOmittingItFromSummaryFork() {
        CapturingSummarizer summarizer = new CapturingSummarizer();
        CompactService compactService = new CompactService(TokenEstimator.getInstance(), summarizer, true);
        CapturingHooks hooks = new CapturingHooks();
        UserMessage user = new UserMessage("u-usage", MessageContent.ofText("do the work"));
        AssistantMessage terminal = new AssistantMessage(
            "a-usage",
            AssistantContent.apiResponse(
                "msg-usage", List.of(new TextBlock("OK")),
                new Usage(1, 1, 0, 0), "claude-sonnet-4-6", "end_turn", null));
        List<Message> loaded = new ArrayList<>();

        new CompactCommand().execute(
            ctx(compactService, hooks, List.of(user, terminal), loaded::addAll), "");

        assertEquals(List.of(user), summarizer.lastMessages,
            "the terminal assistant stays outside the summary wire fork");
        assertEquals(2L, hooks.preTokenCount,
            "2.1.197 computes PreCompact pre_tokens from the full post-microcompact list before omitting the terminal assistant");
        SystemMessage boundary = assertInstanceOf(SystemMessage.class, loaded.getFirst());
        assertEquals(2L, boundary.compactMetadata().preTokens(),
            "compact_boundary.preTokens must use the same full-context count as the PreCompact hook");
        UserMessage summary = loaded.stream()
            .filter(UserMessage.class::isInstance)
            .map(UserMessage.class::cast)
            .filter(UserMessage::isCompactSummary)
            .findFirst().orElseThrow();
        assertEquals(compactService.estimateTokenCount(List.of(summary, terminal)),
            boundary.compactMetadata().postTokens(),
            "compact_boundary.postTokens must include the terminal assistant restored after the summary");
    }

    @Test
    void manualCompactAnnotatesBoundaryWithTerminalPreservedSegment() {
        CapturingSummarizer summarizer = new CapturingSummarizer();
        CompactService compactService = new CompactService(TokenEstimator.getInstance(), summarizer, true);
        UserMessage user = new UserMessage("u1", MessageContent.ofText("do the work"));
        AssistantMessage terminal = new AssistantMessage("a1",
            AssistantContent.of(List.of(new TextBlock("OK"))));
        List<Message> loaded = new ArrayList<>();

        new CompactCommand().execute(
            ctx(compactService, new CapturingHooks(), List.of(user, terminal), loaded::addAll), "");

        SystemMessage boundary = (SystemMessage) loaded.getFirst();
        UserMessage summary = loaded.stream()
            .filter(UserMessage.class::isInstance)
            .map(UserMessage.class::cast)
            .filter(UserMessage::isCompactSummary)
            .findFirst().orElseThrow();

        assertNotNull(boundary.compactMetadata(),
            "a restart can only recover the preserved terminal assistant when the boundary persists relink metadata");
        assertNotNull(boundary.compactMetadata().preservedSegment());
        assertEquals(terminal.uuid(), boundary.compactMetadata().preservedSegment().headUuid());
        assertEquals(terminal.uuid(), boundary.compactMetadata().preservedSegment().tailUuid());
        assertEquals(summary.uuid(), boundary.compactMetadata().preservedSegment().anchorUuid());
    }

    @Test
    void resumedRecoveryCompactPinsCaveatToSyntheticAssistantParent() {
        CapturingSummarizer summarizer = new CapturingSummarizer();
        CompactService compactService = new CompactService(TokenEstimator.getInstance(), summarizer, true);
        UserMessage user = new UserMessage("recovery-user",
            MessageContent.ofText("Continue from where you left off."));
        AssistantMessage sentinel = new AssistantMessage("recovery-assistant",
            AssistantContent.of(List.of(new TextBlock("No response requested."))));
        List<Message> loaded = new ArrayList<>();

        new CompactCommand().execute(
            ctx(compactService, new CapturingHooks(), List.of(user, sentinel), loaded::addAll), "");

        UserMessage caveat = loaded.stream()
            .filter(UserMessage.class::isInstance)
            .map(UserMessage.class::cast)
            .filter(message -> message.message().isText()
                && Strings.CS.startsWith(message.message().text(), "<local-command-caveat>"))
            .findFirst().orElseThrow();
        assertEquals(sentinel.uuid(), caveat.sourceToolAssistantUUID());
    }

    @Test
    void compactCommandRecordsCaveatInputAndStdoutIntoPostCompactHistory() {
        CapturingSummarizer summarizer = new CapturingSummarizer();
        CompactService compactService = new CompactService(TokenEstimator.getInstance(), summarizer, true);
        List<Message> loaded = new ArrayList<>();

        new CompactCommand().execute(
            ctx(compactService, new CapturingHooks(), someMessages(), loaded::addAll), "focus on auth");

        String joined = loaded.stream()
            .filter(UserMessage.class::isInstance)
            .map(UserMessage.class::cast)
            .map(m -> m.message().isText() ? m.message().text() : "")
            .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(Strings.CS.contains(joined, "<local-command-caveat>"));
        assertTrue(Strings.CS.contains(joined, "<command-name>/compact</command-name>"));
        assertTrue(Strings.CS.contains(joined, "<command-args>focus on auth</command-args>"));
        assertTrue(Strings.CS.contains(joined, "<local-command-stdout>Compacted"));
        List<String> localCommandTexts = loaded.stream()
            .filter(UserMessage.class::isInstance)
            .map(UserMessage.class::cast)
            .filter(m -> m.message().isText())
            .map(m -> m.message().text())
            .filter(text -> Strings.CS.startsWith(text, "<local-command-caveat>")
                || Strings.CS.startsWith(text, "<command-name>")
                || Strings.CS.startsWith(text, "<local-command-stdout>"))
            .toList();
        assertEquals(3, localCommandTexts.size());
        assertTrue(localCommandTexts.stream().noneMatch(text -> Strings.CS.endsWith(text, "\n")),
            "JSONL stores each local-command message without a trailing newline; API merging adds the separator");
    }

    @Test
    void preCompactHook_receivesCustomInstructions() {
        CapturingSummarizer summarizer = new CapturingSummarizer();
        CompactService compactService = new CompactService(TokenEstimator.getInstance(), summarizer, true);
        CapturingHooks hooks = new CapturingHooks();

        new CompactCommand().execute(
            ctx(compactService, hooks, someMessages(), _ -> {}),
            "keep the API design notes");

        assertTrue(hooks.preCalled);
        assertEquals("keep the API design notes", hooks.preCustomInstructions);
    }

    @Test
    void postCompactHook_receivesSummaryText() {
        CapturingSummarizer summarizer = new CapturingSummarizer();
        CompactService compactService = new CompactService(TokenEstimator.getInstance(), summarizer, true);
        CapturingHooks hooks = new CapturingHooks();

        new CompactCommand().execute(
            ctx(compactService, hooks, someMessages(), _ -> {}), "");

        assertTrue(hooks.postCalled);
        assertEquals("Summary of the conversation.", hooks.postCompactSummary);
    }

    @Test
    void blankArgs_passNullCustomInstructionsToHook() {
        CapturingSummarizer summarizer = new CapturingSummarizer();
        CompactService compactService = new CompactService(TokenEstimator.getInstance(), summarizer, true);
        CapturingHooks hooks = new CapturingHooks();

        new CompactCommand().execute(
            ctx(compactService, hooks, someMessages(), _ -> {}), "   ");

        assertNull(hooks.preCustomInstructions,
            "blank /compact args must not surface as a non-null custom_instructions value");
    }

    @Test
    void preCompactHookAdditionalContext_mergesWithUserInstructions() {
        CapturingSummarizer summarizer = new CapturingSummarizer();
        CompactService compactService = new CompactService(TokenEstimator.getInstance(), summarizer, true);
        CapturingHooks hooks = new CapturingHooks();
        hooks.preAdditionalContext = "hook says: focus on security";

        new CompactCommand().execute(
            ctx(compactService, hooks, someMessages(), _ -> {}), "keep the API design notes");

        assertTrue(Strings.CS.contains(summarizer.lastPrompt, "keep the API design notes"),
            "user instructions must survive the merge; got: " + summarizer.lastPrompt);
        assertTrue(Strings.CS.contains(summarizer.lastPrompt, "hook says: focus on security"),
            "PreCompact hook additionalContext must be merged into the summarization prompt; got: "
                + summarizer.lastPrompt);
        int userIdx = summarizer.lastPrompt.indexOf("keep the API design notes");
        int hookIdx = summarizer.lastPrompt.indexOf("hook says: focus on security");
        assertTrue(userIdx < hookIdx, "user instructions must come before hook instructions (mergeHookInstructions order)");
    }

    @Test
    void preCompactHookAdditionalContext_usedAloneWhenNoUserInstructions() {
        CapturingSummarizer summarizer = new CapturingSummarizer();
        CompactService compactService = new CompactService(TokenEstimator.getInstance(), summarizer, true);
        CapturingHooks hooks = new CapturingHooks();
        hooks.preAdditionalContext = "hook-only instructions";

        new CompactCommand().execute(
            ctx(compactService, hooks, someMessages(), _ -> {}), "");

        assertTrue(Strings.CS.contains(summarizer.lastPrompt, "hook-only instructions"),
            "PreCompact hook additionalContext must steer the prompt even with no user args; got: "
                + summarizer.lastPrompt);
    }

    @Test
    void hookAdditionalContext_appearsInSuccessMessage() {
        CapturingSummarizer summarizer = new CapturingSummarizer();
        CompactService compactService = new CompactService(TokenEstimator.getInstance(), summarizer, true);
        CapturingHooks hooks = new CapturingHooks();
        hooks.preAdditionalContext = "pre hook ran";
        hooks.postAdditionalContext = "post hook ran";
        hooks.preUserDisplayMessage = "PreCompact [pre.sh] completed successfully: pre hook ran";
        hooks.postUserDisplayMessage = "PostCompact [post.sh] completed successfully: post hook ran";

        CommandResult r = new CompactCommand().execute(
            ctx(compactService, hooks, someMessages(), _ -> {}), "");

        assertTrue(Strings.CS.contains(r.output(), "PreCompact [pre.sh] completed successfully: pre hook ran"),
            "pre-compact hook status must reach the user; got: " + r.output());
        assertTrue(Strings.CS.contains(r.output(), "PostCompact [post.sh] completed successfully: post hook ran"),
            "post-compact hook status must reach the user; got: " + r.output());
    }

    @Test
    void realUsage_flowsToPostCompactHookTokenCount() {
        Usage usage = new Usage(500, 100, 50, 20); // total = 670
        UsageAwareSummarizer summarizer = new UsageAwareSummarizer(usage);
        CompactService compactService = new CompactService(TokenEstimator.getInstance(), summarizer, true);
        CapturingHooks hooks = new CapturingHooks();

        new CompactCommand().execute(
            ctx(compactService, hooks, someMessages(), _ -> {}), "");

        assertEquals(670L, hooks.postTokenCount,
            "postTokenCount must be the real API usage total (input+output+cache), not a flat estimate");
    }

    @Test
    void noSummarizer_reportsTheCompactionError() {
        CompactService compactService = new CompactService(TokenEstimator.getInstance(), null, true);

        CommandResult r = new CompactCommand().execute(
            ctx(compactService, new CapturingHooks(), someMessages(), _ -> {}), "");

        assertTrue(Strings.CS.startsWith(r.output(), "Error during compaction:"),
            "no summarizer configured must not silently succeed; got: " + r.output());
    }
}
