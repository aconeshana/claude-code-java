package com.claudecode.cli;

import com.claudecode.commands.CommandRegistry;
import com.claudecode.commands.plugins.PluginRuntimePort;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.gateway.GatewayCommandsPort;
import com.claudecode.gateway.GatewayFileSuggestionsPort;
import com.claudecode.gateway.GatewayPluginsPort;
import com.claudecode.runtime.plugins.PluginMarketplacePort;
import com.claudecode.tools.files.WorkspaceFileSearch;
import com.claudecode.tools.skills.Skill;
import com.claudecode.tools.skills.SkillLoader;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

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
        SkillLoader skillLoader = new SkillLoader();
        skillLoader.addSource(Skill.SkillSource.USER, ClaudePaths.CLAUDE_HOME.resolve("skills"));
        skillLoader.addLegacyCommandsSource(
            Skill.SkillSource.USER, ClaudePaths.CLAUDE_HOME.resolve("commands"));
        Path cwdPath = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        skillLoader.addSource(Skill.SkillSource.PROJECT, cwdPath.resolve(".claude").resolve("skills"));
        skillLoader.addLegacyCommandsSource(
            Skill.SkillSource.PROJECT, cwdPath.resolve(".claude").resolve("commands"));
        return new GatewayCommandsPort() {
            @Override public List<CommandEntry> list() {
                List<CommandEntry> out = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                for (var command : registry.snapshot().commands()) {
                    if (command.isHidden()) continue;
                    String key = command.name().toLowerCase(Locale.ROOT);
                    if (!seen.add(key)) continue;
                    out.add(new CommandEntry(
                        command.name(),
                        command.menuDescription(),
                        command.argumentHint(),
                        command instanceof CliSkillPromptCommand ? "skill" : "command"));
                }
                for (Skill skill : skillLoader.loadAll()) {
                    if (skill == null || skill.name() == null || !skill.userInvocable()) continue;
                    String key = skill.name().toLowerCase(Locale.ROOT);
                    if (!seen.add(key)) continue;
                    out.add(new CommandEntry(
                        skill.name(),
                        skill.menuDescription(),
                        skill.argumentHint(),
                        "skill"));
                }
                return List.copyOf(out);
            }
        };
    }

    static GatewayPluginsPort plugins(
            PluginMarketplacePort marketplace, CliPluginRuntimeView runtime) {
        return new GatewayPluginsPort() {
            @Override
            public PluginMarketplacePort marketplace() {
                return marketplace;
            }

            @Override
            public RuntimeSummary summary() {
                if (runtime == null) {
                    int errors = marketplace == null ? 0 : marketplace.errors().size();
                    return new RuntimeSummary(0, 0, 0, 0, errors);
                }
                PluginRuntimePort.Summary s = runtime.summary();
                return new RuntimeSummary(
                    s.commandCount(), s.agentCount(), s.skillCount(), s.mcpCount(), s.errorCount());
            }

            @Override
            public RuntimeSummary refreshRuntime() {
                if (runtime != null) {
                    runtime.refresh();
                }
                return summary();
            }

            @Override
            public List<UiPanel> uiPanels() {
                if (runtime == null || runtime.modelProviders() == null) {
                    return List.of();
                }
                return runtime.modelProviders().uiPanels().stream()
                    .map(t -> new UiPanel(
                        t.pluginId(),
                        t.providerId(),
                        t.title(),
                        t.description(),
                        "/api/plugins/" + URLEncoder.encode(
                            t.pluginId(), StandardCharsets.UTF_8) + "/ui/",
                        t.status(),
                        t.error(),
                        t.settingsTab()))
                    .toList();
            }

            @Override
            public Optional<UiProxyTarget> uiProxyTarget(String pluginId) {
                if (runtime == null || runtime.modelProviders() == null) {
                    return Optional.empty();
                }
                return runtime.modelProviders().uiTarget(pluginId)
                    .map(t -> new UiProxyTarget(
                        t.pluginId(), t.baseUrl(), t.uiPath(), t.token(), t.status(), t.error()));
            }

            @Override
            public void invalidateProviderModels() {
                if (runtime != null && runtime.modelProviders() != null) {
                    runtime.modelProviders().invalidateModelsCache();
                }
            }
        };
    }
}
