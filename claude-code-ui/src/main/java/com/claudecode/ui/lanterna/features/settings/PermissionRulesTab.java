package com.claudecode.ui.lanterna.features.settings;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.PermissionEngine;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.PermissionRule;
import com.claudecode.permissions.PermissionRuleDescription;
import com.claudecode.permissions.RuleSource;
import com.claudecode.permissions.UnreachableRule;
import com.claudecode.permissions.UnreachableRuleDetector;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Interactable;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.PasteKeyStroke;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import com.claudecode.ui.lanterna.components.LanternaDraw;
import com.claudecode.ui.lanterna.input.ContextKeybindingDispatcher;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * One Allow/Ask/Deny tab body for {@link PermissionsPanel} — a searchable rule list with
 * add/delete, instantiated three times (one per {@link PermissionBehavior}).
 */
public final class PermissionRulesTab extends Panel {

    enum Mode { LIST, SEARCH, RULE_DETAIL, ADD_INPUT, ADD_DESTINATION }

    private record Destination(String label, String description, RuleSource tier) {}

    private static final List<Destination> DESTINATIONS = List.of(
        new Destination("Project settings (local)", "Saved in .claude/settings.local.json", RuleSource.LOCAL_SETTINGS),
        new Destination("Project settings", "Checked in at .claude/settings.json", RuleSource.PROJECT_SETTINGS),
        new Destination("User settings", "Saved in ~/.claude/settings.json", RuleSource.USER_SETTINGS)
    );

    private static final List<RuleSource> NON_DELETABLE_SOURCES = List.of(
        RuleSource.POLICY_SETTINGS, RuleSource.FLAG_SETTINGS, RuleSource.COMMAND, RuleSource.SKILL);

    private static final int LEFT_PAD = 2;
    private static final int MAX_VISIBLE_OPTIONS = 10;

    private final PermissionBehavior behavior;

    private boolean tabVisible;
    private boolean headerFocused;
    private Mode mode = Mode.LIST;

    private Supplier<PermissionGate> gateSupplier;
    private Supplier<String> cwdSupplier;
    private Runnable onFocusHeaderRequest;
    private Runnable onCloseRequest;
    private BiConsumer<String, TextColor> changeRecorder;
    private Consumer<Runnable> guiInvoker = Runnable::run;
    private volatile boolean saving;

    private List<PermissionRule> filtered = new ArrayList<>();
    private int selectedIndex;
    private int scrollOffset;

    private final StringBuilder searchQuery = new StringBuilder();
    private int searchCursorOffset;

    private PermissionRule detailRule;
    private int detailChoiceIdx;

    private final StringBuilder addInput = new StringBuilder();
    private int addCursorOffset;
    private String addInputError;

    private String pendingRuleString;
    private int destinationIdx;
    private final ContextKeybindingDispatcher keybindings =
        new ContextKeybindingDispatcher();

    PermissionRulesTab(PermissionBehavior behavior) {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        this.behavior = behavior;
        Area area = new Area();
        area.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(area);
    }

    void setOnFocusHeaderRequest(Runnable callback) { this.onFocusHeaderRequest = callback; }
    void setOnCloseRequest(Runnable callback) { this.onCloseRequest = callback; }
    void setChangeRecorder(BiConsumer<String, TextColor> recorder) { this.changeRecorder = recorder; }
    void setGuiInvoker(Consumer<Runnable> invoker) {
        this.guiInvoker = invoker != null ? invoker : Runnable::run;
    }
    void setKeybindingsStore(UserKeybindingsStore store) { keybindings.setStore(store); }

    void bind(Supplier<PermissionGate> gateSupplier, Supplier<String> cwdSupplier) {
        this.gateSupplier = gateSupplier;
        this.cwdSupplier = cwdSupplier;
    }

    void setTabVisible(boolean visible) {
        this.tabVisible = visible;
        invalidate();
    }

    void setHeaderFocused(boolean focused) {
        headerFocused = focused;
        invalidate();
    }

    /** Re-reads the live rule list and resets to LIST mode. See class Javadoc. */
    void reload() {
        this.mode = Mode.LIST;
        this.searchQuery.setLength(0);
        this.searchCursorOffset = 0;
        this.selectedIndex = 0;
        this.scrollOffset = 0;
        refreshRules();
        invalidate();
    }

    private void refreshRules() {
        PermissionGate gate = gateSupplier != null ? gateSupplier.get() : null;
        List<PermissionRule> rules = gate != null
            ? gate.currentContext().rules().stream().filter(r -> r.behavior() == behavior).toList()
            : List.of();
        rebuildFiltered(rules);
    }

    private void rebuildFiltered(List<PermissionRule> rules) {
        String q = searchQuery.toString().trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            filtered = new ArrayList<>(rules);
        } else {
            filtered = new ArrayList<>();
            for (PermissionRule r : rules) {
                if (Strings.CI.contains(PermissionEngine.permissionRuleToString(r), q)) {
                    filtered.add(r);
                }
            }
        }
        int rowCount = filtered.size() + 1; // +1 = the synthetic "Add a new rule..." row
        if (selectedIndex >= rowCount) selectedIndex = rowCount - 1;
        adjustScroll();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Key handling
    // ──────────────────────────────────────────────────────────────────────────

    void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (saving) {
            deliver.set(false);
            return;
        }
        switch (mode) {
            case LIST -> handleListKey(key, deliver);
            case SEARCH -> handleSearchKey(key, deliver);
            case RULE_DETAIL -> handleDetailKey(key, deliver);
            case ADD_INPUT -> handleAddInputKey(key, deliver);
            case ADD_DESTINATION -> handleDestinationKey(key, deliver);
        }
    }

    private void handleListKey(KeyStroke key, AtomicBoolean deliver) {
        KeyType t = key.getKeyType();
        Character ch = key.getCharacter();
        deliver.set(false);
        int rowCount = filtered.size() + 1;

        ContextKeybindingDispatcher.Result resolved =
            keybindings.resolve(List.of("Select", "Settings"), key);
        if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) return;
        if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String value)) {
            switch (value) {
                case "select:previous" -> { moveListPrevious(); return; }
                case "select:next" -> { moveListNext(rowCount); return; }
                case "select:accept" -> { acceptListSelection(); return; }
                case "select:cancel", "confirm:no" -> { requestClose(); return; }
                default -> { }
            }
        }

        if (t == KeyType.CHARACTER && key.isCtrlDown() && ch != null
                && (Character.toLowerCase(ch) == 'c' || Character.toLowerCase(ch) == 'd')) {
            requestClose();
            return;
        }
        if (t == KeyType.ARROW_UP) {
            moveListPrevious();
            return;
        }
        if (t == KeyType.ARROW_DOWN) {
            moveListNext(rowCount);
            return;
        }
        if (t == KeyType.ENTER) {
            acceptListSelection();
            return;
        }
        if (t == KeyType.CHARACTER && ch != null && ch > 0x20 && !key.isCtrlDown() && !key.isAltDown()) {
            mode = Mode.SEARCH;
            searchQuery.setLength(0);
            searchCursorOffset = 0;
            if (ch != '/') {
                searchQuery.append(ch.charValue());
                searchCursorOffset = 1;
                refreshRules();
            }
            selectedIndex = 0;
            scrollOffset = 0;
            invalidate();
        }
    }

    private void moveListPrevious() {
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

    private void moveListNext(int rowCount) {
        if (selectedIndex < rowCount - 1) {
            selectedIndex++;
            adjustScroll();
        }
        invalidate();
    }

    private void acceptListSelection() {
        if (selectedIndex == filtered.size()) openAddInput();
        else openDetail(filtered.get(selectedIndex));
    }

    private void handleSearchKey(KeyStroke key, AtomicBoolean deliver) {
        KeyType t = key.getKeyType();
        deliver.set(false);
        if (t == KeyType.ARROW_UP) {
            if (onFocusHeaderRequest != null) onFocusHeaderRequest.run();
            invalidate();
            return;
        }
        if (t == KeyType.ESCAPE) {
            if (!searchQuery.isEmpty()) {
                searchQuery.setLength(0);
                searchCursorOffset = 0;
                refreshRules();
            } else {
                mode = Mode.LIST;
            }
            invalidate();
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
            } else if (searchCursorOffset > 0) {
                searchQuery.deleteCharAt(searchCursorOffset - 1);
                searchCursorOffset--;
                refreshRules();
            }
            invalidate();
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
        if (t == KeyType.PASTE && key instanceof PasteKeyStroke pks) {
            // This tab is a hand-drawn Panel with no Interactable of its own to
            // hold Lanterna's real GUI focus, so an unhandled PASTE silently
            // falls through to whatever IS focused underneath (the main chat
            // input) instead of landing in the search box. Must consume it here
            // — same fix as AddDirDialog's input field.
            String pasted = pks.getPastedText();
            if (StringUtils.isNotEmpty(pasted)) {
                String flat = pasted.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
                searchQuery.insert(searchCursorOffset, flat);
                searchCursorOffset += flat.length();
                refreshRules();
                invalidate();
            }
            return;
        }
        if (t == KeyType.CHARACTER && key.getCharacter() != null
                && key.getCharacter() >= 0x20 && !key.isCtrlDown() && !key.isAltDown()) {
            searchQuery.insert(searchCursorOffset, key.getCharacter().charValue());
            searchCursorOffset++;
            refreshRules();
            invalidate();
        }
    }

    private void openDetail(PermissionRule rule) {
        this.detailRule = rule;
        this.detailChoiceIdx = 0;
        this.addInputError = null;
        this.mode = Mode.RULE_DETAIL;
        invalidate();
    }

    private void handleDetailKey(KeyStroke key, AtomicBoolean deliver) {
        deliver.set(false);
        ContextKeybindingDispatcher.Result resolved =
            keybindings.resolve(List.of("Select", "Confirmation"), key);
        if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) return;
        if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String value)) {
            boolean deletable = !NON_DELETABLE_SOURCES.contains(detailRule.source());
            switch (value) {
                case "select:cancel", "confirm:no" -> closeDetail();
                case "select:previous", "select:next" -> {
                    if (deletable) {
                        detailChoiceIdx = detailChoiceIdx == 0 ? 1 : 0;
                        invalidate();
                    }
                }
                case "select:accept" -> {
                    if (deletable) {
                        if (detailChoiceIdx == 0) deleteDetailRule();
                        else closeDetail();
                    }
                }
                case "confirm:yes" -> {}
                default -> { }
            }
        }
    }

    private void closeDetail() {
        mode = Mode.LIST;
        invalidate();
    }

    private void deleteDetailRule() {
        PermissionRule rule = detailRule;
        String ruleString = PermissionEngine.permissionRuleToString(rule);
        PermissionGate gate = gateSupplier != null ? gateSupplier.get() : null;
        String cwd = cwdSupplier != null ? cwdSupplier.get() : System.getProperty("user.dir");
        if (rule.source() == RuleSource.USER_SETTINGS
                || rule.source() == RuleSource.PROJECT_SETTINGS
                || rule.source() == RuleSource.LOCAL_SETTINGS) {
            saving = true;
            invalidate();
            UiSettings.removePermissionRuleAsync(cwd, behavior, ruleString, rule.source())
                .whenComplete((_, failure) -> guiInvoker.accept(() -> {
                    if (failure != null) {
                        addInputError = failureMessage(failure);
                        saving = false;
                        invalidate();
                        return;
        }
                    finishDelete(rule, ruleString, gate);
                }));
            return;
        }
        finishDelete(rule, ruleString, gate);
    }

    private void finishDelete(PermissionRule rule, String ruleString, PermissionGate gate) {
        if (gate != null) gate.removeRules(r -> r.equals(rule));
        if (changeRecorder != null) {
            changeRecorder.accept("Deleted " + behavior.name().toLowerCase(Locale.ROOT) + " rule " + ruleString,
                LanternaTheme.inputText());
        }
        mode = Mode.LIST;
        refreshRules();
        saving = false;
        invalidate();
    }

    private void openAddInput() {
        this.addInput.setLength(0);
        this.addCursorOffset = 0;
        this.addInputError = null;
        this.mode = Mode.ADD_INPUT;
        invalidate();
    }

    private void handleAddInputKey(KeyStroke key, AtomicBoolean deliver) {
        deliver.set(false);
        ContextKeybindingDispatcher.Result resolved = keybindings.resolve("Settings", key);
        if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) return;
        if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String value)
                && Strings.CS.equals("confirm:no", value)) {
            mode = Mode.LIST;
            invalidate();
            return;
        }
        KeyType t = key.getKeyType();
        if (t == KeyType.CHARACTER && key.isCtrlDown() && key.getCharacter() != null) {
            char ch = Character.toLowerCase(key.getCharacter());
            if (ch == 'c' || ch == 'd') { mode = Mode.LIST; invalidate(); }
            return;
        }
        if (t == KeyType.ENTER) {
            String trimmed = addInput.toString().trim();
            if (trimmed.isEmpty()) return;
            // Parse just to validate — PermissionEngine.permissionRuleFromString is lenient


            PermissionEngine.permissionRuleFromString(trimmed, behavior, RuleSource.SESSION);
            this.pendingRuleString = trimmed;
            this.destinationIdx = 0;
            this.addInputError = null;
            this.mode = Mode.ADD_DESTINATION;
            invalidate();
            return;
        }
        if (t == KeyType.BACKSPACE) {
            if (addCursorOffset > 0) {
                addInput.deleteCharAt(addCursorOffset - 1);
                addCursorOffset--;
            }
            invalidate();
            return;
        }
        if (t == KeyType.ARROW_LEFT) {
            if (addCursorOffset > 0) addCursorOffset--;
            invalidate();
            return;
        }
        if (t == KeyType.ARROW_RIGHT) {
            if (addCursorOffset < addInput.length()) addCursorOffset++;
            invalidate();
            return;
        }
        if (t == KeyType.PASTE && key instanceof PasteKeyStroke pks) {
            // Same fix as AddDirDialog's input field: this tab has no
            // Interactable of its own to hold real GUI focus, so an unhandled
            // PASTE here would silently leak through to the main chat input
            // behind the panel instead of landing in this rule-string field.
            String pasted = pks.getPastedText();
            if (StringUtils.isNotEmpty(pasted)) {
                String flat = pasted.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
                addInput.insert(addCursorOffset, flat);
                addCursorOffset += flat.length();
                invalidate();
            }
            return;
        }
        if (t == KeyType.CHARACTER && key.getCharacter() != null
                && key.getCharacter() >= 0x20 && !key.isCtrlDown() && !key.isAltDown()) {
            addInput.insert(addCursorOffset, key.getCharacter().charValue());
            addCursorOffset++;
            invalidate();
        }
    }

    private void handleDestinationKey(KeyStroke key, AtomicBoolean deliver) {
        deliver.set(false);
        ContextKeybindingDispatcher.Result resolved =
            keybindings.resolve(List.of("Select", "Confirmation"), key);
        if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) return;
        if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String value)) {
            switch (value) {
                case "select:previous" -> { moveDestination(-1); return; }
                case "select:next" -> { moveDestination(1); return; }
                case "select:accept" -> { saveDestination(); return; }
                case "select:cancel", "confirm:no" -> {
                    mode = Mode.LIST;
                    invalidate();
                    return;
                }
                default -> { }
            }
        }
        KeyType t = key.getKeyType();
        if (t == KeyType.ARROW_UP) {
            moveDestination(-1);
            return;
        }
        if (t == KeyType.ARROW_DOWN) {
            moveDestination(1);
            return;
        }
        if (t == KeyType.ENTER) {
            saveDestination();
        }
    }

    private void moveDestination(int delta) {
        destinationIdx = InlineOverlay.cycleIndex(destinationIdx, delta, DESTINATIONS.size());
        invalidate();
    }

    private void saveDestination() {
        Destination dest = DESTINATIONS.get(destinationIdx);
        String cwd = cwdSupplier != null ? cwdSupplier.get() : System.getProperty("user.dir");
        String ruleString = pendingRuleString;
        PermissionRule newRule = PermissionEngine.permissionRuleFromString(ruleString, behavior, dest.tier());
        PermissionGate gate = gateSupplier != null ? gateSupplier.get() : null;
        saving = true;
        addInputError = null;
        invalidate();
        UiSettings.addPermissionRuleAsync(cwd, behavior, ruleString, dest.tier())
            .whenComplete((_, failure) -> guiInvoker.accept(() -> {
                if (failure != null) {
                    addInputError = failureMessage(failure);
                    saving = false;
                    invalidate();
                    return;
                }
                finishAdd(newRule, gate);
            }));
    }

    private void finishAdd(PermissionRule newRule, PermissionGate gate) {
        if (gate != null) gate.addRules(List.of(newRule));
        recordAddedRule(newRule, gate);
        pendingRuleString = null;
        mode = Mode.LIST;
        refreshRules();
        saving = false;
        invalidate();
    }

    private static String failureMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        return "Failed to update permission settings: "
            + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
    }

    /**
     * Appends "Added … rule …" to the change log and, if the newly-added rule is unreachable
     * (shadowed/blocked by a tool-wide ask/deny rule), also appends the warning.
     */
    private void recordAddedRule(PermissionRule newRule, PermissionGate gate) {
        if (changeRecorder == null) return;
        changeRecorder.accept(
            "Added " + behavior.name().toLowerCase(Locale.ROOT) + " rule " + PermissionEngine.permissionRuleToString(newRule),
            LanternaTheme.inputText());
        if (gate == null) return;
        for (UnreachableRule u : UnreachableRuleDetector.detect(gate.currentContext().rules())) {
            if (!u.rule().equals(newRule)) continue;
            String severity = u.shadowType() == UnreachableRule.ShadowType.DENY ? "blocked" : "shadowed";
            changeRecorder.accept("⚠ Warning: " + u.ruleDisplay() + " is " + severity, LanternaTheme.toolWarning());
            changeRecorder.accept("  " + u.reason(), LanternaTheme.welcomeDim());
            changeRecorder.accept("  Fix: " + u.fix(), LanternaTheme.welcomeDim());
        }
    }

    private void requestClose() {
        if (onCloseRequest != null) onCloseRequest.run();
    }

    private void adjustScroll() {
        if (selectedIndex < scrollOffset) scrollOffset = selectedIndex;
        if (selectedIndex >= scrollOffset + MAX_VISIBLE_OPTIONS) {
            scrollOffset = selectedIndex - MAX_VISIBLE_OPTIONS + 1;
        }
        int rowCount = filtered.size() + 1;
        int maxOffset = Math.max(0, rowCount - MAX_VISIBLE_OPTIONS);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxOffset));
    }

    private int visibleCount() {
        return Math.min(filtered.size() + 1, MAX_VISIBLE_OPTIONS);
    }

    private int listContentRows() {
        return 7 + visibleCount();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Sizing / focus
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        if (!tabVisible) return new TerminalSize(0, 0);
        return super.calculatePreferredSize();
    }

    @Override public Interactable nextFocus(Interactable fromThis) { return tabVisible ? super.nextFocus(fromThis) : null; }
    @Override public Interactable previousFocus(Interactable fromThis) { return tabVisible ? super.previousFocus(fromThis) : null; }

    // ──────────────────────────────────────────────────────────────────────────
    // Test accessors (package-private)
    // ──────────────────────────────────────────────────────────────────────────

    Mode mode() { return mode; }
    int selectedIndex() { return selectedIndex; }
    List<String> filteredRuleStrings() {
        return filtered.stream().map(PermissionEngine::permissionRuleToString).toList();
    }
    PermissionRule detailRule() { return detailRule; }
    boolean savingForTest() { return saving; }

    // ──────────────────────────────────────────────────────────────────────────
    // Renderer
    // ──────────────────────────────────────────────────────────────────────────

    private final class Area extends AbstractComponent<Area> {
        @Override protected ComponentRenderer<Area> createDefaultRenderer() {
            return new Renderer();
        }
    }

    private final class Renderer implements ComponentRenderer<Area> {

        @Override
        public TerminalSize getPreferredSize(Area c) {
            if (!tabVisible) return new TerminalSize(0, 0);
            int rows = switch (mode) {
                case LIST, SEARCH -> listContentRows();
                case RULE_DETAIL -> 10 + (PermissionRuleDescription.describe(detailRule).isPresent() ? 1 : 0)
                    + (addInputError != null ? 1 : 0);
                case ADD_INPUT -> 8 + (addInputError != null ? 1 : 0);
                case ADD_DESTINATION -> 6 + DESTINATIONS.size() * 2
                    + (addInputError != null ? 1 : 0);
            };
            return new TerminalSize(80, rows);
        }

        @Override
        public void drawComponent(TextGUIGraphics g, Area c) {
            if (!tabVisible) return;
            g.fill(' ');
            switch (mode) {
                case LIST, SEARCH -> drawList(g);
                case RULE_DETAIL -> drawDetail(g);
                case ADD_INPUT -> drawAddInput(g);
                case ADD_DESTINATION -> drawDestination(g);
            }
        }

        private void drawList(TextGUIGraphics g) {
            String description = description();
            int boxWidth = description.length();
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, 0, description);
            g.setForegroundColor(mode == Mode.SEARCH
                ? LanternaTheme.suggestion() : LanternaTheme.ghostText());
            g.putString(LEFT_PAD, 1, LanternaDraw.borderedSearchBoxTop(boxWidth));
            g.putString(LEFT_PAD, 2, LanternaDraw.borderedSearchBoxContent(
                mode == Mode.SEARCH, searchQuery.toString(), searchCursorOffset, boxWidth));
            g.putString(LEFT_PAD, 3, LanternaDraw.borderedSearchBoxBottom(boxWidth));

            int row = 5;
            if (scrollOffset > 0) {
                g.setForegroundColor(LanternaTheme.welcomeDim());
                g.putString(LEFT_PAD, row - 1, "↑ " + scrollOffset + " more above");
            }

            int rowCount = filtered.size() + 1;
            int end = Math.min(rowCount, scrollOffset + MAX_VISIBLE_OPTIONS);
            for (int i = scrollOffset; i < end; i++) {
                boolean selected = !headerFocused && mode == Mode.LIST && i == selectedIndex;
                String label = i == filtered.size()
                    ? "Add a new rule…"
                    : PermissionEngine.permissionRuleToString(filtered.get(i));
                drawNumberedItem(g, row, i + 1, label, selected);
                row++;
            }

            int below = rowCount - end;
            if (below > 0) {
                g.setForegroundColor(LanternaTheme.welcomeDim());
                g.putString(LEFT_PAD, row, "↓ " + below + " more below");
            }
            row++;
            g.setForegroundColor(LanternaTheme.welcomeDim());
            String footer = mode == Mode.SEARCH
                ? "Type to filter · Enter/↓ to select · ↑ to tabs · Esc to clear"
                : headerFocused
                    ? "←/→ to switch · ↓ to select · Esc to cancel"
                    : "↑/↓ to navigate · Enter to select · ←/→ to switch · Esc to cancel";
            g.putString(LEFT_PAD, row, footer);
        }

        private void drawNumberedItem(TextGUIGraphics g, int row, int number,
                                      String label, boolean selected) {
            g.setForegroundColor(selected
                ? LanternaTheme.inputText() : LanternaTheme.welcomeDim());
            if (selected) g.enableModifiers(SGR.BOLD);
            g.putString(LEFT_PAD, row, selected ? "> " : "  ");
            g.putString(LEFT_PAD + 2, row, number + ". " + label);
            g.disableModifiers(SGR.BOLD);
        }

        private String description() {
            return switch (behavior) {
                case ALLOW -> "Claude Code won't ask before using allowed tools.";
                case ASK -> "Claude Code will always ask for confirmation before using these tools.";
                case DENY -> "Claude Code will always reject requests to use denied tools.";
                case PASSTHROUGH -> "";
            };
        }

        private void drawDetail(TextGUIGraphics g) {
            int cols = g.getSize().getColumns();
            g.setForegroundColor(LanternaTheme.divider());
            g.putString(0, 0, "─".repeat(Math.max(0, cols)));

            boolean deletable = !NON_DELETABLE_SOURCES.contains(detailRule.source());
            g.setForegroundColor(deletable ? LanternaTheme.toolError() : LanternaTheme.permission());
            g.enableModifiers(SGR.BOLD);
            g.putString(LEFT_PAD, 1, deletable
                ? "Delete " + behavior.name().toLowerCase(Locale.ROOT) + " tool?"
                : "Rule details");
            g.disableModifiers(SGR.BOLD);

            g.setForegroundColor(LanternaTheme.inputText());
            g.enableModifiers(SGR.BOLD);
            g.putString(LEFT_PAD, 3, PermissionEngine.permissionRuleToString(detailRule));
            g.disableModifiers(SGR.BOLD);

            int row = 4;
            Optional<String> description = PermissionRuleDescription.describe(detailRule);
            if (description.isPresent()) {
                g.setForegroundColor(LanternaTheme.welcomeDim());
                g.putString(LEFT_PAD, row, description.get());
                row++;
            }

            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, row, "From " + detailRule.source().displayName());
            row += 2;

            if (deletable) {
                g.setForegroundColor(LanternaTheme.inputText());
                g.putString(LEFT_PAD, row, "Are you sure you want to delete this permission rule?");
                String[] labels = {"Yes", "No"};
                for (int i = 0; i < labels.length; i++) {
                    boolean selected = i == detailChoiceIdx;
                    g.setForegroundColor(selected ? LanternaTheme.suggestion() : LanternaTheme.inputText());
                    g.putString(LEFT_PAD, row + 1 + i, (selected ? "❯ " : "  ") + labels[i]);
                }
                if (addInputError != null) {
                    g.setForegroundColor(LanternaTheme.toolError());
                    g.putString(LEFT_PAD, row + 4, addInputError);
                }
            } else {
                g.setForegroundColor(LanternaTheme.welcomeDim());
                g.putString(LEFT_PAD, row, "This rule is configured by managed settings and cannot be modified.");
                g.putString(LEFT_PAD, row + 1, "Contact your system administrator for more information.");
            }
        }

        private void drawAddInput(TextGUIGraphics g) {
            int cols = g.getSize().getColumns();
            g.setForegroundColor(LanternaTheme.divider());
            g.putString(0, 0, "─".repeat(Math.max(0, cols)));

            g.setForegroundColor(LanternaTheme.permission());
            g.enableModifiers(SGR.BOLD);
            g.putString(LEFT_PAD, 1, "Add " + behavior.name().toLowerCase(Locale.ROOT) + " permission rule");
            g.disableModifiers(SGR.BOLD);

            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, 3, "Permission rules are a tool name, optionally followed by a specifier");
            g.putString(LEFT_PAD, 4, "in parentheses. e.g., WebFetch or Bash(ls:*)");

            g.setForegroundColor(LanternaTheme.inputText());
            String input = addInput.toString();
            int co = Math.min(addCursorOffset, input.length());
            String shown = input.substring(0, co) + "█" + input.substring(co);
            if (input.isEmpty()) {
                g.putString(LEFT_PAD, 6, "› █");
                g.setForegroundColor(LanternaTheme.ghostText());
                g.putString(LEFT_PAD + 3, 6, "Enter permission rule…");
            } else {
                g.putString(LEFT_PAD, 6, "› " + shown);
            }

            int row = 7;
            if (addInputError != null) {
                g.setForegroundColor(LanternaTheme.toolError());
                g.putString(LEFT_PAD, row, addInputError);
                row++;
            }
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, row, "Enter to submit · Esc to cancel");
        }

        private void drawDestination(TextGUIGraphics g) {
            int cols = g.getSize().getColumns();
            g.setForegroundColor(LanternaTheme.divider());
            g.putString(0, 0, "─".repeat(Math.max(0, cols)));

            g.setForegroundColor(LanternaTheme.permission());
            g.enableModifiers(SGR.BOLD);
            g.putString(LEFT_PAD, 1, "Add " + behavior.name().toLowerCase(Locale.ROOT) + " permission rule");
            g.disableModifiers(SGR.BOLD);

            g.setForegroundColor(LanternaTheme.inputText());
            g.putString(LEFT_PAD, 2, pendingRuleString);

            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, 4, "Where should this rule be saved?");

            for (int i = 0; i < DESTINATIONS.size(); i++) {
                Destination d = DESTINATIONS.get(i);
                boolean selected = i == destinationIdx;
                int row = 6 + i * 2;
                g.setForegroundColor(selected ? LanternaTheme.suggestion() : LanternaTheme.inputText());
                g.putString(LEFT_PAD, row, (selected ? "❯ " : "  ") + d.label());
                g.setForegroundColor(LanternaTheme.welcomeDim());
                g.putString(LEFT_PAD + 2, row + 1, d.description());
            }
            if (addInputError != null) {
                g.setForegroundColor(LanternaTheme.toolError());
                g.putString(LEFT_PAD, 6 + DESTINATIONS.size() * 2, addInputError);
            }
        }
    }
}
