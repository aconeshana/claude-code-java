package com.claudecode.commands.impl.terminal;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.prompt.PromptInvocation;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.platform.Platform;

import java.util.List;

import org.apache.commons.lang3.StringUtils;

/**
 * /statusline — set up Claude Code's status line UI via the statusline-setup sub-agent.
 */
@SlashCommand(
    name = "statusline",
    description = "Set up Claude Code's status line UI"
)
public class StatuslineCommand implements AnnotatedCommand {

    private static final String AGENT_TOOL_NAME = "Agent";
    private static final String CONFIGURE_TOOL_NAME = "ConfigureStatusLine";

    @Override
    public boolean isAvailable(CommandContext context) {

        // makes sense in an interactive REPL, so hide it in print / -p mode.
        return !context.session().nonInteractive();
    }

    @Override
    @Explanation("Uses a write-only configuration tool so Windows setup cannot expose unrelated settings")
    public CommandResult execute(CommandContext context, String args) {
        String userPrompt = (StringUtils.isNotBlank(args))
            ? args.trim()
            : "Configure a concise, useful status line for this operating system";
        String query = """
            Create an Agent with subagent_type "statusline-setup".
            The agent must use ConfigureStatusLine and must not read or edit any settings file.
            Configure a single-line command compatible with operating system: %s.
            The command receives Claude Code status JSON on stdin. It must only format status
            information: do not add network access, credential access, file writes, or process
            management. Only report success after ConfigureStatusLine succeeds.
            Windows rule: pass ConfigureStatusLine a PowerShell script body, never a nested
            powershell.exe/pwsh/cmd invocation. The script may be multiline; the tool encodes it
            into one stored command. Target Windows PowerShell 5.1 and use ASCII display text;
            do not use PowerShell 7-only `u{...} escapes. Read stdin with
            [Console]::In.ReadToEnd().
            Useful JSON fields are model.display_name, model.id, cost.total_cost_usd,
            context_window.total_input_tokens, context_window.total_output_tokens, and session_id.
            User request: %s
            """.formatted(Platform.CURRENT, userPrompt).strip();
        return CommandResult.forPrompt(PromptInvocation.builder(List.of(new TextBlock(query)))
            .progressMessage("setting up statusLine")
            .allowedTools(List.of(AGENT_TOOL_NAME, CONFIGURE_TOOL_NAME))
            .source("builtin")
            .userFacingName(name())
            .contentLength(query.length())
            .build());
    }
}
