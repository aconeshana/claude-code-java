package com.claudecode.commands.impl.integration;

import com.claudecode.commands.Command;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.metadata.CommandMetadata;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.prompt.PromptInvocation;
import com.claudecode.commands.prompt.PromptShellExecution;
import com.claudecode.core.message.TextBlock;
import com.claudecode.runtime.plugins.PluginCommandDefinition;
import com.claudecode.core.prompt.ArgumentSubstitutor;

import org.apache.commons.lang3.StringUtils;
import java.util.List;

/**
 * Slash-command adapter for one plugin-provided markdown command (or skill file inside a {@code
 * commands/} directory).
 */
public final class PluginMarkdownCommand implements Command {

    private final PluginCommandDefinition def;

    public PluginMarkdownCommand(PluginCommandDefinition def) {
        this.def = def;
    }

    @Override
    public CommandMetadata metadata() {
        return new CommandMetadata(def.name(), def.description());
    }

    @Override
    public String argumentHint() {
        return def.argumentHint();
    }

    @Override
    public List<String> argumentNames() {
        return def.argNames();
    }

    @Override
    public boolean supportsNonInteractive() { return true; }

    @Override
    public boolean isHidden() {
        return def.hidden();
    }

    @Override
    public boolean isLongRunning() {
        return PromptShellExecution.containsShellPattern(def.prompt());
    }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        String prompt = ArgumentSubstitutor.substitute(
            def.prompt(), args == null ? "" : args, def.argNames(), true);
        String sessionId = context != null && context.session().currentSessionId() != null
            ? context.session().currentSessionId().get() : null;
        if (sessionId != null) {
            prompt = prompt.replace("${CLAUDE_SESSION_ID}", sessionId);
        }
        if (PromptShellExecution.containsShellPattern(prompt)) {
            if (context == null || context.session().promptShellExecutor() == null) {
                return CommandResult.error(
                    "Shell command execution is unavailable for /" + def.name());
            }
            try {
                prompt = context.session().promptShellExecutor().execute(
                    prompt, "/" + def.name(), def.allowedTools(), def.shell());
            } catch (RuntimeException e) {
                String message = e.getMessage();
                return CommandResult.error(StringUtils.isBlank(message)
                    ? "Error while expanding shell command in /" + def.name() : message);
            }
        }
        return CommandResult.forPrompt(PromptInvocation.builder(List.of(new TextBlock(prompt)))
            .progressMessage(def.progressMessage())
            .allowedTools(def.allowedTools())
            .model(def.model())
            .effort(def.effort())
            .disableModelInvocation(def.disableModelInvocation())
            .source(def.source())
            .loadedFrom(def.loadedFrom())
            .userFacingName(def.userFacingName())
            .hasUserSpecifiedDescription(def.hasUserSpecifiedDescription())
            .whenToUse(def.whenToUse())
            .version(def.version())
            .contentLength(def.contentLength())
            .build());
    }

    /** Exposed for {@code PluginCommandSync} / tests. */
    public PluginCommandDefinition def() {
        return def;
    }
}
