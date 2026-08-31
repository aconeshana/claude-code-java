package com.claudecode.permissions;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.BashSandboxGate;
import com.claudecode.core.engine.PermissionUpdate;
import com.claudecode.core.tool.LegacyToolNames;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Permission engine that evaluates tool permission requests against rules and mode.
 */
public class PermissionEngine {

    /** Internal-path carve-outs (session memory, scratchpad, tool-results, …). */
    private final PermissionPaths permissionPaths;

    /**
     * Optional hook telling the engine whether a Bash command would run inside
     * the native sandbox, enabling {@code autoAllowBashIfSandboxed}. Injected by
     * the wiring layer (which owns the {@link BashSandboxGate} implementation);
     * {@code null} when sandboxing is not wired up (default: no bash auto-allow).
     */
    private BashSandboxGate bashSandboxGate;

    /** Default constructor: no internal-path carve-outs (conservative). */
    public PermissionEngine() {
        this(PermissionPaths.EMPTY);
    }

    /**
     * Constructs with an explicit {@link PermissionPaths} provider. The CLI wires
     * a provider that resolves session/services paths so internal files are
     * editable/readable without prompting; {@code permissions} stays core-only.
     */
    public PermissionEngine(PermissionPaths permissionPaths) {
        this.permissionPaths = permissionPaths;
    }

    /**
     * Injects the sandbox auto-allow hook (wiring layer).
     */
    public void setBashSandboxGate(BashSandboxGate gate) {
        this.bashSandboxGate = gate;
    }

    /**
     * Tool names treated as "write" (side-effect-producing) for mode-based
     * decisions. Must match the registered {@link com.claudecode.tools.Tool#name}
     * values, not class names.
     */
    private static final List<String> WRITE_TOOLS = List.of(
        "Bash", "Write", "Edit", "NotebookEdit", "PowerShell", "REPL"
    );

    /**
     * File tools eligible for the working-directory auto-allow step.
     */
    private static final List<String> WORKING_DIR_ELIGIBLE_TOOLS = List.of(
        "Read", "Write", "Edit", "NotebookEdit", "Grep", "LSP"
    );

/** Read this field first, then this, then this — matches {@link #extractInputText}. */
    private static final List<String> PATH_FIELDS = List.of("file_path", "notebook_path", "path", "filePath");

    /**
     * Tools whose auto-allow eligibility follows the "read" rule (unconditional) rather than the
     * "write" rule (requires {@link PermissionMode#ACCEPT_EDITS}).
     */
    private static final List<String> READ_ONLY_WORKING_DIR_TOOLS = List.of("Read", "Grep", "LSP");


    private static final Set<String> AUTO_SAFE_TOOLS = Set.of(
        "Read", "Grep", "Glob", "LSP", "ToolSearch", "ListMcpResources",
        "ReadMcpResourceTool", "TodoWrite", "TaskCreate", "TaskGet",
        "TaskUpdate", "TaskList", "TaskStop", "TaskOutput",
        "AskUserQuestion", "EnterPlanMode", "ExitPlanMode", "TeamCreate",
        "TeamDelete", "SendMessage", "Workflow", "Sleep"
    );


    private static final Map<String, String> TOOL_TO_FILE_BUCKET = Map.of(
        "Write",         "Edit",
        "NotebookEdit",  "Edit",
        "Grep",          "Read",
        "Glob",          "Read",
        "LSP",           "Read"
    );


    private static final String CLAUDE_FOLDER_PERMISSION_PATTERN = "/.claude";
    private static final String GLOBAL_CLAUDE_FOLDER_PERMISSION_PATTERN = "~/.claude";

    /**
     * Like {@link #evaluateDetailed(String, JsonNode, ToolPermissionContext, PermissionDecision)}
     * with no tool-level decision (the tool's {@code checkPermissions} is not consulted).
     */
    public PermissionDecisionResult evaluateDetailed(String toolName, JsonNode input, ToolPermissionContext context) {
        return mergeToolDecision(evaluateDetailedInternal(toolName, input, context), null);
    }


    public PermissionDecisionResult evaluateDetailed(String toolName, JsonNode input,
                                                     ToolPermissionContext context, PermissionDecision toolDecision) {
        return mergeToolDecision(evaluateDetailedInternal(toolName, input, context), toolDecision);
    }

    /** Core rules/mode decision chain, without the tool-level overlay. */
    private PermissionDecisionResult evaluateDetailedInternal(String toolName, JsonNode input, ToolPermissionContext context) {
        List<PermissionRule> rules = context.rules();
        boolean bypassesOrdinaryPathSafety =
            context.mode() == PermissionMode.BYPASS_PERMISSIONS;

        PermissionDecisionResult readSafety = readPathSafety(
            toolName, input, context, bypassesOrdinaryPathSafety);
        if (readSafety != null) return readSafety;

        Optional<PermissionRule> denyMatch = findMatchingRule(toolName, input, rules, PermissionBehavior.DENY, context);
        if (denyMatch.isPresent()) {
            return new PermissionDecisionResult(PermissionDecision.deny(), new DecisionReason.Rule(denyMatch.get()));
        }


        // Checked after deny rules so an explicit deny still wins.
        if (sandboxAutoAllows(toolName, input)) {
            return new PermissionDecisionResult(
                PermissionDecision.allow(),
                new DecisionReason.SandboxOverride("autoAllowBashIfSandboxed: command will run sandboxed"));
        }

        Optional<PermissionRule> askMatch = findMatchingRule(toolName, input, rules, PermissionBehavior.ASK, context);
        if (askMatch.isPresent()) {
            return applyDontAskTransform(context.mode(), PermissionDecision.ask(), new DecisionReason.Rule(askMatch.get()));
        }

        PermissionDecisionResult claudeFolderResult = claudeFolderSessionAllowResult(toolName, input, context);
        if (claudeFolderResult != null) {
            return claudeFolderResult;
        }

        PermissionDecisionResult pathResult = pathSafetyAndInternal(
            toolName, input, bypassesOrdinaryPathSafety);
        if (pathResult != null) {
            return pathResult;
        }

        if (workingDirectoryAutoAllow(toolName, input, context)) {
            return new PermissionDecisionResult(PermissionDecision.allow(), new DecisionReason.Mode(context.mode()));
        }

        Optional<PermissionRule> allowMatch = findMatchingRule(toolName, input, rules, PermissionBehavior.ALLOW, context);
        if (allowMatch.isPresent()) {
            return new PermissionDecisionResult(PermissionDecision.allow(), new DecisionReason.Rule(allowMatch.get()));
        }


        // once read-specific deny/ask have passed, an edit-bucket allow rule that
        // matches the same path also grants read. Only content (path) rules with the
        // canonical "Edit" tool name count — a bare "Edit" tool allow is a general
        // layer rule and does not imply read here.
        if (isReadBucketTool(toolName)) {
            Optional<PermissionRule> editAllow = editBucketAllowForRead(input, rules, context);
            if (editAllow.isPresent()) {
                return new PermissionDecisionResult(
                    PermissionDecision.allow(), new DecisionReason.Rule(editAllow.get()));
            }
        }

        PermissionMode mode = context.mode();
        return applyDontAskTransform(mode, evaluateByMode(toolName, mode), new DecisionReason.Mode(mode));
    }

    /**
     * Folds the tool's {@code checkPermissions} result into the engine's decision. See
     * {@link #evaluateDetailed(String, JsonNode, ToolPermissionContext, PermissionDecision)} for
     * the merge contract. {@code null} toolDecision means "no opinion" and returns the engine
     * result untouched.
     */
    private PermissionDecisionResult mergeToolDecision(PermissionDecisionResult engineResult, PermissionDecision toolDecision) {
        switch (toolDecision) {
            case null -> {
                return engineResult;
            }
            case PermissionDecision.Deny _ -> {
                return new PermissionDecisionResult(
                    toolDecision,
                    new DecisionReason.Other("tool.checkPermissions returned deny"));
            }
            case PermissionDecision.Allow _ -> {

                // returns a tool-level allow before applying that transform; only
                // an actual deny rule/tool refusal remains authoritative.
                boolean dontAskModeTransform =
                    engineResult.reason() instanceof DecisionReason.Mode(PermissionMode mode1)
                        && mode1 == PermissionMode.DONT_ASK;
                if (engineResult.decision() instanceof PermissionDecision.Deny
                    && !dontAskModeTransform) {
                    return engineResult;
                }
                return new PermissionDecisionResult(
                    PermissionDecision.allow(),
                    new DecisionReason.Other("tool.checkPermissions returned allow (passthrough)"));
            }
            default -> {
            }
        }

        // ask after the rule/mode chain and carries these suggestions into the
        // final permission request; dropping them here made MCP/WebSearch's
        // "always allow" option disappear from Java dialogs.
        if (engineResult.decision() instanceof PermissionDecision.Ask(
            String blockedPath, JsonNode updatedInput, String message, String suggestionRuleContent,
            String suggestionLabel, List<PermissionUpdate> suggestions
        )
            && toolDecision instanceof PermissionDecision.Ask(
            String path, JsonNode input, String message1, String ruleContent, String label,
            List<PermissionUpdate> suggestions1
        )) {
            PermissionDecision.Ask merged = new PermissionDecision.Ask(
                blockedPath != null
                    ? blockedPath : path,
                input != null
                    ? input : updatedInput,
                message1 != null
                    ? message1 : message,
                ruleContent != null
                    ? ruleContent : suggestionRuleContent,
                label != null
                    ? label : suggestionLabel,
                !suggestions1.isEmpty()
                    ? suggestions1 : suggestions);
            return new PermissionDecisionResult(merged, engineResult.reason());
        }
        return engineResult;
    }

    /**
     * Finds the first rule matching the given tool name, input, and behavior.
     */
    private Optional<PermissionRule> findMatchingRule(String toolName, JsonNode input,
                                                      List<PermissionRule> rules,
                                                      PermissionBehavior behavior,
                                                      ToolPermissionContext context) {
        return rules.stream()
            .filter(rule -> rule.behavior() == behavior)
            .filter(rule -> matchesToolName(rule, toolName))
            .filter(rule -> matchesPattern(rule, toolName, input, behavior, context))
            .findFirst();
    }

    /**
     * Checks if a rule's tool name matches the given tool name.
     */
    private boolean matchesToolName(PermissionRule rule, String toolName) {
        String ruleToolName = rule.toolName();
        if (Strings.CS.equals("*", ruleToolName)) {
            return true;
        }

        if (ruleToolName.equals(toolName)) {
            return true;
        }



        // fetches rules by the canonical bucket name only, so e.g. "Write(...)"
        // does not match an Edit invocation.
        if (rule.pattern().isPresent()) {
            String bucket = TOOL_TO_FILE_BUCKET.getOrDefault(toolName, toolName);
            return !bucket.equals(toolName) && ruleToolName.equals(bucket);
// Content rules match by exact tool name (or bucket, above) only — never via the
// server-level branch below.
        }
// MCP server-level permission: rule "mcp__server1" (or "mcp__server1__*") matches any tool
// "mcp__server1__*" from that server.
        McpInfo ruleInfo = mcpInfoFromString(ruleToolName);
        McpInfo toolInfo = mcpInfoFromString(toolName);
        return ruleInfo != null
            && toolInfo != null
            && (ruleInfo.toolName == null || Strings.CS.equals("*", ruleInfo.toolName))
            && ruleInfo.serverName.equals(toolInfo.serverName);
    }

    /**
     * Parses an MCP tool name of the form {@code mcp__<server>[__<tool>]} into its server and tool
     * parts.
     */
    private static McpInfo mcpInfoFromString(String name) {
        if (name == null || !Strings.CS.startsWith(name, "mcp__")) {
            return null;
        }
        String rest = name.substring(4); // strip "mcp__"
        int idx = rest.indexOf("__");
        if (idx < 0) {
            return new McpInfo(rest, null);
        }
        String serverName = rest.substring(0, idx);
        String toolPart = rest.substring(idx + 2);
        if (Strings.CS.equals("*", toolPart)) {
            return new McpInfo(serverName, "*");
        }
        return new McpInfo(serverName, toolPart.isEmpty() ? null : toolPart);
    }

    /** Parsed MCP tool name: server id plus optional tool id (or "*"). */
    private record McpInfo(String serverName, String toolName) {}

    /**
     * Checks if a rule's optional pattern matches the tool input.
     */
    private boolean matchesPattern(PermissionRule rule, String toolName, JsonNode input,
                                   PermissionBehavior behavior, ToolPermissionContext context) {
        if (rule.pattern().isEmpty()) {
            return true;
        }
        String pattern = rule.pattern().get();
        if (isFilePathTool(toolName)) {

            // its resolved symlink form; DENY/ASK rules must block on EITHER

            // loop over pathsToCheck), while ALLOW rules match only the literal

            // that asymmetry here: only DENY/ASK get the symlink-resolution pass.
            String rawPath = extractPathField(input);
            if (FilePermissionRuleMatcher.matches(pattern, rule.source(), rawPath,
                    context.pathContext(), behavior)) {
                return true;
            }
            if (behavior == PermissionBehavior.DENY || behavior == PermissionBehavior.ASK) {
                String resolved = resolveSymlinkPath(rawPath);
                return resolved != null && !resolved.equals(rawPath)
                    && FilePermissionRuleMatcher.matches(pattern, rule.source(),
                    resolved, context.pathContext(), behavior);
            }
            return false;
        }
        String inputText = extractInputText(input, rule.toolName());
        if (inputText == null) {
            return false;
        }
        return matchWildcardPattern(pattern, inputText);
    }

    /**
     * True when {@code toolName} is Bash and the command would run inside the native sandbox, so its
     * permission may be auto-allowed.
     */
    private boolean sandboxAutoAllows(String toolName, JsonNode input) {
        if (bashSandboxGate == null || !Strings.CI.equals("Bash", toolName)) {
            return false;
        }
        String command = (input != null && input.has("command") && input.get("command").isTextual())
            ? input.get("command").asText() : "";
        boolean dangerouslyDisableSandbox = input != null
            && input.has("dangerouslyDisableSandbox")
            && input.get("dangerouslyDisableSandbox").asBoolean(false);
        return bashSandboxGate.shouldAutoAllow(command, dangerouslyDisableSandbox);
    }

    /**
     * Auto-allows {@code Read}/{@code Grep} unconditionally, and {@code Write}/ {@code Edit}/{@code
     * NotebookEdit} in {@link PermissionMode#ACCEPT_EDITS} or {@link PermissionMode#AUTO}, when the
     * tool's target path resolves inside the working directory or an {@code /add-dir}-added directory.
     */
    private boolean workingDirectoryAutoAllow(String toolName, JsonNode input, ToolPermissionContext context) {
        if (WORKING_DIR_ELIGIBLE_TOOLS.stream().noneMatch(t -> t.equalsIgnoreCase(toolName))) {
            return false;
        }
        String rawPath = extractPathField(input);
        if (StringUtils.isBlank(rawPath)) {
            return false;
        }
        boolean isReadOnly = READ_ONLY_WORKING_DIR_TOOLS.stream().anyMatch(t -> t.equalsIgnoreCase(toolName));
        if (!isReadOnly
                && context.mode() != PermissionMode.ACCEPT_EDITS
                && context.mode() != PermissionMode.AUTO) {
            return false;
        }
        try {
            return WorkingDirectoryPaths.isWithinWorkingDirectories(Path.of(rawPath), context);
        } catch (RuntimeException _) {
            return false;
        }
    }

    /** Extracts the target path from tool input, trying {@link #PATH_FIELDS} in order. */
    private String extractPathField(JsonNode input) {
        if (input == null) {
            return null;
        }
        for (String field : PATH_FIELDS) {
            if (input.has(field) && input.get(field).isTextual()) {
                return input.get(field).asText();
            }
        }
        return null;
    }


    private static String resolveSymlinkPath(String rawPath) {
        if (StringUtils.isBlank(rawPath)) {
            return null;
        }
        try {
            return Path.of(rawPath).toRealPath().toString();
        } catch (RuntimeException | IOException _) {
            return null;
        }
    }

    /**
     * Extracts a text representation from the tool input for pattern matching.
     */
    private String extractInputText(JsonNode input, String toolName) {
        if (input == null) {
            return null;
        }
        // Bash/PowerShell use "command"
        if (Strings.CI.equals("Bash", toolName) || Strings.CI.equals("PowerShell", toolName)) {
            if (input.has("command") && input.get("command").isTextual()) {
                return input.get("command").asText();
            }
        }
        // Dynamic workflow rules use the canonical named workflow (for example
        // Workflow(code-review)); a script/path has no stable catalog identity.
        if (Strings.CS.equals("Workflow", toolName) && input.has("name") && input.get("name").isTextual()) {
            return input.get("name").asText();
        }
        // File tools use "file_path"/"filePath" or "path"
        for (String field : List.of("file_path", "filePath", "path", "command", "content")) {
            if (input.has(field) && input.get(field).isTextual()) {
                return input.get(field).asText();
            }
        }
        // Unknown content shape: return null so an unrecognized content rule does

        // extractInputText fallback for non-file tools — unmatched content rules
// fall through to the rule/mode decision rather than matching input.toString.
        return null;
    }

    /**
     * Applies the internal-path carve-out and the sensitive-file safety gate (writes only) before
     * rule/mode evaluation.
     */
    private PermissionDecisionResult pathSafetyAndInternal(
            String toolName, JsonNode input, boolean bypassesOrdinaryPathSafety) {
        String pathStr = extractPathField(input);
        if (StringUtils.isBlank(pathStr)) {
            return null;
        }
        Path p;
        try {
            p = Path.of(pathStr);
        } catch (RuntimeException _) {
            return new PermissionDecisionResult(
                PermissionDecision.ask(),
                new DecisionReason.SafetyCheck(
                    "Claude requested permissions for an invalid path that requires manual approval.",
                    false));
        }
        if (isWriteTool(toolName)) {
            if (isInternalPath(p, permissionPaths.internalEditablePaths())) {
                return new PermissionDecisionResult(
                    PermissionDecision.allow(),
                    new DecisionReason.Other("internal path allowed for writing"));
            }
            PathSafety.SafetyResult safety = PathSafety.checkPathSafetyForAutoEdit(p);
            if (!safety.safe() && !bypassesOrdinaryPathSafety) {
                return new PermissionDecisionResult(
                    PermissionDecision.ask(),
                    new DecisionReason.SafetyCheck(safety.message(), safety.classifierApprovable()));
            }
        } else if (isInternalPath(p, permissionPaths.internalReadablePaths())) {
            return new PermissionDecisionResult(
                PermissionDecision.allow(),
                new DecisionReason.Other("internal path allowed for reading"));
        }
        return null;
    }


    private PermissionDecisionResult claudeFolderSessionAllowResult(String toolName, JsonNode input,
                                                                    ToolPermissionContext context) {
        if (!isWriteTool(toolName)) {
            return null;
        }
        String rawPath = extractPathField(input);
        if (StringUtils.isBlank(rawPath)) {
            return null;
        }
        for (PermissionRule rule : context.rules()) {
            if (rule.behavior() != PermissionBehavior.ALLOW) continue;
            if (rule.source() != RuleSource.SESSION) continue;
            if (rule.pattern().isEmpty()) continue;
            String content = rule.pattern().get();
            if (!Strings.CS.startsWith(content, CLAUDE_FOLDER_PERMISSION_PATTERN)
                    && !Strings.CS.startsWith(content, GLOBAL_CLAUDE_FOLDER_PERMISSION_PATTERN)) {
                continue;
            }
            if (Strings.CS.contains(content, "..")) continue;
            if (!Strings.CS.endsWith(content, "/**")) continue;
            if (matchesPattern(rule, toolName, input, PermissionBehavior.ALLOW, context)) {
                return new PermissionDecisionResult(
                    PermissionDecision.allow(), new DecisionReason.Rule(rule));
            }
        }
        return null;
    }


    private PermissionDecisionResult readPathSafety(
            String toolName, JsonNode input, ToolPermissionContext context,
            boolean bypassesOrdinaryPathSafety) {
        if (isWriteTool(toolName)) return null;
        String raw = extractPathField(input);
        if (StringUtils.isBlank(raw)) return null;
        Path candidate;
        try {
            candidate = Path.of(raw);
        } catch (RuntimeException _) {
            return new PermissionDecisionResult(
                PermissionDecision.ask(),
                new DecisionReason.SafetyCheck(
                    "Claude requested permissions to read an invalid path that requires manual approval.",
                    false));
        }
        PathSafety.SafetyResult safety = PathSafety.checkPathSafetyForRead(
            raw, candidate, context.pathContext());
        if (safety.safe() || bypassesOrdinaryPathSafety) return null;
        return new PermissionDecisionResult(
            PermissionDecision.ask(),
            new DecisionReason.SafetyCheck(safety.message(), safety.classifierApprovable()));
    }

    private static boolean isFilePathTool(String toolName) {
        return Set.of("Read", "Write", "Edit", "NotebookEdit", "Grep", "LSP", "Glob")
            .stream().anyMatch(tool -> tool.equalsIgnoreCase(toolName));
    }


    private static boolean isReadBucketTool(String toolName) {
        return Set.of("Read", "Grep", "Glob", "LSP")
            .stream().anyMatch(tool -> tool.equalsIgnoreCase(toolName));
    }

    /**
     * Edit access implies read access: returns an edit-bucket ({@code "Edit"}) allow rule whose path
     * pattern matches the given input.
     */
    private Optional<PermissionRule> editBucketAllowForRead(JsonNode input,
                                                           List<PermissionRule> rules,
                                                           ToolPermissionContext context) {
        return rules.stream()
            .filter(r -> r.behavior() == PermissionBehavior.ALLOW)
            .filter(r -> r.pattern().isPresent())
            .filter(r -> Strings.CS.equals("Edit", r.toolName()))
            .filter(r -> matchesPattern(r, "Edit", input, PermissionBehavior.ALLOW, context))
            .findFirst();
    }

    /** True if {@code target} equals or sits inside {@code root} (separator-aware). */
    private boolean isInternalPath(Path target, Set<Path> roots) {
        if (roots.isEmpty()) {
            return false;
        }
        Path norm = target.toAbsolutePath().normalize();
        String t = norm.toString();
        for (Path root : roots) {
            String r = root.toAbsolutePath().normalize().toString();
            if (t.equals(r) || Strings.CS.startsWith(t, r + File.separator) || Strings.CS.startsWith(t, r + "/")) {
                return true;
            }
        }
        return false;
    }


    static boolean matchWildcardPattern(String pattern, String input) {
        String trimmedPattern = pattern.strip();



        // matches a command that equals the prefix or starts with "prefix "


        // builder turned "git:*" into the literal string "git:*", so the rule

        // rule match a compound command (e.g. "git:*" must not match
// "cd /x && git push"); that containment check lives in the not-yet-implemented
        // Bash AST splitter, not here.
        String cmdPrefix = permissionRuleExtractPrefix(trimmedPattern);
        if (cmdPrefix != null) {
            if (Strings.CS.equals(input, cmdPrefix) ||Strings.CS.startsWith( input, cmdPrefix + " ")) {
                return true;
            }
            String xargsPrefix = "xargs " + cmdPrefix;
            return Strings.CS.equals(input, xargsPrefix)
                || Strings.CS.startsWith(input, xargsPrefix + " ");
        }

        // Build a regex from the pattern, handling escape sequences
        StringBuilder regex = new StringBuilder("(?s)"); // dotAll - match newlines
        int i = 0;
        while (i < trimmedPattern.length()) {
            char c = trimmedPattern.charAt(i);
            if (c == '\\' && i + 1 < trimmedPattern.length()) {
                char next = trimmedPattern.charAt(i + 1);
                if (next == '*') {
                    regex.append(Pattern.quote("*")); // literal *
                    i += 2;
                    continue;
                } else if (next == '\\') {
                    regex.append(Pattern.quote("\\")); // literal backslash
                    i += 2;
                    continue;
                }
            }
            if (c == '*') {
                regex.append(".*");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
            i++;
        }

        String regexStr = regex.toString();
        if (Pattern.compile(regexStr).matcher(input).matches()) {
            return true;
        }


        if (Strings.CS.endsWith(trimmedPattern, " *") && !Strings.CS.endsWith(trimmedPattern, "\\*")) {
            String prefix = trimmedPattern.substring(0, trimmedPattern.length() - 2);
            return input.equals(prefix) || Strings.CS.startsWith(input, prefix + " ");
        }

        return false;
    }

    // ── Mode-based fallback ──────────────────────────────────────────────────

    /**
     * Mode-based fallback decision when no rules match.
     */
    private PermissionDecision evaluateByMode(String toolName, PermissionMode mode) {
        return switch (mode) {
            case BYPASS_PERMISSIONS -> PermissionDecision.allow();
            case PLAN -> PermissionDecision.ask();
            case ACCEPT_EDITS -> PermissionDecision.ask();
            case AUTO -> AUTO_SAFE_TOOLS.contains(toolName)
                ? PermissionDecision.allow() : PermissionDecision.ask();
            case DEFAULT, DONT_ASK -> PermissionDecision.ask();
        };
    }


    private PermissionDecisionResult applyDontAskTransform(
            PermissionMode mode, PermissionDecision decision, DecisionReason reason) {
        if (mode == PermissionMode.DONT_ASK && decision instanceof PermissionDecision.Ask) {
            return new PermissionDecisionResult(PermissionDecision.deny(), new DecisionReason.Mode(mode));
        }
        return new PermissionDecisionResult(decision, reason);
    }

    private boolean isWriteTool(String toolName) {
        return WRITE_TOOLS.stream().anyMatch(w -> w.equalsIgnoreCase(toolName));
    }



    /**
     * Parses a permission rule string of the form {@code "ToolName"} or {@code "ToolName(content)"}
     * into a {@link PermissionRule}.
     */
    public static PermissionRule permissionRuleFromString(
            String ruleString, PermissionBehavior behavior, RuleSource source) {
        int openIdx = findFirstUnescapedChar(ruleString, '(');
        if (openIdx == -1) {
            return PermissionRule.of(normalizeLegacyToolName(ruleString), behavior, source);
        }
        int closeIdx = findLastUnescapedChar(ruleString, ')');
        if (closeIdx == -1 || closeIdx <= openIdx || closeIdx != ruleString.length() - 1) {
            return PermissionRule.of(normalizeLegacyToolName(ruleString), behavior, source);
        }

        String toolName = ruleString.substring(0, openIdx);
        if (toolName.isEmpty()) {
            return PermissionRule.of(normalizeLegacyToolName(ruleString), behavior, source);
        }

        String rawContent = ruleString.substring(openIdx + 1, closeIdx);
        if (rawContent.isEmpty() || Strings.CS.equals("*", rawContent)) {
            return PermissionRule.of(normalizeLegacyToolName(toolName), behavior, source);
        }

        String content = unescapeRuleContent(rawContent);
        return PermissionRule.withPattern(normalizeLegacyToolName(toolName), behavior, source, content);
    }

    /** Normalizes legacy tool names (e.g. Task → Agent, KillShell → TaskStop). */
    public static String normalizeLegacyToolName(String name) {
        return LegacyToolNames.normalize(name);
    }

    /**
     * Returns the deny rule (if any) that targets a specific agent.
     */
    public static Optional<PermissionRule> getDenyRuleForAgent(ToolPermissionContext ctx, String agentId) {
        return ctx.rules().stream()
            .filter(r -> r.behavior() == PermissionBehavior.DENY)
            .filter(r -> Strings.CS.equals("Agent", normalizeLegacyToolName(r.toolName())))
            .filter(r -> r.pattern().map(p -> matchWildcardPattern(p, agentId)).orElse(true))
            .findFirst();
    }

    /**
     * Returns the subset of {@code agentIds} that have a deny rule.
     */
    public static List<String> filterDeniedAgents(ToolPermissionContext ctx, List<String> agentIds) {
        return agentIds.stream()
            .filter(id -> getDenyRuleForAgent(ctx, id).isPresent())
            .toList();
    }

    /**
     * Human-readable source name for a permission rule, for dialog/warning text.
     */
    public static String permissionRuleSourceDisplayString(RuleSource source) {
        return source.displayName();
    }

    /** Escapes parentheses and backslashes in rule content for storage. */
    public static String escapeRuleContent(String content) {
        return content.replace("\\", "\\\\")
                      .replace("(", "\\(")
                      .replace(")", "\\)");
    }

    /** Reverses escapeRuleContent. */
    public static String unescapeRuleContent(String content) {
        return content.replace("\\(", "(")
                      .replace("\\)", ")")
                      .replace("\\\\", "\\");
    }

    /** Converts a rule back to its string representation (ToolName or ToolName(content)). */
    public static String permissionRuleToString(PermissionRule rule) {
        return rule.pattern()
            .map(p -> rule.toolName() + "(" + escapeRuleContent(p) + ")")
            .orElse(rule.toolName());
    }

    /**
     * Extracts the prefix from the legacy {@code:*} syntax (e.g.
     */
    public static String permissionRuleExtractPrefix(String permissionRule) {
        if (permissionRule == null || !Strings.CS.endsWith(permissionRule, ":*")) {
            return null;
        }
        String prefix = permissionRule.substring(0, permissionRule.length() - 2);
        return prefix.isEmpty() ? null : prefix;
    }

    /**
     * Finds the index of the first unescaped occurrence of {@code ch} in {@code str}.
     * A char is escaped if preceded by an odd number of backslashes.
     */
    static int findFirstUnescapedChar(String str, char ch) {
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == ch) {
                int backslashes = 0;
                int j = i - 1;
                while (j >= 0 && str.charAt(j) == '\\') { backslashes++; j--; }
                if (backslashes % 2 == 0) return i;
            }
        }
        return -1;
    }

    /**
     * Finds the index of the last unescaped occurrence of {@code ch} in {@code str}.
     */
    static int findLastUnescapedChar(String str, char ch) {
        for (int i = str.length() - 1; i >= 0; i--) {
            if (str.charAt(i) == ch) {
                int backslashes = 0;
                int j = i - 1;
                while (j >= 0 && str.charAt(j) == '\\') { backslashes++; j--; }
                if (backslashes % 2 == 0) return i;
            }
        }
        return -1;
    }

}
