package com.claudecode.commands.workflows;

import java.util.Locale;

import com.claudecode.commands.Command;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.metadata.CommandMetadata;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.prompt.PromptInvocation;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.serialization.JsonUtils;

import org.apache.commons.lang3.StringUtils;
import java.util.List;

/**
 * Prompt-command adapter for one visible dynamic workflow.
 */
public final class WorkflowPromptCommand implements Command {

    private final WorkflowCommandDefinition definition;

    public WorkflowPromptCommand(WorkflowCommandDefinition definition) {
        this.definition = definition;
    }

    @Override public CommandMetadata metadata() {
        return new CommandMetadata(
            definition.name(),
            definition.description()
        );
    }

    @Override public boolean supportsNonInteractive() { return true; }

    @Override public boolean isHidden() { return definition.hidden(); }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        String prompt = buildPrompt(args);
        boolean bundled = definition.source() == WorkflowCommandDefinition.Source.BUILT_IN;
        String source = bundled ? "bundled" : sourceName(definition);
        String loadedFrom = bundled ? "bundled"
            : definition.source() == WorkflowCommandDefinition.Source.PLUGIN ? "plugin" : "skills";
        return CommandResult.forPrompt(PromptInvocation.builder(List.of(new TextBlock(prompt)))
            .progressMessage("running dynamic workflow")
            .source(source)
            .loadedFrom(loadedFrom)
            .userFacingName(definition.title())
            .hasUserSpecifiedDescription(true)
            .whenToUse(definition.whenToUse())
            .contentLength(definition.script().length())
            .build());
    }

    private String buildPrompt(String rawArgs) {
        StringBuilder prompt = new StringBuilder();
        String jsonName = JsonUtils.toJson(definition.name());
        prompt.append("Run the ").append(jsonName).append(" workflow.\n\n")
            .append(definition.description());
        if (StringUtils.isNotBlank(definition.whenToUse())) {
            prompt.append("\n\n").append(definition.whenToUse());
        }
        if (!definition.phases().isEmpty()) {
            prompt.append("\n\nPhases:");
            for (WorkflowCommandDefinition.Phase phase : definition.phases()) {
                prompt.append("\n- ").append(phase.title());
                if (StringUtils.isNotBlank(phase.detail())) {
                    prompt.append(": ").append(phase.detail());
                }
            }
        }
        prompt.append("\n\nInvoke: Workflow({ name: ").append(jsonName);
        String args = rawArgs == null ? "" : rawArgs.strip();
        if (!args.isEmpty()) prompt.append(", args: ").append(JsonUtils.toJson(args));
        return prompt.append(" })").toString();
    }

    private static String sourceName(WorkflowCommandDefinition definition) {
        if (definition.source() == WorkflowCommandDefinition.Source.PLUGIN
                && definition.pluginName() != null
                && !StringUtils.isBlank(definition.pluginName())) {
            return definition.pluginName();
        }
        return definition.source().name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public WorkflowCommandDefinition definition() { return definition; }
}
