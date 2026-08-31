package com.claudecode.commands.impl.context;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.metadata.CommandMetadataEncoder;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.engine.CompactProgressEvent;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.engine.MessageCompactor;
import com.claudecode.core.message.*;
import com.claudecode.core.process.SubprocessEnvironment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/**
 * /compact — triggers conversation compaction.
 */
@SlashCommand(
    name = "compact",
    description = "Clear conversation history but keep a summary in context. Optional: /compact [instructions for summarization]"
)
public class CompactCommand implements AnnotatedCommand {

    @Override
    public String argumentHint() {
        return "<optional custom summarization instructions>";
    }

    @Override
    public boolean supportsNonInteractive() {
        return true;
    }

    /**
     * {@code execute} calls a non-streaming LLM summarization request
     * (implemented by the CLI compact summarizer adapter)
     * that can take many seconds for a real conversation — without this,
     * the Lanterna GUI thread would block for the whole call (no repaint,
     * no key handling), which is exactly what "the whole app freezes"
     * looks like from the terminal.
     */
    @Override
    public boolean isLongRunning() {
        return true;
    }


    @Override
    public boolean isAvailable(CommandContext context) {
        return !EnvUtils.isEnvTruthy(
            SubprocessEnvironment.get("DISABLE_COMPACT"));
    }

    @Override
    public CommandResult execute(CommandContext context, String args) {
// REPL keeps snipped messages for UI scrollback — project so the compact model doesn't
// summarize content that was intentionally removed.
        List<Message> allMessages = context.session().messagesSupplier().get();
        List<Message> messages = MessageConstants.getMessagesAfterCompactBoundary(allMessages);

        if (messages.isEmpty()) {
            return CommandResult.localError("No messages to compact");
        }

        String customInstructions = args != null ? args.trim() : "";

        // Check if compactService is wired
        if (context.session().compactService() == null || context.session().compactService().get() == null) {
            return CommandResult.local(
                """
                Compaction is not available in this context.
                Token usage will be managed automatically when context approaches limits.""");
        }

        return triggerCompact(context, messages, customInstructions);
    }

    /**
     * Triggers real compaction by calling {@link MessageCompactor#compactConversation}.
     */
    private CommandResult triggerCompact(CommandContext context,
                                          List<Message> messages,
                                          String customInstructions) {
        Consumer<CompactProgressEvent> notify = context.session().onCompactProgress();
        HookDispatcher hooks = context.session().hookDispatcher();
        MessageCompactor compactor = context.session().compactService().get();
        String userInstructions = StringUtils.isBlank(customInstructions) ? null : customInstructions;

        // Manual commands start outside submitMessage(), so clear the completed turn's sticky
        // cancellation before hooks or the cache-sharing compact request can observe it.
        compactor.prepareManualCompact();

// Step 0: microcompact — truncate long tool outputs, BEFORE anything else.
        MessageCompactor.MicrocompactResult microResult = compactor.microcompactMessages(messages);
        List<Message> microMessages = microResult.messages();
        long fullPreCompactTokenCount = compactor.contextTokenCount(microMessages, context.session().model());
        CompactInput compactInput = splitTerminalPreservedSegment(microMessages);
        List<Message> messagesToSummarize = compactInput.messagesToSummarize();
        if (messagesToSummarize.isEmpty()) {
            return CommandResult.localError("No messages to compact");
        }

        // Step 1: hooks_start(pre_compact) → run PreCompact hooks against the
        // post-microcompact message set. The hook's additionalContext doubles

// <instructions> (user text first.
        if (notify != null) notify.accept(new CompactProgressEvent.HooksStart("pre_compact"));
        HookDispatcher.HookOutcome preOutcome = hooks != null
            ? hooks.dispatchPreCompactWithOutcome(
                "manual", userInstructions, fullPreCompactTokenCount)
            : HookDispatcher.HookOutcome.PROCEED;
        String mergedInstructions = MessageCompactor.mergeHookInstructions(
            userInstructions, preOutcome.additionalContext());

        // Step 2: compact_start — LLM summarisation is about to begin
        if (notify != null) notify.accept(new CompactProgressEvent.CompactStart());

        try {
// Step 3: full compaction — LLM-based summarization, steered by the merged user+hook
// instructions.
            MessageCompactor.CompactionResult result = withPreservedTail(
                compactor.compactConversation(
                    messagesToSummarize, false, mergedInstructions),
                compactInput.preservedTail(), fullPreCompactTokenCount, compactor);


            // processSessionStartHooks('compact') inside compactConversation
            // and appends the hook messages LAST in the post-compact list
            // (CompactionResult.hookResults, buildPostCompactMessages order).
            // Java's hooks live at the command layer, so fire here and append
            // any additionalContext as a system-reminder isMeta user message
            // in the same final position.
            if (notify != null) notify.accept(new CompactProgressEvent.HooksStart("session_start"));
            HookDispatcher.HookOutcome sessionStartOutcome = hooks != null
                ? hooks.dispatchSessionStartWithOutcome("compact")
                : HookDispatcher.HookOutcome.PROCEED;


            if (notify != null) notify.accept(new CompactProgressEvent.HooksStart("post_compact"));
            HookDispatcher.HookOutcome postOutcome = hooks != null
                ? hooks.dispatchPostCompactWithOutcome("manual", result.summaryText(),
                    postTokenCount(result, compactor, context.session().model()))
                : HookDispatcher.HookOutcome.PROCEED;

            String preDisplay = preOutcome.hasUserDisplayMessage()
                ? preOutcome.userDisplayMessage() : preOutcome.additionalContext();
            String postDisplay = postOutcome.hasUserDisplayMessage()
                ? postOutcome.userDisplayMessage() : postOutcome.additionalContext();
            String userDisplayMessage = joinNonBlank(preDisplay, postDisplay);
            boolean verbose = context.session().verboseSupplier() != null
                && Boolean.TRUE.equals(context.session().verboseSupplier().get());
            String displayText = "Compacted "
                + (verbose ? "" : "(ctrl+o to see full summary)")
                + (userDisplayMessage != null
                    ? (verbose ? "" : "\n") + userDisplayMessage : "");


            // messagesToKeep, so they precede attachments/hook results on the
            // next wire request and merge with the user's next prompt.
            List<Message> postCompact = assembleWithSlashCommandMessages(
                result, sessionStartOutcome, customInstructions, displayText);

            Consumer<List<Message>> loader = context.session().loadCompactedMessages() != null
                ? context.session().loadCompactedMessages()
                : context.session().loadMessages();
            if (loader != null) {
                loader.accept(postCompact);
            }


            if (context.session().postCompactCallback() != null) {
                try { context.session().postCompactCallback().run(); }
                catch (Exception _) { /* best-effort */ }
            }

            Consumer<Message> recorder = context.session().transcriptRecorder();
            if (recorder != null) {
                for (Message m : postCompact) recorder.accept(m);
            }
            if (context.session().postCompactTranscriptCallback() != null) {
                try { context.session().postCompactTranscriptCallback().run(); }
                catch (Exception _) { /* best-effort */ }
            }


            // successful manual compact because token counts are stale until
            // the next API response. microcompactMessages clears it at the
            // start of the next attempt.
            compactor.suppressCompactWarning();

            return CommandResult.displayOnly(displayText);
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.toString();
            if (Thread.currentThread().isInterrupted()
                    || e instanceof CancellationException) {
                return CommandResult.localError("Compaction canceled.");
            }
            if (Strings.CS.equals("Not enough messages to compact.", message)
                    || Strings.CS.equals("Compaction interrupted · This may be due to network issues — please try again.", message)) {
                return CommandResult.localError(message);
            }
            String friendly = e instanceof FriendlyApiError fae ? fae.friendlyMessage() : null;
            return CommandResult.localError(
                "Error during compaction: " + (friendly != null ? friendly : message));
        } finally {

            if (notify != null) notify.accept(new CompactProgressEvent.CompactEnd());
        }
    }

    private static CompactInput splitTerminalPreservedSegment(List<Message> messages) {
        int terminalAssistant = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message instanceof UserMessage) break;
            if (message instanceof AssistantMessage) {
                terminalAssistant = i;
                break;
            }
        }
        if (terminalAssistant < 0) {
            return new CompactInput(messages, List.of());
        }
        return new CompactInput(
            new ArrayList<>(messages.subList(0, terminalAssistant)),
            new ArrayList<>(messages.subList(terminalAssistant, messages.size())));
    }

    private static MessageCompactor.CompactionResult withPreservedTail(
            MessageCompactor.CompactionResult result, List<Message> preservedTail,
            long fullPreCompactTokenCount, MessageCompactor compactor) {
        List<Message> kept = new ArrayList<>(result.messagesToKeep());
        for (Message message : preservedTail) {
            if (!kept.contains(message)) kept.add(message);
        }
        SystemMessage annotatedBoundary = result.boundaryMarker();
        if (!kept.isEmpty()) {
            String anchorUuid = result.summaryMessages().isEmpty()
                ? result.boundaryMarker().uuid()
                : result.summaryMessages().getLast().uuid();
            annotatedBoundary = MessageCompactor.annotateBoundaryWithPreservedSegment(
                annotatedBoundary, anchorUuid, kept);
        }
        List<Message> postPayload = new ArrayList<>(result.summaryMessages());
        postPayload.addAll(kept);
        postPayload.addAll(result.attachments());
        long postCompactTokenCount = compactor.estimatePostCompactTokenCount(postPayload);
        if (annotatedBoundary.compactMetadata() != null) {
            annotatedBoundary = new SystemMessage(
                annotatedBoundary.uuid(), annotatedBoundary.subtype(), annotatedBoundary.level(),
                annotatedBoundary.content(), annotatedBoundary.parentUuid().orElse(null),
                annotatedBoundary.timestamp().orElse(null),
                annotatedBoundary.compactMetadata().withTokenCounts(
                    fullPreCompactTokenCount, postCompactTokenCount));
        }
        return new MessageCompactor.CompactionResult(
            annotatedBoundary, result.summaryMessages(), result.attachments(),
            result.hookResults(), kept, fullPreCompactTokenCount,
            result.compactionUsage(), result.rawSummary());
    }

    private record CompactInput(List<Message> messagesToSummarize, List<Message> preservedTail) {}

    private static List<Message> assembleWithSlashCommandMessages(
            MessageCompactor.CompactionResult result,
            HookDispatcher.HookOutcome sessionStartOutcome,
            String args,
            String displayText) {
        List<Message> out = new ArrayList<>();
        out.add(result.boundaryMarker());
        out.addAll(result.summaryMessages());
        out.addAll(result.messagesToKeep());
        String resumedRecoveryParent = resumedRecoveryParent(result.messagesToKeep());
        out.add(localCommandMessage(
            "<local-command-caveat>Caveat: The messages below were generated by the user while "
                + "running local commands. DO NOT respond to these messages or otherwise consider "
                + "them in your response unless the user explicitly asks you to.</local-command-caveat>",
            true, Instant.now(), resumedRecoveryParent));
        out.add(localCommandMessage(
            CommandMetadataEncoder.encodeCommandInputTags("compact", args == null ? "" : args),
            false, Instant.now()));
        out.add(localCommandMessage(
            "<local-command-stdout>" + displayText + "</local-command-stdout>",
            false, Instant.now().plusMillis(100)));
        out.addAll(result.attachments());
        out.addAll(result.hookResults());
        if (sessionStartOutcome.hasAdditionalContext()) {
            out.add(new UserMessage(
                UUID.randomUUID().toString(),
                MessageContent.ofText(MessageConstants.wrapInSystemReminder(
                    sessionStartOutcome.additionalContext())),
                true, false, null, MessageOrigin.USER,
                null, Instant.now(), null, null));
        }
        return out;
    }

    private static UserMessage localCommandMessage(String text, boolean isMeta, Instant timestamp) {
        return localCommandMessage(text, isMeta, timestamp, null);
    }

    private static UserMessage localCommandMessage(
            String text, boolean isMeta, Instant timestamp, String parentHint) {
        return new UserMessage(
            UUID.randomUUID().toString(), MessageContent.ofText(text),
            isMeta, false, null, MessageOrigin.USER,
            null, timestamp, null, null, null, parentHint);
    }

    private static String resumedRecoveryParent(List<Message> messagesToKeep) {
        if (messagesToKeep == null) return null;
        for (int i = messagesToKeep.size() - 1; i >= 0; i--) {
            if (!(messagesToKeep.get(i) instanceof AssistantMessage assistant)
                    || assistant.message() == null
                    || assistant.message().content() == null) {
                continue;
            }
            boolean recoverySentinel = assistant.message().content().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .anyMatch(block -> Strings.CS.equals(
                    MessageConstants.NO_RESPONSE_REQUESTED, block.text()));
            if (recoverySentinel) return assistant.uuid();
        }
        return null;
    }

    /**
     * Real API usage of the summarization call when available (four-field sum.
     */
    private static long postTokenCount(MessageCompactor.CompactionResult result,
                                       MessageCompactor compactor, String model) {
        Usage usage = result.compactionUsage();
        if (usage != null && (usage.inputTokens() > 0 || usage.outputTokens() > 0)) {
            return TokenEstimator.contextTokens(usage, model);
        }
        return compactor.estimateTokenCount(result.buildPostCompactMessages());
    }

/**
     * Joins two possibly-null/blank strings with {@code \n}.
     */
    private static String joinNonBlank(String a, String b) {
        boolean hasA = StringUtils.isNotBlank(a);
        boolean hasB = StringUtils.isNotBlank(b);
        if (hasA && hasB) return a + "\n" + b;
        if (hasA) return a;
        if (hasB) return b;
        return null;
    }

}
