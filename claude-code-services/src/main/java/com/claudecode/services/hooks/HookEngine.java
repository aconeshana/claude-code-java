package com.claudecode.services.hooks;

import com.claudecode.core.engine.AsyncHookResponse;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.engine.SubAgentLifecycleListener;
import com.claudecode.core.message.Message;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.services.http.ServiceHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;
import okhttp3.OkHttpClient;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hook dispatch façade: builds the per-event {@link HookInput}, resolves the
 * eligible hooks through {@link HookRegistry}, runs them in parallel on virtual
 * threads through {@link HookCommandRunner}, publishes their effects, and
 * aggregates the results into the {@link HookDispatcher.HookOutcome} the query
 * loop consumes. Composition roots configure the engine through its component
 * accessors ({@link #registry()}, {@link #context()}, {@link #effects()},
 * {@link #llm()}, {@link #goalEvaluator()}, {@link #http()}, {@link #goals()})
 * rather than through setters on the engine itself.
 *
 * <ul>
 *   <li>{@code src/utils/hooks.ts} — {@code executeHooks}: eligible-hook
 *       resolution, parallel execution, per-event default timeouts,
 *       config-async backgrounding, and outcome aggregation.</li>
 *   <li>{@code src/utils/hooks.ts} — the per-event entry points (PreToolUse,
 *       PostToolUse, Stop, SessionStart, PreCompact, Notification, ...) and
 *       their fail-open error handling.</li>
 *   <li>{@code src/utils/hooks/hookEvents.ts} — event-specific input shaping
 *       for the WorktreeCreate / TaskCreated / TaskCompleted probes.</li>
 *   <li>{@code src/utils/hooks/registerFrontmatterHooks.ts} — child agent
 *       dispatcher creation with frontmatter hooks and SubagentStop scoping.</li>
 * </ul>
 */
public class HookEngine implements HookDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(HookEngine.class);

    /** Default timeout for Stop (SessionEnd) hooks — kept tight because they run during shutdown. */
    private static final int SESSION_END_HOOK_TIMEOUT_MS_DEFAULT = 1500;

    private final HookRegistry registry;
    private final HookSessionContext context;
    private final HookEffects effects;
    private final HookLlmBindings llm;
    private final GoalEvaluatorBindings goalEvaluator;
    private final HttpHookExecutor http;
    private final BashHookExecutor bash;
    private final StopConditionEvaluator stopConditions;
    private final HookCommandRunner runner;
    private final GoalTracker goals;

    // ---- construction ----

    public HookEngine(HooksSettings settings, String workingDirectory) {
        this(settings, workingDirectory, SessionIdentity.newRandom());
    }

    public HookEngine(HooksSettings settings, String workingDirectory, SessionIdentity sessionIdentity) {
        this(settings, workingDirectory, ServiceHttpClient.noRedirects(), sessionIdentity, true);
    }

    public HookEngine(HooksSettings settings, String workingDirectory, OkHttpClient httpClient) {
        this(settings, workingDirectory, httpClient, SessionIdentity.newRandom());
    }

    public HookEngine(HooksSettings settings, String workingDirectory, OkHttpClient httpClient,
                      SessionIdentity sessionIdentity) {
        this(settings, workingDirectory, httpClient, sessionIdentity, false);
    }

    /**
     * @param workingDirectory  {@code null} follows the live JVM cwd (production); non-null pins a
     *                          fixed directory (tests, child agents)
     * @param managedHttpClient when true, HTTP hooks build a per-URL client honoring the sandbox proxy
     */
    private HookEngine(HooksSettings settings, String workingDirectory, OkHttpClient httpClient,
                       SessionIdentity sessionIdentity, boolean managedHttpClient) {
        this(new HookRegistry(settings), new HookSessionContext(sessionIdentity, workingDirectory),
            new HookEffects(), new HookLlmBindings(), new GoalEvaluatorBindings(),
            new HttpHookExecutor(httpClient, managedHttpClient, new HookOutputParser()));
    }

    private HookEngine(HookRegistry registry, HookSessionContext context, HookEffects effects,
                       HookLlmBindings llm, GoalEvaluatorBindings goalEvaluator,
                       HttpHookExecutor http) {
        this.registry = registry;
        this.context = context;
        this.effects = effects;
        this.llm = llm;
        this.goalEvaluator = goalEvaluator;
        this.http = http;
        HookOutputParser parser = new HookOutputParser();
        this.bash = new BashHookExecutor(context, registry, effects, parser);
        this.stopConditions = new StopConditionEvaluator(llm, goalEvaluator, context, effects, parser);
        PromptHookExecutor prompts = new PromptHookExecutor(llm, effects, parser, stopConditions);
        AgentHookExecutor agents = new AgentHookExecutor(llm, context, prompts);
        this.runner = new HookCommandRunner(bash, http, prompts, agents, parser);
        this.goals = new GoalTracker(registry);
    }

    /** Creates an isolated hook engine for one child agent invocation. */
    public HookDispatcher createSubAgentDispatcher(
            SubAgentLifecycleListener.SubAgentHookContext child) {
        return new HookEngine(
            registry.forChild(child.frontmatterHooks()),
            context.forChild(child),
            effects.forChild(),
            llm,
            goalEvaluator.withEffort(child::effort),
            http);
    }

    // ---- components ----

    /** Hook source layers: settings, plugins, SDK callbacks, skill and session hooks. */
    public HookRegistry registry() {
        return registry;
    }

    /** Session id, cwd, permission mode, prompt id, and conversation view. */
    public HookSessionContext context() {
        return context;
    }

    /** UI sink, model wake-up queue, and attachment outbox. */
    public HookEffects effects() {
        return effects;
    }

    /** Side-query client, live model, and sub-agent runtime for LLM-backed hooks. */
    public HookLlmBindings llm() {
        return llm;
    }

    /** Main-session request parameters mirrored onto the Stop-condition evaluator. */
    public GoalEvaluatorBindings goalEvaluator() {
        return goalEvaluator;
    }

    /** HTTP-hook client, URL policy, and sandbox proxy environment. */
    public HttpHookExecutor http() {
        return http;
    }

    /** The /goal state machine. */
    public GoalTracker goals() {
        return goals;
    }

    // ---- HookDispatcher: lifecycle & registries ----

    @Override
    public void setForceSyncExecution(boolean value) {
        bash.setForceSyncExecution(value);
    }

    @Override
    public void installInvocationHooks(HookDispatcher.InvocationHooks hooks, Path sourceRoot) {
        if (hooks instanceof HooksSettings invocationSettings) {
            registry.addExtraHooks(invocationSettings, sourceRoot);
        }
    }

    @Override
    public void clearInvocationHooks() {
        registry.clearExtraHooks();
    }

    @Override
    public boolean setGoal(String condition, long tokensAtStart) {
        if (StringUtils.isBlank(condition)) return false;
        return goals.set(condition, tokensAtStart);
    }

    @Override
    public String clearGoal() {
        return goals.clear();
    }

    @Override
    public Optional<HookDispatcher.ActiveGoal> activeGoal() {
        return goals.active();
    }

    @Override
    public Optional<HookDispatcher.GoalTransition> consumeGoalTransition() {
        return goals.consumeTransition();
    }

    @Override
    public void restoreGoalFromTranscript(List<Message> messages, long tokensAtStart) {
        goals.restoreFromTranscript(messages, tokensAtStart);
    }

    @Override
    public List<Message> consumeHookMessages() {
        return effects.drain();
    }

    @Override
    public List<AsyncHookResponse> checkForAsyncHookResponses() {
        return bash.checkForAsyncHookResponses();
    }

    @Override
    public void removeDeliveredAsyncHooks(List<String> processIds) {
        bash.removeDeliveredAsyncHooks(processIds);
    }

    @Override
    public void finalizePendingAsyncHooks() {
        bash.finalizePendingAsyncHooks();
    }

    // ---- execution core ----

    /** Executes all matching hooks for the given event in parallel. */
    public List<HookResult> executeHooks(HookEvent event, HookInput input) {
        return executeHooksWithCommands(event, input, _ -> true).stream()
            .map(HookExecution::result)
            .toList();
    }

    private List<HookExecution> executeHooksWithCommands(HookEvent event, HookInput input,
                                                          Predicate<HookCommand> include) {
        List<HookMatchResolver.MatchedHook> eligible = registry.eligible(event, input, include);
        if (eligible.isEmpty()) {
            return List.of();
        }

        // Per-event default timeout: only SessionEnd hooks get a tight bound
        // (1.5 s by default) because they run during shutdown; everything else
        // gets the 10-minute tool-hook timeout.
        long defaultTimeoutMillis = event == HookEvent.SESSION_END
            ? getSessionEndHookTimeoutMillis()
            : BashHookExecutor.TOOL_HOOK_TIMEOUT_SECONDS * 1000L;

        // Tool hooks reuse the model's tool_use id; lifecycle callbacks receive a
        // random UUID that belongs only to the SDK control envelope, not HookInput JSON.
        String callbackToolUseId = input.toolUseId()
            .orElseGet(() -> UUID.randomUUID().toString());

        List<CompletableFuture<HookExecution>> futures = eligible.stream()
            .map(hook -> CompletableFuture.supplyAsync(
                () -> launch(hook.command(), input, defaultTimeoutMillis, callbackToolUseId),
                r -> Thread.ofVirtual().start(r)))
            .toList();

        List<HookExecution> executions = futures.stream()
            .map(f -> {
                try { return f.join(); }
                catch (Exception e) {
                    LOG.warn("Hook execution failed (async join): {}", e.getMessage());
                    return new HookExecution(null, HookResult.skip(), false, null);
                }
            })
            .toList();
        effects.publish(event, input, executions);
        return executions;
    }

    /**
     * Config-declared async hooks are fire-and-forget on a background virtual
     * thread unless force-sync (exit path) is active, in which case they fall
     * through to synchronous execution so they flush instead of being orphaned.
     */
    private HookExecution launch(HookCommand command, HookInput input,
                                 long defaultTimeoutMillis, String callbackToolUseId) {
        if (command instanceof BashCommandHook bashHook
                && (bashHook.async() || bashHook.asyncRewake()) && !bash.forceSync()) {
            bash.launchDetached(bashHook, input);
            return new HookExecution(command, HookResult.skip(), true, null);
        }
        var capture = new BashHookExecutor.OutputDrivenAsyncCapture();
        HookResult result = BashHookExecutor.capturing(capture,
            () -> runner.execute(command, input, defaultTimeoutMillis, callbackToolUseId));
        return new HookExecution(command, result, capture.backgrounded(), capture.initialOutput());
    }

    /** Returns the SessionEnd hook timeout in milliseconds. */
    static long getSessionEndHookTimeoutMillis() {
        String raw = SubprocessEnvironment.get("CLAUDE_CODE_SESSIONEND_HOOKS_TIMEOUT_MS");
        if (StringUtils.isNotBlank(raw)) {
            try {
                long ms = Long.parseLong(raw.trim());
                if (ms > 0) return ms;
            } catch (NumberFormatException _) {}
        }
        return SESSION_END_HOOK_TIMEOUT_MS_DEFAULT;
    }

    /**
     * Runs the event and aggregates into an outcome; any failure is logged and
     * fails open with {@link HookOutcome#PROCEED} so hooks never break the caller.
     */
    private HookOutcome guarded(HookEvent event, String subject, Supplier<HookInput> input) {
        try {
            return HookOutcomes.aggregate(executeHooks(event, input.get()));
        } catch (Throwable failure) {
            warnDispatchFailed(event, subject, failure);
            return HookOutcome.PROCEED;
        }
    }

    /** Runs the event for its side effects only; failures are logged and swallowed. */
    private void fireAndForget(HookEvent event, String subject, Supplier<HookInput> input) {
        try {
            executeHooks(event, input.get());
        } catch (Throwable failure) {
            warnDispatchFailed(event, subject, failure);
        }
    }

    private static void warnDispatchFailed(HookEvent event, String subject, Throwable failure) {
        if (subject == null) {
            LOG.warn("{} hook dispatch failed: {}", event.name(), failure.getMessage());
        } else {
            LOG.warn("{} hook dispatch failed for {}: {}", event.name(), subject, failure.getMessage());
        }
    }

    private String sessionId() {
        return context.sessionId();
    }

    private String cwd() {
        return context.cwd();
    }

    private String permissionMode() {
        return context.permissionMode();
    }

    // ---- tool lifecycle ----

    /**
     * Executes PreToolUse hooks and returns a permission decision modifier: the
     * first Block wins, otherwise Allow contexts are joined.
     */
    public HookResult executePreToolHooks(String toolName, JsonNode toolInput, String toolUseId) {
        HookInput input = HookInput.forPreToolUse(toolName, toolInput, toolUseId,
            sessionId(), cwd(), permissionMode());
        List<HookResult> results = executeHooks(HookEvent.PRE_TOOL_USE, input);

        for (HookResult result : results) {
            if (HookOutcomes.baseResult(result) instanceof HookResult.Block) {
                return result;
            }
        }

        StringBuilder additionalContext = new StringBuilder();
        for (HookResult result : results) {
            if (HookOutcomes.baseResult(result) instanceof HookResult.Allow(Optional<String> extra)
                && extra.isPresent()) {
                if (!additionalContext.isEmpty()) additionalContext.append("\n");
                additionalContext.append(extra.get());
            }
        }
        if (!additionalContext.isEmpty()) {
            return new HookResult.Allow(additionalContext.toString());
        }
        return HookResult.skip();
    }

    /** Executes PostToolUse hooks. */
    public List<HookResult> executePostToolHooks(
            String toolName, JsonNode toolInput, JsonNode toolOutput, String toolUseId) {
        return executeHooks(HookEvent.POST_TOOL_USE, HookInput.forPostToolUse(
            toolName, toolInput, toolOutput, toolUseId, sessionId(), cwd(), permissionMode()));
    }

    @Override
    public boolean dispatchPreToolUse(String toolName, JsonNode input, String toolUseId) {
        try {
            HookResult r = executePreToolHooks(toolName, input, toolUseId);
            return !(HookOutcomes.baseResult(r) instanceof HookResult.Block);
        } catch (Throwable t) {
            LOG.warn("PRE_TOOL_USE hook dispatch (block-check) failed for {}: {}", toolName, t.getMessage());
            return true; // fail open: never block tools because hooks crashed
        }
    }

    @Override
    public HookOutcome dispatchPreToolUseWithOutcome(String toolName, JsonNode input, String toolUseId) {
        try {
            HookOutcome outcome = HookOutcomes.aggregate(executeHooks(HookEvent.PRE_TOOL_USE,
                HookInput.forPreToolUse(toolName, input, toolUseId, sessionId(), cwd(), permissionMode())));
            if (outcome.hasBlockingErrors()) {
                List<String> errors = outcome.blockingErrors().stream()
                    .map(reason -> "PreToolUse:" + toolName + " hook error: " + reason)
                    .toList();
                return new HookOutcome(false, outcome.additionalContext(), errors,
                    outcome.preventContinuation(), outcome.stopReason(),
                    outcome.userDisplayMessage(), outcome.additionalContexts(),
                    outcome.specificOutputs());
            }
            return outcome;
        } catch (Throwable t) {
            LOG.warn("PRE_TOOL_USE hook dispatch (outcome) failed for {}: {}", toolName, t.getMessage());
            return HookOutcome.PROCEED;
        }
    }

    @Override
    public void dispatchPostToolUse(String toolName, JsonNode input, JsonNode output, String toolUseId) {
        dispatchPostToolUseWithOutcome(toolName, input, output, toolUseId);
    }

    @Override
    public HookOutcome dispatchPostToolUseWithOutcome(
            String toolName, JsonNode input, JsonNode output, String toolUseId) {
        try {
            return HookOutcomes.aggregate(executePostToolHooks(toolName, input, output, toolUseId));
        } catch (Throwable failure) {
            warnDispatchFailed(HookEvent.POST_TOOL_USE, toolName, failure);
            return HookOutcome.PROCEED;
        }
    }

    @Override
    public HookOutcome dispatchPostToolUseFailureWithOutcome(
            String toolName, JsonNode input, String toolUseId, String error, boolean isInterrupt) {
        return guarded(HookEvent.POST_TOOL_USE_FAILURE, toolName, () ->
            HookInput.forPostToolUseFailure(toolName, input, toolUseId, error, isInterrupt,
                sessionId(), cwd(), permissionMode()));
    }

    @Override
    public HookOutcome dispatchPostToolBatchWithOutcome(JsonNode toolCalls) {
        return guarded(HookEvent.POST_TOOL_BATCH, null, () ->
            HookInput.forPostToolBatch(toolCalls, sessionId(), cwd(), permissionMode()));
    }

    // ---- permissions & notifications ----

    @Override
    public HookOutcome dispatchPermissionRequestWithOutcome(
            String toolName, JsonNode input, String toolUseId) {
        return guarded(HookEvent.PERMISSION_REQUEST, toolName, () ->
            HookInput.forPermissionRequest(toolName, input, toolUseId,
                sessionId(), cwd(), permissionMode()));
    }

    @Override
    public HookOutcome dispatchPermissionDeniedWithOutcome(
            String toolName, JsonNode input, String toolUseId, String reason) {
        return guarded(HookEvent.PERMISSION_DENIED, toolName, () ->
            HookInput.forPermissionDenied(toolName, input, toolUseId, reason,
                sessionId(), cwd(), permissionMode()));
    }

    @Override
    public void dispatchNotification(String message, String title, String notificationType) {
        fireAndForget(HookEvent.NOTIFICATION, null, () ->
            HookInput.forNotification(message, title, notificationType, sessionId(), cwd()));
    }

    public HookOutcome dispatchElicitationWithOutcome(
            String serverName, String message, String mode, String url,
            String elicitationId, JsonNode requestedSchema) {
        return guarded(HookEvent.ELICITATION, serverName, () ->
            HookInput.forElicitation(serverName, message, mode, url, elicitationId,
                requestedSchema, sessionId(), cwd(), permissionMode()));
    }

    public HookOutcome dispatchElicitationResultWithOutcome(
            String serverName, String action, JsonNode content, String mode, String elicitationId) {
        return guarded(HookEvent.ELICITATION_RESULT, serverName, () ->
            HookInput.forElicitationResult(serverName, action, content, mode,
                elicitationId, sessionId(), cwd(), permissionMode()));
    }

    @Override
    public HookOutcome dispatchMessageDisplayWithOutcome(
            String turnId, String messageId, int index, boolean finalDelta, String delta) {
        return guarded(HookEvent.MESSAGE_DISPLAY, null, () ->
            HookInput.forMessageDisplay(turnId, messageId, index, finalDelta, delta, sessionId(), cwd()));
    }

    // ---- prompts ----

    @Override
    public void dispatchUserPromptSubmit(String prompt) {
        dispatchUserPromptSubmitWithOutcome(prompt);
    }

    @Override
    public HookOutcome dispatchUserPromptSubmitWithOutcome(String prompt) {
        return guarded(HookEvent.USER_PROMPT_SUBMIT, null, () ->
            HookInput.forUserPromptSubmit(prompt, sessionId(), cwd(), permissionMode(),
                context.promptId()));
    }

    @Override
    public HookOutcome dispatchUserPromptExpansionWithOutcome(
            String expansionType, String commandName, String commandArgs,
            String commandSource, String originalPrompt) {
        return guarded(HookEvent.USER_PROMPT_EXPANSION, commandName, () ->
            HookInput.forUserPromptExpansion(expansionType, commandName, commandArgs,
                commandSource, originalPrompt, sessionId(), cwd(), permissionMode()));
    }

    // ---- session lifecycle ----

    @Override
    public HookOutcome dispatchSetupWithOutcome(String trigger) {
        return guarded(HookEvent.SETUP, null, () -> HookInput.forSetup(trigger, sessionId(), cwd()));
    }

    @Override
    public void dispatchSessionStart(String trigger) {
        dispatchSessionStartWithOutcome(trigger);
    }

    @Override
    public HookOutcome dispatchSessionStartWithOutcome(String trigger) {
        return guarded(HookEvent.SESSION_START, null, () ->
            HookInput.forSessionStart(trigger, sessionId(), cwd()));
    }

    @Override
    public void dispatchSessionEnd(String reason) {
        fireAndForget(HookEvent.SESSION_END, null, () ->
            HookInput.forSessionEnd(reason, sessionId(), cwd()));
    }

    @Override
    public void dispatchCwdChanged(String oldCwd, String newCwd) {
        Thread.ofVirtual().name("cwd-changed-hook").start(() ->
            dispatchCwdChangedWithOutcome(oldCwd, newCwd));
    }

    @Override
    public HookOutcome dispatchCwdChangedWithOutcome(String oldCwd, String newCwd) {
        if (oldCwd == null || newCwd == null || oldCwd.equals(newCwd)) {
            return HookOutcome.PROCEED;
        }
        try {
            HookOutcome outcome = HookOutcomes.aggregate(executeHooks(
                HookEvent.CWD_CHANGED, HookInput.forCwdChanged(oldCwd, newCwd, sessionId())));
            effects.sink().cwdChanged(Path.of(oldCwd), Path.of(newCwd));
            return outcome;
        } catch (Throwable failure) {
            warnDispatchFailed(HookEvent.CWD_CHANGED, null, failure);
            return HookOutcome.PROCEED;
        }
    }

    @Override
    public HookOutcome dispatchFileChangedWithOutcome(String filePath, String fileEvent) {
        return guarded(HookEvent.FILE_CHANGED, null, () ->
            HookInput.forFileChanged(filePath, fileEvent, sessionId(), cwd()));
    }

    @Override
    public void dispatchInstructionsLoaded(String filePath, String memoryType,
                                           String loadReason, List<String> globs) {
        fireAndForget(HookEvent.INSTRUCTIONS_LOADED, filePath, () ->
            HookInput.forInstructionsLoaded(filePath, memoryType, loadReason, globs, sessionId(), cwd()));
    }

    public boolean dispatchConfigChange(String source, String filePath) {
        try {
            HookInput input = HookInput.forConfigChange(source, filePath, sessionId(), cwd());
            if (Strings.CS.equals("policy_settings", source)) {
                executeHooks(HookEvent.CONFIG_CHANGE, input);
                return false;
            }
            return executeHooks(HookEvent.CONFIG_CHANGE, input).stream()
                .anyMatch(HookResult.Block.class::isInstance);
        } catch (Throwable error) {
            warnDispatchFailed(HookEvent.CONFIG_CHANGE, source, error);
            return false;
        }
    }

    // ---- agents & teams ----

    @Override
    public HookOutcome dispatchSubAgentStartWithOutcome(String agentId, String agentType) {
        return guarded(HookEvent.SUBAGENT_START, agentType, () ->
            HookInput.forSubagentStart(agentId, agentType, sessionId(), cwd()));
    }

    @Override
    public HookOutcome dispatchTeammateIdleWithOutcome(String teammateName, String teamName) {
        return guarded(HookEvent.TEAMMATE_IDLE, null, () ->
            HookInput.forTeammateIdle(teammateName, teamName, sessionId(), cwd(), permissionMode()));
    }

    // ---- stop ----

    @Override
    public void dispatchStop(String reason) {
        dispatchStopWithOutcome(reason, false);
    }

    @Override
    public HookOutcome dispatchStopWithOutcome(String reason) {
        return dispatchStopWithOutcome(reason, false);
    }

    @Override
    public HookOutcome dispatchStopWithOutcome(String reason, boolean stopHookActive) {
        HookSessionContext.SubAgentScope scope = context.subAgent();
        if (scope != null) {
            return guarded(HookEvent.SUBAGENT_STOP, null, () ->
                HookInput.forSubagentStop(scope.agentId(), scope.agentTranscriptPath(),
                    scope.agentType(), stopHookActive, context.lastAssistantText(),
                    sessionId(), cwd(), scope.permissionMode(), context.promptId(), scope.effort()));
        }
        try {
            HookInput in = HookInput.forStop(stopHookActive, context.lastAssistantText(),
                sessionId(), cwd(), permissionMode(), context.promptId(),
                stopConditions.currentEffort());
            PromptHook currentGoal = goals.currentHook();
            boolean deferGoal = goals.shouldDefer(currentGoal);
            if (deferGoal) {
                LOG.debug("[goal] evaluation deferred — background work still running");
            }
            LOG.info("[goal-diag] dispatchStopWithOutcome reason={} goalPresent={} deferGoal={} stopHookActive={}",
                reason, currentGoal != null, deferGoal, stopHookActive);
            List<HookExecution> executions = executeHooksWithCommands(
                HookEvent.STOP, in, command -> !deferGoal || command != currentGoal);
            LOG.info("[goal-diag] STOP executions={} executedGoal={}",
                executions.size(),
                executions.stream().filter(e -> currentGoal != null && e.command() == currentGoal).count());
            goals.record(currentGoal, executions);
            HookOutcome outcome =
                HookOutcomes.aggregate(executions.stream().map(HookExecution::result).toList());
            LOG.info("[goal-diag] STOP outcome blockingErrors={} preventContinuation={}",
                outcome.blockingErrors().size(), outcome.preventContinuation());
            return outcome;
        } catch (Throwable t) {
            warnDispatchFailed(HookEvent.STOP, null, t);
            return HookOutcome.PROCEED;
        }
    }

    /** Fires the distinct {@code StopFailure} event. */
    @Override
    public void dispatchStopFailure(String reason) {
        fireAndForget(HookEvent.STOP_FAILURE, null, () ->
            HookInput.forStopFailure(reason, context.lastAssistantText(), sessionId(), cwd()));
    }

    // ---- compact ----

    @Override
    public void dispatchPreCompact(String trigger, String customInstructions, long preTokenCount) {
        dispatchPreCompactWithOutcome(trigger, customInstructions, preTokenCount);
    }

    @Override
    public HookOutcome dispatchPreCompactWithOutcome(
            String trigger, String customInstructions, long preTokenCount) {
        try {
            List<HookExecution> results = executeHooksWithCommands(HookEvent.PRE_COMPACT,
                HookInput.forPreCompact(trigger, customInstructions, preTokenCount,
                    sessionId(), cwd(), context.promptId()), _ -> true);
            return HookOutcomes.aggregateCompact("PreCompact", results, "\n\n");
        } catch (Throwable t) {
            warnDispatchFailed(HookEvent.PRE_COMPACT, null, t);
            return HookOutcome.PROCEED;
        }
    }

    @Override
    public void dispatchPostCompact(String trigger, String compactSummary, long postTokenCount) {
        dispatchPostCompactWithOutcome(trigger, compactSummary, postTokenCount);
    }

    /**
     * Fires PostCompact and aggregates hook-emitted {@code additionalContext} into the returned
     * outcome — the caller joins this with the PreCompact hook's own {@code additionalContext}
     * into the success message shown after {@code /compact} completes.
     */
    @Override
    public HookOutcome dispatchPostCompactWithOutcome(
            String trigger, String compactSummary, long postTokenCount) {
        try {
            List<HookExecution> results = executeHooksWithCommands(HookEvent.POST_COMPACT,
                HookInput.forPostCompact(trigger, compactSummary, postTokenCount, sessionId(), cwd()),
                _ -> true);
            return HookOutcomes.aggregateCompact("PostCompact", results, "\n");
        } catch (Throwable t) {
            warnDispatchFailed(HookEvent.POST_COMPACT, null, t);
            return HookOutcome.PROCEED;
        }
    }

    // ---- worktrees ----

    /** Whether any {@code WorktreeCreate} hook is configured. */
    public boolean hasWorktreeCreateHook() {
        return !registry.match(HookEvent.WORKTREE_CREATE,
            HookInput.forWorktreeCreate("", sessionId(), cwd())).isEmpty();
    }

    /**
     * Runs {@code WorktreeCreate} hooks and returns the first successful hook's stdout (the created
     * worktree path).
     */
    public Optional<String> dispatchWorktreeCreate(String name) {
        try {
            for (HookResult r : executeHooks(HookEvent.WORKTREE_CREATE,
                    HookInput.forWorktreeCreate(name, sessionId(), cwd()))) {
                r = HookOutcomes.baseResult(r);
                if (r instanceof HookResult.Allow(Optional<String> additionalContext)
                    && additionalContext.filter(s -> !StringUtils.isBlank(s)).isPresent()) {
                    return additionalContext.map(String::trim);
                }
                if (r instanceof HookResult.Structured(JsonNode output, _)) {
                    JsonNode path = output == null ? null : output.get("worktreePath");
                    if (path != null && path.isTextual()
                            && !StringUtils.isBlank(path.asText())
                            && Path.of(path.asText()).isAbsolute()) {
                        return Optional.of(path.asText().trim());
                    }
                }
            }
        } catch (Throwable t) {
            warnDispatchFailed(HookEvent.WORKTREE_CREATE, null, t);
        }
        return Optional.empty();
    }

    /** Runs {@code WorktreeRemove} hooks. */
    public boolean dispatchWorktreeRemove(String worktreePath) {
        try {
            List<HookResult> results = executeHooks(HookEvent.WORKTREE_REMOVE,
                HookInput.forWorktreeRemove(worktreePath, sessionId(), cwd()));
            return results.stream().anyMatch(r -> !(HookOutcomes.baseResult(r) instanceof HookResult.Skip));
        } catch (Throwable t) {
            warnDispatchFailed(HookEvent.WORKTREE_REMOVE, null, t);
            return false;
        }
    }

    // ---- tasks ----

    /** Whether any {@code TaskCreated} hook is configured. */
    public boolean hasTaskCreatedHook() {
        return !registry.match(HookEvent.TASK_CREATED,
            HookInput.forTaskCreated("", "", null, sessionId(), cwd(), permissionMode())).isEmpty();
    }

    public List<String> dispatchTaskCreated(String taskId, String subject, String description) {
        return dispatchTaskCreated(taskId, subject, description, null, null);
    }

    public List<String> dispatchTaskCreated(
            String taskId, String subject, String description,
            String teammateName, String teamName) {
        return blockFeedback(HookEvent.TASK_CREATED, "TaskCreated", () ->
            HookInput.forTaskCreated(taskId, subject, description, teammateName, teamName,
                sessionId(), cwd(), permissionMode()));
    }

    /** Whether any {@code TaskCompleted} hook is configured. */
    public boolean hasTaskCompletedHook() {
        return !registry.match(HookEvent.TASK_COMPLETED,
            HookInput.forTaskCompleted("", "", null, sessionId(), cwd(), permissionMode())).isEmpty();
    }

    public List<String> dispatchTaskCompleted(String taskId, String subject, String description) {
        return dispatchTaskCompleted(taskId, subject, description, null, null);
    }

    public List<String> dispatchTaskCompleted(
            String taskId, String subject, String description,
            String teammateName, String teamName) {
        return blockFeedback(HookEvent.TASK_COMPLETED, "TaskCompleted", () ->
            HookInput.forTaskCompleted(taskId, subject, description, teammateName, teamName,
                sessionId(), cwd(), permissionMode()));
    }

    @Override
    public HookOutcome dispatchTaskCompletedWithOutcome(
            String taskId, String subject, String description) {
        return guarded(HookEvent.TASK_COMPLETED, taskId, () ->
            HookInput.forTaskCompleted(taskId, subject, description, sessionId(), cwd(), permissionMode()));
    }

    /** Collects Block reasons as "{@code <Event> hook feedback:}" lines for task hooks. */
    private List<String> blockFeedback(HookEvent event, String eventLabel, Supplier<HookInput> input) {
        try {
            return executeHooks(event, input.get()).stream()
                .filter(HookResult.Block.class::isInstance)
                .map(r -> eventLabel + " hook feedback:\n" + ((HookResult.Block) r).reason())
                .toList();
        } catch (Throwable t) {
            warnDispatchFailed(event, null, t);
            return List.of();
        }
    }

    // ---- test seams (package-private) ----

    HookResult executeBashHook(BashCommandHook cmd, HookInput input, int defaultTimeoutSeconds) {
        return runner.execute(cmd, input, defaultTimeoutSeconds * 1000L, null);
    }

    HookResult executePromptHook(PromptHook cmd, HookInput input, int defaultTimeoutSeconds) {
        return runner.execute(cmd, input, defaultTimeoutSeconds * 1000L, null);
    }

    HookResult executeHttpHook(HttpHook cmd, HookInput input, int defaultTimeoutSeconds) {
        return runner.execute(cmd, input, defaultTimeoutSeconds * 1000L, null);
    }

    HookResult executeAgentHook(AgentHook cmd, HookInput input, int defaultTimeoutSeconds) {
        return runner.execute(cmd, input, defaultTimeoutSeconds * 1000L, null);
    }

    HookResult parseHookOutput(String output) {
        return new HookOutputParser().parse(output);
    }
}
