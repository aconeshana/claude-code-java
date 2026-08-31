package com.claudecode.commands.prompt;

import java.util.List;

/**
 * Host-owned execution bridge for shell interpolation inside markdown prompt commands.
 */
@FunctionalInterface
public interface PromptShellExecutor {

    String execute(String text, String slashCommandName,
                   List<String> allowedTools, String shell);
}
