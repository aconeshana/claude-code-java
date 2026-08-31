package com.claudecode.ui.lanterna.plugin;

import org.apache.commons.lang3.Strings;

import com.claudecode.runtime.plugins.PluginMarketplacePort;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.claudecode.ui.lanterna.components.StyledText.blank;
import static com.claudecode.ui.lanterna.components.StyledText.bold;
import static com.claudecode.ui.lanterna.components.StyledText.boldLine;
import static com.claudecode.ui.lanterna.components.StyledText.line;
import static com.claudecode.core.text.StringUtils.plural;
import static com.claudecode.ui.lanterna.components.StyledText.seg;
import com.claudecode.ui.lanterna.components.StyledText;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * Marketplaces tab: the marketplace list (with "Add Marketplace" as the first row), pending
 * update/remove marks applied in bulk, a details view (browse / update / auto-update toggle /
 * remove), a remove confirmation, and the Add-Marketplace text input backed by {@link
 * PluginMarketplacePort}.
 */
final class PluginMarketplacesTab {

    enum Mode { LOADING, LIST, DETAILS, CONFIRM_REMOVE, ADD }

    static final class MarketState {
        final String name;
        final String source;
        final String lastUpdated;
        final Integer pluginCount;
        final List<String> installedPlugins;
        boolean pendingUpdate;
        boolean pendingRemove;
        boolean autoUpdate;

        MarketState(String name, String source, String lastUpdated, Integer pluginCount,
                    List<String> installedPlugins, boolean autoUpdate) {
            this.name = name;
            this.source = source;
            this.lastUpdated = lastUpdated;
            this.pluginCount = pluginCount;
            this.installedPlugins = installedPlugins;
            this.autoUpdate = autoUpdate;
        }
    }

    private record MenuOption(String label, String secondaryLabel, String value) {}

    private final PluginPanelServices services;
    private final PluginPanelHost host;

    private Mode mode = Mode.LOADING;
    private List<MarketState> states = new ArrayList<>();
    private int selectedIndex;
    private boolean isProcessing;
    private String processError;
    private String successMessage;
    private String progressMessage;
    private MarketState selectedMarketplace;
    private int detailsMenuIndex;

    private final PluginMarketplaceAddView addView;

    private String targetMarketplace;
    private String pendingAction;

    PluginMarketplacesTab(PluginPanelServices services, PluginPanelHost host) {
        this.services = services;
        this.host = host;
        this.addView = new PluginMarketplaceAddView(services, host, () -> {

            mode = Mode.LOADING;
            reload();
        });
    }

    /** Opens/reopens the tab; target+action come from {@code /plugin marketplace update|remove}. */
    void open(String targetMarketplace, String action) {
        this.targetMarketplace = targetMarketplace;
        this.pendingAction = action;
        this.mode = Mode.LOADING;
        this.selectedIndex = 0;
        this.processError = null;
        this.successMessage = null;
        this.selectedMarketplace = null;
        reload();
    }

    /** Opens straight into the Add view ({@code /plugin marketplace add [source]}). */
    void openAdd(String initialValue) {
        this.mode = Mode.ADD;
        addView.open(initialValue);
    }

    boolean allowsTabSwitch() {
        return (mode == Mode.LIST || mode == Mode.LOADING) && !isProcessing;
    }

    private void reload() {
        services.background().execute(() -> {
            try {
                List<MarketState> loaded = loadStates();
                synchronized (this) {
                    states = loaded;
                    mode = Mode.LIST;
                    autoAction();
                }
            } catch (Exception e) {
                synchronized (this) {
                    states = new ArrayList<>();
                    mode = Mode.LIST;
                    processError = e.getMessage() == null
                        ? "Failed to load marketplaces" : e.getMessage();
                }
            }
            host.refresh();
        });
    }

    private List<MarketState> loadStates() {
        Map<String, PluginMarketplacePort.Marketplace> known = services.plugins().marketplaces();
        List<PluginMarketplacePort.InstalledPlugin> installed = services.plugins().installedPlugins();
        List<MarketState> loaded = new ArrayList<>();
        for (Map.Entry<String, PluginMarketplacePort.Marketplace> entry : known.entrySet()) {
            String name = entry.getKey();
            Integer pluginCount = null;
            try {
                PluginMarketplacePort.MarketplaceManifest manifest = services.plugins().marketplace(name);
                pluginCount = manifest.plugins() == null ? 0 : manifest.plugins().size();
            } catch (Exception _) {
                // graceful degradation — count stays unknown
            }
            List<String> installedFrom = installed.stream()
                .map(PluginMarketplacePort.InstalledPlugin::pluginId)
                .filter(id -> Strings.CS.endsWith(id, "@" + name))
                .map(PluginMarketplacePort::pluginName)
                .toList();
            boolean autoUpdate = entry.getValue().autoUpdate() != null
                ? entry.getValue().autoUpdate()
                : Strings.CS.equals("claude-plugins-official", name);
            loaded.add(new MarketState(name, entry.getValue().source(),
                entry.getValue().lastUpdated(), pluginCount, installedFrom, autoUpdate));
        }
        loaded.sort((a, b) -> {
            if (Strings.CS.equals(a.name, "claude-plugin-directory")) {
                return -1;
            }
            if (Strings.CS.equals(b.name, "claude-plugin-directory")) {
                return 1;
            }
            return a.name.compareTo(b.name);
        });
        return loaded;
    }


    private void autoAction() {
        if (targetMarketplace == null) {
            return;
        }
        String target = targetMarketplace;
        String action = pendingAction;
        targetMarketplace = null;
        pendingAction = null;
        int index = -1;
        for (int i = 0; i < states.size(); i++) {
            if (states.get(i).name.equals(target)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            processError = "Marketplace not found: " + target;
            return;
        }
        selectedIndex = index + 1; // +1: "Add Marketplace" is row 0
        if (Strings.CS.equals("update", action)) {
            states.get(index).pendingUpdate = true;
            applyChanges(false);
        } else if (Strings.CS.equals("remove", action)) {
            states.get(index).pendingRemove = true;
            applyChanges(false);
        } else {
            selectedMarketplace = states.get(index);
            detailsMenuIndex = 0;
            mode = Mode.DETAILS;
        }
    }

    // ── keys ──────────────────────────────────────────────────────────────────

    synchronized void handleKey(KeyStroke key) {
        if (isProcessing) {
            return;
        }
        switch (mode) {
            case LOADING -> {
                if (key.getKeyType() == KeyType.ESCAPE) {
                    host.closePanel();
                }
            }
            case LIST -> handleListKey(key);
            case DETAILS -> handleDetailsKey(key);
            case CONFIRM_REMOVE -> handleConfirmRemoveKey(key);
            case ADD -> addView.handleKey(key);
        }
    }

    private boolean hasPendingChanges() {
        return states.stream().anyMatch(s -> s.pendingUpdate || s.pendingRemove);
    }

    private void handleListKey(KeyStroke key) {
        KeyType t = key.getKeyType();
        int totalRows = states.size() + 1;
        if (t == KeyType.ESCAPE) {
            if (hasPendingChanges()) {
                states.forEach(s -> {
                    s.pendingUpdate = false;
                    s.pendingRemove = false;
                });
                selectedIndex = 0;
            } else {
                host.closePanel();
            }
            return;
        }
        if (isUp(key)) {
            selectedIndex = Math.max(0, selectedIndex - 1);
            return;
        }
        if (isDown(key)) {
            selectedIndex = Math.min(totalRows - 1, selectedIndex + 1);
            return;
        }
        if (t == KeyType.ENTER) {
            if (selectedIndex == 0) {
                openAdd(null);
            } else if (hasPendingChanges()) {
                applyChanges(false);
            } else {
                selectedMarketplace = states.get(selectedIndex - 1);
                detailsMenuIndex = 0;
                mode = Mode.DETAILS;
            }
            return;
        }
        if (t == KeyType.CHARACTER && key.getCharacter() != null && selectedIndex > 0) {
            char c = Character.toLowerCase(key.getCharacter());
            MarketState state = states.get(selectedIndex - 1);
            if (c == 'u') {
                boolean turningOn = !state.pendingUpdate;
                state.pendingUpdate = turningOn;
                if (turningOn) {
                    state.pendingRemove = false;
                }
            } else if (c == 'r') {
                selectedMarketplace = state;
                mode = Mode.CONFIRM_REMOVE;
            }
        }
    }

    private void handleDetailsKey(KeyStroke key) {
        KeyType t = key.getKeyType();
        if (t == KeyType.ESCAPE) {
            mode = Mode.LIST;
            detailsMenuIndex = 0;
            successMessage = null;
            processError = null;
            return;
        }
        List<MenuOption> menu = detailsMenuOptions(selectedMarketplace);
        if (isUp(key)) {
            detailsMenuIndex = Math.max(0, detailsMenuIndex - 1);
            return;
        }
        if (isDown(key)) {
            detailsMenuIndex = Math.min(menu.size() - 1, detailsMenuIndex + 1);
            return;
        }
        if (t == KeyType.ENTER) {
            switch (menu.get(detailsMenuIndex).value()) {
                case "browse" -> host.switchToDiscover(selectedMarketplace.name, null);
                case "update" -> {
                    selectedMarketplace.pendingUpdate = true;
                    applyChanges(true);
                }
                case "toggle-auto-update" -> toggleAutoUpdate(selectedMarketplace);
                case "remove" -> mode = Mode.CONFIRM_REMOVE;
                default -> { }
            }
        }
    }

    private void handleConfirmRemoveKey(KeyStroke key) {
        if (key.getKeyType() == KeyType.ESCAPE) {
            mode = Mode.LIST;
            return;
        }
        Character c = key.getCharacter();
        if (key.getKeyType() == KeyType.CHARACTER && c != null) {
            char lower = Character.toLowerCase(c);
            if (lower == 'y') {
                selectedMarketplace.pendingRemove = true;
                applyChanges(false);
            } else if (lower == 'n') {
                selectedMarketplace = null;
                mode = Mode.LIST;
            }
        }
    }

    // ── operations ────────────────────────────────────────────────────────────


    private void applyChanges(boolean stayInDetails) {
        isProcessing = true;
        processError = null;
        successMessage = null;
        progressMessage = null;
        services.background().execute(() -> {
            int updated = 0;
            int removed = 0;
            try {
                for (MarketState state : List.copyOf(states)) {
                    if (state.pendingRemove) {
                        services.plugins().removeMarketplace(state.name);
                        removed++;
                        continue;
                    }
                    if (state.pendingUpdate) {
                        services.plugins().updateMarketplace(state.name, message -> {
                            synchronized (this) {
                                progressMessage = message;
                            }
                            host.refresh();
                        });
                        updated++;
                    }
                }
                List<MarketState> reloaded = loadStates();
                List<String> actions = new ArrayList<>();
                if (updated > 0) {
                    actions.add("Updated " + updated + " " + plural(updated, "marketplace"));
                }
                if (removed > 0) {
                    actions.add("Removed " + removed + " " + plural(removed, "marketplace"));
                }
                synchronized (this) {
                    states = reloaded;
                    isProcessing = false;
                    progressMessage = null;
                    if (stayInDetails && selectedMarketplace != null) {
                        String name = selectedMarketplace.name;
                        selectedMarketplace = reloaded.stream()
                            .filter(s -> s.name.equals(name)).findFirst().orElse(null);
                        if (selectedMarketplace == null) {
                            mode = Mode.LIST;
                        }
                    }
                }
                if (actions.isEmpty()) {
                    if (!stayInDetails) {
                        host.closePanel();
                    }
                } else if (stayInDetails) {
                    synchronized (this) {
                        successMessage = "✓ " + String.join(", ", actions);
                    }
                } else {
                    host.finish("✓ " + String.join(", ", actions));
                }
            } catch (Exception e) {
                synchronized (this) {
                    isProcessing = false;
                    progressMessage = null;
                    processError = e.getMessage();
                }
            }
            host.refresh();
        });
    }

    private void toggleAutoUpdate(MarketState state) {
        boolean newValue = !state.autoUpdate;
        services.background().execute(() -> {
            try {
                services.plugins().setMarketplaceAutoUpdate(state.name, newValue);
                synchronized (this) {
                    state.autoUpdate = newValue;
                }
            } catch (Exception e) {
                synchronized (this) {
                    processError = e.getMessage() == null
                        ? "Failed to update setting" : e.getMessage();
                }
            }
            host.refresh();
        });
    }


    private List<MenuOption> detailsMenuOptions(MarketState marketplace) {
        if (marketplace == null) {
            return List.of();
        }
        List<MenuOption> options = new ArrayList<>();
        options.add(new MenuOption(
            "Browse plugins (" + (marketplace.pluginCount == null ? 0 : marketplace.pluginCount) + ")",
            null, "browse"));
        options.add(new MenuOption("Update marketplace",
            marketplace.lastUpdated != null
                ? "(last updated " + formatDate(marketplace.lastUpdated) + ")" : null,
            "update"));
        options.add(new MenuOption(
            marketplace.autoUpdate ? "Disable auto-update" : "Enable auto-update",
            null, "toggle-auto-update"));
        options.add(new MenuOption("Remove marketplace", null, "remove"));
        return options;
    }

    private static String formatDate(String isoInstant) {
        try {
            return DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
                .withZone(ZoneId.systemDefault())
                .format(Instant.parse(isoInstant));
        } catch (Exception _) {
            return isoInstant;
        }
    }

    // ── render ────────────────────────────────────────────────────────────────

    synchronized List<StyledText.Line> buildLines() {
        return switch (mode) {
            case LOADING -> List.of(line("Loading marketplaces…", LanternaTheme.inputText()));
            case LIST -> buildListLines();
            case DETAILS -> buildDetailsLines();
            case CONFIRM_REMOVE -> buildConfirmRemoveLines();
            case ADD -> addView.buildLines();
        };
    }

    private List<StyledText.Line> buildListLines() {
        List<StyledText.Line> lines = new ArrayList<>();
        lines.add(boldLine("Manage marketplaces", LanternaTheme.inputText()));
        lines.add(blank());
        boolean addSelected = selectedIndex == 0;
        lines.add(line(
            seg(addSelected ? "❯ + " : "  + ",
                addSelected ? LanternaTheme.suggestion() : LanternaTheme.inputText()),
            bold("Add Marketplace",
                addSelected ? LanternaTheme.suggestion() : LanternaTheme.inputText())));
        lines.add(blank());
        for (int i = 0; i < states.size(); i++) {
            MarketState state = states.get(i);
            boolean selected = selectedIndex == i + 1;
            List<String> indicators = new ArrayList<>();
            if (state.pendingUpdate) {
                indicators.add("UPDATE");
            }
            if (state.pendingRemove) {
                indicators.add("REMOVE");
            }
            List<StyledText.Seg> segs = new ArrayList<>();
            segs.add(seg(selected ? "❯ " : "  ",
                selected ? LanternaTheme.suggestion() : LanternaTheme.inputText()));
            segs.add(seg(state.pendingRemove ? "✖ " : "• ",
                selected ? LanternaTheme.suggestion() : LanternaTheme.inputText()));
            if (Strings.CS.equals("claude-plugins-official", state.name)) {
                segs.add(seg("✻ ", LanternaTheme.claude()));
            }
            segs.add(bold(state.name,
                state.pendingRemove ? LanternaTheme.ghostText() : LanternaTheme.inputText()));
            if (Strings.CS.equals("claude-plugins-official", state.name)) {
                segs.add(seg(" ✻", LanternaTheme.claude()));
            }
            if (!indicators.isEmpty()) {
                segs.add(seg(" [" + String.join(", ", indicators) + "]",
                    LanternaTheme.toolWarning()));
            }
            lines.add(new StyledText.Line(segs));
            lines.add(line("    " + state.source, LanternaTheme.welcomeDim()));
            StringBuilder detail = new StringBuilder();
            if (state.pluginCount != null) {
                detail.append(state.pluginCount).append(" available");
            }
            if (!state.installedPlugins.isEmpty()) {
                if (!detail.isEmpty()) {
                    detail.append(" • ");
                }
                detail.append(state.installedPlugins.size()).append(" installed");
            }
            if (state.lastUpdated != null) {
                if (!detail.isEmpty()) {
                    detail.append(" • ");
                }
                detail.append("Updated ").append(formatDate(state.lastUpdated));
            }
            if (!detail.isEmpty()) {
                lines.add(line("    " + detail, LanternaTheme.welcomeDim()));
            }
            lines.add(blank());
        }
        if (hasPendingChanges()) {
            int updateCount = (int) states.stream().filter(s -> s.pendingUpdate).count();
            int removeCount = (int) states.stream().filter(s -> s.pendingRemove).count();
            lines.add(line(
                bold("Pending changes:", LanternaTheme.inputText()),
                seg(" Enter to apply", LanternaTheme.welcomeDim())));
            if (updateCount > 0) {
                lines.add(line("• Update " + updateCount + " "
                    + plural(updateCount, "marketplace"), LanternaTheme.inputText()));
            }
            if (removeCount > 0) {
                lines.add(line("• Remove " + removeCount + " "
                    + plural(removeCount, "marketplace"), LanternaTheme.toolWarning()));
            }
            lines.add(blank());
        }
        if (isProcessing) {
            lines.add(line("Processing changes…", LanternaTheme.claude()));
        }
        if (processError != null) {
            lines.add(line(processError, LanternaTheme.toolError()));
        }
        lines.add(line(hasPendingChanges()
                ? "Enter to apply changes · Esc to cancel"
                : "Enter to select · u to update · r to remove · Esc to go back",
            LanternaTheme.welcomeDim()));
        return lines;
    }

    private List<StyledText.Line> buildDetailsLines() {
        MarketState state = selectedMarketplace;
        List<StyledText.Line> lines = new ArrayList<>();
        lines.add(boldLine(state.name, LanternaTheme.inputText()));
        lines.add(line(state.source, LanternaTheme.welcomeDim()));
        lines.add(blank());
        int count = state.pluginCount == null ? 0 : state.pluginCount;
        lines.add(line(count + " available " + plural(count, "plugin"), LanternaTheme.inputText()));
        if (!state.installedPlugins.isEmpty()) {
            lines.add(blank());
            lines.add(boldLine("Installed plugins (" + state.installedPlugins.size() + "):",
                LanternaTheme.inputText()));
            for (String name : state.installedPlugins) {
                lines.add(line(" • " + name, LanternaTheme.inputText()));
            }
        }
        boolean isUpdating = state.pendingUpdate || isProcessing;
        if (isUpdating) {
            lines.add(blank());
            lines.add(line("Updating marketplace…", LanternaTheme.claude()));
            if (progressMessage != null) {
                lines.add(line(progressMessage, LanternaTheme.welcomeDim()));
            }
        } else {
            if (successMessage != null) {
                lines.add(blank());
                lines.add(line(successMessage, LanternaTheme.claude()));
            }
            if (processError != null) {
                lines.add(blank());
                lines.add(line(processError, LanternaTheme.toolError()));
            }
            lines.add(blank());
            List<MenuOption> menu = detailsMenuOptions(state);
            for (int i = 0; i < menu.size(); i++) {
                MenuOption option = menu.get(i);
                boolean selected = i == detailsMenuIndex;
                List<StyledText.Seg> segs = new ArrayList<>();
                segs.add(seg((selected ? "❯ " : "  ") + option.label(),
                    selected ? LanternaTheme.suggestion() : LanternaTheme.inputText()));
                if (option.secondaryLabel() != null) {
                    segs.add(seg(" " + option.secondaryLabel(), LanternaTheme.welcomeDim()));
                }
                lines.add(new StyledText.Line(segs));
            }
            if (state.autoUpdate) {
                lines.add(blank());
                lines.add(line("Auto-update enabled. Claude Code will automatically update this "
                    + "marketplace and its installed plugins.", LanternaTheme.welcomeDim()));
            }
        }
        lines.add(blank());
        lines.add(line(isUpdating ? "Please wait…" : "Enter to select · Esc to go back",
            LanternaTheme.welcomeDim()));
        return lines;
    }

    private List<StyledText.Line> buildConfirmRemoveLines() {
        MarketState state = selectedMarketplace;
        List<StyledText.Line> lines = new ArrayList<>();
        lines.add(boldLine("Remove marketplace " + state.name + "?", LanternaTheme.toolWarning()));
        int count = state.installedPlugins.size();
        if (count > 0) {
            lines.add(blank());
            lines.add(line("This will also uninstall " + count + " " + plural(count, "plugin")
                + " from this marketplace:", LanternaTheme.toolWarning()));
            lines.add(blank());
            for (String name : state.installedPlugins) {
                lines.add(line("  • " + name, LanternaTheme.welcomeDim()));
            }
        }
        lines.add(blank());
        lines.add(line(
            seg("Press ", LanternaTheme.inputText()),
            bold("y", LanternaTheme.inputText()),
            seg(" to confirm or ", LanternaTheme.inputText()),
            bold("n", LanternaTheme.inputText()),
            seg(" to cancel", LanternaTheme.inputText())));
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

    List<MarketState> states() {
        return states;
    }

    String addError() {
        return addView.error();
    }

    String addInput() {
        return addView.input();
    }

    String processError() {
        return processError;
    }

    MarketState selectedMarketplace() {
        return selectedMarketplace;
    }
}
