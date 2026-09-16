package com.claudecode.services.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.apache.commons.lang3.StringUtils;

/**
 * The five independently mutable hook source layers, in their established
 * precedence order: settings files, plugins, SDK callbacks, per-invocation
 * skill hooks, and session-persistent hooks. Owns reload and lifecycle of each
 * layer; matching itself is delegated to the stateless {@link HookMatchResolver}.
 *
 * <ul>
 *   <li>{@code src/utils/hooks/hooksConfigSnapshot.ts} — settings-layer
 *       snapshot replaced atomically on hot reload.</li>
 *   <li>{@code src/utils/plugins/loadPluginHooks.ts} — plugin-contributed hook
 *       layer kept independent from user settings.</li>
 *   <li>{@code src/utils/hooks/registerSkillHooks.ts} — per-invocation skill
 *       hooks plus their {@code CLAUDE_PLUGIN_ROOT} directory.</li>
 *   <li>{@code src/utils/hooks/registerFrontmatterHooks.ts} — agent
 *       frontmatter hooks scoped to one child dispatcher.</li>
 *   <li>{@code src/utils/hooks/sessionHooks.ts} — session-persistent hooks
 *       (the /goal Stop hook) that survive turn-end cleanup.</li>
 *   <li>{@code src/utils/hooks.ts} — {@code once} de-duplication across the
 *       session.</li>
 * </ul>
 */
public final class HookRegistry {

    private volatile HooksSettings settings;
    private volatile Map<HookEvent, List<HookMatcher>> pluginHooks = Map.of();
    private volatile Map<HookEvent, List<HookMatcher>> sdkHooks = Map.of();
    private final ConcurrentHashMap<HookEvent, List<HookMatcher>> extraHooks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<HookEvent, List<HookMatcher>> sessionHooks = new ConcurrentHashMap<>();
    /**
     * Skill root associated with each per-invocation hook command. Identity
     * semantics avoid accidentally applying a skill root to an equal-looking
     * hook from base settings or another skill.
     */
    private final Map<HookCommand, Path> extraHookRoots =
        Collections.synchronizedMap(new IdentityHashMap<>());
    private final Set<String> executedOnceHooks = ConcurrentHashMap.newKeySet();
    private final HookMatchResolver resolver = new HookMatchResolver();

    public HookRegistry(HooksSettings settings) {
        this.settings = settings != null ? settings : HooksSettings.EMPTY;
    }

    /**
     * Registry for one child agent invocation: the settings, plugin, and SDK
     * layers are inherited by value, the per-invocation layer starts empty, and
     * the child's frontmatter hooks become its session layer with {@code Stop}
     * rescoped to {@code SubagentStop}.
     */
    HookRegistry forChild(JsonNode frontmatterHooks) {
        HookRegistry child = new HookRegistry(settings);
        child.pluginHooks = pluginHooks;
        child.sdkHooks = sdkHooks;
        HooksSettings frontmatter = HooksSettings.fromJson(frontmatterHooks);
        frontmatter.eventHooks().forEach((event, matchers) -> {
            HookEvent scopedEvent = event == HookEvent.STOP ? HookEvent.SUBAGENT_STOP : event;
            child.installSessionHooks(scopedEvent, matchers);
        });
        return child;
    }

    // ---- settings layer ----

    /** Replaces the base settings (from settings.json and its project / local siblings). */
    public void replaceSettings(HooksSettings newSettings) {
        this.settings = newSettings != null ? newSettings : HooksSettings.EMPTY;
    }

    /** Currently active base settings. Public for hot-reload/config UI. */
    public HooksSettings currentSettings() {
        return settings;
    }

    // ---- plugin layer ----

    /** Replaces the plugin-contributed hooks in one atomic swap. */
    public void setPluginHooks(Map<HookEvent, List<HookMatcher>> hooks) {
        this.pluginHooks = hooks == null ? Map.of() : Map.copyOf(hooks);
    }

    /** Currently registered plugin hooks (read-only view). */
    public Map<HookEvent, List<HookMatcher>> currentPluginHooks() {
        EnumMap<HookEvent, List<HookMatcher>> snapshot = new EnumMap<>(HookEvent.class);
        pluginHooks.forEach((event, matchers) -> snapshot.put(event, List.copyOf(matchers)));
        return Collections.unmodifiableMap(snapshot);
    }

    // ---- SDK layer ----

    /** Atomically replaces callbacks supplied by SDK initialize.hooks. */
    public void setSdkHooks(Map<HookEvent, List<HookMatcher>> hooks) {
        this.sdkHooks = hooks == null ? Map.of() : Map.copyOf(hooks);
    }

    public Map<HookEvent, List<HookMatcher>> currentSdkHooks() {
        return sdkHooks;
    }

    // ---- per-invocation layer ----

    /** Merges additional hooks from a skill's frontmatter into the per-turn extra-hooks layer. */
    public void addExtraHooks(HooksSettings extra) {
        addExtraHooks(extra, null);
    }

    /**
     * Registers skill hooks together with the directory exposed to their subprocesses as
     * {@code CLAUDE_PLUGIN_ROOT}.
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
            extraHooks.merge(entry.getKey(), entry.getValue(), HookRegistry::concat);
        }
    }

    /**
     * Removes all per-turn extra hooks registered by skills. Called at turn-complete to prevent
     * cross-turn hook leakage.
     */
    public void clearExtraHooks() {
        extraHooks.clear();
        extraHookRoots.clear();
    }

    /** Skill root registered for a per-invocation hook, or {@code null}. */
    Path skillRoot(HookCommand command) {
        return extraHookRoots.get(command);
    }

    // ---- session layer ----

    void installSessionHooks(HookEvent event, List<HookMatcher> matchers) {
        sessionHooks.merge(event, List.copyOf(matchers), HookRegistry::concat);
    }

    void replaceSessionHooks(HookEvent event, List<HookMatcher> matchers) {
        sessionHooks.put(event, List.copyOf(matchers));
    }

    void removeSessionHooks(HookEvent event) {
        sessionHooks.remove(event);
    }

    /**
     * Snapshot of all in-memory session hooks shown by {@code /hooks}: invoked skill/frontmatter
     * hooks plus session-persistent hooks such as /goal.
     */
    public Map<HookEvent, List<HookMatcher>> currentSessionHooks() {
        EnumMap<HookEvent, List<HookMatcher>> snapshot = new EnumMap<>(HookEvent.class);
        extraHooks.forEach((event, matchers) -> snapshot.put(event, List.copyOf(matchers)));
        sessionHooks.forEach((event, matchers) ->
            snapshot.merge(event, List.copyOf(matchers), HookRegistry::concat));
        return Collections.unmodifiableMap(snapshot);
    }

    // ---- cross-layer views ----

    /** Static FileChanged matcher expressions in normal hook source order. */
    public List<String> configuredFileChangedMatchers() {
        List<String> result = new ArrayList<>();
        for (List<HookMatcher> layer : layers(HookEvent.FILE_CHANGED)) {
            for (HookMatcher matcher : layer) {
                matcher.matcher().filter(StringUtils::isNotBlank).ifPresent(result::add);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Hooks whose matcher accepts {@code input}, before {@code if} rules and
     * {@code once} de-duplication. Used for "is any hook configured" probes.
     */
    List<HookMatchResolver.MatchedHook> match(HookEvent event, HookInput input) {
        return resolver.resolve(event, input, layers(event));
    }

    /**
     * Hooks eligible to run for one dispatch: matcher accepted, caller filter
     * passed, {@code if} rule satisfied, and {@code once} not yet consumed.
     */
    List<HookMatchResolver.MatchedHook> eligible(HookEvent event, HookInput input,
                                                 Predicate<HookCommand> include) {
        return match(event, input).stream()
            .filter(hook -> include.test(hook.command()))
            .filter(hook -> resolver.matchesIfCondition(hook.command(), input))
            .filter(hook -> !hook.command().once() || executedOnceHooks.add(identity(hook)))
            .toList();
    }

    /**
     * Captures the five layers as an immutable per-dispatch view so a concurrent
     * reload never tears one resolution.
     */
    private List<List<HookMatcher>> layers(HookEvent event) {
        HooksSettings settingsSnapshot = settings;
        Map<HookEvent, List<HookMatcher>> pluginSnapshot = pluginHooks;
        Map<HookEvent, List<HookMatcher>> sdkSnapshot = sdkHooks;
        return List.of(
            settingsSnapshot.getMatchers(event),
            pluginSnapshot.getOrDefault(event, List.of()),
            sdkSnapshot.getOrDefault(event, List.of()),
            extraHooks.getOrDefault(event, List.of()),
            sessionHooks.getOrDefault(event, List.of()));
    }

    private static String identity(HookMatchResolver.MatchedHook hook) {
        return hook.command().getClass().getSimpleName() + ":"
            + switch (hook.command()) {
                case BashCommandHook cmd -> cmd.command();
                case PromptHook cmd -> cmd.prompt();
                case HttpHook cmd -> cmd.url();
                case AgentHook cmd -> cmd.prompt();
                case CallbackHook cmd -> cmd.callbackId();
            };
    }

    private static List<HookMatcher> concat(List<HookMatcher> left, List<HookMatcher> right) {
        List<HookMatcher> merged = new ArrayList<>(left);
        merged.addAll(right);
        return List.copyOf(merged);
    }
}
