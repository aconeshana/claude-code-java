package com.claudecode.services.plugins.marketplace;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.runtime.plugins.PluginMarketplacePort;
import com.claudecode.mcp.oauth.SecureStorage;
import com.claudecode.mcp.oauth.SecureStorageData;
import com.claudecode.mcp.oauth.SecureStorageFactory;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.mcp.McpServerConfig;
import com.claudecode.services.plugins.runtime.McpbBundleLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Services implementation of the runtime plugin-marketplace port.
 */
public final class PluginMarketplaceAdapter implements PluginMarketplacePort {

    private final MarketplaceManager marketplaces;
    private final PluginInstaller installer;
    private final PluginSettingsStore settings;
    private final Supplier<Map<String, Long>> installCounts;
    private final Supplier<List<PluginError>> errors;
    private final SecureStorage secureStorage;
    private final FlaggedPluginStore flaggedStore;
    private final PluginDelistingService delistingService;
    private final Supplier<List<McpServerConfig>> pluginMcpConfigs;
    private final McpbBundleLoader mcpbLoader;

    public PluginMarketplaceAdapter(MarketplaceManager marketplaces,
                                    PluginInstaller installer,
                                    PluginSettingsStore settings,
                                    Supplier<Map<String, Long>> installCounts,
                                    Supplier<List<PluginError>> errors) {
        this(marketplaces, installer, settings, installCounts, errors,
            SecureStorageFactory.getInstance(), List::of);
    }

    public PluginMarketplaceAdapter(MarketplaceManager marketplaces,
                                    PluginInstaller installer,
                                    PluginSettingsStore settings,
                                    Supplier<Map<String, Long>> installCounts,
                                    Supplier<List<PluginError>> errors,
                                    SecureStorage secureStorage) {
        this(marketplaces, installer, settings, installCounts, errors, secureStorage, List::of);
    }

    public PluginMarketplaceAdapter(MarketplaceManager marketplaces,
                                    PluginInstaller installer,
                                    PluginSettingsStore settings,
                                    Supplier<Map<String, Long>> installCounts,
                                    Supplier<List<PluginError>> errors,
                                    SecureStorage secureStorage,
                                    Supplier<List<McpServerConfig>> pluginMcpConfigs) {
        this.marketplaces = marketplaces;
        this.installer = installer;
        this.settings = settings;
        this.installCounts = installCounts != null ? installCounts : () -> null;
        this.errors = errors != null ? errors : List::of;
        this.secureStorage = secureStorage;
        this.flaggedStore = new FlaggedPluginStore(marketplaces.directories().flaggedPluginsFile());
        this.delistingService = new PluginDelistingService(marketplaces, installer, flaggedStore);
        this.pluginMcpConfigs = pluginMcpConfigs != null ? pluginMcpConfigs : List::of;
        this.mcpbLoader = new McpbBundleLoader();
    }

    public static PluginMarketplaceAdapter standard(String cwd,
                                                     Supplier<List<PluginError>> errors) {
        return standard(cwd, errors, List::of);
    }

    public static PluginMarketplaceAdapter standard(String cwd,
                                                     Supplier<List<PluginError>> errors,
                                                     Supplier<List<McpServerConfig>> pluginMcpConfigs) {
        MarketplaceManager manager = MarketplaceManager.standard(cwd);
        return new PluginMarketplaceAdapter(
            manager,
            new PluginInstaller(manager, new ProcessGitExecutor(), cwd),
            PluginSettingsStore.standard(cwd),
            InstallCounts.standard(manager.directories())::get,
            errors, SecureStorageFactory.getInstance(), pluginMcpConfigs);
    }

    @Override
    public Map<String, Marketplace> marketplaces() {
        Map<String, Marketplace> result = new LinkedHashMap<>();
        marketplaces.list().forEach((name, entry) -> result.put(name,
            new Marketplace(name, sourceDisplay(entry.source()), entry.installLocation(),
                entry.lastUpdated(), entry.autoUpdate())));
        return Map.copyOf(result);
    }

    @Override
    public PluginMarketplacePort.MarketplaceManifest marketplace(String name) {
        var manifest = marketplaces.get(name);
        List<PluginEntry> plugins = manifest.plugins() == null ? List.of()
            : manifest.plugins().stream().map(PluginMarketplaceAdapter::entry).toList();
        return new PluginMarketplacePort.MarketplaceManifest(manifest.name(), plugins);
    }

    @Override
    public ParsedMarketplaceInput parseMarketplaceInput(String input) {
        return switch (MarketplaceInput.parse(input)) {
            case MarketplaceInput.Parsed _ -> new ParsedMarketplaceInput.Parsed(input);
            case MarketplaceInput.Invalid invalid ->
                new ParsedMarketplaceInput.Invalid(invalid.error());
            case MarketplaceInput.Unrecognized _ ->
                new ParsedMarketplaceInput.Unrecognized();
        };
    }

    @Override
    public AddResult addMarketplace(ParsedMarketplaceInput.Parsed input,
                                    Consumer<String> progress) {
        MarketplaceInput.Result parsed = MarketplaceInput.parse(input.input());
        if (!(parsed instanceof MarketplaceInput.Parsed(MarketplaceSource source))) {
            throw new IllegalArgumentException("Invalid marketplace source");
        }
        MarketplaceManager.AddResult result = marketplaces.add(source, progress);
        return new AddResult(result.name(), result.alreadyMaterialized());
    }

    @Override
    public void removeMarketplace(String name) {
        List<String> removedPluginIds = installedPlugins().stream()
            .map(InstalledPlugin::pluginId)
            .filter(pluginId -> name.equals(PluginMarketplacePort.pluginMarketplace(pluginId)))
            .toList();
        marketplaces.remove(name);
        removedPluginIds.forEach(this::deleteOptions);
    }

    @Override
    public void updateMarketplace(String name, Consumer<String> progress) {
        marketplaces.update(name, progress);
        installer.updateMarketplacePlugins(name);
    }

    @Override
    public void setMarketplaceAutoUpdate(String name, boolean enabled) {
        marketplaces.setAutoUpdate(name, enabled);
    }

    @Override
    public List<InstalledPlugin> installedPlugins() {
        return installer.listInstalled().stream().map(status -> {
            List<Installation> installations = status.installations().stream()
                .map(entry -> new Installation(
                    Scope.valueOf(entry.scope().name()), entry.projectPath(),
                    entry.installPath() == null ? null : Path.of(entry.installPath()),
                    entry.version()))
                .toList();
            Path installPath = installations.isEmpty() ? null : installations.getFirst().installPath();
            PluginManifest manifest = readManifest(installPath);
            return new InstalledPlugin(
                status.pluginId(), installations, status.enabled(),
                manifest != null ? manifest.description() : null,
                manifest != null ? manifest.version() : null,
                manifest != null && manifest.author() != null ? manifest.author().name() : null,
                manifest != null ? manifest.homepage() : null,
                manifest != null ? manifest.repository() : null,
                manifest != null ? options(manifest.userConfig()) : new LinkedHashMap<>());
        }).toList();
    }

    @Override
    public InstallResult install(String pluginName, String marketplaceName, Scope scope) {
        PluginInstaller.InstallResult result = installer.install(
            pluginName, marketplaceName, PluginScope.valueOf(scope.name()));
        return new InstallResult(result.pluginId(), result.version(), result.installPath());
    }

    @Override
    public PluginUninstallResult uninstall(String pluginId, Scope scope,
                                           boolean deleteDataDirectory) {
        PluginInstaller.UninstallResult result = installer.uninstall(pluginId,
            PluginScope.valueOf(scope.name()), deleteDataDirectory);
        if (result.removed() && result.lastScope()) deleteOptions(pluginId);
        return new PluginUninstallResult(result.removed(), result.lastScope());
    }

    @Override
    public Optional<PluginDataDirectory> pluginDataDirectory(String pluginId) {
        Path path = marketplaces.directories().pluginDataDir(pluginId);
        if (!Files.isDirectory(path)) return Optional.empty();
        long bytes;
        try (var files = Files.walk(path)) {
            bytes = files.filter(Files::isRegularFile).mapToLong(file -> {
                try {
                    return Files.size(file);
                } catch (Exception _) {
                    return 0L;
                }
            }).sum();
        } catch (Exception _) {
            return Optional.empty();
        }
        return bytes == 0 ? Optional.empty()
            : Optional.of(new PluginDataDirectory(path, bytes, FormatUtils.formatFileSize(bytes)));
    }

    @Override
    public boolean isEnabledAtProjectScope(String pluginId) {
        return Boolean.TRUE.equals(settings.enabledPluginAtScope(pluginId, PluginScope.PROJECT));
    }

    @Override
    public void disableLocally(String pluginId) {
        settings.setEnabledPlugin(pluginId, false, PluginScope.LOCAL);
    }

    @Override
    public String updateAvailabilityError(String pluginId) {
        PluginDirectories.PluginId id = PluginDirectories.PluginId.parse(pluginId);
        if (id.marketplace() == null) return null;
        MarketplacePluginEntry entry = marketplaces.get(id.marketplace()).findPlugin(id.name())
            .orElse(null);
        if (entry != null && entry.source() instanceof PluginSource.RelativePath(String path)) {
            return "Local plugins cannot be updated remotely. To update, modify the source at: "
                + path;
        }
        return null;
    }

    @Override
    public DiscoveryEnvironment discoveryEnvironment() {
        List<MarketplaceSource> strict = settings.strictKnownMarketplaces();
        return new DiscoveryEnvironment(gitAvailable(), strict != null,
            strict != null && strict.isEmpty());
    }

    @Override
    public List<FlaggedPlugin> flaggedPlugins() {
        List<String> newlyFlagged = delistingService.reconcile();
        newlyFlagged.stream()
            .filter(pluginId -> installer.listInstalled().stream()
                .noneMatch(status -> status.pluginId().equals(pluginId)))
            .forEach(this::deleteOptions);
        Map<String, FlaggedPluginStore.Entry> entries = flaggedStore.load();
        flaggedStore.markSeen(entries.keySet().stream().toList());
        return entries.entrySet().stream()
            .map(entry -> new FlaggedPlugin(entry.getKey(), entry.getValue().flaggedAt()))
            .toList();
    }

    @Override
    public void dismissFlaggedPlugin(String pluginId) {
        flaggedStore.remove(pluginId);
    }

    @Override
    public List<FailedPlugin> failedPlugins() {
        Map<String, List<PluginError>> fatalByPlugin = new LinkedHashMap<>();
        for (PluginError error : errors.get()) {
            if (!isFatalPluginLoadError(error) || error.plugin() == null) continue;
            String target = error.source() != null && Strings.CS.contains(error.source(), "@")
                ? error.source() : error.plugin();
            fatalByPlugin.computeIfAbsent(target, _ -> new ArrayList<>())
                .add(error);
        }
        List<FailedPlugin> result = new ArrayList<>();
        for (PluginInstaller.InstalledPluginStatus installed : installer.listInstalled()) {
            List<PluginError> fatal = fatalByPlugin.get(installed.pluginId());
            if (fatal == null) {
                String name = PluginDirectories.PluginId.parse(installed.pluginId()).name();
                fatal = fatalByPlugin.get(name);
            }
            if (fatal == null || fatal.isEmpty()) continue;
            List<ErrorView> views = fatal.stream().map(this::errorView).toList();
            for (InstalledPlugins.InstallationEntry installation : installed.installations()) {
                result.add(new FailedPlugin(installed.pluginId(),
                    Scope.valueOf(installation.scope().name()), views));
            }
        }
        return List.copyOf(result);
    }

    @Override
    public List<PluginMcpServer> pluginMcpServers() {
        Map<String, Scope> scopes = new LinkedHashMap<>();
        for (PluginInstaller.InstalledPluginStatus installed : installer.listInstalled()) {
            if (installed.installations().isEmpty()) continue;
            scopes.put(PluginDirectories.PluginId.parse(installed.pluginId()).name(),
                Scope.valueOf(installed.installations().getFirst().scope().name()));
        }
        return pluginMcpConfigs.get().stream().map(server -> {
            String[] parts = server.name().split(":", 3);
            String pluginName = parts.length >= 2 ? parts[1] : server.name();
            return new PluginMcpServer(server.name(),
                scopes.getOrDefault(pluginName, Scope.USER), server.disabled(),
                server.transportType(), server.command(), server.args(), server.env(),
                server.url(), server.headers());
        }).toList();
    }

    private static boolean isFatalPluginLoadError(PluginError error) {
        return error instanceof PluginError.PluginCacheMiss
            || error instanceof PluginError.ManifestParseError
            || error instanceof PluginError.ManifestValidationError
            || error instanceof PluginError.GenericError generic
                && generic.error() != null
                && Strings.CS.startsWith(generic.error(), "Failed to load plugin:");
    }

    private static boolean gitAvailable() {
        try {
            return new ProcessBuilder("git", "--version").start().waitFor() == 0;
        } catch (Exception _) {
            return false;
        }
    }
    @Override public PluginUpdateResult updatePlugin(String pluginId, Scope scope) {
        PluginInstaller.UpdateResult result = installer.updatePlugin(pluginId,
            PluginScope.valueOf(scope.name()));
        return new PluginUpdateResult(result.updated(), result.version());
    }
    @Override public void enable(String pluginId, Scope scope) {
        installer.enable(pluginId, PluginScope.valueOf(scope.name()));
    }
    @Override public void disable(String pluginId, Scope scope) {
        installer.disable(pluginId, PluginScope.valueOf(scope.name()));
    }

    @Override public Map<String, Long> installCounts() { return installCounts.get(); }

    @Override
    public List<ErrorView> errors() {
        List<PluginError> current = errors.get();
        if (current == null) return List.of();
        return current.stream()
            .map(this::errorView)
            .toList();
    }

    @Override public void removeExtraMarketplace(String marketplaceName) {
        settings.removeMarketplaceReferences(marketplaceName);
    }

    @Override public boolean openExternalUrl(String url) {
        return ExternalUrlOpener.open(url);
    }

    @Override
    public LinkedHashMap<String, ConfigOption> userConfig(Path installPath) {
        PluginManifest manifest = readManifest(installPath);
        return manifest != null ? options(manifest.userConfig()) : new LinkedHashMap<>();
    }

    @Override
    public synchronized Map<String, Object> loadOptions(String pluginId) {
        Map<String, Object> values = new LinkedHashMap<>();
        JsonNode config = settings.pluginConfig(pluginId);
        JsonNode options = config != null && config.isObject() ? config.get("options") : null;
        if (options != null && options.isObject()) {
            options.fields().forEachRemaining(field -> {
                JsonNode value = field.getValue();
                if (value.isBoolean()) values.put(field.getKey(), value.asBoolean());
                else if (value.isNumber()) values.put(field.getKey(), value.asDouble());
                else if (value.isTextual()) values.put(field.getKey(), value.asText());
            });
        }
        if (secureStorage != null) {
            secureStorage.read().map(SecureStorageData::pluginSecrets)
                .map(secrets -> secrets.get(pluginId))
                .ifPresent(values::putAll);
        }
        return values;
    }

    @Override
    public synchronized void saveOptions(String pluginId, Map<String, Object> values,
                            LinkedHashMap<String, ConfigOption> schema) {
        Map<String, Object> nonSensitive = new LinkedHashMap<>();
        Map<String, String> sensitive = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            ConfigOption option = schema == null ? null : schema.get(key);
            if (option != null && Boolean.TRUE.equals(option.sensitive())) {
                sensitive.put(key, String.valueOf(value));
            } else {
                nonSensitive.put(key, value);
            }
        });

        // Secure storage is committed first: do not erase a legacy plaintext
        // fallback if the keychain/credentials store cannot accept the secret.
        saveSensitiveOptions(pluginId, sensitive, nonSensitive.keySet());

        JsonNode existing = settings.pluginConfig(pluginId);
        ObjectNode config = existing instanceof ObjectNode object
            ? object.deepCopy() : JsonUtils.getMapper().createObjectNode();
        JsonNode existingOptions = config.get("options");
        ObjectNode options = existingOptions instanceof ObjectNode object
            ? object.deepCopy() : JsonUtils.getMapper().createObjectNode();
        nonSensitive.forEach((key, value) -> {
            switch (value) {
                case Boolean bool -> options.put(key, bool);
                case Double number -> options.put(key, number);
                case Number number -> options.put(key, number.doubleValue());
                default -> options.put(key, String.valueOf(value));
            }
        });
        sensitive.keySet().forEach(options::remove);
        config.set("options", options);
        settings.setPluginConfig(pluginId, config);
    }

    @Override
    public synchronized List<ConfigurationStep> unconfiguredSteps(String pluginId, Path installPath) {
        PluginManifest manifest = readManifest(installPath);
        if (manifest == null) return List.of();
        List<ConfigurationStep> steps = new ArrayList<>();

        Map<String, Object> topValues = loadOptions(pluginId);
        LinkedHashMap<String, UserConfigOption> topSchema = new LinkedHashMap<>(manifest.userConfig());
        LinkedHashMap<String, UserConfigOption> missingTop =
            UserConfigValidator.unconfigured(topValues, topSchema);
        if (!missingTop.isEmpty()) {
            steps.add(new ConfigurationStep("top-level", "Configure " + manifest.name(),
                "Plugin options", null, options(missingTop), topValues));
        }

        for (PluginChannel channel : manifest.channels()) {
            if (StringUtils.isBlank(channel.server())
                    || channel.userConfig().isEmpty()) continue;
            Map<String, Object> values = loadMcpbValues(pluginId, channel.server());
            if (!UserConfigValidator.validate(values, channel.userConfig()).isEmpty()) {
                String display = StringUtils.isBlank(channel.displayName())
                    ? channel.server() : channel.displayName();
                steps.add(new ConfigurationStep("channel:" + channel.server(),
                    "Configure " + display, "Plugin: " + manifest.name(), channel.server(),
                    options(channel.userConfig()), values));
            }
        }
        return List.copyOf(steps);
    }

    @Override
    public synchronized void saveConfigurationStep(String pluginId, ConfigurationStep step,
                                      Map<String, Object> values) {
        if (step.serverName() == null) {
            saveOptions(pluginId, values, step.schema());
            return;
        }
        saveServerConfiguration(pluginId, step.serverName(), step.schema(), values);
    }

    @Override
    public synchronized boolean hasMcpb(String pluginId, Path installPath) {
        PluginManifest manifest = readManifest(installPath);
        return manifest != null && findMcpbSource(manifest.mcpServers()) != null;
    }

    @Override
    public synchronized Optional<McpbConfiguration> loadMcpbConfiguration(
        String pluginId, Path installPath) {
        PluginManifest manifest = readManifest(installPath);
        String source = manifest == null ? null : findMcpbSource(manifest.mcpServers());
        if (source == null) return Optional.empty();
        McpbBundleLoader.Result result = mcpbLoader.load(source, installPath,
            serverName -> loadMcpbValues(pluginId, serverName));
        return Optional.of(new McpbConfiguration(source, result.serverName(),
            options(result.configSchema()), result.existingConfig(),
            result.validationErrors()));
    }

    @Override
    public synchronized void saveMcpbConfiguration(String pluginId, Path installPath,
                                      McpbConfiguration configuration,
                                      Map<String, Object> values) {
        McpbBundleLoader.Result checked = mcpbLoader.load(configuration.source(), installPath,
            _ -> values);
        if (checked.needsConfig()) {
            throw new IllegalArgumentException(String.join("; ", checked.validationErrors()));
        }

        saveServerConfiguration(pluginId, configuration.serverName(),
            configuration.schema(), values);
    }

    private void saveServerConfiguration(String pluginId, String serverName,
                                         Map<String, ConfigOption> schema,
                                         Map<String, Object> values) {
        Map<String, Object> nonSensitive = new LinkedHashMap<>();
        Map<String, String> sensitive = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            ConfigOption option = schema.get(key);
            if (option != null && Boolean.TRUE.equals(option.sensitive())) {
                sensitive.put(key, String.valueOf(value));
            } else {
                nonSensitive.put(key, value);
            }
        });
        String secretKey = pluginId + "/" + serverName;
        saveMcpbSecrets(secretKey, sensitive, nonSensitive.keySet());

        JsonNode existing = settings.pluginConfig(pluginId);
        ObjectNode pluginConfig = existing instanceof ObjectNode object
            ? object.deepCopy() : JsonUtils.getMapper().createObjectNode();
        ObjectNode servers = pluginConfig.get("mcpServers") instanceof ObjectNode object
            ? object.deepCopy() : JsonUtils.getMapper().createObjectNode();
        ObjectNode server = servers.get(serverName) instanceof ObjectNode object
            ? object.deepCopy() : JsonUtils.getMapper().createObjectNode();
        nonSensitive.forEach((key, value) -> setValue(server, key, value));
        sensitive.keySet().forEach(server::remove);
        servers.set(serverName, server);
        pluginConfig.set("mcpServers", servers);
        settings.setPluginConfig(pluginId, pluginConfig);
    }

    private Map<String, Object> loadMcpbValues(String pluginId, String serverName) {
        Map<String, Object> values = new LinkedHashMap<>();
        JsonNode config = settings.pluginConfig(pluginId);
        JsonNode stored = config == null ? null : config.path("mcpServers").get(serverName);
        if (stored != null && stored.isObject()) {
            stored.fields().forEachRemaining(field -> values.put(field.getKey(),
                plainValue(field.getValue())));
        }
        if (secureStorage != null) {
            secureStorage.read().map(SecureStorageData::pluginSecrets)
                .map(secrets -> secrets.get(pluginId + "/" + serverName))
                .ifPresent(values::putAll);
        }
        return values;
    }

    private void saveMcpbSecrets(String secretKey, Map<String, String> sensitive,
                                 Set<String> nonSensitiveKeys) {
        if (secureStorage == null) {
            if (!sensitive.isEmpty()) {
                throw new IllegalStateException(
                    "Secure storage is unavailable for sensitive MCPB configuration");
            }
            return;
        }
        SecureStorageData current = secureStorage.read().orElseGet(SecureStorageData::empty);
        Map<String, Map<String, String>> allSecrets = new LinkedHashMap<>(current.pluginSecrets());
        Map<String, String> serverSecrets = new LinkedHashMap<>(
            allSecrets.getOrDefault(secretKey, Map.of()));
        nonSensitiveKeys.forEach(serverSecrets::remove);
        serverSecrets.putAll(sensitive);
        if (serverSecrets.equals(allSecrets.get(secretKey))) return;
        allSecrets.put(secretKey, serverSecrets);
        secureStorage.update(new SecureStorageData(current.mcpOAuth(),
            current.mcpOAuthClientConfig(), allSecrets, current.extras()));
    }

    private static void setValue(ObjectNode target, String key, Object value) {
        target.set(key, JsonUtils.getMapper().valueToTree(value));
    }

    private static String findMcpbSource(JsonNode spec) {
        if (spec == null || spec.isNull()) return null;
        if (spec.isTextual()) return isMcpbSource(spec.asText()) ? spec.asText() : null;
        if (spec.isArray()) {
            for (JsonNode item : spec) {
                if (item.isTextual() && isMcpbSource(item.asText())) return item.asText();
            }
        }
        return null;
    }

    private static boolean isMcpbSource(String source) {
        return Strings.CS.endsWith(source, ".mcpb") || Strings.CS.endsWith(source, ".dxt");
    }

    private void saveSensitiveOptions(String pluginId, Map<String, String> sensitive,
                                      Set<String> nonSensitiveKeys) {
        if (secureStorage == null) {
            if (!sensitive.isEmpty()) {
                throw new IllegalStateException("Secure storage is unavailable for sensitive plugin options");
            }
            return;
        }
        SecureStorageData current = secureStorage.read().orElseGet(SecureStorageData::empty);
        Map<String, Map<String, String>> allSecrets = new LinkedHashMap<>(current.pluginSecrets());
        Map<String, String> pluginSecrets = new LinkedHashMap<>(
            allSecrets.getOrDefault(pluginId, Map.of()));
        nonSensitiveKeys.forEach(pluginSecrets::remove);
        pluginSecrets.putAll(sensitive);
        boolean changed = !pluginSecrets.equals(allSecrets.get(pluginId));
        if (!changed) return;
        allSecrets.put(pluginId, pluginSecrets);
        secureStorage.update(new SecureStorageData(current.mcpOAuth(),
            current.mcpOAuthClientConfig(), allSecrets, current.extras()));
    }

    private void deleteOptions(String pluginId) {
        settings.removePluginConfig(pluginId);
        if (secureStorage == null) return;
        try {
            SecureStorageData current = secureStorage.read().orElse(null);
            if (current == null || !current.pluginSecrets().containsKey(pluginId)) return;
            Map<String, Map<String, String>> allSecrets = new LinkedHashMap<>(current.pluginSecrets());
            allSecrets.remove(pluginId);
            secureStorage.update(new SecureStorageData(current.mcpOAuth(),
                current.mcpOAuthClientConfig(), allSecrets, current.extras()));
        } catch (RuntimeException _) {

        }
    }

    @Override
    public ValidationResult validate(Path path) {
        PluginValidator.ValidationResult result = Files.isDirectory(path)
            ? PluginValidator.validatePlugin(path)
            : PluginValidator.validatePluginManifest(path);
        return new ValidationResult(
            result.success(),
            result.errors().stream()
                .map(error -> new ValidationError(error.path(), error.message(), error.code()))
                .toList(),
            result.warnings().stream()
                .map(warning -> new ValidationWarning(warning.path(), warning.message()))
                .toList(),
            result.filePath(), result.fileType());
    }

    private static PluginEntry entry(MarketplacePluginEntry entry) {
        String githubUrl = entry.source() instanceof PluginSource.GithubRepo github
            ? "https://github.com/" + github.repo()
            : null;
        return new PluginEntry(
            entry.name(), entry.description(), entry.version(),
            entry.author() == null ? null : new Author(entry.author().name()),
            entry.category(), entry.tags(), entry.homepage(), githubUrl);
    }

    private static LinkedHashMap<String, ConfigOption> options(
        Map<String, UserConfigOption> source) {
        LinkedHashMap<String, ConfigOption> result = new LinkedHashMap<>();
        if (source == null) return result;
        source.forEach((key, option) -> result.put(key, new ConfigOption(
            option.type(), option.title(), option.description(), option.required(),
            plainValue(option.defaultValue()), option.multiple(), option.sensitive(),
            option.min(), option.max())));
        return result;
    }

    private static Object plainValue(JsonNode value) {
        return value == null || value.isNull()
            ? null : JsonUtils.getMapper().convertValue(value, Object.class);
    }

    private static PluginManifest readManifest(Path installPath) {
        if (installPath == null) return null;
        Path nested = installPath.resolve(".claude-plugin").resolve("plugin.json");
        Path path = Files.isRegularFile(nested) ? nested : installPath.resolve("plugin.json");
        if (!Files.isRegularFile(path)) return null;
        try {
            return JsonUtils.getMapper().readValue(path.toFile(), PluginManifest.class);
        } catch (Exception _) {
            return null;
        }
    }

    private static String sourceDisplay(MarketplaceSource source) {
        return switch (source) {
            case MarketplaceSource.Github github -> github.repo();
            case MarketplaceSource.Url url -> url.url();
            case MarketplaceSource.Git git -> git.url();
            case MarketplaceSource.Directory directory -> directory.path();
            case MarketplaceSource.File file -> file.path();
            default -> "Unknown source";
        };
    }

    private static String guidance(PluginError error) {
        return switch (error) {
            case PluginError.PathNotFound _ ->
                "Check that the path in your manifest or marketplace config is correct";
            case PluginError.GitAuthFailed value -> Strings.CS.equals("ssh", value.authType())
                ? "Configure SSH keys or use HTTPS URL instead"
                : "Configure credentials or use SSH URL instead";
            case PluginError.GitTimeout _ ->
                "Check your internet connection and try again";
            case PluginError.NetworkError _ ->
                "Check your internet connection and try again";
            case PluginError.ManifestParseError _ ->
                "Check manifest file syntax in the plugin directory";
            case PluginError.ManifestValidationError _ ->
                "Check manifest file follows the required schema";
            case PluginError.PluginNotFound value ->
                "Plugin may not exist in marketplace \"" + value.marketplace() + "\"";
            case PluginError.MarketplaceNotFound value ->
                value.availableMarketplaces() != null && !value.availableMarketplaces().isEmpty()
                    ? "Available marketplaces: " + String.join(", ", value.availableMarketplaces())
                    : "Add the marketplace first using /plugin marketplace add";
            case PluginError.McpConfigInvalid _ ->
                "Check MCP server configuration in .mcp.json or manifest";
            case PluginError.McpServerSuppressedDuplicate value -> {
                if (Strings.CS.startsWith(value.duplicateOf(), "plugin:")) {
                    String[] parts = value.duplicateOf().split(":", 3);
                    String plugin = parts.length > 1 && !parts[1].isEmpty()
                        ? parts[1] : "the other plugin";
                    yield "Disable plugin \"" + plugin
                        + "\" if you want this plugin's version instead";
                }
                yield "Remove \"" + value.duplicateOf()
                    + "\" from your MCP config if you want the plugin's version instead";
            }
            case PluginError.HookLoadFailed _ ->
                "Check hooks.json file syntax and structure";
            case PluginError.ComponentLoadFailed value ->
                "Check " + value.component().wire() + " directory structure and file permissions";
            case PluginError.McpbDownloadFailed _ ->
                "Check your internet connection and URL accessibility";
            case PluginError.McpbExtractFailed _ ->
                "Verify the MCPB file is valid and not corrupted";
            case PluginError.McpbInvalidManifest _ ->
                "Contact the plugin author about the invalid manifest";
            case PluginError.MarketplaceBlockedByPolicy value ->
                Boolean.TRUE.equals(value.blockedByBlocklist())
                    ? "This marketplace source is explicitly blocked by your administrator"
                    : value.allowedSources() != null && !value.allowedSources().isEmpty()
                        ? "Allowed sources: " + String.join(", ", value.allowedSources())
                        : "Contact your administrator to configure allowed marketplace sources";
            case PluginError.DependencyUnsatisfied value -> Strings.CS.equals("not-enabled", value.reason())
                ? "Enable \"" + value.dependency() + "\" or uninstall \"" + value.plugin() + "\""
                : "Install \"" + value.dependency() + "\" or uninstall \"" + value.plugin() + "\"";
            case PluginError.LspConfigInvalid _ ->
                "Check LSP server configuration in the plugin manifest";
            case PluginError.LspServerStartFailed _ ->
                "Check LSP server logs with --debug for details";
            case PluginError.LspServerCrashed _ ->
                "Check LSP server logs with --debug for details";
            case PluginError.LspRequestTimeout _ ->
                "Check LSP server logs with --debug for details";
            case PluginError.LspRequestFailed _ ->
                "Check LSP server logs with --debug for details";
            case PluginError.PluginCacheMiss _ -> "Run /plugins to refresh the plugin cache";
            case PluginError.MarketplaceLoadFailed _ -> null;
            case PluginError.GenericError _ -> null;
        };
    }


    private ErrorView errorView(PluginError error) {
        String source = error.source() == null ? "unknown" : error.source();
        if (error instanceof PluginError.MarketplaceNotFound(_, var marketplace, _)) {
            return marketplaceErrorView(source, error, marketplace);
        }
        if (error instanceof PluginError.MarketplaceLoadFailed(_, var marketplace, _)) {
            return marketplaceErrorView(source, error, marketplace);
        }
        if (StringUtils.isBlank(error.plugin())) {
            return new ErrorView(source, error.getMessage(), guidance(error));
        }
        String rawSource = error.source();
        String target = rawSource != null && Strings.CS.contains(rawSource, "@") ? rawSource : error.plugin();
        return new ErrorView(source, error.getMessage(), guidance(error),
            PluginMarketplacePort.ErrorAction.UNINSTALL_PLUGIN, target);
    }

    private ErrorView marketplaceErrorView(String source, PluginError error, String marketplace) {
        PluginSettingsStore.MarketplaceSourceInfo info = settings.marketplaceSourceInfo(marketplace);
        PluginMarketplacePort.ErrorAction action = info.hasEditableSource()
            ? PluginMarketplacePort.ErrorAction.REMOVE_EXTRA_MARKETPLACE
            : info.policy() ? PluginMarketplacePort.ErrorAction.MANAGED_ONLY
            : marketplaces.list().containsKey(marketplace)
                ? PluginMarketplacePort.ErrorAction.REMOVE_INSTALLED_MARKETPLACE
                : PluginMarketplacePort.ErrorAction.NONE;
        String guidance = action == PluginMarketplacePort.ErrorAction.MANAGED_ONLY
            ? "Managed by your organization — contact your admin" : guidance(error);
        return new ErrorView(source, error.getMessage(), guidance, action, marketplace);
    }
}
