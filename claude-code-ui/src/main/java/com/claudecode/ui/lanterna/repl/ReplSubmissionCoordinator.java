package com.claudecode.ui.lanterna.repl;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.claudecode.commands.Command;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandRegistry;
import com.claudecode.commands.prompt.PromptInvocation;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.PastedContent;
import com.claudecode.core.paste.PastedRefParser;
import com.claudecode.core.queue.QueuePriority;
import com.claudecode.core.queue.QueuedCommand;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.runtime.turn.TurnEngine;
import com.claudecode.runtime.turn.UserInput;
import com.claudecode.tools.skills.InvokedSkillRegistry;
import com.claudecode.tools.tasks.teammate.AgentTeamsEnabled;
import com.claudecode.ui.lanterna.bashmode.BashModeExecutor;
import com.claudecode.ui.lanterna.dialog.PermissionDialog;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.input.PromptHistory;
import com.claudecode.ui.lanterna.slash.PromptInvocationAdapter;
import com.claudecode.ui.lanterna.slash.SlashCommandDispatcher;

/**
 * Owns the interactive text-to-turn pipeline: routes submitted prompt text through history,
 * bash, slash, and the busy-turn queue, then builds the {@link UserInput} for every way a turn
 * can start (typed, remote, slash-expanded, prompt command, queued drain, plan continuation).
 *
 * <p>TS coverage (paths relative to the claude-code repo root):
 * <ul>
 *   <li>{@code utils/handlePromptSubmit.ts} — the busy-turn branch of
 *       {@code onSubmit}: reading {@code hasInterruptibleToolInProgress} and
 *       aborting the active turn before enqueuing (the queue-steer path in
 *       {@code handleQuery}), plus the plain queue-only fallback.</li>
 *   <li>{@code screens/REPL.tsx} — {@code onQuery}/{@code executeQuery}: turning display
 *       text + expanded content + pasted chips into the user turn, tagging the argv startup
 *       prompt, and the queued-command drain that re-routes {@code /}-prefixed queue entries
 *       through slash dispatch.</li>
 *   <li>{@code utils/messageQueueManager.ts} — {@code enqueue} bookkeeping surfaced as the
 *       transcript queue-operation records around {@link #renderAndQueue}.</li>
 *   <li>{@code components/permissions/PermissionRequest.tsx} — the "clear context and
 *       implement plan" approval continuation ({@link #acceptPlanWithClearedContext}).</li>
 * </ul>
 */
final class ReplSubmissionCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ReplSubmissionCoordinator.class);

    /** Collaborators that turn a routed submission into an engine turn. */
    record TurnSubmission(
        QuerySession queryEngine,
        TurnEngine turns,
        LanternaSessionSink turnView,
        SessionTopicTitleCoordinator titles,
        InvokedSkillRegistry invokedSkills,
        Consumer<String> systemMessage,
        Consumer<Runnable> guiInvoker) {
        TurnSubmission {
            Objects.requireNonNull(queryEngine, "queryEngine");
            Objects.requireNonNull(turns, "turns");
            Objects.requireNonNull(turnView, "turnView");
            Objects.requireNonNull(invokedSkills, "invokedSkills");
            Objects.requireNonNull(systemMessage, "systemMessage");
            Objects.requireNonNull(guiInvoker, "guiInvoker");
        }
    }

    /** Collaborators for the plan-mode "clear context and implement" continuation. */
    record PlanContinuation(
        SessionController sessions,
        PermissionGate permissionGate,
        InteractiveSessionPort interactiveSessions) {}

    private final InputPanel input;
    private final PromptHistory history;
    private final CommandRegistry commands;
    private final CommandContext commandContext;
    private final ImmediateCommandUiAdapter immediate;
    private final BashModeExecutor bash;
    private final SlashCommandDispatcher slash;
    private final TurnEngine turns;
    private final String projectRoot;
    private final TurnSubmission submission;
    private final PlanContinuation plan;
    private volatile boolean longRunning;
    /** True only while the one-shot argv prompt is synchronously routed through handleInput. */
    private boolean routingInteractiveStartupPrompt;
    private volatile boolean lastSubmittedInputWasInteractiveStartupPrompt;

    ReplSubmissionCoordinator(InputPanel input, PromptHistory history, CommandRegistry commands,
                              CommandContext commandContext, ImmediateCommandUiAdapter immediate,
                              BashModeExecutor bash, SlashCommandDispatcher slash,
                              String projectRoot, TurnSubmission submission,
                              PlanContinuation plan) {
        this.input = input;
        this.history = history;
        this.commands = commands;
        this.commandContext = commandContext;
        this.immediate = immediate;
        this.bash = bash;
        this.slash = slash;
        this.turns = submission.turns();
        this.projectRoot = projectRoot;
        this.submission = submission;
        this.plan = plan;
    }

    // ── Routing ─────────────────────────────────────────────────────────────

    void handleInput(String value) {
        if (StringUtils.isBlank(value)) return;
        Map<Integer, PastedContent> raw = input.getPastedContents();
        Map<Integer, PastedContent> pasted = dropUnreferencedImages(value, raw);
        boolean hasImages = pasted.values().stream().anyMatch(PastedRefParser::isValidImagePaste);

        // History keeps the UNEXPANDED display text plus the full chip map, so ↑ replays
        // the compact "[Pasted text #1 +40 lines]" line the user actually saw.
        history.addEntry(value, sessionId(), System.getProperty("user.dir"), projectRoot, raw);
        input.resetHistory();


        // dispatching, "so queued commands and immediate commands both receive the
        // expanded text from when it was submitted". Without this the model only ever
        // saw the chip label and the pasted body was silently dropped.
        String finalInput = PastedRefParser.expandPastedTextRefs(value, pasted);

        // inlined the map is only still needed for image blocks.
        Map<Integer, PastedContent> forTurn = hasImages ? pasted : Map.of();

        if (Strings.CS.startsWith(finalInput, "!")) {
            bash.handle(finalInput.substring(1));
            return;
        }
        if (Strings.CS.startsWith(finalInput, "/")) {
            String[] parts = finalInput.substring(1).split("\\s+", 2);
            String name = parts[0].toLowerCase(Locale.ROOT);
            String args = parts.length > 1 ? parts[1].trim() : "";
            Command command = commands.find(name).orElse(null);
            boolean busy = turns.isInFlight() || longRunning;
            if (command != null && immediate.tryDispatchImmediate(command, args, false, busy, commandContext)) return;
            if (longRunning) {
                enqueue(finalInput, forTurn, value);
                return;
            }
            slash.dispatch(finalInput);
            return;
        }
        handleQuery(finalInput, forTurn, value, false);
    }

    /** Routes the one-shot argv prompt; turns started while it runs carry startup provenance. */
    void handleStartupInput(String value) {
        routingInteractiveStartupPrompt = true;
        try {
            handleInput(value);
        } finally {
            routingInteractiveStartupPrompt = false;
        }
    }

    /** Whether the most recently submitted turn was the interactive argv startup prompt. */
    boolean lastSubmittedInputWasInteractiveStartupPrompt() {
        return lastSubmittedInputWasInteractiveStartupPrompt;
    }

    void handleQuery(String value) {
        handleQuery(value, input.getPastedContents(), null, false);
    }

    /** Direct semantic endpoint submit that does not mutate the terminal input widget. */
    void handleRemoteQuery(String value, Map<Integer, PastedContent> pasted) {
        if (StringUtils.isBlank(value)) return;
        handleQuery(value, pasted == null ? Map.of() : Map.copyOf(pasted), null, true);
    }

    void longRunningStarted() { longRunning = true; }
    void longRunningFinished() { longRunning = false; turns.drainIfIdle(); }
    boolean longRunningInFlight() { return longRunning; }

    private void handleQuery(String value, Map<Integer, PastedContent> pasted,
                             String preExpansionValue, boolean remote) {
        if (turns.isInFlight() || longRunning) {
            // Queue steer: when the active turn's executing tools are all
            // steerable, the submission aborts the turn BEFORE enqueuing so the
            // drained queue can start a fresh turn immediately — the twin of
            // handlePromptSubmit's hasInterruptibleToolInProgress branch. A
            // long-running slash command owns no engine turn and never steers.
            if (turns.isInFlight() && turns.hasInterruptibleToolInProgress()) {
                turns.interruptForQueuedSubmit();
            }
            enqueue(value, pasted, preExpansionValue, remote ? "session-host" : null);
            return;
        }
        if (remote) executeRemoteQuery(value, pasted);
        else executeQuery(value, value, pasted);
    }

    /**
     * Queue an already-expanded submission.
     */
    private void enqueue(String value, Map<Integer, PastedContent> pasted,
                         String preExpansionValue) {
        enqueue(value, pasted, preExpansionValue, null);
    }

    private void enqueue(String value, Map<Integer, PastedContent> pasted,
                         String preExpansionValue, String originKind) {
        String preview = preExpansionValue != null ? preExpansionValue : value;
        renderAndQueue(new QueuedCommand(value, pasted, "prompt", QueuePriority.NEXT,
            false, originKind, false, false, preExpansionValue, null, null), preview);
        input.setQueuedHint(true);
    }

    private static Map<Integer, PastedContent> dropUnreferencedImages(
            String value, Map<Integer, PastedContent> raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        Set<Integer> referenced = new HashSet<>();
        for (PastedRefParser.Ref ref : PastedRefParser.parseReferences(value)) {
            referenced.add(ref.id());
        }
        Map<Integer, PastedContent> kept = new LinkedHashMap<>();
        for (Map.Entry<Integer, PastedContent> e : raw.entrySet()) {
            if (e.getValue().isImage() && !referenced.contains(e.getKey())) continue;
            kept.put(e.getKey(), e.getValue());
        }
        return kept;
    }

    // ── Queue ───────────────────────────────────────────────────────────────

    /**
     * Add a command to the live runtime queue. The prompt's reactive queue
     * projection renders from {@link TurnEngine#setInputQueueListener}; nothing
     * is appended to transcript history here.
     *
     * @param cmd         the command to queue for later execution
     * @param displayText text to show in the dim preview line (may differ from
     *                    cmd.text() for skill invocations)
     */
    void renderAndQueue(QueuedCommand cmd, String displayText) {
        QueuedCommand queued = cmd;
        if (cmd.preExpansionValue() == null && displayText != null
                && !displayText.equals(cmd.text())) {
            queued = new QueuedCommand(
                cmd.text(), cmd.pastedContents(), cmd.mode(), cmd.priority(), cmd.isMeta(),
                cmd.originKind(), cmd.skipSlashCommands(), cmd.bridgeOrigin(), displayText,
                cmd.workload(), cmd.agentId(), cmd.orphanedPermission(), cmd.taskId(),
                cmd.modelScheduledOrigin());
        }
        var transcript = queryEngine().execution().getTranscriptSink();
        if (transcript != null) {
            transcript.recordQueueOperation(sessionId(), "enqueue", queued.text());
        }
        turns.enqueue(queued);
    }

    /**
     * Drain a batch of {@link QueuedCommand}s from the in-flight queue.
     */
    void executeQueuedCommands(List<QueuedCommand> batch) {
        if (batch.isEmpty()) return;
        var transcript = queryEngine().execution().getTranscriptSink();
        if (transcript != null) {
            transcript.recordQueueOperation(sessionId(), "dequeue", null);
        }
        QueuedCommand cmd = batch.getFirst();
        String text = QueuedCommandMapper.envelope(cmd);
        // skipSlashCommands: treat as plain text even if starts with '/'.
        // Covers inputs that must bypass local slash-command routing.
        if (!cmd.skipSlashCommands() && text != null && Strings.CS.startsWith(text, "/")) {
            // Re-route through slash dispatch. Slash entries are drained one at a time
            // (alone), so there is nothing left in `batch` to lose here.
            slash.dispatch(text);
            return;
        }
        // matches handleInput/handleRemoteQuery's blank-input guard for human-typed
        // submissions: a queued command must never reach turnEngine.submit() with
        // neither text nor a pasted image. Without this, a malformed task-notification
        // or an orphaned-permission command that (contrary to the assumption below)
        // reached this UI-edge drain with its payload already consumed elsewhere would
        // submit an empty user turn — which serializes to a wire message with an empty
        // text content block. Real incident: that empty block survived into a
        // tool_result-heavy turn and downstream strict backends rejected it with
        // "message content cannot be empty".
        if (QueuedCommandMapper.isBlankQueuedCommand(cmd, text)) {
            log.warn("executeQueuedCommands: dropping queued command with blank text and no "
                + "pasted image (mode={}, originKind={})", cmd.mode(), cmd.originKind());
            return;
        }
        // mode == "orphaned-permission" / "task-notification": route as plain query.
        // isMeta: passed through — the message will be sent to the model but
        // the UI does not currently filter meta messages differently.
        // bridgeOrigin is retained only as legacy queue provenance; no bridge
        // command filter exists after the bridge subsystem removal.
        //
        // NOTE: a *payload-bearing* orphaned-permission command (from the SDK control
        // broker, cli module) is consumed by the engine's in-loop drain
        // (QueryHelpers.drainQueuedCommands → OrphanedPermissionExecutor), never here.
        // The UI edge drain only ever sees a payload-less orphaned-permission, which the
        // UI mode never enqueues in the first place — so routing it as a plain query is a
        // harmless fallback, not a behavior change.
        UserInput input = QueuedCommandMapper.applyQueuedCommandProvenance(
            UserInput.builder(text, text)
            .pasted(cmd.pastedContents())
            .permissionMode(this.input.getPermissionMode())
            .build(), cmd);
        input = withStartupPromptProvenance(input);
        if (batch.size() > 1) {
            input = input.withAdditionalUserMessages(batch.stream().skip(1)
                .map(QueuedCommandMapper::envelope)
                .filter(StringUtils::isNotEmpty)
                .map(MessageContent::ofText)
                .toList());
        }
        if (!Strings.CS.equals("task-notification", cmd.mode())) {
            noteUserQuery(text, false);
        }
        submit(input);
    }

    // ── Turn construction ───────────────────────────────────────────────────

    private void executeRemoteQuery(String userInput, Map<Integer, PastedContent> pasted) {
        UserInput input = withStartupPromptProvenance(UserInput.of(
            userInput, userInput, pasted, this.input.getPermissionMode(), false))
            .withInputOrigin("remote");
        noteUserQuery(userInput, false);
        submit(input);
    }

    /**
     * Execute a query with separate display text and actual query content.
     */
    void executeQuery(String displayText, String queryContent,
                      Map<Integer, PastedContent> pasted) {
        executeQuery(displayText, queryContent, pasted, false);
    }

    void executeQuery(String displayText, String queryContent,
                      Map<Integer, PastedContent> pasted, boolean isSlash) {
        UserInput input = withStartupPromptProvenance(UserInput.of(
            displayText, queryContent, pasted, this.input.getPermissionMode(), isSlash));
        noteUserQuery(queryContent, isSlash);
        submit(input);
    }

    /**
     * Structured prompt-command path. Unlike the legacy string overload this
     * retains MCP image/document blocks and installs command-scoped hooks,
     * permissions and model overrides before the turn starts.
     */
    void executePrompt(String displayText, PromptInvocation invocation,
                       Map<Integer, PastedContent> pasted) {
        HookDispatcher.HookOutcome expansionOutcome =
            PromptInvocationAdapter.installTurnScopedState(
            invocation,
            displayText,
            PromptInvocationAdapter.commandNameFromDisplay(displayText),
            queryEngine().execution().getHookDispatcher(),
            (commandName, logicalPath, content) -> submission.invokedSkills()
                .record(null, commandName, logicalPath, content));
        if (!expansionOutcome.proceed() || expansionOutcome.preventContinuation()) {
            String reason = expansionOutcome.hasBlockingErrors()
                ? expansionOutcome.blockingErrors().getFirst()
                : expansionOutcome.stopReason();
            submission.systemMessage().accept(StringUtils.isNotBlank(reason)
                ? "Prompt expansion blocked by hook: " + reason
                : "Prompt expansion blocked by hook");
            queryEngine().execution().getHookDispatcher().clearInvocationHooks();
            return;
        }
        UserInput input = PromptInvocationAdapter.applyExpansionOutcome(
            PromptInvocationAdapter.toUserInput(
                displayText, invocation, pasted, this.input.getPermissionMode()),
            expansionOutcome);
        input = withStartupPromptProvenance(input);
        noteUserQuery(invocation.textContent(), true);
        submit(input);
    }

    /**
     * Plan-mode approval with "clear context": resets the conversation, applies the
     * approved permission updates, then submits the implementation prompt as an
     * auto-continuation turn that still carries the plan content.
     */
    void acceptPlanWithClearedContext(PermissionDialog.PlanClearApproval approval) {
        if (approval == null || StringUtils.isBlank(approval.plan())) return;
        String previousSessionId = sessionId();
        Path previousTranscript = StringUtils.isNotBlank(previousSessionId)
            && plan.interactiveSessions() != null
            ? plan.interactiveSessions().sessionFile(System.getProperty("user.dir"), previousSessionId)
            : null;
        submission.guiInvoker().accept(() -> {
            plan.sessions().clearConversation();
            plan.permissionGate().applyUpdates(approval.permissionUpdates());
            String mode = plan.permissionGate().currentMode().kind().wireValue();
            input.setPermissionMode(mode);
            plan.permissionGate().markPlanModeExited();
            boolean hasAgentTool = AgentTeamsEnabled.isEnabled()
                && queryEngine().configuration().getConfig().tools().contains("Agent");
            String prompt = buildClearedContextPlanPrompt(
                approval.plan(), previousTranscript, hasAgentTool, approval.feedback());
            UserInput continuation = UserInput.of(
                    prompt, prompt, Map.of(), mode)
                .withQuerySource("auto-continuation")
                .withPlanContent(approval.plan());
            submit(continuation);
        });
    }

    static String buildClearedContextPlanPrompt(String plan, Path previousTranscript) {
        return buildClearedContextPlanPrompt(plan, previousTranscript, false, null);
    }

    static String buildClearedContextPlanPrompt(
            String plan, Path previousTranscript, boolean hasAgentTool, String feedback) {
        String transcriptHint = previousTranscript == null ? ""
            : "\nIf you need specific details from before exiting plan mode (like exact code snippets, "
                + "error messages, or content you generated), read the full transcript at: "
                + previousTranscript;
        String teamHint = hasAgentTool
            ? """
                
                If this plan can be broken down into multiple independent tasks, consider spawning \
                named teammates with the Agent tool (pass a `name`) to parallelize the work."""
            : "";
        String normalizedFeedback = StringUtils.trimToNull(feedback);
        String feedbackSuffix = normalizedFeedback == null ? ""
            : "\nUser feedback on this plan: " + normalizedFeedback;
        return "Implement the following plan:\n" + plan
            + transcriptHint + teamHint + feedbackSuffix;
    }

    // ── Shared tail ─────────────────────────────────────────────────────────

    private UserInput withStartupPromptProvenance(UserInput input) {
        UserInput routed = routingInteractiveStartupPrompt
            ? input.asInteractiveStartupPrompt() : input;
        lastSubmittedInputWasInteractiveStartupPrompt = routed.interactiveStartupPrompt();
        return routed;
    }

    private void noteUserQuery(String text, boolean slashInvocation) {
        SessionTopicTitleCoordinator titles = submission.titles();
        if (titles != null) titles.onUserQuery(text, slashInvocation);
    }

    /** Every turn start funnels through here: first-turn transcript metadata, then submit. */
    private void submit(UserInput input) {
        submission.turnView().prepareFirstTurnTranscriptMetadata(input);
        turns.submit(input);
    }

    private QuerySession queryEngine() { return submission.queryEngine(); }

    private String sessionId() { return queryEngine().conversation().getSessionId(); }
}
