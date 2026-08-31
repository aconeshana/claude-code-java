package com.claudecode.commands.metadata;

import com.claudecode.commands.Command;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.commands.impl.integration.PluginMarkdownCommand;
import com.claudecode.commands.workflows.WorkflowPromptCommand;

/**
 * Formats slash-command descriptions for user-facing catalogues.
 */
public final class CommandDescriptionFormatter {

    private CommandDescriptionFormatter() {}

    public static String formatWithSource(Command command) {
        String description = command.description();
        if (command instanceof WorkflowPromptCommand) {
            return description + " (workflow)";
        }
        if (!(command instanceof PluginMarkdownCommand plugin)) {
            return description;
        }

        String source = plugin.def().source();
        if (Strings.CS.equals("plugin", source)) {
            String pluginName = plugin.def().pluginName();
            return StringUtils.isNotBlank(pluginName)
                ? "(" + pluginName + ") " + description
                : description + " (plugin)";
        }
        if (Strings.CS.equals("builtin", source) || Strings.CS.equals("mcp", source)) {
            return description;
        }
        if (Strings.CS.equals("bundled", source)) {
            return description + " (bundled)";
        }
        return description + " (" + settingSourceName(source) + ")";
    }

    private static String settingSourceName(String source) {
        return switch (source) {
            case "userSettings", "user" -> "user";
            case "projectSettings", "project" -> "project";
            case "localSettings", "local" -> "project, gitignored";
            case "flagSettings", "flag" -> "cli flag";
            case "policySettings", "policy", "managed" -> "managed";
            default -> StringUtils.isBlank(source) ? "unknown" : source;
        };
    }
}
