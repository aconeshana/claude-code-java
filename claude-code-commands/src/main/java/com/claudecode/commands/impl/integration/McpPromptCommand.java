package com.claudecode.commands.impl.integration;

import com.claudecode.commands.Command;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.metadata.CommandMetadata;
import com.claudecode.commands.metadata.CommandMetadataEncoder;
import com.claudecode.commands.prompt.PromptInvocation;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.MessageContent;
import com.claudecode.runtime.mcp.McpPromptPort;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.apache.commons.lang3.StringUtils;

/**
 * Slash-command adapter for one MCP-exposed prompt.
 *
 * <ul>
 *   <li>dynamic
 *       naming, ordered arguments, literal-space zipping, prompt dispatch,
 *       and model-visible command metadata.</li>
 * </ul>
 */
public final class McpPromptCommand implements Command {

    private final McpPromptPort.Definition definition;
    private final McpPromptPort prompts;
    private final Function<CommandContext, Path> toolResultsDirResolver;

    public McpPromptCommand(McpPromptPort.Definition definition, McpPromptPort prompts) {
        this(definition, prompts, McpPromptCommand::toolResultsDir);
    }

    McpPromptCommand(McpPromptPort.Definition definition, McpPromptPort prompts,
                     Function<CommandContext, Path> toolResultsDirResolver) {
        this.definition = definition;
        this.prompts = prompts;
        this.toolResultsDirResolver = toolResultsDirResolver;
    }

    @Override
    public CommandMetadata metadata() {
        String description = definition.description();
        return new CommandMetadata(definition.commandName(), description == null ? "" : description);
    }

    @Override
    public List<String> argumentNames() {
        return definition.arguments().stream().map(McpPromptPort.Argument::name).toList();
    }

    @Override public boolean supportsNonInteractive() { return true; }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        List<String> tokens = tokenise(args);
        Map<String, String> argMap = new LinkedHashMap<>();
        List<McpPromptPort.Argument> declared = definition.arguments();
        for (int i = 0; i < Math.min(tokens.size(), declared.size()); i++) {
            argMap.put(declared.get(i).name(), tokens.get(i));
        }
        try {
            List<ContentBlock> content = prompts.invoke(
                definition, argMap, toolResultsDirResolver.apply(context));
            String metadataArgs = StringUtils.isEmpty(args) ? null : args;
            return CommandResult.forPrompt(PromptInvocation.builder(content)
                .progressMessage("running")
                .source("mcp")
                .userFacingName(definition.serverName() + ":" + definition.promptName() + " (MCP)")
                .hasUserSpecifiedDescription(StringUtils.isNotEmpty(definition.description()))
                .contentLength(0)
                .isMcp(true)
                .precedingUserMessages(List.of(MessageContent.ofText(
                    CommandMetadataEncoder.encodeSlashCommandLoading(
                        definition.commandName(), metadataArgs))))
                .build());
        } catch (Exception e) {
            String message = e.getMessage();
            return CommandResult.error("Error: "
                + (message == null ? e.getClass().getSimpleName() : message));
        }
    }

    static List<String> tokenise(String args) {
        if (args == null) return List.of();
        return Arrays.asList(args.split(" ", -1));
    }

    static Path toolResultsDir(CommandContext context) {
        if (context != null && context.session().currentSessionId() != null) {
            String sessionId = context.session().currentSessionId().get();
            if (StringUtils.isNotBlank(sessionId)) {
                return context.application().sessions().toolResultsDirectory(sessionId);
            }
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "claude-code-tool-results");
    }

    public McpPromptPort.Definition definition() { return definition; }
}
