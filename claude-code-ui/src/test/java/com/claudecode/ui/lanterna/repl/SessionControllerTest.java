package com.claudecode.ui.lanterna.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.CompactProgressEvent;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.engine.MessageCompactor;
import com.claudecode.core.engine.TranscriptSink;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.RefusalFallbackAnnouncement;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.SummarizeMetadata;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.message.Usage;
import com.claudecode.core.prompt.SystemPromptSection;
import com.claudecode.core.prompt.SystemPromptSectionResolver;
import com.claudecode.runtime.query.DefaultQuerySession;
import com.claudecode.runtime.query.QuerySessionSpec;
import com.claudecode.ui.lanterna.components.LogoPanel;
import com.claudecode.ui.lanterna.dialog.MessageSelectorDialog;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.transcript.MessageCollapser;
import com.claudecode.ui.lanterna.transcript.MessageHistory;
import com.claudecode.ui.lanterna.transcript.LanternaMessageDispatcher;
import com.claudecode.ui.lanterna.transcript.MessagePanel;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.apache.commons.lang3.Strings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link SessionController}'s rewind behavior and pure helpers:
 * <ul>
 *   <li>{@link SessionController#rewindTo(List)} — interrupt auto-restore's message-list
 *       truncation, exercising the {@code selectableUserMessagesFilter} rule (skip
 *       tool_result wrappers).</li>
 *   <li>{@link SessionController#textForResubmit(String)} — {@code /rewind}'s
 *       bash/slash-command reconstruction for the restored input box.</li>
 * </ul>
 */
class SessionControllerTest {

    private static final StreamingClient NOOP_CLIENT = new StreamingClient() {
        @Override
        public Iterator<StreamingEvent> createStream(StreamRequest request) {
            return Collections.emptyIterator();
        }

        @Override
        public String getModel() {
            return "test-model";
        }
    };

    @TempDir
    Path tempDir;

    private static UserMessage realUser(String text) {
        return new UserMessage("u-" + text, MessageContent.ofText(text));
    }

    private static UserMessage equalCopy(UserMessage message) {
        return new UserMessage(
            message.uuid(), message.message(), message.isMeta(), message.isCompactSummary(),
            message.toolUseResult(), message.origin(), message.parentUuidValue(),
            message.timestampValue(), message.imagePasteIds(), message.permissionMode(),
            message.sessionIdValue(), message.sourceToolAssistantUUID(), message.sourceToolUseID(),
            message.isVirtual(), message.mcpMeta(), message.isVisibleInTranscriptOnly(),
            message.planContent(), message.summarizeMetadata());
    }

    /** A UserMessage whose first block is a tool_result — must be skipped. */
    private static UserMessage toolResultUser() {
        return new UserMessage("tr", MessageContent.ofToolResult(
            "tool-1", List.of(new TextBlock("ok")), false));
    }

    @Test
    void freshConversationResetClearsOldTranscriptAndRestoresWelcome() {
        MessagePanel panel = new MessagePanel();
        panel.appendLine("old conversation", TextColor.ANSI.DEFAULT);
        LogoPanel logo = new LogoPanel();

        SessionController.resetFreshConversationSurface(
            panel, () -> logo.show(panel, 120, "claude-sonnet-4-6"));

        assertTrue(panel.searchLines("old conversation").isEmpty());
        assertFalse(panel.searchLines("Claude Code").isEmpty(),
            "a new local or cc-connect session must remount the welcome block");
    }

    @Test
    void emptyList_returnsNull() {
        assertNull(SessionController.rewindTo(new ArrayList<>()));
    }

    @Test
    void dropsLastRealUser_andEverythingAfter() {
        UserMessage u1 = realUser("first");
        UserMessage u2 = realUser("second");
        List<Message> msgs = new ArrayList<>(List.of(u1, u2));

        UserMessage removed = SessionController.rewindTo(msgs);

        assertSame(u2, removed);
        assertEquals(List.of(u1), msgs, "everything from the last real user on is dropped");
    }

    @Test
    void skipsToolResultWrapper_rewindsToPrecedingRealUser() {
        UserMessage u1 = realUser("first");
        UserMessage tr = toolResultUser();
        List<Message> msgs = new ArrayList<>(List.of(u1, tr));

        UserMessage removed = SessionController.rewindTo(msgs);

        assertSame(u1, removed, "tool_result wrapper is not a selectable user message");
        assertTrue(msgs.isEmpty(), "both the real user and the trailing tool_result are dropped");
    }

    @Test
    void skipsSyntheticInterruption_rewindsToPrecedingRealUser() {
        UserMessage prompt = realUser("continue");
        UserMessage interruption = realUser(MessageConstants.INTERRUPT_MESSAGE);
        List<Message> msgs = new ArrayList<>(List.of(prompt, interruption));

        UserMessage removed = SessionController.rewindTo(msgs);

        assertSame(prompt, removed,
            "interrupt auto-restore must target the submitted human prompt, not its synthetic sentinel");
        assertTrue(msgs.isEmpty(),
            "the human prompt and trailing interruption sentinel must both be removed");
    }

    @Test
    void skipsGeneratedUserRows_rewindsToPrecedingHumanPrompt() {
        UserMessage prompt = realUser("continue");
        UserMessage taskNotification = new UserMessage(
            "task", MessageContent.ofText("background task finished"), false, false, null,
            MessageOrigin.TASK_NOTIFICATION, null, Instant.now(), null, null);
        List<Message> msgs = new ArrayList<>(List.of(prompt, taskNotification));

        UserMessage removed = SessionController.rewindTo(msgs);

        assertSame(prompt, removed);
        assertTrue(msgs.isEmpty(),
            "the human prompt and trailing generated row must both be removed");
    }

    @Test
    void onlyToolResults_returnsNull() {
        List<Message> msgs = new ArrayList<>(List.of(toolResultUser(), toolResultUser()));
        assertNull(SessionController.rewindTo(msgs));
        assertEquals(2, msgs.size(), "nothing removed when there is no real user message");
    }

    @Test
    void interruptRewindRemovesTheSubmittedPromptFromConversationAndTranscript() {
        UserMessage first = realUser("first");
        UserMessage second = realUser("second");
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .initialMessages(List.of(first, second))
            .build());
        MessagePanel panel = new MessagePanel();
        panel.appendLine("first", TextColor.ANSI.DEFAULT);
        panel.registerLogicalMessage("render-first", first.uuid(),
            MessagePanel.LogicalMessageKind.USER, 0, 0,
            "first", "first", null, null, false);
        panel.appendLine("second", TextColor.ANSI.DEFAULT);
        panel.registerLogicalMessage("render-second", second.uuid(),
            MessagePanel.LogicalMessageKind.USER, 1, 1,
            "second", "second", null, null, false);
        panel.appendLine("synthetic tail", TextColor.ANSI.DEFAULT);
        MessageHistory history = new MessageHistory();
        history.record(new SDKMessage.User(first));
        history.record(new SDKMessage.User(second));
        MessageCollapser collapser = new MessageCollapser(null, false) {
            @Override
            public void resetTurn() {
                // This test has no dispatcher state to reset.
            }
        };
        SessionController controller = new SessionController(
            null, null, engine, null, panel, history, collapser, null, null, null,
            null, null, null, null, null);

        UserMessage removed = assertDoesNotThrow(
            controller::rewindToBeforeLastRealUserMessage);

        assertSame(second, removed);
        assertEquals(List.of(first), engine.conversation().getMessages());
        assertEquals(1, panel.snapshotLineCount(),
            "the submitted prompt and every synthetic row after it are no longer visible");
        assertTrue(panel.searchLines("second").isEmpty());
        assertEquals(1, history.events().size(),
            "replay must not resurrect the cancelled prompt");
        assertSame(first, ((SDKMessage.User) history.events().getFirst()).message());
    }

    @Test
    void messageActionRewindReplacesTheReadOnlyConversationViewWithItsTruncatedCopy() {
        UserMessage first = realUser("first");
        UserMessage selected = new UserMessage("selected", null);
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .initialMessages(List.of(first, selected))
            .build());
        engine.setPreviousTurnTools(List.of("Read"));
        engine.setCompactionOccurred(true);
        MessageCollapser collapser = new MessageCollapser(null, false) {
            @Override
            public void resetTurn() {
                // This test has no dispatcher state to reset.
            }
        };
        SessionController controller = new SessionController(
            null, null, engine, null, new MessagePanel(), new MessageHistory(),
            collapser, null, null, null, null, null, null, null, null);

        assertDoesNotThrow(() -> controller.editMessageFromActions(selected.uuid()));

        assertEquals(List.of(first), engine.conversation().getMessages());
        assertEquals(List.of("Read"), engine.getPreviousTurnTools());
        assertTrue(engine.hasCompactionOccurred());
    }

    @Test
    void messageActionRewindWaitsForTheActiveTurnDeferrer() {
        UserMessage first = realUser("first");
        UserMessage selected = new UserMessage("selected", null);
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .initialMessages(List.of(first, selected))
            .build());
        MessageCollapser collapser = new MessageCollapser(null, false) {
            @Override public void resetTurn() {}
        };
        SessionController controller = new SessionController(
            null, null, engine, null, new MessagePanel(), new MessageHistory(),
            collapser, null, null, null, null, null, null, null, null);
        AtomicReference<Runnable> deferred = new AtomicReference<>();
        controller.setRewindDeferrer(deferred::set);

        controller.editMessageFromActions(selected.uuid());

        assertEquals(List.of(first, selected), engine.conversation().getMessages());
        assertNotNull(deferred.get());
        deferred.get().run();
        assertEquals(List.of(first), engine.conversation().getMessages());
    }

    @Test
    void idleMessageActionRewindDoesNotAbortTheSession() {
        UserMessage selected = realUser("restore me");
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .initialMessages(List.of(selected))
            .build());
        SessionController controller = new SessionController(
            null, null, engine, null, new MessagePanel(), new MessageHistory(),
            new MessageCollapser(null, false) {
                @Override public void resetTurn() {}
            }, new InputPanel(), null, null, null, null, null, null, null);
        controller.setRewindDeferrer(_ -> {});

        controller.editMessageFromActions(selected.uuid());

        assertFalse(engine.execution().getAbortController().isAborted(),
            "2.1.197 only interrupts when a request is actually active");
    }

    @Test
    void activeMessageActionRewindInterruptsBeforeDeferring() {
        UserMessage selected = realUser("restore me");
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .initialMessages(List.of(selected))
            .build());
        SessionController controller = new SessionController(
            null, null, engine, null, new MessagePanel(), new MessageHistory(),
            new MessageCollapser(null, false) {
                @Override public void resetTurn() {}
            }, new InputPanel(), null, null, null, null, null, null, null);
        controller.setRewindInterruptRequired(() -> true);
        controller.setRewindDeferrer(_ -> {});

        controller.editMessageFromActions(selected.uuid());

        assertTrue(engine.execution().getAbortController().isAborted());
        assertEquals("user-cancel", engine.execution().getAbortController().getReason());
    }

    @Test
    void staleMessageActionSelectionOnlyRestoresThePrompt() {
        UserMessage first = realUser("first prompt");
        UserMessage selected = realUser("selected prompt");
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .initialMessages(List.of(first, selected))
            .build());
        engine.forks().getNestedMemoryAttachmentTriggers().add("/tmp/pending-trigger.md");
        InputPanel input = new InputPanel();
        MessagePanel panel = new MessagePanel();
        panel.appendLine("first prompt", TextColor.ANSI.DEFAULT);
        panel.registerLogicalMessage("render-first", first.uuid(),
            MessagePanel.LogicalMessageKind.USER, 0, 0,
            "first prompt", "first prompt", null, null, false);
        panel.appendLine("selected prompt", TextColor.ANSI.DEFAULT);
        panel.registerLogicalMessage("render-selected", selected.uuid(),
            MessagePanel.LogicalMessageKind.USER, 1, 1,
            "selected prompt", "selected prompt", null, null, false);
        SessionController controller = new SessionController(
            null, null, engine, null, panel, new MessageHistory(),
            new MessageCollapser(null, false) {
                @Override public void resetTurn() {}
            }, input, null, null, null, null, null, null, null);
        AtomicReference<Runnable> deferred = new AtomicReference<>();
        controller.setRewindDeferrer(deferred::set);

        controller.editMessageFromActions(selected.uuid());
        UserMessage replacementFirst = equalCopy(first);
        UserMessage replacementSelected = equalCopy(selected);
        engine.conversation().loadMessages(List.of(replacementFirst, replacementSelected));
        assertNotNull(deferred.get());

        deferred.get().run();

        assertEquals(2, engine.conversation().getMessages().size());
        assertSame(replacementFirst, engine.conversation().getMessages().getFirst());
        assertSame(replacementSelected, engine.conversation().getMessages().getLast(),
            "record equality must not make a stale selector object look live");
        assertEquals(1, engine.forks().getNestedMemoryAttachmentTriggers().size());
        assertTrue(engine.forks().getNestedMemoryAttachmentTriggers()
            .contains("/tmp/pending-trigger.md"));
        assertFalse(panel.searchLines("selected prompt").isEmpty(),
            "a stale selection must not truncate the current transcript surface");
        assertEquals("selected prompt", input.getText(),
            "197 still restores the picked prompt after the conversation rewind no-ops");
    }

    @Test
    void conversationRestoreUsesTheLastLiveOccurrenceOfTheSelectedObject() throws Exception {
        UserMessage selected = realUser("duplicated prompt object");
        AssistantMessage between = new AssistantMessage(
            "between", AssistantContent.of(List.of(new TextBlock("between duplicates"))));
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .initialMessages(List.of(selected, between, selected))
            .build());
        MessageSelectorDialog dialog = new MessageSelectorDialog();
        SessionController controller = new SessionController(
            null, null, engine, null, new MessagePanel(), new MessageHistory(),
            new MessageCollapser(null, false) {
                @Override public void resetTurn() {}
            }, new InputPanel(), dialog, null, null, null, null, null, null);

        controller.showMessageSelector();
        dialog.handleKey(new KeyStroke(
            KeyType.ARROW_UP), new AtomicBoolean(true));
        dialog.handleKey(new KeyStroke(
            KeyType.ENTER), new AtomicBoolean(true));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (dialog.isActive() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertEquals(List.of(selected, between), engine.conversation().getMessages(),
            "2.1.197 uses Array.lastIndexOf for conversation rewind");
    }

    @Test
    void conversationRestoreRebasesTranscriptPersistenceToTheRetainedTail() {
        UserMessage first = realUser("first prompt");
        AssistantMessage firstAnswer = new AssistantMessage(
            "first-answer", AssistantContent.of(List.of(new TextBlock("first answer"))));
        UserMessage selected = realUser("retry this prompt");
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .initialMessages(List.of(first, firstAnswer, selected))
            .build());
        AtomicReference<List<Message>> retainedForTranscript = new AtomicReference<>();
        engine.execution().setTranscriptSink(new TranscriptSink() {
            @Override public void record(String sessionId, Message message) {}

            @Override public void rewindConversation(
                    String sessionId, List<Message> retainedMessages) {
                retainedForTranscript.set(List.copyOf(retainedMessages));
            }
        });
        SessionController controller = new SessionController(
            null, null, engine, null, new MessagePanel(), new MessageHistory(),
            new MessageCollapser(null, false) {
                @Override public void resetTurn() {}
            }, new InputPanel(), null, null, null, null, null, null, null);

        controller.editMessageFromActions(selected.uuid());

        assertEquals(List.of(first, firstAnswer), retainedForTranscript.get(),
            "the next persisted prompt must fork from the same prefix kept in memory");
    }

    @Test
    void rewindingAcrossRefusalFallbackRestoresThePreviousSessionModel() {
        String originalModel = "claude-sonnet-4-6";
        String fallbackModel = "claude-opus-4-6";
        AssistantMessage earlierAnswer = new AssistantMessage("assistant", AssistantContent.apiResponse(
            "msg-assistant", List.of(new TextBlock("earlier answer")), null,
            originalModel, "end_turn", null));
        var remoteImage = JsonNodeFactory.instance.objectNode()
            .put("type", "url")
            .put("url", "https://example.invalid/image.png");
        UserMessage selected = new UserMessage(
            "selected", MessageContent.ofBlocks(List.of(new ImageBlock(remoteImage))));
        Message fallback = RefusalFallbackAnnouncement.row(
            "fallback", originalModel, fallbackModel, null, null, List.of(), selected.uuid());
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .model(originalModel)
            .modelPreference(originalModel)
            .initialMessages(List.of(earlierAnswer, selected, fallback))
            .build());
        engine.getConfig().setMainLoopModelOverride(fallbackModel);
        MessageCollapser collapser = new MessageCollapser(null, false) {
            @Override public void resetTurn() {}
        };
        SessionController controller = new SessionController(
            null, null, engine, null, new MessagePanel(), new MessageHistory(),
            collapser, null, null, null, null, null, null, null, null);

        controller.editMessageFromActions(selected.uuid());

        assertEquals(originalModel, engine.getConfig().modelPreference());
        assertEquals(originalModel, engine.getConfig().model());
        assertEquals(List.of(earlierAnswer), engine.conversation().getMessages());
    }

    @Test
    void automaticCancelRewindAcrossRefusalFallbackRestoresThePreviousSessionModel() {
        String originalModel = "claude-sonnet-4-6";
        String fallbackModel = "claude-opus-4-6";
        AssistantMessage earlierAnswer = new AssistantMessage("assistant", AssistantContent.apiResponse(
            "msg-assistant", List.of(new TextBlock("earlier answer")), null,
            originalModel, "end_turn", null));
        UserMessage selected = realUser("retry this prompt");
        Message fallback = RefusalFallbackAnnouncement.row(
            "fallback", originalModel, fallbackModel, null, null, List.of(), selected.uuid());
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .model(originalModel)
            .modelPreference(originalModel)
            .initialMessages(List.of(earlierAnswer, selected, fallback))
            .build());
        engine.getConfig().setMainLoopModelOverride(fallbackModel);
        engine.forks().getNestedMemoryAttachmentTriggers().add("/tmp/pending-memory-trigger.md");
        MessageCollapser collapser = new MessageCollapser(null, false) {
            @Override public void resetTurn() {}
        };
        SessionController controller = new SessionController(
            null, null, engine, null, new MessagePanel(), new MessageHistory(),
            collapser, null, null, null, null, null, null, null, null);
        AtomicBoolean rewindStateReset = new AtomicBoolean(false);
        controller.setRewindStateReset(() -> rewindStateReset.set(true));

        UserMessage removed = controller.rewindToBeforeLastRealUserMessage();

        assertSame(selected, removed);
        assertEquals(originalModel, engine.getConfig().modelPreference());
        assertEquals(originalModel, engine.getConfig().model());
        assertEquals(List.of(earlierAnswer), engine.conversation().getMessages());
        assertTrue(engine.forks().getNestedMemoryAttachmentTriggers().isEmpty());
        assertTrue(rewindStateReset.get(),
            "rewind resets the released background-wait accounting state");
    }

    @Test
    void summarizeFromRestoresTheSelectedPromptIntoTheInput() throws Exception {
        UserMessage selected = realUser("retry this prompt");
        AtomicReference<String> direction = new AtomicReference<>();
        AtomicBoolean manualCompactPrepared = new AtomicBoolean(false);
        MessageCompactor compactor = new MessageCompactor() {
            @Override public MicrocompactResult microcompactMessages(List<Message> messages) {
                return new MicrocompactResult(messages);
            }

            @Override public boolean shouldAutoCompact(
                    List<Message> messages, String model, String querySource) {
                return false;
            }

            @Override public CompactionResult compactConversation(
                    List<Message> messages, boolean isAutoCompact) {
                throw new UnsupportedOperationException();
            }

            @Override public void prepareManualCompact() {
                manualCompactPrepared.set(true);
            }

            @Override public List<Message> partialCompactAndAssemble(
                    List<Message> messages, int pivotIndex, String selectedDirection,
                    String feedback) {
                assertTrue(manualCompactPrepared.get(),
                    "partial compact must not inherit the interrupted turn's cancellation state");
                direction.set(selectedDirection);
                return List.of(realUser("summary"));
            }
        };
        DefaultQuerySession engine = new DefaultQuerySession(
            QuerySessionSpec.builder()
                .llmClient(NOOP_CLIENT)
                .initialMessages(List.of(selected))
                .fileHistoryEnabled(true)
                .workingDirectory(tempDir.toString())
                .build(),
            compactor);
        AtomicBoolean postCompactCleanupRan = new AtomicBoolean(false);
        engine.execution().setPostCompactCallback(() -> postCompactCleanupRan.set(true));
        engine.setPreviousTurnTools(List.of("Read"));
        SystemPromptSectionResolver.clearAll();
        SystemPromptSectionResolver.resolve(List.of(
            SystemPromptSection.cached("rewind-partial-compact-test", () -> "cached")));
        assertTrue(SystemPromptSectionResolver.cacheSize() > 0);
        engine.forks().getLoadedNestedMemoryPaths().add("/tmp/stale-nested-memory.md");
        AtomicReference<String> contextualHint = new AtomicReference<>();
        AtomicReference<Long> contextualHintTimeout = new AtomicReference<>();
        InputPanel input = new InputPanel() {
            @Override public void showTransientHint(String text, long timeoutMs) {
                contextualHint.set(text);
                contextualHintTimeout.set(timeoutMs);
            }
        };
        MessagePanel panel = new MessagePanel();
        MessageSelectorDialog dialog = new MessageSelectorDialog();
        MessageCollapser collapser = new MessageCollapser(
            new LanternaMessageDispatcher(), false);
        SessionController controller = new SessionController(
            null, null, engine, null, panel, new MessageHistory(),
            collapser, input, dialog, null, null, null, null, null, null);

        controller.showMessageSelector();
        dialog.handleKey(new KeyStroke(
            KeyType.ARROW_UP), new AtomicBoolean(true));
        dialog.handleKey(new KeyStroke(
            KeyType.ENTER), new AtomicBoolean(true));
        dialog.handleKey(new KeyStroke(
            KeyType.ARROW_DOWN), new AtomicBoolean(true));
        dialog.handleKey(new KeyStroke(
            KeyType.ENTER), new AtomicBoolean(true));
        dialog.handleKey(new KeyStroke(
            KeyType.ENTER), new AtomicBoolean(true));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (input.getText().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertEquals("from", direction.get());
        assertTrue(manualCompactPrepared.get());
        assertTrue(postCompactCleanupRan.get(),
            "partial compact must run the same cache cleanup as full compaction");
        assertEquals(0, SystemPromptSectionResolver.cacheSize());
        assertTrue(engine.forks().getLoadedNestedMemoryPaths().isEmpty());
        assertTrue(engine.getPreviousTurnTools().isEmpty());
        assertEquals("retry this prompt", input.getText());
        assertTrue(engine.hasCompactionOccurred());
        assertFalse(dialog.isActive());
        assertEquals("Conversation summarized (Ctrl+O for history)", contextualHint.get());
        assertEquals(8_000L, contextualHintTimeout.get());
        assertFalse(Strings.CS.contains(panelText(panel), "Conversation summarized"),
            "the released contextual notification must not become permanent transcript UI");
    }

    @Test
    void partialCompactRunsThe197HookLifecycleAndKeepsUserContextMetadataSeparate()
            throws Exception {
        UserMessage selected = realUser("selected prompt");
        AtomicReference<String> compactorFeedback = new AtomicReference<>();
        AtomicReference<String> compactorInstructions = new AtomicReference<>();
        AtomicReference<String> preHookInstructions = new AtomicReference<>("not-called");
        AtomicReference<String> postHookSummary = new AtomicReference<>();
        AtomicReference<Long> postHookTokens = new AtomicReference<>();
        List<String> progress = new ArrayList<>();
        MessageCompactor compactor = new MessageCompactor() {
            @Override public MicrocompactResult microcompactMessages(List<Message> messages) {
                return new MicrocompactResult(messages);
            }

            @Override public boolean shouldAutoCompact(
                    List<Message> messages, String model, String querySource) {
                return false;
            }

            @Override public CompactionResult compactConversation(
                    List<Message> messages, boolean isAutoCompact) {
                throw new UnsupportedOperationException();
            }

            @Override public long contextTokenCount(List<Message> messages, String model) {
                return 77L;
            }

            @Override public PartialCompactOutput partialCompact(
                    List<Message> messages, int pivotIndex, String direction,
                    String feedback, String customInstructions) {
                compactorFeedback.set(feedback);
                compactorInstructions.set(customInstructions);
                return new PartialCompactOutput(
                    List.of(
                        new SystemMessage("boundary", "compact_boundary", "info", "compacted"),
                        partialSummary(direction, messages.size() - pivotIndex)),
                    new Usage(10, 4, 3, 2), "raw compact summary");
            }
        };
        DefaultQuerySession engine = new DefaultQuerySession(
            QuerySessionSpec.builder()
                .llmClient(NOOP_CLIENT)
                .initialMessages(List.of(selected))
                .build(),
            compactor);
        engine.execution().setOnCompactProgress(event -> progress.add(switch (event) {
            case CompactProgressEvent.HooksStart hooks -> hooks.hookType();
            case CompactProgressEvent.CompactStart _ -> "compact_start";
            case CompactProgressEvent.CompactEnd _ -> "compact_end";
        }));
        engine.execution().setHookDispatcher(new HookDispatcher() {
            @Override public boolean dispatchPreToolUse(
                    String toolName, JsonNode input, String toolUseId) {
                return true;
            }
            @Override public void dispatchPostToolUse(
                    String toolName, JsonNode input, JsonNode output, String toolUseId) { }
            @Override public void dispatchUserPromptSubmit(String prompt) { }
            @Override public void dispatchSessionStart(String trigger) { }
            @Override public void dispatchStop(String reason) { }

            @Override public HookOutcome dispatchPreCompactWithOutcome(
                    String trigger, String customInstructions, long preTokenCount) {
                assertEquals("manual", trigger);
                assertEquals(77L, preTokenCount);
                preHookInstructions.set(customInstructions);
                return new HookOutcome(true, "hook instruction", List.of());
            }

            @Override public HookOutcome dispatchSessionStartWithOutcome(String trigger) {
                assertEquals("compact", trigger);
                return new HookOutcome(true, "session-start context", List.of());
            }

            @Override public HookOutcome dispatchPostCompactWithOutcome(
                    String trigger, String compactSummary, long postTokenCount) {
                assertEquals("manual", trigger);
                postHookSummary.set(compactSummary);
                postHookTokens.set(postTokenCount);
                return HookOutcome.PROCEED;
            }
        });
        SessionController controller = new SessionController(
            null, null, engine, null, new MessagePanel(), new MessageHistory(),
            new MessageCollapser(new LanternaMessageDispatcher(), false), new InputPanel(),
            new MessageSelectorDialog(), null, null, null, null, null, null);

        CompletionStage<?> completion = controller.runSummarize(
            selected,
            MessageSelectorDialog.RestoreAction.SUMMARIZE_FROM, "user detail",
            (Runnable) () -> { }, (Consumer<String>) _ -> { });
        completion.toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertNull(preHookInstructions.get(),
            "197 sends null customInstructions to PreCompact for message-selector feedback");
        assertEquals("user detail", compactorFeedback.get());
        assertEquals("hook instruction\nUser context: user detail",
            compactorInstructions.get());
        assertEquals("raw compact summary", postHookSummary.get());
        assertEquals(19L, postHookTokens.get());
        assertEquals(List.of(
            "pre_compact", "compact_start", "session_start", "post_compact", "compact_end"),
            progress);
        assertTrue(engine.conversation().getMessages().stream()
            .filter(UserMessage.class::isInstance)
            .map(UserMessage.class::cast)
            .map(UserMessage::message)
            .anyMatch(content -> Strings.CS.contains(
                content.text(), "<system-reminder>\nsession-start context\n</system-reminder>")));
    }

    @Test
    void summarizeBeforeTheActiveCompactBoundaryWarnsAndClosesLike197() throws Exception {
        UserMessage old = realUser("old pre-compact prompt");
        SystemMessage boundary = new SystemMessage(
            "boundary", "compact_boundary", "info", "Conversation compacted");
        UserMessage current = realUser("current prompt");
        AtomicBoolean compactCalled = new AtomicBoolean(false);
        MessageCompactor compactor = new MessageCompactor() {
            @Override public MicrocompactResult microcompactMessages(List<Message> messages) {
                return new MicrocompactResult(messages);
            }

            @Override public boolean shouldAutoCompact(
                    List<Message> messages, String model, String querySource) {
                return false;
            }

            @Override public CompactionResult compactConversation(
                    List<Message> messages, boolean isAutoCompact) {
                throw new UnsupportedOperationException();
            }

            @Override public List<Message> partialCompactAndAssemble(
                    List<Message> messages, int pivotIndex, String direction, String feedback) {
                compactCalled.set(true);
                return List.of(realUser("incorrect summary"));
            }
        };
        DefaultQuerySession engine = new DefaultQuerySession(
            QuerySessionSpec.builder()
                .llmClient(NOOP_CLIENT)
                .initialMessages(List.of(old, boundary, current))
                .fileHistoryEnabled(true)
                .workingDirectory(tempDir.toString())
                .build(),
            compactor);
        MessagePanel panel = new MessagePanel();
        MessageSelectorDialog dialog = new MessageSelectorDialog();
        SessionController controller = new SessionController(
            null, null, engine, null, panel, new MessageHistory(),
            new MessageCollapser(new LanternaMessageDispatcher(), false) {
                @Override public void resetTurn() {}
            }, new InputPanel(), dialog, null, null, null, null, null, null);

        AtomicBoolean success = new AtomicBoolean(false);
        AtomicReference<String> failure = new AtomicReference<>();
        CompletionStage<?> completion = controller.runSummarize(
            old,
            MessageSelectorDialog.RestoreAction.SUMMARIZE_FROM, null,
            (Runnable) () -> success.set(true),
            (Consumer<String>) failure::set);
        completion.toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertFalse(compactCalled.get());
        assertTrue(success.get());
        assertNull(failure.get());
        SystemMessage warning = assertInstanceOf(
            SystemMessage.class, engine.conversation().getMessages().getLast());
        assertEquals("warning", warning.level());
        assertEquals(
            "That message is no longer in the active context. Choose a more recent message.",
            warning.content());
        var snapshot = MessagePanel.class.getDeclaredMethod("snapshotStyledLines");
        snapshot.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<MessagePanel.StyledLine> lines =
            (List<MessagePanel.StyledLine>) snapshot.invoke(panel);
        String rendered = lines.stream()
            .map(MessagePanel.StyledLine::text)
            .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(Strings.CS.contains(rendered,
            "That message is no longer in the active context. Choose a more recent message."),
            rendered);
        assertFalse(Strings.CS.contains(rendered, "Summarize failed:"), rendered);
    }

    @Test
    void summarizeSelectionReplacedByAnEqualLiveMessageWarnsInsteadOfCompacting()
            throws Exception {
        UserMessage selected = realUser("selected prompt");
        AtomicBoolean compactCalled = new AtomicBoolean(false);
        MessageCompactor compactor = partialCompactor((_, _, _) -> {
            compactCalled.set(true);
            return List.of(realUser("incorrect summary"));
        });
        DefaultQuerySession engine = new DefaultQuerySession(
            QuerySessionSpec.builder()
                .llmClient(NOOP_CLIENT)
                .initialMessages(List.of(selected))
                .fileHistoryEnabled(true)
                .workingDirectory(tempDir.toString())
                .build(),
            compactor);
        MessageSelectorDialog dialog = new MessageSelectorDialog();
        SessionController controller = new SessionController(
            null, null, engine, null, new MessagePanel(), new MessageHistory(),
            new MessageCollapser(new LanternaMessageDispatcher(), false) {
                @Override public void resetTurn() {}
            }, new InputPanel(), dialog, null, null, null, null, null, null);

        controller.showMessageSelector();
        dialog.handleKey(new KeyStroke(
            KeyType.ARROW_UP), new AtomicBoolean(true));
        dialog.handleKey(new KeyStroke(
            KeyType.ENTER), new AtomicBoolean(true));
        UserMessage replacement = equalCopy(selected);
        engine.conversation().loadMessages(List.of(replacement));
        dialog.handleKey(new KeyStroke(
            KeyType.ARROW_DOWN), new AtomicBoolean(true));
        dialog.handleKey(new KeyStroke(
            KeyType.ENTER), new AtomicBoolean(true));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (dialog.isActive() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertFalse(compactCalled.get());
        assertFalse(dialog.isActive());
        assertSame(replacement, engine.conversation().getMessages().getFirst());
        SystemMessage warning = assertInstanceOf(
            SystemMessage.class, engine.conversation().getMessages().getLast());
        assertEquals(
            "That message is no longer in the active context. Choose a more recent message.",
            warning.content());
    }

    @Test
    void staleSummarizeWarnsBeforeCheckingWhetherACompactorIsAvailable() throws Exception {
        UserMessage selected = realUser("selected prompt");
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .initialMessages(List.of(selected))
            .build());
        engine.conversation().loadMessages(List.of(equalCopy(selected)));
        SessionController controller = new SessionController(
            null, null, engine, null, new MessagePanel(), new MessageHistory(),
            new MessageCollapser(new LanternaMessageDispatcher(), false) {
                @Override public void resetTurn() {}
            }, new InputPanel(), new MessageSelectorDialog(), null,
            null, null, null, null, null);
        AtomicBoolean success = new AtomicBoolean(false);
        AtomicReference<String> failure = new AtomicReference<>();

        controller.runSummarize(selected,
            MessageSelectorDialog.RestoreAction.SUMMARIZE_FROM, null,
            () -> success.set(true), failure::set).toCompletableFuture()
            .get(2, TimeUnit.SECONDS);

        assertTrue(success.get());
        assertNull(failure.get());
        SystemMessage warning = assertInstanceOf(
            SystemMessage.class, engine.conversation().getMessages().getLast());
        assertEquals(
            "That message is no longer in the active context. Choose a more recent message.",
            warning.content());
    }

    @Test
    void summarizeCapturesItsLiveInputBeforeWaitingForTheTurnDeferrer() throws Exception {
        UserMessage selected = realUser("selected prompt");
        AtomicReference<List<Message>> compactInput = new AtomicReference<>();
        MessageCompactor compactor = partialCompactor((messages, _, _) -> {
            compactInput.set(List.copyOf(messages));
            return List.of(partialSummary("from", messages.size()));
        });
        DefaultQuerySession engine = new DefaultQuerySession(
            QuerySessionSpec.builder()
                .llmClient(NOOP_CLIENT)
                .initialMessages(List.of(selected))
                .fileHistoryEnabled(true)
                .workingDirectory(tempDir.toString())
                .build(),
            compactor);
        MessageSelectorDialog dialog = new MessageSelectorDialog();
        SessionController controller = new SessionController(
            null, null, engine, null, new MessagePanel(), new MessageHistory(),
            new MessageCollapser(new LanternaMessageDispatcher(), false) {
                @Override public void resetTurn() {}
            }, new InputPanel(), dialog, null, null, null, null, null, null);
        AtomicReference<Supplier<? extends CompletionStage<?>>> deferred = new AtomicReference<>();
        controller.setAsyncRewindDeferrer(deferred::set);

        controller.showMessageSelector();
        dialog.handleKey(new KeyStroke(
            KeyType.ARROW_UP), new AtomicBoolean(true));
        dialog.handleKey(new KeyStroke(
            KeyType.ENTER), new AtomicBoolean(true));
        dialog.handleKey(new KeyStroke(
            KeyType.ARROW_DOWN), new AtomicBoolean(true));
        dialog.handleKey(new KeyStroke(
            KeyType.ENTER), new AtomicBoolean(true));
        assertNotNull(deferred.get());

        SystemMessage lateTail = new SystemMessage(
            "late-tail", "warning", "warning", "arrived while waiting for idle");
        engine.conversation().appendInMemoryMessage(lateTail);
        deferred.get().get().toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals(List.of(selected), compactInput.get(),
            "197 closes over the live render state at confirmation time");
    }

    @Test
    void summarizeFromRetainsTheLatestLivePrefixAtCompletion() throws Exception {
        UserMessage oldPrefix = realUser("old prefix");
        UserMessage selected = realUser("selected prompt");
        AssistantMessage later = new AssistantMessage(
            "later", AssistantContent.of(List.of(new TextBlock("later response"))));
        CountDownLatch compactStarted = new CountDownLatch(1);
        CountDownLatch allowCompactToFinish = new CountDownLatch(1);
        SystemMessage boundary = new SystemMessage(
            "new-boundary", "compact_boundary", "info", "Conversation compacted");
        UserMessage summary = partialSummary("from", 2);
        MessageCompactor compactor = partialCompactor((messages, pivotIndex, direction) -> {
            assertSame(oldPrefix, messages.getFirst());
            assertEquals(1, pivotIndex);
            assertEquals("from", direction);
            compactStarted.countDown();
            try {
                assertTrue(allowCompactToFinish.await(2, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return List.of(boundary, summary);
        });
        DefaultQuerySession engine = new DefaultQuerySession(
            QuerySessionSpec.builder()
                .llmClient(NOOP_CLIENT)
                .initialMessages(List.of(oldPrefix, selected, later))
                .fileHistoryEnabled(true)
                .workingDirectory(tempDir.toString())
                .build(),
            compactor);
        MessageSelectorDialog dialog = new MessageSelectorDialog();
        SessionController controller = new SessionController(
            null, null, engine, null, new MessagePanel(), new MessageHistory(),
            new MessageCollapser(new LanternaMessageDispatcher(), false) {
                @Override public void resetTurn() {}
            }, new InputPanel(), dialog, null, null, null, null, null, null);

        controller.showMessageSelector();
        dialog.handleKey(new KeyStroke(
            KeyType.ARROW_UP), new AtomicBoolean(true));
        dialog.handleKey(new KeyStroke(
            KeyType.ENTER), new AtomicBoolean(true));
        dialog.handleKey(new KeyStroke(
            KeyType.ARROW_DOWN), new AtomicBoolean(true));
        dialog.handleKey(new KeyStroke(
            KeyType.ENTER), new AtomicBoolean(true));

        assertTrue(compactStarted.await(2, TimeUnit.SECONDS));
        UserMessage latestPrefix = realUser("latest live prefix");
        engine.conversation().loadMessages(List.of(latestPrefix, selected, later));
        allowCompactToFinish.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (dialog.isActive() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertFalse(dialog.isActive());
        assertSame(latestPrefix, engine.conversation().getMessagesForRewind().getFirst(),
            "197 applies the FROM prefix against the live state updater at completion");
        assertEquals(List.of(latestPrefix, boundary, summary),
            engine.conversation().getMessagesForRewind());
    }

    @Test
    void summarizeFailureStaysInTheSelectorInsteadOfAppendingPermanentUi() throws Exception {
        UserMessage selected = realUser("selected prompt");
        MessageCompactor compactor = partialCompactor((_, _, _) -> {
            throw new IllegalStateException("boom");
        });
        DefaultQuerySession engine = new DefaultQuerySession(
            QuerySessionSpec.builder()
                .llmClient(NOOP_CLIENT)
                .initialMessages(List.of(selected))
                .build(),
            compactor);
        MessagePanel panel = new MessagePanel();
        SessionController controller = new SessionController(
            null, null, engine, null, panel, new MessageHistory(),
            new MessageCollapser(new LanternaMessageDispatcher(), false), new InputPanel(),
            new MessageSelectorDialog(), null, null, null, null, null, null);

        AtomicReference<String> failure = new AtomicReference<>();
        CompletionStage<?> completion = controller.runSummarize(
            selected,
            MessageSelectorDialog.RestoreAction.SUMMARIZE_FROM, null,
            (Runnable) () -> {}, (Consumer<String>) failure::set);
        completion.toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals("Error: boom", failure.get());
        assertFalse(Strings.CS.contains(panelText(panel), "Summarize failed:"),
            "2.1.197 keeps this failure inside the rewind selector");
    }

    @Test
    void unavailableCompactorFailureStaysInTheSelectorInsteadOfAppendingPermanentUi()
            throws Exception {
        UserMessage selected = realUser("selected prompt");
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .initialMessages(List.of(selected))
            .build());
        MessagePanel panel = new MessagePanel();
        SessionController controller = new SessionController(
            null, null, engine, null, panel, new MessageHistory(),
            new MessageCollapser(new LanternaMessageDispatcher(), false), new InputPanel(),
            new MessageSelectorDialog(), null, null, null, null, null, null);

        AtomicReference<String> failure = new AtomicReference<>();
        CompletionStage<?> completion = controller.runSummarize(
            selected,
            MessageSelectorDialog.RestoreAction.SUMMARIZE_FROM, null,
            (Runnable) () -> {}, (Consumer<String>) failure::set);
        completion.toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals("compaction service is not available in this session.", failure.get());
        assertFalse(Strings.CS.contains(panelText(panel), "Summarize failed:"),
            "2.1.197 keeps unavailable-service failures inside the rewind selector");
    }

    @Test
    void messageSelectorIncludesTheRetainedPreCompactScrollbackInterval() throws Exception {
        UserMessage old = realUser("old scrollback prompt");
        SystemMessage boundary = new SystemMessage(
            "boundary", "compact_boundary", "info", "Conversation compacted");
        UserMessage current = realUser("current prompt");
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .initialMessages(List.of(boundary, current))
            .build()) {
            @Override public List<Message> getMessagesForRewind() {
                return List.of(old, boundary, current);
            }
        };
        MessageSelectorDialog dialog = new MessageSelectorDialog();
        SessionController controller = new SessionController(
            null, null, engine, null, new MessagePanel(), new MessageHistory(),
            new MessageCollapser(null, false) {
                @Override public void resetTurn() {}
            }, new InputPanel(), dialog, null, null, null, null, null, null);

        controller.showMessageSelector();

        var entriesField = MessageSelectorDialog.class.getDeclaredField("entries");
        entriesField.setAccessible(true);
        List<?> entries = (List<?>) entriesField.get(dialog);
        List<String> displays = new ArrayList<>();
        for (Object entry : entries) {
            var display = entry.getClass().getDeclaredMethod("display");
            display.setAccessible(true);
            displays.add((String) display.invoke(entry));
        }
        assertTrue(displays.contains("old scrollback prompt"), displays.toString());
        assertTrue(displays.contains("current prompt"), displays.toString());
    }

    @Test
    void summarizeFromReplacesTheVisibleTailWithThe197SummaryCard() throws Exception {
        UserMessage first = realUser("first prompt");
        UserMessage selected = realUser("selected prompt");
        AssistantMessage later = new AssistantMessage(
            "later", AssistantContent.of(List.of(new TextBlock("later response"))));
        UserMessage summary = partialSummary("from", 2);
        SystemMessage boundary = new SystemMessage(
            "new-boundary", "compact_boundary", "info", "Conversation compacted");
        MessageCompactor compactor = partialCompactor((messages, _, _) ->
            List.of(boundary, messages.getFirst(), summary));
        DefaultQuerySession engine = new DefaultQuerySession(
            QuerySessionSpec.builder()
                .llmClient(NOOP_CLIENT)
                .initialMessages(List.of(first, selected, later))
                .build(),
            compactor);
        MessagePanel panel = new MessagePanel();
        MessageHistory history = new MessageHistory();
        SessionController controller = new SessionController(
            null, null, engine, null, panel, history,
            new MessageCollapser(new LanternaMessageDispatcher(), false), new InputPanel(),
            new MessageSelectorDialog(), null, null, null, null, null, null);
        controller.replayLoadedMessages(List.of(first, selected, later));

        invokeSummarize(controller, selected,
            MessageSelectorDialog.RestoreAction.SUMMARIZE_FROM);

        String rendered = panelText(panel);
        assertTrue(Strings.CS.contains(rendered, "first prompt"), rendered);
        assertFalse(Strings.CS.contains(rendered, "selected prompt"), rendered);
        assertFalse(Strings.CS.contains(rendered, "later response"), rendered);
        assertTrue(Strings.CS.contains(rendered, "Summarized conversation"), rendered);
        assertFalse(Strings.CS.contains(rendered, "Conversation compacted"), rendered);
        assertEquals(List.of(first, boundary, first, summary),
            engine.conversation().getMessagesForRewind());
    }

    @Test
    void summarizeUpToRebuildsTheVisibleConversationFromTheSummaryAndKeptTail()
            throws Exception {
        UserMessage first = realUser("first prompt");
        UserMessage selected = realUser("selected prompt");
        AssistantMessage later = new AssistantMessage(
            "later", AssistantContent.of(List.of(new TextBlock("later response"))));
        UserMessage summary = partialSummary("up_to", 1);
        SystemMessage boundary = new SystemMessage(
            "new-boundary", "compact_boundary", "info", "Conversation compacted");
        MessageCompactor compactor = partialCompactor((_, _, _) ->
            List.of(boundary, summary, selected, later));
        DefaultQuerySession engine = new DefaultQuerySession(
            QuerySessionSpec.builder()
                .llmClient(NOOP_CLIENT)
                .initialMessages(List.of(first, selected, later))
                .build(),
            compactor);
        MessagePanel panel = new MessagePanel();
        MessageHistory history = new MessageHistory();
        SessionController controller = new SessionController(
            null, null, engine, null, panel, history,
            new MessageCollapser(new LanternaMessageDispatcher(), false), new InputPanel(),
            new MessageSelectorDialog(), null, null, null, null, null, null);
        controller.replayLoadedMessages(List.of(first, selected, later));

        invokeSummarize(controller, selected,
            MessageSelectorDialog.RestoreAction.SUMMARIZE_UP_TO);

        String rendered = panelText(panel);
        assertFalse(Strings.CS.contains(rendered, "first prompt"), rendered);
        assertTrue(Strings.CS.contains(rendered, "selected prompt"), rendered);
        assertTrue(Strings.CS.contains(rendered, "later response"), rendered);
        assertTrue(Strings.CS.contains(rendered, "Summarized conversation"), rendered);
        assertFalse(Strings.CS.contains(rendered, "Conversation compacted"), rendered);
        assertEquals(List.of(boundary, summary, selected, later),
            engine.conversation().getMessagesForRewind());
    }

    @Test
    void restoreFailureFromSessionControllerLeavesTheSelectorOpen() {
        UserMessage selected = realUser("retry this prompt");
        DefaultQuerySession engine = new DefaultQuerySession(
            QuerySessionSpec.builder()
                .llmClient(NOOP_CLIENT)
                .initialMessages(List.of(selected))
                .fileHistoryEnabled(true)
                .workingDirectory(tempDir.toString())
                .build());
        InputPanel failingInput = new InputPanel() {
            @Override public void setRestoredText(String text) {
                throw new IllegalStateException("boom");
            }
        };
        MessageSelectorDialog dialog = new MessageSelectorDialog();
        MessageCollapser collapser = new MessageCollapser(null, false) {
            @Override public void resetTurn() {}
        };
        SessionController controller = new SessionController(
            null, null, engine, null, new MessagePanel(), new MessageHistory(),
            collapser, failingInput, dialog, null, null, null, null, null, null);

        controller.showMessageSelector();
        dialog.handleKey(new KeyStroke(
            KeyType.ARROW_UP), new AtomicBoolean(true));
        dialog.handleKey(new KeyStroke(
            KeyType.ENTER), new AtomicBoolean(true));
        dialog.handleKey(new KeyStroke(
            KeyType.ENTER), new AtomicBoolean(true));

        assertTrue(dialog.isActive(), "restore errors stay inside the rewind overlay");
    }

    @Test
    void restoreSelectionUsesTheExclusiveAsyncRewindPath() {
        UserMessage selected = realUser("restore me");
        DefaultQuerySession engine = new DefaultQuerySession(
            QuerySessionSpec.builder()
                .llmClient(NOOP_CLIENT)
                .initialMessages(List.of(selected))
                .fileHistoryEnabled(true)
                .workingDirectory(tempDir.toString())
                .build());
        MessageSelectorDialog dialog = new MessageSelectorDialog();
        SessionController controller = new SessionController(
            null, null, engine, null, new MessagePanel(), new MessageHistory(),
            new MessageCollapser(null, false) {
                @Override public void resetTurn() {}
            }, new InputPanel(), dialog, null, null, null, null, null, null);
        AtomicBoolean synchronousPathUsed = new AtomicBoolean(false);
        AtomicReference<Supplier<? extends CompletionStage<?>>> deferred = new AtomicReference<>();
        controller.setRewindDeferrer(_ -> synchronousPathUsed.set(true));
        controller.setAsyncRewindDeferrer(deferred::set);

        controller.showMessageSelector();
        dialog.handleKey(new KeyStroke(
            KeyType.ARROW_UP), new AtomicBoolean(true));
        dialog.handleKey(new KeyStroke(
            KeyType.ENTER), new AtomicBoolean(true));
        dialog.handleKey(new KeyStroke(
            KeyType.ENTER), new AtomicBoolean(true));

        assertFalse(synchronousPathUsed.get());
        assertNotNull(deferred.get());
    }

    @Test
    void rewindPreviousSessionRowUsesTheRecordedParentSession() {
        UserMessage selected = realUser("current session prompt");
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .initialMessages(List.of(selected))
            .build());
        AtomicBoolean parentFileRequested = new AtomicBoolean(false);
        InteractiveSessionPort sessions = new InteractiveSessionPort() {
            @Override public String parentSessionId(String cwd, String sessionId) {
                return "parent-session";
            }

            @Override public Path sessionFile(String cwd, String sessionId) {
                parentFileRequested.set(true);
                return tempDir.resolve(sessionId + ".jsonl");
            }
        };
        MessageSelectorDialog dialog = new MessageSelectorDialog();
        MessageCollapser collapser = new MessageCollapser(null, false) {
            @Override public void resetTurn() {}
        };
        SessionController controller = new SessionController(
            null, null, engine, null, new MessagePanel(), new MessageHistory(),
            collapser, null, dialog, null, null, null, null, null, null,
            null, null, null, sessions, null);

        controller.showMessageSelector();
        dialog.handleKey(new KeyStroke(
            KeyType.ARROW_UP), new AtomicBoolean(true));
        dialog.handleKey(new KeyStroke(
            KeyType.ARROW_UP), new AtomicBoolean(true));
        dialog.handleKey(new KeyStroke(
            KeyType.ENTER), new AtomicBoolean(true));

        assertTrue(parentFileRequested.get());
    }

    @FunctionalInterface
    private interface PartialAssembly {
        List<Message> assemble(List<Message> messages, int pivotIndex, String direction);
    }

    private static MessageCompactor partialCompactor(PartialAssembly assembly) {
        return new MessageCompactor() {
            @Override public MicrocompactResult microcompactMessages(List<Message> messages) {
                return new MicrocompactResult(messages);
            }

            @Override public boolean shouldAutoCompact(
                    List<Message> messages, String model, String querySource) {
                return false;
            }

            @Override public CompactionResult compactConversation(
                    List<Message> messages, boolean isAutoCompact) {
                throw new UnsupportedOperationException();
            }

            @Override public List<Message> partialCompactAndAssemble(
                    List<Message> messages, int pivotIndex, String direction, String feedback) {
                return assembly.assemble(messages, pivotIndex, direction);
            }
        };
    }

    private static UserMessage partialSummary(String direction, int count) {
        return new UserMessage(
            "summary-" + direction, MessageContent.ofText("compacted summary body"),
            false, true, null, MessageOrigin.COMPACT_SUMMARY,
            null, Instant.now(), null, null, null, null, null,
            null, null, null, null,
            new SummarizeMetadata(count, null, direction));
    }

    private static void invokeSummarize(
            SessionController controller, UserMessage selected,
            MessageSelectorDialog.RestoreAction action) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        controller.runSummarize(selected, action, null,
            (Runnable) completed::countDown,
            (Consumer<String>) error -> {
                failure.set(error);
                completed.countDown();
            });
        assertTrue(completed.await(2, TimeUnit.SECONDS), "partial compact did not finish");
        assertNull(failure.get(), failure.get());
    }

    private static String panelText(MessagePanel panel) throws Exception {
        var snapshot = MessagePanel.class.getDeclaredMethod("snapshotStyledLines");
        snapshot.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<MessagePanel.StyledLine> lines =
            (List<MessagePanel.StyledLine>) snapshot.invoke(panel);
        return lines.stream()
            .map(MessagePanel.StyledLine::text)
            .reduce("", (left, right) -> left + "\n" + right);
    }

    // ── textForResubmit ──────────────────────────────────────────────────────


    @Test
    void textForResubmit_plainText_returnedUnchanged() {
        assertEquals("fix the bug", SessionController.textForResubmit("fix the bug"));
    }

    @Test
    void textForResubmit_plainTextStripsIdeContextTags() {
        assertEquals("fix the bug\nnow",
            SessionController.textForResubmit(
                """
                <ide_opened_file>/tmp/Demo.java</ide_opened_file>
                fix the bug
                <ide_selection lines="1-2">ignored</ide_selection>
                now"""));
    }

    @Test
    void textForResubmit_bashInput_becomesBangPrefixed() {
        assertEquals("!ls -la",
            SessionController.textForResubmit("<bash-input>ls -la</bash-input>"));
    }

    @Test
    void textForResubmit_slashCommand_reassemblesNameAndArgs() {
        assertEquals("/model opus",
            SessionController.textForResubmit(
                "<command-name>/model</command-name><command-args>opus</command-args>"));
    }

    @Test
    void textForResubmit_slashCommandWithEmptyArgs_keepsEditableTrailingSpace() {
        assertEquals("/clear ",
            SessionController.textForResubmit(
                "<command-name>/clear</command-name><command-args></command-args>"));
    }

    @Test
    void textForResubmit_emptyBashTagRemainsOrdinaryPromptText() {
        assertEquals("<bash-input></bash-input>",
            SessionController.textForResubmit("<bash-input></bash-input>"));
    }

    @Test
    void textForResubmit_bashInputTakesPriorityOverCommandTags() {

        assertEquals("!echo <command-name>/x</command-name>",
            SessionController.textForResubmit(
                "<bash-input>echo <command-name>/x</command-name></bash-input>"));
    }

    @Test
    void restoredInput_keepsImageOnlyPromptChipsWithoutRequiringText() {
        var source = JsonNodeFactory.instance.objectNode()
            .put("type", "base64")
            .put("media_type", "image/png")
            .put("data", "abc123");
        UserMessage imageOnly = new UserMessage(
            "image-only", MessageContent.ofBlocks(List.of(new ImageBlock(source))));

        SessionController.RestoredInput restored = SessionController.restoredInput(imageOnly);

        assertNull(restored.text());
        assertEquals("abc123", restored.imageChips().get(1).content());
        assertTrue(restored.replaceImageChips());
    }

    @Test
    void restoredInput_remoteImageStillReplacesExistingImageChips() {
        var source = JsonNodeFactory.instance.objectNode()
            .put("type", "url")
            .put("url", "https://example.invalid/image.png");
        UserMessage imageOnly = new UserMessage(
            "remote-image", MessageContent.ofBlocks(List.of(new ImageBlock(source))));

        SessionController.RestoredInput restored = SessionController.restoredInput(imageOnly);

        assertTrue(restored.imageChips().isEmpty());
        assertTrue(restored.replaceImageChips());
    }

    @Test
    void restoredInput_emptyStringStillClearsThePrompt() {
        UserMessage empty = new UserMessage(
            "empty", MessageContent.ofText(""));

        SessionController.RestoredInput restored = SessionController.restoredInput(empty);

        assertEquals("", restored.text());
        assertTrue(restored.imageChips().isEmpty());
        assertFalse(restored.replaceImageChips());
    }

    @Test
    void restoredInput_whitespaceStringStillClearsThePrompt() {
        UserMessage whitespace = new UserMessage(
            "whitespace", MessageContent.ofText("   "));

        assertEquals("", SessionController.restoredInput(whitespace).text());
    }

    @Test
    void restoredSessionBadge_readsTheExactTranscriptAndNormalizesDefaultColor() throws Exception {
        Path transcript = tempDir.resolve("resumed.jsonl");
        Files.writeString(transcript, String.join("\n",
            "{\"type\":\"custom-title\",\"customTitle\":\"old title\"}",
            "{\"type\":\"agent-name\",\"agentName\":\"worktree agent\"}",
            "{\"type\":\"agent-color\",\"agentColor\":\"default\"}"));

        SessionController.RestoredSessionBadge badge =
            SessionController.restoredSessionBadge(transcript, metadataPort());

        assertEquals("worktree agent", badge.name());
        assertNull(badge.color(), "TS maps the default sentinel to no color override");
    }

    @Test
    void restoredSessionBadge_missingMetadataClearsThePreviousBadge() throws Exception {
        Path transcript = tempDir.resolve("plain.jsonl");
        Files.writeString(transcript, "{\"type\":\"user\"}\n");

        SessionController.RestoredSessionBadge badge =
            SessionController.restoredSessionBadge(transcript, metadataPort());

        assertNull(badge.name());
        assertNull(badge.color());
    }


    // ── GUI-thread affinity ──────────────────────────────────────────────────
    // /clear and /resume run on a slash-command virtual thread. A Lanterna
    // component mutated from there locks the component and then walks the
// parent chain for its theme, while a concurrent updateScreen holds the
    // parents and descends into the same component — an intermittent, silent
    // TUI freeze. Every UI touch on those paths must go through onGuiThread.

    private static final Path CONTROLLER = Path.of(
        "src/main/java/com/claudecode/ui/lanterna/repl/SessionController.java");

    @Test
    void clearConversationMutatesTheUiOnlyOnTheGuiThread() throws Exception {
        assertUiTouchesAreMarshalled("public void clearConversation()");
    }

    @Test
    void preparedSessionColorMutatesTheUiOnlyOnTheGuiThread() throws Exception {
        assertUiTouchesAreMarshalled(
            "void applyPreparedSessionColor(");
    }

    @Test
    void resumeCommitAppliesWorkerPreparedBadgeWithoutTranscriptIo() throws Exception {
        String source = Files.readString(CONTROLLER);
        String body = methodBody(source,
            "private void finishResume(PreparedSessionResume prepared,");

        assertTrue(body.contains("applyPreparedSessionColor("));
        assertFalse(body.contains("restoredSessionBadge("));
        assertFalse(body.contains("scanMetadata("));
        assertFalse(body.contains("reAppendSessionMetadata("));
    }

    @Test
    void directSessionSwitchScansMetadataOnAVirtualThread() throws Exception {
        String source = Files.readString(CONTROLLER);
        String body = methodBody(source, "void restoreSessionColor(String sessionId)");

        int worker = body.indexOf("Thread.ofVirtual()");
        assertTrue(worker >= 0);
        assertTrue(body.indexOf("sessions.sessionFile(") > worker);
        assertTrue(body.indexOf("restoredSessionBadge(") > worker);
    }

    private void assertUiTouchesAreMarshalled(String signature) throws Exception {
        String body = methodBody(Files.readString(CONTROLLER), signature);
        List<int[]> marshalled = marshalledRegions(body);

        for (String uiCall : List.of(
                "inputPanel.", "resetConversationSurface.run()", "refreshComplete()")) {
            for (int at = body.indexOf(uiCall); at >= 0; at = body.indexOf(uiCall, at + 1)) {
                int position = at;
                assertTrue(
                    marshalled.stream().anyMatch(r -> position > r[0] && position < r[1]),
                    () -> signature + " touches the UI off the GUI thread: " + uiCall);
            }
        }
    }

    /** {@code onGuiThread(...)} argument spans, located by brace/paren balance. */
    private static List<int[]> marshalledRegions(String body) {
        List<int[]> regions = new ArrayList<>();
        String marker = "onGuiThread(";
        for (int at = body.indexOf(marker); at >= 0; at = body.indexOf(marker, at + 1)) {
            int open = at + marker.length() - 1;
            regions.add(new int[] {open, matchingClose(body, open, '(', ')')});
        }
        assertFalse(regions.isEmpty(), "expected the UI half to be marshalled");
        return regions;
    }

    private static String methodBody(String source, String signature) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, () -> "method not found: " + signature);
        int open = source.indexOf('{', at);
        return source.substring(open, matchingClose(source, open, '{', '}') + 1);
    }

    private static int matchingClose(String source, int open, char opener, char closer) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == opener) depth++;
            else if (c == closer && --depth == 0) return i;
        }
        throw new AssertionError("unbalanced " + opener + " at " + open);
    }

    private static InteractiveSessionPort metadataPort() {
        return new InteractiveSessionPort() {
            @Override public MetadataSnapshot scanMetadata(Path transcript) {
                try {
                    String content = Files.readString(transcript);
                    String title = Strings.CS.contains(content, "old title") ? "old title" : null;
                    String name = Strings.CS.contains(content, "worktree agent")
                        ? "worktree agent" : null;
                    String color = Strings.CS.contains(content, "\"agentColor\":\"default\"")
                        ? "default" : null;
                    return new MetadataSnapshot(title, name, color, null);
                } catch (Exception _) {
                    return MetadataSnapshot.empty();
                }
            }
        };
    }



    @Test
    void combinedErrorMessage_bothSucceed_returnsNull() {
        assertNull(SessionController.combinedErrorMessage(null, null));
    }

    @Test
    void combinedErrorMessage_conversationOnlyFails() {
        assertEquals("Failed to restore the conversation:\nError: boom",
            SessionController.combinedErrorMessage(new RuntimeException("boom"), null));
    }

    @Test
    void combinedErrorMessage_codeOnlyFails() {
        assertEquals("Failed to restore the code:\nError: boom",
            SessionController.combinedErrorMessage(null, new RuntimeException("boom")));
    }

    @Test
    void combinedErrorMessage_bothFailUsesTheReleasedCodeFailureDetail() {
        assertEquals("Failed to restore the conversation and code:\nError: code-err",
            SessionController.combinedErrorMessage(
                new RuntimeException("conv-err"), new RuntimeException("code-err")));
    }

    @Test
    void combinedErrorMessage_usesExceptionTypeWhenTheMessageIsMissing() {
        assertEquals("Failed to restore the conversation:\nError: UnsupportedOperationException",
            SessionController.combinedErrorMessage(
                new UnsupportedOperationException(), null));
    }
}
