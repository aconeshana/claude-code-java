package com.claudecode.core.prompt;

import java.util.List;

/**
 * String constants used across the system prompt assembly pipeline.
 */
public final class SystemPromptConstants {

    /**
     * Identity prefix — the first element of the system prompt, split into its own cacheable block on
     * the wire.
     */
    public static final String CLI_SYSPROMPT_PREFIX =
        "You are Claude Code, Anthropic's official CLI for Claude.";

    /** Non-interactive {@code -p} identity when no append-system-prompt is used. */
    public static final String AGENT_SDK_SYSPROMPT_PREFIX =
        "You are a Claude agent, built on Anthropic's Claude Agent SDK.";

    /** Non-interactive identity when Claude Code runs inside an Agent SDK preset. */
    public static final String AGENT_SDK_CLI_PRESET_SYSPROMPT_PREFIX =
        "You are Claude Code, Anthropic's official CLI for Claude, running within the Claude Agent SDK.";


    public static String cliSyspromptPrefix(boolean nonInteractive, boolean hasAppendSystemPrompt) {
        if (!nonInteractive) return CLI_SYSPROMPT_PREFIX;
        return hasAppendSystemPrompt
            ? AGENT_SDK_CLI_PRESET_SYSPROMPT_PREFIX
            : AGENT_SDK_SYSPROMPT_PREFIX;
    }

    /** Content-based prefix recognition used by the wire serializer. */
    public static List<String> cliSyspromptPrefixes() {
        return List.of(
            CLI_SYSPROMPT_PREFIX,
            AGENT_SDK_CLI_PRESET_SYSPROMPT_PREFIX,
            AGENT_SDK_SYSPROMPT_PREFIX);
    }

    /**
     * Boundary marker separating static (cross-org cacheable) content from dynamic content in the
     * system prompt array.
     */
    public static final String SYSTEM_PROMPT_DYNAMIC_BOUNDARY =
        "__SYSTEM_PROMPT_DYNAMIC_BOUNDARY__";

    /**
     * Default system prompt for subagents.
     */
    public static final String DEFAULT_AGENT_PROMPT =
        "You are an agent for Claude Code, Anthropic's official CLI for Claude. "
        + "Given the user's message, you should use the tools available to complete "
        + "the task. Complete the task fully—don't gold-plate, but don't leave it "
        + "half-done. When you complete the task, respond with a concise report "
        + "covering what was done and any key findings — the caller will relay this "
        + "to the user, so it only needs the essentials.";


    public static final String SUMMARIZE_TOOL_RESULTS_SECTION =
        "When working with tool results, write down any important information you "
        + "might need later in your response, as the original tool result may be "
        + "cleared later.";

    private SystemPromptConstants() {}
}
