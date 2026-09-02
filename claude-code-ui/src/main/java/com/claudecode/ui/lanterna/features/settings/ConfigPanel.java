package com.claudecode.ui.lanterna.features.settings;


import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.message.RefusalFallbackFeature;
import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.runtime.outputstyle.OutputStyleCatalog;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.Component;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Interactable;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import com.claudecode.ui.lanterna.dialog.ModelPickerDialog;
import com.claudecode.ui.lanterna.dialog.OutputStylePickerDialog;
import com.claudecode.ui.lanterna.dialog.ThemePickerDialog;
import com.claudecode.ui.lanterna.components.LanternaDraw;
import com.claudecode.ui.lanterna.input.ContextKeybindingDispatcher;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * Inline {@code /config} Settings panel — sits above {@link InputPanel} in the SmartLayout stack,
 * occupying zero rows when idle.
 */
public final class ConfigPanel extends Panel implements InlineOverlay {

    /** How a setting is edited in the panel. */
    enum ItemType { BOOLEAN, ENUM, STRING, THEME, OUTPUT_STYLE, MODEL }

    /**
     * UI-side view model for one setting — key/label/type/options only, no
     * persistence metadata. Keys and enum options are kept aligned with
     * {@code ConfigCommand.SETTINGS} by a guard test ({@link #itemKeys}) — with
     * one documented exception: {@code model} has no {@code ConfigCommand.Setting}
     * row (see below) but is still a real {@code ConfigPanel} item.
     */
    record Item(String key, String label, ItemType type, List<String> options) {}

    private record SearchItem(Item item, String textLower) {}

    private static final List<Item> ITEMS = List.of(

        new Item("autoCompactEnabled", "Auto-compact", ItemType.BOOLEAN, null),
// Offered only while the refusal-fallback lane is on; see visibleItems.
        new Item("switchModelsOnFlag", "Switch models when a message is flagged",
            ItemType.BOOLEAN, null),
        new Item("spinnerTipsEnabled", "Show tips", ItemType.BOOLEAN, null),
        new Item("prefersReducedMotion", "Reduce motion", ItemType.BOOLEAN, null),
        new Item("thinkingEnabled", "Thinking mode", ItemType.BOOLEAN, null),
        new Item("awaySummaryEnabled", "Session recap", ItemType.BOOLEAN, null),
        new Item("fileCheckpointingEnabled", "Rewind code (checkpoints)", ItemType.BOOLEAN, null),
        new Item("enableWorkflows", "Dynamic workflows", ItemType.BOOLEAN, null),
        new Item("workflowKeywordTriggerEnabled", "Ultracode keyword trigger",
            ItemType.BOOLEAN, null),
        new Item("verbose", "Verbose output", ItemType.BOOLEAN, null),
        new Item("terminalProgressBarEnabled", "Terminal progress bar", ItemType.BOOLEAN, null),
        new Item("showTurnDuration", "Show turn duration", ItemType.BOOLEAN, null),
        new Item("defaultPermissionMode", "Default permission mode", ItemType.ENUM,
            List.of("default", "plan", "acceptEdits", "auto", "dontAsk")),
        new Item("worktreeBaseRef", "Worktree base ref", ItemType.ENUM,
            List.of("fresh", "head")),
        new Item("useAutoModeDuringPlan", "Use auto mode during plan", ItemType.BOOLEAN, null),
        new Item("respectGitignore", "Respect .gitignore in file picker", ItemType.BOOLEAN, null),
        new Item("copyFullResponse", "Skip the /copy picker", ItemType.BOOLEAN, null),
        new Item("defaultToAgentsView", "Open agents view by default", ItemType.BOOLEAN, null),
        new Item("leftArrowOpensAgents", "← opens agents", ItemType.BOOLEAN, null),
        new Item("autoUpdatesChannel", "Auto-update channel", ItemType.ENUM,
            List.of("disabled", "latest", "stable")),
        new Item("theme", "Theme", ItemType.THEME, null),
        new Item("preferredNotifChannel", "Local notifications", ItemType.ENUM,
            List.of("auto", "iterm2", "terminal_bell", "iterm2_with_bell", "kitty",
                "ghostty", "notifications_disabled")),
        new Item("outputStyle", "Output style", ItemType.OUTPUT_STYLE, null),
        new Item("language", "Language", ItemType.STRING, null),
        new Item("editorMode", "Editor mode", ItemType.ENUM, List.of("normal", "vim")),
        new Item("externalEditorContext", "Show last response in external editor",
            ItemType.BOOLEAN, null),
        new Item("prStatusFooterEnabled", "Show PR status footer", ItemType.BOOLEAN, null),
        // The model bypasses ConfigCommand persistence and applies to the runtime session.
        new Item("model", "Model", ItemType.MODEL, null),
        new Item("autoConnectIde", "Auto-connect to IDE (external terminal)",
            ItemType.BOOLEAN, null),
        new Item("claudeInChromeDefaultEnabled", "Claude in Chrome enabled by default",
            ItemType.BOOLEAN, null)
    );

    /** Panel interaction mode. */
    enum Mode { LIST, SEARCH, SUBMENU }

    private static final int LEFT_PAD = 2;
    private static final int VALUE_COL = 45;
    private static final String DEFAULT_MODEL_PREFERENCE = "default";

    private final IntSupplier contentHeightSupplier;
    private boolean active;
    /**
     * Rendering-only visibility, distinct from {@link #active}.
     */
    private boolean tabVisible = true;
    private Mode mode = Mode.LIST;

    /**
     * Whether the refusal-fallback lane is on, which is what decides if the
     * {@code switchModelsOnFlag} row is offered. Production reads the process
     * environment; tests inject a constant rather than mutating it.
     */
    private BooleanSupplier refusalLaneEnabled = RefusalFallbackFeature::settingVisible;

    // Buffered-commit state.
    private Map<String, String> original = new LinkedHashMap<>();
    private Map<String, String> pending = new LinkedHashMap<>();


    private boolean hasAssistantMessage;
    private boolean showThinkingWarning;

    /** Warning shown when toggling thinkingEnabled with an existing conversation. */
    private static final String THINKING_WARNING =
        "Changing thinking mode mid-conversation will increase latency and may reduce quality.";

    // Navigation + scroll.
    private int selectedIndex;
    private int scrollOffset;
    private int maxVisible;

    // Search.
    private final StringBuilder searchQuery = new StringBuilder();
    private int searchCursorOffset;
    private final ArrayList<Item> filtered = new ArrayList<>(ITEMS.size());
    private List<Item> visibleItems = ITEMS;
    private List<SearchItem> searchableItems = List.of();
    /** Prior queries within one open panel; backspace/restore never rescans the inventory. */
    private final Map<String, List<Item>> searchResultCache = new HashMap<>();
    /** One terminal poll may deliver a complete word; filter only its final value. */
    private int inputBatchDepth;
    private boolean searchFilterDirty;
    private Consumer<Runnable> guiInvoker;
    private long searchFilterGeneration;
    private boolean searchFilterPending;
    private boolean clearRowsForPendingFilter;
    private String lastAppliedSearchQuery = "";
    /** Static divider/footer rows survive same-mode input frames. */
    private TerminalSize renderedListSize;
    private Mode renderedListMode;
    private final ListArea listArea;

    // Managed submenus — reused for their selection/revert logic + rendering.
    // Only one is ever active at a time; activeSubmenu routes key events to it.
    private final ThemePickerDialog themeSubmenu = new ThemePickerDialog();
    private final OutputStylePickerDialog outputStyleSubmenu;
    private final ModelPickerDialog modelSubmenu = new ModelPickerDialog();
    private final StringValueEditor stringSubmenu = new StringValueEditor();
    /** Settings/catalog metadata loaded by the owner away from the GUI thread. */
    private String modelPickerPriorPersistedEffort;
    private List<CustomModelConfig> modelPickerCustomModels;
    private ModelPickerDialog.PreparedModelPicker preparedModelPicker;
    private InlineOverlay activeSubmenu;

// Callbacks (see show).
    private Consumer<String> onPreview;
    private BiConsumer<String, String> onChange = (_, _) -> { };
    private Consumer<Map<String, String>> onResult;
    private final ContextKeybindingDispatcher keybindings =
        new ContextKeybindingDispatcher();


    private Runnable onFocusHeaderRequest;

    public ConfigPanel(int terminalRows) {
        this(() -> SettingsTabContainer.contentHeightForTerminalRows(terminalRows),
            OutputStyleCatalog.builtIns());
    }

    void setSyntaxHighlightingAccess(BooleanSupplier reader,
                                     Consumer<Boolean> writer) {
        themeSubmenu.setSyntaxHighlightingAccess(reader, writer);
    }

    ConfigPanel(IntSupplier contentHeightSupplier, OutputStyleCatalog outputStyles) {
        this(contentHeightSupplier, OutputStylePickerDialog.liveCwd(outputStyles));
    }

    ConfigPanel(IntSupplier contentHeightSupplier, OutputStyleCatalog outputStyles, Path cwd) {
        this(contentHeightSupplier, new OutputStylePickerDialog(outputStyles, cwd));
    }

    private ConfigPanel(IntSupplier contentHeightSupplier,
                        OutputStylePickerDialog outputStyleSubmenu) {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        this.contentHeightSupplier = contentHeightSupplier;
        this.outputStyleSubmenu = outputStyleSubmenu;
        listArea = new ListArea();
        listArea.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(listArea);
        themeSubmenu.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(themeSubmenu);
        outputStyleSubmenu.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(outputStyleSubmenu);
        modelSubmenu.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(modelSubmenu);
        stringSubmenu.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(stringSubmenu);
    }

    @Override
    protected ComponentRenderer<Panel> createDefaultRenderer() {
        return new IncrementalConfigPanelRenderer();
    }

    /**
     * Search edits keep the panel geometry stable. The stock renderer treats an
     * invalid list child as a reason to re-layout and redraw every mounted cold
     * submenu. Retain child bounds and redraw only invalid children unless a
     * preferred size, visibility, layout, or terminal size actually changed.
     */
    private final class IncrementalConfigPanelRenderer implements ComponentRenderer<Panel> {
        private TerminalSize lastSize;
        private final Map<Component, TerminalSize> preferredSizes = new IdentityHashMap<>();
        private final Map<Component, Boolean> visibility = new IdentityHashMap<>();

        @Override
        public TerminalSize getPreferredSize(Panel component) {
            return getLayoutManager().getPreferredSize(getChildrenList());
        }

        @Override
        public void drawComponent(TextGUIGraphics graphics, Panel component) {
            List<Component> children = getChildrenList();
            TerminalSize size = graphics.getSize();
            boolean relayout = lastSize == null || !lastSize.equals(size)
                || getLayoutManager().hasChanged() || visibilityChanged(children);
            if (!relayout) {
                for (Component child : children) {
                    if (!child.isVisible() || !child.isInvalid()) continue;
                    TerminalSize preferred = child.getPreferredSize();
                    if (!preferred.equals(preferredSizes.get(child))) {
                        relayout = true;
                        break;
                    }
                }
            }
            if (relayout) {
                graphics.fill(' ');
                getLayoutManager().doLayout(size, children);
                lastSize = size;
                rememberGeometry(children);
            }

            boolean drewChild = false;
            for (Component child : children) {
                if (!child.isVisible() || (!relayout && !child.isInvalid())) continue;
                child.draw(graphics.newTextGraphics(child.getPosition(), child.getSize()));
                drewChild = true;
            }
            if (!drewChild) {
                // Parent-only invalidation (for example a theme change) must
                // preserve the stock renderer's complete repaint semantics.
                for (Component child : children) {
                    if (!child.isVisible()) continue;
                    child.draw(graphics.newTextGraphics(child.getPosition(), child.getSize()));
                }
            }
        }

        private boolean visibilityChanged(List<Component> children) {
            if (visibility.size() != children.size()) return true;
            for (Component child : children) {
                if (!Boolean.valueOf(child.isVisible()).equals(visibility.get(child))) return true;
            }
            return false;
        }

        private void rememberGeometry(List<Component> children) {
            preferredSizes.keySet().retainAll(children);
            visibility.keySet().retainAll(children);
            for (Component child : children) {
                visibility.put(child, child.isVisible());
                if (child.isVisible()) preferredSizes.put(child, child.getPreferredSize());
            }
        }
    }

    /**
     * Setting keys in this panel's display order — exposed so a guard test can
     * assert alignment with {@code ConfigCommand.settingKeys()}.
     */
    public static List<String> itemKeys() {
        return ITEMS.stream().map(Item::key).toList();
    }

    /** See {@link #onFocusHeaderRequest}. */
    void setOnFocusHeaderRequest(Runnable callback) {
        this.onFocusHeaderRequest = callback;
    }

    /** Test seam for {@link #refusalLaneEnabled}. */
    void setRefusalLaneEnabled(BooleanSupplier enabled) {
        this.refusalLaneEnabled = enabled;
    }

    void setKeybindingsStore(UserKeybindingsStore store) {
        keybindings.setStore(store);
        themeSubmenu.setKeybindingsStore(store);
        outputStyleSubmenu.setKeybindingsStore(store);
        modelSubmenu.setKeybindingsStore(store);
    }

    void setGuiInvoker(Consumer<Runnable> guiInvoker) {
        this.guiInvoker = guiInvoker;
        outputStyleSubmenu.setGuiInvoker(guiInvoker);
        modelSubmenu.setGuiInvoker(guiInvoker);
    }

    void setModelAllowed(Predicate<String> modelAllowed) {
        modelSubmenu.setModelAllowed(modelAllowed);
    }

    void setBuiltInModelFamiliesVisible(boolean visible) {
        modelSubmenu.setBuiltInFamiliesVisible(visible);
    }







    void setModelPickerMetadata(String priorPersistedEffort,
                                List<CustomModelConfig> customModels) {
        this.modelPickerPriorPersistedEffort = priorPersistedEffort;
        this.modelPickerCustomModels = customModels == null
            ? null : List.copyOf(customModels);
        this.preparedModelPicker = null;
    }

    void setPreparedModelPicker(ModelPickerDialog.PreparedModelPicker prepared) {
        this.preparedModelPicker = prepared;
    }


    public synchronized void show(Map<String, String> currentValues,
                                  boolean hasAssistantMessage,
                                  Consumer<String> onPreview,
                                  Consumer<Map<String, String>> onResult) {
        show(currentValues, hasAssistantMessage, onPreview, (_, _) -> { }, onResult);
    }

    public synchronized void show(Map<String, String> currentValues,
                                  boolean hasAssistantMessage,
                                  Consumer<String> onPreview,
                                  BiConsumer<String, String> onChange,
                                  Consumer<Map<String, String>> onResult) {
        this.original = new LinkedHashMap<>(currentValues);
        this.pending = new LinkedHashMap<>();
        this.hasAssistantMessage = hasAssistantMessage;
        this.showThinkingWarning = false;
        this.onPreview = onPreview;
        this.onChange = onChange != null ? onChange : (_, _) -> { };
        this.onResult = onResult;

        // search-input focus, not list navigation.
        this.mode = Mode.SEARCH;
        this.selectedIndex = 0;
        this.scrollOffset = 0;
        this.searchQuery.setLength(0);
        this.searchCursorOffset = 0;
        this.searchFilterDirty = false;
        this.searchFilterPending = false;
        this.clearRowsForPendingFilter = false;
        this.lastAppliedSearchQuery = "";
        this.searchFilterGeneration++;
        this.renderedListSize = null;
        this.renderedListMode = null;
        prepareVisibleItems();
        refreshViewport();
        this.active = true;
        this.tabVisible = true;
        rebuildFiltered();
        invalidate();
    }

    /**
     * Toggles rendering visibility without ending the edit session — see
     * {@link #visible}. Must run on the GUI thread.
     */
    void setTabVisible(boolean tabVisible) {
        if (this.tabVisible != tabVisible) {
            renderedListSize = null;
            renderedListMode = null;
            listArea.invalidate();
        }
        this.tabVisible = tabVisible;
        invalidate();
    }

    /** Forces every mounted Config surface to redraw into a complete-refresh frame. */
    void invalidateForCompleteRefresh() {
        renderedListSize = null;
        renderedListMode = null;
        for (Component child : getChildrenList()) child.invalidate();
        invalidate();
    }

    @Override public boolean isActive() { return active; }

    synchronized void beginInputBatch() {
        inputBatchDepth++;
    }

    synchronized void endInputBatch() {
        if (inputBatchDepth == 0) return;
        inputBatchDepth--;
        if (inputBatchDepth == 0 && searchFilterDirty) {
            searchFilterDirty = false;
            // A decoded PTY text/backspace run is one semantic edit. Its final
// query is already known before processInput returns, so rebuild
            // the small precomputed inventory now and commit one complete
            // frame instead of painting query-only and filtered-list frames.
            searchFilterGeneration++;
            searchFilterPending = false;
            clearRowsForPendingFilter = false;
            rebuildFiltered();
            listArea.invalidate();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Key handling
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public synchronized void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!active) return;

        // SUBMENU: forward everything to the active picker (theme or model); it
        // drives its own preview/revert and its onResult flips us back to LIST.
        if (mode == Mode.SUBMENU) {
            if (activeSubmenu != null) activeSubmenu.handleKey(key, deliver);
            deliver.set(false);
            return;
        }

        if (mode == Mode.SEARCH) {
            handleSearchKey(key, deliver);
            return;
        }

        handleListKey(key, deliver);
    }

    @Override
    public synchronized void handlePlainText(String text, AtomicBoolean deliver) {
        if (!active) return;
        if (mode == Mode.SUBMENU && activeSubmenu != null) {
            activeSubmenu.handlePlainText(text, deliver);
            return;
        }
        if (mode != Mode.SEARCH || text == null || text.isEmpty()) {
            InlineOverlay.super.handlePlainText(text, deliver);
            return;
        }
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character < 0x20) {
                InlineOverlay.super.handlePlainText(text, deliver);
                return;
            }
        }
        if (searchCursorOffset == searchQuery.length()) {
            searchQuery.append(text);
        } else {
            searchQuery.insert(searchCursorOffset, text);
        }
        searchCursorOffset += text.length();
        searchQueryChanged();
        deliver.set(false);
    }

    private void handleSearchKey(KeyStroke key, AtomicBoolean deliver) {
        KeyType t = key.getKeyType();
        deliver.set(false);
        if (t == KeyType.ARROW_UP) {

            // tab header. No-op (key just swallowed) when standalone/unwired.
            if (onFocusHeaderRequest != null) onFocusHeaderRequest.run();
            invalidate();
            return;
        }
        if (t == KeyType.ESCAPE) {
            if (!searchQuery.isEmpty()) {
                searchQuery.setLength(0);
                searchCursorOffset = 0;
                searchQueryChanged();
            } else {
                mode = Mode.LIST;
                invalidate();
            }
            return;
        }
        if (t == KeyType.ENTER || t == KeyType.ARROW_DOWN) {
            mode = Mode.LIST;
            selectedIndex = 0;
            scrollOffset = 0;
            invalidate();
            return;
        }
        if (t == KeyType.BACKSPACE) {
            if (searchQuery.isEmpty()) {
                mode = Mode.LIST;
                invalidate();
            } else if (searchCursorOffset > 0) {
                if (searchCursorOffset == searchQuery.length()) {
                    searchQuery.setLength(searchCursorOffset - 1);
                } else {
                    searchQuery.deleteCharAt(searchCursorOffset - 1);
                }
                searchCursorOffset--;
                searchQueryChanged();
            }
            return;
        }
        if (t == KeyType.ARROW_LEFT) {
            if (searchCursorOffset > 0) searchCursorOffset--;
            invalidate();
            return;
        }
        if (t == KeyType.ARROW_RIGHT) {
            if (searchCursorOffset < searchQuery.length()) searchCursorOffset++;
            invalidate();
            return;
        }
        if (t == KeyType.CHARACTER && key.getCharacter() != null
                && key.getCharacter() >= 0x20 && !key.isCtrlDown() && !key.isAltDown()) {
            if (searchCursorOffset == searchQuery.length()) {
                searchQuery.append(key.getCharacter().charValue());
            } else {
                searchQuery.insert(searchCursorOffset, key.getCharacter().charValue());
            }
            searchCursorOffset++;
            searchQueryChanged();
        }
    }

    private void handleListKey(KeyStroke key, AtomicBoolean deliver) {
        KeyType t = key.getKeyType();
        Character ch = key.getCharacter();

        if (t == KeyType.CHARACTER && key.isCtrlDown() && ch != null
                && (Character.toLowerCase(ch) == 'c' || Character.toLowerCase(ch) == 'd')) {
            close();
            deliver.set(false);
            return;
        }

        if (keybindings.isCustomizationEnabled()) {
            ContextKeybindingDispatcher.Result resolved = keybindings.resolve("Settings", key);
            if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) {
                deliver.set(false);
                return;
            }
            if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String value)) {
                if (t == KeyType.ENTER && Strings.CS.equals("settings:close", value)) {
                    // Older generated keybindings files mapped Enter to close,
                    // but the released Config contract uses Enter and Space to
                    // change the selected row. Escape remains the close key.
                    toggleSelected(false);
                    invalidate();
                } else {
                    dispatchSettingsAction(value);
                }
                deliver.set(false);
                return;
            }
        }

        if (t == KeyType.ESCAPE) { close(); deliver.set(false); return; }
        if (t == KeyType.ENTER)  {
            toggleSelected(false);
            invalidate();
            deliver.set(false);
            return;
        }

        if (t == KeyType.ARROW_UP) {
            showThinkingWarning = false;
            if (selectedIndex == 0) {
                mode = Mode.SEARCH;
                searchCursorOffset = searchQuery.length();
                scrollOffset = 0;
            } else {
                selectedIndex--;
                adjustScroll();
            }
            invalidate();
            deliver.set(false);
            return;
        }
        if (t == KeyType.ARROW_DOWN) {
            showThinkingWarning = false;
            if (selectedIndex < filtered.size() - 1) {
                selectedIndex++;
                adjustScroll();
            }
            invalidate();
            deliver.set(false);
            return;
        }

        // Space / ←/→ / Tab = toggle in place. Must precede the type-to-search

        // `e.key !== ' '` guard).
        boolean toggleKey = (t == KeyType.CHARACTER && ch != null && ch == ' ')
            || t == KeyType.ARROW_LEFT || t == KeyType.ARROW_RIGHT || t == KeyType.TAB;
        if (toggleKey) {
            toggleSelected(t == KeyType.ARROW_LEFT);
            invalidate();
            deliver.set(false);
            return;
        }

        // Any other printable char → enter search ('/' opens an empty query).
        if (t == KeyType.CHARACTER && ch != null && ch > 0x20
                && !key.isCtrlDown() && !key.isAltDown()) {
            mode = Mode.SEARCH;
            searchQuery.setLength(0);
            searchCursorOffset = 0;
            if (ch != '/') {
                searchQuery.append(ch.charValue());
                searchCursorOffset = 1;
                rebuildFiltered();
            }
            selectedIndex = 0;
            scrollOffset = 0;
            invalidate();
            deliver.set(false);
        }
    }

    private void dispatchSettingsAction(String action) {
        switch (action) {
            case "confirm:no", "settings:close" -> close();
            case "select:previous" -> {
                showThinkingWarning = false;
                if (selectedIndex == 0) {
                    mode = Mode.SEARCH;
                    searchCursorOffset = searchQuery.length();
                    scrollOffset = 0;
                } else {
                    selectedIndex--;
                    adjustScroll();
                }
                invalidate();
            }
            case "select:next" -> {
                showThinkingWarning = false;
                if (selectedIndex < filtered.size() - 1) {
                    selectedIndex++;
                    adjustScroll();
                }
                invalidate();
            }
            case "select:accept" -> {
                toggleSelected(false);
                invalidate();
            }
            case "settings:search" -> {
                mode = Mode.SEARCH;
                searchQuery.setLength(0);
                searchCursorOffset = 0;
                rebuildFiltered();
                selectedIndex = 0;
                scrollOffset = 0;
                invalidate();
            }
            default -> { }
        }
    }

    private void toggleSelected(boolean leftward) {
        if (filtered.isEmpty()) return;
        Item it = filtered.get(selectedIndex);
        switch (it.type()) {
            case BOOLEAN -> {
                boolean cur = Strings.CS.equals("true", effective(it.key()));
                putPending(it.key(), String.valueOf(!cur));
                if (Strings.CS.equals(it.key(), "thinkingEnabled")) {
                    showThinkingWarning = hasAssistantMessage
                        && !effective(it.key()).equals(original.get(it.key()));
                }
            }
            case ENUM -> {


                // left/right/tab fired it (confirmed via handleKeyDown,

                // deliberately as a more intuitive Java-side UX improvement.
                List<String> opts = it.options();
                int i = opts.indexOf(effective(it.key()));
                if (i < 0) i = 0;
                int n = leftward ? (i - 1 + opts.size()) % opts.size() : (i + 1) % opts.size();
                putPending(it.key(), opts.get(n));
            }
            case THEME -> openThemeSubmenu();
            case OUTPUT_STYLE -> openOutputStyleSubmenu();
            case STRING -> openStringSubmenu(it);
            case MODEL -> openModelSubmenu();
        }
    }

    private void openStringSubmenu(Item item) {
        mode = Mode.SUBMENU;
        renderedListSize = null;
        renderedListMode = null;
        activeSubmenu = stringSubmenu;
        stringSubmenu.show(effective(item.key()), chosen -> {
            mode = Mode.LIST;
            if (chosen != null) putPending(item.key(), chosen);
            invalidate();
        });
        invalidate();
    }

    private void openThemeSubmenu() {
        mode = Mode.SUBMENU;
        renderedListSize = null;
        renderedListMode = null;
        activeSubmenu = themeSubmenu;
        themeSubmenu.show(effective("theme"), onPreview, chosen -> {
            mode = Mode.LIST;
            if (chosen != null) putPending("theme", chosen);
            // chosen == null (Esc): ThemePickerDialog already reverted the preview.
            invalidate();
        });
        invalidate();
    }

    private void openModelSubmenu() {
        mode = Mode.SUBMENU;
        renderedListSize = null;
        renderedListMode = null;
        activeSubmenu = modelSubmenu;

        // cancel, nothing applied while browsing), so unlike theme there's no
        // visual state to revert on Esc.
        String preference = pickerModelPreference(effective("model"));
        ModelPickerDialog.PreparedModelPicker prepared = preparedModelPicker;
        if (prepared == null || !Objects.equals(prepared.modelPreference(), preference)) {
            prepared = modelSubmenu.prepare(preference, null,
                modelPickerPriorPersistedEffort, modelPickerCustomModels);
        }
        modelSubmenu.show(prepared, result -> {
            mode = Mode.LIST;
            if (result != null) {
                // /config only tracks the model id; effort is ignored here.
                // The nullable picker preference is represented by the
                // non-null "default" sentinel inside ConfigPanel's value map.
                String chosen = modelPreferenceValue(result.model());
                putPending("model", chosen);
                preparedModelPicker = null;
            }
            invalidate();
        });
        invalidate();
    }

    private void openOutputStyleSubmenu() {
        mode = Mode.SUBMENU;
        renderedListSize = null;
        renderedListMode = null;
        activeSubmenu = outputStyleSubmenu;
        outputStyleSubmenu.show(effective("outputStyle"), chosen -> {
            mode = Mode.LIST;
            if (chosen != null) putPending("outputStyle", chosen);
            invalidate();
        });
        invalidate();
    }

    private void putPending(String key, String value) {
        if (value.equals(original.get(key))) {
            pending.remove(key);
        } else {
            pending.put(key, value);
        }
        onChange.accept(key, value);
    }

    private String effective(String key) {
        return pending.getOrDefault(key, original.get(key));
    }

    static String modelPreferenceValue(String modelPreference) {
        return modelPreference != null ? modelPreference : DEFAULT_MODEL_PREFERENCE;
    }

    private static String pickerModelPreference(String value) {
        return Strings.CS.equals(DEFAULT_MODEL_PREFERENCE, value) ? null : value;
    }

    private synchronized void close() {
        Consumer<Map<String, String>> cb = onResult;
        Map<String, String> p = new LinkedHashMap<>(pending);
        hide();
        if (cb != null) cb.accept(p);
    }

    private synchronized void hide() {
        active = false;
        mode = Mode.LIST;
        searchFilterGeneration++;
        searchFilterPending = false;
        clearRowsForPendingFilter = false;
        renderedListSize = null;
        renderedListMode = null;
        onPreview = null;
        onChange = (_, _) -> { };
        onResult = null;
        invalidate();
    }

    private void rebuildFiltered() {
        String queryText = searchQuery.toString();
        String q = queryText.trim().toLowerCase(Locale.ROOT);
        filtered.clear();
        List<Item> cached = searchResultCache.get(q);
        if (cached != null) {
            filtered.addAll(cached);
        } else {
            for (SearchItem searchable : searchableItems) {
                if (Strings.CS.contains(searchable.textLower(), q)) {
                    filtered.add(searchable.item());
                }
            }
            if (searchResultCache.size() >= 128) {
                searchResultCache.clear();
                searchResultCache.put("", visibleItems);
            }
            searchResultCache.put(q, List.copyOf(filtered));
        }
        if (selectedIndex >= filtered.size()) selectedIndex = Math.max(0, filtered.size() - 1);
        adjustScroll();
        lastAppliedSearchQuery = queryText;
        renderedListSize = null;
        renderedListMode = null;
    }

    private void searchQueryChanged() {
        if (inputBatchDepth > 0) {
            searchFilterDirty = true;
            return;
        }
        publishSearchFilter();
    }

    /**
     * Commits the edited query before painting the matching rows. The queued
     * rebuild runs later in the same GUI cycle (there is no time debounce), so
     * typing/backspace feedback is never held behind a complete list repaint.
     */
    private void publishSearchFilter() {
        Consumer<Runnable> invoker = guiInvoker;
        if (invoker == null) {
            rebuildFiltered();
            searchFilterPending = false;
            invalidate();
            return;
        }
        long generation = ++searchFilterGeneration;
        searchFilterPending = true;
        clearRowsForPendingFilter = lastAppliedSearchQuery.isEmpty()
            && !searchQuery.isEmpty();
        if (clearRowsForPendingFilter) {
            filtered.clear();
            selectedIndex = 0;
            scrollOffset = 0;
        }
        listArea.invalidate();
        try {
            invoker.accept(() -> applySearchFilter(generation));
        } catch (RuntimeException _) {
            applySearchFilter(generation);
        }
    }

    private synchronized void applySearchFilter(long generation) {
        if (!active || generation != searchFilterGeneration) return;
        rebuildFiltered();
        searchFilterPending = false;
        clearRowsForPendingFilter = false;
        listArea.invalidate();
    }

    /**
     * {@link #ITEMS} minus the rows whose feature is switched off.
     */
    private void prepareVisibleItems() {
        visibleItems = refusalLaneEnabled.getAsBoolean()
            ? ITEMS
            : ITEMS.stream()
                .filter(it -> !Strings.CS.equals("switchModelsOnFlag", it.key()))
                .toList();
        searchableItems = visibleItems.stream()
            .map(item -> new SearchItem(item,
                item.key().toLowerCase(Locale.ROOT) + '\u0000'
                    + item.label().toLowerCase(Locale.ROOT)))
            .toList();
        searchResultCache.clear();
        searchResultCache.put("", visibleItems);
    }

    private void adjustScroll() {
        refreshViewport();
    }

    private void refreshViewport() {
        maxVisible = Math.max(5, contentHeightSupplier.getAsInt() - 10);
        if (selectedIndex < scrollOffset) scrollOffset = selectedIndex;
        if (selectedIndex >= scrollOffset + maxVisible) scrollOffset = selectedIndex - maxVisible + 1;
        int maxOffset = Math.max(0, filtered.size() - maxVisible);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxOffset));
    }

    /** Stable list height keeps the settings overlay anchored while filtering. */
    private int listContentRows() {
        refreshViewport();
        return 3 + 2 + viewportRows() + (showThinkingWarning ? 1 : 0) + 2;
    }

    private int viewportRows() {
        return Math.min(visibleItems.size(), maxVisible);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Sizing / focus
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        if (!active || !tabVisible) return new TerminalSize(0, 0);
        return super.calculatePreferredSize(); // sum of ListArea + themeSubmenu
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

    Mode mode() { return mode; }
    int selectedIndex() { return selectedIndex; }
    Map<String, String> pendingSnapshot() { return new LinkedHashMap<>(pending); }
    List<String> filteredKeys() { return filtered.stream().map(Item::key).toList(); }
    ThemePickerDialog themeSubmenu() { return themeSubmenu; }
    OutputStylePickerDialog outputStyleSubmenu() { return outputStyleSubmenu; }
    ModelPickerDialog modelSubmenu() { return modelSubmenu; }
    boolean showThinkingWarning() { return showThinkingWarning; }
    boolean stringEditorActive() {
        return mode == Mode.SUBMENU && activeSubmenu == stringSubmenu;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Renderer
    // ──────────────────────────────────────────────────────────────────────────

    private final class ListArea extends AbstractComponent<ListArea> {
        @Override protected ComponentRenderer<ListArea> createDefaultRenderer() {
            return new ListRenderer();
        }
    }

    private final class ListRenderer implements ComponentRenderer<ListArea> {

        private int renderedSearchWidth;
        private int[] renderedDynamicWidths = new int[0];

        @Override
        public TerminalSize getPreferredSize(ListArea c) {
            if (!active || !tabVisible || mode == Mode.SUBMENU) return new TerminalSize(0, 0);
            return new TerminalSize(Math.max(60, LEFT_PAD * 2 + VALUE_COL + 20), listContentRows());
        }

        @Override
        public void drawComponent(TextGUIGraphics g, ListArea c) {
            if (!active || !tabVisible || mode == Mode.SUBMENU) return;
            refreshViewport();
            TerminalSize size = g.getSize();
            boolean fullRender = !size.equals(renderedListSize)
                || mode != renderedListMode;
            int cols = g.getSize().getColumns();
            int footerRow = listContentRows() - 1;
            boolean queryOnlyFrame = searchFilterPending
                && !clearRowsForPendingFilter && !fullRender;
            if (fullRender) {
                g.fill(' ');
                renderedSearchWidth = 0;
                clearRememberedDynamicWidths();
            } else {
                // Search/filter input never changes the divider, row 2, or
                // same-mode footer. Keep those cells and clear only the rows
                // whose text can actually change.
                if (renderedSearchWidth > 0) {
                    g.fillRectangle(new TerminalPosition(LEFT_PAD, 1),
                        new TerminalSize(Math.min(renderedSearchWidth,
                            Math.max(0, cols - LEFT_PAD)), 1), ' ');
                }
                if (!queryOnlyFrame && footerRow > 3) {
                    clearRememberedDynamicRows(g, cols, 3, footerRow);
                }
            }

            int boxWidth = Math.max(3, cols - LEFT_PAD * 2);
            if (fullRender) {
                g.setForegroundColor(mode == Mode.SEARCH
                    ? LanternaTheme.suggestion() : LanternaTheme.ghostText());
                g.putString(LEFT_PAD, 0, LanternaDraw.borderedSearchBoxTop(boxWidth));
                g.putString(LEFT_PAD, 2, LanternaDraw.borderedSearchBoxBottom(boxWidth));
            }

            g.setForegroundColor(mode == Mode.SEARCH
                ? LanternaTheme.suggestion() : LanternaTheme.ghostText());
            String searchContent = searchQuery.isEmpty()
                ? LanternaDraw.borderedSearchBoxContent(
                    false, "Search settings…", 0, boxWidth)
                : LanternaDraw.borderedSearchBoxContent(
                    mode == Mode.SEARCH, searchQuery.toString(),
                    searchCursorOffset, boxWidth);
            g.putString(LEFT_PAD, 1, searchContent);
            renderedSearchWidth = boxWidth;

            if (queryOnlyFrame) {
                renderedListSize = size;
                renderedListMode = mode;
                return;
            }

            // Row 2: blank
            int row = 3;

            // "↑ N more above"
            if (scrollOffset > 0) {
                g.setForegroundColor(LanternaTheme.welcomeDim());
                String moreAbove = "↑ " + scrollOffset + " more above";
                g.putString(LEFT_PAD, row, moreAbove);
                rememberDynamicWidth(row, LEFT_PAD + moreAbove.length());
            }
            row++;
            int listStart = row;

            // List window
            int end = Math.min(filtered.size(), scrollOffset + maxVisible);
            if (filtered.isEmpty() && !searchFilterPending) {
                g.setForegroundColor(LanternaTheme.welcomeDim());
                String noMatch = "No settings match \"" + searchQuery + "\"";
                g.putString(LEFT_PAD, row, noMatch);
                rememberDynamicWidth(row, LEFT_PAD + noMatch.length());
            }
            int y = row;
            for (int i = scrollOffset; i < end; i++) {
                Item it = filtered.get(i);
                rememberDynamicWidth(y, drawItem(
                    g, y, it, mode == Mode.LIST && i == selectedIndex));
                y++;
                if (showThinkingWarning && Strings.CS.equals(it.key(), "thinkingEnabled")) {
                    rememberDynamicWidth(y, drawThinkingWarning(g, y));
                    y++;
                }
            }
            int renderedRows = filtered.isEmpty() && !searchFilterPending
                ? 1 : Math.max(0, end - scrollOffset);
            row = listStart + renderedRows + (showThinkingWarning ? 1 : 0);

            // "↓ N more below"
            int below = filtered.size() - end;
            if (below > 0) {
                g.setForegroundColor(LanternaTheme.welcomeDim());
                String moreBelow = "↓ " + below + " more below";
                g.putString(LEFT_PAD, row, moreBelow);
                rememberDynamicWidth(row, LEFT_PAD + moreBelow.length());
                row++;
            }

            // Row: blank, then footer
            row++;
            g.setForegroundColor(LanternaTheme.welcomeDim());
            if (fullRender) {
                String footer = mode == Mode.SEARCH
                    ? "Type to filter · Enter/↓ to select · ↑ to tabs · Esc to clear"
                    : "Enter/Space to change · / to search · Esc to close";
                g.putString(LEFT_PAD, row, footer);
            }
            renderedListSize = size;
            renderedListMode = mode;
        }

        private void clearRememberedDynamicRows(TextGUIGraphics g, int columns,
                                                int firstRow, int endRow) {
            int last = Math.min(endRow, renderedDynamicWidths.length);
            for (int row = firstRow; row < last; row++) {
                int width = Math.min(renderedDynamicWidths[row], columns);
                if (width > 0) {
                    g.fillRectangle(new TerminalPosition(0, row),
                        new TerminalSize(width, 1), ' ');
                    renderedDynamicWidths[row] = 0;
                }
            }
        }

        private void clearRememberedDynamicWidths() {
            Arrays.fill(renderedDynamicWidths, 0);
        }

        private void rememberDynamicWidth(int row, int endColumn) {
            if (row >= renderedDynamicWidths.length) {
                renderedDynamicWidths = Arrays.copyOf(
                    renderedDynamicWidths, Math.max(row + 1, renderedDynamicWidths.length * 2 + 8));
            }
            renderedDynamicWidths[row] = Math.max(renderedDynamicWidths[row], endColumn);
        }

        private int drawThinkingWarning(TextGUIGraphics g, int rowY) {
            g.setForegroundColor(LanternaTheme.toolWarning());
            g.putString(LEFT_PAD + 2, rowY, THINKING_WARNING);
            return LEFT_PAD + 2 + THINKING_WARNING.length();
        }

        private int drawItem(TextGUIGraphics g, int rowY, Item it, boolean selected) {
            if (selected) {
                g.setForegroundColor(LanternaTheme.claude());
                g.enableModifiers(SGR.BOLD);
                g.putString(LEFT_PAD, rowY, "❯ ");
            } else {
                g.setForegroundColor(LanternaTheme.welcomeDim());
                g.disableModifiers(SGR.BOLD);
                g.putString(LEFT_PAD, rowY, "  ");
            }
            g.setForegroundColor(selected ? LanternaTheme.inputText() : LanternaTheme.welcomeDim());
            if (selected) g.enableModifiers(SGR.BOLD);
            int valueColumn = Math.min(LEFT_PAD + VALUE_COL,
                Math.max(LEFT_PAD + 12, g.getSize().getColumns() - 16));
            int labelWidth = Math.max(1, valueColumn - (LEFT_PAD + 2) - 1);
            String label = FormatUtils.truncateNoEllipsis(it.label(), labelWidth);
            g.putString(LEFT_PAD + 2, rowY, label);
            g.disableModifiers(SGR.BOLD);

            boolean changed = pending.containsKey(it.key());
            g.setForegroundColor(changed ? LanternaTheme.statusCost()
                : (selected ? LanternaTheme.inputText() : LanternaTheme.welcomeDim()));
            String value = valueDisplay(it);
            int valueWidth = Math.max(0, g.getSize().getColumns() - valueColumn);
            String clippedValue = FormatUtils.truncateNoEllipsis(value, valueWidth);
            if (!clippedValue.isEmpty()) g.putString(valueColumn, rowY, clippedValue);
            return Math.max(LEFT_PAD + 2 + FormatUtils.displayWidth(label),
                valueColumn + FormatUtils.displayWidth(clippedValue));
        }

        private String valueDisplay(Item it) {
            String v = effective(it.key());
            return switch (it.type()) {
                case BOOLEAN -> v;
                case ENUM, STRING -> displayValue(it.key(), v);
                case THEME, OUTPUT_STYLE -> displayValue(it.key(), v);
                case MODEL -> (Strings.CS.equals(DEFAULT_MODEL_PREFERENCE, v)
                    ? "Default (recommended)" : v);
            };
        }
    }

    private static String displayValue(String key, String value) {
        if (value == null) return "";
        return switch (key) {
            case "defaultPermissionMode" -> switch (value) {
                case "default" -> "Default";
                case "plan" -> "Plan Mode";
                case "acceptEdits" -> "Accept edits";
                case "auto" -> "Auto mode";
                case "dontAsk" -> "Don't Ask";
                default -> value;
            };
            case "theme" -> switch (value) {
                case "dark" -> "Dark mode";
                case "light" -> "Light mode";
                case "dark-daltonized" -> "Dark mode (colorblind-friendly)";
                case "light-daltonized" -> "Light mode (colorblind-friendly)";
                case "dark-ansi" -> "Dark mode (ANSI colors only)";
                case "light-ansi" -> "Light mode (ANSI colors only)";
                default -> value;
            };
            case "preferredNotifChannel" -> switch (value) {
                case "auto" -> "Auto";
                case "iterm2" -> "iTerm2 (OSC 9)";
                case "terminal_bell" -> "Terminal Bell (\\a)";
                case "iterm2_with_bell" -> "iTerm2 w/ Bell";
                case "kitty" -> "Kitty (OSC 99)";
                case "ghostty" -> "Ghostty (OSC 777)";
                case "notifications_disabled" -> "Disabled";
                default -> value;
            };
            case "language" -> StringUtils.isBlank(value) ? "English" : value;
            default -> value;
        };
    }

    private final class StringValueEditor extends AbstractComponent<StringValueEditor>
            implements InlineOverlay {
        private final StringBuilder value = new StringBuilder();
        private Consumer<String> callback;
        private boolean editorActive;

        void show(String current, Consumer<String> callback) {
            value.setLength(0);
            if (current != null) value.append(current);
            this.callback = callback;
            editorActive = true;
            invalidate();
        }

        @Override public boolean isActive() { return editorActive; }

        @Override
        public void handleKey(KeyStroke key, AtomicBoolean deliver) {
            if (!editorActive) return;
            deliver.set(false);
            switch (key.getKeyType()) {
                case ENTER -> finish(value.toString());
                case ESCAPE -> finish(null);
                case BACKSPACE -> {
                    if (!value.isEmpty()) value.setLength(value.length() - 1);
                    invalidate();
                }
                case CHARACTER -> {
                    Character character = key.getCharacter();
                    if (character != null && character >= 0x20
                            && !key.isCtrlDown() && !key.isAltDown()) {
                        value.append(character);
                        invalidate();
                    }
                }
                default -> { }
            }
        }

        @Override
        public void handlePlainText(String text, AtomicBoolean deliver) {
            if (!editorActive || text == null || text.isEmpty()) return;
            value.append(text);
            deliver.set(false);
            invalidate();
        }

        private void finish(String result) {
            editorActive = false;
            Consumer<String> completed = callback;
            callback = null;
            invalidate();
            if (completed != null) completed.accept(result);
        }

        @Override
        public TerminalSize calculatePreferredSize() {
            return editorActive
                ? new TerminalSize(80, contentHeightSupplier.getAsInt())
                : new TerminalSize(0, 0);
        }

        @Override
        protected ComponentRenderer<StringValueEditor> createDefaultRenderer() {
            return new ComponentRenderer<>() {
                @Override
                public TerminalSize getPreferredSize(StringValueEditor component) {
                    return component.calculatePreferredSize();
                }

                @Override
                public void drawComponent(TextGUIGraphics graphics, StringValueEditor component) {
                    graphics.setForegroundColor(LanternaTheme.inputText());
                    graphics.putString(LEFT_PAD, 0,
                        "Enter your preferred response and voice language:");
                    graphics.putString(LEFT_PAD, 2, "> " + value);
                    graphics.setForegroundColor(LanternaTheme.welcomeDim());
                    graphics.putString(LEFT_PAD, 4, "Leave empty for default (English)");
                    graphics.putString(LEFT_PAD, 6, "Enter to confirm · Esc to cancel");
                }
            };
        }
    }
}
