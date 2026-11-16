package com.claudecode.cli;

import com.claudecode.commands.CommandRegistry;
import com.claudecode.gateway.GatewayCommandsPort;
import com.claudecode.gateway.GatewayFileSuggestionsPort;
import com.claudecode.tools.files.WorkspaceFileSearch;
import java.util.List;

/**
 * Read-only gateway surfaces that describe the <em>host's</em> installed
 * capabilities rather than any one session: the slash-command catalogue and
 * the {@code @}-completion workspace enumeration.
 *
 * <p>Both are answerable from the process that serves the webui, so the TUI
 * and the standalone {@code claude web} gateway build them through the same
 * two factory methods and cannot drift: a command registered for the TUI
 * composer appears in the webui's {@code +} menu, and both {@code @} menus
 * rank through one {@code WorkspaceFileSearch}.
 */
final class CliGatewayPorts {

    /** Upper bound on one {@code @} menu answer (the client renders far fewer). */
    private static final int MAX_WEB_FILE_CANDIDATES = 50;

    private CliGatewayPorts() {
    }

    /**
     * Projects the TUI {@code @}-completion workspace enumeration onto the
     * gateway, so the webui composer's {@code @} menu lists the same paths the
     * TUI typeahead offers. Rows carry paths only and never read file contents
     * (the submission-time {@code AtMentionedFilesProvider} owns the read).
     */
    static GatewayFileSuggestionsPort fileSuggestions(String cwd) {
        WorkspaceFileSearch search = new WorkspaceFileSearch();
        return new GatewayFileSuggestionsPort() {
            @Override public List<FileCandidate> list(String query) {
                return search.suggest(cwd, query, MAX_WEB_FILE_CANDIDATES).stream()
                    .map(row -> new FileCandidate(row.path(), row.directory()))
                    .toList();
            }
        };
    }

    /**
     * The composer menu reads the registry snapshot on every request, so a
     * command registered later (a skill loaded at startup, an MCP prompt that
     * arrives with its server) is visible to a webui client without a restart.
     */
    static GatewayCommandsPort commands(CommandRegistry registry) {
        return new GatewayCommandsPort() {
            @Override public List<CommandEntry> list() {
                return registry.snapshot().commands().stream()
                    .filter(command -> !command.isHidden())
                    .map(command -> new CommandEntry(
                        command.name(),
                        command.menuDescription(),
                        command.argumentHint(),
                        command instanceof CliSkillPromptCommand ? "skill" : "command"))
                    .toList();
            }
        };
    }
}
