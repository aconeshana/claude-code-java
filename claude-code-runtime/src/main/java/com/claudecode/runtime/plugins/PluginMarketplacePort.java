package com.claudecode.runtime.plugins;

import org.apache.commons.lang3.Strings;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Application port for the interactive plugin marketplace.
 */
public interface PluginMarketplacePort {

    enum Scope {
        MANAGED("managed"), USER("user"), PROJECT("project"), LOCAL("local");

        private final String wire;
        Scope(String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }

    record Author(String name) {}

    record ConfigOption(String type, String title, String description,
                        Boolean required, Object defaultValue, Boolean multiple,
                        Boolean sensitive, Double min, Double max) {}

    record PluginEntry(String name, String description, String version,
                       Author author, String category, List<String> tags,
                       String homepage, String repository) {}

    record MarketplaceManifest(String name, List<PluginEntry> plugins) {}

    record Marketplace(String name, String source, String installLocation,
                       String lastUpdated, Boolean autoUpdate) {}

    sealed interface ParsedMarketplaceInput
        permits ParsedMarketplaceInput.Parsed, ParsedMarketplaceInput.Invalid,
                ParsedMarketplaceInput.Unrecognized {
        record Parsed(String input) implements ParsedMarketplaceInput {}
        record Invalid(String error) implements ParsedMarketplaceInput {}
        record Unrecognized() implements ParsedMarketplaceInput {}
    }

    record AddResult(String name, boolean alreadyMaterialized) {}

    record Installation(Scope scope, String projectPath, Path installPath,
                        String version) {}

    record InstalledPlugin(String pluginId, List<Installation> installations,
                           Boolean enabled, String description, String version,
                           String author, String homepage, String repository,
                           LinkedHashMap<String, ConfigOption> userConfig) {}

    record InstallResult(String pluginId, String version, Path installPath) {}
    record PluginUpdateResult(boolean updated, String version) {}
    record PluginUninstallResult(boolean removed, boolean lastScope) {}
    record PluginDataDirectory(Path path, long bytes, String humanSize) {}
    record DiscoveryEnvironment(boolean gitAvailable, boolean strictPolicyConfigured,
                                boolean allMarketplacesBlocked) {}
    record FlaggedPlugin(String pluginId, String flaggedAt) {}
    record FailedPlugin(String pluginId, Scope scope, List<ErrorView> errors) {}
    record PluginMcpServer(String name, Scope scope, boolean disabled,
                           String transportType, String command, List<String> args,
                           Map<String, String> env, String url,
                           Map<String, String> headers) {}
    record McpbConfiguration(String source, String serverName,
                             LinkedHashMap<String, ConfigOption> schema,
                             Map<String, Object> existingValues,
                             List<String> validationErrors) {}
    record ConfigurationStep(String key, String title, String subtitle,
                             String serverName,
                             LinkedHashMap<String, ConfigOption> schema,
                             Map<String, Object> existingValues) {}

    enum ErrorAction { NONE, UNINSTALL_PLUGIN, REMOVE_EXTRA_MARKETPLACE, REMOVE_INSTALLED_MARKETPLACE, MANAGED_ONLY }
    /** Error display data plus a structured remediation action/target. */
    record ErrorView(String source, String message, String guidance, ErrorAction action, String target) {
        public ErrorView(String source, String message, String guidance) {
            this(source, message, guidance, ErrorAction.NONE, null);
        }
    }

    record ValidationError(String path, String message, String code) {}
    record ValidationWarning(String path, String message) {}
    record ValidationResult(boolean success, List<ValidationError> errors,
                            List<ValidationWarning> warnings, Path filePath,
                            String fileType) {}

    Map<String, Marketplace> marketplaces();

    MarketplaceManifest marketplace(String name);

    ParsedMarketplaceInput parseMarketplaceInput(String input);

    AddResult addMarketplace(ParsedMarketplaceInput.Parsed input,
                             Consumer<String> progress);

    void removeMarketplace(String name);

    void updateMarketplace(String name, Consumer<String> progress);

    void setMarketplaceAutoUpdate(String name, boolean enabled);

    List<InstalledPlugin> installedPlugins();

    InstallResult install(String pluginName, String marketplaceName, Scope scope);

    PluginUninstallResult uninstall(String pluginId, Scope scope, boolean deleteDataDirectory);

    Optional<PluginDataDirectory> pluginDataDirectory(String pluginId);

    boolean isEnabledAtProjectScope(String pluginId);

    void disableLocally(String pluginId);


    String updateAvailabilityError(String pluginId);

    DiscoveryEnvironment discoveryEnvironment();

    List<FlaggedPlugin> flaggedPlugins();

    void dismissFlaggedPlugin(String pluginId);

    List<FailedPlugin> failedPlugins();

    List<PluginMcpServer> pluginMcpServers();

    PluginUpdateResult updatePlugin(String pluginId, Scope scope);

    void enable(String pluginId, Scope scope);

    void disable(String pluginId, Scope scope);

    Map<String, Long> installCounts();

    List<ErrorView> errors();

    void removeExtraMarketplace(String marketplaceName);

    boolean openExternalUrl(String url);

    LinkedHashMap<String, ConfigOption> userConfig(Path installPath);

    Map<String, Object> loadOptions(String pluginId);

    void saveOptions(String pluginId, Map<String, Object> values,
                     LinkedHashMap<String, ConfigOption> schema);

    /** Unconfigured top-level and channel-specific steps shown after install/enable. */
    default List<ConfigurationStep> unconfiguredSteps(String pluginId, Path installPath) {
        return List.of();
    }

    default void saveConfigurationStep(String pluginId, ConfigurationStep step,
                                       Map<String, Object> values) {
        if (step.serverName() == null) saveOptions(pluginId, values, step.schema());
        else throw new UnsupportedOperationException("Channel configuration is not available");
    }

    default boolean hasMcpb(String pluginId, Path installPath) { return false; }

    default Optional<McpbConfiguration> loadMcpbConfiguration(
            String pluginId, Path installPath) {
        return Optional.empty();
    }

    default void saveMcpbConfiguration(String pluginId, Path installPath,
                                       McpbConfiguration configuration,
                                       Map<String, Object> values) {
        throw new UnsupportedOperationException("MCPB configuration is not available");
    }

    ValidationResult validate(Path path);

    static String pluginName(String pluginId) {
        int at = pluginId.indexOf('@');
        return at < 0 ? pluginId : pluginId.substring(0, at);
    }

    static String pluginMarketplace(String pluginId) {
        int at = pluginId.indexOf('@');
        if (at < 0) return null;
        String rest = pluginId.substring(at + 1);
        int next = rest.indexOf('@');
        return next < 0 ? rest : rest.substring(0, next);
    }

    static String formatInstallCount(long count) {
        if (count < 1_000) return String.valueOf(count);
        if (count < 1_000_000) return withSuffix(count / 1_000.0, "K");
        return withSuffix(count / 1_000_000.0, "M");
    }

    private static String withSuffix(double value, String suffix) {
        String formatted = String.format(Locale.ROOT, "%.1f", value);
        return Strings.CS.endsWith(formatted, ".0")
            ? formatted.substring(0, formatted.length() - 2) + suffix
            : formatted + suffix;
    }
}
