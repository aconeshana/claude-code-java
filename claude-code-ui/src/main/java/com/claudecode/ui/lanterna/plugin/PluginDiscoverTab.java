package com.claudecode.ui.lanterna.plugin;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.runtime.plugins.PluginMarketplacePort;
import com.claudecode.runtime.plugins.PluginMarketplacePort.ConfigurationStep;
import com.claudecode.runtime.plugins.PluginMarketplacePort.MarketplaceManifest;
import com.claudecode.runtime.plugins.PluginMarketplacePort.PluginEntry;
import com.claudecode.runtime.plugins.PluginMarketplacePort.Scope;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.PasteKeyStroke;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.claudecode.ui.lanterna.components.StyledText.blank;
import static com.claudecode.ui.lanterna.components.StyledText.bold;
import static com.claudecode.ui.lanterna.components.StyledText.boldLine;
import static com.claudecode.ui.lanterna.components.StyledText.line;
import static com.claudecode.core.text.StringUtils.plural;
import static com.claudecode.ui.lanterna.components.StyledText.seg;
import static com.claudecode.core.text.FormatUtils.truncate;
import com.claudecode.ui.lanterna.components.ListPagination;
import com.claudecode.ui.lanterna.input.SearchInput;
import com.claudecode.ui.lanterna.components.StyledText;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.components.LanternaDraw;

/**
 * Discover tab: marketplace selection (when more than one is configured) → installable-plugin list
 * → plugin details with trust warning and scoped install → optional post-install options flow →
 * install via {@link PluginMarketplacePort} on a background thread.
 */
final class PluginDiscoverTab {

    enum Mode { LOADING, MARKETPLACE_LIST, PLUGIN_LIST, PLUGIN_DETAILS, OPTIONS, ERROR }
    enum EmptyReason { GIT_NOT_INSTALLED, ALL_BLOCKED_BY_POLICY, POLICY_RESTRICTS_SOURCES,
        NO_MARKETPLACES_CONFIGURED }


    static final String TRUST_WARNING =
        "Make sure you trust a plugin before installing, updating, or using it. "
            + "Anthropic does not control what MCP servers, files, or other software are "
            + "included in plugins and cannot verify that they will work as intended or "
            + "that they won't change. See each plugin's homepage for more information.";


    static final String OFFICIAL_MARKETPLACE_NAME = "claude-plugins-official";


    private static final int SEARCH_BOX_WIDTH = 60;

    record MarketplaceRow(String name, int totalPlugins, int installedCount, String source) {}

    record PluginRow(PluginEntry entry, String marketplaceName, String pluginId,
                     boolean isInstalled) {}

    private record MenuOption(String label, String action) {}

    private record InstallFailure(String name, String reason) {}

    private final PluginPanelServices services;
    private final PluginPanelHost host;

    private Mode mode = Mode.LOADING;
    private String targetMarketplace;
    private String targetPlugin;

    private List<MarketplaceRow> marketplaces = List.of();
    private List<PluginRow> allPlugins = List.of();
    private List<PluginRow> plugins = List.of();
    private int marketplaceIndex;
    private final ListPagination pagination = new ListPagination();


    private Map<String, Long> installCounts;

    private boolean isSearchMode;
    private final SearchInput search = new SearchInput(new SearchInput.Listener() {
        @Override
        public void onExit() {
            isSearchMode = false;
        }

        @Override
        public void onChange() {
            applyFilter();
        }
    });

    private List<PluginRow> filtered = List.of();

    private final Set<String> selectedForInstall = new LinkedHashSet<>();
    private Set<String> installingPlugins = Set.of();

    private PluginRow selectedPlugin;
    private int detailsMenuIndex;
    private boolean isInstalling;
    private String installError;
    private String error;
    private String warning;
    private EmptyReason emptyReason;
    private PluginOptionsFlowView optionsView;
    private List<ConfigurationStep> optionSteps = List.of();
    private int optionStepIndex;
    private String optionPluginId;
    private String optionPluginName;

    PluginDiscoverTab(PluginPanelServices services, PluginPanelHost host) {
        this.services = services;
        this.host = host;
    }


    void open(String targetMarketplace, String targetPlugin) {
        this.targetMarketplace = targetMarketplace;
        this.targetPlugin = targetPlugin;
        this.mode = Mode.LOADING;
        this.error = null;
        this.warning = null;
        this.emptyReason = null;
        this.installError = null;
        this.isInstalling = false;
        this.selectedPlugin = null;
        this.optionsView = null;
        this.optionSteps = List.of();
        this.optionStepIndex = 0;
        this.optionPluginId = null;
        this.optionPluginName = null;
        this.marketplaceIndex = 0;
        this.isSearchMode = false;
        this.search.reset("");
        this.selectedForInstall.clear();
        this.installingPlugins = Set.of();
        reload();
    }

    boolean allowsTabSwitch() {
        if (mode == Mode.PLUGIN_LIST && isSearchMode) {
            return false;
        }
        return mode == Mode.MARKETPLACE_LIST || mode == Mode.PLUGIN_LIST
            || mode == Mode.ERROR || mode == Mode.LOADING;
    }

    private void reload() {
        services.background().execute(() -> {
            try {
                Map<String, PluginMarketplacePort.Marketplace> known = services.plugins().marketplaces();
                Set<String> globallyInstalled = globallyInstalledIds();

                List<MarketplaceRow> rows = new ArrayList<>();
                List<PluginRow> all = new ArrayList<>();
                List<String> failures = new ArrayList<>();
                for (Map.Entry<String, PluginMarketplacePort.Marketplace> entry : known.entrySet()) {
                    String name = entry.getKey();
                    MarketplaceManifest manifest;
                    try {
                        manifest = services.plugins().marketplace(name);
                    } catch (Exception e) {
                        failures.add(name + "\u0000" + (e.getMessage() == null
                            ? e.getClass().getSimpleName() : e.getMessage()));
                        continue; // graceful degradation — Errors tab reports failures
                    }
                    int installedCount = 0;
                    List<PluginEntry> entries =
                        manifest.plugins() == null ? List.of() : manifest.plugins();
                    for (PluginEntry pluginEntry : entries) {
                        String pluginId = pluginEntry.name() + "@" + name;
                        boolean installed = globallyInstalled.contains(pluginId);
                        if (installed) {
                            installedCount++;
                        }
                        all.add(new PluginRow(pluginEntry, name, pluginId, installed));
                    }
                    rows.add(new MarketplaceRow(name, entries.size(), installedCount,
                        entry.getValue().source()));
                }
                rows.sort(Comparator.comparing(
                    (MarketplaceRow r) -> Strings.CS.equals("claude-plugin-directory", r.name()) ? 0 : 1));


                // gracefully degrade to alphabetical on any failure.
                Map<String, Long> counts;
                try {
                    counts = services.plugins().installCounts();
                } catch (Exception _) {
                    counts = null;
                }
                if (counts != null) {
                    Map<String, Long> c = counts;
                    all.sort((a, b) -> {
                        long countA = c.getOrDefault(a.pluginId(), 0L);
                        long countB = c.getOrDefault(b.pluginId(), 0L);
                        if (countA != countB) {
                            return Long.compare(countB, countA);
                        }
                        return a.entry().name().compareTo(b.entry().name());
                    });
                } else {
                    all.sort(Comparator.comparing(r -> r.entry().name()));
                }

                synchronized (this) {
                    this.installCounts = counts;
                    this.marketplaces = rows;
                    this.allPlugins = all;
                    this.warning = marketplaceWarning(failures, rows.size());
                    boolean allFailed = !known.isEmpty() && rows.isEmpty() && !failures.isEmpty();
                    if (allFailed) {
                        this.error = allMarketplaceFailure(failures);
                        this.mode = Mode.ERROR;
                    }
                    if (known.isEmpty()) {
                        PluginMarketplacePort.DiscoveryEnvironment environment =
                            services.plugins().discoveryEnvironment();
                        this.emptyReason = !environment.gitAvailable()
                            ? EmptyReason.GIT_NOT_INSTALLED
                            : environment.allMarketplacesBlocked()
                                ? EmptyReason.ALL_BLOCKED_BY_POLICY
                                : environment.strictPolicyConfigured()
                                    ? EmptyReason.POLICY_RESTRICTS_SOURCES
                                    : EmptyReason.NO_MARKETPLACES_CONFIGURED;
                    }
                    if (!allFailed) {
                        resolveInitialView();
                    }
                }
            } catch (Exception e) {
                synchronized (this) {
                    this.error = e.getMessage() == null ? "Failed to load plugins" : e.getMessage();
                    this.mode = Mode.ERROR;
                }
            }
            host.refresh();
        });
    }

    private static String marketplaceWarning(List<String> failures, int successCount) {
        if (failures.isEmpty() || successCount == 0) return null;
        if (failures.size() == 1) {
            String[] parts = failures.getFirst().split("\u0000", 2);
            return "Warning: Failed to load marketplace '" + parts[0] + "': " + parts[1]
                + ". Showing available plugins.";
        }
        String names = failures.stream().map(failure -> failure.split("\u0000", 2)[0])
            .collect(Collectors.joining(", "));
        return "Warning: Failed to load " + failures.size() + " marketplaces: " + names
            + ". Showing available plugins.";
    }

    private static String allMarketplaceFailure(List<String> failures) {
        String details = failures.stream().map(failure -> {
            String[] parts = failure.split("\u0000", 2);
            return parts[0] + ": " + parts[1];
        }).collect(Collectors.joining("; "));
        return "Failed to load all marketplaces. Errors: " + details;
    }


    private Set<String> globallyInstalledIds() {
        Set<String> ids = new HashSet<>();
        for (PluginMarketplacePort.InstalledPlugin status : services.plugins().installedPlugins()) {
            for (PluginMarketplacePort.Installation installation : status.installations()) {
                if (installation.scope() == Scope.USER
                        || installation.scope() == Scope.MANAGED) {
                    ids.add(status.pluginId());
                }
            }
        }
        return ids;
    }

    private void resolveInitialView() {
        if (targetPlugin != null) {
            PluginRow found = allPlugins.stream()
                .filter(p -> targetPlugin.equals(p.entry().name()))
                .findFirst().orElse(null);
            if (found == null) {
                error = "Plugin \"" + targetPlugin + "\" not found in any marketplace";
                mode = Mode.ERROR;
            } else if (found.isInstalled()) {
                error = "Plugin '" + found.pluginId() + "' is already installed globally. "
                    + "Use '/plugin' to manage existing plugins.";
                mode = Mode.ERROR;
            } else {
                selectMarketplace(found.marketplaceName());
                selectedPlugin = found;
                detailsMenuIndex = 0;
                mode = Mode.PLUGIN_DETAILS;
            }
            return;
        }
        if (targetMarketplace != null) {
            boolean exists = marketplaces.stream()
                .anyMatch(m -> m.name().equals(targetMarketplace));
            if (exists) {
                selectMarketplace(targetMarketplace);
                mode = Mode.PLUGIN_LIST;
            } else {
                error = "Marketplace \"" + targetMarketplace + "\" not found";
                mode = Mode.ERROR;
            }
            return;
        }
        if (marketplaces.size() == 1) {
            selectMarketplace(marketplaces.getFirst().name());
            mode = Mode.PLUGIN_LIST;
            return;
        }
        mode = Mode.MARKETPLACE_LIST;
    }

    private void selectMarketplace(String name) {
        plugins = allPlugins.stream()
            .filter(p -> p.marketplaceName().equals(name))
            .toList();
        isSearchMode = false;
        search.reset("");
        selectedForInstall.clear();
        applyFilter();
    }


    private void applyFilter() {
        String query = search.query().toLowerCase(Locale.ROOT);
        filtered = query.isEmpty() ? plugins : plugins.stream()
            .filter(p -> Strings.CI.contains(p.entry().name(), query)
                || (p.entry().description() != null
                    && Strings.CI.contains(p.entry().description(), query))
                || Strings.CI.contains(p.marketplaceName(), query))
            .toList();
        pagination.reset(filtered.size());
    }

    // ── keys ──────────────────────────────────────────────────────────────────

    synchronized void handleKey(KeyStroke key) {
        switch (mode) {
            case LOADING -> handleLoadingKey(key);
            case MARKETPLACE_LIST -> handleMarketplaceListKey(key);
            case PLUGIN_LIST -> handlePluginListKey(key);
            case PLUGIN_DETAILS -> handleDetailsKey(key);
            case OPTIONS -> optionsView.handleKey(key);
            case ERROR -> handleErrorKey(key);
        }
    }

    boolean pluginContextActive() {
        return mode == Mode.PLUGIN_LIST && !isSearchMode;
    }

    boolean confirmationContextActive() {
        return !(mode == Mode.PLUGIN_LIST && isSearchMode) && mode != Mode.OPTIONS;
    }

    boolean handleConfirmationAction(String action) {
        if (!Strings.CS.equals("confirm:no", action)) return false;
        handleKey(new KeyStroke(KeyType.ESCAPE));
        return true;
    }

    boolean selectContextActive() {
        return !isSearchMode && switch (mode) {
            case MARKETPLACE_LIST, PLUGIN_LIST, PLUGIN_DETAILS, ERROR -> true;
            default -> false;
        };
    }

    void handleKeybindingAction(String action) {
        KeyStroke synthetic = switch (action) {
            case "select:previous" -> new KeyStroke(KeyType.ARROW_UP);
            case "select:next" -> new KeyStroke(KeyType.ARROW_DOWN);
            case "select:accept" -> new KeyStroke(KeyType.ENTER);
            case "select:cancel" -> new KeyStroke(KeyType.ESCAPE);
            case "plugin:toggle" -> new KeyStroke(' ', false, false);
            case "plugin:install" -> new KeyStroke('i', false, false);
            default -> null;
        };
        if (synthetic != null) handleKey(synthetic);
    }

    private void handleLoadingKey(KeyStroke key) {
        if (key.getKeyType() == KeyType.ESCAPE) {
            host.closePanel();
        }
    }

    private void handleErrorKey(KeyStroke key) {
        if (key.getKeyType() == KeyType.ESCAPE || key.getKeyType() == KeyType.ENTER) {
            host.closePanel();
        }
    }

    private void handleMarketplaceListKey(KeyStroke key) {
        KeyType t = key.getKeyType();
        if (t == KeyType.ESCAPE) {
            host.closePanel();
            return;
        }
        if (isUp(key)) {
            marketplaceIndex = Math.max(0, marketplaceIndex - 1);
            return;
        }
        if (isDown(key)) {
            marketplaceIndex = Math.min(Math.max(0, marketplaces.size() - 1), marketplaceIndex + 1);
            return;
        }
        if (t == KeyType.ENTER && marketplaceIndex < marketplaces.size()) {
            selectMarketplace(marketplaces.get(marketplaceIndex).name());
            mode = Mode.PLUGIN_LIST;
        }
    }

    private void handlePluginListKey(KeyStroke key) {
        if (isSearchMode) {

            search.handleKey(key);
            return;
        }
        KeyType t = key.getKeyType();
        if (t == KeyType.ESCAPE) {

            if (targetMarketplace != null) {
                host.switchToMarketplaces(targetMarketplace, null);
            } else if (marketplaces.size() <= 1) {
                host.closePanel();
            } else {
                mode = Mode.MARKETPLACE_LIST;
            }
            return;
        }
        if (isUp(key)) {

            if (pagination.selectedIndex() == 0) {
                isSearchMode = true;
            } else {
                pagination.moveBy(-1);
            }
            return;
        }
        if (isDown(key)) {
            pagination.moveBy(1);
            return;
        }
        if (t == KeyType.ENTER) {
            if (pagination.selectedIndex() == filtered.size() && !selectedForInstall.isEmpty()) {
                installSelectedPlugins();
            } else if (pagination.selectedIndex() < filtered.size()) {
                PluginRow plugin = filtered.get(pagination.selectedIndex());
                if (plugin.isInstalled()) {
                    host.switchToInstalled(plugin.entry().name(), plugin.marketplaceName(), null);
                } else {
                    selectedPlugin = plugin;
                    detailsMenuIndex = 0;
                    installError = null;
                    mode = Mode.PLUGIN_DETAILS;
                }
            }
            return;
        }
        if (t == KeyType.PASTE) {

            if (key instanceof PasteKeyStroke pks) {
                String text = pks.getPastedText().replaceAll("[\\r\\n]", "");
                if (!StringUtils.isBlank(text)) {
                    isSearchMode = true;
                    search.reset(text);
                    applyFilter();
                }
            }
            return;
        }
        if (t == KeyType.CHARACTER && key.getCharacter() != null
                && !key.isCtrlDown() && !key.isAltDown()) {
            char ch = key.getCharacter();
            if (ch == ' ') {
                togglePluginSelection();
                return;
            }
            if (ch == 'i') {
                if (!selectedForInstall.isEmpty()) {
                    installSelectedPlugins();
                }
                return;
            }
            if (ch == '/') {
                isSearchMode = true;
                search.reset("");
                applyFilter();
                return;
            }
            if (ch > 0x20) { // any other printable char seeds the query (j/k consumed above)
                isSearchMode = true;
                search.reset(String.valueOf(ch));
                applyFilter();
            }
        }
    }


    private void togglePluginSelection() {
        if (pagination.selectedIndex() < filtered.size()) {
            PluginRow plugin = filtered.get(pagination.selectedIndex());
            if (!plugin.isInstalled() && !selectedForInstall.remove(plugin.pluginId())) {
                selectedForInstall.add(plugin.pluginId());
            }
        }
    }

    private void handleDetailsKey(KeyStroke key) {
        KeyType t = key.getKeyType();
        if (t == KeyType.ESCAPE) {
            selectedPlugin = null;
            mode = Mode.PLUGIN_LIST;
            return;
        }
        if (isInstalling) {
            return;
        }
        List<MenuOption> options = detailsMenuOptions();
        if (isUp(key)) {
            detailsMenuIndex = Math.max(0, detailsMenuIndex - 1);
            return;
        }
        if (isDown(key)) {
            detailsMenuIndex = Math.min(options.size() - 1, detailsMenuIndex + 1);
            return;
        }
        if (t == KeyType.ENTER) {
            String action = options.get(detailsMenuIndex).action();
            switch (action) {
                case "install-user" -> install(Scope.USER);
                case "install-project" -> install(Scope.PROJECT);
                case "install-local" -> install(Scope.LOCAL);
                case "homepage" -> openExternalUrl(selectedPlugin.entry().homepage());
                case "github" -> openExternalUrl(selectedPlugin.entry().repository());
                case "back" -> {
                    selectedPlugin = null;
                    mode = Mode.PLUGIN_LIST;
                }
                default -> { }
            }
        }
    }


    private List<MenuOption> detailsMenuOptions() {
        List<MenuOption> options = new ArrayList<>();
        options.add(new MenuOption("Install for you (user scope)", "install-user"));
        options.add(new MenuOption("Install for all collaborators on this repository (project scope)",
            "install-project"));
        options.add(new MenuOption("Install for you, in this repo only (local scope)", "install-local"));
        if (selectedPlugin != null && selectedPlugin.entry().homepage() != null) {
            options.add(new MenuOption("Open homepage", "homepage"));
        }
        if (selectedPlugin != null && selectedPlugin.entry().repository() != null) {
            options.add(new MenuOption("View on GitHub", "github"));
        }
        options.add(new MenuOption("Back to plugin list", "back"));
        return List.copyOf(options);
    }

    private void openExternalUrl(String url) {
        if (url == null) return;
        services.background().execute(() -> {
            services.plugins().openExternalUrl(url);
            host.refresh();
        });
    }

    // ── install ───────────────────────────────────────────────────────────────


    private void installSelectedPlugins() {
        if (selectedForInstall.isEmpty()) {
            return;
        }
        List<PluginRow> toInstall = plugins.stream()
            .filter(p -> selectedForInstall.contains(p.pluginId()))
            .toList();
        installingPlugins = toInstall.stream()
            .map(PluginRow::pluginId)
            .collect(Collectors.toUnmodifiableSet());
        services.background().execute(() -> {
            int successCount = 0;
            List<InstallFailure> failures = new ArrayList<>();
            for (PluginRow plugin : toInstall) {
                try {
                    services.plugins().install(
                        plugin.entry().name(), plugin.marketplaceName(), Scope.USER);
                    successCount++;
                } catch (Exception e) {
                    failures.add(new InstallFailure(plugin.entry().name(), e.getMessage()));
                }
            }
            synchronized (this) {
                installingPlugins = Set.of();
                selectedForInstall.clear();
            }
            // Outside the tab lock — host.finish takes the panel lock and the
            // GUI thread takes panel→tab; never invert that order here.
            if (failures.isEmpty()) {
                host.finish("✓ Installed " + successCount + " "
                    + plural(successCount, "plugin") + ". Run /reload-plugins to activate.");
            } else if (successCount == 0) {

                host.record("Failed to install: " + formatFailureDetails(failures, true),
                    LanternaTheme.toolError());
                host.closePanel();
            } else {
                host.finish("✓ Installed " + successCount + " of "
                    + (successCount + failures.size()) + " plugins. "
                    + "Failed: " + formatFailureDetails(failures, false) + ". "
                    + "Run /reload-plugins to activate successfully installed plugins.");
            }
            host.refresh();
        });
    }


    static String formatFailureDetails(List<InstallFailure> failures, boolean includeReasons) {
        int maxShow = 2;
        String details = failures.stream()
            .limit(maxShow)
            .map(f -> {
                String reason = StringUtils.isEmpty(f.reason())
                    ? "unknown error" : f.reason();
                return includeReasons ? f.name() + " (" + reason + ")" : f.name();
            })
            .collect(Collectors.joining(includeReasons ? "; " : ", "));
        int remaining = failures.size() - maxShow;
        return remaining > 0 ? details + " and " + remaining + " more" : details;
    }

    private void install(Scope scope) {
        PluginRow plugin = selectedPlugin;
        if (plugin == null) {
            return;
        }
        isInstalling = true;
        installError = null;
        services.background().execute(() -> {
            try {
                PluginMarketplacePort.InstallResult result = services.plugins()
                    .install(plugin.entry().name(), plugin.marketplaceName(), scope);
                List<ConfigurationStep> steps = services.plugins()
                    .unconfiguredSteps(result.pluginId(), result.installPath());
                boolean needsOptions;
                synchronized (this) {
                    isInstalling = false;
                    needsOptions = !steps.isEmpty();
                    if (needsOptions) {
                        openOptionsFlow(plugin.entry().name(), result.pluginId(), steps);
                    }
                }
                if (!needsOptions) {
                    // Outside the tab lock — host.finish takes the panel lock and the
                    // GUI thread takes panel→tab; never invert that order here.
                    host.finish("✓ Installed " + plugin.entry().name()
                        + ". Run /reload-plugins to activate.");
                }
            } catch (Exception e) {
                synchronized (this) {
                    isInstalling = false;
                    installError = e.getMessage();
                }
            }
            host.refresh();
        });
    }

    private void openOptionsFlow(String pluginName, String pluginId,
                                 List<ConfigurationStep> steps) {
        optionPluginName = pluginName;
        optionPluginId = pluginId;
        optionSteps = List.copyOf(steps);
        optionStepIndex = 0;
        openCurrentOptionsStep();
        mode = Mode.OPTIONS;
    }

    private void openCurrentOptionsStep() {
        ConfigurationStep step = optionSteps.get(optionStepIndex);
        optionsView = new PluginOptionsFlowView(
            step.title(), step.subtitle(), step.schema(), step.existingValues(),
            new PluginOptionsFlowView.Listener() {
                @Override
                public void onSave(Map<String, Object> values) {
                    PluginOptionsFlowView current = optionsView;
                    current.setSaving(true);
                    host.refresh();
                    Map<String, Object> snapshot = Map.copyOf(values);
                    services.background().execute(() -> {
                    try {
                            services.plugins().saveConfigurationStep(optionPluginId, step, snapshot);
                            host.postToGui(() -> {
                        optionStepIndex++;
                        if (optionStepIndex < optionSteps.size()) {
                            openCurrentOptionsStep();
                            host.refresh();
                        } else {
                            host.finish("✓ Installed and configured " + optionPluginName
                                + ". Run /reload-plugins to apply.");
                        }
                            });
                    } catch (Exception e) {
                            host.postToGui(() -> {
                                current.setError("Installed but failed to save config: " + e.getMessage());
                                host.refresh();
                            });
                    }
                    });
                }

                @Override
                public void onCancel() {
                    host.finish("✓ Installed " + optionPluginName
                        + ". Run /reload-plugins to apply.");
                }
            });
    }

    // ── render ────────────────────────────────────────────────────────────────

    synchronized List<StyledText.Line> buildLines() {
        return switch (mode) {
            case LOADING -> List.of(line("Loading…", LanternaTheme.inputText()));
            case ERROR -> List.of(
                line(error, LanternaTheme.toolError()),
                blank(),
                line("Esc to go back", LanternaTheme.welcomeDim()));
            case MARKETPLACE_LIST -> buildMarketplaceListLines();
            case PLUGIN_LIST -> buildPluginListLines();
            case PLUGIN_DETAILS -> buildDetailsLines();
            case OPTIONS -> optionsView.buildLines();
        };
    }

    private List<StyledText.Line> buildMarketplaceListLines() {
        List<StyledText.Line> lines = new ArrayList<>();
        lines.add(boldLine("Select marketplace", LanternaTheme.inputText()));
        lines.add(blank());
        addWarning(lines);
        if (marketplaces.isEmpty()) {
            addEmptyMarketplaceMessage(lines);
            lines.add(blank());
            lines.add(line("Esc to go back", LanternaTheme.welcomeDim()));
            return lines;
        }
        for (int i = 0; i < marketplaces.size(); i++) {
            MarketplaceRow m = marketplaces.get(i);
            boolean selected = i == marketplaceIndex;
            lines.add(line(
                seg(selected ? "❯ " : "  ",
                    selected ? LanternaTheme.suggestion() : LanternaTheme.inputText()),
                seg(m.name(), selected ? LanternaTheme.suggestion() : LanternaTheme.inputText())));
            String detail = m.totalPlugins() + " " + plural(m.totalPlugins(), "plugin")
                + " available"
                + (m.installedCount() > 0 ? " · " + m.installedCount() + " already installed" : "")
                + (m.source() != null ? " · " + m.source() : "");
            lines.add(line("  " + detail, LanternaTheme.welcomeDim()));
            if (i < marketplaces.size() - 1) {
                lines.add(blank());
            }
        }
        lines.add(blank());
        lines.add(line("Enter to select · Esc to go back", LanternaTheme.welcomeDim()));
        return lines;
    }

    private List<StyledText.Line> buildPluginListLines() {
        List<StyledText.Line> lines = new ArrayList<>();
        if (plugins.isEmpty()) {
            lines.add(boldLine("Install plugins", LanternaTheme.inputText()));
            lines.add(blank());
            lines.add(line("No new plugins available to install.", LanternaTheme.welcomeDim()));
            lines.add(line("All plugins from this marketplace are already installed.",
                LanternaTheme.welcomeDim()));
            lines.add(blank());
            lines.add(line("Esc to go back", LanternaTheme.welcomeDim()));
            return lines;
        }
        lines.add(boldLine("Install Plugins", LanternaTheme.inputText()));
        lines.add(blank());
        addWarning(lines);
        lines.addAll(searchBoxLines());
        lines.add(blank());
        if (filtered.isEmpty() && !search.query().isEmpty()) {

            lines.add(line("No plugins match \"" + search.query() + "\"",
                LanternaTheme.welcomeDim()));
            lines.add(blank());
        }
        if (pagination.canScrollUp()) {
            lines.add(line(" ↑ more above", LanternaTheme.welcomeDim()));
        }
        for (int i = pagination.startIndex(); i < pagination.endIndex(); i++) {
            PluginRow plugin = filtered.get(i);

            boolean selected = i == pagination.selectedIndex() && !isSearchMode;

            // ◉ selected-for-install / ◯ default.
            String icon = plugin.isInstalled() ? "✔"
                : installingPlugins.contains(plugin.pluginId()) ? "…"
                : selectedForInstall.contains(plugin.pluginId()) ? "◉" : "◯";
            StringBuilder label = new StringBuilder(icon + " " + plugin.entry().name());
            if (plugin.entry().category() != null) {
                label.append(" [").append(plugin.entry().category()).append("]");
            }
            if (plugin.entry().tags() != null
                    && plugin.entry().tags().contains("community-managed")) {
                label.append(" [Community Managed]");
            }
            if (plugin.isInstalled()) {
                label.append(" (installed)");
            }
            List<StyledText.Seg> segs = new ArrayList<>();
            segs.add(seg(selected ? "❯ " : "  ",
                selected ? LanternaTheme.suggestion() : LanternaTheme.inputText()));
            segs.add(seg(label.toString(), plugin.isInstalled()
                ? LanternaTheme.toolSuccess() : LanternaTheme.inputText()));
            if (installCounts != null
                    && OFFICIAL_MARKETPLACE_NAME.equals(plugin.marketplaceName())) {

                segs.add(seg(" · " + PluginMarketplacePort.formatInstallCount(
                        installCounts.getOrDefault(plugin.pluginId(), 0L)) + " installs",
                    LanternaTheme.welcomeDim()));
            }
            lines.add(line(segs.toArray(StyledText.Seg[]::new)));
            if (plugin.entry().description() != null) {
                String desc = truncate(plugin.entry().description(), 60);
                if (plugin.entry().version() != null) {
                    desc += " · v" + plugin.entry().version();
                }
                lines.add(line("    " + desc, LanternaTheme.welcomeDim()));
            }
            if (i < pagination.endIndex() - 1) {
                lines.add(blank());
            }
        }
        if (pagination.canScrollDown()) {
            lines.add(line(" ↓ more below", LanternaTheme.welcomeDim()));
        }
        lines.add(blank());
        lines.add(keyHintLine());
        return lines;
    }

    private void addWarning(List<StyledText.Line> lines) {
        if (warning == null) return;
        lines.add(line(warning, LanternaTheme.toolWarning()));
        lines.add(blank());
    }

    private void addEmptyMarketplaceMessage(List<StyledText.Line> lines) {
        switch (emptyReason == null ? EmptyReason.NO_MARKETPLACES_CONFIGURED : emptyReason) {
            case GIT_NOT_INSTALLED -> {
                lines.add(line("Git is required to install marketplaces.", LanternaTheme.welcomeDim()));
                lines.add(line("Please install git and restart Claude Code.", LanternaTheme.welcomeDim()));
            }
            case ALL_BLOCKED_BY_POLICY -> {
                lines.add(line("Your organization policy does not allow any external marketplaces.",
                    LanternaTheme.welcomeDim()));
                lines.add(line("Contact your administrator.", LanternaTheme.welcomeDim()));
            }
            case POLICY_RESTRICTS_SOURCES -> {
                lines.add(line("Your organization restricts which marketplaces can be added.",
                    LanternaTheme.welcomeDim()));
                lines.add(line("Switch to the Marketplaces tab to view allowed sources.",
                    LanternaTheme.welcomeDim()));
            }
            case NO_MARKETPLACES_CONFIGURED -> {
                lines.add(line("No plugins available.", LanternaTheme.welcomeDim()));
                lines.add(line("Add a marketplace first using the Marketplaces tab.",
                    LanternaTheme.welcomeDim()));
            }
        }
    }


    private List<StyledText.Line> searchBoxLines() {
        TextColor border = isSearchMode ? LanternaTheme.suggestion() : LanternaTheme.ghostText();
        List<StyledText.Line> lines = new ArrayList<>(3);
        lines.add(line(LanternaDraw.borderedSearchBoxTop(SEARCH_BOX_WIDTH), border));
        lines.add(line(LanternaDraw.borderedSearchBoxContent(isSearchMode, search.query(), search.cursorOffset(), SEARCH_BOX_WIDTH), border));
        lines.add(line(LanternaDraw.borderedSearchBoxBottom(SEARCH_BOX_WIDTH), border));
        return lines;
    }


    private StyledText.Line keyHintLine() {
        boolean hasSelection = !selectedForInstall.isEmpty();
        boolean canToggle = pagination.selectedIndex() < filtered.size()
            && !filtered.isEmpty()
            && !filtered.get(pagination.selectedIndex()).isInstalled();
        TextColor dim = LanternaTheme.welcomeDim();
        List<StyledText.Seg> segs = new ArrayList<>();
        if (hasSelection) {
            segs.add(bold("i", dim));
            segs.add(seg(" to install · ", dim));
        }
        segs.add(seg("type to search", dim));
        if (canToggle) {
            segs.add(seg(" · Space to toggle", dim));
        }
        segs.add(seg(" · Enter to details · Esc to back", dim));
        return line(segs.toArray(StyledText.Seg[]::new));
    }

    private List<StyledText.Line> buildDetailsLines() {
        List<StyledText.Line> lines = new ArrayList<>();
        PluginEntry entry = selectedPlugin.entry();
        lines.add(boldLine("Plugin Details", LanternaTheme.inputText()));
        lines.add(blank());
        lines.add(boldLine(entry.name(), LanternaTheme.inputText()));
        lines.add(line("from " + selectedPlugin.marketplaceName(), LanternaTheme.welcomeDim()));
        if (entry.version() != null) {
            lines.add(line("Version: " + entry.version(), LanternaTheme.welcomeDim()));
        }
        if (entry.description() != null) {
            lines.add(blank());
            lines.add(line(entry.description(), LanternaTheme.inputText()));
        }
        if (entry.author() != null && entry.author().name() != null) {
            lines.add(blank());
            lines.add(line("By: " + entry.author().name(), LanternaTheme.welcomeDim()));
        }
        lines.add(blank());
        lines.add(line(
            seg("⚠ ", LanternaTheme.claude()),
            seg(TRUST_WARNING, LanternaTheme.welcomeDim())));
        lines.add(blank());
        if (installError != null) {
            lines.add(line("Error: " + installError, LanternaTheme.toolError()));
            lines.add(blank());
        }
        List<MenuOption> options = detailsMenuOptions();
        for (int i = 0; i < options.size(); i++) {
            MenuOption option = options.get(i);
            boolean selected = i == detailsMenuIndex;
            String label = isInstalling && Strings.CS.startsWith(option.action(), "install-")
                ? "Installing…" : option.label();
            lines.add(line(selected
                ? bold("> " + label, LanternaTheme.inputText())
                : seg("  " + label, LanternaTheme.inputText())));
        }
        lines.add(blank());
        lines.add(line("Enter to select · Esc to back", LanternaTheme.welcomeDim()));
        return lines;
    }

    private static boolean isUp(KeyStroke key) {
        return key.getKeyType() == KeyType.ARROW_UP
            || (key.getKeyType() == KeyType.CHARACTER && key.getCharacter() != null
                && key.getCharacter() == 'k' && !key.isCtrlDown() && !key.isAltDown());
    }

    private static boolean isDown(KeyStroke key) {
        return key.getKeyType() == KeyType.ARROW_DOWN
            || (key.getKeyType() == KeyType.CHARACTER && key.getCharacter() != null
                && key.getCharacter() == 'j' && !key.isCtrlDown() && !key.isAltDown());
    }

    // ── test accessors ────────────────────────────────────────────────────────

    Mode mode() {
        return mode;
    }

    List<PluginRow> plugins() {
        return plugins;
    }

    List<PluginRow> filteredPlugins() {
        return filtered;
    }

    boolean isSearchMode() {
        return isSearchMode;
    }

    String searchQuery() {
        return search.query();
    }

    Set<String> selectedForInstall() {
        return Set.copyOf(selectedForInstall);
    }

    int selectedPluginIndex() {
        return pagination.selectedIndex();
    }

    PluginRow selectedPlugin() {
        return selectedPlugin;
    }

    String error() {
        return error;
    }

    PluginOptionsFlowView optionsView() {
        return optionsView;
    }
}
