package com.claudecode.tools.tasks;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.engine.PermissionAskCallback;
import com.claudecode.core.engine.PermissionAskContext;
import com.claudecode.core.engine.PermissionUpdateJsonCodec;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.permissions.PermissionMode;
import com.claudecode.tools.bash.BashTool;
import com.claudecode.tools.powershell.PowerShellTool;
import com.claudecode.tools.agent.SubAgentFactory;
import com.claudecode.tools.agent.SubAgentRequest;
import com.claudecode.tools.agent.SubAgentResult;
import com.claudecode.tools.tasks.teammate.Mail;
import com.claudecode.tools.tasks.teammate.MailTypes;
import com.claudecode.tools.tasks.teammate.TeammateContext;
import com.claudecode.tools.tasks.teammate.TeammateContextHolder;
import com.claudecode.tools.tasks.teammate.TeammateMailbox;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live handle for an in-process teammate (agent-teams subsystem).
 */
public class InProcessTeammateTask {

    private static final Logger log = LoggerFactory.getLogger(InProcessTeammateTask.class);
    private static final int MAX_MESSAGES = 50;
    private static final int MAX_RECENT_ACTIVITIES = 5;

    private static final int DISPLAY_TRANSCRIPT_CAP = 50;

    private static final String TEAMMATE_MESSAGE_TAG = "teammate-message";

    private static final String INTERRUPT_MESSAGE =
        """


            <system>User interrupted the current task. The task has been left incomplete. \
            You may now safely stop, or ask the user for further instructions if needed.</system>""";

    private final TaskState taskState;
    private final TaskStore taskStore;
    private final SubAgentFactory subAgentFactory;
    private final SubAgentRequest teammateRequest;
    private final TeammateContext context;
    private final TeammateMailbox mailbox;
    /** Team's shared task list (TodoStore) the teammate auto-claims from; null when standalone. */
    private final TodoStore teamTodoStore;
    private final HookDispatcher hookDispatcher;

    private final AtomicBoolean killed = new AtomicBoolean(false);
    private boolean ownedTasksReleased;
    /** Total time spent waiting on leader permission decisions. */
    private final AtomicLong totalPausedMs = new AtomicLong();
    


    private final AtomicLong progressTokens = new AtomicLong();
    /** Live unique tool-use count surfaced by the teammate spinner tree. */
    private final AtomicInteger progressToolUses = new AtomicInteger();
    private final Set<String> countedProgressMessages = ConcurrentHashMap.newKeySet();
    /** Latest human-readable activity emitted by the sub-agent progress stream. */
    private volatile String progressActivity;
    /** Released tracker keeps the five most recent tool activities for board aggregation. */
    private final List<RecentActivity> recentActivities = new ArrayList<>();
    /** Usage snapshots keyed by assistant message id so finalized usage replaces provisional usage. */
    private final Map<String, Usage> usageByMessage = new LinkedHashMap<>();

    private volatile boolean explicitlyKilled = false;
    private volatile Thread runnerThread;
    private volatile Thread mailboxThread;


    private final List<Mail> messages = new ArrayList<>();
    /**
     * Capped display transcript shown when the REPL "views" this teammate.
     */
    private final List<Message> displayTranscript = new ArrayList<>();
    /** Listener fired when {@link #displayTranscript} changes, so the UI can re-render a live view. */
    private volatile Runnable transcriptListener;
    /** Capped user messages injected into the teammate. */
    private final List<String> pendingUserMessages = new ArrayList<>();

    private final List<CompletableFuture<Void>> onIdleCallbacks = new ArrayList<>();
    /** Outstanding permission asks keyed by mailbox requestId. */
    private final Map<String, CompletableFuture<PermissionAskCallback.Result>> pendingPermissions = new ConcurrentHashMap<>();
    /** Outstanding plan-approval asks keyed by mailbox requestId. */
    private final Map<String, CompletableFuture<PlanApproval>> pendingApprovals = new ConcurrentHashMap<>();
    /** Pending next-prompt injections (USER_MESSAGE / SHUTDOWN_REQUEST) for the multi-turn loop. */
    private final BlockingQueue<String> nextPrompts = new LinkedBlockingQueue<>();
    /**
     * Per-turn abort controller — an Esc-style stop of the current turn only, keeping the teammate
     * alive for subsequent turns.
     */
    private volatile AbortController currentWorkAbortController;
    /**
     * Set when the leader requests shutdown (model decides via approve/reject).
     */
    private volatile boolean shutdownRequested = false;
    /**
     * Last idle reason sent, to dedupe consecutive identical idle notifications.
     */
    private volatile String lastIdleReason = null;

    public InProcessTeammateTask(TaskState taskState, TaskStore taskStore, SubAgentFactory subAgentFactory,
                                 SubAgentRequest teammateRequest, TeammateContext context) {
        this(taskState, taskStore, subAgentFactory, teammateRequest, context, null);
    }

    public InProcessTeammateTask(TaskState taskState, TaskStore taskStore, SubAgentFactory subAgentFactory,
                                 SubAgentRequest teammateRequest, TeammateContext context, TodoStore teamTodoStore) {
        this(taskState, taskStore, subAgentFactory, teammateRequest, context, teamTodoStore, null);
    }

    public InProcessTeammateTask(TaskState taskState, TaskStore taskStore,
                                 SubAgentFactory subAgentFactory,
                                 SubAgentRequest teammateRequest, TeammateContext context,
                                 TodoStore teamTodoStore, HookDispatcher hookDispatcher) {
        this.taskState = taskState;
        this.taskStore = taskStore;
        this.subAgentFactory = subAgentFactory;
        // Carry the teammate's shared abort handle into the sub-agent run.
        this.teammateRequest = teammateRequest.withAbortController(context.abortController());
        this.context = context;
        this.teamTodoStore = teamTodoStore;
        this.hookDispatcher = hookDispatcher;
        this.mailbox = TeammateMailbox.instance();
    }

    public String getTaskId() {
        return taskState.id();
    }

    public boolean isActive() {
        return !killed.get() && runnerThread != null && runnerThread.isAlive();
    }

    /** Test hook: exposes the runner thread so tests can join/wait on termination. */
    Thread runnerThreadForTest() {
        return runnerThread;
    }

    /** Starts the teammate: launches the mailbox reader + the multi-turn run loop. */
    public void start() {
        taskStore.updateStatus(taskState.id(), TaskStatus.RUNNING);
        setTeamMemberActive(true);

        // Register the display name (if any) so peers can address this teammate by name rather than
        // its task id.
        if (context.name() != null) mailbox.registerName(context.name(), getTaskId());

        mailboxThread = Thread.ofVirtual().name("teammate-mailbox-" + getTaskId()).unstarted(this::runMailboxReader);
        mailboxThread.start();

        runnerThread = Thread.ofVirtual().name("teammate-" + getTaskId()).unstarted(this::run);
        runnerThread.start();

        log.info("In-process teammate {} started (team={})", getTaskId(), context.teamId() != null ? context.teamId() : "?");
    }


    private void run() {
        TeammateContextHolder.runWithContext(context, () -> {
            try {
                // The initial prompt is already wrapped as a <teammate-message> by the spawn

                // ('team-lead', ...)). Keep it as-is; claimed team tasks and user messages stay
                // plain text (see run loop).
                String prompt = teammateRequest.prompt();
                List<Message> conversation = new ArrayList<>();
                // The teammate's own conversation (returned by the sub-agent after
                // each turn) already begins with the initial prompt as its first user

                // separate prompt entry here: doing so would duplicate the prompt in
                // the viewed transcript (the engine's prompt message carries a
                // different uuid than any local seed, so the uuid-dedup in
                // appendToDisplayTranscript cannot collapse them).
                // Released 2.1.197 claims the first available parent task before
                // running the teammate's spawn prompt. The claimed-task prompt is
                // intentionally ignored here; it only reserves the work and marks
                // it in progress before the initial instructions are processed.
                if (teamTodoStore != null) {
                    tryClaimNextTask(teamTodoStore, context.name());
                }
                while (!killed.get() && !context.abortController().isAborted()) {
                    setTeamMemberActive(true);
                    resetTurnProgressTracker();
                    // Fresh per-turn controller so an abort stops only this turn.
                    currentWorkAbortController = new AbortController();
                    // Reset the idle-notification dedup at the start of each turn so every
                    // completed turn emits its own idle signal.
                    lastIdleReason = null;
                    SubAgentRequest turnRequest = teammateRequest
                        .withPrompt(prompt)
                        .withAbortController(currentWorkAbortController)
                        .withPermissionMode(context.permissionMode())
                        .withPriorMessages(conversation.isEmpty() ? null : new ArrayList<>(conversation))
                        .withProgressCallback(new SubAgentRequest.ProgressCallback() {
                            @Override
                            public void onProgress(String status, double ignoredProgressPercent) {
                                progressActivity = status;
                                if (taskStore != null) {
                                    taskStore.updateProgressSummary(getTaskId(), status);
                                }
                            }

                            @Override
                            public void onAgentUsage(String messageId, Usage usage) {
                                recordUsage(messageId, usage);
                            }

                            @Override
                            public void onAgentMessage(Message message, String ignoredAgentId) {
                                if (message == null) return;
                                appendToDisplayTranscript(List.of(message));
                                if (message instanceof AssistantMessage assistant
                                        && countedProgressMessages.add(assistant.uuid())
                                        && assistant.message() != null
                                        && assistant.message().content() != null) {
                                    List<ToolUseBlock> toolUses = assistant.message().content().stream()
                                        .filter(ToolUseBlock.class::isInstance)
                                        .map(ToolUseBlock.class::cast)
                                        .toList();
                                    progressToolUses.addAndGet(toolUses.size());
                                    recordActivities(toolUses);
                                }
                            }
                        });
                    SubAgentResult result;
                    try {
                        result = subAgentFactory.runSubAgent(turnRequest);
                    } catch (Exception e) {
                        // An exception here is usually a cooperative-shutdown side effect:
                        // stop()/kill() abort the controllers and interrupt the runner thread,
                        // so the in-flight runSubAgent unwinds. In that case the terminal status
                        // must settle on KILLED (written by stop()/onLoopExit), never FAILED —
                        // otherwise a stop() racing the fail() can leave the task FAILED. Only a
                        // genuine error during a live run is reported as FAILED.
                        if (killed.get() || explicitlyKilled || context.abortController().isAborted()) {
                            return;
                        }
                        fail(e.getMessage());
                        return;
                    }
                    if (result.isError()) {
                        fail(result.error().orElse("unknown error"));
                        return;
                    }
                    // Real sub-agents report finalized assistant usage through the
                    // callback above. Keep that per-turn tracker authoritative:
                    // result.progressTokens may have been recomputed from the full
                    // carried conversation and would double-count prior output.
                    // Factories without usage callbacks retain the legacy fallback.
                    synchronized (usageByMessage) {
                        if (usageByMessage.isEmpty()) {
                            progressTokens.set(Math.max(0L, result.progressTokens()));
                        }
                    }
                    // Thread the full conversation back in for the next turn.
                    result.conversation().ifPresent(c -> {
                        conversation.clear();
                        conversation.addAll(c);
                    });
                    // match the teammate's full conversation into the display transcript so the
                    // REPL's "view teammate" mode shows the real prompt↔response exchange.
                    result.conversation().ifPresent(this::appendToDisplayTranscript);
                    // Record this turn's output so the leader can always see the teammate's latest
                    // result.
                    if (taskStore != null && !StringUtils.isBlank(result.output())) {
                        taskStore.updateFinalMessage(getTaskId(), result.output());
                    }
                    // Detect a per-turn (Esc) abort of the CURRENT work only, distinct from a
                    // lifecycle abort that kills the whole teammate.
                    boolean workAborted = currentWorkAbortController.isAborted()
                        && !killed.get() && !context.abortController().isAborted();
                    if (workAborted) {
                        // Append the interrupt message to the teammate's scrollback.
                        appendMessage(new Mail(MailTypes.INTERRUPT, "", getTaskId(), getTaskId(), INTERRUPT_MESSAGE));
                        appendToDisplayTranscript(List.of(new SystemMessage(
                            UUID.randomUUID().toString(), "interrupt", "info", INTERRUPT_MESSAGE)));
                        log.info("Teammate {} work interrupted, returning to idle", getTaskId());
                    }
                    if (killed.get() || context.abortController().isAborted()) {
                        break;
                    }
                    // Tell the leader we're idle/available (or interrupted), then try to claim the
                    // next team task before parking on the mailbox.
                    String reason = workAborted ? "interrupted" : "available";
                    HookDispatcher.HookOutcome idleBoundary = runIdleBoundaryHooks();
                    if (idleBoundary.preventContinuation()) {
                        return;
                    }
                    if (!idleBoundary.proceed() || idleBoundary.hasBlockingErrors()) {
                        prompt = idleBoundary.blockingErrors().isEmpty()
                            ? "TeammateIdle hook feedback:\nHook blocked teammate idle"
                            : String.join("\n", idleBoundary.blockingErrors());
                        continue;
                    }
                    sendIdleNotification(reason);

                    // turns; TeamDelete uses that persisted flag to distinguish an
                    // idle/dead member from one still executing.
                    setTeamMemberActive(false);
                    String queuedPrompt = nextPrompts.poll();
                    if (queuedPrompt != null) {
                        prompt = queuedPrompt;
                        continue;
                    }
                    String claimed = teamTodoStore != null ? tryClaimNextTask(teamTodoStore, context.name()) : null;
                    if (claimed != null) {
                        // Self-directed: a team task was claimed → run it as the next turn.
                        prompt = claimed;
                        continue;
                    }
                    try {
                        prompt = nextPrompts.take();
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } finally {
                // Ensure the mailbox reader thread is interrupted/cleaned up even on
                // error or abrupt exit. Does NOT mark killed — onLoopExit decides
                // COMPLETED vs KILLED based on whether stop() was the trigger.
                onLoopExit();
            }
        });
    }

    private HookDispatcher.HookOutcome runIdleBoundaryHooks() {
        if (hookDispatcher == null) return HookDispatcher.HookOutcome.PROCEED;
        List<HookDispatcher.HookOutcome> outcomes = new ArrayList<>();
        if (teamTodoStore != null) {
            teamTodoStore.reload();
            for (Task task : teamTodoStore.list()) {
                if (task.status() != TodoStatus.IN_PROGRESS
                        || task.owner().filter(this::ownsTask).isEmpty()) continue;
                HookDispatcher.HookOutcome completed =
                    hookDispatcher.dispatchTaskCompletedWithOutcome(
                        task.id(), task.subject(), task.description());
                outcomes.add(feedbackOutcome(
                    completed, "TaskCompleted hook feedback:\n"));
            }
        }
        outcomes.add(feedbackOutcome(hookDispatcher.dispatchTeammateIdleWithOutcome(
            StringUtils.defaultString(context.name()),
            StringUtils.defaultString(context.teamId())),
            "TeammateIdle hook feedback:\n"));
        return aggregateIdleBoundaryOutcomes(outcomes);
    }

    private boolean ownsTask(String owner) {
        return owner.equals(StringUtils.defaultString(context.name()));
    }

    private static HookDispatcher.HookOutcome feedbackOutcome(
            HookDispatcher.HookOutcome outcome, String prefix) {
        if (outcome == null) return HookDispatcher.HookOutcome.PROCEED;
        if (outcome.proceed() && !outcome.hasBlockingErrors()) return outcome;
        List<String> feedback = outcome.blockingErrors().stream()
            .map(error -> Strings.CS.startsWith(error, prefix) ? error : prefix + error)
            .toList();
        if (feedback.isEmpty() && StringUtils.isNotBlank(outcome.stopReason())) {
            feedback = List.of(prefix + outcome.stopReason());
        }
        return new HookDispatcher.HookOutcome(false, outcome.additionalContext(), feedback,
            outcome.preventContinuation(), outcome.stopReason(), outcome.userDisplayMessage(),
            outcome.additionalContexts(), outcome.specificOutputs());
    }

    private static HookDispatcher.HookOutcome aggregateIdleBoundaryOutcomes(
            List<HookDispatcher.HookOutcome> outcomes) {
        boolean allProceed = true;
        boolean preventContinuation = false;
        String stopReason = null;
        List<String> blockingErrors = new ArrayList<>();
        List<String> additionalContexts = new ArrayList<>();
        List<HookDispatcher.HookSpecificOutput> specificOutputs = new ArrayList<>();
        List<String> userDisplayMessages = new ArrayList<>();
        for (HookDispatcher.HookOutcome outcome : outcomes) {
            if (outcome == null) continue;
            allProceed &= outcome.proceed();
            blockingErrors.addAll(outcome.blockingErrors());
            additionalContexts.addAll(outcome.additionalContexts());
            specificOutputs.addAll(outcome.specificOutputs());
            if (StringUtils.isNotBlank(outcome.userDisplayMessage())) {
                userDisplayMessages.add(outcome.userDisplayMessage());
            }
            if (outcome.preventContinuation()) {
                preventContinuation = true;
                if (StringUtils.isNotBlank(outcome.stopReason())) {
                    stopReason = outcome.stopReason();
                }
            }
        }
        if (preventContinuation) blockingErrors.clear();
        boolean proceed = allProceed && !preventContinuation && blockingErrors.isEmpty();
        return new HookDispatcher.HookOutcome(
            proceed,
            null,
            List.copyOf(blockingErrors),
            preventContinuation,
            stopReason,
            userDisplayMessages.isEmpty() ? null : String.join("\n", userDisplayMessages),
            List.copyOf(additionalContexts),
            List.copyOf(specificOutputs));
    }

    /** Drains leader→teammate control messages, servicing them without polling. */
    private void runMailboxReader() {
        try {
            // Exit when killed OR the runner has finished (so a natural exit cleans up the reader).
            while (!killed.get() && (runnerThread == null || runnerThread.isAlive())) {
                Mail mail = mailbox.receive(getTaskId());
                handleMail(mail);
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleMail(Mail mail) {
        switch (mail.type()) {
            case MailTypes.SHUTDOWN_REQUEST -> {

                log.info("Teammate {} received shutdown_request (delegated to model)", getTaskId());
                shutdownRequested = true;
                if (!nextPrompts.offer(formatShutdownMessage(mail.payload()))) {
                    log.warn("Teammate {} nextPrompts queue full; shutdown request dropped", getTaskId());
                }
            }
            case MailTypes.PERMISSION_RESPONSE -> {
                CompletableFuture<PermissionAskCallback.Result> f = pendingPermissions.remove(mail.requestId());
                if (f != null) {
                    f.complete(decodeDecision(mail.payload()));
                }
            }
            case MailTypes.PLAN_APPROVAL_RESPONSE -> {
                CompletableFuture<PlanApproval> f = pendingApprovals.remove(mail.requestId());
                if (f != null) {
                    PlanApproval pa = decodeApproval(mail.payload());

                    // (plan → default) once its plan is approved. The next turn
                    // re-reads context.permissionMode(), so the switch takes
                    // effect on resume.
                    if (pa.approved() && pa.mode() != null && !StringUtils.isBlank(pa.mode())) {
                        context.setPermissionMode(PermissionMode.fromString(pa.mode()));
                    }
                    if (pa.approved()) {

                        context.setPlanModeRequired(false);
                    }
                    f.complete(pa);
                }
            }
            case MailTypes.USER_MESSAGE -> {
                appendUserMessage(mail.payload());

                if (!nextPrompts.offer(mail.payload())) {
                    log.warn("Teammate {} nextPrompts queue full; injected user message dropped", getTaskId());
                }
            }
            case MailTypes.TASK_ASSIGNMENT -> {
                String prompt = formatAsTeammateMessage(
                    mail.from(), mail.payload(), null, null);
                if (!nextPrompts.offer(prompt)) {
                    log.warn("Teammate {} nextPrompts queue full; task assignment dropped",
                        getTaskId());
                }
            }
            default -> log.debug("Teammate {} ignoring mail type {}", getTaskId(), mail.type());
        }
        appendMessage(mail);
    }

    /**
     * Wraps a leader/peer message as a teammate-message the model can identify.
     */
    private static String formatAsTeammateMessage(String from, String text, String color, String summary) {
        StringBuilder sb = new StringBuilder();
        sb.append('<').append(TEAMMATE_MESSAGE_TAG).append(" teammate_id=\"").append(from == null ? "" : from).append('"');
        if (color != null) sb.append(" color=\"").append(color).append('"');
        if (summary != null) sb.append(" summary=\"").append(summary).append('"');
        sb.append(">\n").append(text == null ? "" : text).append("\n</").append(TEAMMATE_MESSAGE_TAG).append('>');
        return sb.toString();
    }

    /** Wraps a leader shutdown request as a teammate-message the model can act on. */
    private static String formatShutdownMessage(String payload) {
        return formatAsTeammateMessage("team-lead",
            "The team leader has requested this teammate shut down.\n"
                + "If you agree, call the shutdown approval tool; otherwise explain why you should continue.\n"
                + "Original request: " + payload, null, null);
    }

    /** Notifies the leader this teammate has finished a turn and is idle/available. */
    private void sendIdleNotification(String idleReason) {
        // Dedupe consecutive identical idle notifications.
        if (idleReason.equals(lastIdleReason)) {
            return;
        }
        lastIdleReason = idleReason;
        String payload = "teammate=" + getTaskId()
            + " name=" + (context.name() != null ? context.name() : "")
            + " reason=" + idleReason
            + " summary=" + lastPeerDmSummary();
        mailbox.send(Mail.of(MailTypes.IDLE_NOTIFICATION, getTaskId(), TeammateMailbox.TEAM_LEAD, payload));
    }

    /** Best-effort summary of the last peer DM for the idle notification payload. */
    private String lastPeerDmSummary() {
        synchronized (messages) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                Mail m = messages.get(i);
                if (MailTypes.INTERRUPT.equals(m.type())) continue;
                if (StringUtils.isNotBlank(m.payload())) {
                    String p = m.payload();
                    return p.length() > 120 ? p.substring(0, 120) : p;
                }
            }
        }
        return "";
    }

    /**
     * Forwards a permission ask to the leader over the mailbox and blocks until
     * the leader replies. Runs on the teammate's own virtual thread, so it does
     * not block the leader. Preserves compatibility with {@code leaderPermissionBridge}.
     */
    public PermissionAskCallback.Result requestPermission(PermissionAskContext ctx) {
        String requestId = Mail.newRequestId();
        CompletableFuture<PermissionAskCallback.Result> f = new CompletableFuture<>();
        pendingPermissions.put(requestId, f);
        // Structured payload so the leader consumer can faithfully reconstruct the
        // PermissionAskContext (tool name, input, worker badge = this teammate's id).
        String payload = encodePermissionRequest(ctx);
        // Carry the real requestId so the leader's reply can be correlated back
        // to this pending future (Mail.of blanks it, which would strand f.join).
        mailbox.send(new Mail(MailTypes.PERMISSION_REQUEST, requestId, getTaskId(), TeammateMailbox.TEAM_LEAD, payload));
        long waitStartedNanos = System.nanoTime();
        try {
            return f.join();
        } catch (CancellationException _) {
            return PermissionAskCallback.Result.deny();
        } finally {
            totalPausedMs.addAndGet(TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - waitStartedNanos));
        }
    }

    private void recordUsage(String messageId, Usage usage) {
        if (messageId == null || usage == null) return;
        synchronized (usageByMessage) {
            usageByMessage.put(messageId, usage);
            long latestInput = 0L;
            long cumulativeOutput = 0L;
            for (Usage snapshot : usageByMessage.values()) {
                latestInput = snapshot.inputTokens()
                    + snapshot.cacheCreationInputTokens()
                    + snapshot.cacheReadInputTokens();
                cumulativeOutput += snapshot.outputTokens();
            }
            progressTokens.set(Math.max(0L, latestInput + cumulativeOutput));
        }
    }

    private void resetTurnProgressTracker() {
        synchronized (usageByMessage) {
            usageByMessage.clear();
        }
        countedProgressMessages.clear();
        progressTokens.set(0L);
        progressToolUses.set(0);
        synchronized (recentActivities) {
            recentActivities.clear();
        }
        progressActivity = null;
    }

    public long totalPausedMillis() {
        return totalPausedMs.get();
    }

    public long progressTokens() {
        return progressTokens.get();
    }

    public int progressToolUses() {
        return progressToolUses.get();
    }

    public String progressActivity() {
        String boardActivity = taskBoardActivity();
        if (boardActivity != null) return boardActivity;
        return progressActivity;
    }

    /** Activity shown on the task board; generic streaming progress is intentionally excluded. */
    public String taskBoardActivity() {
        synchronized (recentActivities) {
            return recentActivities.isEmpty() ? null : summarizeRecentActivities();
        }
    }

    private void recordActivities(List<ToolUseBlock> toolUses) {
        if (toolUses.isEmpty()) return;
        synchronized (recentActivities) {
            for (ToolUseBlock toolUse : toolUses) {
                if (Strings.CS.equals("StructuredOutput", toolUse.name())
                        || Strings.CS.equals("REPL", toolUse.name())) {
                    continue;
                }
                ActivityClassification classification = activityClassification(toolUse);
                recentActivities.add(new RecentActivity(
                    activityDescription(toolUse), classification.search(),
                    classification.read()));
                if (recentActivities.size() > MAX_RECENT_ACTIVITIES) {
                    recentActivities.removeFirst();
                }
            }
            String summary = summarizeRecentActivities();
            if (taskStore != null && summary != null) {
                taskStore.updateProgressSummary(getTaskId(), summary);
            }
        }
    }

    private String summarizeRecentActivities() {
        int searchCount = 0;
        int readCount = 0;
        for (int index = recentActivities.size() - 1; index >= 0; index--) {
            RecentActivity activity = recentActivities.get(index);
            if (activity.search()) searchCount++;
            else if (activity.read()) readCount++;
            else break;
        }
        if (searchCount + readCount >= 2) {
            List<String> parts = new ArrayList<>(2);
            if (searchCount > 0) {
                parts.add("Searching for " + searchCount + " "
                    + (searchCount == 1 ? "pattern" : "patterns"));
            }
            if (readCount > 0) {
                String verb = parts.isEmpty() ? "Reading" : "reading";
                parts.add(verb + " " + readCount + " "
                    + (readCount == 1 ? "file" : "files"));
            }
            return String.join(", ", parts) + "…";
        }
        for (int index = recentActivities.size() - 1; index >= 0; index--) {
            String description = recentActivities.get(index).description();
            if (description != null) return description;
        }
        return null;
    }

    private String activityDescription(ToolUseBlock toolUse) {
        return TaskActivityDescription.describe(toolUse, activityWorkingDirectory());
    }

    private Path activityWorkingDirectory() {
        String cwd = teammateRequest.cwd();
        if (StringUtils.isBlank(cwd) && teammateRequest.parentContext() != null) {
            cwd = teammateRequest.parentContext().workingDirectory();
        }
        if (StringUtils.isBlank(cwd)) cwd = System.getProperty("user.dir", ".");
        try {
            return Path.of(cwd);
        } catch (InvalidPathException _) {
            return Path.of(System.getProperty("user.dir", "."));
        }
    }

    private static ActivityClassification activityClassification(ToolUseBlock toolUse) {
        String name = toolUse.name();
        if (Strings.CS.equals("Grep", name) || Strings.CS.equals("Glob", name)) {
            return ActivityClassification.SEARCH;
        }
        if (Strings.CS.equals("Read", name) || Strings.CS.equals("FileRead", name)) {
            return ActivityClassification.READ;
        }
        String command = text(toolUse.input(), "command");
        if (Strings.CS.equals("Bash", name)) {
            BashTool.SearchReadClassification classification =
                BashTool.classifySearchOrReadCommand(command);
            return new ActivityClassification(
                classification.isSearch(), classification.isRead());
        }
        if (Strings.CS.equals("PowerShell", name)) {
            PowerShellTool.SearchReadClassification classification =
                PowerShellTool.classifySearchOrReadCommand(command);
            return new ActivityClassification(
                classification.isSearch(), classification.isRead());
        }
        return ActivityClassification.OTHER;
    }

    private static String text(JsonNode input, String field) {
        if (input == null) return null;
        String value = input.path(field).asText("");
        return StringUtils.isBlank(value) ? null : value;
    }

    private record ActivityClassification(boolean search, boolean read) {
        private static final ActivityClassification SEARCH =
            new ActivityClassification(true, false);
        private static final ActivityClassification READ =
            new ActivityClassification(false, true);
        private static final ActivityClassification OTHER =
            new ActivityClassification(false, false);
    }

    private record RecentActivity(String description, boolean search, boolean read) {}

    public boolean isAwaitingPlanApproval() {
        return !pendingApprovals.isEmpty();
    }

    /** Encodes a permission ask as JSON for the leader consumer (Preserves compatibility with worker-badge ask). */
    private String encodePermissionRequest(PermissionAskContext ctx) {
        try {
            ObjectNode n = JsonUtils.getMapper().createObjectNode();
            n.put("teammate", getTaskId());
            n.put("toolName", ctx.toolName() != null ? ctx.toolName() : "?");
            n.put("toolUseId", ctx.toolUseId());
            n.put("workerId", getTaskId());
            if (ctx.input() != null) {
                n.set("input", ctx.input());
            }
            // Forward the rich permission hints so the leader's dialog can render the
            // "why ASK" line, the "allow [pattern]" suggestion, and the destructive
            // warning.
            if (ctx.decisionReasonType() != null) n.put("decisionReasonType", ctx.decisionReasonType());
            if (ctx.decisionReasonDetail() != null) n.put("decisionReasonDetail", ctx.decisionReasonDetail());
            if (ctx.suggestionRuleContent() != null) n.put("suggestionRuleContent", ctx.suggestionRuleContent());
            if (ctx.suggestionLabel() != null) n.put("suggestionLabel", ctx.suggestionLabel());
            if (ctx.destructiveWarning() != null) n.put("destructiveWarning", ctx.destructiveWarning());
            if (ctx.blockedPath() != null) n.put("blockedPath", ctx.blockedPath());
            if (ctx.customMessage() != null) n.put("customMessage", ctx.customMessage());
            if (!StringUtils.isBlank(ctx.toolDescription())) n.put("toolDescription", ctx.toolDescription());
            if (!ctx.suggestions().isEmpty()) {
                n.set("suggestions", PermissionUpdateJsonCodec.toJson(ctx.suggestions()));
            }
            return JsonUtils.getMapper().writeValueAsString(n);
        } catch (Exception _) {
            return "{\"teammate\":\"" + getTaskId() + "\"}";
        }
    }

    /**
     * Asks the leader to approve a plan the teammate wants to act on.
     */
    public PlanApproval requestPlanApproval(String planSummary) {
        String requestId = Mail.newRequestId();
        CompletableFuture<PlanApproval> f = new CompletableFuture<>();
        pendingApprovals.put(requestId, f);
        // Carry the real requestId for correlation (Mail.of blanks it).
        mailbox.send(new Mail(MailTypes.PLAN_APPROVAL_REQUEST, requestId, getTaskId(), TeammateMailbox.TEAM_LEAD, planSummary));
        try {
            return f.join();
        } catch (CancellationException _) {
            return new PlanApproval(false, "cancelled", null);
        }
    }

    /**
     * Sends a plan approval request without blocking the tool call.
     */
    public String submitPlanApprovalRequest(String planSummary) {
        return submitPlanApprovalRequest(planSummary, _ -> {});
    }

    /**
     * Sends a plan approval request and observes the eventual leader decision without blocking.
     */
    public String submitPlanApprovalRequest(
            String planSummary, Consumer<PlanApproval> completionCallback) {
        String requestId = Mail.newRequestId();
        CompletableFuture<PlanApproval> future = new CompletableFuture<>();
        pendingApprovals.put(requestId, future);
        future.whenComplete((approval, failure) -> {
            if (failure != null || approval == null || killed.get()) return;
            try {
                completionCallback.accept(approval);
            } catch (RuntimeException e) {
                log.warn("Plan approval completion callback failed for teammate {}",
                    getTaskId(), e);
            }
            String message;
            if (approval.approved()) {
                message = "The team lead approved your plan. You may now proceed with implementation.";
            } else {
                String feedback = StringUtils.isBlank(approval.feedback())
                    ? "Refine the plan and submit it again."
                    : approval.feedback();
                message = "The team lead rejected your plan. Refine it and submit it again.\n"
                    + "Feedback: " + feedback;
            }
            nextPrompts.add(message);
        });
        mailbox.send(new Mail(MailTypes.PLAN_APPROVAL_REQUEST, requestId, getTaskId(),
            TeammateMailbox.TEAM_LEAD, planSummary));
        return requestId;
    }

    /**
     * Injects a user message into the teammate.
     */
    public void injectUserMessage(String message) {
        if (!isActive()) {
            return;
        }
        mailbox.send(Mail.of(MailTypes.USER_MESSAGE, TeammateMailbox.TEAM_LEAD, getTaskId(), message));
    }

    /**
     * Leader requests this teammate to shut down.
     */
    public void requestTeammateShutdown() {
        if (!isActive() || shutdownRequested) {
            return;
        }
        shutdownRequested = true;
        mailbox.send(Mail.of(MailTypes.SHUTDOWN_REQUEST, TeammateMailbox.TEAM_LEAD, getTaskId(), "shutdown"));
    }

    /**
     * True once a shutdown has been requested.
     */
    public boolean isShutdownRequested() {
        return shutdownRequested;
    }

    /**
     * Interrupts only the current turn (Esc semantics), keeping the teammate alive for subsequent
     * turns.
     */
    public void abortCurrentTurn() {
        if (currentWorkAbortController != null) {
            currentWorkAbortController.abort("user interrupted turn");
        }
    }

/**
     * Updates the teammate's permission mode; picked up on the next turn.
     */
    public void setPermissionMode(PermissionMode mode) {
        context.setPermissionMode(mode);
    }

/**
     * Display name the leader/peers address this teammate by.
     */
    public String name() {
        return context.name();
    }

    /** Team whose shared task list this teammate belongs to, or {@code null}. */
    public String teamId() {
        return context.teamId();
    }

/**
     * Current permission mode — re-read each turn by the run loop.
     */
    public PermissionMode permissionMode() {
        return context.permissionMode();
    }

    /** Best-effort current task status (RUNNING while the loop is alive). */
    public TaskStatus status() {
        if (taskStore == null) {
            return TaskStatus.RUNNING;
        }
        return taskStore.get(getTaskId()).map(TaskState::status).orElse(TaskStatus.RUNNING);
    }

/**
     * True while the run loop is alive and not aborted.
     */
    public boolean isRunning() {
        return isActive();
    }

    /**
     * True after a completed turn has announced that this teammate is idle, until the next turn starts.
     */
    public boolean isIdle() {
        return isActive() && lastIdleReason != null;
    }

    /**
     * Best-effort preview of the teammate's latest surfaced message (skips the
     * interrupt sentinel), truncated to {@code maxLen} chars. Used by the REPL
     * teammate-view header so the user can see what the teammate is doing.
     */
    public String lastMessagePreview(int maxLen) {
        synchronized (messages) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                Mail m = messages.get(i);
                if (MailTypes.INTERRUPT.equals(m.type())) continue;
                if (StringUtils.isNotBlank(m.payload())) {
                    String p = m.payload();
                    return p.length() > maxLen ? p.substring(0, maxLen) : p;
                }
            }
        }
        return "";
    }

    /** Appends a teammate-side message to the capped log. */
    public void appendMessage(Mail mail) {
        synchronized (messages) {
            messages.add(mail);
            while (messages.size() > MAX_MESSAGES) {
                messages.removeFirst();
            }
        }
    }

    /** Returns a thread-safe copy of the teammate's capped message log. */
    public List<Mail> messages() {
        synchronized (messages) {
            return new ArrayList<>(messages);
        }
    }

    /**
     * Returns a thread-safe copy of the teammate's display transcript (the real prompt↔response
     * conversation shown when the REPL views this teammate).
     */
    public List<Message> displayTranscript() {
        synchronized (displayTranscript) {
            return new ArrayList<>(displayTranscript);
        }
    }

    /**
     * Registers a listener invoked whenever the display transcript changes, so the UI can re-render a
     * live, running teammate's viewed transcript in real time.
     */
    public void setTranscriptListener(Runnable listener) {
        this.transcriptListener = listener;
    }


    boolean hasTranscriptListener() {
        return transcriptListener != null;
    }

    /** Appends messages to the display transcript, deduping by uuid and capping at {@link #DISPLAY_TRANSCRIPT_CAP}. */
    private void appendToDisplayTranscript(List<Message> msgs) {
        if (msgs == null || msgs.isEmpty()) return;
        synchronized (displayTranscript) {
            Set<String> existing = displayTranscript.stream().map(Message::uuid).collect(Collectors.toSet());
            for (Message m : msgs) {
                if (m.uuid() != null && existing.contains(m.uuid())) continue;
                displayTranscript.add(m);
                if (m.uuid() != null) existing.add(m.uuid());
            }
            while (displayTranscript.size() > DISPLAY_TRANSCRIPT_CAP) {
                displayTranscript.removeFirst();
            }
        }
        fireTranscriptListener();
    }

    private void fireTranscriptListener() {
        Runnable r = transcriptListener;
        if (r != null) r.run();
    }

    private void appendUserMessage(String message) {
        synchronized (pendingUserMessages) {
            pendingUserMessages.add(message);
            while (pendingUserMessages.size() > MAX_MESSAGES) {
                pendingUserMessages.removeFirst();
            }
        }
    }

/**
     * Registers a no-poll wait primitive.
     */
    public CompletableFuture<Void> onIdle() {
        CompletableFuture<Void> f = new CompletableFuture<>();
        if (killed.get()) {
            f.complete(null);
            return f;
        }
        synchronized (onIdleCallbacks) {
            onIdleCallbacks.add(f);
        }
        return f;
    }

    private void fireIdle() {
        List<CompletableFuture<Void>> cbs;
        synchronized (onIdleCallbacks) {
            cbs = new ArrayList<>(onIdleCallbacks);
            onIdleCallbacks.clear();
        }
        cbs.forEach(f -> f.complete(null));
    }

    /**
     * Stops the teammate: aborts the run, interrupts threads, unblocks waiters.
     */
    public void stop() {
        // Mark explicitly-killed BEFORE anything else so the runner thread's
        // onLoopExit() (which may run concurrently once we interrupt it) observes
        // it and leaves the KILLED status intact instead of clobbering to COMPLETED.
        explicitlyKilled = true;
        if (!killed.compareAndSet(false, true)) {
            releaseOwnedTasks();
            return;
        }
        transcriptListener = null;
        context.abortController().abort("shutdown via /tasks");
        // Abort the in-flight per-turn controller so the current runSubAgent unwinds.
        if (currentWorkAbortController != null) {
            currentWorkAbortController.abort("shutdown via /tasks");
        }
        // Unblock any teammate thread parked on a leader decision.
        pendingPermissions.values().forEach(f -> f.complete(PermissionAskCallback.Result.deny()));
        pendingApprovals.values().forEach(f -> f.complete(new PlanApproval(false, "shutdown", null)));
        if (runnerThread != null) {
            runnerThread.interrupt();
        }
        if (mailboxThread != null) {
            mailboxThread.interrupt();
        }
        mailbox.clear(getTaskId());
        if (context.name() != null) mailbox.unregisterName(context.name());
        setTeamMemberActive(false);
        releaseOwnedTasks();
        // Terminate the task record (explicit stop = the teammate has ended).
        if (taskStore != null) {
            var cur = taskStore.get(getTaskId());
            if (cur.isPresent() && cur.get().status() == TaskStatus.RUNNING) {
                taskStore.updateStatusAndMarkNotified(getTaskId(), TaskStatus.KILLED);
            }
        }
        fireIdle();
        log.info("In-process teammate {} stopped", getTaskId());
    }

    /**
     * Called when the run loop exits naturally (lifecycle abort or loop end).
     */
    private void onLoopExit() {
        killed.set(true);
        setTeamMemberActive(false);
        releaseOwnedTasks();
        if (mailboxThread != null) {
            mailboxThread.interrupt();
        }
        if (taskStore != null) {
            var cur = taskStore.get(getTaskId());
            // Only a NATURAL exit (lifecycle abort, loop end) becomes COMPLETED.
// An explicit stop/kill sets explicitlyKilled and writes KILLED
            // itself; leaving that status intact here avoids a race where this
            // method could otherwise clobber KILLED back to COMPLETED.
            if (cur.isPresent() && cur.get().status() == TaskStatus.RUNNING && !explicitlyKilled) {
                taskStore.updateStatus(getTaskId(), TaskStatus.COMPLETED);
                taskStore.markNotified(getTaskId());
            }
        }
        fireIdle();
    }

    private synchronized void releaseOwnedTasks() {
        if (teamTodoStore == null || ownedTasksReleased) return;
        ownedTasksReleased = true;
        try {
            teamTodoStore.reload();
            String agentId = getTaskId();
            String agentName = context.name();
            List<Task> owned = teamTodoStore.list().stream()
                .filter(task -> task.status() != TodoStatus.COMPLETED)
                .filter(task -> task.owner().filter(owner ->
                    owner.equals(agentId) || owner.equals(agentName)).isPresent())
                .toList();
            for (Task task : owned) {
                teamTodoStore.updateAtomically(task.id(), current ->
                    current.withOwner(null).withStatus(TodoStatus.PENDING));
            }
            if (!owned.isEmpty()) {
                log.info("Teammate {} released {} open task(s)", agentId, owned.size());
            }
        } catch (RuntimeException e) {
            log.warn("Teammate {} failed to release owned tasks: {}",
                getTaskId(), e.getMessage());
        }
    }

/** Kill entry point used by {@link TaskRegistry#killTeammate} — matches {@link LocalAgentTask#kill}. */
    public boolean kill() {
        if (taskStore == null) {
            return false;
        }
        var current = taskStore.get(getTaskId());
        if (current.isEmpty() || current.get().status() != TaskStatus.RUNNING) {
            return false;
        }
        stop();
        taskStore.updateStatusAndMarkNotified(getTaskId(), TaskStatus.KILLED);
        return true;
    }

    private void fail(String error) {
        if (taskStore != null) {
            taskStore.updateError(getTaskId(), error);
        }
        transitionIfRunning(TaskStatus.FAILED);
        finish();
    }

    private void finish() {
        if (mailboxThread != null) {
            mailboxThread.interrupt();
        }
        fireIdle();
    }

    private void setTeamMemberActive(boolean active) {
        if (StringUtils.isNotBlank(context.teamId())) {
            TeamRegistry.instance().setAgentActive(context.teamId(), getTaskId(), active);
        }
    }

    private void transitionIfRunning(TaskStatus target) {
        var cur = taskStore.get(getTaskId());
        if (cur.isPresent() && cur.get().status() == TaskStatus.RUNNING) {
            taskStore.updateStatus(getTaskId(), target);
        }
    }



    /**
     * Tries to claim an available task from the team's shared todo list and
     * returns it formatted as a prompt, or null if none available. Claims the
     * task (owner = this teammate, status = in_progress) so two teammates don't
     * grab the same one.
     */
    private String tryClaimNextTask(TodoStore store, String agentName) {
        try {
            store.reload();
            List<Task> tasks = store.list();
            Task available = findAvailableTask(tasks);
            if (available == null) {
                return null;
            }
            if (store.claim(available.id(), agentName).isEmpty()) return null;
            store.updateAtomically(available.id(),
                current -> current.withStatus(TodoStatus.IN_PROGRESS));
            log.info("Teammate {} claimed team task #{}: {}", getTaskId(), available.id(), available.subject());
            return formatTaskAsPrompt(available);
        } catch (Exception e) {
            log.warn("Teammate {} failed to claim team task: {}", getTaskId(), e.getMessage());
            return null;
        }
    }

    /** A task is claimable when pending, unowned, and all its blockers are resolved. */
    static Task findAvailableTask(List<Task> tasks) {
        for (Task t : tasks) {
            if (t.status() != TodoStatus.PENDING) continue;
            if (!t.owner().orElse("").isEmpty()) continue;
            boolean blocked = t.blockedBy().stream().anyMatch(id -> !isResolved(tasks, id));
            if (blocked) continue;
            return t;
        }
        return null;
    }

    private static boolean isResolved(List<Task> tasks, String id) {
        for (Task t : tasks) {
            if (t.id().equals(id)) {
                return t.status() == TodoStatus.COMPLETED;
            }
        }
        // Unknown blocker id → treat as resolved so we don't deadlock on dangling refs.
        return true;
    }

/**
     * Formats a claimed team task as the next prompt for the teammate.
     */
    static String formatTaskAsPrompt(Task task) {
        StringBuilder sb = new StringBuilder();
        sb.append("Complete all open tasks. Start with task #").append(task.id())
            .append(": \n\n ").append(task.subject());
        if (!task.description().isEmpty()) {
            sb.append("\n\n").append(task.description());
        }
        return sb.toString();
    }

    // ── Helpers ─

    private static PermissionAskCallback.Result decodeDecision(String payload) {
        try {
            JsonNode n = JsonUtils.getMapper().readTree(payload);
            boolean allowed = n.has("allowed") && n.get("allowed").asBoolean();
            String feedback = n.has("feedback") ? n.get("feedback").asText() : "";
            if (!allowed) {
                return StringUtils.isBlank(feedback) ? PermissionAskCallback.Result.deny()
                    : PermissionAskCallback.Result.denyWithFeedback(feedback);
            }
            if (!StringUtils.isBlank(feedback)) {
                return PermissionAskCallback.Result.allowWithFeedback(feedback);
            }
            return PermissionAskCallback.Result.allow();
        } catch (Exception _) {
            return PermissionAskCallback.Result.deny();
        }
    }

    /** Encodes a leader decision back to the teammate (used by the leader-side reply helper). */
    public static String encodeDecision(PermissionAskCallback.Result r) {
        try {
            ObjectNode n = JsonUtils.getMapper().createObjectNode();
            n.put("allowed", r.allowed());
            n.put("feedback", r.feedback() == null ? "" : r.feedback());
            return JsonUtils.getMapper().writeValueAsString(n);
        } catch (Exception _) {
            return "{\"allowed\":false}";
        }
    }

    private static PlanApproval decodeApproval(String payload) {
        try {
            JsonNode n = JsonUtils.getMapper().readTree(payload);
            boolean approved = n.has("approved") && n.get("approved").asBoolean();
            String feedback = n.has("feedback") ? n.get("feedback").asText() : "";
            String mode = n.has("mode") ? n.get("mode").asText() : null;
            return new PlanApproval(approved, feedback, mode);
        } catch (Exception _) {
            return new PlanApproval(false, "unreadable response", null);
        }
    }

    /** Encodes a plan-approval decision (used by the leader-side reply helper). */
    public static String encodeApproval(PlanApproval a) {
        try {
            ObjectNode n = JsonUtils.getMapper().createObjectNode();
            n.put("approved", a.approved());
            n.put("feedback", a.feedback() == null ? "" : a.feedback());
            n.put("mode", a.mode() == null ? "" : a.mode());
            return JsonUtils.getMapper().writeValueAsString(n);
        } catch (Exception _) {
            return "{\"approved\":false}";
        }
    }

/**
     * Leader's plan-approval decision returned to a teammate.
     */
    public record PlanApproval(boolean approved, String feedback, String mode) {}
}
