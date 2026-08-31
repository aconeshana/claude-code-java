package com.claudecode.ui.lanterna.features.settings;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.permissions.PermissionGate;
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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.ui.lanterna.dialog.AddDirDialog;
import com.claudecode.ui.lanterna.input.ContextKeybindingDispatcher;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import org.apache.commons.lang3.StringUtils;

/**
 * "Workspace" tab body for {@link PermissionsPanel} — lists {@link PermissionGate}'s current
 * additional working directories, with add/remove.
 */
public final class WorkspaceTab extends Panel {

    enum Mode { LIST, REMOVE_CONFIRM }

    private static final int LEFT_PAD = 2;
    private static final int MAX_VISIBLE_OPTIONS = 10;
    private static final String DESCRIPTION_LINE_ONE =
        "Claude Code can read files in the workspace, and make edits when auto-accept";
    private static final String DESCRIPTION_LINE_TWO = "edits is on.";
    private static final int WORKSPACE_VALUE_COLUMN = 51;

    private boolean tabVisible;
    private boolean headerFocused;
    private Mode mode = Mode.LIST;

    private Supplier<PermissionGate> gateSupplier;
    private Supplier<String> originalCwdSupplier;
    private Function<String, AddDirDialog.ValidationOutcome> dirValidator;
    private BiConsumer<String, Boolean> onAddDirectoryResult;
    private Runnable onFocusHeaderRequest;
    private Runnable onCloseRequest;
    private BiConsumer<String, TextColor> changeRecorder;

    private final AddDirDialog addDirDialog = new AddDirDialog();

    private List<Path> directories = List.of();
    private int selectedIndex;
    private int scrollOffset;
    private Path removeCandidate;
    private int confirmIdx;
    private final ContextKeybindingDispatcher keybindings =
        new ContextKeybindingDispatcher();

    WorkspaceTab() {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        Area area = new Area();
        area.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(area);
        addDirDialog.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(addDirDialog);
    }

    void setOnFocusHeaderRequest(Runnable callback) { this.onFocusHeaderRequest = callback; }
    void setOnCloseRequest(Runnable callback) { this.onCloseRequest = callback; }
    void setChangeRecorder(BiConsumer<String, TextColor> recorder) { this.changeRecorder = recorder; }
    void setKeybindingsStore(UserKeybindingsStore store) {
        keybindings.setStore(store);
        addDirDialog.setKeybindingsStore(store);
    }

    void setGuiInvoker(Consumer<Runnable> guiInvoker) {
        addDirDialog.setGuiInvoker(guiInvoker);
    }

    void bind(Supplier<PermissionGate> gateSupplier, Supplier<String> originalCwdSupplier,
              Function<String, AddDirDialog.ValidationOutcome> dirValidator,
              BiConsumer<String, Boolean> onAddDirectoryResult) {
        this.gateSupplier = gateSupplier;
        this.originalCwdSupplier = originalCwdSupplier;
        this.dirValidator = dirValidator;
        this.onAddDirectoryResult = onAddDirectoryResult;
    }

    void setTabVisible(boolean visible) {
        this.tabVisible = visible;
        invalidate();
    }

    void setHeaderFocused(boolean focused) {
        headerFocused = focused;
        invalidate();
    }

    /** Re-reads the live directory list and resets to LIST mode. */
    void reload() {
        this.mode = Mode.LIST;
        this.selectedIndex = 0;
        refreshDirectories();
        invalidate();
    }

    private void refreshDirectories() {
        PermissionGate gate = gateSupplier != null ? gateSupplier.get() : null;
        this.directories = gate != null
            ? new ArrayList<>(gate.currentContext().additionalDirs().keySet())
            : List.of();
        int rowCount = directories.size() + 1; // +1 = "Add directory..."
        if (selectedIndex >= rowCount) selectedIndex = rowCount - 1;
        if (selectedIndex < 0) selectedIndex = 0;
        adjustScroll(rowCount);
    }

    private String originalCwd() {
        String cwd = originalCwdSupplier != null ? originalCwdSupplier.get() : null;
        if (StringUtils.isNotBlank(cwd)) return cwd;
        String fallbackCwd = System.getProperty("user.dir");
        return fallbackCwd != null ? fallbackCwd : "";
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Key handling
    // ──────────────────────────────────────────────────────────────────────────

    void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (addDirDialog.isActive()) {
            addDirDialog.handleKey(key, deliver);
            return;
        }
        switch (mode) {
            case LIST -> handleListKey(key, deliver);
            case REMOVE_CONFIRM -> handleConfirmKey(key, deliver);
        }
    }

    private void handleListKey(KeyStroke key, AtomicBoolean deliver) {
        deliver.set(false);
        KeyType t = key.getKeyType();
        Character ch = key.getCharacter();
        int rowCount = directories.size() + 1;

        ContextKeybindingDispatcher.Result resolved =
            keybindings.resolve(List.of("Select", "Settings"), key);
        if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) return;
        if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String value)) {
            switch (value) {
                case "select:previous" -> { moveListPrevious(); return; }
                case "select:next" -> { moveListNext(rowCount); return; }
                case "select:accept" -> { acceptListSelection(); return; }
                case "select:cancel", "confirm:no" -> {
                    if (onCloseRequest != null) onCloseRequest.run();
                    return;
                }
                default -> { }
            }
        }

        if (t == KeyType.CHARACTER && key.isCtrlDown() && ch != null
                && (Character.toLowerCase(ch) == 'c' || Character.toLowerCase(ch) == 'd')) {
            if (onCloseRequest != null) onCloseRequest.run();
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
        }
    }

    private void moveListPrevious() {
        if (selectedIndex == 0) {
            if (onFocusHeaderRequest != null) onFocusHeaderRequest.run();
        } else selectedIndex--;
        adjustScroll(directories.size() + 1);
        invalidate();
    }

    private void moveListNext(int rowCount) {
        if (selectedIndex < rowCount - 1) selectedIndex++;
        adjustScroll(rowCount);
        invalidate();
    }

    private void adjustScroll(int rowCount) {
        if (selectedIndex < scrollOffset) scrollOffset = selectedIndex;
        if (selectedIndex >= scrollOffset + MAX_VISIBLE_OPTIONS) {
            scrollOffset = selectedIndex - MAX_VISIBLE_OPTIONS + 1;
        }
        int maxOffset = Math.max(0, rowCount - MAX_VISIBLE_OPTIONS);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxOffset));
    }

    private void acceptListSelection() {
        if (selectedIndex == directories.size()) openAddDirectory();
        else openRemoveConfirm(directories.get(selectedIndex));
    }

    private void openAddDirectory() {
        addDirDialog.show(null, dirValidator, (path, remember) -> {
            if (onAddDirectoryResult != null) onAddDirectoryResult.accept(path, remember);
            if (remember != null && changeRecorder != null) {
                changeRecorder.accept("Added directory " + path + " to workspace"
                    + (remember ? " and saved to local settings" : " for this session"),
                    LanternaTheme.inputText());
            }
            refreshDirectories();
            invalidate();
        });
    }

    private void openRemoveConfirm(Path path) {
        this.removeCandidate = path;
        this.confirmIdx = 0;
        this.mode = Mode.REMOVE_CONFIRM;
        invalidate();
    }

    private void handleConfirmKey(KeyStroke key, AtomicBoolean deliver) {
        deliver.set(false);
        ContextKeybindingDispatcher.Result resolved =
            keybindings.resolve(List.of("Select", "Confirmation"), key);
        if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) return;
        if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String value)) {
            switch (value) {
                case "select:previous", "select:next" -> { toggleConfirm(); return; }
                case "select:accept" -> { acceptConfirm(); return; }
                case "select:cancel", "confirm:no" -> {
                    mode = Mode.LIST;
                    invalidate();
                    return;
                }
                default -> { }
            }
        }
        KeyType t = key.getKeyType();
        if (t == KeyType.ARROW_UP || t == KeyType.ARROW_DOWN) {
            toggleConfirm();
            return;
        }
        if (t == KeyType.ENTER) {
            acceptConfirm();
        }
    }

    private void toggleConfirm() {
        confirmIdx = confirmIdx == 0 ? 1 : 0;
        invalidate();
    }

    private void acceptConfirm() {
        if (confirmIdx == 0) {
            PermissionGate gate = gateSupplier != null ? gateSupplier.get() : null;
            if (gate != null) gate.removeDirectories(List.of(removeCandidate));
            if (changeRecorder != null) {
                changeRecorder.accept("Removed directory " + removeCandidate + " from workspace",
                    LanternaTheme.inputText());
            }
        }
        mode = Mode.LIST;
        refreshDirectories();
        invalidate();
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
    int scrollOffset() { return scrollOffset; }
    List<Path> directories() { return directories; }
    AddDirDialog addDirDialog() { return addDirDialog; }

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
            if (!tabVisible || addDirDialog.isActive()) return new TerminalSize(0, 0);
            int rows = mode == Mode.LIST
                ? 8 + Math.min(MAX_VISIBLE_OPTIONS, directories.size() + 1)
                : 8;
            return new TerminalSize(80, rows);
        }

        @Override
        public void drawComponent(TextGUIGraphics g, Area c) {
            if (!tabVisible || addDirDialog.isActive()) return;
            g.fill(' ');
            if (mode == Mode.LIST) {
                drawList(g);
            } else {
                drawConfirm(g);
            }
        }

        private void drawList(TextGUIGraphics g) {
            int cols = g.getSize().getColumns();
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, 0, DESCRIPTION_LINE_ONE);
            g.putString(LEFT_PAD, 1, DESCRIPTION_LINE_TWO);

            String cwd = StringUtils.defaultString(originalCwd());
            int itemColumn = LEFT_PAD + 2;
            int valueColumn = Math.min(WORKSPACE_VALUE_COLUMN,
                Math.max(itemColumn + 12, cols - 29));
            int pathWidth = Math.max(1, valueColumn - itemColumn);
            String prefixedCwd = "-  " + cwd;
            g.setForegroundColor(LanternaTheme.inputText());
            if (prefixedCwd.length() <= pathWidth) {
                g.putString(itemColumn, 3, prefixedCwd);
            } else {
                g.putString(itemColumn, 3, "-");
                g.putString(itemColumn, 4,
                    FormatUtils.truncateNoEllipsis(cwd, pathWidth));
            }
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(valueColumn, 3, "(Original working");
            g.putString(valueColumn, 4, "directory)");

            int row = 5;
            int rowCount = directories.size() + 1;
            int end = Math.min(rowCount, scrollOffset + MAX_VISIBLE_OPTIONS);
            for (int i = scrollOffset; i < end; i++) {
                boolean selected = !headerFocused && i == selectedIndex;
                String label = i == directories.size()
                    ? "Add directory…"
                    : directories.get(i).toString();
                drawNumberedItem(g, row, i + 1, label, selected);
                row++;
            }
            row += 2;
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, row, headerFocused
                ? "←/→ to switch · ↓ to select · Esc to cancel"
                : "↑/↓ to navigate · Enter to select · ←/→ to switch · Esc to cancel");
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

        private void drawConfirm(TextGUIGraphics g) {
            int cols = g.getSize().getColumns();
            g.setForegroundColor(LanternaTheme.divider());
            g.putString(0, 0, "─".repeat(Math.max(0, cols)));

            g.setForegroundColor(LanternaTheme.toolError());
            g.enableModifiers(SGR.BOLD);
            g.putString(LEFT_PAD, 1, "Remove directory from workspace?");
            g.disableModifiers(SGR.BOLD);

            g.setForegroundColor(LanternaTheme.inputText());
            g.putString(LEFT_PAD, 3, removeCandidate.toString());
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, 4, "Claude Code will no longer have access to files in this directory.");

            String[] labels = {"Yes", "No"};
            for (int i = 0; i < labels.length; i++) {
                boolean selected = i == confirmIdx;
                g.setForegroundColor(selected ? LanternaTheme.suggestion() : LanternaTheme.inputText());
                g.putString(LEFT_PAD, 6 + i, (selected ? "❯ " : "  ") + labels[i]);
            }
        }
    }
}
