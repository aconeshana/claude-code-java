package com.claudecode.ui.lanterna.features.settings;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.commands.StatusProperty;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.ui.lanterna.dialog.ModelPickerDialog;
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
import com.googlecode.lanterna.screen.Screen;

import java.util.List;
import java.util.Map;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import com.claudecode.runtime.outputstyle.OutputStyleCatalog;
import com.claudecode.ui.lanterna.input.ContextKeybindingDispatcher;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.status.StatusPane;
import com.claudecode.ui.lanterna.status.UsagePane;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import org.apache.commons.lang3.Strings;

/**
 * Shared tabbed panel behind {@code /config}, {@code /status}, and {@code /usage} — sits above
 * {@link InputPanel} in the SmartLayout stack, occupying zero rows when idle.
 */
public final class SettingsTabContainer extends Panel implements InlineOverlay {

    enum Tab { STATUS, CONFIG, USAGE }

    private static final int LEFT_PAD = 2;

    private boolean active;
    private Tab selectedTab = Tab.CONFIG;
    private boolean headerFocused;
    private TerminalSize renderedHeaderSize;
    private Tab renderedHeaderTab;
    private boolean renderedHeaderFocused;
    private boolean renderedHeaderCompact;
    private final boolean isWindows;
    private final Runnable completeRefresh;

    private final HeaderArea header = new HeaderArea();
    private final ConfigPanel configPanel;
    private final StatusPane statusPane = new StatusPane();
    private final UsagePane usagePane = new UsagePane();

    private Supplier<List<StatusProperty>> statusPropertiesSupplier;
    private Consumer<Map<String, String>> onResult;
    private Runnable statsTabRequest;
    private final ContextKeybindingDispatcher keybindings =
        new ContextKeybindingDispatcher();

    SettingsTabContainer(int terminalRows) {
        this(() -> terminalRows, OutputStyleCatalog.builtIns());
    }

    SettingsTabContainer(IntSupplier terminalRowsSupplier, OutputStyleCatalog outputStyles) {
        this(new ConfigPanel(
            () -> contentHeightForTerminalRows(terminalRowsSupplier.getAsInt()), outputStyles),
            isWindowsPlatform(), null);
    }

    SettingsTabContainer(int terminalRows, OutputStyleCatalog outputStyles, Path cwd) {
        this(new ConfigPanel(() -> contentHeightForTerminalRows(terminalRows), outputStyles, cwd),
            isWindowsPlatform(), null);
    }

    /** Test seam for the Windows complete-refresh workaround. */
    SettingsTabContainer(int terminalRows, boolean isWindows, Runnable completeRefresh) {
        this(new ConfigPanel(() -> contentHeightForTerminalRows(terminalRows),
            OutputStyleCatalog.builtIns()), isWindows, completeRefresh);
    }

    private SettingsTabContainer(ConfigPanel configPanel,
                                 boolean isWindows,
                                 Runnable completeRefresh) {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        this.isWindows = isWindows;
        this.completeRefresh = completeRefresh != null
            ? completeRefresh : this::requestCompleteRefresh;

        header.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(header);

        this.configPanel = configPanel;
        this.configPanel.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(this.configPanel);
        this.configPanel.setOnFocusHeaderRequest(() -> {
            headerFocused = true;
            invalidate();
            requestWindowsCompleteRefresh();
        });

        statusPane.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(statusPane);

        usagePane.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(usagePane);
    }

    @Override
    protected ComponentRenderer<Panel> createDefaultRenderer() {
        // HeaderArea plus the selected tab content cover the complete
        // container. The stock Panel clear otherwise writes every Settings
        // cell once before those children immediately overwrite it.
        Panel.DefaultPanelRenderer renderer = new Panel.DefaultPanelRenderer();
        renderer.setFillAreaBeforeDrawingComponents(false);
        return renderer;
    }


    static int contentHeightForTerminalRows(int terminalRows) {
        return Math.max(15, Math.min((int) Math.floor(terminalRows * 0.8), 30));
    }

    /**
     * Activates the container.
     */
    synchronized void show(Tab defaultTab,
                           Map<String, String> configValues,
                           boolean hasAssistantMessage,
                           Supplier<List<StatusProperty>> statusPropertiesSupplier,
                           Consumer<String> onThemePreview,
                           Consumer<Map<String, String>> onResult) {
        show(defaultTab, configValues, hasAssistantMessage, statusPropertiesSupplier,
            onThemePreview, (_, _) -> { }, onResult);
    }

    synchronized void show(Tab defaultTab,
                           Map<String, String> configValues,
                           boolean hasAssistantMessage,
                           Supplier<List<StatusProperty>> statusPropertiesSupplier,
                           Consumer<String> onThemePreview,
                           BiConsumer<String, String> onConfigChange,
                           Consumer<Map<String, String>> onResult) {
        this.selectedTab = defaultTab;

        this.headerFocused = (defaultTab != Tab.CONFIG);
        this.statusPropertiesSupplier = statusPropertiesSupplier;
        statusPane.setDiagnostics(List.of());
        this.onResult = onResult;
        this.active = true;
        this.renderedHeaderSize = null;
        header.invalidate();

        configPanel.show(configValues, hasAssistantMessage, onThemePreview,
            onConfigChange, this::onConfigPanelResult);
        syncTabVisibility();
        invalidate();
        requestWindowsCompleteRefresh();
    }

    private void onConfigPanelResult(Map<String, String> pending) {
// ConfigPanel already collapsed its own visibility flag via commit/cancel;
        // reflect that at the container level and forward the result unchanged.
        active = false;
        renderedHeaderSize = null;
        invalidate();
        Consumer<Map<String, String>> cb = onResult;
        onResult = null;
        if (cb != null) cb.accept(pending);
    }

    /** Closes the container from the tab header while preserving already-applied config edits. */
    private void closeWithoutChanges() {
        active = false;
        renderedHeaderSize = null;
        invalidate();
        Consumer<Map<String, String>> cb = onResult;
        onResult = null;
        if (cb != null) {
            Map<String, String> pending = configPanel.pendingSnapshot();
            cb.accept(pending.isEmpty() ? null : pending);
        }
    }

    @Override public boolean isActive() { return active; }

    void updateStatusDiagnostics(List<String> diagnostics) {
        statusPane.setDiagnostics(diagnostics);
        invalidate();
    }

    void setKeybindingsStore(UserKeybindingsStore store) {
        keybindings.setStore(store);
        configPanel.setKeybindingsStore(store);
    }

    void setGuiInvoker(Consumer<Runnable> guiInvoker) {
        configPanel.setGuiInvoker(guiInvoker);
    }

    void setStatsTabRequest(Runnable request) {
        statsTabRequest = request;
    }

    void setSyntaxHighlightingAccess(BooleanSupplier reader,
                                     Consumer<Boolean> writer) {
        configPanel.setSyntaxHighlightingAccess(reader, writer);
    }

    synchronized void updateStatusProperties(List<StatusProperty> properties) {
        List<StatusProperty> snapshot = properties != null ? List.copyOf(properties) : List.of();
        statusPropertiesSupplier = () -> snapshot;
        if (active && selectedTab == Tab.STATUS) {
            statusPane.show(snapshot);
            invalidate();
        }
    }

    void setModelAllowed(Predicate<String> modelAllowed) {
        configPanel.setModelAllowed(modelAllowed);
    }

    void setBuiltInModelFamiliesVisible(boolean visible) {
        configPanel.setBuiltInModelFamiliesVisible(visible);
    }

    void setModelPickerMetadata(String priorPersistedEffort,
                                List<CustomModelConfig> customModels) {
        configPanel.setModelPickerMetadata(priorPersistedEffort, customModels);
    }

    void setPreparedModelPicker(ModelPickerDialog.PreparedModelPicker prepared) {
        configPanel.setPreparedModelPicker(prepared);
    }

    void beginInputBatch() {
        configPanel.beginInputBatch();
    }

    void endInputBatch() {
        configPanel.endInputBatch();
    }

    @Override
    public synchronized void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!active) return;

        if (!headerFocused) {
// Only reachable when selectedTab == CONFIG — see switchTab/show.
            configPanel.handleKey(key, deliver);
            return;
        }

        KeyType t = key.getKeyType();
        Character ch = key.getCharacter();
        deliver.set(false);

        if (t == KeyType.CHARACTER && key.isCtrlDown() && ch != null
                && (Character.toLowerCase(ch) == 'c' || Character.toLowerCase(ch) == 'd')) {
            closeWithoutChanges();
            return;
        }
        ContextKeybindingDispatcher.Result resolved =
            keybindings.resolve(List.of("Tabs", "Settings"), key);
        if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) return;
        if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String value)) {
            boolean handled = switch (value) {
                case "tabs:next" -> { switchTab(1); yield true; }
                case "tabs:previous" -> { switchTab(-1); yield true; }
                case "confirm:no" -> { closeWithoutChanges(); yield true; }
                default -> false;
            };
            if (handled) {
                return;
            }
        }
        if (t == KeyType.ESCAPE) { closeWithoutChanges(); return; }
        if (t == KeyType.ARROW_LEFT)  { switchTab(-1); return; }
        if (t == KeyType.ARROW_RIGHT) { switchTab(1);  return; }
        if (t == KeyType.ARROW_DOWN && selectedTab == Tab.CONFIG) {

            // focused tab's content has opted in — only Config does here.
            headerFocused = false;
            invalidate();
        }
        // Any other key while header-focused: nothing to do (Status/Usage have
        // no content interaction) — already consumed above.
    }

    @Override
    public synchronized void handlePlainText(String text, AtomicBoolean deliver) {
        if (!active) return;
        if (!headerFocused && selectedTab == Tab.CONFIG) {
            configPanel.handlePlainText(text, deliver);
            return;
        }
        InlineOverlay.super.handlePlainText(text, deliver);
    }

    private void switchTab(int delta) {
        if (statsTabRequest != null
                && ((delta > 0 && selectedTab == Tab.USAGE)
                    || (delta < 0 && selectedTab == Tab.STATUS))) {
            openStatsTab();
            return;
        }
        Tab[] tabs = Tab.values();
        selectedTab = tabs[InlineOverlay.cycleIndex(selectedTab.ordinal(), delta, tabs.length)];
        headerFocused = true;
        renderedHeaderSize = null;
        header.invalidate();
        syncTabVisibility();
        invalidate();
        requestWindowsCompleteRefresh();
    }

    private static boolean isWindowsPlatform() {
        return Strings.CI.contains(System.getProperty("os.name", ""), "win");
    }

    private void requestWindowsCompleteRefresh() {
        if (!isWindows) return;
        invalidateCompleteRefreshFrame();
        completeRefresh.run();
    }

    private synchronized void invalidateCompleteRefreshFrame() {
        renderedHeaderSize = null;
        header.invalidate();
        configPanel.invalidateForCompleteRefresh();
        statusPane.invalidate();
        usagePane.invalidate();
        invalidate();
    }

    @Explanation("Windows console repaint after Settings opens, changes focus, or switches tabs can omit changed cells")
    private void requestCompleteRefresh() {
        var textGui = getTextGUI();
        if (textGui == null) return;
        textGui.getGUIThread().invokeLater(() -> {
            // Settings tab changes also change preferred height. Let Lanterna
            // complete that layout frame before resending the settled buffer.
            textGui.getGUIThread().invokeLater(() -> {
                try {
                    // The normal frame consumes the first invalidation. Mark
                    // the complete frame dirty again so updateScreen redraws
                    // the components instead of only resending stale cells.
                    invalidateCompleteRefreshFrame();
                    textGui.updateScreen();
                    textGui.getScreen().refresh(Screen.RefreshType.COMPLETE);
                } catch (Exception _) {
                    // The terminal may be closing while the queued repaint runs.
                }
            });
        });
    }

    private void openStatsTab() {
        active = false;
        renderedHeaderSize = null;
        onResult = null;
        configPanel.setTabVisible(false);
        statusPane.hide();
        usagePane.hide();
        invalidate();
        statsTabRequest.run();
    }

    private void syncTabVisibility() {
        configPanel.setTabVisible(selectedTab == Tab.CONFIG);
        if (selectedTab == Tab.STATUS) {
            statusPane.show(statusPropertiesSupplier != null ? statusPropertiesSupplier.get() : List.of());
        } else {
            statusPane.hide();
        }
        if (selectedTab == Tab.USAGE) {
            usagePane.show();
        } else {
            usagePane.hide();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Sizing / focus
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        if (!active) return new TerminalSize(0, 0);
        return super.calculatePreferredSize();
    }

    @Override public Interactable nextFocus(Interactable fromThis) {
        return active ? super.nextFocus(fromThis) : null;
    }

    @Override public Interactable previousFocus(Interactable fromThis) {
        return active ? super.previousFocus(fromThis) : null;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test accessors (package-private)
    // ──────────────────────────────────────────────────────────────────────────

    Tab selectedTab() { return selectedTab; }
    boolean headerFocused() { return headerFocused; }
    ConfigPanel configPanel() { return configPanel; }
    StatusPane statusPane() { return statusPane; }
    UsagePane usagePane() { return usagePane; }

    // ──────────────────────────────────────────────────────────────────────────
    // Header renderer
    // ──────────────────────────────────────────────────────────────────────────

    private final class HeaderArea extends AbstractComponent<HeaderArea> {
        @Override protected ComponentRenderer<HeaderArea> createDefaultRenderer() {
            return new HeaderRenderer();
        }
    }

    private final class HeaderRenderer implements ComponentRenderer<HeaderArea> {

        @Override
        public TerminalSize getPreferredSize(HeaderArea c) {
            if (!active) return new TerminalSize(0, 0);
            return new TerminalSize(60, configPanel.stringEditorActive() ? 1 : 4);
        }

        @Override
        public void drawComponent(TextGUIGraphics g, HeaderArea c) {
            if (!active) return;
            TerminalSize size = g.getSize();
            boolean compact = configPanel.stringEditorActive();
            if (size.equals(renderedHeaderSize)
                    && selectedTab == renderedHeaderTab
                    && headerFocused == renderedHeaderFocused
                    && compact == renderedHeaderCompact) {
                return;
            }
            g.fill(' ');
            int columns = size.getColumns();
            g.setForegroundColor(LanternaTheme.divider());
            g.putString(0, 0, "─".repeat(Math.max(0, columns)));

            if (compact) {
                renderedHeaderSize = size;
                renderedHeaderTab = selectedTab;
                renderedHeaderFocused = headerFocused;
                renderedHeaderCompact = true;
                return;
            }

            int col = LEFT_PAD;
            g.setForegroundColor(LanternaTheme.professionalBlue());
            g.enableModifiers(SGR.BOLD);
            g.putString(col, 1, "Settings");
            g.disableModifiers(SGR.BOLD);
            col += "Settings".length() + 1;
            for (Tab tab : Tab.values()) {
                boolean isCurrent = tab == selectedTab;
                String label = " " + tabLabel(tab) + " ";
                if (isCurrent && headerFocused) {
                    g.setBackgroundColor(LanternaTheme.claude());
                    g.setForegroundColor(LanternaTheme.inverseText());
                } else {
                    g.setBackgroundColor(TextColor.ANSI.DEFAULT);
                    g.setForegroundColor(isCurrent ? LanternaTheme.inputText() : LanternaTheme.welcomeDim());
                }
                if (isCurrent) g.enableModifiers(SGR.BOLD);
                g.putString(col, 1, label);
                g.disableModifiers(SGR.BOLD);
                col += label.length() + 1;
            }
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(col, 1, " Stats ");
            g.setBackgroundColor(TextColor.ANSI.DEFAULT);
            renderedHeaderSize = size;
            renderedHeaderTab = selectedTab;
            renderedHeaderFocused = headerFocused;
            renderedHeaderCompact = false;
        }
    }

    private static String tabLabel(Tab tab) {
        return switch (tab) {
            case STATUS -> "Status";
            case CONFIG -> "Config";
            case USAGE  -> "Usage";
        };
    }
}
