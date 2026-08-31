package com.claudecode.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Picocli output adapter shared by the MCP command tree.
 */
@Command
abstract class McpOutputCommand {
    @Spec private CommandSpec commandSpec;

    final CliOutput stdout() {
        return CliOutput.borrowed(commandSpec.commandLine().getOut());
    }

    final CliOutput stderr() {
        return CliOutput.borrowed(commandSpec.commandLine().getErr());
    }
}
