package com.claudecode.ui.lanterna.features.help;

import com.claudecode.commands.Command;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.metadata.CommandMetadata;
import com.claudecode.commands.CommandRegistry;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.impl.integration.PluginMarkdownCommand;
import com.claudecode.commands.workflows.WorkflowPromptCommand;
import com.claudecode.commands.workflows.WorkflowCommandDefinition;
import com.claudecode.runtime.plugins.PluginCommandDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HelpCommandCatalogTest {

    @Test
    void partitionsBuiltInsFromDynamicCommandsAndFormatsTheirSources() {
        CommandRegistry registry = new CommandRegistry();
        registry.registerBuiltIn(command("help", "Show help", false));
        registry.register(command("hidden-custom", "Hidden", true));
        registry.register(new PluginMarkdownCommand(PluginCommandDefinition
            .builder("review", "prompt", "quality-pack")
            .description("Review changes").hasUserSpecifiedDescription(true).build()));
        registry.register(new WorkflowPromptCommand(new WorkflowCommandDefinition(
            "research", "Research", "Research deeply", null, List.of(), "script",
            WorkflowCommandDefinition.Source.USER, null, false)));

        HelpCommandCatalog.Catalog catalog = HelpCommandCatalog.build(
            registry, CommandContext.minimal());

        assertEquals(List.of(new HelpPanel.CommandEntry("help", "Show help")),
            catalog.builtin());
        assertEquals(List.of(
            new HelpPanel.CommandEntry("research", "Research deeply (workflow)"),
            new HelpPanel.CommandEntry("review", "(quality-pack) Review changes")),
            catalog.custom());
    }

    private static Command command(String name, String description, boolean hidden) {
        return new Command() {
            @Override public CommandMetadata metadata() {
                return new CommandMetadata(name, description);
            }
            @Override public boolean isHidden() { return hidden; }
            @Override public CommandResult execute(CommandContext context, String args) {
                return CommandResult.skip();
            }
        };
    }
}
