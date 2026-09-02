package com.claudecode.permissions;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.BashSandboxGate;
import com.claudecode.core.engine.FileReadIgnorePattern;
import com.claudecode.core.engine.PermissionUpdate;
import com.claudecode.core.attachment.PlanModeExitSignal;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.BooleanSupplier;

/**
 * Mutable facade combining a {@link PermissionEngine} with a current {@link ToolPermissionContext}.
 */
public final class PermissionGate {

    private final PermissionEngine engine;
    private final AtomicReference<ToolPermissionContext> contextRef;

    private final AtomicReference<PermissionMode> prePlanMode = new AtomicReference<>();
    private final AtomicBoolean hasExitedPlanMode = new AtomicBoolean();
    /**
     * Whether this session may enter bypassPermissions.
     */
    private volatile boolean bypassPermissionsModeAvailable = true;
    /** Current settings/configuration kill-switch, kept for control-channel error precedence. */
    private volatile boolean bypassPermissionsModeDisabledByPolicy;
    private volatile BooleanSupplier autoModeOptIn = () -> false;
    private volatile BooleanSupplier useAutoModeDuringPlan = () -> true;
    private volatile BooleanSupplier autoModeGateEnabled = () -> false;
    private volatile Predicate<String> autoModeModelSupported = _ -> true;
    private volatile String autoModeCurrentModel;
    private final Object planAutoRulesLock = new Object();
    private final List<IndexedPermissionRule> strippedPlanAutoRules = new ArrayList<>();

    public PermissionGate(ToolPermissionContext initialContext) {
        this(initialContext, PermissionPaths.EMPTY);
    }

    public PermissionGate() {
        this(ToolPermissionContext.of(Path.of(System.getProperty("user.dir"))));
    }

    /**
     * Builds the gate with an explicit {@link PermissionPaths} provider so the
     * engine's internal-path carve-out (session memory, scratchpad, tool-results,
     * …) can auto-allow those writes/reads. The CLI supplies a real provider;
     * tests and other embedders default to {@link PermissionPaths#EMPTY}.
     */
    public PermissionGate(PermissionPaths permissionPaths) {
        this(ToolPermissionContext.of(Path.of(System.getProperty("user.dir"))), permissionPaths);
    }

    /** Full constructor: explicit initial context and internal-path provider. */
    public PermissionGate(ToolPermissionContext initialContext, PermissionPaths permissionPaths) {
        this.engine = new PermissionEngine(permissionPaths);
        this.contextRef = new AtomicReference<>(initialContext);
    }

    /**
     * Maps the UI-level mode string to a {@link PermissionMode}.
     */
    public static PermissionMode parseMode(String uiMode) {
        if (uiMode == null) return PermissionMode.DEFAULT;
        return switch (uiMode) {
            case "default"           -> PermissionMode.DEFAULT;
            case "plan"              -> PermissionMode.PLAN;
            case "acceptEdits"       -> PermissionMode.ACCEPT_EDITS;
            case "bypassPermissions" -> PermissionMode.BYPASS_PERMISSIONS;
            case "dontAsk"           -> PermissionMode.DONT_ASK;
            case "auto"              -> PermissionMode.AUTO;
            default                  -> PermissionMode.DEFAULT;
        };
    }

    /** Replaces the mode on the current context. Thread-safe. */
    public void setMode(PermissionMode mode) {
        PermissionMode requested = mode == null ? PermissionMode.DEFAULT : mode;
        PermissionMode effective = requested == PermissionMode.BYPASS_PERMISSIONS
            && !bypassPermissionsModeAvailable
            ? PermissionMode.DEFAULT : requested;
        contextRef.updateAndGet(ctx -> reconcileClassifierRules(transitionMode(ctx, effective)));
    }

    /** Convenience setter accepting the UI-level string. */
    public void setMode(String uiMode) {
        setMode(parseMode(uiMode));
    }


    public void applyPermissionUpdateMode(PermissionMode mode) {
        PermissionMode requested = mode == null ? PermissionMode.DEFAULT : mode;
        contextRef.updateAndGet(ctx -> reconcileClassifierRules(transitionMode(ctx, requested)));
    }


    public PermissionMode finishPlanMode() {
        PermissionMode previous = prePlanMode.getAndSet(null);
        PermissionMode restore = previous == null ? PermissionMode.DEFAULT : previous;
        if (restore == PermissionMode.AUTO && !isPlanAutoModeAvailable()) {
            restore = PermissionMode.DEFAULT;
        }
        PermissionMode target = restore;
        ToolPermissionContext updated = contextRef.updateAndGet(ctx -> reconcileClassifierRules(
            ctx.mode() == PermissionMode.PLAN ? ctx.setMode(target) : ctx));
        return updated.mode();
    }

    /** Records a successful ExitPlanMode so the next plan entry can emit one-time guidance. */
    public void markPlanModeExited() {
        hasExitedPlanMode.set(true);
    }

    /** Consumes the re-entry marker only once an existing plan is available. */
    public boolean consumePlanModeReentry(boolean planExists) {
        return planExists && hasExitedPlanMode.compareAndSet(true, false);
    }

    /**
     * Configures whether this session may enter bypassPermissions.
     */
    public void setBypassPermissionsModeAvailable(boolean available) {
        bypassPermissionsModeDisabledByPolicy = false;
        bypassPermissionsModeAvailable = available;
        leaveBypassIfUnavailable();
    }

    /**
     * Sets the launch capability and the effective settings kill-switch
     * independently. The CLI uses this at startup; settings hot-reload applies
     * only the one-way kill-switch through {@link #setBypassPermissionsModeDisabled}.
     */
    public void configureBypassPermissionsMode(boolean launchAllowed, boolean disabledByPolicy) {
        bypassPermissionsModeDisabledByPolicy = disabledByPolicy;
        bypassPermissionsModeAvailable = launchAllowed && !disabledByPolicy;
        leaveBypassIfUnavailable();
    }

    /**
     * Re-applies a changed settings kill-switch without changing launch capability.
     */
    public void setBypassPermissionsModeDisabled(boolean disabledByPolicy) {
        bypassPermissionsModeDisabledByPolicy = disabledByPolicy;
        if (disabledByPolicy) {
            bypassPermissionsModeAvailable = false;
            leaveBypassIfUnavailable();
        }
    }

    private void leaveBypassIfUnavailable() {
        if (!bypassPermissionsModeAvailable) {
            contextRef.updateAndGet(ctx -> ctx.mode() == PermissionMode.BYPASS_PERMISSIONS
                ? ctx.setMode(PermissionMode.DEFAULT) : ctx);
        }
    }

    /** Returns whether this session may enter bypassPermissions. */
    public boolean isBypassPermissionsModeAvailable() {
        return bypassPermissionsModeAvailable;
    }

    /** Returns whether settings/configuration currently disable bypass mode. */
    public boolean isBypassPermissionsModeDisabledByPolicy() {
        return bypassPermissionsModeDisabledByPolicy;
    }

    public void configurePlanAutoMode(BooleanSupplier optedIn,
                                      BooleanSupplier useDuringPlan,
                                      BooleanSupplier gateEnabled) {
        autoModeOptIn = optedIn == null ? () -> false : optedIn;
        useAutoModeDuringPlan = useDuringPlan == null ? () -> true : useDuringPlan;
        autoModeGateEnabled = gateEnabled == null ? () -> false : gateEnabled;
    }


    public void configureAutoModeModelSupport(Predicate<String> supported) {
        autoModeModelSupported = supported == null ? _ -> true : supported;
    }

    /** Updates the live model observed by Plan approval after a /model switch. */
    public void setAutoModeCurrentModel(String model) {
        autoModeCurrentModel = model;
        effectivePermissionContext();
    }


    public static boolean supportsReleasedExternalAutoModeModel(String model) {
        if (StringUtils.isBlank(model)) return false;
        String canonical = model.toLowerCase(Locale.ROOT);
        return canonical.contains("claude-opus-4-6")
            || canonical.contains("claude-sonnet-4-6");
    }




    public boolean isPlanAutoModeAvailable() {
        return autoModeOptIn.getAsBoolean() && autoModeGateEnabled.getAsBoolean()
            && autoModeModelSupported.test(autoModeCurrentModel);
    }

    /** Dynamic so a settings reload takes effect while the session remains in plan mode. */
    public boolean isPlanAutoModeActive() {
        return currentMode() == PermissionMode.PLAN
            && prePlanMode.get() != PermissionMode.BYPASS_PERMISSIONS
            && isPlanAutoModeAvailable()
            && useAutoModeDuringPlan.getAsBoolean();
    }

    /**
     * Attempts to change mode and reports whether the requested mode was
     * accepted.  Unlike {@link #setMode(PermissionMode)}, this is useful for
     * control channels that must return an error instead of silently falling
     * back when bypassPermissions is unavailable.
     */
    public boolean trySetMode(PermissionMode mode) {
        PermissionMode requested = mode == null ? PermissionMode.DEFAULT : mode;
        if (requested == PermissionMode.BYPASS_PERMISSIONS
                && !bypassPermissionsModeAvailable) {
            return false;
        }
        setMode(requested);
        return true;
    }

    /**
     * Injects the sandbox auto-allow hook so Bash commands that would run inside the native sandbox are
     * auto-allowed ({@code sandbox.autoAllowBashIfSandboxed}).
     */
    public void setBashSandboxGate(BashSandboxGate gate) {
        engine.setBashSandboxGate(gate);
    }

    /** Appends rules to the active context. Thread-safe. */
    public void addRules(List<PermissionRule> rules) {
        contextRef.updateAndGet(ctx -> reconcileClassifierRules(ctx.addRules(rules)));
    }

    /**
     * Replaces rules from one behavior/source bucket atomically.
     */
    public void replaceRules(PermissionBehavior behavior, RuleSource source,
                             List<PermissionRule> replacement) {
        contextRef.updateAndGet(ctx -> reconcileClassifierRules(ctx
            .removeRules(rule -> rule.behavior() == behavior && rule.source() == source)
            .addRules(replacement)));
    }

    /** Removes rules matching the predicate from the active context. Thread-safe. */
    public void removeRules(Predicate<PermissionRule> filter) {
        contextRef.updateAndGet(ctx -> ctx.removeRules(filter));
    }

    /** Applies ordered tool permission suggestions to the live context atomically. */
    public void applyUpdates(List<PermissionUpdate> updates) {
        if (updates == null || updates.isEmpty()) return;
        contextRef.updateAndGet(initial -> {
            ToolPermissionContext current = initial;
            for (PermissionUpdate update : updates) {
                if (update == null) continue;
                current = switch (update) {
                    case PermissionUpdate.AddRules add ->
                        current.addRules(toRules(add.rules(), add.behavior(), add.destination()));
                    case PermissionUpdate.ReplaceRules replace -> {
                        PermissionBehavior behavior = behavior(replace.behavior());
                        RuleSource source = source(replace.destination());
                        ToolPermissionContext without = current.removeRules(rule ->
                            rule.behavior() == behavior && rule.source() == source);
                        yield without.addRules(toRules(
                            replace.rules(), replace.behavior(), replace.destination()));
                    }
                    case PermissionUpdate.RemoveRules remove -> {
                        PermissionBehavior behavior = behavior(remove.behavior());
                        RuleSource source = source(remove.destination());
                        List<PermissionUpdate.RuleValue> values = remove.rules();
                        yield current.removeRules(rule -> rule.behavior() == behavior
                            && rule.source() == source
                            && values.stream().anyMatch(value -> sameRule(rule, value)));
                    }
                    case PermissionUpdate.SetMode mode -> {
                        PermissionMode requested = parseMode(mode.mode().wireValue());
                        if (requested == PermissionMode.AUTO && !isPlanAutoModeAvailable()) {
                            requested = PermissionMode.DEFAULT;
                        }
                        yield transitionMode(current, requested);
                    }
                    case PermissionUpdate.AddDirectories add ->
                        current.addDirectories(paths(add.directories()), source(add.destination()));
                    case PermissionUpdate.RemoveDirectories remove ->
                        current.removeDirectories(paths(remove.directories()));
                };
            }
            return reconcileClassifierRules(current);
        });
    }

    private ToolPermissionContext transitionMode(ToolPermissionContext context,
                                                 PermissionMode requested) {
        PermissionMode current = context.mode();
        if (requested == PermissionMode.PLAN && current != PermissionMode.PLAN) {
            PlanModeExitSignal.clear();
            prePlanMode.compareAndSet(null, current);
        } else if (current == PermissionMode.PLAN && requested != PermissionMode.PLAN) {
            prePlanMode.set(null);
        }
        return context.setMode(requested);
    }

    private static List<PermissionRule> toRules(
            List<PermissionUpdate.RuleValue> values,
            PermissionUpdate.Behavior behavior,
            PermissionUpdate.Destination destination) {
        PermissionBehavior mappedBehavior = behavior(behavior);
        RuleSource mappedSource = source(destination);
        List<PermissionRule> rules = new ArrayList<>();
        for (PermissionUpdate.RuleValue value : values) {
            if (value == null || value.toolName() == null || StringUtils.isBlank(value.toolName())) continue;
            if (StringUtils.isBlank(value.ruleContent())) {
                rules.add(PermissionRule.of(value.toolName(), mappedBehavior, mappedSource));
            } else {
                rules.add(PermissionRule.withPattern(
                    value.toolName(), mappedBehavior, mappedSource, value.ruleContent()));
            }
        }
        return rules;
    }

    private static boolean sameRule(PermissionRule rule, PermissionUpdate.RuleValue value) {
        if (!rule.toolName().equals(value.toolName())) return false;
        String actual = rule.pattern().orElse(null);
        String expected = value.ruleContent();
        return Objects.equals(actual, expected)
            || (actual == null && StringUtils.isBlank(expected));
    }

    private static PermissionBehavior behavior(PermissionUpdate.Behavior behavior) {
        return switch (behavior) {
            case ALLOW -> PermissionBehavior.ALLOW;
            case DENY -> PermissionBehavior.DENY;
            case ASK -> PermissionBehavior.ASK;
        };
    }

    private static RuleSource source(PermissionUpdate.Destination destination) {
        return switch (destination) {
            case USER_SETTINGS -> RuleSource.USER_SETTINGS;
            case PROJECT_SETTINGS -> RuleSource.PROJECT_SETTINGS;
            case LOCAL_SETTINGS -> RuleSource.LOCAL_SETTINGS;
            case SESSION -> RuleSource.SESSION;
            case CLI_ARG -> RuleSource.CLI_ARG;
        };
    }

    private static List<Path> paths(List<String> values) {
        List<Path> paths = new ArrayList<>();
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) paths.add(Path.of(value));
        }
        return paths;
    }

    /**
     * Replaces disk-sourced rules and appends {@code newDiskRules}, preserving
     * runtime rules. Equivalent to {@link #syncFromDisk(List, boolean)} with
     * {@code managedPermissionRulesOnly = false}.
     */
    public void syncFromDisk(List<PermissionRule> newDiskRules) {
        syncFromDisk(newDiskRules, false);
    }

    /**
     * Replaces disk-sourced rules and appends {@code newDiskRules} while preserving any rules added at
     * runtime, used by the settings hot-reload path when (or its project / local siblings) changes on
     * disk.
     */
    public void syncFromDisk(List<PermissionRule> newDiskRules, boolean managedPermissionRulesOnly) {
        contextRef.updateAndGet(ctx -> {
            List<PermissionRule> preserved = ctx.rules().stream()
                .filter(r -> {
                    if (isEditableDiskSource(r.source())) return false;
                    if (r.source() == RuleSource.FLAG_SETTINGS
                            || r.source() == RuleSource.POLICY_SETTINGS) {

                        // source/behavior group absent from the incoming list.
                        // Preserve that group's existing rules exactly.
                        return newDiskRules.stream().noneMatch(incoming ->
                            incoming.source() == r.source()
                                && incoming.behavior() == r.behavior());
                    }
                    return true;
                })
                .toList();
            if (managedPermissionRulesOnly) {
                preserved = preserved.stream()
                    .filter(r -> !MANAGED_STRIP_SOURCES.contains(r.source()))
                    .toList();
            }
            List<PermissionRule> combined = new ArrayList<>(preserved);
            combined.addAll(newDiskRules);
            return reconcileClassifierRules(ctx.replaceRules(combined));
        });
    }

/**
     * Non-policy sources stripped under {@code shouldAllowManagedPermissionRulesOnly}.
     */
    private static final Set<RuleSource> MANAGED_STRIP_SOURCES = Set.of(
        RuleSource.USER_SETTINGS,
        RuleSource.PROJECT_SETTINGS,
        RuleSource.LOCAL_SETTINGS,
        RuleSource.CLI_ARG,
        RuleSource.SESSION
    );

    private static boolean isEditableDiskSource(RuleSource source) {
        return source == RuleSource.USER_SETTINGS
            || source == RuleSource.PROJECT_SETTINGS
            || source == RuleSource.LOCAL_SETTINGS;
    }

    /** Adds working directories (runtime source {@link RuleSource#SESSION}). Thread-safe. */
    public void addDirectories(List<Path> dirs) {
        addDirectories(dirs, RuleSource.SESSION);
    }

    /** Adds working directories recording {@code source} as provenance. Thread-safe. */
    public void addDirectories(List<Path> dirs, RuleSource source) {
        contextRef.updateAndGet(ctx -> ctx.addDirectories(dirs, source));
    }

    /** Removes the specified directories (by path) from the additional-dirs set. */
    public void removeDirectories(List<Path> dirs) {
        contextRef.updateAndGet(ctx -> ctx.removeDirectories(dirs));
    }

    /** Returns the current immutable context snapshot. */
    public ToolPermissionContext currentContext() {
        return contextRef.updateAndGet(this::reconcileClassifierRules);
    }

    /**
     * Resolves the currently-active file-read {@code deny} rules into glob patterns to exclude from
     * GlobTool results.
     */
    public List<FileReadIgnorePattern> getFileReadIgnorePatterns() {
        List<FileReadIgnorePattern> result = new ArrayList<>();
        for (PermissionRule rule : currentContext().rules()) {
            if (rule.behavior() != PermissionBehavior.DENY) continue;
            if (!READ_TOOL_NAME.equals(rule.toolName())) continue;
            if (rule.pattern().isEmpty()) continue;
            result.add(FilePermissionRuleMatcher.toIgnorePattern(
                rule.pattern().get(), rule.source(), currentContext().pathContext()));
        }
        return result;
    }

    /**
     * Returns whether a background attachment reread is forbidden by the live permission context.
     */
    public boolean isFileReadDenied(String path) {
        ToolPermissionContext context = currentContext();
        Path candidate;
        try {
            candidate = Path.of(path).toAbsolutePath().normalize();
        } catch (RuntimeException _) {
            return true;
        }
        if (PathSafety.isNetworkPath(path)
                && !context.pathContext().isTrustedNetworkDirectory(candidate)) {
            return true;
        }
        for (PermissionRule rule : context.rules()) {
            if (rule.behavior() != PermissionBehavior.DENY) continue;
            if (!READ_TOOL_NAME.equals(rule.toolName())) continue;
            if (rule.pattern().isEmpty()) return true;
            if (FilePermissionRuleMatcher.matches(
                    rule.pattern().get(), rule.source(), path,
                    context.pathContext(), PermissionBehavior.DENY)) {
                return true;
            }
        }
        return false;
    }


    private static final String READ_TOOL_NAME = "Read";

    /** The current permission mode from the immutable context snapshot. */
    public PermissionMode currentMode() {
        return contextRef.get().mode();
    }

    /**
     * Like {@link #checkDetailed(String, JsonNode)} but also returns the reason for the decision,
     * enabling the permission dialog to show why approval was required (rule match or mode fallback).
     */
    public PermissionDecisionResult checkDetailed(String toolName, JsonNode input) {
        return engine.evaluateDetailed(toolName, input, effectivePermissionContext());
    }

    /**
     * Like {@link #checkDetailed(String, JsonNode)} but folds in the tool's own {@code
     * checkPermissions} decision.
     */
    public PermissionDecisionResult checkDetailed(String toolName, JsonNode input, PermissionDecision toolDecision) {
        return engine.evaluateDetailed(
            toolName, input, effectivePermissionContext(), toolDecision);
    }

    private ToolPermissionContext effectivePermissionContext() {
        return contextRef.updateAndGet(this::reconcileClassifierRules);
    }

    private ToolPermissionContext reconcileClassifierRules(ToolPermissionContext context) {
        synchronized (planAutoRulesLock) {
            boolean classifierActive = context.mode() == PermissionMode.AUTO
                ? isPlanAutoModeAvailable()
                : context.mode() == PermissionMode.PLAN
                    && prePlanMode.get() != PermissionMode.BYPASS_PERMISSIONS
                    && isPlanAutoModeAvailable()
                    && useAutoModeDuringPlan.getAsBoolean();
            return classifierActive
                ? stripDangerousClassifierRules(context)
                : restoreDangerousClassifierRules(context);
        }
    }

    private ToolPermissionContext stripDangerousClassifierRules(ToolPermissionContext context) {
        List<PermissionRule> current = context.rules();
        List<PermissionRule> retained = new ArrayList<>(current.size());
        Map<PermissionRule, Integer> dangerousOccurrences = new HashMap<>();
        boolean changed = false;
        for (int i = 0; i < current.size(); i++) {
            PermissionRule rule = current.get(i);
            if (isRemovableClassifierRule(rule)) {
                int occurrence = dangerousOccurrences.merge(rule, 1, Integer::sum);
                long savedOccurrences = strippedPlanAutoRules.stream()
                    .filter(saved -> saved.rule().equals(rule)).count();
                if (occurrence > savedOccurrences) {
                    strippedPlanAutoRules.add(new IndexedPermissionRule(i, rule));
                }
                changed = true;
            } else {
                retained.add(rule);
            }
        }
        return changed ? context.replaceRules(retained) : context;
    }

    private ToolPermissionContext restoreDangerousClassifierRules(ToolPermissionContext context) {
        if (strippedPlanAutoRules.isEmpty()) return context;
        List<PermissionRule> restored = new ArrayList<>(context.rules());
        strippedPlanAutoRules.stream()
            .sorted(Comparator.comparingInt(IndexedPermissionRule::index))
            .forEach(saved -> restored.add(
                Math.min(saved.index(), restored.size()), saved.rule()));
        strippedPlanAutoRules.clear();
        return context.replaceRules(restored);
    }

    private static boolean isRemovableClassifierRule(PermissionRule rule) {
        if (rule.behavior() != PermissionBehavior.ALLOW
                || !CLASSIFIER_RULE_SOURCES.contains(rule.source())) return false;
        String toolName = rule.toolName();
        String content = rule.pattern().orElse(null);
        return isDangerousBashRule(toolName, content)
            || isDangerousPowerShellRule(toolName, content)
            || Strings.CI.equals("Agent", toolName)
            || Strings.CI.equals("Task", toolName);
    }

    private static boolean isDangerousBashRule(String toolName, String content) {
        if (!Strings.CS.equals("Bash", toolName)) return false;
        return isDangerousShellPattern(content, DANGEROUS_BASH_PREFIXES, false);
    }

    private static boolean isDangerousPowerShellRule(String toolName, String content) {
        if (!Strings.CS.equals("PowerShell", toolName)) return false;
        return isDangerousShellPattern(content, DANGEROUS_POWERSHELL_PREFIXES, true);
    }

    private static boolean isDangerousShellPattern(
            String content, Set<String> prefixes, boolean matchExe) {
        if (StringUtils.isBlank(content)) return true;
        String normalized = content.trim().toLowerCase(Locale.ROOT);
        if (Strings.CS.equals("*", normalized)) return true;
        for (String prefix : prefixes) {
            if (matchesDangerousPrefix(normalized, prefix)) return true;
            if (matchExe && matchesDangerousPrefix(normalized, executablePrefix(prefix))) return true;
        }
        return false;
    }

    private static boolean matchesDangerousPrefix(String content, String prefix) {
        return Strings.CS.equals(content, prefix)
            || Strings.CS.equals(content, prefix + ":*")
            || Strings.CS.equals(content, prefix + "*")
            || Strings.CS.equals(content, prefix + " *")
            || Strings.CS.startsWith(content, prefix + " -") && Strings.CS.endsWith(content, "*");
    }

    private static String executablePrefix(String prefix) {
        int space = prefix.indexOf(' ');
        return space < 0 ? prefix + ".exe"
            : prefix.substring(0, space) + ".exe" + prefix.substring(space);
    }

    private record IndexedPermissionRule(int index, PermissionRule rule) {}

    private static final Set<RuleSource> CLASSIFIER_RULE_SOURCES = Set.of(
        RuleSource.USER_SETTINGS, RuleSource.PROJECT_SETTINGS, RuleSource.LOCAL_SETTINGS,
        RuleSource.CLI_ARG, RuleSource.SESSION);

    private static final Set<String> CROSS_PLATFORM_CODE_EXEC = Set.of(
        "python", "python3", "python2", "node", "deno", "tsx", "ruby", "perl", "php", "lua",
        "npx", "bunx", "npm run", "yarn run", "pnpm run", "bun run", "bash", "sh", "ssh");

    private static final Set<String> DANGEROUS_BASH_PREFIXES;
    private static final Set<String> DANGEROUS_POWERSHELL_PREFIXES;

    static {
        Set<String> bash = new LinkedHashSet<>(CROSS_PLATFORM_CODE_EXEC);
        bash.addAll(List.of("zsh", "fish", "eval", "exec", "env", "xargs", "sudo"));
        DANGEROUS_BASH_PREFIXES = Set.copyOf(bash);
        Set<String> powershell = new LinkedHashSet<>(CROSS_PLATFORM_CODE_EXEC);
        powershell.addAll(List.of(
            "pwsh", "powershell", "cmd", "wsl", "iex", "invoke-expression", "icm",
            "invoke-command", "start-process", "saps", "start", "start-job", "sajb",
            "start-threadjob", "register-objectevent", "register-engineevent", "register-wmievent",
            "register-scheduledjob", "new-pssession", "nsn", "enter-pssession", "etsn",
            "add-type", "new-object"));
        DANGEROUS_POWERSHELL_PREFIXES = Set.copyOf(powershell);
    }
}
