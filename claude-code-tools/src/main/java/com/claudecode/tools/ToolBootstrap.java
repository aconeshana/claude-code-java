package com.claudecode.tools;

import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.SubAgentCompactServiceFactory;
import com.claudecode.core.engine.SubAgentLifecycleListener;
import com.claudecode.core.engine.SubAgentProgressSummarizer;
import com.claudecode.core.engine.ToolExecutor;
import com.claudecode.core.message.SkillListingEntry;
import com.claudecode.core.feature.FeatureGate;
import com.claudecode.tools.tasks.TeamCreateTool;
import com.claudecode.tools.tasks.TeamDeleteTool;
import com.claudecode.tools.skills.DynamicSkillDiscovery;
import com.claudecode.tools.skills.Skill;
import com.claudecode.core.process.SubprocessEnvironment;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.List;
import com.claudecode.tools.agent.AgentTool;
import com.claudecode.runtime.query.QuerySessionFactory;
import com.claudecode.tools.agent.DefaultSubAgentFactory;
import com.claudecode.tools.agent.SubAgentFactory;
import com.claudecode.tools.bash.BashTool;
import com.claudecode.tools.cron.ScheduleWakeupTool;
import com.claudecode.tools.files.FileEditTool;
import com.claudecode.tools.files.FileReadTool;
import com.claudecode.tools.files.FileWriteTool;
import com.claudecode.tools.files.GlobTool;
import com.claudecode.tools.files.GrepTool;
import com.claudecode.tools.files.NotebookEditTool;
import com.claudecode.tools.messaging.SendMessageTool;
import com.claudecode.tools.monitor.MonitorTool;
import com.claudecode.tools.questions.AskUserQuestionTool;
import com.claudecode.tools.sandbox.PlatformSandboxManager;
import com.claudecode.tools.tasks.TodoWriteTool;
import com.claudecode.tools.web.WebBrowserTool;
import com.claudecode.tools.web.WebFetchTool;
import com.claudecode.tools.web.WebSearchTool;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Single source of truth for registering the built-in tool implementations onto a {@link
 * ToolRegistry}.
 */
public final class ToolBootstrap {

    private ToolBootstrap() {}

    /**
     * Registers every built-in tool onto {@code registry}.
     *
     * @param client   the LLM client for sub-agent tools (Agent). May be
     *                 {@code null} only for non-runtime callers (tests) — when
     *                 both {@code client} and {@code executor} are null a
     *                 no-op sub-agent factory is used; the tool's
     *                 {@code inputSchema} is identical either way.
     * @param executor the tool executor delegated to by sub-agent tools. Same
     *                 null-tolerance rule as {@code client}.
     */
    public static void registerBuiltInTools(ToolRegistry registry,
                                            StreamingClient client,
                                            ToolExecutor executor) {
        registerBuiltInTools(registry, client, executor, null);
    }

    /**
     * Like {@link #registerBuiltInTools(ToolRegistry, StreamingClient, ToolExecutor)}
     * but also threads a {@link SubAgentProgressSummarizer} (core interface) into the
     * Agent tool so the sub-agent progress summarizer can run. The concrete
     * implementation lives in services and is injected by the composition root.
     * Pass {@code null} to disable the summarizer (the 3-arg overload does exactly this).
     */
    public static void registerBuiltInTools(ToolRegistry registry,
                                            StreamingClient client,
                                            ToolExecutor executor,
                                            SubAgentProgressSummarizer summarizer) {
        registerBuiltInTools(registry, client, executor, summarizer, null);
    }

    /**
     * Like {@link #registerBuiltInTools(ToolRegistry, StreamingClient, ToolExecutor,
     * SubAgentProgressSummarizer)} but also threads the composition root's shared {@link
     * SessionIdentity} into the Agent tool so every sub-agent's QuerySession observes the same session
     * id as the main loop.
     */
    public static void registerBuiltInTools(ToolRegistry registry,
                                            StreamingClient client,
                                            ToolExecutor executor,
                                            SubAgentProgressSummarizer summarizer,
                                            SessionIdentity sessionIdentity) {
        registerBuiltInTools(registry, client, executor, summarizer, sessionIdentity, null);
    }

    /**
     * Like {@link #registerBuiltInTools(ToolRegistry, StreamingClient, ToolExecutor, SubAgentProgressSummarizer, SessionIdentity)}
     * but also threads a {@link SubAgentLifecycleListener} (core interface) into
     * the Agent tool, fired when each sub-agent finishes so higher layers can
     * release per-agent resources (e.g. prompt-cache-break tracking's
     * {@code cleanupAgentTracking}). The concrete implementation lives in
     * services and is injected by the composition root. Pass {@code null} to
     * fall back to the 5-arg behaviour.
     */
    public static void registerBuiltInTools(ToolRegistry registry,
                                            StreamingClient client,
                                            ToolExecutor executor,
                                            SubAgentProgressSummarizer summarizer,
                                            SessionIdentity sessionIdentity,
                                            SubAgentLifecycleListener lifecycleListener) {
        registerBuiltInTools(registry, client, executor, summarizer, sessionIdentity, lifecycleListener, null);
    }


    public static void registerBuiltInTools(ToolRegistry registry,
                                            StreamingClient client,
                                            ToolExecutor executor,
                                            SubAgentProgressSummarizer summarizer,
                                            SessionIdentity sessionIdentity,
                                            SubAgentLifecycleListener lifecycleListener,
                                            SubAgentCompactServiceFactory compactFactory) {
        registerBuiltInTools(registry, client, executor, summarizer, sessionIdentity,
            lifecycleListener, compactFactory, null);
    }

    /** Full composition-root overload including session-scoped dynamic Skills. */
    public static void registerBuiltInTools(ToolRegistry registry,
                                            StreamingClient client,
                                            ToolExecutor executor,
                                            SubAgentProgressSummarizer summarizer,
                                            SessionIdentity sessionIdentity,
                                            SubAgentLifecycleListener lifecycleListener,
                                            SubAgentCompactServiceFactory compactFactory,
                                            DynamicSkillDiscovery dynamicSkillDiscovery) {
        registerBuiltInTools(registry, client, executor, summarizer, sessionIdentity,
            lifecycleListener, compactFactory, dynamicSkillDiscovery, () -> true);
    }

    /** Full composition-root overload including the live git-instructions gate. */
    public static void registerBuiltInTools(ToolRegistry registry,
                                            StreamingClient client,
                                            ToolExecutor executor,
                                            SubAgentProgressSummarizer summarizer,
                                            SessionIdentity sessionIdentity,
                                            SubAgentLifecycleListener lifecycleListener,
                                            SubAgentCompactServiceFactory compactFactory,
                                            DynamicSkillDiscovery dynamicSkillDiscovery,
                                            Supplier<Boolean> includeGitInstructionsSupplier) {
        registerBuiltInTools(registry, client, executor, summarizer, sessionIdentity,
            lifecycleListener, compactFactory, dynamicSkillDiscovery,
            includeGitInstructionsSupplier, null);
    }

    /** Full composition-root overload including the live Skill inventory. */
    public static void registerBuiltInTools(ToolRegistry registry,
                                            StreamingClient client,
                                            ToolExecutor executor,
                                            SubAgentProgressSummarizer summarizer,
                                            SessionIdentity sessionIdentity,
                                            SubAgentLifecycleListener lifecycleListener,
                                            SubAgentCompactServiceFactory compactFactory,
                                            DynamicSkillDiscovery dynamicSkillDiscovery,
                                            Supplier<Boolean> includeGitInstructionsSupplier,
                                            Supplier<List<SkillListingEntry>> skillListingSupplier) {
        registerBuiltInTools(registry, client, executor, summarizer, sessionIdentity,
            lifecycleListener, compactFactory, dynamicSkillDiscovery,
            includeGitInstructionsSupplier, skillListingSupplier, null);
    }

    /** Full composition-root overload including skill bodies for agent preloading. */
    public static void registerBuiltInTools(ToolRegistry registry,
                                            StreamingClient client,
                                            ToolExecutor executor,
                                            SubAgentProgressSummarizer summarizer,
                                            SessionIdentity sessionIdentity,
                                            SubAgentLifecycleListener lifecycleListener,
                                            SubAgentCompactServiceFactory compactFactory,
                                            DynamicSkillDiscovery dynamicSkillDiscovery,
                                            Supplier<Boolean> includeGitInstructionsSupplier,
                                            Supplier<List<SkillListingEntry>> skillListingSupplier,
                                            Supplier<List<Skill>> skillSupplier) {
        registerBuiltInTools(registry, client, executor, summarizer, sessionIdentity,
            lifecycleListener, compactFactory, dynamicSkillDiscovery,
            includeGitInstructionsSupplier, skillListingSupplier, skillSupplier, null);
    }

    /** Full composition-root overload including all-scope sub-agent memory loading. */
    public static void registerBuiltInTools(ToolRegistry registry,
                                            StreamingClient client,
                                            ToolExecutor executor,
                                            SubAgentProgressSummarizer summarizer,
                                            SessionIdentity sessionIdentity,
                                            SubAgentLifecycleListener lifecycleListener,
                                            SubAgentCompactServiceFactory compactFactory,
                                            DynamicSkillDiscovery dynamicSkillDiscovery,
                                            Supplier<Boolean> includeGitInstructionsSupplier,
                                            Supplier<List<SkillListingEntry>> skillListingSupplier,
                                            Supplier<List<Skill>> skillSupplier,
                                            Function<Path, String> claudeMdContentLoader) {
        registerBuiltInTools(registry, client, executor, summarizer, sessionIdentity,
            lifecycleListener, compactFactory, dynamicSkillDiscovery,
            includeGitInstructionsSupplier, skillListingSupplier, skillSupplier,
            claudeMdContentLoader, null);
    }

    /** Full composition-root overload including the effective WebFetch setting gate. */
    public static void registerBuiltInTools(
                                            ToolRegistry registry,
                                            StreamingClient client,
                                            ToolExecutor executor,
                                            SubAgentProgressSummarizer summarizer,
                                            SessionIdentity sessionIdentity,
                                            SubAgentLifecycleListener lifecycleListener,
                                            SubAgentCompactServiceFactory compactFactory,
                                            DynamicSkillDiscovery dynamicSkillDiscovery,
                                            Supplier<Boolean> includeGitInstructionsSupplier,
                                            Supplier<List<SkillListingEntry>> skillListingSupplier,
                                            Supplier<List<Skill>> skillSupplier,
                                            Function<Path, String> claudeMdContentLoader,
                                            Supplier<Boolean> skipWebFetchPreflightSupplier) {
        registerBuiltInTools(registry, client, executor, summarizer, sessionIdentity,
            lifecycleListener, compactFactory, dynamicSkillDiscovery,
            includeGitInstructionsSupplier, skillListingSupplier, skillSupplier,
            claudeMdContentLoader, skipWebFetchPreflightSupplier, false, () -> null);
    }

    /** Complete production wiring, including the guide agent's live prompt context. */
    public static void registerBuiltInTools(
                                            ToolRegistry registry,
                                            StreamingClient client,
                                            ToolExecutor executor,
                                            SubAgentProgressSummarizer summarizer,
                                            SessionIdentity sessionIdentity,
                                            SubAgentLifecycleListener lifecycleListener,
                                            SubAgentCompactServiceFactory compactFactory,
                                            DynamicSkillDiscovery dynamicSkillDiscovery,
                                            Supplier<Boolean> includeGitInstructionsSupplier,
                                            Supplier<List<SkillListingEntry>> skillListingSupplier,
                                            Supplier<List<Skill>> skillSupplier,
                                            Function<Path, String> claudeMdContentLoader,
                                            Supplier<Boolean> skipWebFetchPreflightSupplier,
                                            boolean usingThirdPartyServices,
                                            Supplier<JsonNode> effectiveSettingsSupplier) {
        registerBuiltInTools(registry, client, executor, summarizer, sessionIdentity,
            lifecycleListener, compactFactory, dynamicSkillDiscovery,
            includeGitInstructionsSupplier, skillListingSupplier, skillSupplier,
            claudeMdContentLoader, skipWebFetchPreflightSupplier, usingThirdPartyServices,
            effectiveSettingsSupplier, null);
    }

    /** Complete production wiring with the composition-root query factory. */
    public static void registerBuiltInTools(
                                            ToolRegistry registry,
                                            StreamingClient client,
                                            ToolExecutor executor,
                                            SubAgentProgressSummarizer summarizer,
                                            SessionIdentity sessionIdentity,
                                            SubAgentLifecycleListener lifecycleListener,
                                            SubAgentCompactServiceFactory compactFactory,
                                            DynamicSkillDiscovery dynamicSkillDiscovery,
                                            Supplier<Boolean> includeGitInstructionsSupplier,
                                            Supplier<List<SkillListingEntry>> skillListingSupplier,
                                            Supplier<List<Skill>> skillSupplier,
                                            Function<Path, String> claudeMdContentLoader,
                                            Supplier<Boolean> skipWebFetchPreflightSupplier,
                                            boolean usingThirdPartyServices,
                                            Supplier<JsonNode> effectiveSettingsSupplier,
                                            QuerySessionFactory querySessionFactory) {
        BashTool bashTool = new BashTool(SubprocessEnvironment::get, PlatformSandboxManager.create(),
            includeGitInstructionsSupplier);
        bashTool.setAttributionSettingsSupplier(effectiveSettingsSupplier);
        registry.register(bashTool);
        registry.register(new FileReadTool(dynamicSkillDiscovery));
        registry.register(new FileWriteTool(dynamicSkillDiscovery));
        registry.register(new FileEditTool(dynamicSkillDiscovery));
        registry.register(new NotebookEditTool());






        registry.register(new GlobTool());
        registry.register(new GrepTool());

// client is the same authenticated StreamingClient the main loop uses — required
// for the secondary LLM summary pass.
        registry.register(new WebFetchTool(client, ToolHttpClient.webFetch(),
            skipWebFetchPreflightSupplier));
        registry.register(new WebSearchTool(client));

        // Agent & SubAgent — uses the real constructor in production, and
        // a no-op factory when no runtime clients are supplied (tests).
        SubAgentFactory subAgentFactory = client != null && executor != null
            ? new DefaultSubAgentFactory(client, executor, System.getProperty("user.dir"),
                summarizer, sessionIdentity, lifecycleListener, compactFactory,
                skillListingSupplier, skillSupplier, includeGitInstructionsSupplier,
                claudeMdContentLoader, usingThirdPartyServices, effectiveSettingsSupplier)
            : null;
        if (subAgentFactory instanceof DefaultSubAgentFactory defaultFactory
                && querySessionFactory != null) {
            defaultFactory.setQuerySessionFactory(querySessionFactory);
        }
        registry.register(subAgentFactory != null
            ? new AgentTool(subAgentFactory)
            : new AgentTool());

        registry.register(new AskUserQuestionTool());

        registry.register(new MonitorTool());

        registry.register(new ScheduleWakeupTool());




        registry.register(new TodoWriteTool());

        registry.register(subAgentFactory != null
            ? new SendMessageTool(subAgentFactory)
            : new SendMessageTool());

        // Team task list provisioning for agent-teams (gated by AgentTeamsEnabled in

        registry.register(new TeamCreateTool());
        registry.register(new TeamDeleteTool());



        // the gate is on (env CLAUDE_CODE_FEATURE_WEB_BROWSER_TOOL=1/true). Default



// coverage.yml.
        if (FeatureGate.isEnabled(FeatureGate.Flag.WEB_BROWSER_TOOL)) {
            registry.register(new WebBrowserTool());
        }
    }

    /** Convenience for non-runtime callers (tests) that only need schemas. */
    public static ToolRegistry buildBuiltInRegistry() {
        ToolRegistry registry = new ToolRegistry();
        registerBuiltInTools(registry, null, null);
        return registry;
    }
}
