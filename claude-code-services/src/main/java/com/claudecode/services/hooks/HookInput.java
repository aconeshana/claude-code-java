package com.claudecode.services.hooks;

import com.claudecode.session.SessionManager;
import com.claudecode.tools.cron.ScheduledTaskHooks;
import com.claudecode.core.serialization.JsonUtils;
import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Input data passed to hook commands as context.
 */
public record HookInput(
    HookEvent event,
    Optional<String> toolName,
    Optional<JsonNode> toolInput,
    Optional<JsonNode> toolOutput,
    Optional<String> toolUseId,
    Map<String, Object> extra
) {

    public static HookInput forPreToolUse(String toolName, JsonNode input, String toolUseId,
                                          String sessionId, String cwd, String permissionMode) {
        Map<String, Object> base = baseFields(sessionId, cwd, permissionMode);
        return new HookInput(HookEvent.PRE_TOOL_USE, Optional.of(toolName),
            Optional.ofNullable(input), Optional.empty(), Optional.of(toolUseId), base);
    }

    public static HookInput forPreToolUse(String toolName, JsonNode input, String toolUseId) {
        return forPreToolUse(toolName, input, toolUseId, null, null, null);
    }

    public static HookInput forPostToolUse(String toolName, JsonNode input, JsonNode output,
                                           String toolUseId, String sessionId, String cwd,
                                           String permissionMode) {
        Map<String, Object> base = baseFields(sessionId, cwd, permissionMode);
        return new HookInput(HookEvent.POST_TOOL_USE, Optional.of(toolName),
            Optional.ofNullable(input), Optional.ofNullable(output), Optional.of(toolUseId), base);
    }

    public static HookInput forPostToolUse(String toolName, JsonNode input, JsonNode output,
                                           String toolUseId) {
        return forPostToolUse(toolName, input, output, toolUseId, null, null, null);
    }

    public static HookInput forPostToolUseFailure(
            String toolName, JsonNode input, String toolUseId, String error,
            boolean isInterrupt, String sessionId, String cwd, String permissionMode) {
        Map<String, Object> extra = baseFields(sessionId, cwd, permissionMode);
        extra.put("error", error == null ? "" : error);
        if (isInterrupt) extra.put("is_interrupt", true);
        return new HookInput(HookEvent.POST_TOOL_USE_FAILURE, Optional.of(toolName),
            Optional.ofNullable(input), Optional.empty(), Optional.of(toolUseId), extra);
    }

    public static HookInput forPostToolBatch(JsonNode toolCalls, String sessionId,
                                             String cwd, String permissionMode) {
        Map<String, Object> extra = baseFields(sessionId, cwd, permissionMode);
        extra.put("tool_calls", toolCalls != null ? toolCalls : List.of());
        return new HookInput(HookEvent.POST_TOOL_BATCH, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), extra);
    }


    public static HookInput forStop(boolean stopHookActive, String lastAssistantMessage,
                                    String sessionId, String cwd, String permissionMode) {
        return forStop(stopHookActive, lastAssistantMessage, sessionId, cwd,
            permissionMode, null, null);
    }


    public static HookInput forStop(boolean stopHookActive, String lastAssistantMessage,
                                    String sessionId, String cwd, String permissionMode,
                                    String promptId, String effortLevel) {
        Map<String, Object> extra = baseFields(sessionId, cwd, permissionMode);
        if (StringUtils.isNotBlank(promptId)) {
            extra.put("prompt_id", promptId);
        }
        if (StringUtils.isNotBlank(effortLevel)) {
            extra.put("effort", Map.of("level", effortLevel));
        }
        extra.put("stop_hook_active", stopHookActive);
        if (StringUtils.isNotBlank(lastAssistantMessage)) {
            extra.put("last_assistant_message", lastAssistantMessage);
        }
        extra.put("background_tasks", List.of());
        extra.put("session_crons", ScheduledTaskHooks.snapshot());
        return new HookInput(HookEvent.STOP, Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), extra);
    }

    public static HookInput forStop(boolean stopHookActive) {
        return forStop(stopHookActive, null, null, null, null);
    }


    public static HookInput forStopFailure(String error, String lastAssistantMessage,
                                           String sessionId, String cwd) {
        Map<String, Object> extra = baseFields(sessionId, cwd, null);
        extra.put("error", StringUtils.isNotBlank(error) ? error : "unknown");
        if (StringUtils.isNotBlank(lastAssistantMessage)) {
            extra.put("last_assistant_message", lastAssistantMessage);
        }
        return new HookInput(HookEvent.STOP_FAILURE, Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), extra);
    }


    public static HookInput forUserPromptSubmit(String prompt, String sessionId,
                                                String cwd, String permissionMode) {
        return forUserPromptSubmit(prompt, sessionId, cwd, permissionMode, null);
    }


    public static HookInput forUserPromptSubmit(String prompt, String sessionId,
                                                String cwd, String permissionMode,
                                                String promptId) {
        Map<String, Object> extra = baseFields(sessionId, cwd, permissionMode);
        if (StringUtils.isNotBlank(promptId)) {
            extra.put("prompt_id", promptId);
        }
        extra.put("prompt", prompt != null ? prompt : "");
        return new HookInput(HookEvent.USER_PROMPT_SUBMIT, Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), extra);
    }


    public static HookInput forUserPromptExpansion(
            String expansionType, String commandName, String commandArgs,
            String commandSource, String prompt, String sessionId, String cwd,
            String permissionMode) {
        Map<String, Object> extra = baseFields(sessionId, cwd, permissionMode);
        extra.put("expansion_type", expansionType != null ? expansionType : "slash_command");
        extra.put("command_name", commandName != null ? commandName : "");
        extra.put("command_args", commandArgs != null ? commandArgs : "");
        extra.put("command_source", commandSource != null ? commandSource : "");
        extra.put("prompt", prompt != null ? prompt : "");
        return new HookInput(HookEvent.USER_PROMPT_EXPANSION, Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), extra);
    }


    public static HookInput forSessionStart(String source, String sessionId, String cwd) {
        Map<String, Object> extra = baseFields(sessionId, cwd, null);
        extra.put("source", source);
        return new HookInput(HookEvent.SESSION_START, Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), extra);
    }

    public static HookInput forSessionStart(String source) {
        return forSessionStart(source, null, null);
    }


    public static HookInput forSubagentStart(String agentId, String agentType,
                                             String sessionId, String cwd) {
        Map<String, Object> extra = baseFields(sessionId, cwd, null);
        extra.put("agent_id", agentId != null ? agentId : "");
        extra.put("agent_type", agentType != null ? agentType : "");
        return new HookInput(HookEvent.SUBAGENT_START, Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), extra);
    }


    public static HookInput forSubagentStop(
            String agentId, String agentTranscriptPath, String agentType,
            boolean stopHookActive, String lastAssistantMessage,
            String sessionId, String cwd, String permissionMode,
            String promptId, String effortLevel) {
        Map<String, Object> extra = baseFields(sessionId, cwd, permissionMode);
        if (StringUtils.isNotBlank(promptId)) {
            extra.put("prompt_id", promptId);
        }
        if (StringUtils.isNotBlank(effortLevel)) {
            extra.put("effort", Map.of("level", effortLevel));
        }
        extra.put("agent_id", agentId != null ? agentId : "");
        extra.put("agent_transcript_path",
            agentTranscriptPath != null ? agentTranscriptPath : "");
        extra.put("agent_type", agentType != null ? agentType : "");
        extra.put("stop_hook_active", stopHookActive);
        if (StringUtils.isNotBlank(lastAssistantMessage)) {
            extra.put("last_assistant_message", lastAssistantMessage);
        }
        extra.put("background_tasks", List.of());
        extra.put("session_crons", ScheduledTaskHooks.snapshot());
        return new HookInput(HookEvent.SUBAGENT_STOP, Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), extra);
    }


    public static HookInput forSessionEnd(String reason, String sessionId, String cwd) {
        Map<String, Object> extra = baseFields(sessionId, cwd, null);
        extra.put("reason", reason);
        return new HookInput(HookEvent.SESSION_END, Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), extra);
    }

    public static HookInput forSessionEnd(String reason) {
        return forSessionEnd(reason, null, null);
    }

/** matches the CwdChanged payload: base cwd is the new cwd plus old_cwd/new_cwd. */
    public static HookInput forCwdChanged(String oldCwd, String newCwd, String sessionId) {
        Map<String, Object> extra = baseFields(sessionId, newCwd, null);
        extra.put("old_cwd", oldCwd);
        extra.put("new_cwd", newCwd);
        return new HookInput(HookEvent.CWD_CHANGED, Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), extra);
    }


    public static HookInput forConfigChange(String source, String filePath,
                                            String sessionId, String cwd) {
        Map<String, Object> extra = baseFields(sessionId, cwd, null);
        extra.put("source", source);
        if (filePath != null) extra.put("file_path", filePath);
        return new HookInput(HookEvent.CONFIG_CHANGE, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), extra);
    }


    public static HookInput forWorktreeCreate(String name, String sessionId, String cwd) {
        Map<String, Object> extra = baseFields(sessionId, cwd, null);
        extra.put("name", name);
        return new HookInput(HookEvent.WORKTREE_CREATE, Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), extra);
    }


    public static HookInput forWorktreeRemove(String worktreePath, String sessionId, String cwd) {
        Map<String, Object> extra = baseFields(sessionId, cwd, null);
        extra.put("worktree_path", worktreePath);
        return new HookInput(HookEvent.WORKTREE_REMOVE, Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), extra);
    }


    public static HookInput forTaskCreated(String taskId, String subject, String description,
                                            String sessionId, String cwd, String permissionMode) {
        return forTaskCreated(taskId, subject, description, null, null,
            sessionId, cwd, permissionMode);
    }


    public static HookInput forTaskCreated(
            String taskId, String subject, String description,
            String teammateName, String teamName,
            String sessionId, String cwd, String permissionMode) {
        Map<String, Object> extra = baseFields(sessionId, cwd, permissionMode);
        extra.put("task_id", taskId);
        extra.put("task_subject", subject);
        if (description != null) {
            extra.put("task_description", description);
        }
        if (teammateName != null) extra.put("teammate_name", teammateName);
        if (teamName != null) extra.put("team_name", teamName);
        return new HookInput(HookEvent.TASK_CREATED, Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), extra);
    }


    public static HookInput forTaskCompleted(String taskId, String subject, String description,
                                              String sessionId, String cwd, String permissionMode) {
        return forTaskCompleted(taskId, subject, description, null, null,
            sessionId, cwd, permissionMode);
    }


    public static HookInput forTaskCompleted(
            String taskId, String subject, String description,
            String teammateName, String teamName,
            String sessionId, String cwd, String permissionMode) {
        Map<String, Object> extra = baseFields(sessionId, cwd, permissionMode);
        extra.put("task_id", taskId);
        extra.put("task_subject", subject);
        if (description != null) {
            extra.put("task_description", description);
        }
        if (teammateName != null) extra.put("teammate_name", teammateName);
        if (teamName != null) extra.put("team_name", teamName);
        return new HookInput(HookEvent.TASK_COMPLETED, Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), extra);
    }


    public static HookInput forPreCompact(String trigger, String customInstructions,
                                           long preTokenCount, String sessionId, String cwd) {
        return forPreCompact(trigger, customInstructions, preTokenCount,
            sessionId, cwd, null);
    }


    public static HookInput forPreCompact(String trigger, String customInstructions,
                                           long preTokenCount, String sessionId, String cwd,
                                           String promptId) {
        Map<String, Object> extra = baseFields(sessionId, cwd, null);
        extra.put("trigger", trigger);
        extra.put("custom_instructions",
            StringUtils.isNotBlank(customInstructions) ? customInstructions : null);
        if (StringUtils.isNotBlank(promptId)) {
            extra.put("prompt_id", promptId);
        }
        return new HookInput(HookEvent.PRE_COMPACT, Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), extra);
    }


    public static HookInput forPostCompact(String trigger, String compactSummary,
                                            long postTokenCount, String sessionId, String cwd) {
        Map<String, Object> extra = baseFields(sessionId, cwd, null);
        extra.put("trigger", trigger);
        if (StringUtils.isNotBlank(compactSummary)) {
            extra.put("compact_summary", compactSummary);
        }
        return new HookInput(HookEvent.POST_COMPACT, Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), extra);
    }

    /**
     * Fired once per memory file loaded into the system-prompt tail block.
     */
    public static HookInput forInstructionsLoaded(String filePath, String memoryType,
                                                   String loadReason, List<String> globs,
                                                   String sessionId, String cwd) {
        Map<String, Object> extra = baseFields(sessionId, cwd, null);
        extra.put("file_path", filePath);
        extra.put("memory_type", memoryType);
        extra.put("load_reason", loadReason);
        if (globs != null) extra.put("globs", globs);
        return new HookInput(HookEvent.INSTRUCTIONS_LOADED, Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), extra);
    }

    public static HookInput forPermissionRequest(String toolName, JsonNode input, String toolUseId,
                                                 String sessionId, String cwd, String permissionMode) {
        Map<String, Object> base = baseFields(sessionId, cwd, permissionMode);
        return new HookInput(HookEvent.PERMISSION_REQUEST, Optional.of(toolName),
            Optional.ofNullable(input), Optional.empty(), Optional.of(toolUseId), base);
    }

    public static HookInput forPermissionRequest(String toolName, JsonNode input, String toolUseId) {
        return forPermissionRequest(toolName, input, toolUseId, null, null, null);
    }

    public static HookInput forPermissionDenied(
            String toolName, JsonNode input, String toolUseId, String reason,
            String sessionId, String cwd, String permissionMode) {
        Map<String, Object> extra = baseFields(sessionId, cwd, permissionMode);
        extra.put("reason", reason != null ? reason : "");
        return new HookInput(HookEvent.PERMISSION_DENIED, Optional.of(toolName),
            Optional.ofNullable(input), Optional.empty(), Optional.of(toolUseId), extra);
    }

    public static HookInput forNotification(String message, String title,
                                             String notificationType,
                                             String sessionId, String cwd) {
        Map<String, Object> extra = baseFields(sessionId, cwd, null);
        extra.put("message", message != null ? message : "");
        if (StringUtils.isNotBlank(title)) extra.put("title", title);
        extra.put("notification_type", notificationType != null ? notificationType : "");
        return new HookInput(HookEvent.NOTIFICATION, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), extra);
    }

    public static HookInput forSetup(String trigger, String sessionId, String cwd) {
        Map<String, Object> extra = baseFields(sessionId, cwd, null);
        extra.put("trigger", trigger);
        return new HookInput(HookEvent.SETUP, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), extra);
    }

    public static HookInput forTeammateIdle(String teammateName, String teamName,
                                            String sessionId, String cwd,
                                            String permissionMode) {
        Map<String, Object> extra = baseFields(sessionId, cwd, permissionMode);
        extra.put("teammate_name", teammateName);
        extra.put("team_name", teamName);
        return new HookInput(HookEvent.TEAMMATE_IDLE, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), extra);
    }

    public static HookInput forFileChanged(String filePath, String fileEvent,
                                           String sessionId, String cwd) {
        Map<String, Object> extra = baseFields(sessionId, cwd, null);
        extra.put("file_path", filePath);
        extra.put("event", fileEvent);
        return new HookInput(HookEvent.FILE_CHANGED, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), extra);
    }

    public static HookInput forElicitation(
            String serverName, String message, String mode, String url,
            String elicitationId, JsonNode requestedSchema,
            String sessionId, String cwd, String permissionMode) {
        Map<String, Object> extra = baseFields(sessionId, cwd, permissionMode);
        extra.put("mcp_server_name", serverName);
        extra.put("message", message);
        if (StringUtils.isNotBlank(mode)) extra.put("mode", mode);
        if (StringUtils.isNotBlank(url)) extra.put("url", url);
        if (StringUtils.isNotBlank(elicitationId)) extra.put("elicitation_id", elicitationId);
        if (requestedSchema != null) extra.put("requested_schema", requestedSchema);
        return new HookInput(HookEvent.ELICITATION, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), extra);
    }

    public static HookInput forElicitationResult(
            String serverName, String action, JsonNode content, String mode,
            String elicitationId, String sessionId, String cwd, String permissionMode) {
        Map<String, Object> extra = baseFields(sessionId, cwd, permissionMode);
        extra.put("mcp_server_name", serverName);
        extra.put("action", action);
        if (content != null) extra.put("content", content);
        if (StringUtils.isNotBlank(mode)) extra.put("mode", mode);
        if (StringUtils.isNotBlank(elicitationId)) extra.put("elicitation_id", elicitationId);
        return new HookInput(HookEvent.ELICITATION_RESULT, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), extra);
    }


    public static HookInput forMessageDisplay(
            String turnId, String messageId, int index, boolean finalDelta, String delta,
            String sessionId, String cwd) {
        Map<String, Object> extra = baseFields(sessionId, cwd, null);
        extra.put("turn_id", turnId != null ? turnId : "");
        extra.put("message_id", messageId != null ? messageId : "");
        extra.put("index", index);
        extra.put("final", finalDelta);
        extra.put("delta", delta != null ? delta : "");
        return new HookInput(HookEvent.MESSAGE_DISPLAY, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), extra);
    }

    public static HookInput forEvent(HookEvent event) {
        return new HookInput(event, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Map.of());
    }

    public static HookInput forEvent(HookEvent event, String sessionId, String cwd) {
        return new HookInput(event, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), baseFields(sessionId, cwd, null));
    }


    private static Map<String, Object> baseFields(String sessionId, String cwd, String permissionMode) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (sessionId != null) m.put("session_id", sessionId);
        if (sessionId != null && cwd != null) {
            m.put("transcript_path", new SessionManager(cwd).getSessionFile(sessionId).toString());
        }
        if (cwd != null) m.put("cwd", cwd);
        if (permissionMode != null) m.put("permission_mode", permissionMode);
        return m;
    }

    /**
     * Serializes this input to a JSON string matching the compatibility Claude Code hook format.
     */
    public String toJson() {
        ObjectNode node = JsonUtils.getMapper().createObjectNode();

        List<String> prefixFields = List.of(
            "session_id", "transcript_path", "cwd", "prompt_id",
            "permission_mode", "effort");
        prefixFields.forEach(field -> putExtra(node, field));
        node.put("hook_event_name", event.displayName());
        extra.forEach((key, _) -> {
            if (!prefixFields.contains(key)) putExtra(node, key);
        });
        // Tool fields follow event-specific context, matching hook input shape.
        toolName.ifPresent(n -> node.put("tool_name", n));
        toolInput.ifPresent(i -> node.set("tool_input", i));
        toolOutput.ifPresent(o -> node.set("tool_response", o));
        toolUseId.ifPresent(id -> node.put("tool_use_id", id));
        return node.toString();
    }

    private void putExtra(ObjectNode node, String key) {
        if (!extra.containsKey(key)) return;
        Object value = extra.get(key);
        if (value == null) node.putNull(key);
        else node.set(key, JsonUtils.getMapper().valueToTree(value));
    }
}
