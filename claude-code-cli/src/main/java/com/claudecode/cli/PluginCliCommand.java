package com.claudecode.cli;

import com.claudecode.runtime.plugins.PluginMarketplacePort;
import com.claudecode.services.plugins.marketplace.PluginMarketplaceAdapter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Picocli subcommand tree that backs {@code claude plugin <list|install|uninstall|enable|disable|update|validate|marketplace>}.
 */
@Command(
    name = "plugin",
    aliases = {"plugins"},
    description = "Manage Claude Code plugins and plugin marketplaces",
    mixinStandardHelpOptions = true,
    subcommands = {
        PluginCliCommand.ListPlugins.class,
        PluginCliCommand.Install.class,
        PluginCliCommand.Uninstall.class,
        PluginCliCommand.Enable.class,
        PluginCliCommand.Disable.class,
        PluginCliCommand.Update.class,
        PluginCliCommand.Validate.class,
        PluginCliCommand.MarketplaceCmd.class
    }
)
public class PluginCliCommand extends McpOutputCommand implements Callable<Integer> {

    private static final PluginMarketplacePort.Scope[] SCOPE_DETECT_ORDER = {
        PluginMarketplacePort.Scope.LOCAL,
        PluginMarketplacePort.Scope.PROJECT,
        PluginMarketplacePort.Scope.USER
    };

    @Override
    public Integer call() {
        stderr().println("Usage: claude plugin <list|install|uninstall|enable|disable|update|validate|marketplace> [options]");
        stderr().println("Run 'claude plugin --help' for full help.");
        return 2;
    }

    static Path resolveCwd() {
        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    static PluginMarketplacePort openPort() {
        return PluginMarketplaceAdapter.standard(resolveCwd().toString(), List::of);
    }

    static PluginMarketplacePort.Scope parseScope(String raw) {
        String normalized = StringUtils.defaultIfBlank(raw, "user").trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "user" -> PluginMarketplacePort.Scope.USER;
            case "project" -> PluginMarketplacePort.Scope.PROJECT;
            case "local" -> PluginMarketplacePort.Scope.LOCAL;
            default -> throw new IllegalArgumentException(
                "Invalid --scope '" + raw + "'. Expected: user, project, or local.");
        };
    }

    static String resolveInstalledPluginId(PluginMarketplacePort port, String spec) {
        String trimmed = spec.trim();
        if (Strings.CS.contains(trimmed, "@")) {
            return trimmed;
        }
        List<String> matches = port.installedPlugins().stream()
            .map(PluginMarketplacePort.InstalledPlugin::pluginId)
            .filter(id -> Strings.CS.equals(trimmed, PluginMarketplacePort.pluginName(id)))
            .toList();
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("Ambiguous plugin '" + trimmed
                + "'. Specify full plugin@marketplace: " + String.join(", ", matches));
        }
        return trimmed;
    }

    // ── list ─────────────────────────────────────────────────────────────────

    @Command(name = "list", aliases = {"ls"}, description = "List installed plugins",
        mixinStandardHelpOptions = true)
    static class ListPlugins extends McpOutputCommand implements Callable<Integer> {

        @Override
        public Integer call() {
            PluginMarketplacePort port = openPort();
            List<PluginMarketplacePort.InstalledPlugin> installed = port.installedPlugins();
            if (installed.isEmpty()) {
                stdout().println("No plugins installed.");
                return 0;
            }
            for (PluginMarketplacePort.InstalledPlugin plugin : installed) {
                String status = Boolean.TRUE.equals(plugin.enabled()) ? "enabled" : "disabled";
                String scopes = plugin.installations().stream()
                    .map(inst -> inst.scope().wire())
                    .distinct()
                    .reduce((a, b) -> a + "," + b)
                    .orElse("user");
                String version = StringUtils.defaultIfBlank(plugin.version(), "unknown");
                stdout().println(plugin.pluginId() + " (" + version + ") [" + status + ", scope: " + scopes + "]");
                if (StringUtils.isNotBlank(plugin.description())) {
                    stdout().println("  " + plugin.description());
                }
            }
            return 0;
        }
    }

    // ── install ──────────────────────────────────────────────────────────────

    @Command(name = "install", aliases = {"add"}, description = "Install and enable a plugin from a registered marketplace",
        mixinStandardHelpOptions = true)
    static class Install extends McpOutputCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Plugin identifier (<plugin>@<marketplace> or <plugin>)")
        String pluginSpec;

        @Option(names = {"-s", "--scope"}, defaultValue = "user",
            description = "Installation scope: user (default) | project | local")
        String scope;

        @Override
        public Integer call() {
            try {
                PluginMarketplacePort.Scope targetScope = parseScope(scope);
                PluginMarketplacePort port = openPort();
                String trimmed = pluginSpec.trim();
                String pluginName;
                String marketplaceName;
                int at = trimmed.indexOf('@');
                if (at > 0 && at < trimmed.length() - 1) {
                    pluginName = trimmed.substring(0, at);
                    marketplaceName = trimmed.substring(at + 1);
                } else {
                    pluginName = trimmed;
                    List<String> matchingMarketplaces = new ArrayList<>();
                    for (String candidateMkt : port.marketplaces().keySet()) {
                        boolean found = port.marketplace(candidateMkt).plugins().stream()
                            .anyMatch(entry -> Strings.CS.equals(pluginName, entry.name()));
                        if (found) {
                            matchingMarketplaces.add(candidateMkt);
                        }
                    }
                    if (matchingMarketplaces.isEmpty()) {
                        stderr().println("Error: Plugin '" + pluginName
                            + "' was not found in any registered marketplace. "
                            + "Add a marketplace first with 'claude plugin marketplace add <owner/repo>'.");
                        return 1;
                    }
                    if (matchingMarketplaces.size() > 1) {
                        stderr().println("Error: Plugin '" + pluginName + "' exists in multiple marketplaces ("
                            + String.join(", ", matchingMarketplaces)
                            + "). Specify <plugin>@<marketplace>.");
                        return 1;
                    }
                    marketplaceName = matchingMarketplaces.getFirst();
                }
                PluginMarketplacePort.InstallResult result =
                    port.install(pluginName, marketplaceName, targetScope);
                stdout().println("Installed " + result.pluginId() + " (" + result.version()
                    + ") [scope: " + targetScope.wire() + "]");
                return 0;
            } catch (RuntimeException failure) {
                stderr().println("Error: " + failure.getMessage());
                return 1;
            }
        }
    }

    // ── uninstall ────────────────────────────────────────────────────────────

    @Command(name = "uninstall", aliases = {"remove", "rm"}, description = "Uninstall an installed plugin",
        mixinStandardHelpOptions = true)
    static class Uninstall extends McpOutputCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Plugin identifier (<plugin>@<marketplace> or <plugin>)")
        String pluginSpec;

        @Option(names = {"-s", "--scope"}, description = "Scope: user | project | local (auto-detected if omitted)")
        String scope;

        @Option(names = {"--delete-data"}, description = "Delete plugin data directory when removing last installation")
        boolean deleteData;

        @Override
        public Integer call() {
            try {
                PluginMarketplacePort port = openPort();
                String pluginId = resolveInstalledPluginId(port, pluginSpec);
                if (StringUtils.isNotBlank(scope)) {
                    PluginMarketplacePort.Scope targetScope = parseScope(scope);
                    PluginMarketplacePort.PluginUninstallResult res =
                        port.uninstall(pluginId, targetScope, deleteData);
                    if (!res.removed()) {
                        stderr().println("Error: Plugin '" + pluginId + "' is not installed at scope '"
                            + targetScope.wire() + "'.");
                        return 1;
                    }
                    stdout().println("Uninstalled " + pluginId + " [scope: " + targetScope.wire() + "]");
                    return 0;
                }
                for (PluginMarketplacePort.Scope candidate : SCOPE_DETECT_ORDER) {
                    PluginMarketplacePort.PluginUninstallResult res =
                        port.uninstall(pluginId, candidate, deleteData);
                    if (res.removed()) {
                        stdout().println("Uninstalled " + pluginId + " [scope: " + candidate.wire() + "]");
                        return 0;
                    }
                }
                stderr().println("Error: Plugin '" + pluginId + "' is not installed.");
                return 1;
            } catch (RuntimeException failure) {
                stderr().println("Error: " + failure.getMessage());
                return 1;
            }
        }
    }

    // ── enable / disable ─────────────────────────────────────────────────────

    @Command(name = "enable", description = "Enable an installed plugin", mixinStandardHelpOptions = true)
    static class Enable extends McpOutputCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Plugin identifier (<plugin>@<marketplace> or <plugin>)")
        String pluginSpec;

        @Option(names = {"-s", "--scope"}, defaultValue = "user",
            description = "Scope: user (default) | project | local")
        String scope;

        @Override
        public Integer call() {
            try {
                PluginMarketplacePort.Scope targetScope = parseScope(scope);
                PluginMarketplacePort port = openPort();
                String pluginId = resolveInstalledPluginId(port, pluginSpec);
                port.enable(pluginId, targetScope);
                stdout().println("Enabled " + pluginId + " [scope: " + targetScope.wire() + "]");
                return 0;
            } catch (RuntimeException failure) {
                stderr().println("Error: " + failure.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "disable", description = "Disable an installed plugin", mixinStandardHelpOptions = true)
    static class Disable extends McpOutputCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Plugin identifier (<plugin>@<marketplace> or <plugin>)")
        String pluginSpec;

        @Option(names = {"-s", "--scope"}, defaultValue = "user",
            description = "Scope: user (default) | project | local")
        String scope;

        @Override
        public Integer call() {
            try {
                PluginMarketplacePort.Scope targetScope = parseScope(scope);
                PluginMarketplacePort port = openPort();
                String pluginId = resolveInstalledPluginId(port, pluginSpec);
                port.disable(pluginId, targetScope);
                stdout().println("Disabled " + pluginId + " [scope: " + targetScope.wire() + "]");
                return 0;
            } catch (RuntimeException failure) {
                stderr().println("Error: " + failure.getMessage());
                return 1;
            }
        }
    }

    // ── update ───────────────────────────────────────────────────────────────

    @Command(name = "update", description = "Update one or all installed plugins", mixinStandardHelpOptions = true)
    static class Update extends McpOutputCommand implements Callable<Integer> {

        @Parameters(index = "0", arity = "0..1", description = "Optional plugin identifier (<plugin>@<marketplace> or <plugin>)")
        String pluginSpec;

        @Option(names = {"-s", "--scope"}, defaultValue = "user",
            description = "Scope: user (default) | project | local")
        String scope;

        @Override
        public Integer call() {
            try {
                PluginMarketplacePort.Scope targetScope = parseScope(scope);
                PluginMarketplacePort port = openPort();
                if (StringUtils.isNotBlank(pluginSpec)) {
                    String pluginId = resolveInstalledPluginId(port, pluginSpec);
                    String mkt = PluginMarketplacePort.pluginMarketplace(pluginId);
                    if (mkt != null && port.marketplaces().containsKey(mkt)) {
                        port.updateMarketplace(mkt, _ -> {});
                    }
                    PluginMarketplacePort.PluginUpdateResult res = port.updatePlugin(pluginId, targetScope);
                    stdout().println((res.updated() ? "Updated " : "Already up to date: ")
                        + pluginId + " (" + res.version() + ")");
                    return 0;
                }
                for (String mkt : port.marketplaces().keySet()) {
                    port.updateMarketplace(mkt, _ -> {});
                }
                List<PluginMarketplacePort.InstalledPlugin> installed = port.installedPlugins();
                if (installed.isEmpty()) {
                    stdout().println("No plugins installed.");
                    return 0;
                }
                for (PluginMarketplacePort.InstalledPlugin item : installed) {
                    PluginMarketplacePort.Scope itemScope = item.installations().isEmpty()
                        ? targetScope : item.installations().getFirst().scope();
                    PluginMarketplacePort.PluginUpdateResult res = port.updatePlugin(item.pluginId(), itemScope);
                    stdout().println((res.updated() ? "Updated " : "Up to date: ")
                        + item.pluginId() + " (" + res.version() + ")");
                }
                return 0;
            } catch (RuntimeException failure) {
                stderr().println("Error: " + failure.getMessage());
                return 1;
            }
        }
    }

    // ── validate ─────────────────────────────────────────────────────────────

    @Command(name = "validate", description = "Validate a plugin or marketplace manifest directory/file",
        mixinStandardHelpOptions = true)
    static class Validate extends McpOutputCommand implements Callable<Integer> {

        @Parameters(index = "0", defaultValue = ".", description = "Path to plugin/marketplace directory or manifest JSON")
        String targetPath;

        @Override
        public Integer call() {
            PluginMarketplacePort port = openPort();
            Path resolved = Path.of(targetPath).toAbsolutePath().normalize();
            PluginMarketplacePort.ValidationResult result = port.validate(resolved);
            stdout().println("Validating " + result.fileType() + " manifest: " + result.filePath());
            for (PluginMarketplacePort.ValidationWarning warning : result.warnings()) {
                stdout().println("  Warning [" + warning.path() + "]: " + warning.message());
            }
            if (!result.success()) {
                for (PluginMarketplacePort.ValidationError err : result.errors()) {
                    stderr().println("  Error [" + err.path() + "]: " + err.message());
                }
                return 1;
            }
            stdout().println("Validation passed.");
            return 0;
        }
    }

    // ── marketplace ──────────────────────────────────────────────────────────

    @Command(
        name = "marketplace",
        aliases = {"marketplaces"},
        description = "Manage registered plugin marketplaces",
        mixinStandardHelpOptions = true,
        subcommands = {
            MarketplaceCmd.ListMarketplaces.class,
            MarketplaceCmd.AddMarketplace.class,
            MarketplaceCmd.RemoveMarketplace.class,
            MarketplaceCmd.UpdateMarketplace.class
        }
    )
    static class MarketplaceCmd extends McpOutputCommand implements Callable<Integer> {

        @Override
        public Integer call() {
            stderr().println("Usage: claude plugin marketplace <list|add|remove|update> [options]");
            return 2;
        }

        @Command(name = "list", aliases = {"ls"}, description = "List registered plugin marketplaces",
            mixinStandardHelpOptions = true)
        static class ListMarketplaces extends McpOutputCommand implements Callable<Integer> {
            @Override
            public Integer call() {
                Map<String, PluginMarketplacePort.Marketplace> all = openPort().marketplaces();
                if (all.isEmpty()) {
                    stdout().println("No plugin marketplaces registered.");
                    return 0;
                }
                all.values().forEach(mkt -> stdout().println(
                    mkt.name() + " — " + mkt.source()
                        + (Boolean.TRUE.equals(mkt.autoUpdate()) ? " [auto-update]" : "")));
                return 0;
            }
        }

        @Command(name = "add", description = "Register a plugin marketplace (owner/repo, URL, or path)",
            mixinStandardHelpOptions = true)
        static class AddMarketplace extends McpOutputCommand implements Callable<Integer> {

            @Parameters(index = "0", description = "Marketplace source (e.g. aconeshana/poco-plugins, https://..., or ./path)")
            String source;

            @Override
            public Integer call() {
                PluginMarketplacePort port = openPort();
                PluginMarketplacePort.ParsedMarketplaceInput parsed = port.parseMarketplaceInput(source.trim());
                return switch (parsed) {
                    case PluginMarketplacePort.ParsedMarketplaceInput.Unrecognized _ -> {
                        stderr().println("Error: Invalid marketplace source format. Try: owner/repo, https://..., or ./path");
                        yield 1;
                    }
                    case PluginMarketplacePort.ParsedMarketplaceInput.Invalid invalid -> {
                        stderr().println("Error: " + invalid.error());
                        yield 1;
                    }
                    case PluginMarketplacePort.ParsedMarketplaceInput.Parsed ok -> {
                        try {
                            PluginMarketplacePort.AddResult res = port.addMarketplace(ok, msg -> stdout().println("  " + msg));
                            stdout().println((res.alreadyMaterialized()
                                ? "Marketplace already registered: " : "Added marketplace: ") + res.name());
                            yield 0;
                        } catch (RuntimeException failure) {
                            stderr().println("Error: " + failure.getMessage());
                            yield 1;
                        }
                    }
                };
            }
        }

        @Command(name = "remove", aliases = {"rm"}, description = "Remove a registered plugin marketplace",
            mixinStandardHelpOptions = true)
        static class RemoveMarketplace extends McpOutputCommand implements Callable<Integer> {

            @Parameters(index = "0", description = "Marketplace name")
            String name;

            @Override
            public Integer call() {
                try {
                    openPort().removeMarketplace(name.trim());
                    stdout().println("Removed marketplace: " + name.trim());
                    return 0;
                } catch (RuntimeException failure) {
                    stderr().println("Error: " + failure.getMessage());
                    return 1;
                }
            }
        }

        @Command(name = "update", description = "Refresh one or all registered plugin marketplaces",
            mixinStandardHelpOptions = true)
        static class UpdateMarketplace extends McpOutputCommand implements Callable<Integer> {

            @Parameters(index = "0", arity = "0..1", description = "Optional marketplace name")
            String name;

            @Override
            public Integer call() {
                try {
                    PluginMarketplacePort port = openPort();
                    if (StringUtils.isNotBlank(name)) {
                        port.updateMarketplace(name.trim(), msg -> stdout().println("  " + msg));
                        stdout().println("Updated marketplace: " + name.trim());
                        return 0;
                    }
                    Map<String, PluginMarketplacePort.Marketplace> all = port.marketplaces();
                    if (all.isEmpty()) {
                        stdout().println("No plugin marketplaces registered.");
                        return 0;
                    }
                    for (String mkt : all.keySet()) {
                        port.updateMarketplace(mkt, msg -> stdout().println("  " + msg));
                        stdout().println("Updated marketplace: " + mkt);
                    }
                    return 0;
                } catch (RuntimeException failure) {
                    stderr().println("Error: " + failure.getMessage());
                    return 1;
                }
            }
        }
    }
}
