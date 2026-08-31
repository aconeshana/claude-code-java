package com.claudecode.services.hooks;

import com.claudecode.api.ApiException;
import com.claudecode.api.ApiProviderResolver;
import com.claudecode.api.CreateMessageRequest;
import com.claudecode.api.LlmClient;
import com.claudecode.api.PromptTooLongException;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ApiMessageFormatter;
import com.claudecode.core.engine.AsyncHookResponse;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.engine.HookEffectSink;
import com.claudecode.core.engine.RequestMessageNormalizer;
import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.SubAgentLifecycleListener;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.GoalStatusAttachment;
import com.claudecode.core.message.HookNonBlockingErrorAttachment;
import com.claudecode.core.message.HookSystemMessageAttachment;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.TokenEstimator;
import com.claudecode.core.message.Usage;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.queue.MessageQueueManager;
import com.claudecode.core.queue.QueuePriority;
import com.claudecode.core.queue.QueuedCommand;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.http.HttpCalls;
import com.claudecode.permissions.PermissionMode;
import com.claudecode.services.http.ServiceHttpClient;
import com.claudecode.services.model.GoalContextWindowPolicy;
import com.claudecode.services.model.ModelOutputTokens;
import com.claudecode.services.model.SideQuery;
import com.claudecode.services.process.PlatformShellCommand;
import com.claudecode.tools.agent.SubAgentFactory;
import com.claudecode.tools.agent.SubAgentRequest;
import com.claudecode.tools.agent.SubAgentResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Core hook execution engine.
 */
public class HookEngine implements HookDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(HookEngine.class);
    private static final double GOAL_TRANSCRIPT_FRACTION = 0.5;
    private static final long DEFAULT_GOAL_CONTEXT_WINDOW = 200_000L;

    /**
     * Default timeout for normal tool hooks.
     */
    private static final int TOOL_HOOK_TIMEOUT_SECONDS = 600;  // 10 minutes

    private static final String STOP_CONDITION_SYSTEM_PROMPT = """
        You are evaluating a stop-condition hook in Claude Code. Read the conversation transcript carefully, then judge whether the user-provided condition is satisfied.

        Your response must be a JSON object with one of these shapes:
        - {"ok": true, "reason": "<quote evidence from the transcript that satisfies the condition>"}
        - {"ok": false, "reason": "<quote what is missing or what blocks the condition>"}
        - {"ok": false, "impossible": true, "reason": "<explain why the condition can never be satisfied>"}

        Always include a "reason" field, quoting specific text from the transcript whenever possible. If the transcript does not contain clear evidence that the condition is satisfied, return {"ok": false, "reason": "insufficient evidence in transcript"}.

        Only use {"ok": false, "impossible": true} when the condition is genuinely unachievable in this session — for example: the condition is self-contradictory, it depends on a resource or capability that is unavailable, or the assistant has explicitly tried, exhausted reasonable approaches, and stated it cannot be done. Apply your own judgment when deciding this — the assistant claiming the goal is impossible is evidence, not proof; independently confirm the condition is genuinely unachievable rather than deferring to the assistant's self-assessment. Do not use it just because the goal has not been reached yet or because progress is slow. When in doubt, return {"ok": false} without "impossible".\
        """;

    /**
     * When true, async hooks (both config-declared and output-driven) are NOT backgrounded — they run
     * synchronously so the caller blocks until completion.
     */
    private volatile boolean forceSyncExecution = false;

    /**
     * Per-engine registry of in-flight output-driven and config-async hooks.
     * Completed responses are polled by {@link #checkForAsyncHookResponses}
     * and re-injected as attachments on a later turn.
     */
    private final AsyncHookRegistry asyncHookRegistry = new AsyncHookRegistry();

    /** Stateless resolver for source snapshots and command {@code if} rules. */
    private final HookMatchResolver hookMatchResolver = new HookMatchResolver();

    /** Stateless generic and strict Prompt/Agent hook output parser. */
    private final HookOutputParser hookOutputParser = new HookOutputParser();

    /**
     * Per-dispatch capture that lets the compact outcome aggregator distinguish
     * an output-driven async handoff from an ordinary no-output Skip without
     * changing the public HookResult contract used by direct callers.
     */
    private static final ScopedValue<OutputDrivenAsyncCapture> OUTPUT_DRIVEN_ASYNC_CAPTURE =
        ScopedValue.newInstance();

    private static final class OutputDrivenAsyncCapture {
        private boolean backgrounded;
        private String initialOutput;

        void background(String output) {
            backgrounded = true;
            initialOutput = output;
        }
    }

    @Override
    public void setForceSyncExecution(boolean value) {
        this.forceSyncExecution = value;
    }

    /**
     * Default timeout for Stop (SessionEnd) hooks — kept tight because they run during shutdown.
     */
    private static final int SESSION_END_HOOK_TIMEOUT_MS_DEFAULT = 1500;

    /**
     * Base settings loaded from  (and its
     * project / local siblings). Marked {@code volatile} so a hot-reload
     * running on the watcher thread is safely visible to hook-matching reads
     * on the query thread. Mutated only via {@link #replaceSettings(HooksSettings)}.
     */
    private volatile HooksSettings settings;
    /** Effective global HTTP-hook URL/env policy; atomically replaced on settings reload. */
    private volatile HttpHookPolicy httpHookPolicy = HttpHookPolicy.unrestricted();
    /**
     * When non-null, hooks always run in (and report) this fixed directory — used by tests that pin a
     * temp dir.
     */
    private final String fixedWorkingDirectory;
    private final OkHttpClient httpClient;
    private final boolean managedHttpClient;
    private Supplier<Map<String, String>> sandboxProxyEnvironmentSupplier = Map::of;
    /**
     * Shared model-call abstraction. {@link #setLlmClient} constructs one
     * internally so legacy callers stay source-compatible; new callers should
     * inject a pre-built {@link SideQuery} via {@link #setSideQuery} so all
     * side-query features (rename, permission explainer, session search,
     * prompt/agent hooks) share the same client instance.
     */
    private SideQuery sideQuery;
    private String llmModel;
    private Supplier<String> llmModelSupplier = () -> llmModel;
    private Supplier<String> goalSystemPromptIdentitySupplier = () -> "";
    private Supplier<JsonNode> goalMetadataSupplier = () -> null;
    private Supplier<String> goalEffortSupplier = () -> null;
    private Supplier<List<CreateMessageRequest.ToolDefinition>> goalToolsSupplier = List::of;
    private SubAgentFactory agentHookFactory;

    /**
     * Shared session-id holder — included in every hook input as
     * {@code session_id}. Composition roots that also construct a
     * {@link com.claudecode.runtime.query.QuerySession} for the same session
     * should pass its {@link com.claudecode.runtime.query.QuerySession#sessionIdentity()}
     * here so a single {@code switchToSession} call is visible to both without a manual
     * {@code setSessionId} sync step.
     */
    private final SessionIdentity sessionIdentity;

    /** Current permission mode — included in tool hook inputs as {@code permission_mode}. */
    private String permissionMode;
    private Supplier<String> permissionModeSupplier = () -> permissionMode;


    private Supplier<List<Message>> messagesSupplier;

    /** Current SDK/headless queue-turn prompt id, included in PreCompact input. */
    private Supplier<String> promptIdSupplier = () -> null;

    /**
     * Optional sink for model-waking notifications.
     */
    private MessageQueueManager messageQueue;

    /** Tracks hooks marked as "once" that have already executed. */
    private final Set<String> executedOnceHooks = ConcurrentHashMap.newKeySet();

    /**
     * Per-invocation extra hooks registered by skills via their frontmatter.
     */
    private final ConcurrentHashMap<HookEvent, List<HookMatcher>> extraHooks = new ConcurrentHashMap<>();
    /** Session hooks survive turn-end cleanup; /goal owns one Stop prompt hook here. */
    private final ConcurrentHashMap<HookEvent, List<HookMatcher>> sessionHooks = new ConcurrentHashMap<>();
    private final Object goalLock = new Object();
    private volatile PromptHook goalPromptHook;
    private volatile HookDispatcher.ActiveGoal activeGoal;
    private final AtomicReference<HookDispatcher.GoalTransition> goalTransition = new AtomicReference<>();
    private final ConcurrentLinkedQueue<Message> hookMessages = new ConcurrentLinkedQueue<>();
    private LongSupplier tokenCountSupplier = () -> 0L;
    private BooleanSupplier backgroundTasksRunningSupplier = () -> false;
    private ToLongFunction<String> goalContextWindowResolver =
        HookEngine::defaultGoalContextWindow;
    /**
     * Skill root associated with each per-invocation hook command. Identity
     * semantics avoid accidentally applying a skill root to an equal-looking
     * hook from base settings or another skill.
     */
    private final Map<HookCommand, Path> extraHookRoots =
        Collections.synchronizedMap(new IdentityHashMap<>());

    /**
     * Hooks contributed by enabled plugins — an independent channel from the settings-file hooks
     * ({@link #settings}) so a settings hot-reload never wipes plugin hooks and a plugin reload never
     * touches user settings.
     */
    private volatile Map<HookEvent, List<HookMatcher>> pluginHooks = Map.of();
    private volatile Map<HookEvent, List<HookMatcher>> sdkHooks = Map.of();
    private volatile HookEffectSink hookEffectSink = HookEffectSink.NOOP;

    /** Non-null only on a dispatcher cloned for one child agent invocation. */
    private SubAgentScope subAgentScope;

    private record SubAgentScope(
        String agentId,
        String agentType,
        String agentTranscriptPath,
        String permissionMode,
        String effort
    ) {}

    /** Atomically replaces callbacks supplied by SDK initialize.hooks. */
    public void setSdkHooks(Map<HookEvent, List<HookMatcher>> hooks) {
        this.sdkHooks = hooks == null ? Map.of() : Map.copyOf(hooks);
    }

    /** Installs the application-owned consumer for user/terminal/session effects. */
    public void setHookEffectSink(HookEffectSink sink) {
        this.hookEffectSink = sink == null ? HookEffectSink.NOOP : sink;
    }

    public Map<HookEvent, List<HookMatcher>> currentSdkHooks() {
        return sdkHooks;
    }

    /** Static FileChanged matcher expressions in normal hook source order. */
    public List<String> configuredFileChangedMatchers() {
        List<String> result = new ArrayList<>();
        for (List<HookMatcher> layer : List.of(
                settings.getMatchers(HookEvent.FILE_CHANGED),
                pluginHooks.getOrDefault(HookEvent.FILE_CHANGED, List.of()),
                sdkHooks.getOrDefault(HookEvent.FILE_CHANGED, List.of()),
                extraHooks.getOrDefault(HookEvent.FILE_CHANGED, List.of()),
                sessionHooks.getOrDefault(HookEvent.FILE_CHANGED, List.of()))) {
            for (HookMatcher matcher : layer) {
                matcher.matcher().filter(StringUtils::isNotBlank).ifPresent(result::add);
            }
        }
        return List.copyOf(result);
    }

    /** Installs the real sub-agent runtime used by {@code type:"agent"} hooks. */
    public void setAgentHookFactory(SubAgentFactory factory) {
        this.agentHookFactory = factory;
    }

    /**
     * Creates an isolated hook engine for one child invocation.
     */
    public HookDispatcher createSubAgentDispatcher(
            SubAgentLifecycleListener.SubAgentHookContext context) {
        HookEngine child = new HookEngine(settings, context.workingDirectory(),
            httpClient, sessionIdentity);
        child.httpHookPolicy = httpHookPolicy;
        child.sandboxProxyEnvironmentSupplier = sandboxProxyEnvironmentSupplier;
        child.sideQuery = sideQuery;
        child.llmModel = llmModel;
        child.llmModelSupplier = llmModelSupplier;
        child.goalSystemPromptIdentitySupplier = goalSystemPromptIdentitySupplier;
        child.goalMetadataSupplier = goalMetadataSupplier;
        child.goalEffortSupplier = context::effort;
        child.goalToolsSupplier = goalToolsSupplier;
        child.agentHookFactory = agentHookFactory;
        child.permissionMode = context.permissionMode();
        child.permissionModeSupplier = context::permissionMode;
        child.messagesSupplier = context.messagesSupplier();
        child.promptIdSupplier = context.promptIdSupplier();
        child.messageQueue = messageQueue;
        child.pluginHooks = pluginHooks;
        child.sdkHooks = sdkHooks;
        child.subAgentScope = new SubAgentScope(
            context.agentId(), context.agentType(), context.agentTranscriptPath(),
            context.permissionMode(), context.effort());

        HooksSettings frontmatter = HooksSettings.fromJson(context.frontmatterHooks());
        frontmatter.eventHooks().forEach((event, matchers) -> {
            HookEvent scopedEvent = event == HookEvent.STOP
                ? HookEvent.SUBAGENT_STOP : event;
            child.sessionHooks.merge(scopedEvent, List.copyOf(matchers), (left, right) -> {
                List<HookMatcher> merged = new ArrayList<>(left);
                merged.addAll(right);
                return List.copyOf(merged);
            });
        });
        return child;
    }

    public HookEngine(HooksSettings settings, String workingDirectory) {
        this(settings, workingDirectory, SessionIdentity.newRandom());
    }

    public HookEngine(HooksSettings settings, String workingDirectory, SessionIdentity sessionIdentity) {
        this(settings, workingDirectory,
            ServiceHttpClient.noRedirects(),
            sessionIdentity, true);
    }

    public HookEngine(HooksSettings settings, String workingDirectory, OkHttpClient httpClient) {
        this(settings, workingDirectory, httpClient, SessionIdentity.newRandom());
    }

    public HookEngine(HooksSettings settings, String workingDirectory, OkHttpClient httpClient,
                       SessionIdentity sessionIdentity) {
        this(settings, workingDirectory, httpClient, sessionIdentity, false);
    }

    private HookEngine(HooksSettings settings, String workingDirectory, OkHttpClient httpClient,
                       SessionIdentity sessionIdentity, boolean managedHttpClient) {
        this.settings = settings != null ? settings : HooksSettings.EMPTY;
        // Store as-is (nullable): null means "follow the live cwd" (production),
        // non-null pins a fixed dir (tests). See fixedWorkingDirectory Javadoc.
        this.fixedWorkingDirectory = workingDirectory;
        this.httpClient = httpClient;
        this.managedHttpClient = managedHttpClient;
        this.sessionIdentity = sessionIdentity != null ? sessionIdentity : SessionIdentity.newRandom();
    }

    /**
     * Supplies the sandbox proxy environment lazily so the proxy starts only
     * when an HTTP hook actually runs.
     */
    public void setSandboxProxyEnvironmentSupplier(
            Supplier<Map<String, String>> sandboxProxyEnvironmentSupplier) {
        this.sandboxProxyEnvironmentSupplier = sandboxProxyEnvironmentSupplier != null
            ? sandboxProxyEnvironmentSupplier : Map::of;
    }

    /**
     * The directory hooks run in and report in their {@code cwd} JSON field.
     */
    private String resolveCwd() {
        return fixedWorkingDirectory != null ? fixedWorkingDirectory : System.getProperty("user.dir");
    }

    /**
     * Sets the LLM client for PromptHook and AgentHook execution.
     */
    public void setLlmClient(LlmClient llmClient) {
        this.sideQuery = llmClient != null ? new SideQuery(llmClient) : null;
    }

    /**
     * Preferred injection point — wires the shared {@link SideQuery} so
     * hooks share the same side-query pipeline as rename / permission
     * explainer.
     */
    public void setSideQuery(SideQuery sideQuery) {
        this.sideQuery = sideQuery;
    }

    /**
     * Sets the default LLM model for hook execution.
     */
    public void setLlmModel(String model) {
        this.llmModel = model;
    }

    /** Live model source so /model changes also affect later prompt hooks. */
    public void setLlmModelSupplier(Supplier<String> supplier) {
        this.llmModelSupplier = supplier != null ? supplier : () -> llmModel;
    }

    


    public void setGoalSystemPromptIdentitySupplier(Supplier<String> supplier) {
        this.goalSystemPromptIdentitySupplier = supplier != null ? supplier : () -> "";
    }

    /** Messages API metadata shared with the main session request. */
    public void setGoalMetadataSupplier(Supplier<JsonNode> supplier) {
        this.goalMetadataSupplier = supplier != null ? supplier : () -> null;
    }

    /** Resolved request effort used both on the wire and in Stop-hook ARGUMENTS. */
    public void setGoalEffortSupplier(Supplier<String> supplier) {
        this.goalEffortSupplier = supplier != null ? supplier : () -> null;
    }

    


    public void setGoalToolsSupplier(
            Supplier<List<CreateMessageRequest.ToolDefinition>> supplier) {
        this.goalToolsSupplier = supplier != null ? supplier : List::of;
    }

    /** Sets the permission mode included in tool hook inputs as {@code permission_mode}. */
    public void setPermissionMode(String permissionMode) {
        this.permissionMode = permissionMode;
    }

    /** Live permission-mode source so UI/control-channel changes reach hook input. */
    public void setPermissionModeSupplier(Supplier<String> supplier) {
        this.permissionModeSupplier = supplier != null ? supplier : () -> permissionMode;
    }

    private String currentPermissionMode() {
        try {
            return permissionModeSupplier.get();
        } catch (RuntimeException _) {
            return permissionMode;
        }
    }

    private String currentLlmModel() {
        try {
            String model = llmModelSupplier.get();
            return StringUtils.isNotBlank(model) ? model : llmModel;
        } catch (RuntimeException _) {
            return llmModel;
        }
    }

    private String currentGoalEffort() {
        try {
            String effort = goalEffortSupplier.get();
            if (StringUtils.isNotBlank(effort)) return effort;
            String model = currentLlmModel();
            return model != null && Strings.CI.contains(model, "claude-") ? "high" : null;
        } catch (RuntimeException _) {
            return null;
        }
    }

    private String currentGoalIdentity() {
        try {
            String identity = goalSystemPromptIdentitySupplier.get();
            return identity == null ? "" : identity;
        } catch (RuntimeException _) {
            return "";
        }
    }

    private JsonNode currentGoalMetadata() {
        try {
            return goalMetadataSupplier.get();
        } catch (RuntimeException _) {
            return null;
        }
    }

    private List<CreateMessageRequest.ToolDefinition> currentGoalTools() {
        try {
            List<CreateMessageRequest.ToolDefinition> tools = goalToolsSupplier.get();
            return tools == null ? List.of() : List.copyOf(tools);
        } catch (RuntimeException _) {
            return List.of();
        }
    }

    /**
     * Wires the live conversation view so Stop / StopFailure hook inputs can
     * carry {@code last_assistant_message}. Typically
     * {@code queryEngine::getMessages}.
     */
    public void setMessagesSupplier(Supplier<List<Message>> supplier) {
        this.messagesSupplier = supplier;
    }

    /**
     * Wires the active queue turn's prompt id into.
     */
    public void setPromptIdSupplier(Supplier<String> supplier) {
        this.promptIdSupplier = supplier != null ? supplier : () -> null;
    }

    /** Token counter used for the final goal statistics. */
    public void setTokenCountSupplier(LongSupplier supplier) {
        this.tokenCountSupplier = supplier != null ? supplier : () -> 0L;
    }

    /** Defers goal evaluation while a shell/agent background task is active. */
    public void setBackgroundTasksRunningSupplier(BooleanSupplier supplier) {
        this.backgroundTasksRunningSupplier = supplier != null ? supplier : () -> false;
    }

    @Override
    public boolean setGoal(String condition, long tokensAtStart) {
        if (StringUtils.isBlank(condition)) return false;
        synchronized (goalLock) {
            removeGoalHookLocked();
            PromptHook hook = new PromptHook(condition);
            goalPromptHook = hook;
            sessionHooks.put(HookEvent.STOP, List.of(
                new HookMatcher(Optional.of(""), List.of(hook))));
            activeGoal = new HookDispatcher.ActiveGoal(
                condition, 0, System.currentTimeMillis(), tokensAtStart, null);
            goalTransition.set(null);
            return true;
        }
    }

    @Override
    public String clearGoal() {
        synchronized (goalLock) {
            String condition = activeGoal != null ? activeGoal.condition()
                : goalPromptHook != null ? goalPromptHook.prompt() : null;
            removeGoalHookLocked();
            activeGoal = null;
            goalTransition.set(null);
            return condition;
        }
    }

    @Override
    public Optional<HookDispatcher.ActiveGoal> activeGoal() {
        return Optional.ofNullable(activeGoal);
    }

    @Override
    public Optional<HookDispatcher.GoalTransition> consumeGoalTransition() {
        return Optional.ofNullable(goalTransition.getAndSet(null));
    }

    @Override
    public List<Message> consumeHookMessages() {
        List<Message> messages = new ArrayList<>();
        Message message;
        while ((message = hookMessages.poll()) != null) messages.add(message);
        return List.copyOf(messages);
    }

    /** Resolves the model's effective context window for Goal transcript budgeting. */
    public void setGoalContextWindowResolver(ToLongFunction<String> resolver) {
        this.goalContextWindowResolver = resolver != null
            ? resolver : HookEngine::defaultGoalContextWindow;
    }

    private static long defaultGoalContextWindow(String model) {
        return GoalContextWindowPolicy.contextWindow(model, ApiProviderResolver.resolve(),
            SubprocessEnvironment.get("ANTHROPIC_BASE_URL"));
    }

    @Override
    public void restoreGoalFromTranscript(List<Message> messages, long tokensAtStart) {
        String condition = findGoalToRestore(messages);
        if (condition == null) clearGoal();
        else setGoal(condition, tokensAtStart);
    }

    static String findGoalToRestore(List<Message> messages) {
        if (messages == null) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (!(message instanceof AttachmentMessage attachment)
                    || !(attachment.payload() instanceof GoalStatusAttachment goal)) {
                continue;
            }
            return goal.met() || goal.hasFailedMarker() ? null : goal.condition();
        }
        return null;
    }

    private void removeGoalHookLocked() {
        sessionHooks.remove(HookEvent.STOP);
        goalPromptHook = null;
    }

    /**
     * Wires the per-session message queue so {@code asyncRewake} blocking errors (exit code 2) can wake
     * the model via {@code enqueuePendingNotification}.
     */
    public void setMessageQueue(MessageQueueManager messageQueue) {
        this.messageQueue = messageQueue;
    }

    /**
     * Text of the last assistant message, or {@code null} when unavailable.
     */
    private String lastAssistantText() {
        try {
            if (messagesSupplier == null) return null;
            List<Message> messages = messagesSupplier.get();
            if (messages == null) return null;
            var last = MessageConstants.getLastAssistantMessage(messages);
            if (last == null || last.message() == null || last.message().content() == null) return null;
            String text = MessageConstants.extractTextContent(last.message().content(), "\n").trim();
            return text.isEmpty() ? null : text;
        } catch (Throwable t) {
            LOG.debug("last_assistant_message extraction failed: {}", t.getMessage());
            return null;
        }
    }

    /**
     * Merges additional hooks from a skill's frontmatter into the per-turn extra-hooks layer.
     */
    public void addExtraHooks(HooksSettings extra) {
        addExtraHooks(extra, null);
    }

    /**
     * Registers skill hooks together with the directory exposed to their subprocesses as {@code
     * CLAUDE_PLUGIN_ROOT}.
     */
    public void addExtraHooks(HooksSettings extra, Path skillRoot) {
        if (extra == null) return;
        for (Map.Entry<HookEvent, List<HookMatcher>> entry : extra.eventHooks().entrySet()) {
            if (skillRoot != null) {
                for (HookMatcher matcher : entry.getValue()) {
                    for (HookCommand command : matcher.hooks()) {
                        extraHookRoots.put(command, skillRoot);
                    }
                }
            }
            extraHooks.merge(entry.getKey(), entry.getValue(), (existing, added) -> {
                List<HookMatcher> merged = new ArrayList<>(existing);
                merged.addAll(added);
                return List.copyOf(merged);
            });
        }
    }

    @Override
    public void installInvocationHooks(HookDispatcher.InvocationHooks hooks, Path sourceRoot) {
        if (hooks instanceof HooksSettings invocationSettings) {
            addExtraHooks(invocationSettings, sourceRoot);
        }
    }

    /**
     * Removes all per-turn extra hooks registered by skills.
     * Called at turn-complete to prevent cross-turn hook leakage.
     */
    public void clearExtraHooks() {
        extraHooks.clear();
        extraHookRoots.clear();
    }

    @Override
    public void clearInvocationHooks() {
        clearExtraHooks();
    }

    /**
     * Snapshot of all in-memory session hooks shown by {@code /hooks}: invoked skill/frontmatter hooks
     * plus session-persistent hooks such as /goal.
     */
    public Map<HookEvent, List<HookMatcher>> currentSessionHooks() {
        EnumMap<HookEvent, List<HookMatcher>> snapshot = new EnumMap<>(HookEvent.class);
        extraHooks.forEach((event, matchers) ->
            snapshot.put(event, List.copyOf(matchers)));
        sessionHooks.forEach((event, matchers) ->
            snapshot.merge(event, List.copyOf(matchers), (left, right) -> {
                List<HookMatcher> merged = new ArrayList<>(left);
                merged.addAll(right);
                return List.copyOf(merged);
            }));
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * Replaces the base settings (from and its project / local siblings).
     */
    public void replaceSettings(HooksSettings newSettings) {
        this.settings = newSettings != null ? newSettings : HooksSettings.EMPTY;
    }

    /** Replaces the effective global HTTP-hook policy without touching hook definitions. */
    public void replaceHttpHookPolicy(HttpHookPolicy policy) {
        this.httpHookPolicy = policy != null ? policy : HttpHookPolicy.unrestricted();
    }

    /** Returns the currently active base settings. Public for hot-reload/config UI. */
    public HooksSettings currentSettings() {
        return this.settings;
    }

    /**
     * Replaces the plugin-contributed hooks in one atomic swap.
     */
    public void setPluginHooks(Map<HookEvent, List<HookMatcher>> hooks) {
        this.pluginHooks = hooks == null ? Map.of() : Map.copyOf(hooks);
    }

    /** Currently registered plugin hooks (read-only view). */
    public Map<HookEvent, List<HookMatcher>> currentPluginHooks() {
        EnumMap<HookEvent, List<HookMatcher>> snapshot = new EnumMap<>(HookEvent.class);
        pluginHooks.forEach((event, matchers) ->
            snapshot.put(event, List.copyOf(matchers)));
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * Executes all matching hooks for the given event in parallel.
     */
    public List<HookResult> executeHooks(HookEvent event, HookInput input) {
        return executeHooksWithCommands(event, input).stream()
            .map(HookExecution::result)
            .toList();
    }


    public boolean dispatchConfigChange(String source, String filePath) {
        try {
            HookInput input = HookInput.forConfigChange(source, filePath,
                sessionIdentity.get(), resolveCwd());
            if (Strings.CS.equals("policy_settings", source)) {
                executeHooks(HookEvent.CONFIG_CHANGE, input);
                return false;
            }
            return executeHooks(HookEvent.CONFIG_CHANGE, input).stream()
                .anyMatch(HookResult.Block.class::isInstance);
        } catch (Throwable error) {
            LOG.warn("CONFIG_CHANGE hook dispatch failed for {}: {}", source, error.getMessage());
            return false;
        }
    }

    @Override
    public HookDispatcher.HookOutcome dispatchPermissionRequestWithOutcome(
            String toolName, JsonNode input, String toolUseId) {
        try {
            return aggregateOutcome(executeHooks(HookEvent.PERMISSION_REQUEST,
                HookInput.forPermissionRequest(toolName, input, toolUseId,
                    sessionIdentity.get(), resolveCwd(), currentPermissionMode())));
        } catch (Throwable failure) {
            LOG.warn("PERMISSION_REQUEST hook dispatch failed for {}: {}",
                toolName, failure.getMessage());
            return HookDispatcher.HookOutcome.PROCEED;
        }
    }

    @Override
    public HookDispatcher.HookOutcome dispatchPermissionDeniedWithOutcome(
            String toolName, JsonNode input, String toolUseId, String reason) {
        try {
            return aggregateOutcome(executeHooks(HookEvent.PERMISSION_DENIED,
                HookInput.forPermissionDenied(toolName, input, toolUseId, reason,
                    sessionIdentity.get(), resolveCwd(), currentPermissionMode())));
        } catch (Throwable failure) {
            LOG.warn("PERMISSION_DENIED hook dispatch failed for {}: {}",
                toolName, failure.getMessage());
            return HookDispatcher.HookOutcome.PROCEED;
        }
    }

    @Override
    public void dispatchNotification(String message, String title, String notificationType) {
        try {
            executeHooks(HookEvent.NOTIFICATION, HookInput.forNotification(
                message, title, notificationType, sessionIdentity.get(), resolveCwd()));
        } catch (Throwable failure) {
            LOG.warn("NOTIFICATION hook dispatch failed: {}", failure.getMessage());
        }
    }

    @Override
    public HookDispatcher.HookOutcome dispatchSetupWithOutcome(String trigger) {
        try {
            return aggregateOutcome(executeHooks(HookEvent.SETUP,
                HookInput.forSetup(trigger, sessionIdentity.get(), resolveCwd())));
        } catch (Throwable failure) {
            LOG.warn("SETUP hook dispatch failed: {}", failure.getMessage());
            return HookDispatcher.HookOutcome.PROCEED;
        }
    }

    @Override
    public HookDispatcher.HookOutcome dispatchTeammateIdleWithOutcome(
            String teammateName, String teamName) {
        try {
            return aggregateOutcome(executeHooks(HookEvent.TEAMMATE_IDLE,
                HookInput.forTeammateIdle(teammateName, teamName,
                    sessionIdentity.get(), resolveCwd(), currentPermissionMode())));
        } catch (Throwable failure) {
            LOG.warn("TEAMMATE_IDLE hook dispatch failed: {}", failure.getMessage());
            return HookDispatcher.HookOutcome.PROCEED;
        }
    }

    @Override
    public HookDispatcher.HookOutcome dispatchFileChangedWithOutcome(
            String filePath, String fileEvent) {
        try {
            return aggregateOutcome(executeHooks(HookEvent.FILE_CHANGED,
                HookInput.forFileChanged(filePath, fileEvent,
                    sessionIdentity.get(), resolveCwd())));
        } catch (Throwable failure) {
            LOG.warn("FILE_CHANGED hook dispatch failed: {}", failure.getMessage());
            return HookDispatcher.HookOutcome.PROCEED;
        }
    }

    public HookDispatcher.HookOutcome dispatchElicitationWithOutcome(
            String serverName, String message, String mode, String url,
            String elicitationId, JsonNode requestedSchema) {
        try {
            return aggregateOutcome(executeHooks(HookEvent.ELICITATION,
                HookInput.forElicitation(serverName, message, mode, url, elicitationId,
                    requestedSchema, sessionIdentity.get(), resolveCwd(), currentPermissionMode())));
        } catch (Throwable failure) {
            LOG.warn("ELICITATION hook dispatch failed for {}: {}",
                serverName, failure.getMessage());
            return HookDispatcher.HookOutcome.PROCEED;
        }
    }

    public HookDispatcher.HookOutcome dispatchElicitationResultWithOutcome(
            String serverName, String action, JsonNode content, String mode,
            String elicitationId) {
        try {
            return aggregateOutcome(executeHooks(HookEvent.ELICITATION_RESULT,
                HookInput.forElicitationResult(serverName, action, content, mode,
                    elicitationId, sessionIdentity.get(), resolveCwd(), currentPermissionMode())));
        } catch (Throwable failure) {
            LOG.warn("ELICITATION_RESULT hook dispatch failed for {}: {}",
                serverName, failure.getMessage());
            return HookDispatcher.HookOutcome.PROCEED;
        }
    }

    @Override
    public HookDispatcher.HookOutcome dispatchMessageDisplayWithOutcome(
            String turnId, String messageId, int index, boolean finalDelta, String delta) {
        try {
            return aggregateOutcome(executeHooks(HookEvent.MESSAGE_DISPLAY,
                HookInput.forMessageDisplay(turnId, messageId, index, finalDelta, delta,
                    sessionIdentity.get(), resolveCwd())));
        } catch (Throwable failure) {
            LOG.warn("MESSAGE_DISPLAY hook dispatch failed: {}", failure.getMessage());
            return HookDispatcher.HookOutcome.PROCEED;
        }
    }


    private List<HookExecution> executeHooksWithCommands(HookEvent event, HookInput input) {
        return executeHooksWithCommands(event, input, _ -> true);
    }

    private List<HookExecution> executeHooksWithCommands(HookEvent event, HookInput input,
                                                          Predicate<HookCommand> include) {
        List<HookMatchResolver.MatchedHook> eligible = getMatchingHooks(event, input).stream()
            .filter(hook -> include.test(hook.command()))
            .filter(hook -> hookMatchResolver.matchesIfCondition(hook.command(), input))
            .filter(hook -> !hook.command().once() || executedOnceHooks.add(hookIdentity(hook)))
            .toList();

        if (eligible.isEmpty()) {
            return List.of();
        }

        // Per-event default timeout: only SessionEnd hooks get a tight bound
        // (1.5 s by default) because they run during shutdown; everything else

        // TOOL_HOOK_EXECUTION_TIMEOUT_MS — gets 10 minutes.
        long defaultTimeoutMillis = event == HookEvent.SESSION_END
            ? getSessionEndHookTimeoutMillis()
            : TOOL_HOOK_TIMEOUT_SECONDS * 1000L;

        // hooks reuse the model's id; lifecycle callbacks receive a random UUID
        // that belongs only to the SDK control envelope, not HookInput JSON.
        String callbackToolUseId = input.toolUseId()
            .orElseGet(() -> UUID.randomUUID().toString());

        // Launch all hooks in parallel via virtual threads.
        List<CompletableFuture<HookExecution>> futures = eligible.stream()
            .map(hook -> CompletableFuture.supplyAsync(
                () -> {
                    // Async hooks: fire-and-forget on a background virtual thread.
                    // When forceSyncExecution is set (exit path), fall through to
                    // synchronous executeHookCommand so hooks flush instead of
                    // being orphaned.
                    if (hook.command() instanceof BashCommandHook bash
                            && (bash.async() || bash.asyncRewake()) && !forceSyncExecution) {
                        Thread.ofVirtual().start(() -> {
                            try {
                                RunningBashHook h = startBashProcess(bash, input);
                                long asyncTimeoutMs =
                                    bash.timeoutSeconds().orElse(TOOL_HOOK_TIMEOUT_SECONDS) * 1000L;
                                String pid = registerPendingAsyncHook(bash, input, asyncTimeoutMs, h);
                                completeBashHookInBackground(h, bash, asyncTimeoutMs, input, pid, true);
                            } catch (IOException e) {
                                LOG.debug("Config-async hook launch failed: {}", e.getMessage());
                            }
                        });
                        return new HookExecution(hook.command(), HookResult.skip(), true, null);
                    }
                    OutputDrivenAsyncCapture capture = new OutputDrivenAsyncCapture();
                    return ScopedValue.where(OUTPUT_DRIVEN_ASYNC_CAPTURE, capture).call(() ->
                        new HookExecution(hook.command(),
                            executeHookCommandMillis(hook.command(), input, defaultTimeoutMillis,
                                callbackToolUseId),
                            capture.backgrounded, capture.initialOutput));
                },
                r -> Thread.ofVirtual().start(r) // virtual-thread executor
            ))
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
        publishHookEffects(event, input, executions);
        return executions;
    }

    private record HookExecution(HookCommand command, HookResult result,
                                 boolean backgrounded, String initialOutput) {}

    private void publishHookEffects(HookEvent event, HookInput input,
                                    List<HookExecution> executions) {
        HookEffectSink sink = hookEffectSink;
        List<Path> watchPaths = new ArrayList<>();
        String sessionTitle = null;
        boolean reloadSkills = false;
        for (HookExecution execution : executions) {
            HookResult result = execution.result();
            if (result instanceof HookResult.Decorated(
                HookResult result1, HookResult.Effects effects
            )) {
              String hookName = hookCommandText(execution.command());
                effects.systemMessage().filter(StringUtils::isNotBlank)
                    .ifPresent(message -> {
                        sink.showSystemMessage(event.displayName(), hookName, message);
                        hookMessages.add(new AttachmentMessage(UUID.randomUUID().toString(),
                            new HookSystemMessageAttachment(message, hookName,
                                input.toolUseId().orElseGet(() -> UUID.randomUUID().toString()),
                                event.displayName())));
                    });
                effects.successOutput().filter(StringUtils::isNotBlank)
                    .ifPresent(output -> sink.showSuccessOutput(
                        event.displayName(), hookName, output));
                effects.terminalSequence().ifPresent(sink::emitTerminalSequence);
                if (StringUtils.isNotBlank(effects.validationError())) {
                    enqueueEffectError(event, input, execution.command(),
                        effects.validationError());
                }
                result = result1;
            }
            if (!(result instanceof HookResult.Structured structured)
                    || structured.output() == null) continue;
            JsonNode output = structured.output();
            if (event == HookEvent.SESSION_START) {
                JsonNode title = output.get("sessionTitle");
                if (title != null && title.isTextual()
                        && StringUtils.isNotBlank(title.asText())) {
                    sessionTitle = title.asText();
                }
                JsonNode reload = output.get("reloadSkills");
                reloadSkills |= reload != null && reload.isBoolean() && reload.asBoolean();
            }
            JsonNode paths = output.get("watchPaths");
            if (paths != null && !paths.isArray()) {
                continue;
            }
            if (paths != null) {
                for (JsonNode path : paths) {
                    if (!path.isTextual()) {
                        enqueueEffectError(event, input, execution.command(),
                            "watchPaths entries must be strings");
                        continue;
                    }
                    String raw = path.asText();
                    if (Strings.CS.startsWith(raw, "//")
                            || Strings.CS.startsWith(raw, "\\\\")) {
                        enqueueEffectError(event, input, execution.command(),
                            "watchPaths must not use remote UNC paths: " + raw);
                        continue;
                    }
                    try {
                        Path candidate = Path.of(raw);
                        if (!candidate.isAbsolute()) {
                            enqueueEffectError(event, input, execution.command(),
                                "watchPaths must be absolute: " + raw);
                        } else if (!watchPaths.contains(candidate.normalize())) {
                            watchPaths.add(candidate.normalize());
                        }
                    } catch (RuntimeException _) {
                        enqueueEffectError(event, input, execution.command(),
                            "invalid watch path: " + raw);
                    }
                }
            }
        }
        String source = String.valueOf(input.extra().getOrDefault("source", ""));
        if (event == HookEvent.SESSION_START
                && (Strings.CS.equalsAny(source, "startup", "resume"))
                && StringUtils.isNotBlank(sessionTitle)) {
            sink.applySessionTitle(sessionTitle);
        }
        if (event == HookEvent.SESSION_START && reloadSkills) sink.reloadSkills();
        if (event == HookEvent.SESSION_START || event == HookEvent.CWD_CHANGED
                || event == HookEvent.FILE_CHANGED) {
            sink.replaceWatchPaths(List.copyOf(watchPaths));
        }
    }

    private void enqueueEffectError(HookEvent event, HookInput input,
                                    HookCommand command, String error) {
        HookNonBlockingErrorAttachment payload = new HookNonBlockingErrorAttachment(
            event.displayName(), error, "", 1,
            input.toolUseId().orElseGet(() -> UUID.randomUUID().toString()),
            event.displayName(), hookCommandText(command), 0L);
        hookMessages.add(new AttachmentMessage(UUID.randomUUID().toString(), payload));
    }

    /**
     * Returns the SessionEnd hook timeout in milliseconds.
     */
    static long getSessionEndHookTimeoutMillis() {
        String raw = SubprocessEnvironment.get(
            "CLAUDE_CODE_SESSIONEND_HOOKS_TIMEOUT_MS");
        if (StringUtils.isNotBlank(raw)) {
            try {
                long ms = Long.parseLong(raw.trim());
                if (ms > 0) return ms;
            } catch (NumberFormatException _) {}
        }
        return SESSION_END_HOOK_TIMEOUT_MS_DEFAULT;
    }

    /**
     * Executes PreToolUse hooks and returns a permission decision modifier.
     *
     * @param toolName  the tool being invoked
     * @param toolInput the tool input
     * @param toolUseId the tool use ID
     * @return aggregated hook result affecting permission
     */
    public HookResult executePreToolHooks(String toolName, JsonNode toolInput, String toolUseId) {
        HookInput input = HookInput.forPreToolUse(toolName, toolInput, toolUseId,
            sessionIdentity.get(), resolveCwd(), currentPermissionMode());
        List<HookResult> results = executeHooks(HookEvent.PRE_TOOL_USE, input);

        // If any hook blocks, the operation is blocked
        for (HookResult result : results) {
            if (baseResult(result) instanceof HookResult.Block) {
                return result;
            }
        }

        // Collect additional context from Allow results
        StringBuilder context = new StringBuilder();
        for (HookResult result : results) {
            if (baseResult(result) instanceof HookResult.Allow(Optional<String> additionalContext)
                && additionalContext.isPresent()) {
                if (!context.isEmpty()) context.append("\n");
                context.append(additionalContext.get());
            }
        }

        if (!context.isEmpty()) {
            return new HookResult.Allow(context.toString());
        }

        return HookResult.skip();
    }

    /**
     * Executes PostToolUse hooks.
     */
    public List<HookResult> executePostToolHooks(
            String toolName, JsonNode toolInput, JsonNode toolOutput, String toolUseId) {
        HookInput input = HookInput.forPostToolUse(toolName, toolInput, toolOutput, toolUseId,
            sessionIdentity.get(), resolveCwd(), currentPermissionMode());
        return executeHooks(HookEvent.POST_TOOL_USE, input);
    }

    // ---- HookDispatcher interface (core-facing, no-throw) ----

    @Override
    public boolean dispatchPreToolUse(String toolName, JsonNode input, String toolUseId) {
        try {
            HookResult r = executePreToolHooks(toolName, input, toolUseId);
            return !(baseResult(r) instanceof HookResult.Block);
        } catch (Throwable t) {
            LOG.warn("PRE_TOOL_USE hook dispatch (block-check) failed for {}: {}", toolName, t.getMessage());
            return true; // fail open: never block tools because hooks crashed
        }
    }

    @Override
    public HookDispatcher.HookOutcome
    dispatchPreToolUseWithOutcome(String toolName, JsonNode input, String toolUseId) {
        try {
            HookInput hookInput = HookInput.forPreToolUse(toolName, input, toolUseId,
                sessionIdentity.get(), resolveCwd(), currentPermissionMode());
            HookDispatcher.HookOutcome outcome = aggregateOutcome(
                executeHooks(HookEvent.PRE_TOOL_USE, hookInput));
            if (outcome.hasBlockingErrors()) {
                List<String> errors = outcome.blockingErrors().stream()
                    .map(reason -> "PreToolUse:" + toolName + " hook error: " + reason)
                    .toList();
                return new HookDispatcher.HookOutcome(false, outcome.additionalContext(), errors,
                    outcome.preventContinuation(), outcome.stopReason(),
                    outcome.userDisplayMessage(), outcome.additionalContexts(),
                    outcome.specificOutputs());
            }
            return outcome;
        } catch (Throwable t) {
            LOG.warn("PRE_TOOL_USE hook dispatch (outcome) failed for {}: {}", toolName, t.getMessage());
            return HookDispatcher.HookOutcome.PROCEED;
        }
    }

    @Override
    public void dispatchPostToolUse(String toolName, JsonNode input, JsonNode output, String toolUseId) {
        dispatchPostToolUseWithOutcome(toolName, input, output, toolUseId);
    }

    @Override
    public HookDispatcher.HookOutcome dispatchPostToolUseWithOutcome(
            String toolName, JsonNode input, JsonNode output, String toolUseId) {
        try {
            return aggregateOutcome(executePostToolHooks(toolName, input, output, toolUseId));
        } catch (Throwable failure) {
            LOG.warn("POST_TOOL_USE hook dispatch failed for {}: {}",
                toolName, failure.getMessage());
            return HookDispatcher.HookOutcome.PROCEED;
        }
    }

    @Override
    public HookDispatcher.HookOutcome dispatchPostToolUseFailureWithOutcome(
            String toolName, JsonNode input, String toolUseId,
            String error, boolean isInterrupt) {
        try {
            HookInput hookInput = HookInput.forPostToolUseFailure(
                toolName, input, toolUseId, error, isInterrupt,
                sessionIdentity.get(), resolveCwd(), currentPermissionMode());
            return aggregateOutcome(executeHooks(HookEvent.POST_TOOL_USE_FAILURE, hookInput));
        } catch (Throwable failure) {
            LOG.warn("POST_TOOL_USE_FAILURE hook dispatch failed for {}: {}",
                toolName, failure.getMessage());
            return HookDispatcher.HookOutcome.PROCEED;
        }
    }

    @Override
    public HookDispatcher.HookOutcome dispatchPostToolBatchWithOutcome(JsonNode toolCalls) {
        try {
            return aggregateOutcome(executeHooks(HookEvent.POST_TOOL_BATCH,
                HookInput.forPostToolBatch(toolCalls, sessionIdentity.get(),
                    resolveCwd(), currentPermissionMode())));
        } catch (Throwable failure) {
            LOG.warn("POST_TOOL_BATCH hook dispatch failed: {}", failure.getMessage());
            return HookDispatcher.HookOutcome.PROCEED;
        }
    }

    @Override
    public void dispatchUserPromptSubmit(String prompt) {
        dispatchUserPromptSubmitWithOutcome(prompt);
    }


    @Override
    public HookDispatcher.HookOutcome
    dispatchUserPromptSubmitWithOutcome(String prompt) {
        try {
            HookInput in = HookInput.forUserPromptSubmit(prompt, sessionIdentity.get(),
                resolveCwd(), currentPermissionMode(), promptIdSupplier.get());
            return aggregateOutcome(executeHooks(HookEvent.USER_PROMPT_SUBMIT, in));
        } catch (Throwable t) {
            LOG.warn("USER_PROMPT_SUBMIT hook dispatch failed: {}", t.getMessage());
            return HookDispatcher.HookOutcome.PROCEED;
        }
    }

    @Override
    public HookDispatcher.HookOutcome dispatchUserPromptExpansionWithOutcome(
            String expansionType, String commandName, String commandArgs,
            String commandSource, String originalPrompt) {
        try {
            HookInput input = HookInput.forUserPromptExpansion(
                expansionType, commandName, commandArgs, commandSource, originalPrompt,
                sessionIdentity.get(), resolveCwd(), currentPermissionMode());
            return aggregateOutcome(executeHooks(HookEvent.USER_PROMPT_EXPANSION, input));
        } catch (Throwable failure) {
            LOG.warn("USER_PROMPT_EXPANSION hook dispatch failed for {}: {}",
                commandName, failure.getMessage());
            return HookDispatcher.HookOutcome.PROCEED;
        }
    }

    @Override
    public void dispatchSessionStart(String trigger) {
        dispatchSessionStartWithOutcome(trigger);
    }

    @Override
    public void dispatchCwdChanged(String oldCwd, String newCwd) {
        Thread.ofVirtual().name("cwd-changed-hook").start(() ->
            dispatchCwdChangedWithOutcome(oldCwd, newCwd));
    }

    @Override
    public HookDispatcher.HookOutcome dispatchCwdChangedWithOutcome(
            String oldCwd, String newCwd) {
        if (oldCwd == null || newCwd == null || oldCwd.equals(newCwd)) {
            return HookDispatcher.HookOutcome.PROCEED;
        }
        try {
            HookDispatcher.HookOutcome outcome = aggregateOutcome(executeHooks(
                HookEvent.CWD_CHANGED,
                HookInput.forCwdChanged(oldCwd, newCwd, sessionIdentity.get())));
            hookEffectSink.cwdChanged(Path.of(oldCwd), Path.of(newCwd));
            return outcome;
        } catch (Throwable failure) {
            LOG.warn("CWD_CHANGED hook dispatch failed: {}", failure.getMessage());
            return HookDispatcher.HookOutcome.PROCEED;
        }
    }


    @Override
    public HookDispatcher.HookOutcome
    dispatchSessionStartWithOutcome(String trigger) {
        try {
            HookInput in = HookInput.forSessionStart(trigger, sessionIdentity.get(), resolveCwd());
            return aggregateOutcome(executeHooks(HookEvent.SESSION_START, in));
        } catch (Throwable t) {
            LOG.warn("SESSION_START hook dispatch failed: {}", t.getMessage());
            return HookDispatcher.HookOutcome.PROCEED;
        }
    }

    @Override
    public HookDispatcher.HookOutcome
    dispatchSubAgentStartWithOutcome(String agentId, String agentType) {
        try {
            HookInput input = HookInput.forSubagentStart(
                agentId, agentType, sessionIdentity.get(), resolveCwd());
            return aggregateOutcome(executeHooks(HookEvent.SUBAGENT_START, input));
        } catch (Throwable error) {
            LOG.warn("SUBAGENT_START hook dispatch failed for {}: {}", agentType,
                error.getMessage());
            return HookDispatcher.HookOutcome.PROCEED;
        }
    }

    @Override
    public void dispatchInstructionsLoaded(String filePath, String memoryType,
                                            String loadReason, List<String> globs) {
        try {
            executeHooks(HookEvent.INSTRUCTIONS_LOADED,
                HookInput.forInstructionsLoaded(filePath, memoryType, loadReason, globs,
                    sessionIdentity.get(), resolveCwd()));
        } catch (Throwable t) {
            LOG.warn("INSTRUCTIONS_LOADED hook dispatch failed for {}: {}", filePath, t.getMessage());
        }
    }

    @Override
    public void dispatchStop(String reason) {
        dispatchStopWithOutcome(reason, false);
    }


    @Override
    public void dispatchSessionEnd(String reason) {
        try {
            executeHooks(HookEvent.SESSION_END, HookInput.forSessionEnd(reason, sessionIdentity.get(), resolveCwd()));
        } catch (Throwable t) {
            LOG.warn("SESSION_END hook dispatch failed: {}", t.getMessage());
        }
    }

    /**
     * Whether any {@code WorktreeCreate} hook is configured.
     */
    public boolean hasWorktreeCreateHook() {
        return !getMatchingHooks(HookEvent.WORKTREE_CREATE,
            HookInput.forWorktreeCreate("", sessionIdentity.get(), resolveCwd())).isEmpty();
    }

    /**
     * Runs {@code WorktreeCreate} hooks and returns the first successful hook's stdout (the created
     * worktree path).
     */
    public Optional<String> dispatchWorktreeCreate(String name) {
        try {
            for (HookResult r : executeHooks(HookEvent.WORKTREE_CREATE,
                    HookInput.forWorktreeCreate(name, sessionIdentity.get(), resolveCwd()))) {
                r = baseResult(r);
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
            LOG.warn("WORKTREE_CREATE hook dispatch failed: {}", t.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Runs {@code WorktreeRemove} hooks.
     */
    public boolean dispatchWorktreeRemove(String worktreePath) {
        try {
            List<HookResult> results = executeHooks(HookEvent.WORKTREE_REMOVE,
                HookInput.forWorktreeRemove(worktreePath, sessionIdentity.get(), resolveCwd()));
            return results.stream().anyMatch(r -> !(baseResult(r) instanceof HookResult.Skip));
        } catch (Throwable t) {
            LOG.warn("WORKTREE_REMOVE hook dispatch failed: {}", t.getMessage());
            return false;
        }
    }

    /** Whether any {@code TaskCreated} hook is configured. */
    public boolean hasTaskCreatedHook() {
        return !getMatchingHooks(HookEvent.TASK_CREATED,
            HookInput.forTaskCreated("", "", null, sessionIdentity.get(), resolveCwd(),
                currentPermissionMode()))
            .isEmpty();
    }


    public List<String> dispatchTaskCreated(String taskId, String subject, String description) {
        return dispatchTaskCreated(taskId, subject, description, null, null);
    }


    public List<String> dispatchTaskCreated(
            String taskId, String subject, String description,
            String teammateName, String teamName) {
        try {
            List<HookResult> results = executeHooks(HookEvent.TASK_CREATED,
                HookInput.forTaskCreated(taskId, subject, description, teammateName, teamName,
                    sessionIdentity.get(), resolveCwd(), currentPermissionMode()));
            return results.stream()
                .filter(HookResult.Block.class::isInstance)
                .map(r -> "TaskCreated hook feedback:\n" + ((HookResult.Block) r).reason())
                .toList();
        } catch (Throwable t) {
            LOG.warn("TASK_CREATED hook dispatch failed: {}", t.getMessage());
            return List.of();
        }
    }

    /** Whether any {@code TaskCompleted} hook is configured. */
    public boolean hasTaskCompletedHook() {
        return !getMatchingHooks(HookEvent.TASK_COMPLETED,
            HookInput.forTaskCompleted("", "", null, sessionIdentity.get(), resolveCwd(),
                currentPermissionMode()))
            .isEmpty();
    }


    public List<String> dispatchTaskCompleted(String taskId, String subject, String description) {
        return dispatchTaskCompleted(taskId, subject, description, null, null);
    }


    public List<String> dispatchTaskCompleted(
            String taskId, String subject, String description,
            String teammateName, String teamName) {
        try {
            List<HookResult> results = executeHooks(HookEvent.TASK_COMPLETED,
                HookInput.forTaskCompleted(taskId, subject, description, teammateName, teamName,
                    sessionIdentity.get(), resolveCwd(), currentPermissionMode()));
            return results.stream()
                .filter(HookResult.Block.class::isInstance)
                .map(r -> "TaskCompleted hook feedback:\n" + ((HookResult.Block) r).reason())
                .toList();
        } catch (Throwable t) {
            LOG.warn("TASK_COMPLETED hook dispatch failed: {}", t.getMessage());
            return List.of();
        }
    }

    @Override
    public HookDispatcher.HookOutcome dispatchTaskCompletedWithOutcome(
            String taskId, String subject, String description) {
        try {
            return aggregateOutcome(executeHooks(HookEvent.TASK_COMPLETED,
                HookInput.forTaskCompleted(taskId, subject, description,
                    sessionIdentity.get(), resolveCwd(), currentPermissionMode())));
        } catch (Throwable failure) {
            LOG.warn("TASK_COMPLETED hook dispatch failed for {}: {}",
                taskId, failure.getMessage());
            return HookDispatcher.HookOutcome.PROCEED;
        }
    }

    @Override
    public HookDispatcher.HookOutcome
    dispatchStopWithOutcome(String reason) {
        return dispatchStopWithOutcome(reason, false);
    }


    @Override
    public HookDispatcher.HookOutcome
    dispatchStopWithOutcome(String reason, boolean stopHookActive) {
        if (subAgentScope != null) {
            return dispatchSubAgentStopWithOutcome(stopHookActive);
        }
        try {
            HookInput in = HookInput.forStop(stopHookActive, lastAssistantText(),
                sessionIdentity.get(), resolveCwd(), currentPermissionMode(),
                promptIdSupplier.get(), currentGoalEffort());
            PromptHook currentGoal = goalPromptHook;
            boolean deferGoal = currentGoal != null
                && backgroundTasksRunningSupplier.getAsBoolean();
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
            processGoalExecution(currentGoal, executions);
            HookDispatcher.HookOutcome outcome =
                aggregateOutcome(executions.stream().map(HookExecution::result).toList());
            LOG.info("[goal-diag] STOP outcome blockingErrors={} preventContinuation={}",
                outcome.blockingErrors().size(), outcome.preventContinuation());
            return outcome;
        } catch (Throwable t) {
            LOG.warn("STOP hook dispatch failed: {}", t.getMessage());
            return HookDispatcher.HookOutcome.PROCEED;
        }
    }

    private HookDispatcher.HookOutcome dispatchSubAgentStopWithOutcome(
            boolean stopHookActive) {
        try {
            HookInput input = HookInput.forSubagentStop(
                subAgentScope.agentId(), subAgentScope.agentTranscriptPath(),
                subAgentScope.agentType(), stopHookActive, lastAssistantText(),
                sessionIdentity.get(), resolveCwd(), subAgentScope.permissionMode(),
                promptIdSupplier.get(), subAgentScope.effort());
            return aggregateOutcome(executeHooks(HookEvent.SUBAGENT_STOP, input));
        } catch (Throwable error) {
            LOG.warn("SUBAGENT_STOP hook dispatch failed: {}", error.getMessage());
            return HookDispatcher.HookOutcome.PROCEED;
        }
    }

    private void processGoalExecution(PromptHook evaluatedGoal, List<HookExecution> executions) {
        if (evaluatedGoal == null) return;
        HookExecution execution = executions.stream()
            .filter(item -> item.command() == evaluatedGoal)
            .findFirst().orElse(null);
        if (execution == null) return;
        HookDispatcher.ActiveGoal current = activeGoal;
        if (current == null || !current.condition().equals(evaluatedGoal.prompt())) return;

        int iterations = current.iterations() + 1;
        HookResult result = baseResult(execution.result());
        if (result instanceof HookResult.ConditionNotMet notMet) {
            activeGoal = new HookDispatcher.ActiveGoal(current.condition(), iterations,
                current.setAtMillis(), current.tokensAtStart(), notMet.reason());
            goalTransition.set(new HookDispatcher.GoalTransition(
                HookDispatcher.GoalTransitionKind.PENDING, current.condition(),
                notMet.reason(), iterations, 0L, 0L));
            return;
        }
        if (result instanceof HookResult.ConditionMet(String reason)) {
            finishGoal(current, iterations, reason,
                HookDispatcher.GoalTransitionKind.MET);
        } else if (result instanceof HookResult.ConditionImpossible(String reason)) {
            finishGoal(current, iterations, reason,
                HookDispatcher.GoalTransitionKind.FAILED);
        }
    }

    private void finishGoal(HookDispatcher.ActiveGoal current, int iterations,
                            String reason, HookDispatcher.GoalTransitionKind kind) {
        long duration = Math.max(0L, System.currentTimeMillis() - current.setAtMillis());
        long tokens = Math.max(0L, tokenCountSupplier.getAsLong() - current.tokensAtStart());
        synchronized (goalLock) {
            if (activeGoal == null || !activeGoal.condition().equals(current.condition())) return;
            removeGoalHookLocked();
            activeGoal = null;
        }
        goalTransition.set(new HookDispatcher.GoalTransition(
            kind, current.condition(), reason, iterations, duration, tokens));
    }

    /**
     * Fires the distinct {@code StopFailure} event.
     */
    @Override
    public void dispatchStopFailure(String reason) {
        try {
            HookInput in = HookInput.forStopFailure(reason, lastAssistantText(),
                sessionIdentity.get(), resolveCwd());
            executeHooks(HookEvent.STOP_FAILURE, in);
        } catch (Throwable t) {
            LOG.warn("STOP_FAILURE hook dispatch failed: {}", t.getMessage());
        }
    }

    @Override
    public void dispatchPreCompact(String trigger, String customInstructions, long preTokenCount) {
        dispatchPreCompactWithOutcome(trigger, customInstructions, preTokenCount);
    }


    @Override
    public HookDispatcher.HookOutcome
    dispatchPreCompactWithOutcome(String trigger, String customInstructions, long preTokenCount) {
        try {
            List<HookExecution> results = executeHooksWithCommands(HookEvent.PRE_COMPACT,
                HookInput.forPreCompact(trigger, customInstructions, preTokenCount,
                    sessionIdentity.get(), resolveCwd(), promptIdSupplier.get()));
            return aggregateCompactOutcome("PreCompact", results, "\n\n");
        } catch (Throwable t) {
            LOG.warn("PRE_COMPACT hook dispatch failed: {}", t.getMessage());
            return HookDispatcher.HookOutcome.PROCEED;
        }
    }

    @Override
    public void dispatchPostCompact(String trigger, String compactSummary, long postTokenCount) {
        dispatchPostCompactWithOutcome(trigger, compactSummary, postTokenCount);
    }

    /**
     * Fires PostCompact and aggregates hook-emitted {@code additionalContext} into the returned {@link
     * HookDispatcher.HookOutcome} — the caller joins this with the PreCompact hook's own {@code
     * additionalContext} into the success message shown after {@code /compact} completes.
     */
    @Override
    public HookDispatcher.HookOutcome
    dispatchPostCompactWithOutcome(String trigger, String compactSummary, long postTokenCount) {
        try {
            List<HookExecution> results = executeHooksWithCommands(HookEvent.POST_COMPACT,
                HookInput.forPostCompact(trigger, compactSummary, postTokenCount, sessionIdentity.get(), resolveCwd()));
            return aggregateCompactOutcome("PostCompact", results, "\n");
        } catch (Throwable t) {
            LOG.warn("POST_COMPACT hook dispatch failed: {}", t.getMessage());
            return HookDispatcher.HookOutcome.PROCEED;
        }
    }


    private static HookDispatcher.HookOutcome aggregateCompactOutcome(
            String eventName, List<HookExecution> executions, String additionalSeparator) {
        StringBuilder additional = new StringBuilder();
        List<String> display = new ArrayList<>();
        for (HookExecution execution : executions) {
            HookResult result = baseResult(execution.result());
            String command = hookCommandText(execution.command());
            boolean succeeded = result instanceof HookResult.Allow
                || result instanceof HookResult.Message
                || execution.backgrounded();
            String output = execution.backgrounded()
                    && execution.initialOutput() != null
                    && !StringUtils.isBlank(execution.initialOutput())
                ? execution.initialOutput().trim()
                : compactHookOutput(result, command);
            if (succeeded && !StringUtils.isBlank(output)) {
                if (!additional.isEmpty()) additional.append(additionalSeparator);
                additional.append(output);
            }
            String status = eventName + " [" + command + "] "
                + (succeeded ? "completed successfully" : "failed");
            display.add(StringUtils.isBlank(output) ? status : status + ": " + output);
        }
        return new HookDispatcher.HookOutcome(
            true,
            additional.isEmpty() ? null : additional.toString(),
            List.of(), false, null,
            display.isEmpty() ? null : String.join("\n", display));
    }

    private static String hookCommandText(HookCommand command) {
        if (command == null) return "unknown";
        return HooksConfigManager.getRawHookContent(command);
    }

    private static String compactHookOutput(HookResult result, String command) {
        result = baseResult(result);
        return switch (result) {
            case HookResult.Allow allow -> allow.additionalContext().orElse("").trim();
            case HookResult.Message message -> message.content() != null ? message.content().trim() : "";
            case HookResult.Block block -> stripCommandPrefix(block.reason(), command);
            case HookResult.PreventContinuation stopped -> stopped.stopReason().orElse("").trim();
            case HookResult.Structured structured ->
                structured.additionalContext().orElse("").trim();
            case HookResult.Decorated _ -> throw new IllegalStateException(
                "decorated hook result must be unwrapped");
            case HookResult.Skip _ -> "";
            case HookResult.ConditionMet met -> met.reason() != null ? met.reason().trim() : "";
            case HookResult.ConditionNotMet notMet -> notMet.reason() != null ? notMet.reason().trim() : "";
            case HookResult.ConditionImpossible impossible ->
                impossible.reason() != null ? impossible.reason().trim() : "";
        };
    }

    private static String stripCommandPrefix(String output, String command) {
        if (output == null) return "";
        String trimmed = output.trim();
        String prefix = "[" + command + "]: ";
        return Strings.CS.startsWith(trimmed, prefix) ? trimmed.substring(prefix.length()).trim() : trimmed;
    }


    private static HookDispatcher.HookOutcome aggregateOutcome(List<HookResult> results) {
        List<String> blocking = new ArrayList<>();
        List<String> contexts = new ArrayList<>();
        boolean preventContinuation = false;
        String stopReason = null;
        List<HookDispatcher.HookSpecificOutput> specificOutputs = new ArrayList<>();
        for (HookResult r : results) {
            r = baseResult(r);
            if (r instanceof HookResult.Block block) {
                blocking.add(block.reason());
            } else if (r instanceof HookResult.ConditionNotMet(String condition, String reason)) {
                blocking.add("[" + condition + "]: " + reason);
            } else if (r instanceof HookResult.Allow(Optional<String> additionalContext)
                && additionalContext.isPresent()) {
                String c = additionalContext.get();
                if (!StringUtils.isBlank(c)) {
                    contexts.add(c);
                }
            } else if (r instanceof HookResult.PreventContinuation(Optional<String> reason)) {
                preventContinuation = true;
                if (reason.isPresent()) {
                    stopReason = reason.get();
                }
            } else if (r instanceof HookResult.Structured(JsonNode output,
                    Optional<String> additionalContext)) {
                if (output != null && output.path("hookEventName").isTextual()) {
                    specificOutputs.add(new HookDispatcher.HookSpecificOutput(
                        output.path("hookEventName").asText(), output));
                }
                additionalContext.filter(c -> !StringUtils.isBlank(c))
                    .ifPresent(contexts::add);
            }
        }
        return new HookDispatcher.HookOutcome(
            blocking.isEmpty() && !preventContinuation,
            contexts.isEmpty() ? null : String.join("\n", contexts),
            List.copyOf(blocking),
            preventContinuation,
            stopReason,
            null,
            List.copyOf(contexts),
            List.copyOf(specificOutputs));
    }

    private static HookResult baseResult(HookResult result) {
        return result instanceof HookResult.Decorated decorated
            ? decorated.result() : result;
    }

    /**
     * Captures the five independently mutable source layers in their established order. The
     * resolver only interprets this immutable per-dispatch view; it never owns reload or session
     * state.
     */
    private List<HookMatchResolver.MatchedHook> getMatchingHooks(HookEvent event, HookInput input) {
        HooksSettings settingsSnapshot = settings;
        Map<HookEvent, List<HookMatcher>> pluginSnapshot = pluginHooks;
        Map<HookEvent, List<HookMatcher>> sdkSnapshot = sdkHooks;
        return hookMatchResolver.resolve(event, input, List.of(
            settingsSnapshot.getMatchers(event),
            pluginSnapshot.getOrDefault(event, List.of()),
            sdkSnapshot.getOrDefault(event, List.of()),
            extraHooks.getOrDefault(event, List.of()),
            sessionHooks.getOrDefault(event, List.of())));
    }

    // ---- Hook execution by type ----

    private HookResult executeHookCommandMillis(HookCommand command, HookInput input,
                                                long defaultTimeoutMillis,
                                                String callbackToolUseId) {
        try {
            return switch (command) {
                case BashCommandHook cmd -> executeBashHookMillis(cmd, input, defaultTimeoutMillis);
                case PromptHook cmd -> executePromptHookMillis(cmd, input, defaultTimeoutMillis);
                case HttpHook cmd -> executeHttpHookMillis(cmd, input, defaultTimeoutMillis);
                case AgentHook cmd -> executeAgentHookMillis(cmd, input, defaultTimeoutMillis);
                case CallbackHook cmd -> parseHookOutput(cmd.callback().invoke(
                    input, callbackToolUseId).toString(), input.event());
            };
        } catch (Exception e) {
            LOG.warn("Hook execution failed (single dispatch): {}", e.getMessage());
            return HookResult.skip();
        }
    }

    /**
     * Derives the hook identifier used in blocking-error messages, matching {@code hookName =
     * matchQuery ? `${hookEvent}:${matchQuery}`: hookEvent}.
     */
    private static String hookEventName(HookInput input) {
        String base = input.event().displayName();
        return input.toolName().map(t -> base + ":" + t).orElse(base);
    }


    HookResult executeBashHook(BashCommandHook cmd, HookInput input, int defaultTimeoutSeconds) {
        return executeBashHookMillis(cmd, input, defaultTimeoutSeconds * 1000L);
    }

    private HookResult executeBashHookMillis(BashCommandHook cmd, HookInput input,
                                             long defaultTimeoutMillis) {
        long timeoutMillis = cmd.timeoutSeconds()
            .map(seconds -> seconds * 1000L)
            .orElse(defaultTimeoutMillis);

        try {
            // Launch the subprocess. Throws IOException on broken pipe → the
            // caller returns Skip, matching the synchronous failure path. The
            // returned handle keeps the live process + drain threads so async
            // paths can hand it to a background thread.
            RunningBashHook h = startBashProcess(cmd, input);

            // Read stdout manually (no try-with-resources) so that, if the hook
            // self-declares output-driven async via its first stdout line
            // ({"async":true,...}), we can hand the still-open stream + process
            // to a background thread without the reader auto-closing
            // process.getInputStream(). See completeBashHookInBackground.
            StringBuilder stdout = new StringBuilder();
            boolean handedOff = false;
            try {
                String firstLine = h.stdoutReader.readLine();
                if (firstLine != null && isAsyncHandshake(firstLine)) {
                    if (!forceSyncExecution) {
                        long asyncTimeoutMs = resolveAsyncTimeout(firstLine, timeoutMillis);
                        String pid = registerPendingAsyncHook(cmd, input, asyncTimeoutMs, h);
                        completeBashHookInBackground(h, cmd, asyncTimeoutMs, input, pid, true);
                        OutputDrivenAsyncCapture capture = OUTPUT_DRIVEN_ASYNC_CAPTURE.isBound()
                            ? OUTPUT_DRIVEN_ASYNC_CAPTURE.get() : null;
                        if (capture != null) capture.background(firstLine);
                        handedOff = true;
                        return HookResult.skip();
                    }
                    // forceSync: the async handshake is a control line, not hook
                    // output — drop it so the real result on later lines parses
                    // normally (the handshake is consumed, never re-injected).
                } else if (firstLine != null) {
                    stdout.append(firstLine).append('\n');
                }
                String line;
                while ((line = h.stdoutReader.readLine()) != null) {
                    stdout.append(line).append('\n');
                }
            } finally {
                if (!handedOff) {
                    try {
                        h.stdoutReader.close();
                    } catch (IOException _) {
                        /* best-effort */
                    }
                }
            }

            boolean completed = h.process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!completed) {
                h.process.destroyForcibly();
                return HookResult.skip();
            }
            h.stderrDrain.join(1000);

            int exitCode = h.process.exitValue();
            String output = stdout.toString().trim();
            String rawErr = h.stderr.toString();
            String err = rawErr.trim();




            //

            // HookMatcher/BashCommandHook don't model that name, so we fall back
            // to the command string, consistent with the blocking-error message

            if (cmd.asyncRewake() && exitCode == 2) {
                if (messageQueue != null) {
                    String body = err.isEmpty() ? output : err;
                    messageQueue.enqueuePendingNotification(new QueuedCommand(
                        MessageConstants.wrapInSystemReminder(
                            "Stop hook blocking error from command \"" + hookEventName(input) + "\": " + body),
                        null, "task-notification", QueuePriority.LATER,
                        false, null, false, false, null, null, null));
                }
                return new HookResult.Message("Hook requested model rewake");
            }

            // JSON stdout takes precedence over the exit code.
            if (Strings.CS.startsWith(output, "{")) {
                return parseHookOutput(output, input.event());
            }

            // Exit 2 — blocking feedback built from stderr.
            if (exitCode == 2) {
                return new HookResult.Block(
                    "[" + cmd.command() + "]: "
                        + (rawErr.isEmpty() ? "No stderr output" : rawErr));
            }

            // Other non-zero — non-blocking error, ignore.
            if (exitCode != 0) {
                return HookResult.skip();
            }

            return output.isEmpty() ? HookResult.allow() : new HookResult.Allow(output);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.debug("Bash hook execution error: {}", e.getMessage());
            return HookResult.skip();
        }
    }


    private static boolean isAsyncHandshake(String firstLineJson) {
        try {
            JsonNode n = JsonUtils.getMapper().readTree(firstLineJson);
            return n.has("async") && n.get("async").asBoolean(false);
        } catch (Exception _) {
            return false;
        }
    }

    /**
     * Resolves the timeout for an output-driven async hook. An explicit
     * {@code asyncTimeout} in the handshake overrides the command's effective
     * execution timeout; otherwise the hook retains the same configured/default
     * timeout it had before being backgrounded.
     */
    @Explanation("Enforces the asyncTimeout supplied by an asynchronous hook handshake")
    private static long resolveAsyncTimeout(String firstLineJson, long commandTimeoutMillis) {
        long fromHandshake = -1;
        try {
            JsonNode n = JsonUtils.getMapper().readTree(firstLineJson);
            if (n.has("asyncTimeout")) {
                fromHandshake = n.get("asyncTimeout").asLong(-1);
            }
        } catch (Exception _) {
            /* fall through to defaults */
        }
        if (fromHandshake > 0) {
            return fromHandshake;
        }
        return commandTimeoutMillis;
    }

    /**
     * Completes a backgrounded async hook: drains remaining stdout on a side thread, enforces the
     * {@code asyncTimeoutMs} floor (force-kill on overrun), records the result in the registry when
     * {@code registerIntoRegistry} is true, and (for {@code asyncRewake} hooks) enqueues a
     * task-notification on exit 2.
     */
    private void completeBashHookInBackground(RunningBashHook h, BashCommandHook cmd,
            long asyncTimeoutMs, HookInput input, String processId, boolean registerIntoRegistry) {
        Thread.ofVirtual().start(() -> {
            try {
                // Drain remaining stdout on a side thread so we never block the
                // timeout logic on a still-running process — blocking on a full
                // readLine() here would let the hook run to completion and defeat
                // the asyncTimeout kill below.
                StringBuilder rest = new StringBuilder();
                Thread stdoutDrain = Thread.ofVirtual().start(() -> {
                    try {
                        String line;
                        while ((line = h.stdoutReader.readLine()) != null) {
                            rest.append(line).append('\n');
                        }
                    } catch (IOException _) {
                        /* best-effort */
                    }
                });
                boolean done;
                try {
                    // Enforce the timeout FIRST — a hung hook must be killed at
                    // asyncTimeoutMs, not after joining the drain threads. Joining
                    // stderr first would let a 1s-sleep hook finish before the
                    // 200ms timeout is ever checked.
                    done = h.process.waitFor(asyncTimeoutMs, TimeUnit.MILLISECONDS);
                    if (!done) {
                        h.process.destroyForcibly();   // asyncTimeout floor: kill a hung hook
                    }
                    h.stderrDrain.join(1000);
                    stdoutDrain.join(2000);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    h.process.destroyForcibly();
                    try {
                        h.stderrDrain.join(1000);
                        stdoutDrain.join(1000);
                    } catch (InterruptedException _) {
                        /* best-effort */
                    }
                    done = false;
                }
                int exitCode = h.process.exitValue();
                String stdout = rest.toString();
                String stderr = h.stderr.toString();
                if (registerIntoRegistry) {
                    asyncHookRegistry.complete(processId, stdout, stderr, exitCode,
                        done ? AsyncHookRegistry.AsyncStatus.COMPLETED
                            : AsyncHookRegistry.AsyncStatus.KILLED);
                }
                if (cmd.asyncRewake() && exitCode == 2 && messageQueue != null) {
                    String body = stderr.isEmpty() ? stdout : stderr;
                    messageQueue.enqueuePendingNotification(new QueuedCommand(
                        MessageConstants.wrapInSystemReminder(
                            "Stop hook blocking error from command \"" + hookEventName(input) + "\": " + body),
                        null, "task-notification", QueuePriority.LATER,
                        false, null, false, false, null, null, null));
                }
                // Plain async (no rewake): the process finishes in the background and its result
                // does not re-wake the model; it is recorded in the registry for later re-injection
                // as an attachment.
                LOG.debug("Async hook completed (exit {}): {}", exitCode, cmd.command());
            } catch (Exception e) {
                LOG.debug("Async hook background completion failed: {}", e.getMessage());
            } finally {
                try {
                    h.stdoutReader.close();
                } catch (IOException _) {
                    /* best-effort */
                }
                if (h.process.isAlive()) {
                    h.process.destroyForcibly();   // double safety net against orphan processes
                }
            }
        });
    }

    /**
     * Launches a BashCommandHook subprocess and returns a handle with the still-running process plus
     * its stderr/stdout drains.
     */
    private RunningBashHook startBashProcess(BashCommandHook cmd, HookInput input)
            throws IOException {
        Path skillRoot = extraHookRoots.get(cmd);
        String command = cmd.command();
        if (skillRoot != null) {
            command = command.replace("${CLAUDE_PLUGIN_ROOT}", skillRoot.toString());
        }
        ProcessBuilder pb = new ProcessBuilder(
            PlatformShellCommand.resolve(cmd.shell().orElse(null), command));
        SubprocessEnvironment.applyTo(pb.environment());

        // live cwd may momentarily point at a deleted path. Only set the
        // subprocess directory when it still exists.
        File hookCwd = Path.of(resolveCwd()).toFile();
        if (hookCwd.isDirectory()) {
            pb.directory(hookCwd);
        } else {
            LOG.debug("Hook cwd {} does not exist; inheriting JVM cwd", hookCwd);
        }
        pb.environment().put("HOOK_INPUT", input.toJson());
        if (skillRoot != null) {
            pb.environment().put("CLAUDE_PLUGIN_ROOT", skillRoot.toString());
        }
        pb.redirectErrorStream(false);

        Process process = pb.start();
        try {
            process.getOutputStream().write((input.toJson() + "\n").getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
        } catch (IOException stdinErr) {
            LOG.debug("Hook stdin write failed (process likely exited early): {}", stdinErr.getMessage());
            try {
                process.getOutputStream().close();
            } catch (IOException _) {
                // The child already closed the pipe.
            }
        }

        StringBuilder stderr = new StringBuilder();
        Thread stderrDrain = Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderr.append(line).append('\n');
                }
            } catch (IOException _) { /* best-effort capture */ }
        });

        BufferedReader stdoutReader = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        return new RunningBashHook(process, stderr, stderrDrain, stdoutReader);
    }

    /**
     * Registers a still-running async hook (output-driven or config-async) so
     * its completed result can later be polled and re-injected as an attachment.
     */
    private String registerPendingAsyncHook(BashCommandHook cmd, HookInput input,
            long asyncTimeoutMs, RunningBashHook h) {
        String hookEvent = hookEventName(input);
        return asyncHookRegistry.register(
            hookEvent, cmd.command(), hookEvent, input.toolName().orElse(null), null,
            asyncTimeoutMs, h.process);
    }

    @Override
    public List<AsyncHookResponse> checkForAsyncHookResponses() {
        return asyncHookRegistry.checkForAsyncHookResponses();
    }

    @Override
    public void removeDeliveredAsyncHooks(List<String> processIds) {
        asyncHookRegistry.removeDeliveredAsyncHooks(processIds);
    }

    @Override
    public void finalizePendingAsyncHooks() {
        asyncHookRegistry.finalizePendingAsyncHooks();
    }

    /** A still-running bash hook subprocess plus its drain threads. */
    private record RunningBashHook(
        Process process,
        StringBuilder stderr,
        Thread stderrDrain,
        BufferedReader stdoutReader
    ) {}

    /**
     * Executes a PromptHook: calls an LLM to evaluate the prompt.
     */
    HookResult executePromptHook(PromptHook cmd, HookInput input, int defaultTimeoutSeconds) {
        return executePromptHookMillis(cmd, input, defaultTimeoutSeconds * 1000L);
    }

    private HookResult executePromptHookMillis(PromptHook cmd, HookInput input,
                                               long defaultTimeoutMillis) {
        String arguments = input.toJson();
        String resolvedPrompt = Strings.CS.contains(cmd.prompt(), "$ARGUMENTS")
            ? cmd.prompt().replace("$ARGUMENTS", arguments)
            : cmd.prompt() + "\n\nARGUMENTS: " + arguments;

        if (input.event() == HookEvent.STOP || input.event() == HookEvent.SUBAGENT_STOP) {
            return executeStopConditionPromptHook(
                cmd, resolvedPrompt, input, input.event() == HookEvent.STOP);
        }

        if (sideQuery == null) {
            LOG.debug("PromptHook: LLM client not configured, returning Allow with prompt");
            return new HookResult.Allow("PromptHook: " + resolvedPrompt);
        }

        try {
            String currentModel = currentLlmModel();
            String model = cmd.model().orElse(currentModel != null
                ? currentModel : "claude-sonnet-4-20250514");
            String promptText = buildPromptEvaluationPrompt(resolvedPrompt, input);
            String response = callStructuredHookLlm(
                promptText, model, cmd.timeoutSeconds()
                    .map(seconds -> seconds * 1000L)
                    .orElse(defaultTimeoutMillis));

            if (StringUtils.isBlank(response)) {
                enqueuePromptHookError(hookEventName(input), input, cmd.prompt(),
                    "JSON validation failed", response == null ? "" : response,
                    System.currentTimeMillis());
                return HookResult.skip();
            }

            return parsePromptHookOutput(response, cmd, input);

        } catch (Exception e) {
            LOG.warn("PromptHook execution failed: {}", e.getMessage());
            return HookResult.skip();
        }
    }


    private HookResult executeStopConditionPromptHook(PromptHook cmd, String condition,
                                                       HookInput input,
                                                       boolean impossibleIsTerminal) {
        if (sideQuery == null) {
            LOG.info("[goal] executeStopConditionPromptHook: sideQuery is null — goal evaluator SKIPPED (fail-open); advanced goal condition \"{}\" will NOT block stop",
              condition);
            return HookResult.skip();
        }
        LOG.info("[goal] executeStopConditionPromptHook: evaluating condition \"{}\" via side-query (model={})",
            condition, cmd.model().orElse(null));
        long startedAt = System.currentTimeMillis();
        String toolUseId = input.toolUseId().orElseGet(
            () -> UUID.randomUUID().toString());
        String currentModel = currentLlmModel();
        String model = cmd.model().orElse(currentModel != null
            ? currentModel : "claude-sonnet-4-20250514");
        List<Message> transcript = stopTranscriptSource();
        JsonNode format = stopConditionOutputFormat();
        long timeoutMillis = cmd.timeoutSeconds().orElse(30) * 1000L;
        String response;
        try {
            List<CreateMessageRequest.RequestMessage> requestMessages =
                stopTranscriptMessages(transcript, model, GOAL_TRANSCRIPT_FRACTION);
            response = queryStopCondition(
                model, condition, requestMessages, format, timeoutMillis);
        } catch (PromptTooLongException _) {
            List<CreateMessageRequest.RequestMessage> retryMessages =
                stopTranscriptMessages(transcript, model, GOAL_TRANSCRIPT_FRACTION / 2);
            LOG.debug("Stop PromptHook: prompt too long; retrying with {} messages",
                retryMessages.size());
            try {
                response = queryStopCondition(
                    model, condition, retryMessages, format, timeoutMillis);
            } catch (ApiException apiError) {
                enqueueStopHookError(cmd, input, toolUseId, startedAt,
                    "Hook evaluator API error: " + apiError.getMessage(), "");
                return HookResult.skip();
            } catch (RuntimeException failure) {
                enqueueStopHookError(cmd, input, toolUseId, startedAt,
                    "Error executing prompt hook: " + failure.getMessage(), "");
                return HookResult.skip();
            }
        } catch (ApiException apiError) {
            enqueueStopHookError(cmd, input, toolUseId, startedAt,
                "Hook evaluator API error: " + apiError.getMessage(), "");
            return HookResult.skip();
        } catch (RuntimeException failure) {
            enqueueStopHookError(cmd, input, toolUseId, startedAt,
                "Error executing prompt hook: " + failure.getMessage(), "");
            return HookResult.skip();
        }

        if (StringUtils.isBlank(response)) {
            enqueueStopHookError(cmd, input, toolUseId, startedAt,
                "JSON validation failed", response == null ? "" : response);
            return HookResult.skip();
        }
        JsonNode result = hookOutputParser.readJson(response);
        if (result == null) {
            enqueueStopHookError(cmd, input, toolUseId, startedAt,
                "JSON validation failed", response);
            return HookResult.skip();
        }
        String schemaFailure = stopConditionSchemaFailure(result);
        if (schemaFailure != null) {
            enqueueStopHookError(cmd, input, toolUseId, startedAt,
                "Schema validation failed: " + schemaFailure, response);
            return HookResult.skip();
        }
        String reason = result.path("reason").asText();
        LOG.info("[goal] evaluator response ok={} impossible={} reason=\"{}\" durationMs={}",
            result.path("ok").asBoolean(),
            result.path("impossible").asBoolean(false),
            reason,
            System.currentTimeMillis() - startedAt);
        if (result.path("ok").asBoolean()) {
            return new HookResult.ConditionMet(reason);
        }
        if (impossibleIsTerminal && result.path("impossible").asBoolean(false)) {
            return new HookResult.ConditionImpossible(reason);
        }
        // The evaluator sees the resolved prompt plus ARGUMENTS, but blocking
        // feedback and /goal state keep the user-authored condition itself.
        return new HookResult.ConditionNotMet(cmd.prompt(), reason);
    }

    private static JsonNode stopConditionOutputFormat() {
        var schema = JsonUtils.getMapper().createObjectNode();
        schema.put("type", "object");
        var properties = schema.putObject("properties");
        properties.putObject("ok").put("type", "boolean");
        properties.putObject("reason").put("type", "string");
        properties.putObject("impossible").put("type", "boolean");
        schema.putArray("required").add("ok").add("reason");
        schema.put("additionalProperties", false);
        var format = JsonUtils.getMapper().createObjectNode();
        format.put("type", "json_schema");
        format.set("schema", schema);
        return format;
    }

    private static String stopConditionSchemaFailure(JsonNode result) {
        if (!result.isObject()) return "expected an object";
        if (!result.path("ok").isBoolean()) return "ok must be a boolean";
        if (!result.path("reason").isTextual()) return "reason must be a string";
        if (result.has("impossible") && !result.path("impossible").isBoolean()) {
            return "impossible must be a boolean";
        }
        return null;
    }

    private void enqueueStopHookError(PromptHook cmd, HookInput input,
                                      String toolUseId, long startedAt,
                                      String stderr, String stdout) {
        HookNonBlockingErrorAttachment payload = new HookNonBlockingErrorAttachment(
            hookEventName(input), stderr, stdout == null ? "" : stdout, 1,
            toolUseId, input.event().displayName(), cmd.prompt(),
            Math.max(0L, System.currentTimeMillis() - startedAt));
        hookMessages.add(new AttachmentMessage(UUID.randomUUID().toString(), payload));
        if (StringUtils.isNotBlank(stderr)) {
            LOG.warn("Stop PromptHook execution failed: {}", stderr);
        }
    }

    private String queryStopCondition(String model, String condition,
                                      List<CreateMessageRequest.RequestMessage> transcript,
                                      JsonNode format, long timeoutMillis) {
        List<CreateMessageRequest.RequestMessage> messages = new ArrayList<>(transcript);
        messages.add(new CreateMessageRequest.RequestMessage("user",
            "Based on the conversation transcript above, has the following stopping "
                + "condition been satisfied? Answer based on transcript evidence only.\n\n"
                + "Condition: " + condition));
        String identity = currentGoalIdentity();
        String systemPrompt = StringUtils.isBlank(identity)
            ? STOP_CONDITION_SYSTEM_PROMPT
            : identity + "\n\n" + STOP_CONDITION_SYSTEM_PROMPT;
        String effort = currentGoalEffort();
        return sideQuery.queryTextOrThrow(new SideQuery.Request()
            .model(model)
            .systemPrompt(systemPrompt)
            .messages(messages)
            .maxTokens(Math.toIntExact(ModelOutputTokens.getMaxOutputTokensForModel(model)))
            .timeoutMillis(timeoutMillis)
            .thinking(CreateMessageRequest.ThinkingConfig.disabled())
            .outputConfig(new CreateMessageRequest.OutputConfig(effort, format))
            .metadata(currentGoalMetadata())
            .tools(currentGoalTools())
            .temperature(1.0)
            .streaming(true)
            .querySource("hook_prompt"));
    }

    private List<Message> stopTranscriptSource() {
        List<Message> messages;
        try {
            messages = messagesSupplier != null ? messagesSupplier.get() : List.of();
        } catch (Throwable _) {
            messages = List.of();
        }
        return messages == null ? List.of() : List.copyOf(messages);
    }


    private List<CreateMessageRequest.RequestMessage> stopTranscriptMessages(
            List<Message> messages, String model, double fraction) {
        if (messages == null || messages.isEmpty()) return List.of();
        long contextWindow;
        try {
            contextWindow = goalContextWindowResolver.applyAsLong(model);
        } catch (RuntimeException _) {
            contextWindow = DEFAULT_GOAL_CONTEXT_WINDOW;
        }
        if (contextWindow <= 0L) contextWindow = DEFAULT_GOAL_CONTEXT_WINDOW;
        long budget = (long) Math.floor(contextWindow * fraction);
        List<Message> retained = truncateStopTranscript(messages, model, budget);
        List<StreamingClient.StreamRequest.RequestMessage> wire = new ArrayList<>();
        int omitted = messages.size() - retained.size();
        if (omitted > 0) {
            wire.add(new StreamingClient.StreamRequest.RequestMessage("user",
                "[Earlier conversation truncated to fit the hook evaluator's context window — "
                    + omitted + " earlier messages omitted. Evaluate the condition against the "
                    + "recent transcript below; if the required evidence may be in the omitted "
                    + "prefix, return {\"ok\": false, \"reason\": \"insufficient evidence in "
                    + "transcript\"}.]"));
            LOG.debug("Hooks: truncated Stop transcript {}→{} messages (budget {}, model {})",
                messages.size(), retained.size(), budget, model);
        }
        wire.addAll(ApiMessageFormatter.toRequestMessages(retained, false));
        List<StreamingClient.StreamRequest.RequestMessage> normalized =
            RequestMessageNormalizer.mergeConsecutiveRequestMessages(wire);
        List<CreateMessageRequest.RequestMessage> result = new ArrayList<>();
        for (StreamingClient.StreamRequest.RequestMessage message : normalized) {
            if (!Strings.CS.equals("user", message.role()) && !Strings.CS.equals("assistant", message.role())) continue;
            result.add(new CreateMessageRequest.RequestMessage(message.role(), message.content()));
        }
        return List.copyOf(result);
    }

    private static List<Message> truncateStopTranscript(
            List<Message> messages, String model, long budget) {
        if (latestAssistantUsage(messages, model) <= budget) return List.copyOf(messages);
        List<List<Message>> groups = groupAssistantResponses(messages);
        long tokens = 0L;
        int start = groups.size();
        for (int i = groups.size() - 1; i >= 0; i--) {
            long groupTokens = estimateGroupTokens(groups.get(i));
            if (start < groups.size() && tokens + groupTokens > budget) break;
            tokens += groupTokens;
            start = i;
        }
        List<Message> retained = new ArrayList<>();
        for (int i = start; i < groups.size(); i++) retained.addAll(groups.get(i));
        return List.copyOf(retained);
    }

    private static long latestAssistantUsage(List<Message> messages, String model) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AssistantMessage assistant
                    && assistant.message() != null && assistant.message().usage() != null) {
                Usage usage = assistant.message().usage();
                return TokenEstimator.contextTokens(usage, model);
            }
        }
        return 0L;
    }

    private static List<List<Message>> groupAssistantResponses(List<Message> messages) {
        List<List<Message>> groups = new ArrayList<>();
        List<Message> current = new ArrayList<>();
        String previousAssistantId = null;
        for (Message message : messages) {
            if (message instanceof AssistantMessage assistant) {
                String id = assistant.message() != null ? assistant.message().id() : null;
                if (!Objects.equals(id, previousAssistantId) && !current.isEmpty()) {
                    groups.add(List.copyOf(current));
                    current.clear();
                }
                previousAssistantId = id;
            }
            current.add(message);
        }
        if (!current.isEmpty()) groups.add(List.copyOf(current));
        return List.copyOf(groups);
    }

    private static long estimateGroupTokens(List<Message> group) {
        TokenEstimator estimator = TokenEstimator.getInstance();
        long characters = 0L;
        for (Message message : group) {
            long messageChars = estimator.estimateMessageChars(message);
            if (messageChars == 0L) {
                try {
                    messageChars = JsonUtils.getMapper().writeValueAsString(message).length();
                } catch (Exception _) {
                    messageChars = String.valueOf(message).length();
                }
            }
            characters += messageChars;
        }
        return Math.max(1L, Math.round(characters / 4.0));
    }

    /**
     * Executes an AgentHook: launches an agent verifier.
     */
    HookResult executeAgentHook(AgentHook cmd, HookInput input, int defaultTimeoutSeconds) {
        return executeAgentHookMillis(cmd, input, defaultTimeoutSeconds * 1000L);
    }

    private HookResult executeAgentHookMillis(AgentHook cmd, HookInput input,
                                              long defaultTimeoutMillis) {
        String resolvedPrompt = cmd.prompt().replace("$ARGUMENTS", input.toJson());

        if (agentHookFactory != null) {
            return executeVerifierAgentHook(cmd, input, resolvedPrompt, defaultTimeoutMillis);
        }

        if (sideQuery == null) {
            LOG.debug("AgentHook: LLM client not configured, returning Allow with prompt");
            return new HookResult.Allow("AgentHook: " + resolvedPrompt);
        }

        try {
            String model = cmd.model().orElse(llmModel != null ? llmModel : "claude-sonnet-4-20250514");
            String verificationPrompt = buildAgentVerificationPrompt(resolvedPrompt, input);
            String response = callStructuredHookLlm(
                verificationPrompt, model, cmd.timeoutSeconds()
                    .map(seconds -> seconds * 1000L)
                    .orElse(defaultTimeoutMillis));

            if (StringUtils.isBlank(response)) {
                enqueuePromptHookError(hookEventName(input), input, cmd.prompt(),
                    "JSON validation failed", response == null ? "" : response,
                    System.currentTimeMillis());
                return HookResult.skip();
            }

            return parsePromptHookOutput(response, cmd, input);

        } catch (Exception e) {
            LOG.warn("AgentHook execution failed: {}", e.getMessage());
            return HookResult.skip();
        }
    }


    private HookResult executeVerifierAgentHook(AgentHook cmd, HookInput input,
                                                String resolvedPrompt,
                                                long defaultTimeoutMillis) {
        long timeoutMillis = cmd.timeoutSeconds()
            .map(seconds -> seconds * 1000L)
            .orElse(defaultTimeoutMillis > 0 ? defaultTimeoutMillis : 60_000L);
        String transcriptPath = String.valueOf(
            input.extra().getOrDefault("transcript_path", ""));
        String event = input.event().displayName();
        String systemPrompt = (input.event() == HookEvent.STOP
                || input.event() == HookEvent.SUBAGENT_STOP
            ? "You are verifying a stop condition in Claude Code. Your task is to verify "
                + "that the agent completed the given plan."
            : "You are evaluating a " + event + " hook in Claude Code. Your task is to "
                + "evaluate the condition described in the user message.")
            + " The conversation transcript is available at: " + transcriptPath + "\n"
            + "Use the available tools to inspect the transcript and workspace as needed. "
            + "Return the verification result exactly once through the structured-output tool.";
        AbortController abort = new AbortController();
        JsonNode schema = promptHookOutputFormat().path("schema").deepCopy();
        SubAgentRequest request = SubAgentRequest.builder()
            .prompt(resolvedPrompt)
            .subagentType("hook-agent")
            .disallowedTools(List.of("Agent", "ExitPlanMode"))
            .model(cmd.model().orElse(currentLlmModel()))
            .permissionMode(PermissionMode.DONT_ASK)
            .cwd(resolveCwd())
            .maxTurns(50)
            .jsonSchema(schema)
            .systemPromptOverride(systemPrompt)
            .abortController(abort)
            .description("Verify hook condition")
            .build();
        CompletableFuture<SubAgentResult> future = CompletableFuture.supplyAsync(
            () -> agentHookFactory.runSubAgent(request),
            runnable -> Thread.ofVirtual().start(runnable));
        try {
            SubAgentResult result = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (result == null || result.isError() || StringUtils.isBlank(result.output())) {
                return HookResult.skip();
            }
            return parsePromptHookOutput(result.output(), cmd, input);
        } catch (TimeoutException _) {
            abort.abort("Agent hook timed out");
            future.cancel(true);
            return HookResult.skip();
        } catch (Exception failure) {
            abort.abort("Agent hook failed");
            LOG.warn("AgentHook verifier failed: {}", failure.getMessage());
            return HookResult.skip();
        }
    }


    private String callStructuredHookLlm(String prompt, String model, long timeoutMillis) {
        if (sideQuery == null) return null;
        String systemPrompt = """
            You are evaluating a hook in Claude Code.

            Your response must be a JSON object with one of these shapes:
            - {"ok": true}
            - {"ok": false, "reason": "Reason for why it is not met"}
            Return only the JSON object.
            """;
        return sideQuery.queryTextOrThrow(new SideQuery.Request()
            .model(model)
            .systemPrompt(systemPrompt)
            .userPrompt(prompt)
            .maxTokens(1024)
            .timeoutMillis(timeoutMillis)
            .thinking(CreateMessageRequest.ThinkingConfig.disabled())
            .outputConfig(new CreateMessageRequest.OutputConfig(null, promptHookOutputFormat()))
            .querySource("hook_prompt"));
    }

    private static JsonNode promptHookOutputFormat() {
        var schema = JsonUtils.getMapper().createObjectNode();
        schema.put("type", "object");
        var properties = schema.putObject("properties");
        properties.putObject("ok").put("type", "boolean");
        properties.putObject("reason").put("type", "string");
        schema.putArray("required").add("ok");
        schema.put("additionalProperties", false);
        var format = JsonUtils.getMapper().createObjectNode();
        format.put("type", "json_schema");
        format.set("schema", schema);
        return format;
    }

    /**
     * Builds a prompt for LLM-based hook evaluation.
     */
    private String buildPromptEvaluationPrompt(String hookPrompt, HookInput input) {
      return
          "Evaluate the following hook prompt and determine if the operation should be allowed or blocked.\n\n"
              + "Hook Prompt:\n" + hookPrompt + "\n\n"
              + "Context:\n" + input.toJson() + "\n\n"
              + "Return only a JSON object with:\n"
              + "- ok: true if the condition is satisfied, false otherwise\n"
              + "- reason: optional explanation when ok is false\n\n"
              + "Example: {\"ok\":true}";
    }

    /**
     * Builds a prompt for agent-based verification.
     */
    private String buildAgentVerificationPrompt(String agentPrompt, HookInput input) {
      return "You are verifying an action taken by Claude Code.\n\n"
          + "Verification Task:\n" + agentPrompt + "\n\n"
          + "Action Context:\n" + input.toJson() + "\n\n"
          + "Verify whether the action was performed correctly and safely.\n"
          + "Return only a JSON object with:\n"
          + "- ok: true if the action is verified, false if it is not\n"
          + "- reason: optional explanation when ok is false\n\n"
          + "Example: {\"ok\":true}";
    }

    /**
     * Executes an HttpHook: POSTs hook input JSON to the configured URL.
     */
    HookResult executeHttpHook(HttpHook cmd, HookInput input, int defaultTimeoutSeconds) {
        return executeHttpHookMillis(cmd, input, defaultTimeoutSeconds * 1000L);
    }

    private HookResult executeHttpHookMillis(HttpHook cmd, HookInput input,
                                             long defaultTimeoutMillis) {
        long timeoutMillis = cmd.timeoutSeconds()
            .map(seconds -> seconds * 1000L)
            .orElse(defaultTimeoutMillis);

        HttpHookPolicy policy = httpHookPolicy;
        if (!policy.allowsUrl(cmd.url())) {
            LOG.warn("HTTP hook blocked: {} does not match any pattern in allowedHttpHookUrls",
                cmd.url());
            return HookResult.skip();
        }

        try {
            Request.Builder reqBuilder = new Request.Builder()
                .url(cmd.url())
                .header("Content-Type", "application/json")
                .post(RequestBody.create(input.toJson(), MediaType.get("application/json")));

            // Add resolved headers (with env var interpolation)
            Map<String, String> headers = cmd.resolvedHeaders(
                Set.copyOf(policy.effectiveEnvVars(cmd.allowedEnvVars())));
            headers.forEach(reqBuilder::header);

            OkHttpClient client = managedHttpClient
                ? ServiceHttpClient.forHook(cmd.url(), sandboxProxyEnvironmentSupplier.get())
                : httpClient;
            try (Response response = HttpCalls.execute(
                    client, reqBuilder.build(), Duration.ofMillis(timeoutMillis))) {
                if (response.code() >= 400) {
                    LOG.debug("HTTP hook returned status {}", response.code());
                    return HookResult.skip();
                }

                String body = response.body().string();
                if (StringUtils.isBlank(body)) {
                    return HookResult.allow();
                }
                return parseHookOutput(body, input.event());
            }
        } catch (Exception e) {
            LOG.debug("HTTP hook execution error: {}", e.getMessage());
            return HookResult.skip();
        }
    }

    // ---- Output parsing ----

    HookResult parseHookOutput(String output) {
        return hookOutputParser.parse(output);
    }

    private HookResult parseHookOutput(String output, HookEvent expectedEvent) {
        return hookOutputParser.parse(output, expectedEvent);
    }

    /** Parses the strict ordinary Prompt/Agent Hook {@code {ok,reason}} contract. */
    private HookResult parsePromptHookOutput(String output, HookCommand command, HookInput input) {
        long startedAt = System.currentTimeMillis();
        String hookName = hookEventName(input);
        String commandText = command instanceof PromptHook p ? p.prompt()
            : command instanceof AgentHook a ? a.prompt() : command.toString();
        HookOutputParser.PromptDecision decision = hookOutputParser.parsePromptDecision(output);
        if (!decision.valid()) {
            enqueuePromptHookError(hookName, input, commandText,
                decision.failure(), output, startedAt);
            return HookResult.skip();
        }
        if (!decision.allowed()) {
            return new HookResult.Block(decision.reason());
        }
        return HookResult.allow();
    }

    private void enqueuePromptHookError(String hookName, HookInput input, String command,
                                        String stderr, String stdout, long startedAt) {
        HookNonBlockingErrorAttachment payload = new HookNonBlockingErrorAttachment(
            hookName, stderr, stdout == null ? "" : stdout, 1,
            input.toolUseId().orElseGet(() -> UUID.randomUUID().toString()),
            input.event().displayName(), command,
            Math.max(0L, System.currentTimeMillis() - startedAt));
        hookMessages.add(new AttachmentMessage(UUID.randomUUID().toString(), payload));
    }

    // ---- Helpers ----

    private String hookIdentity(HookMatchResolver.MatchedHook hook) {
        return hook.command().getClass().getSimpleName() + ":" +
            switch (hook.command()) {
                case BashCommandHook cmd -> cmd.command();
                case PromptHook cmd -> cmd.prompt();
                case HttpHook cmd -> cmd.url();
                case AgentHook cmd -> cmd.prompt();
                case CallbackHook cmd -> cmd.callbackId();
            };
    }

}
