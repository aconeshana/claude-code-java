package com.claudecode.ui.lanterna.plugin;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.runtime.plugins.PluginMarketplacePort;
import com.claudecode.runtime.mcp.McpManagementPort;
import com.claudecode.runtime.mcp.McpManagementPort.Action;
import com.claudecode.runtime.plugins.PluginMarketplacePort.ConfigOption;
import com.claudecode.runtime.plugins.PluginMarketplacePort.ConfigurationStep;
import com.claudecode.runtime.plugins.PluginMarketplacePort.Scope;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static com.claudecode.ui.lanterna.components.StyledText.blank;
import static com.claudecode.ui.lanterna.components.StyledText.bold;
import static com.claudecode.ui.lanterna.components.StyledText.boldLine;
import static com.claudecode.ui.lanterna.components.StyledText.line;
import static com.claudecode.ui.lanterna.components.StyledText.seg;
import com.claudecode.ui.lanterna.components.ListPagination;
import com.claudecode.ui.lanterna.components.StyledText;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.components.LanternaDraw;
import com.claudecode.ui.lanterna.input.SearchInput;
import com.claudecode.core.config.ClaudePaths;

/**
 * Installed tab: the installed-plugin list grouped by scope with Space enable/disable toggling, a
 * per-plugin details view with enable/disable/configure/uninstall actions, and route-driven
 * auto-navigation ({@code /plugin enable|disable|uninstall <name>}).
 */
final class PluginInstalledTab {

    private static final int SEARCH_BOX_WIDTH = 60;

    enum Mode { LOADING, LIST, DETAILS, FLAGGED_DETAILS, FAILED_DETAILS,
        MCP_DETAILS, MCP_TOOLS, MCP_TOOL_DETAIL, OPTIONS,
        CONFIRM_PROJECT_UNINSTALL, CONFIRM_DATA_CLEANUP }

    private sealed interface ListItem permits InstalledItem, FlaggedItem, FailedItem, McpItem {
        String pluginId();
        String name();
        String marketplace();
        String description();
    }

    record InstalledItem(String pluginId, String pluginName, String marketplace, Scope scope,
                         boolean enabled, String description, String version, String author,
                         String homepage, String repository, Path installPath,
                         LinkedHashMap<String, ConfigOption> userConfig,
                         boolean hasMcpb) implements ListItem {
        @Override
        public String name() {
            return pluginName;
        }
    }

    record FlaggedItem(String pluginId, String name, String marketplace,
                       String description, String flaggedAt) implements ListItem {}

    record FailedItem(String pluginId, String name, String marketplace, Scope scope,
                      String description, List<PluginMarketplacePort.ErrorView> errors)
        implements ListItem {}

    record McpItem(String pluginId, String name, String marketplace, String description,
                   McpManagementPort.Server server) implements ListItem {}

    private record MenuItem(String label, Runnable action) {}

    private final PluginPanelServices services;
    private final PluginPanelHost host;

    private Mode mode = Mode.LOADING;
    private List<InstalledItem> items = List.of();
    private List<FlaggedItem> flaggedItems = List.of();
    private List<FailedItem> failedItems = List.of();
    private List<McpItem> mcpItems = List.of();
    private final ListPagination pagination = new ListPagination(8);
    private final Map<String, String> pendingToggles = new LinkedHashMap<>();
    private final Set<String> pendingUpdates = new LinkedHashSet<>();
    private boolean isSearchMode;
    private final SearchInput search = new SearchInput(new SearchInput.Listener() {
        @Override
        public void onExit() {
            isSearchMode = false;
        }

        @Override
        public void onChange() {
            pagination.reset(filteredItems().size());
        }
    });

    private InstalledItem selectedItem;
    private FlaggedItem selectedFlaggedItem;
    private FailedItem selectedFailedItem;
    private McpItem selectedMcpItem;
    private int mcpMenuIndex;
    private List<McpManagementPort.Tool> mcpTools = List.of();
    private int mcpToolIndex;
    private McpManagementPort.Tool selectedMcpTool;
    private int detailsMenuIndex;
    private boolean isProcessing;
    private String processError;
    private PluginOptionsFlowView optionsView;
    private List<ConfigurationStep> configurationSteps = List.of();
    private int configurationStepIndex;
    private PluginMarketplacePort.PluginDataDirectory pendingDataDirectory;

    private String targetPlugin;
    private String targetMarketplace;
    private String pendingAction;

    PluginInstalledTab(PluginPanelServices services, PluginPanelHost host) {
        this.services = services;
        this.host = host;
    }

    /**
     * Opens/reopens the tab.
     */
    void open(String targetPlugin, String targetMarketplace, String action) {
        this.targetPlugin = targetPlugin;
        this.targetMarketplace = targetMarketplace;
        this.pendingAction = action;
        this.mode = Mode.LOADING;
        this.selectedItem = null;
        this.selectedFlaggedItem = null;
        this.selectedFailedItem = null;
        this.selectedMcpItem = null;
        this.mcpTools = List.of();
        this.mcpToolIndex = 0;
        this.selectedMcpTool = null;
        this.processError = null;
        this.isProcessing = false;
        this.optionsView = null;
        this.configurationSteps = List.of();
        this.configurationStepIndex = 0;
        this.pendingDataDirectory = null;
        this.pendingToggles.clear();
        this.pendingUpdates.clear();
        this.isSearchMode = false;
        this.search.reset("");
        reload();
    }

    boolean allowsTabSwitch() {
        return mode == Mode.LIST || mode == Mode.LOADING;
    }

    private void reload() {
        services.background().execute(() -> {
            try {
                List<FlaggedItem> loadedFlagged = loadFlaggedItems();
                List<FailedItem> loadedFailed = loadFailedItems();
                List<McpItem> loadedMcp = loadMcpItems();

                // read installed records afterwards so auto-removed rows do not linger.
                Set<String> failedPluginIds = loadedFailed.stream()
                    .map(FailedItem::pluginId).collect(Collectors.toSet());
                List<InstalledItem> loaded = loadItems(failedPluginIds);
                synchronized (this) {
                    items = loaded;
                    flaggedItems = loadedFlagged;
                    failedItems = loadedFailed;
                    mcpItems = loadedMcp;
                    pagination.reset(filteredItems().size());
                    mode = Mode.LIST;
                    autoNavigate();
                }
            } catch (Exception e) {
                synchronized (this) {
                    items = List.of();
                    flaggedItems = List.of();
                    failedItems = List.of();
                    mcpItems = List.of();
                    pagination.reset(0);
                    mode = Mode.LIST;
                    processError = e.getMessage();
                }
            }
            host.refresh();
        });
    }

    private List<InstalledItem> loadItems(Set<String> failedPluginIds) {
        List<InstalledItem> loaded = new ArrayList<>();
        for (PluginMarketplacePort.InstalledPlugin status : services.plugins().installedPlugins()) {
            if (failedPluginIds.contains(status.pluginId())) continue;
            String name = PluginMarketplacePort.pluginName(status.pluginId());
            String marketplace = PluginMarketplacePort.pluginMarketplace(status.pluginId());

            boolean enabled = status.enabled() != Boolean.FALSE;
            List<PluginMarketplacePort.Installation> installations = status.installations().isEmpty()
                ? List.of(new PluginMarketplacePort.Installation(Scope.USER, null, null, status.version()))
                : status.installations();
            for (PluginMarketplacePort.Installation installation : installations) {
                boolean hasMcpb = false;
                if (installation.installPath() != null) {
                    try {
                        hasMcpb = services.plugins().hasMcpb(
                            status.pluginId(), installation.installPath());
                    } catch (RuntimeException _) { }
                }
                loaded.add(new InstalledItem(
                    status.pluginId(), name, marketplace == null ? "local" : marketplace,
                    installation.scope(), enabled, status.description(), installation.version(),
                    status.author(), status.homepage(), status.repository(),
                    installation.installPath(), status.userConfig(), hasMcpb));
            }
        }
        loaded.sort(Comparator
            .comparingInt((InstalledItem i) -> scopeOrder(i.scope()))
            .thenComparing(InstalledItem::name));
        return loaded;
    }

    private List<FlaggedItem> loadFlaggedItems() {
        return services.plugins().flaggedPlugins().stream()
            .map(flagged -> {
                String pluginId = flagged.pluginId();
                String name = PluginMarketplacePort.pluginName(pluginId);
                String marketplace = PluginMarketplacePort.pluginMarketplace(pluginId);
                return new FlaggedItem(pluginId, name,
                    marketplace == null ? "unknown" : marketplace,
                    "Removed from marketplace", flagged.flaggedAt());
            })
            .sorted(Comparator.comparing(FlaggedItem::name))
            .toList();
    }

    private List<FailedItem> loadFailedItems() {
        return services.plugins().failedPlugins().stream()
            .map(failed -> {
                String pluginId = failed.pluginId();
                String name = PluginMarketplacePort.pluginName(pluginId);
                String marketplace = PluginMarketplacePort.pluginMarketplace(pluginId);
                String description = failed.errors().isEmpty() ? "Failed to load"
                    : failed.errors().getFirst().message();
                return new FailedItem(pluginId, name,
                    marketplace == null ? "unknown" : marketplace,
                    failed.scope(), description, failed.errors());
            })
            .sorted(Comparator.comparingInt((FailedItem item) -> scopeOrder(item.scope()))
                .thenComparing(FailedItem::name))
            .toList();
    }

    private List<McpItem> loadMcpItems() {
        return services.mcp().servers().stream()
            .filter(server -> !Strings.CS.equals("ide", server.name()))
            .map(server -> new McpItem("mcp:" + server.name(), server.displayName(), "",
                server.transport() + " MCP server", server))
            .toList();
    }


    private static int scopeOrder(Scope scope) {
        return switch (scope) {
            case PROJECT -> 0;
            case LOCAL -> 1;
            case USER -> 2;
            case MANAGED -> 4;
        };
    }


    static String scopeLabel(Scope scope) {
        return switch (scope) {
            case PROJECT -> "Project";
            case LOCAL -> "Local";
            case USER -> "User";
            case MANAGED -> "Managed";
        };
    }


    private void autoNavigate() {
        if (targetPlugin == null) {
            return;
        }
        String parsedName = PluginMarketplacePort.pluginName(targetPlugin);
        String parsedMarketplace = PluginMarketplacePort.pluginMarketplace(targetPlugin);
        String wantedMarketplace = targetMarketplace != null ? targetMarketplace : parsedMarketplace;
        InstalledItem found = items.stream()
            .filter(i -> i.name().equals(parsedName))
            .filter(i -> wantedMarketplace == null || i.marketplace().equals(wantedMarketplace))
            .findFirst().orElse(null);
        String action = pendingAction;
        String target = targetPlugin;
        targetPlugin = null;
        targetMarketplace = null;
        pendingAction = null;
        if (found != null) {
            selectedItem = found;
            detailsMenuIndex = 0;
            mode = Mode.DETAILS;
            if (action != null) {
                runOperation(action);
            }
        } else if (action != null) {
            host.finish("Plugin \"" + target + "\" is not installed in this project");
        }
    }

    // ── keys ──────────────────────────────────────────────────────────────────

    synchronized void handleKey(KeyStroke key) {
        switch (mode) {
            case LOADING -> {
                if (key.getKeyType() == KeyType.ESCAPE) {
                    host.closePanel();
                }
            }
            case LIST -> handleListKey(key);
            case DETAILS -> handleDetailsKey(key);
            case FLAGGED_DETAILS -> handleFlaggedDetailsKey(key);
            case FAILED_DETAILS -> handleFailedDetailsKey(key);
            case MCP_DETAILS -> handleMcpDetailsKey(key);
            case MCP_TOOLS -> handleMcpToolsKey(key);
            case MCP_TOOL_DETAIL -> handleMcpToolDetailKey(key);
            case OPTIONS -> optionsView.handleKey(key);
            case CONFIRM_PROJECT_UNINSTALL -> handleProjectUninstallConfirmation(key);
            case CONFIRM_DATA_CLEANUP -> handleDataCleanupConfirmation(key);
        }
    }

    boolean pluginContextActive() {
        return mode == Mode.LIST && !isSearchMode;
    }

    boolean confirmationContextActive() {
        return !(mode == Mode.LIST && isSearchMode)
            && mode != Mode.OPTIONS && mode != Mode.CONFIRM_DATA_CLEANUP;
    }

    boolean handleConfirmationAction(String action) {
        if (Strings.CS.equals("confirm:no", action)) {
            handleKey(new KeyStroke(KeyType.ESCAPE));
            return true;
        }
        if (Strings.CS.equals("confirm:yes", action) && mode == Mode.CONFIRM_PROJECT_UNINSTALL) {
            handleKey(new KeyStroke(KeyType.ENTER));
            return true;
        }
        return false;
    }

    boolean selectContextActive() {
        return !isSearchMode && switch (mode) {
            case LIST, DETAILS, FLAGGED_DETAILS, FAILED_DETAILS,
                 MCP_DETAILS, MCP_TOOLS, MCP_TOOL_DETAIL -> true;
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
            default -> null;
        };
        if (synthetic != null) handleKey(synthetic);
    }

    private void handleListKey(KeyStroke key) {
        if (isSearchMode) {
            search.handleKey(key);
            return;
        }
        KeyType t = key.getKeyType();
        if (t == KeyType.ESCAPE) {
            if (!pendingToggles.isEmpty()) {
                host.finish("Run /reload-plugins to apply plugin changes.");
            } else {
                host.closePanel();
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
        if (t == KeyType.CHARACTER && key.getCharacter() != null && key.getCharacter() == ' ') {
            toggleSelected();
            return;
        }
        if (t == KeyType.CHARACTER && key.getCharacter() != null
                && !key.isCtrlDown() && !key.isAltDown()) {
            char ch = key.getCharacter();
            if (ch == '/') {
                isSearchMode = true;
                search.reset("");
                pagination.reset(filteredItems().size());
                return;
            }
            if (!Character.isWhitespace(ch) && ch != 'j' && ch != 'k') {
                isSearchMode = true;
                search.reset(String.valueOf(ch));
                pagination.reset(filteredItems().size());
                return;
            }
        }
        List<ListItem> filtered = filteredItems();
        if (t == KeyType.ENTER && pagination.selectedIndex() < filtered.size()) {
            ListItem selected = filtered.get(pagination.selectedIndex());
            processError = null;
            if (selected instanceof InstalledItem installed) {
                selectedItem = installed;
                detailsMenuIndex = 0;
                mode = Mode.DETAILS;
            } else if (selected instanceof FlaggedItem flagged) {
                selectedFlaggedItem = flagged;
                mode = Mode.FLAGGED_DETAILS;
            } else if (selected instanceof FailedItem failed) {
                selectedFailedItem = failed;
                mode = Mode.FAILED_DETAILS;
            } else if (selected instanceof McpItem mcp) {
                selectedMcpItem = mcp;
                mcpMenuIndex = 0;
                mode = Mode.MCP_DETAILS;
            }
        }
    }


    private void toggleSelected() {
        List<ListItem> filtered = filteredItems();
        if (pagination.selectedIndex() >= filtered.size()) {
            return;
        }
        ListItem selected = filtered.get(pagination.selectedIndex());
        if (selected instanceof McpItem mcp) {
            toggleMcpFromList(mcp);
            return;
        }
        if (!(selected instanceof InstalledItem item)) {
            return;
        }
        if (item.scope() == Scope.MANAGED) {
            return;
        }
        String current = pendingToggles.get(item.pluginId());
        if (current != null) {
            pendingToggles.remove(item.pluginId());
            boolean reEnable = Strings.CS.equals("will-disable", current);
            services.background().execute(() -> {
                applyEnableSilently(item, reEnable);
                host.refresh();
            });
        } else {
            pendingToggles.put(item.pluginId(), item.enabled() ? "will-disable" : "will-enable");
            boolean enable = !item.enabled();
            services.background().execute(() -> {
                applyEnableSilently(item, enable);
                host.refresh();
            });
        }
    }

    private void applyEnableSilently(InstalledItem item, boolean enable) {
        try {
            if (enable) {
                services.plugins().enable(item.pluginId(), effectiveScope(item));
            } else {
                services.plugins().disable(item.pluginId(), effectiveScope(item));
            }
        } catch (Exception e) {
            synchronized (this) {
                processError = e.getMessage();
            }
        }
    }

    private static Scope effectiveScope(InstalledItem item) {
        return item.scope() == Scope.MANAGED ? Scope.USER : item.scope();
    }

    private void handleDetailsKey(KeyStroke key) {
        KeyType t = key.getKeyType();
        if (t == KeyType.ESCAPE) {
            selectedItem = null;
            processError = null;
            mode = Mode.LIST;
            return;
        }
        if (isProcessing) {
            return;
        }
        List<MenuItem> menu = detailsMenu();
        if (isUp(key)) {
            detailsMenuIndex = Math.max(0, detailsMenuIndex - 1);
            return;
        }
        if (isDown(key)) {
            detailsMenuIndex = Math.min(menu.size() - 1, detailsMenuIndex + 1);
            return;
        }
        if (t == KeyType.ENTER && detailsMenuIndex < menu.size()) {
            menu.get(detailsMenuIndex).action().run();
        }
    }

    private void handleFlaggedDetailsKey(KeyStroke key) {
        if (key.getKeyType() == KeyType.ESCAPE) {
            selectedFlaggedItem = null;
            mode = Mode.LIST;
            return;
        }
        if (key.getKeyType() != KeyType.ENTER || selectedFlaggedItem == null) return;
        FlaggedItem dismissed = selectedFlaggedItem;
        services.background().execute(() -> {
            services.plugins().dismissFlaggedPlugin(dismissed.pluginId());
            synchronized (this) {
                flaggedItems = flaggedItems.stream()
                    .filter(item -> !item.pluginId().equals(dismissed.pluginId()))
                    .toList();
                selectedFlaggedItem = null;
                mode = Mode.LIST;
                pagination.reset(filteredItems().size());
            }
            host.refresh();
        });
    }

    private void handleFailedDetailsKey(KeyStroke key) {
        if (key.getKeyType() == KeyType.ESCAPE) {
            selectedFailedItem = null;
            processError = null;
            mode = Mode.LIST;
            return;
        }
        if (key.getKeyType() != KeyType.ENTER || selectedFailedItem == null
                || selectedFailedItem.scope() == Scope.MANAGED || isProcessing) return;
        FailedItem failed = selectedFailedItem;
        isProcessing = true;
        processError = null;
        services.background().execute(() -> {
            try {
                PluginMarketplacePort.PluginUninstallResult result = services.plugins()
                    .uninstall(failed.pluginId(), failed.scope(), false);
                synchronized (this) {
                    isProcessing = false;
                    if (result.removed()) {
                        failedItems = failedItems.stream()
                            .filter(item -> !(item.pluginId().equals(failed.pluginId())
                                && item.scope() == failed.scope()))
                            .toList();
                        selectedFailedItem = null;
                        mode = Mode.LIST;
                        pagination.reset(filteredItems().size());
                    } else {
                        processError = "Plugin is not installed in " + failed.scope().wire()
                            + " scope.";
                    }
                }
            } catch (Exception e) {
                synchronized (this) {
                    isProcessing = false;
                    processError = e.getMessage();
                }
            }
            host.refresh();
        });
    }

    private void toggleMcpFromList(McpItem item) {
        if (!item.server().manageable()) return;
        services.background().execute(() -> {
            try {
                services.mcp().execute(item.server().disabled() ? Action.ENABLE : Action.DISABLE,
                    item.server().name());
                synchronized (this) {
                    mcpItems = loadMcpItems();
                    pagination.reset(filteredItems().size());
                }
            } catch (Exception e) {
                synchronized (this) {
                    processError = e.getMessage();
                }
            }
            host.refresh();
        });
    }

    private void handleMcpDetailsKey(KeyStroke key) {
        if (key.getKeyType() == KeyType.ESCAPE) {
            selectedMcpItem = null;
            processError = null;
            mode = Mode.LIST;
            return;
        }
        List<String> menu = mcpMenu();
        if (isUp(key)) {
            mcpMenuIndex = Math.max(0, mcpMenuIndex - 1);
            return;
        }
        if (isDown(key)) {
            mcpMenuIndex = Math.min(menu.size() - 1, mcpMenuIndex + 1);
            return;
        }
        if (key.getKeyType() != KeyType.ENTER || menu.isEmpty()) return;
        String action = menu.get(mcpMenuIndex);
        switch (action) {
            case "View tools" -> {
                mcpTools = services.mcp().tools(selectedMcpItem.server().name());
                mcpToolIndex = 0;
                mode = Mode.MCP_TOOLS;
            }
            case "Reconnect" -> runMcpOperation(false);
            case "Enable", "Disable" -> runMcpOperation(true);
            case "Authenticate", "Re-authenticate" -> runMcpAuthOperation(false);
            case "Clear authentication" -> runMcpAuthOperation(true);
            case "Back" -> {
                selectedMcpItem = null;
                mode = Mode.LIST;
            }
            default -> { }
        }
    }

    private void handleMcpToolsKey(KeyStroke key) {
        if (key.getKeyType() == KeyType.ESCAPE) {
            mode = Mode.MCP_DETAILS;
            return;
        }
        if (isUp(key) && !mcpTools.isEmpty()) {
            mcpToolIndex = Math.max(0, mcpToolIndex - 1);
        } else if (isDown(key) && !mcpTools.isEmpty()) {
            mcpToolIndex = Math.min(mcpTools.size() - 1, mcpToolIndex + 1);
        } else if (key.getKeyType() == KeyType.ENTER && !mcpTools.isEmpty()) {
            selectedMcpTool = mcpTools.get(mcpToolIndex);
            mode = Mode.MCP_TOOL_DETAIL;
        }
    }

    private void handleMcpToolDetailKey(KeyStroke key) {
        if (key.getKeyType() == KeyType.ESCAPE) {
            selectedMcpTool = null;
            mode = Mode.MCP_TOOLS;
        }
    }

    private List<String> mcpMenu() {
        if (selectedMcpItem == null) return List.of();
        McpManagementPort.Status status = selectedMcpItem.server().status();
        String serverName = selectedMcpItem.server().name();
        List<String> menu = new ArrayList<>();
        List<McpManagementPort.Tool> availableTools = status == McpManagementPort.Status.CONNECTED
            ? services.mcp().tools(serverName) : List.of();
        if (!availableTools.isEmpty()) menu.add("View tools");
        boolean remote = isRemote(selectedMcpItem.server());
        if (remote && status != McpManagementPort.Status.DISABLED) {
            McpManagementPort.AuthStatus auth = selectedMcpItem.server().authStatus();
            boolean effectivelyAuthenticated = auth == McpManagementPort.AuthStatus.AUTHENTICATED
                || !availableTools.isEmpty();
            if (effectivelyAuthenticated) {
                menu.add("Re-authenticate");
                menu.add("Clear authentication");
            } else if (auth != McpManagementPort.AuthStatus.STATIC_HEADER) {
                menu.add("Authenticate");
            }
        }
        if (status != McpManagementPort.Status.DISABLED
                && status != McpManagementPort.Status.NEEDS_AUTH
                && selectedMcpItem.server().manageable()) {
            menu.add("Reconnect");
        }
        if (selectedMcpItem.server().manageable()) {
            menu.add(status == McpManagementPort.Status.DISABLED ? "Enable" : "Disable");
        }
        if (menu.isEmpty()) menu.add("Back");
        return List.copyOf(menu);
    }

    private void runMcpOperation(boolean toggle) {
        McpItem item = selectedMcpItem;
        isProcessing = true;
        processError = null;
        services.background().execute(() -> {
            try {
                String result = services.mcp().execute(toggle
                    ? (item.server().disabled() ? Action.ENABLE : Action.DISABLE)
                    : Action.RECONNECT, item.server().name());
                synchronized (this) {
                    isProcessing = false;
                }
                host.finish(result);
            } catch (Exception e) {
                synchronized (this) {
                    isProcessing = false;
                    processError = e.getMessage();
                }
                host.refresh();
            }
        });
    }

    private void runMcpAuthOperation(boolean clear) {
        McpItem item = selectedMcpItem;
        isProcessing = true;
        processError = null;
        services.background().execute(() -> {
            try {
                String result = services.mcp().execute(clear
                    ? Action.CLEAR_AUTHENTICATION : Action.AUTHENTICATE, item.server().name());
                synchronized (this) {
                    isProcessing = false;
                }
                host.finish(result);
            } catch (Exception e) {
                synchronized (this) {
                    isProcessing = false;
                    processError = e.getMessage();
                }
                host.refresh();
            }
        });
    }

    private static boolean isRemote(McpManagementPort.Server server) {
        return server.remote();
    }

    private boolean effectiveEnabled(InstalledItem item) {
        String pending = pendingToggles.get(item.pluginId());
        if (pending != null) {
            return Strings.CS.equals("will-enable", pending);
        }
        return item.enabled();
    }

    private List<MenuItem> detailsMenu() {
        InstalledItem item = selectedItem;
        List<MenuItem> menu = new ArrayList<>();
        boolean enabled = effectiveEnabled(item);
        menu.add(new MenuItem(enabled ? "Disable plugin" : "Enable plugin",
            () -> runOperation(enabled ? "disable" : "enable")));
        boolean markedForUpdate = pendingUpdates.contains(itemKey(item));
        menu.add(new MenuItem(markedForUpdate ? "Unmark for update" : "Mark for update",
            this::toggleUpdateMark));
        if (item.hasMcpb()) {
            menu.add(new MenuItem("Configure", this::openMcpbConfiguration));
        }
        if (!item.userConfig().isEmpty()) {
            menu.add(new MenuItem("Configure options", this::openConfigureOptions));
        }
        menu.add(new MenuItem("Update now", () -> runOperation("update")));
        menu.add(new MenuItem("Uninstall", () -> runOperation("uninstall")));
        if (item.homepage() != null) {
            menu.add(new MenuItem("Open homepage", () -> openExternalUrl(item.homepage())));
        }
        if (item.repository() != null) {
            menu.add(new MenuItem("View repository", () -> openExternalUrl(item.repository())));
        }
        menu.add(new MenuItem("Back to plugin list", () -> {
            selectedItem = null;
            processError = null;
            mode = Mode.LIST;
        }));
        return menu;
    }

    private void toggleUpdateMark() {
        InstalledItem item = selectedItem;
        try {
            String unavailable = services.plugins().updateAvailabilityError(item.pluginId());
            if (unavailable != null) {
                processError = unavailable;
                return;
            }
        } catch (Exception e) {
            processError = e.getMessage() == null
                ? "Failed to check plugin update availability" : e.getMessage();
            return;
        }
        String key = itemKey(item);
        if (!pendingUpdates.add(key)) {
            pendingUpdates.remove(key);
        }
        processError = null;
    }

    private static String itemKey(InstalledItem item) {
        return item.pluginId() + "\u0000" + item.scope().wire();
    }

    private void openExternalUrl(String url) {
        services.background().execute(() -> {
            services.plugins().openExternalUrl(url);
            host.refresh();
        });
    }

    // ── operations ────────────────────────────────────────────────────────────

    private void handleProjectUninstallConfirmation(KeyStroke key) {
        if (isProcessing) return;
        if (key.getKeyType() == KeyType.ESCAPE
                || isCharacter(key, 'n')) {
            mode = Mode.DETAILS;
            processError = null;
            return;
        }
        if (key.getKeyType() == KeyType.ENTER || isCharacter(key, 'y')) {
            InstalledItem item = selectedItem;
            isProcessing = true;
            processError = null;
            services.background().execute(() -> {
                try {
                    services.plugins().disableLocally(item.pluginId());
                    synchronized (this) {
                        isProcessing = false;
                    }
                    host.finish("✓ Disabled " + item.name()
                        + " in .claude/settings.local.json. Run /reload-plugins to apply.");
                } catch (Exception e) {
                    synchronized (this) {
                        isProcessing = false;
                        processError = "Failed to write settings: " + e.getMessage();
                    }
                    host.refresh();
                }
            });
        }
    }

    private void handleDataCleanupConfirmation(KeyStroke key) {
        if (isProcessing) return;
        if (key.getKeyType() == KeyType.ESCAPE) {
            pendingDataDirectory = null;
            mode = Mode.DETAILS;
            processError = null;
            return;
        }
        if (isCharacter(key, 'y')) {
            performUninstall(true);
        } else if (isCharacter(key, 'n')) {
            performUninstall(false);
        }
    }

    private static boolean isCharacter(KeyStroke key, char expected) {
        return key.getKeyType() == KeyType.CHARACTER && key.getCharacter() != null
            && Character.toLowerCase(key.getCharacter()) == expected
            && !key.isCtrlDown() && !key.isAltDown();
    }

    private void runOperation(String operation) {
        InstalledItem item = selectedItem;
        if (item == null) {
            return;
        }
        if (item.scope() == Scope.MANAGED) {
            processError =
                "This plugin is managed by your organization. Contact your admin to disable it.";
            return;
        }
        isProcessing = true;
        processError = null;
        services.background().execute(() -> {
            try {
                // host.finish always runs OUTSIDE the tab lock — it takes the panel
                // lock and the GUI thread takes panel→tab; never invert that order.
                switch (operation) {
                    case "enable" -> {
                        services.plugins().enable(item.pluginId(), effectiveScope(item));
                        List<ConfigurationStep> steps = item.installPath() == null
                            ? List.of()
                            : services.plugins().unconfiguredSteps(
                                item.pluginId(), item.installPath());
                        boolean needsOptions;
                        synchronized (this) {
                            isProcessing = false;
                            needsOptions = !steps.isEmpty();
                            if (needsOptions) {
                                openConfigurationSteps(item, steps);
                            }
                        }
                        if (!needsOptions) {
                            host.finish("✓ Enabled " + item.name()
                                + ". Run /reload-plugins to apply.");
                        }
                    }
                    case "disable" -> {
                        services.plugins().disable(item.pluginId(), effectiveScope(item));
                        synchronized (this) {
                            isProcessing = false;
                        }
                        host.finish("✓ Disabled " + item.name() + ". Run /reload-plugins to apply.");
                    }
                    case "uninstall" -> {
                        if (services.plugins().isEnabledAtProjectScope(item.pluginId())) {
                            synchronized (this) {
                                isProcessing = false;
                                mode = Mode.CONFIRM_PROJECT_UNINSTALL;
                            }
                            host.refresh();
                            return;
                        }
                        boolean lastScope = items.stream()
                            .filter(installed -> installed.pluginId().equals(item.pluginId()))
                            .count() <= 1;
                        PluginMarketplacePort.PluginDataDirectory data = lastScope
                            ? services.plugins().pluginDataDirectory(item.pluginId()).orElse(null)
                            : null;
                        if (data != null) {
                            synchronized (this) {
                                isProcessing = false;
                                pendingDataDirectory = data;
                                mode = Mode.CONFIRM_DATA_CLEANUP;
                            }
                            host.refresh();
                            return;
                        }
                        synchronized (this) {
                            isProcessing = false;
                        }
                        performUninstall(true);
                    }
                    case "update" -> {
                        PluginMarketplacePort.PluginUpdateResult result = services.plugins()
                            .updatePlugin(item.pluginId(), item.scope());
                        synchronized (this) {
                            isProcessing = false;
                        }
                        host.finish(result.updated()
                            ? "✓ Updated " + item.name() + ". Run /reload-plugins to apply."
                            : item.name() + " is already at the latest version ("
                                + result.version() + ").");
                    }
                    default -> {
                        synchronized (this) {
                            isProcessing = false;
                        }
                    }
                }
            } catch (Exception e) {
                synchronized (this) {
                    isProcessing = false;
                    processError = "Failed to " + operation + ": " + e.getMessage();
                }
            }
            host.refresh();
        });
    }

    private void performUninstall(boolean deleteDataDirectory) {
        InstalledItem item = selectedItem;
        if (item == null) return;
        isProcessing = true;
        processError = null;
        services.background().execute(() -> {
            try {
                PluginMarketplacePort.PluginUninstallResult result = services.plugins()
                    .uninstall(item.pluginId(), item.scope(), deleteDataDirectory);
                synchronized (this) {
                    isProcessing = false;
                }
                if (!result.removed()) {
                    synchronized (this) {
                        processError = "Plugin is not installed in " + item.scope().wire()
                            + " scope.";
                    }
                    host.refresh();
                    return;
                }
                host.finish("✓ Uninstalled " + item.name()
                    + (deleteDataDirectory ? "" : " · data preserved")
                    + ". Run /reload-plugins to apply.");
            } catch (Exception e) {
                synchronized (this) {
                    isProcessing = false;
                    processError = "Failed to uninstall: " + e.getMessage();
                }
                host.refresh();
            }
        });
    }

    private void openConfigureOptions() {
        InstalledItem item = selectedItem;
        if (item == null || isProcessing) return;
        isProcessing = true;
        processError = null;
        services.background().execute(() -> {
            try {
                Map<String, Object> saved = Map.copyOf(
                    services.plugins().loadOptions(item.pluginId()));
                host.postToGui(() -> {
                    isProcessing = false;
                    if (selectedItem != item) {
                        host.refresh();
                        return;
                    }
        openOptionsFlow(item, item.userConfig(), saved, false);
                    host.refresh();
                });
            } catch (RuntimeException failure) {
                host.postToGui(() -> {
                    isProcessing = false;
                    if (selectedItem != item) {
                        host.refresh();
                        return;
    }
                    processError = "Failed to load configuration: " + failure.getMessage();
                    host.refresh();
                });
        }
        });
    }

    private void openMcpbConfiguration() {
        InstalledItem item = selectedItem;
        if (item == null || item.installPath() == null) return;
        isProcessing = true;
        processError = null;
        services.background().execute(() -> {
            try {
                PluginMarketplacePort.McpbConfiguration configuration = services.plugins()
                    .loadMcpbConfiguration(item.pluginId(), item.installPath())
                    .orElseThrow(() -> new IllegalStateException("No MCPB file found in plugin"));
                PluginOptionsFlowView view = new PluginOptionsFlowView(
                    "Configure " + configuration.serverName(), "Plugin: " + item.name(),
                    configuration.schema(), configuration.existingValues(),
                    new PluginOptionsFlowView.Listener() {
                        @Override
                        public void onSave(Map<String, Object> values) {
                            PluginOptionsFlowView current = optionsView;
                            current.setSaving(true);
                            host.refresh();
                            Map<String, Object> snapshot = Map.copyOf(values);
                            services.background().execute(() -> {
                            try {
                                services.plugins().saveMcpbConfiguration(item.pluginId(),
                                        item.installPath(), configuration, snapshot);
                                    host.postToGui(() -> host.finish(
                                        "Configuration saved. Run /reload-plugins for changes to take effect."));
                            } catch (Exception e) {
                                    host.postToGui(() -> {
                                        current.setError("Failed to save configuration: " + e.getMessage());
                                        host.refresh();
                                    });
                            }
                            });
                        }

                        @Override
                        public void onCancel() {
                            synchronized (PluginInstalledTab.this) {
                                optionsView = null;
                                mode = Mode.DETAILS;
                            }
                            host.refresh();
                        }
                    });
                synchronized (this) {
                    isProcessing = false;
                    optionsView = view;
                    mode = Mode.OPTIONS;
                }
            } catch (Exception e) {
                synchronized (this) {
                    isProcessing = false;
                    processError = "Failed to load configuration: " + e.getMessage();
                }
            }
            host.refresh();
        });
    }

    private void openOptionsFlow(InstalledItem item, LinkedHashMap<String, ConfigOption> schema,
                                 Map<String, Object> saved, boolean fromEnable) {
        optionsView = new PluginOptionsFlowView(
            "Configure " + item.name(), "Plugin options", schema, saved,
            new PluginOptionsFlowView.Listener() {
                @Override
                public void onSave(Map<String, Object> values) {
                    PluginOptionsFlowView current = optionsView;
                    current.setSaving(true);
                    host.refresh();
                    Map<String, Object> snapshot = Map.copyOf(values);
                    services.background().execute(() -> {
                    try {
                            services.plugins().saveOptions(item.pluginId(), snapshot, schema);
                            host.postToGui(() -> host.finish(fromEnable
                            ? "✓ Enabled and configured " + item.name()
                                + ". Run /reload-plugins to apply."
                                : "Configuration saved. Run /reload-plugins for changes to take effect."));
                    } catch (Exception e) {
                            host.postToGui(() -> {
                                current.setError("Failed to save configuration: " + e.getMessage());
                                host.refresh();
                            });
                    }
                    });
                }

                @Override
                public void onCancel() {
                    if (fromEnable) {
                        host.finish("✓ Enabled " + item.name() + ". Run /reload-plugins to apply.");
                    } else {
                        synchronized (PluginInstalledTab.this) {
                            optionsView = null;
                            mode = Mode.DETAILS;
                        }
                        host.refresh();
                    }
                }
            });
        mode = Mode.OPTIONS;
    }


    private void openConfigurationSteps(InstalledItem item, List<ConfigurationStep> steps) {
        configurationSteps = List.copyOf(steps);
        configurationStepIndex = 0;
        openCurrentConfigurationStep(item);
        mode = Mode.OPTIONS;
    }

    private void openCurrentConfigurationStep(InstalledItem item) {
        ConfigurationStep step = configurationSteps.get(configurationStepIndex);
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
                            services.plugins().saveConfigurationStep(item.pluginId(), step, snapshot);
                            host.postToGui(() -> {
                        configurationStepIndex++;
                        if (configurationStepIndex < configurationSteps.size()) {
                            openCurrentConfigurationStep(item);
                            host.refresh();
                        } else {
                            host.finish("✓ Enabled and configured " + item.name()
                                + ". Run /reload-plugins to apply.");
                        }
                            });
                    } catch (Exception e) {
                            host.postToGui(() -> {
                                current.setError("Failed to save configuration: " + e.getMessage());
                                host.refresh();
                            });
                    }
                    });
                }

                @Override
                public void onCancel() {
                    host.finish("✓ Enabled " + item.name()
                        + ". Run /reload-plugins to apply.");
                }
            });
    }

    // ── render ────────────────────────────────────────────────────────────────

    synchronized List<StyledText.Line> buildLines() {
        return switch (mode) {
            case LOADING -> List.of(line("Loading installed plugins…", LanternaTheme.inputText()));
            case LIST -> buildListLines();
            case DETAILS -> buildDetailsLines();
            case FLAGGED_DETAILS -> buildFlaggedDetailsLines();
            case FAILED_DETAILS -> buildFailedDetailsLines();
            case MCP_DETAILS -> buildMcpDetailsLines();
            case MCP_TOOLS -> buildMcpToolsLines();
            case MCP_TOOL_DETAIL -> buildMcpToolDetailLines();
            case OPTIONS -> optionsView.buildLines();
            case CONFIRM_PROJECT_UNINSTALL -> buildProjectUninstallConfirmationLines();
            case CONFIRM_DATA_CLEANUP -> buildDataCleanupConfirmationLines();
        };
    }

    private List<StyledText.Line> buildProjectUninstallConfirmationLines() {
        InstalledItem item = selectedItem;
        List<StyledText.Line> lines = new ArrayList<>();
        lines.add(boldLine(item.name() + " is enabled in .claude/settings.json",
            LanternaTheme.toolWarning()));
        lines.add(line("(shared with your team)", LanternaTheme.toolWarning()));
        lines.add(blank());
        lines.add(line("Disable it just for you in .claude/settings.local.json?",
            LanternaTheme.inputText()));
        lines.add(line("This has the same effect as uninstalling, without affecting other contributors.",
            LanternaTheme.welcomeDim()));
        if (processError != null) {
            lines.add(blank());
            lines.add(line(processError, LanternaTheme.toolError()));
        }
        lines.add(blank());
        lines.add(line(isProcessing ? "Disabling…" : "y/Enter to disable · Esc to cancel",
            LanternaTheme.welcomeDim()));
        return lines;
    }

    private List<StyledText.Line> buildDataCleanupConfirmationLines() {
        InstalledItem item = selectedItem;
        PluginMarketplacePort.PluginDataDirectory data = pendingDataDirectory;
        List<StyledText.Line> lines = new ArrayList<>();
        lines.add(boldLine(item.name() + " has " + data.humanSize() + " of persistent data",
            LanternaTheme.inputText()));
        lines.add(blank());
        lines.add(line("Delete it along with the plugin?", LanternaTheme.inputText()));
        lines.add(line(data.path().toString(), LanternaTheme.welcomeDim()));
        if (processError != null) {
            lines.add(blank());
            lines.add(line(processError, LanternaTheme.toolError()));
        }
        lines.add(blank());
        lines.add(line(isProcessing ? "Uninstalling…"
            : "y to delete · n to keep · esc to cancel", LanternaTheme.welcomeDim()));
        return lines;
    }

    private List<StyledText.Line> buildListLines() {
        List<StyledText.Line> lines = new ArrayList<>();
        if (items.isEmpty() && flaggedItems.isEmpty() && failedItems.isEmpty()
                && mcpItems.isEmpty()) {
            lines.add(boldLine("Manage plugins", LanternaTheme.inputText()));
            lines.add(blank());
            lines.add(line("No plugins or MCP servers installed.", LanternaTheme.inputText()));
            lines.add(blank());
            lines.add(line("Esc to go back", LanternaTheme.welcomeDim()));
            return lines;
        }
        List<ListItem> filtered = filteredItems();
        lines.addAll(searchBoxLines());
        lines.add(blank());
        if (filtered.isEmpty() && !search.query().isEmpty()) {
            lines.add(line("No items match \"" + search.query() + "\"",
                LanternaTheme.welcomeDim()));
            lines.add(blank());
        }
        if (pagination.canScrollUp()) {
            lines.add(line(" ↑ more above", LanternaTheme.welcomeDim()));
        }
        String previousScope = null;
        for (int i = pagination.startIndex(); i < pagination.endIndex(); i++) {
            ListItem item = filtered.get(i);
            String scope = item instanceof FlaggedItem ? "flagged"
                : item instanceof InstalledItem installed ? installed.scope().wire()
                : item instanceof FailedItem failed ? failed.scope().wire()
                : ((McpItem) item).server().scope();
            if (!scope.equals(previousScope)) {
                if (previousScope != null) {
                    lines.add(blank());
                }
                Scope itemScope = item instanceof InstalledItem installed ? installed.scope()
                    : item instanceof FailedItem failed ? failed.scope() : null;
                lines.add(line("  " + (Strings.CS.equals("flagged", scope) ? "Flagged"
                    : itemScope != null ? scopeLabel(itemScope) : mcpScopeLabel(scope)),
                    Strings.CS.equals("flagged", scope) ? LanternaTheme.toolWarning()
                        : LanternaTheme.welcomeDim()));
                previousScope = scope;
            }
            boolean selected = i == pagination.selectedIndex() && !isSearchMode;
            lines.add(item instanceof InstalledItem installed
                ? buildCell(installed, selected)
                : item instanceof FlaggedItem flagged
                    ? buildFlaggedCell(flagged, selected)
                    : item instanceof FailedItem failed
                        ? buildFailedCell(failed, selected)
                        : buildMcpCell((McpItem) item, selected));
        }
        if (pagination.canScrollDown()) {
            lines.add(line(" ↓ more below", LanternaTheme.welcomeDim()));
        }
        if (processError != null) {
            lines.add(blank());
            lines.add(line(processError, LanternaTheme.toolError()));
        }
        lines.add(blank());
        lines.add(line("type to search · Space to toggle · Enter for details · Esc to go back",
            LanternaTheme.welcomeDim()));
        if (!pendingToggles.isEmpty()) {
            lines.add(line("Run /reload-plugins to apply changes", LanternaTheme.welcomeDim()));
        }
        return lines;
    }

    private List<ListItem> filteredItems() {
        List<ListItem> all = new ArrayList<>(flaggedItems.size() + failedItems.size()
            + items.size() + mcpItems.size());
        all.addAll(flaggedItems);
        List<ListItem> scoped = new ArrayList<>(failedItems.size() + items.size() + mcpItems.size());
        scoped.addAll(failedItems);
        scoped.addAll(items);
        scoped.addAll(mcpItems);
        scoped.sort(Comparator
            .comparingInt(PluginInstalledTab::listItemScopeOrder)
            .thenComparing(PluginInstalledTab::listItemSortName));
        all.addAll(scoped);
        if (search.query().isEmpty()) return List.copyOf(all);
        String query = search.query().toLowerCase(Locale.ROOT);
        return all.stream()
            .filter(item -> Strings.CI.contains(item.name(), query)
                || item.description() != null
                    && Strings.CI.contains(item.description(), query))
            .toList();
    }


    private List<StyledText.Line> searchBoxLines() {
        TextColor border = isSearchMode ? LanternaTheme.suggestion() : LanternaTheme.ghostText();
        return List.of(
            line(LanternaDraw.borderedSearchBoxTop(SEARCH_BOX_WIDTH), border),
            line(LanternaDraw.borderedSearchBoxContent(isSearchMode, search.query(),
                search.cursorOffset(), SEARCH_BOX_WIDTH), border),
            line(LanternaDraw.borderedSearchBoxBottom(SEARCH_BOX_WIDTH), border));
    }

    /** UnifiedInstalledCell's plugin arm: pointer + name + badge + marketplace + status. */
    private StyledText.Line buildCell(InstalledItem item, boolean selected) {
        String pending = pendingToggles.get(item.pluginId());
        String statusIcon;
        String statusText;
        if (pending != null) {
            statusIcon = "→";
            statusText = Strings.CS.equals("will-enable", pending) ? "will enable" : "will disable";
        } else if (!item.enabled()) {
            statusIcon = "◯";
            statusText = "disabled";
        } else {
            statusIcon = "✔";
            statusText = "enabled";
        }
        var nameColor = selected ? LanternaTheme.suggestion() : LanternaTheme.inputText();
        return line(
            seg(selected ? "❯ " : "  ", nameColor),
            seg(item.name(), nameColor),
            seg(" Plugin", LanternaTheme.welcomeDim()),
            seg(" · " + item.marketplace(), LanternaTheme.welcomeDim()),
            seg(" · ", LanternaTheme.welcomeDim()),
            seg(statusIcon, statusColor(pending, item.enabled())),
            seg(" " + statusText, LanternaTheme.welcomeDim()));
    }

    private StyledText.Line buildFlaggedCell(FlaggedItem item, boolean selected) {
        TextColor nameColor = selected ? LanternaTheme.suggestion() : LanternaTheme.toolWarning();
        return line(
            seg(selected ? "❯ " : "  ", nameColor),
            seg(item.name(), nameColor),
            seg(" Plugin", LanternaTheme.welcomeDim()),
            seg(" · " + item.marketplace(), LanternaTheme.welcomeDim()),
            seg(" · ✘ removed", LanternaTheme.toolError()));
    }

    private StyledText.Line buildFailedCell(FailedItem item, boolean selected) {
        TextColor nameColor = selected ? LanternaTheme.suggestion() : LanternaTheme.inputText();
        int count = item.errors().size();
        return line(
            seg(selected ? "❯ " : "  ", nameColor),
            seg(item.name(), nameColor),
            seg(" Plugin", LanternaTheme.welcomeDim()),
            seg(" · " + item.marketplace(), LanternaTheme.welcomeDim()),
            seg(" · ✘ failed to load · " + count + " "
                + (count == 1 ? "error" : "errors"), LanternaTheme.toolError()));
    }

    private StyledText.Line buildMcpCell(McpItem item, boolean selected) {
        McpManagementPort.Server server = item.server();
        String icon = switch (server.status()) {
            case CONNECTED -> "✓";
            case NEEDS_AUTH -> "△";
            case DISABLED, DISCONNECTED -> "○";
        };
        String status = switch (server.status()) {
            case CONNECTED -> "connected";
            case DISABLED -> "disabled";
            case NEEDS_AUTH -> "needs authentication";
            case DISCONNECTED -> "disconnected";
        };
        TextColor nameColor = selected ? LanternaTheme.suggestion() : LanternaTheme.inputText();
        return line(
            seg(selected ? "❯ " : "  ", nameColor),
            seg(server.pluginChild() ? "  ↳ " : "", LanternaTheme.welcomeDim()),
            seg(item.name(), nameColor),
            seg(" MCP", LanternaTheme.welcomeDim()),
            seg(" · " + icon + " " + status,
                server.status() == McpManagementPort.Status.CONNECTED
                    ? LanternaTheme.toolSuccess() : LanternaTheme.welcomeDim()));
    }

    private static int listItemScopeOrder(ListItem item) {
        if (item instanceof InstalledItem installed) return scopeOrder(installed.scope());
        if (item instanceof FailedItem failed) return scopeOrder(failed.scope());
        if (item instanceof McpItem mcp) return mcp.server().scopeOrder();
        return -1;
    }

    private static String listItemSortName(ListItem item) {
        if (item instanceof McpItem mcp) {
            if (mcp.server().pluginChild()) {
                String[] parts = mcp.server().name().split(":", 3);
                String owner = parts.length >= 2 ? parts[1] : mcp.name();
                return owner + "\u0001" + mcp.name();
            }
            return "\uffff" + mcp.name();
        }
        return item.name() + "\u0000";
    }

    private static String mcpScopeLabel(String scope) {
        return switch (scope) {
            case "project" -> "Project";
            case "local" -> "Local";
            case "user" -> "User";
            case "enterprise" -> "Enterprise";
            case "managed" -> "Managed";
            case "dynamic" -> "Built-in";
            default -> scope;
        };
    }

    private static TextColor statusColor(String pending, boolean enabled) {
        if (pending != null) {
            return LanternaTheme.suggestion();
        }
        return enabled ? LanternaTheme.toolSuccess() : LanternaTheme.ghostText();
    }

    private List<StyledText.Line> buildDetailsLines() {
        InstalledItem item = selectedItem;
        List<StyledText.Line> lines = new ArrayList<>();
        lines.add(boldLine(item.name() + " @ " + item.marketplace(), LanternaTheme.inputText()));
        lines.add(line(
            seg("Scope: ", LanternaTheme.welcomeDim()),
            seg(item.scope().wire(), LanternaTheme.inputText())));
        if (item.version() != null) {
            lines.add(line(
                seg("Version: ", LanternaTheme.welcomeDim()),
                seg(item.version(), LanternaTheme.inputText())));
        }
        if (item.description() != null) {
            lines.add(line(item.description(), LanternaTheme.inputText()));
        }
        if (item.author() != null) {
            lines.add(line(
                seg("Author: ", LanternaTheme.welcomeDim()),
                seg(item.author(), LanternaTheme.inputText())));
        }
        boolean enabled = effectiveEnabled(item);
        lines.add(line(
            seg("Status: ", LanternaTheme.welcomeDim()),
            seg(enabled ? "Enabled" : "Disabled",
                enabled ? LanternaTheme.toolSuccess() : LanternaTheme.toolWarning()),
            pendingUpdates.contains(itemKey(item))
                ? seg(" · Marked for update", LanternaTheme.suggestion())
                : seg("", LanternaTheme.welcomeDim())));
        List<PluginMarketplacePort.ErrorView> pluginErrors = pluginErrors(item);
        if (!pluginErrors.isEmpty()) {
            lines.add(blank());
            lines.add(boldLine(pluginErrors.size() + " "
                + (pluginErrors.size() == 1 ? "error" : "errors") + ":",
                LanternaTheme.toolError()));
            for (PluginMarketplacePort.ErrorView error : pluginErrors) {
                lines.add(line("  " + error.message(), LanternaTheme.toolError()));
                if (StringUtils.isNotBlank(error.guidance())) {
                    lines.add(line("  → " + error.guidance(), LanternaTheme.welcomeDim()));
                }
            }
        }
        lines.add(blank());
        List<MenuItem> menu = detailsMenu();
        for (int i = 0; i < menu.size(); i++) {
            MenuItem entry = menu.get(i);
            boolean selected = i == detailsMenuIndex;
            var color = Strings.CS.contains(entry.label(), "Uninstall")
                ? LanternaTheme.toolError() : LanternaTheme.inputText();
            lines.add(line(selected
                ? bold("❯ " + entry.label(), color)
                : seg("  " + entry.label(), color)));
        }
        if (isProcessing) {
            lines.add(blank());
            lines.add(line("Processing…", LanternaTheme.inputText()));
        }
        if (processError != null) {
            lines.add(blank());
            lines.add(line(processError, LanternaTheme.toolError()));
        }
        lines.add(blank());
        lines.add(line("↑ to navigate · Enter to select · Esc to back", LanternaTheme.welcomeDim()));
        return lines;
    }

    private List<StyledText.Line> buildFlaggedDetailsLines() {
        FlaggedItem item = selectedFlaggedItem;
        List<StyledText.Line> lines = new ArrayList<>();
        lines.add(boldLine(item.name() + " @ " + item.marketplace(), LanternaTheme.inputText()));
        lines.add(line(
            seg("Status: ", LanternaTheme.welcomeDim()),
            seg("Removed", LanternaTheme.toolError())));
        lines.add(blank());
        lines.add(line("Removed from marketplace · reason: delisted", LanternaTheme.toolError()));
        lines.add(line(item.description(), LanternaTheme.inputText()));
        lines.add(line("Flagged on " + flaggedDate(item.flaggedAt()), LanternaTheme.welcomeDim()));
        lines.add(blank());
        lines.add(line(bold("❯ Dismiss", LanternaTheme.suggestion())));
        lines.add(blank());
        lines.add(line("Enter to dismiss · Esc to back", LanternaTheme.welcomeDim()));
        return lines;
    }

    private List<StyledText.Line> buildFailedDetailsLines() {
        FailedItem item = selectedFailedItem;
        PluginMarketplacePort.ErrorView first = item.errors().isEmpty()
            ? null : item.errors().getFirst();
        List<StyledText.Line> lines = new ArrayList<>();
        lines.add(line(
            bold(item.name(), LanternaTheme.inputText()),
            seg(" @ " + item.marketplace(), LanternaTheme.welcomeDim()),
            seg(" (" + item.scope().wire() + ")", LanternaTheme.welcomeDim())));
        lines.add(line(first == null ? "Failed to load" : first.message(),
            LanternaTheme.toolError()));
        if (first != null && first.guidance() != null) {
            lines.add(line("→ " + first.guidance(), LanternaTheme.welcomeDim()));
        }
        lines.add(blank());
        if (item.scope() == Scope.MANAGED) {
            lines.add(line("Managed by your organization — contact your admin",
                LanternaTheme.welcomeDim()));
        } else {
            lines.add(line(bold("❯ Remove", LanternaTheme.suggestion())));
        }
        if (isProcessing) lines.add(line("Processing…", LanternaTheme.inputText()));
        if (processError != null) lines.add(line(processError, LanternaTheme.toolError()));
        lines.add(blank());
        lines.add(line(item.scope() == Scope.MANAGED
            ? "Esc to back" : "Enter to remove · Esc to back", LanternaTheme.welcomeDim()));
        return lines;
    }

    private List<StyledText.Line> buildMcpDetailsLines() {
        McpItem item = selectedMcpItem;
        McpManagementPort.Server server = item.server();
        List<StyledText.Line> lines = new ArrayList<>();
        lines.add(boldLine(item.name(), LanternaTheme.inputText()));
        lines.add(line(
            seg("Scope: ", LanternaTheme.welcomeDim()),
            seg(server.scope(), LanternaTheme.inputText())));
        lines.add(line(
            seg("Transport: ", LanternaTheme.welcomeDim()),
            seg(server.transport(), LanternaTheme.inputText())));
        lines.add(line(
            seg("Status: ", LanternaTheme.welcomeDim()),
            seg(server.status() == McpManagementPort.Status.NEEDS_AUTH
                    ? "needs authentication"
                    : server.status().name().toLowerCase(Locale.ROOT),
                server.status() == McpManagementPort.Status.CONNECTED
                    ? LanternaTheme.toolSuccess()
                    : server.status() == McpManagementPort.Status.NEEDS_AUTH
                        ? LanternaTheme.toolWarning() : LanternaTheme.welcomeDim())));
        if (isRemote(server)) {
            McpManagementPort.AuthStatus auth = server.authStatus();
            String authText = switch (auth) {
                case STATIC_HEADER -> "authenticated (static header)";
                case AUTHENTICATED -> "authenticated";
                case NOT_AUTHENTICATED -> "not authenticated";
                case NOT_APPLICABLE -> "not applicable";
            };
            lines.add(line(
                seg("Auth: ", LanternaTheme.welcomeDim()),
                seg(authText, auth == McpManagementPort.AuthStatus.NOT_AUTHENTICATED
                    ? LanternaTheme.toolError() : LanternaTheme.inputText())));
            if (server.url() != null) {
                lines.add(line(
                    seg("URL: ", LanternaTheme.welcomeDim()),
                    seg(server.url(), LanternaTheme.inputText())));
            }
        } else if (server.command() != null) {
            lines.add(line(
                seg("Command: ", LanternaTheme.welcomeDim()),
                seg(server.command(), LanternaTheme.inputText())));
            if (!server.args().isEmpty()) {
                lines.add(line(
                    seg("Args: ", LanternaTheme.welcomeDim()),
                    seg(String.join(" ", server.args()), LanternaTheme.inputText())));
            }
        }
        lines.add(line(
            seg("Config location: ", LanternaTheme.welcomeDim()),
            seg(describeMcpConfigLocation(server.scope()), LanternaTheme.inputText())));
        if (server.status() == McpManagementPort.Status.CONNECTED) {
            int count = services.mcp().tools(server.name()).size();
            lines.add(line(
                seg("Tools: ", LanternaTheme.welcomeDim()),
                seg(count + " " + (count == 1 ? "tool" : "tools"),
                    LanternaTheme.inputText())));
        }
        lines.add(blank());
        List<String> menu = mcpMenu();
        for (int i = 0; i < menu.size(); i++) {
            String label = menu.get(i);
            lines.add(line(i == mcpMenuIndex
                ? bold("❯ " + label, LanternaTheme.suggestion())
                : seg("  " + label, LanternaTheme.inputText())));
        }
        if (isProcessing) lines.add(line("Processing…", LanternaTheme.inputText()));
        if (processError != null) lines.add(line(processError, LanternaTheme.toolError()));
        lines.add(blank());
        lines.add(line("↑ to navigate · Enter to select · Esc to back",
            LanternaTheme.welcomeDim()));
        return lines;
    }

    private List<StyledText.Line> buildMcpToolsLines() {
        List<StyledText.Line> lines = new ArrayList<>();
        lines.add(boldLine("Tools from " + selectedMcpItem.name(), LanternaTheme.inputText()));
        lines.add(blank());
        if (mcpTools.isEmpty()) {
            lines.add(line("No tools available.", LanternaTheme.welcomeDim()));
        } else {
            for (int i = 0; i < mcpTools.size(); i++) {
                McpManagementPort.Tool tool = mcpTools.get(i);
                lines.add(line(i == mcpToolIndex
                    ? bold("❯ " + tool.name(), LanternaTheme.suggestion())
                    : seg("  " + tool.name(), LanternaTheme.inputText())));
                if (tool.description() != null) {
                    lines.add(line("  " + tool.description(), LanternaTheme.welcomeDim()));
                }
            }
        }
        lines.add(blank());
        lines.add(line("↑ to navigate · Enter to select · Esc to back",
            LanternaTheme.welcomeDim()));
        return lines;
    }

    private List<StyledText.Line> buildMcpToolDetailLines() {
        McpManagementPort.Tool tool = selectedMcpTool;
        List<StyledText.Line> lines = new ArrayList<>();
        if (tool == null) {
            lines.add(line("Tool unavailable", LanternaTheme.toolError()));
            lines.add(line("Esc to back", LanternaTheme.welcomeDim()));
            return lines;
        }
        lines.add(boldLine(tool.name(), LanternaTheme.inputText()));
        lines.add(line("Server: " + selectedMcpItem.name(), LanternaTheme.welcomeDim()));
        lines.add(line("Tool name: " + tool.name(), LanternaTheme.welcomeDim()));
        lines.add(line("Full name: mcp__" + selectedMcpItem.server().name() + "__"
            + tool.name(), LanternaTheme.welcomeDim()));
        if (StringUtils.isNotBlank(tool.description())) {
            lines.add(blank());
            lines.add(boldLine("Description:", LanternaTheme.inputText()));
            lines.add(line(tool.description(), LanternaTheme.inputText()));
        }
        JsonNode schema = tool.inputSchema();
        JsonNode properties = schema == null ? null : schema.get("properties");
        if (properties != null && properties.isObject() && !properties.isEmpty()) {
            Set<String> required = new LinkedHashSet<>();
            JsonNode requiredNode = schema.get("required");
            if (requiredNode != null && requiredNode.isArray()) {
                requiredNode.forEach(node -> required.add(node.asText()));
            }
            lines.add(blank());
            lines.add(boldLine("Parameters:", LanternaTheme.inputText()));
            properties.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                String suffix = required.contains(entry.getKey()) ? " (required)" : "";
                lines.add(line("• " + entry.getKey() + suffix + ": "
                    + value.path("type").asText("unknown"), LanternaTheme.inputText()));
                String description = value.path("description").asText("");
                if (!StringUtils.isBlank(description)) {
                    lines.add(line("  " + description, LanternaTheme.welcomeDim()));
                }
            });
        }
        lines.add(blank());
        lines.add(line("Esc to go back", LanternaTheme.welcomeDim()));
        return lines;
    }

    private static String describeMcpConfigLocation(String scope) {
        return switch (scope) {
            case "user" -> ClaudePaths.GLOBAL_JSON.toString();
            case "project" -> Path.of(System.getProperty("user.dir"))
                .resolve(".mcp.json").toString();
            case "local" -> ClaudePaths.GLOBAL_JSON + " [project: "
                + System.getProperty("user.dir") + "]";
            case "enterprise", "managed" -> ClaudePaths.managedRoot()
                .resolve("managed-mcp.json").toString();
            default -> "Dynamically configured";
        };
    }

    private static String flaggedDate(String value) {
        try {
            return DateTimeFormatter.ofLocalizedDate(
                    FormatStyle.SHORT)
                .withLocale(Locale.getDefault())
                .format(Instant.parse(value)
                    .atZone(ZoneId.systemDefault()).toLocalDate());
        } catch (Exception _) {
            return value;
        }
    }

    private List<PluginMarketplacePort.ErrorView> pluginErrors(InstalledItem item) {
        return services.plugins().errors().stream()
            .filter(error -> item.pluginId().equals(error.source())
                || item.name().equals(error.source())
                || error.source() != null && Strings.CS.startsWith(error.source(), item.name() + "@")
                || item.pluginId().equals(error.target()))
            .toList();
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

    List<InstalledItem> items() {
        return items;
    }

    InstalledItem selectedItem() {
        return selectedItem;
    }

    Map<String, String> pendingToggles() {
        return pendingToggles;
    }

    boolean isSearchMode() {
        return isSearchMode;
    }

    String searchQuery() {
        return search.query();
    }

    List<FlaggedItem> flaggedItems() {
        return flaggedItems;
    }

    List<FailedItem> failedItems() {
        return failedItems;
    }

    List<McpItem> mcpItems() {
        return mcpItems;
    }
}
