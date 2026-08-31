package com.claudecode.cli;

import com.claudecode.commands.CommandRegistry;
import com.claudecode.commands.plugins.PluginRuntimePort;
import com.claudecode.services.plugins.runtime.PluginRuntimeSnapshot;
import java.nio.file.Path;

/**
 * Non-owning plugin-runtime capability exposed to session consumers. The
 * lifecycle bootstrap retains the closeable owner, while command/UI/SDK paths
 * borrow this view, following the same split as {@code McpConnectionView}.
 */
interface CliPluginRuntimeView extends PluginRuntimePort {

    PluginRuntimeSnapshot currentSnapshot();

    void attachCommandRegistry(CommandRegistry registry);

    void attachCommandRegistry(CommandRegistry registry,
                               CliSkillCommandSync sharedSkillCommandSync);

    int reloadSkills();

    void syncWorkflowCommands(Path cwd);
}
