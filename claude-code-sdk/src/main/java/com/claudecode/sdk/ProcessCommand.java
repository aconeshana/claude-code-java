package com.claudecode.sdk;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic projection of SDK query options to the CLI child process.
query process option mapping.</li></ul>
 */
public record ProcessCommand(String executable, List<String> arguments, Path cwd,
                             Map<String, String> environment) {
    static ProcessCommand create(QueryOptions options) {
        QueryOptions o = options == null ? QueryOptions.builder().build() : options;
        if (o.canUseTool != null && StringUtils.isNotBlank(o.permissionPromptToolName)) {
            throw new IllegalArgumentException("canUseTool callback cannot be used with permissionPromptToolName");
        }
        if (o.sessionStore != null && !o.persistSession) {
            throw new IllegalArgumentException("sessionStore cannot be used with persistSession: false");
        }
        if (StringUtils.isNotBlank(o.model) && o.model.equals(o.fallbackModel)) {
            throw new IllegalArgumentException("Fallback model cannot be the same as the main model");
        }
        if (o.taskBudget != null && o.taskBudget <= 0) {
            throw new IllegalArgumentException("taskBudget must be a positive integer");
        }
        List<String> args = new ArrayList<>();
        String executable;
        if (o.executable != null) {
            if (Strings.CS.endsWith(o.executable.toString(), ".jar")) {
                executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
                args.add("-jar");
                args.add(o.executable.toString());
            } else executable = o.executable.toString();
            args.addAll(o.executableArguments);
        } else {
            executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            args.add("-cp");
            args.add(System.getProperty("java.class.path"));
            args.add("com.claudecode.cli.ClaudeCodeCli");
        }
        args.addAll(List.of("--output-format", "stream-json", "--verbose", "--input-format", "stream-json"));
        pair(args, "--thinking", o.thinking);
        pair(args, "--thinking-display", o.thinkingDisplay);
        pair(args, "--max-thinking-tokens", o.maxThinkingTokens);
        pair(args, "--effort", o.effort);
        pair(args, "--max-turns", o.maxTurns);
        pair(args, "--max-budget-usd", o.maxBudgetUsd);
        pair(args, "--task-budget", o.taskBudget);
        pair(args, "--model", o.model);
        pair(args, "--api-key", o.apiKey);
        pair(args, "--base-url", o.baseUrl);
        pair(args, "--agent", o.agent);
        if (!o.betas.isEmpty()) pair(args, "--betas", String.join(",", o.betas));
        if (o.outputSchema != null) pair(args, "--json-schema", o.outputSchema.toString());
        if (o.debugFile != null) pair(args, "--debug-file", o.debugFile);
        else flag(args, "--debug", o.debug);
        if (o.canUseTool != null) pair(args, "--permission-prompt-tool", "stdio");
        else pair(args, "--permission-prompt-tool", o.permissionPromptToolName);
        flag(args, "--continue", o.continueConversation);
        pair(args, "--resume", o.resume);
        if (!o.allowedTools.isEmpty()) pair(args, "--allowedTools", String.join(",", o.allowedTools));
        if (!o.disallowedTools.isEmpty()) pair(args, "--disallowedTools", String.join(",", o.disallowedTools));
        if (o.tools != null) pair(args, "--tools", String.join(",", o.tools));
        if (!o.mcpServers.isEmpty()) {
            pair(args, "--mcp-config", JsonUtils.getMapper().valueToTree(Map.of("mcpServers", o.mcpServers)).toString());
        }
        flag(args, "--strict-mcp-config", o.strictMcpConfig);
        pair(args, "--permission-mode", o.permissionMode);
        flag(args, "--allow-dangerously-skip-permissions", o.allowDangerouslySkipPermissions);
        pair(args, "--fallback-model", o.fallbackModel);
        flag(args, "--include-hook-events", o.includeHookEvents);
        flag(args, "--include-partial-messages", o.includePartialMessages);
        flag(args, "--session-mirror", o.sessionStore != null);
        o.additionalDirectories.forEach(path -> pair(args, "--add-dir", path));
        o.plugins.forEach(path -> pair(args, "--plugin-dir", path));
        o.pluginsNoMcp.forEach(path -> pair(args, "--plugin-dir-no-mcp", path));
        flag(args, "--fork-session", o.forkSession);
        pair(args, "--resume-session-at", o.resumeSessionAt);
        pair(args, "--session-id", o.sessionId);
        flag(args, "--no-session-persistence", !o.persistSession);
        pair(args, "--managed-settings", o.managedSettings);
        ObjectNode settings = o.settings == null
            ? JsonUtils.getMapper().createObjectNode() : o.settings.value();
        if (o.sandbox != null) settings.set("sandbox", o.sandbox.deepCopy());
        if (!settings.isEmpty()) pair(args, "--settings", settings.toString());
        if (!o.settingSources.isEmpty()) {
            pair(args, "--setting-sources", String.join(",", o.settingSources));
        }
        pair(args, "--system-prompt", o.systemPrompt);
        pair(args, "--append-system-prompt", o.appendSystemPrompt);
        pair(args, "--name", o.title);
        args.addAll(o.extraArguments);
        Map<String, String> env = new LinkedHashMap<>(System.getenv());
        env.putAll(o.env);
        env.putIfAbsent("CLAUDE_CODE_ENTRYPOINT", "sdk-ts");
        return new ProcessCommand(executable, List.copyOf(args), o.cwd, Map.copyOf(env));
    }

    private static void pair(List<String> args, String name, Object value) {
        if (value != null && !StringUtils.isBlank(value.toString())) {
            args.add(name);
            args.add(value.toString());
        }
    }
    private static void flag(List<String> args, String name, boolean enabled) { if (enabled) args.add(name); }
}
