package com.claudecode.services.hooks;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.permissions.RuleSource;
import com.claudecode.services.config.SettingsSources;
import com.claudecode.services.config.SettingsPaths;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Static utilities for the hooks configuration browser.
 */
public final class HooksConfigManager {

    private HooksConfigManager() {}

    // ── Event metadata ────────────────────────────────────────────────────────

    /**
     * Returns display metadata for all 30 hook events.
     */
    public static Map<HookEvent, HookEventMetadata> getHookEventMetadata(List<String> toolNames) {
        String placeholder = toolNames.isEmpty()
            ? "bash|read|write|edit"
            : toolNames.stream().limit(6).collect(Collectors.joining("|"));
        HookEventMetadata.MatcherMetadata toolMatcher =
            new HookEventMetadata.MatcherMetadata(placeholder, "tool_name");


        Map<HookEvent, HookEventMetadata> map = new LinkedHashMap<>();
        map.put(HookEvent.PRE_TOOL_USE, new HookEventMetadata(
            "Before tool execution",
            """
            Input to command is JSON of tool call arguments.
            Exit code 0 - stdout/stderr not shown
            Exit code 2 - show stderr to model and block tool call
            Other exit codes - show stderr to user only but continue with tool call""",
            toolMatcher));
        map.put(HookEvent.POST_TOOL_USE, new HookEventMetadata(
            "After tool execution",
            """
            Input to command is JSON with fields "inputs" (tool call arguments) and "response" (tool call response).
            Exit code 0 - stdout shown in transcript mode (ctrl+o)
            Exit code 2 - show stderr to model immediately
            Other exit codes - show stderr to user only""",
            toolMatcher));
        map.put(HookEvent.POST_TOOL_USE_FAILURE, new HookEventMetadata(
            "After tool execution fails",
            """
            Input to command is JSON with tool_name, tool_input, tool_use_id, error, error_type, is_interrupt, and is_timeout.
            Exit code 0 - stdout shown in transcript mode (ctrl+o)
            Exit code 2 - show stderr to model immediately
            Other exit codes - show stderr to user only""",
            toolMatcher));
        map.put(HookEvent.POST_TOOL_BATCH, new HookEventMetadata(
            "After a batch of tool calls resolves",
            """
            Fires once after every tool call in a batch has resolved, before the next model request. Input includes tool_calls (array of {tool_name, tool_input, tool_use_id, tool_response}).
            Return additionalContext via hookSpecificOutput to inject context once for the whole batch.
            Exit code 2 - stop the agentic loop (stderr shown to user only)
            Other exit codes - show stderr to user only""",
            null));
        map.put(HookEvent.PERMISSION_DENIED, new HookEventMetadata(
            "After auto mode classifier denies a tool call",
            """
            Input to command is JSON with tool_name, tool_input, tool_use_id, and reason.
            Return {"hookSpecificOutput":{"hookEventName":"PermissionDenied","retry":true}} to tell the model it may retry.
            Exit code 0 - stdout shown in transcript mode (ctrl+o)
            Other exit codes - show stderr to user only""",
            toolMatcher));
        map.put(HookEvent.NOTIFICATION, new HookEventMetadata(
            "When notifications are sent",
            """
            Input to command is JSON with notification message and type.
            Exit code 0 - stdout/stderr not shown
            Other exit codes - show stderr to user only""",
            new HookEventMetadata.MatcherMetadata(
                "permission_prompt|idle_prompt|auth_success|elicitation_dialog|elicitation_complete|elicitation_response", "notification_type")));
        map.put(HookEvent.USER_PROMPT_SUBMIT, new HookEventMetadata(
            "When the user submits a prompt",
            """
            Input to command is JSON with original user prompt text.
            Exit code 0 - stdout shown to Claude
            Exit code 2 - block processing, erase original prompt, and show stderr to user only
            Other exit codes - show stderr to user only""",
            null));
        map.put(HookEvent.USER_PROMPT_EXPANSION, new HookEventMetadata(
            "When a user-typed slash command expands into a prompt",
            """
            Input to command is JSON with expansion_type, command_name, command_args, command_source, and original prompt.
            Exit code 0 - stdout shown to Claude
            Exit code 2 - block expansion and show stderr to user only
            Other exit codes - show stderr to user only""",
            new HookEventMetadata.MatcherMetadata("", "command_name")));
        map.put(HookEvent.SESSION_START, new HookEventMetadata(
            "When a new session is started",
            """
            Input to command is JSON with session start source.
            Exit code 0 - stdout shown to Claude
            Blocking errors are ignored
            Other exit codes - show stderr to user only""",
            new HookEventMetadata.MatcherMetadata("startup|resume|clear|compact", "source")));
        map.put(HookEvent.STOP, new HookEventMetadata(
            "Right before Claude concludes its response",
            """
            Exit code 0 - stdout/stderr not shown
            Exit code 2 - show stderr to model and continue conversation
            Other exit codes - show stderr to user only""",
            null));
        map.put(HookEvent.STOP_FAILURE, new HookEventMetadata(
            "When the turn ends due to an API error",
            "Fires instead of Stop when an API error (rate limit, auth failure, etc.) ended the turn. "
            + "Fire-and-forget — hook output and exit codes are ignored.",
            new HookEventMetadata.MatcherMetadata(
                "rate_limit|authentication_failed|billing_error|invalid_request|server_error|max_output_tokens|unknown", "error")));
        map.put(HookEvent.SUBAGENT_START, new HookEventMetadata(
            "When a subagent (Agent tool call) is started",
            """
            Input to command is JSON with agent_id and agent_type.
            Exit code 0 - stdout shown to subagent
            Blocking errors are ignored
            Other exit codes - show stderr to user only""",
            new HookEventMetadata.MatcherMetadata("", "agent_type")));
        map.put(HookEvent.SUBAGENT_STOP, new HookEventMetadata(
            "Right before a subagent (Agent tool call) concludes its response",
            """
            Input to command is JSON with agent_id, agent_type, and agent_transcript_path.
            Exit code 0 - stdout/stderr not shown
            Exit code 2 - show stderr to subagent and continue having it run
            Other exit codes - show stderr to user only""",
            new HookEventMetadata.MatcherMetadata("", "agent_type")));
        map.put(HookEvent.PRE_COMPACT, new HookEventMetadata(
            "Before conversation compaction",
            """
            Input to command is JSON with compaction details.
            Exit code 0 - stdout appended as custom compact instructions
            Exit code 2 - block compaction
            Other exit codes - show stderr to user only but continue with compaction""",
            new HookEventMetadata.MatcherMetadata("manual|auto", "trigger")));
        map.put(HookEvent.POST_COMPACT, new HookEventMetadata(
            "After conversation compaction",
            """
            Input to command is JSON with compaction details and the summary.
            Exit code 0 - stdout shown to user
            Other exit codes - show stderr to user only""",
            new HookEventMetadata.MatcherMetadata("manual|auto", "trigger")));
        map.put(HookEvent.SESSION_END, new HookEventMetadata(
            "When a session is ending",
            """
            Input to command is JSON with session end reason.
            Exit code 0 - command completes successfully
            Other exit codes - show stderr to user only""",
            new HookEventMetadata.MatcherMetadata("clear|logout|prompt_input_exit|other", "reason")));
        map.put(HookEvent.PERMISSION_REQUEST, new HookEventMetadata(
            "When a permission dialog is displayed",
            """
            Input to command is JSON with tool_name, tool_input, and tool_use_id.
            Output JSON with hookSpecificOutput containing decision to allow or deny.
            Exit code 0 - use hook decision if provided
            Other exit codes - show stderr to user only""",
            toolMatcher));
        map.put(HookEvent.SETUP, new HookEventMetadata(
            "Repo setup hooks for init and maintenance",
            """
            Input to command is JSON with trigger (init or maintenance).
            Exit code 0 - stdout shown to Claude
            Blocking errors are ignored
            Other exit codes - show stderr to user only""",
            new HookEventMetadata.MatcherMetadata("init|maintenance", "trigger")));
        map.put(HookEvent.TEAMMATE_IDLE, new HookEventMetadata(
            "When a teammate is about to go idle",
            """
            Input to command is JSON with teammate_name and team_name.
            Exit code 0 - stdout/stderr not shown
            Exit code 2 - show stderr to teammate and prevent idle (teammate continues working)
            Other exit codes - show stderr to user only""",
            null));
        map.put(HookEvent.TASK_CREATED, new HookEventMetadata(
            "When a task is being created",
            """
            Input to command is JSON with task_id, task_subject, task_description, teammate_name, and team_name.
            Exit code 0 - stdout/stderr not shown
            Exit code 2 - show stderr to model and prevent task creation
            Other exit codes - show stderr to user only""",
            null));
        map.put(HookEvent.TASK_COMPLETED, new HookEventMetadata(
            "When a task is being marked as completed",
            """
            Input to command is JSON with task_id, task_subject, task_description, teammate_name, and team_name.
            Exit code 0 - stdout/stderr not shown
            Exit code 2 - show stderr to model and prevent task completion
            Other exit codes - show stderr to user only""",
            null));
        map.put(HookEvent.ELICITATION, new HookEventMetadata(
            "When an MCP server requests user input (elicitation)",
            """
            Input to command is JSON with mcp_server_name, message, and requested_schema.
            Output JSON with hookSpecificOutput containing action (accept/decline/cancel) and optional content.
            Exit code 0 - use hook response if provided
            Exit code 2 - deny the elicitation
            Other exit codes - show stderr to user only""",
            new HookEventMetadata.MatcherMetadata("", "mcp_server_name")));
        map.put(HookEvent.ELICITATION_RESULT, new HookEventMetadata(
            "After a user responds to an MCP elicitation",
            """
            Input to command is JSON with mcp_server_name, action, content, mode, and elicitation_id.
            Output JSON with hookSpecificOutput containing optional action and content to override the response.
            Exit code 0 - use hook response if provided
            Exit code 2 - block the response (action becomes decline)
            Other exit codes - show stderr to user only""",
            new HookEventMetadata.MatcherMetadata("", "mcp_server_name")));
        map.put(HookEvent.CONFIG_CHANGE, new HookEventMetadata(
            "When configuration files change during a session",
            """
            Input to command is JSON with source (user_settings, project_settings, local_settings, policy_settings, skills) and file_path.
            Exit code 0 - allow the change
            Exit code 2 - block the change from being applied to the session
            Other exit codes - show stderr to user only""",
            new HookEventMetadata.MatcherMetadata(
                "user_settings|project_settings|local_settings|policy_settings|skills", "source")));
        map.put(HookEvent.INSTRUCTIONS_LOADED, new HookEventMetadata(
            "When an instruction file (CLAUDE.md or rule) is loaded",
            """
            Input to command is JSON with file_path, memory_type (User, Project, Local, Managed), \
            load_reason (session_start, nested_traversal, path_glob_match, include, compact), \
            globs (optional — the paths: frontmatter patterns that matched), \
            trigger_file_path (optional — the file Claude touched that caused the load), \
            and parent_file_path (optional — the file that @-included this one).
            Exit code 0 - command completes successfully
            Other exit codes - show stderr to user only
            This hook is observability-only and does not support blocking.""",
            new HookEventMetadata.MatcherMetadata(
                "session_start|nested_traversal|path_glob_match|include|compact", "load_reason")));
        map.put(HookEvent.WORKTREE_CREATE, new HookEventMetadata(
            "Create an isolated worktree for VCS-agnostic isolation",
            """
            Input to command is JSON with name (suggested worktree slug).
            Stdout should contain the absolute path to the created worktree directory.
            Exit code 0 - worktree created successfully
            Other exit codes - worktree creation failed""",
            null));
        map.put(HookEvent.WORKTREE_REMOVE, new HookEventMetadata(
            "Remove a previously created worktree",
            """
            Input to command is JSON with worktree_path (absolute path to worktree).
            Exit code 0 - worktree removed successfully
            Other exit codes - show stderr to user only""",
            null));
        map.put(HookEvent.CWD_CHANGED, new HookEventMetadata(
            "After the working directory changes",
            """
            Input to command is JSON with old_cwd and new_cwd.
            CLAUDE_ENV_FILE is set — write bash exports there to apply env to subsequent BashTool commands.
            Hook output can include hookSpecificOutput.watchPaths (array of absolute paths) to register with the FileChanged watcher.
            Exit code 0 - command completes successfully
            Other exit codes - show stderr to user only""",
            null));
        map.put(HookEvent.FILE_CHANGED, new HookEventMetadata(
            "When a watched file changes",
            """
            Input to command is JSON with file_path and event (change, add, unlink).
            CLAUDE_ENV_FILE is set — write bash exports there to apply env to subsequent BashTool commands.
            The matcher field specifies filenames to watch in the current directory (e.g. ".envrc|.env").
            Hook output can include hookSpecificOutput.watchPaths (array of absolute paths) to dynamically update the watch list.
            Exit code 0 - command completes successfully
            Other exit codes - show stderr to user only""",
            null));
        map.put(HookEvent.MESSAGE_DISPLAY, new HookEventMetadata(
            "While assistant message text is displayed",
            """
            Input to command is JSON with turn_id, message_id, index, final, and delta (the newly completed lines).
            Output JSON with hookSpecificOutput containing displayContent to replace the delta on screen.
            Display-only: the stored message and what the model sees are untouched.
            Exit code 0 - use hook response if provided
            Other exit codes - display the original delta""",
            null));
        return Collections.unmodifiableMap(map);
    }

    // ── Multi-source loader ───────────────────────────────────────────────────

    /**
     * Loads all hooks from the three on-disk settings sources.
     */
    public static List<IndividualHookConfig> getAllHooks(String workingDirectory) {
        List<IndividualHookConfig> result = new ArrayList<>();

        Set<Path> seenFiles = new HashSet<>();
        JsonNode policy = SettingsSources.settingsForSource(
            RuleSource.POLICY_SETTINGS, workingDirectory);
        boolean restrictedToManagedOnly = policy != null
            && policy.has("allowManagedHooksOnly")
            && policy.get("allowManagedHooksOnly").isBoolean()
            && policy.get("allowManagedHooksOnly").asBoolean();
        if (!restrictedToManagedOnly) {
            addHooksFrom(RuleSource.USER_SETTINGS, workingDirectory, userSettingsPath(),
                         HookSource.USER_SETTINGS, result, seenFiles);
            if (StringUtils.isNotBlank(workingDirectory)) {
                addHooksFrom(RuleSource.PROJECT_SETTINGS, workingDirectory,
                             SettingsPaths.sessionProjectSettingsPath(workingDirectory),
                             HookSource.PROJECT_SETTINGS, result, seenFiles);
                addHooksFrom(RuleSource.LOCAL_SETTINGS, workingDirectory,
                             SettingsPaths.sessionLocalSettingsPath(workingDirectory),
                             HookSource.LOCAL_SETTINGS, result, seenFiles);
            }
        }
        return Collections.unmodifiableList(result);
    }

    static Path userSettingsPath() {
        return SettingsPaths.userSettingsPath();
    }

    private static void addHooksFrom(RuleSource ruleSource, String workingDirectory, Path path,
                                     HookSource source,
                                     List<IndividualHookConfig> out, Set<Path> seenFiles) {
        if (!Files.isRegularFile(path)) return;

        // distinct symlink aliases as distinct source paths while still collapsing the ordinary
        // user/project home-directory alias.
        if (!seenFiles.add(path.toAbsolutePath().normalize())) return;
        try {

            // sanitized source only when the complete settings file passes validation. Do not
            // parse raw JSON here: an unrelated schema error must suppress that source's hooks.
            ObjectNode root = SettingsSources.settingsForSource(ruleSource, workingDirectory);
            JsonNode hooksNode = root.get("hooks");
            if (hooksNode == null) return;
            if (hooksNode.isMissingNode() || hooksNode.isNull()) return;
            HooksSettings settings = HooksSettings.fromJson(hooksNode);
            for (Map.Entry<HookEvent, List<HookMatcher>> entry : settings.eventHooks().entrySet()) {
                for (HookMatcher matcher : entry.getValue()) {
                    String matcherStr = matcher.matcher().orElse(null);
                    for (HookCommand cmd : matcher.hooks()) {
                        out.add(new IndividualHookConfig(entry.getKey(), cmd, matcherStr, source, null));
                    }
                }
            }
        } catch (Exception _) {
            // Skip missing / malformed files silently
        }
    }

    /**
     * Returns the primary display text for a hook command — the command string, prompt text, or URL,
     * truncated to {@code maxLen} characters.
     */
    public static String getHookDisplayText(HookCommand command, int maxLen) {
// statusMessage takes display priority.
        Optional<String> sm = command.statusMessage();
        if (sm.isPresent() && !StringUtils.isBlank(sm.get())) {
            String msg = sm.get();
            return msg.length() <= maxLen ? msg : FormatUtils.truncate(msg, maxLen);
        }
        String raw = switch (command) {
            case BashCommandHook h -> h.command();
            case PromptHook h      -> h.prompt();
            case HttpHook h        -> h.url();
            case AgentHook h       -> h.prompt();
            case CallbackHook h    -> h.callbackId();
        };
        if (raw.length() <= maxLen) return raw;
        return FormatUtils.truncate(raw, maxLen);
    }

    /**
     * Returns the raw command/prompt/url for detail view, bypassing statusMessage.
     */
    public static String getRawHookContent(HookCommand command) {
        return switch (command) {
            case BashCommandHook h -> h.command();
            case PromptHook h      -> h.prompt();
            case HttpHook h        -> h.url();
            case AgentHook h       -> h.prompt();
            case CallbackHook h    -> h.callbackId();
        };
    }

}
