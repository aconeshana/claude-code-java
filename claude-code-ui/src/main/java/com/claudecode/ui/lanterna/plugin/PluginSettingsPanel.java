package com.claudecode.ui.lanterna.plugin;

import org.apache.commons.lang3.Strings;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.ui.lanterna.input.ContextKeybindingDispatcher;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.components.StyledText;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Interactable;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.features.settings.PermissionsPanel;
import com.claudecode.ui.lanterna.features.settings.SettingsTabContainer;
import com.claudecode.ui.lanterna.input.InputPanel;

/**
 * Inline {@code /plugin} panel — sits above {@link InputPanel} in the SmartLayout stack, occupying
 * zero rows when idle.
 */
public final class PluginSettingsPanel extends Panel implements InlineOverlay, PluginPanelHost {

    enum Tab { DISCOVER, INSTALLED, MARKETPLACES, ERRORS }


    static final List<String> HELP_LINES = List.of(
        "Plugin Command Usage:",
        "",
        "Installation:",
        " /plugin install - Browse and install plugins",
        " /plugin install <marketplace> - Install from specific marketplace",
        " /plugin install <plugin> - Install specific plugin",
        " /plugin install <plugin>@<market> - Install plugin from marketplace",
        "",
        "Management:",
        " /plugin manage - Manage installed plugins",
        " /plugin enable <plugin> - Enable a plugin",
        " /plugin disable <plugin> - Disable a plugin",
        " /plugin uninstall <plugin> - Uninstall a plugin",
        "",
        "Marketplaces:",
        " /plugin marketplace - Marketplace management menu",
        " /plugin marketplace add - Add a marketplace",
        " /plugin marketplace add <path/url> - Add marketplace directly",
        " /plugin marketplace update - Update marketplaces",
        " /plugin marketplace update <name> - Update specific marketplace",
        " /plugin marketplace remove - Remove a marketplace",
        " /plugin marketplace remove <name> - Remove specific marketplace",
        " /plugin marketplace list - List all marketplaces",
        "",
        "Validation:",
        " /plugin validate <path> - Validate a manifest file or directory",
        "",
        "Other:",
        " /plugin - Main plugin menu",
        " /plugin help - Show this help",
        " /plugins - Alias for /plugin");

    private static final int LEFT_PAD = 2;

    private final PluginPanelServices services;

    private boolean active;
    private Tab selectedTab = Tab.DISCOVER;
    private boolean validateVisible;
    private boolean marketplaceListing;

    private final PluginDiscoverTab discoverTab;
    private final PluginInstalledTab installedTab;
    private final PluginMarketplacesTab marketplacesTab;
    private final PluginErrorsTab errorsTab;
    private final PluginValidateView validateView;

    private BiConsumer<String, TextColor> changeRecorder;
    private Runnable onClose;
    private final ContextKeybindingDispatcher keybindings =
        new ContextKeybindingDispatcher();

    public PluginSettingsPanel(PluginPanelServices services) {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        this.services = services;

        HeaderArea header = new HeaderArea();
        header.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(header);

        ContentArea content = new ContentArea();
        content.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(content);

        discoverTab = new PluginDiscoverTab(services, this);
        installedTab = new PluginInstalledTab(services, this);
        marketplacesTab = new PluginMarketplacesTab(services, this);
        errorsTab = new PluginErrorsTab(services, this);
        validateView = new PluginValidateView(services, this::record, this::closePanel,
            this::refresh);
    }

    public void setKeybindingsStore(UserKeybindingsStore store) {
        keybindings.setStore(store);
    }

    /**
     * Activates the panel on the view {@code route} selects.
     */
    public synchronized void show(PluginRoute route,
                           BiConsumer<String, TextColor> changeRecorder,
                           Runnable onClose) {
        this.changeRecorder = changeRecorder;
        this.onClose = onClose;
        this.validateVisible = false;
        this.marketplaceListing = false;
        PluginRoute effective = route == null ? PluginRoute.menu() : route;

        if (effective.type() == PluginRoute.Type.HELP) {

            record(String.join("\n", HELP_LINES), LanternaTheme.inputText());
            Runnable cb = this.onClose;
            this.onClose = null;
            if (cb != null) {
                cb.run();
            }
            return;
        }

        this.active = true;
        switch (effective.type()) {
            case MENU, INSTALL -> {
                selectedTab = Tab.DISCOVER;
                discoverTab.open(effective.marketplace(), effective.plugin());
            }
            case MANAGE -> {
                selectedTab = Tab.INSTALLED;
                installedTab.open(null, null, null);
            }
            case UNINSTALL, ENABLE, DISABLE -> {
                selectedTab = Tab.INSTALLED;
                installedTab.open(effective.plugin(), null,
                    effective.type().name().toLowerCase(Locale.ROOT));
            }
            case VALIDATE -> {
                validateVisible = true;
                validateView.open(effective.path());
            }
            case MARKETPLACE -> openMarketplaceRoute(effective);
            default -> {
                selectedTab = Tab.DISCOVER;
                discoverTab.open(null, null);
            }
        }
        invalidate();
    }

    private void openMarketplaceRoute(PluginRoute route) {
        String action = route.action();
        if (Strings.CS.equals("list", action)) {

            marketplaceListing = true;
            services.background().execute(() -> {
                String message;
                try {
                    var names = services.plugins().marketplaces().keySet();
                    message = names.isEmpty()
                        ? "No marketplaces configured"
                        : "Configured marketplaces:\n" + names.stream()
                            .map(n -> "  • " + n)
                            .reduce((a, b) -> a + "\n" + b).orElse("");
                } catch (Exception e) {
                    message = "Error loading marketplaces: " + e.getMessage();
                }
                finish(message);
            });
            return;
        }
        selectedTab = Tab.MARKETPLACES;
        if (Strings.CS.equals("add", action)) {
            marketplacesTab.openAdd(route.marketplace());
        } else if (Strings.CS.equals("remove", action) || Strings.CS.equals("update", action)) {
            marketplacesTab.open(route.marketplace(), action);
        } else {

            // (marketplace-menu → menu); the Marketplaces tab is strictly more useful.
            marketplacesTab.open(null, null);
        }
    }

    // ── PluginPanelHost ───────────────────────────────────────────────────────

    @Override
    public void record(String line, TextColor color) {
        BiConsumer<String, TextColor> recorder = changeRecorder;
        if (recorder != null) {
            recorder.accept(line, color);
        }
    }

    @Override
    public void finish(String resultMessage) {
        if (resultMessage != null) {
            record(resultMessage, LanternaTheme.inputText());
        }
        closePanel();
    }

    @Override
    public synchronized void closePanel() {
        active = false;
        marketplaceListing = false;
        validateVisible = false;
        invalidate();
        Runnable cb = onClose;
        onClose = null;
        if (cb != null) {
            cb.run();
        }
    }

    @Override
    public synchronized void switchToDiscover(String targetMarketplace, String targetPlugin) {
        selectedTab = Tab.DISCOVER;
        discoverTab.open(targetMarketplace, targetPlugin);
        invalidate();
    }

    @Override
    public synchronized void switchToInstalled(String targetPlugin, String targetMarketplace,
                                               String action) {
        selectedTab = Tab.INSTALLED;
        installedTab.open(targetPlugin, targetMarketplace, action);
        invalidate();
    }

    @Override
    public synchronized void switchToMarketplaces(String targetMarketplace, String action) {
        selectedTab = Tab.MARKETPLACES;
        marketplacesTab.open(targetMarketplace, action);
        invalidate();
    }

    @Override
    public void refresh() {
        invalidate();
    }

    @Override
    public void postToGui(Runnable action) {
        var textGui = getTextGUI();
        if (textGui != null) textGui.getGUIThread().invokeLater(action);
        else action.run();
    }

    // ── InlineOverlay ─────────────────────────────────────────────────────────

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public synchronized void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!active) {
            return;
        }
        deliver.set(false);
        KeyType t = key.getKeyType();
        Character ch = key.getCharacter();

        if (t == KeyType.CHARACTER && key.isCtrlDown() && ch != null
                && (Character.toLowerCase(ch) == 'c' || Character.toLowerCase(ch) == 'd')) {
            closePanel();
            return;
        }
        if (marketplaceListing) {
            if (t == KeyType.ESCAPE) {
                closePanel();
            }
            return;
        }
        if (validateVisible) {
            validateView.handleKey(key);
            invalidate();
            return;
        }
        if (confirmationContextActive()) {
            ContextKeybindingDispatcher.Result resolved = keybindings.resolve("Confirmation", key);
            if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) return;
            if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String value)
                    && dispatchConfirmationAction(value)) {
                invalidate();
                return;
            }
        }
        if (pluginContextActive()) {
            ContextKeybindingDispatcher.Result resolved = keybindings.resolve("Plugin", key);
            if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) return;
            if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String value)) {
                dispatchContextAction(value);
                invalidate();
                return;
            }
        }
        if (selectContextActive()) {
            ContextKeybindingDispatcher.Result resolved = keybindings.resolve("Select", key);
            if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) return;
            if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String value)) {
                dispatchContextAction(value);
                invalidate();
                return;
            }
        }
        if ((t == KeyType.ARROW_LEFT || t == KeyType.ARROW_RIGHT) && allowsTabSwitch()) {
            switchTab(t == KeyType.ARROW_LEFT ? -1 : 1);
            return;
        }
        switch (selectedTab) {
            case DISCOVER -> discoverTab.handleKey(key);
            case INSTALLED -> installedTab.handleKey(key);
            case MARKETPLACES -> marketplacesTab.handleKey(key);
            case ERRORS -> errorsTab.handleKey(key);
        }
        invalidate();
    }

    private boolean pluginContextActive() {
        return switch (selectedTab) {
            case DISCOVER -> discoverTab.pluginContextActive();
            case INSTALLED -> installedTab.pluginContextActive();
            default -> false;
        };
    }

    private boolean confirmationContextActive() {
        return switch (selectedTab) {
            case DISCOVER -> discoverTab.confirmationContextActive();
            case INSTALLED -> installedTab.confirmationContextActive();
            default -> false;
        };
    }

    private boolean dispatchConfirmationAction(String action) {
        return switch (selectedTab) {
            case DISCOVER -> discoverTab.handleConfirmationAction(action);
            case INSTALLED -> installedTab.handleConfirmationAction(action);
            default -> false;
        };
    }

    private boolean selectContextActive() {
        return switch (selectedTab) {
            case DISCOVER -> discoverTab.selectContextActive();
            case INSTALLED -> installedTab.selectContextActive();
            default -> false;
        };
    }

    private void dispatchContextAction(String action) {
        switch (selectedTab) {
            case DISCOVER -> discoverTab.handleKeybindingAction(action);
            case INSTALLED -> installedTab.handleKeybindingAction(action);
            default -> { }
        }
    }

    private boolean allowsTabSwitch() {
        return switch (selectedTab) {
            case DISCOVER -> discoverTab.allowsTabSwitch();
            case INSTALLED -> installedTab.allowsTabSwitch();
            case MARKETPLACES -> marketplacesTab.allowsTabSwitch();
            case ERRORS -> errorsTab.allowsTabSwitch();
        };
    }


    private void switchTab(int delta) {
        Tab[] tabs = Tab.values();
        selectedTab = tabs[InlineOverlay.cycleIndex(selectedTab.ordinal(), delta, tabs.length)];
        switch (selectedTab) {
            case DISCOVER -> discoverTab.open(null, null);
            case INSTALLED -> installedTab.open(null, null, null);
            case MARKETPLACES -> marketplacesTab.open(null, null);
            case ERRORS -> errorsTab.open();
        }
        invalidate();
    }

    private List<StyledText.Line> contentLines() {
        if (marketplaceListing) {
            return List.of(StyledText.line("Loading marketplaces...", LanternaTheme.inputText()));
        }
        if (validateVisible) {
            return validateView.buildLines();
        }
        return switch (selectedTab) {
            case DISCOVER -> discoverTab.buildLines();
            case INSTALLED -> installedTab.buildLines();
            case MARKETPLACES -> marketplacesTab.buildLines();
            case ERRORS -> errorsTab.buildLines();
        };
    }

    private String tabLabel(Tab tab) {
        return switch (tab) {
            case DISCOVER -> "Discover";
            case INSTALLED -> "Installed";
            case MARKETPLACES -> "Marketplaces";
            case ERRORS -> {
                int count = errorCount();
                yield count > 0 ? "Errors (" + count + ")" : "Errors";
            }
        };
    }

    private int errorCount() {
        try {
            List<?> errors = services.plugins().errors();
            return errors == null ? 0 : errors.size();
        } catch (Exception _) {
            return 0;
        }
    }

    // ── sizing / focus ────────────────────────────────────────────────────────

    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        if (!active) {
            return new TerminalSize(0, 0);
        }
        return super.calculatePreferredSize();
    }

    @Override
    public Interactable nextFocus(Interactable fromThis) {
        return active ? super.nextFocus(fromThis) : null;
    }

    @Override
    public Interactable previousFocus(Interactable fromThis) {
        return active ? super.previousFocus(fromThis) : null;
    }

    // ── test accessors (package-private) ──────────────────────────────────────

    Tab selectedTab() {
        return selectedTab;
    }

    boolean validateVisible() {
        return validateVisible;
    }

    PluginDiscoverTab discoverTab() {
        return discoverTab;
    }

    PluginInstalledTab installedTab() {
        return installedTab;
    }

    PluginMarketplacesTab marketplacesTab() {
        return marketplacesTab;
    }

    PluginErrorsTab errorsTab() {
        return errorsTab;
    }

    PluginValidateView validateView() {
        return validateView;
    }

    List<String> contentPlainLines() {
        return StyledText.plain(contentLines());
    }

    String errorsTabTitle() {
        return tabLabel(Tab.ERRORS);
    }

    // ── header renderer ───────────────────────────────────────────────────────

    private final class HeaderArea extends AbstractComponent<HeaderArea> {
        @Override
        protected ComponentRenderer<HeaderArea> createDefaultRenderer() {
            return new HeaderRenderer();
        }
    }

    private final class HeaderRenderer implements ComponentRenderer<HeaderArea> {

        @Override
        public TerminalSize getPreferredSize(HeaderArea c) {
            if (!active || validateVisible || marketplaceListing) {
                return new TerminalSize(0, 0);
            }
            return new TerminalSize(60, 1);
        }

        @Override
        public void drawComponent(TextGUIGraphics g, HeaderArea c) {
            if (!active || validateVisible || marketplaceListing) {
                return;
            }
            g.fill(' ');
            int col = LEFT_PAD;
            for (Tab tab : Tab.values()) {
                boolean isCurrent = tab == selectedTab;
                String label = " " + tabLabel(tab) + " ";
                if (isCurrent) {
                    g.setBackgroundColor(LanternaTheme.claude());
                    g.setForegroundColor(LanternaTheme.inverseText());
                    g.enableModifiers(SGR.BOLD);
                } else {
                    g.setBackgroundColor(TextColor.ANSI.DEFAULT);
                    g.setForegroundColor(LanternaTheme.welcomeDim());
                }
                g.putString(col, 0, label);
                g.disableModifiers(SGR.BOLD);
                col += label.length() + 1;
            }
            g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        }
    }

    // ── content renderer ──────────────────────────────────────────────────────

    private final class ContentArea extends AbstractComponent<ContentArea> {
        @Override
        protected ComponentRenderer<ContentArea> createDefaultRenderer() {
            return new ContentRenderer();
        }
    }

    private final class ContentRenderer implements ComponentRenderer<ContentArea> {

        @Override
        public TerminalSize getPreferredSize(ContentArea c) {
            if (!active) {
                return new TerminalSize(0, 0);
            }
            return new TerminalSize(80, contentLines().size() + 1);
        }

        @Override
        public void drawComponent(TextGUIGraphics g, ContentArea c) {
            if (!active) {
                return;
            }
            g.fill(' ');
            int cols = g.getSize().getColumns();
            g.setForegroundColor(LanternaTheme.divider());
            g.putString(0, 0, "─".repeat(Math.max(0, cols)));
            List<StyledText.Line> lines = contentLines();
            for (int row = 0; row < lines.size(); row++) {
                int colOffset = LEFT_PAD;
                for (StyledText.Seg seg : lines.get(row).segs()) {
                    g.setForegroundColor(seg.color());
                    if (seg.bold()) {
                        g.enableModifiers(SGR.BOLD);
                    }
                    g.putString(colOffset, row + 1, seg.text());
                    if (seg.bold()) {
                        g.disableModifiers(SGR.BOLD);
                    }
                    colOffset += seg.text().length();
                }
            }
        }
    }
}
