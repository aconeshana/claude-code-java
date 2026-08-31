package com.claudecode.ui.lanterna.dialog;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.runtime.memory.MemoryCatalog;
import com.claudecode.runtime.memory.MemoryCatalog.File;
import com.claudecode.runtime.memory.MemoryCatalog.Scope;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.core.text.StringUtils;
import com.googlecode.lanterna.SGR;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.input.ContextKeybindingDispatcher;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * Inline picker for CLAUDE.md memory files.
 */
public final class MemorySelectorDialog extends Panel implements InlineOverlay {

    private static final int LEFT_PAD = 2;

    /** Callback payload: file the user picked, or empty if they cancelled. */
    public record Result(Path selectedFile, boolean cancelled, boolean openFolder) {
        public static Result cancel() { return new Result(null, true, false); }
        public static Result of(Path file) { return new Result(file, false, false); }
        public static Result folder(Path dir) { return new Result(dir, false, true); }
    }

    private enum State { HIDDEN, LOADING, PICKING }

    /** One row in the picker — either an existing memory file, a "(new)" default, or a folder-open action. */
    private record Row(Path path, Scope type, Path parent, boolean exists,
                       String label, String description, boolean isFolderOpen) {
        Row(Path path, Scope type, Path parent, boolean exists, String label, String description) {
            this(path, type, parent, exists, label, description, false);
        }
    }

    private final MemoryCatalog catalog;
    private final Path cwd;
    private final Path homeDir;
    private final Path configHome;
    private final Body body;
    private final ContextKeybindingDispatcher keybindings = new ContextKeybindingDispatcher();
    private Consumer<Runnable> guiInvoker = Runnable::run;

    private volatile State state = State.HIDDEN;
    private volatile List<Row> rows = List.of();
    private volatile List<File> memoryFiles = List.of();
    private int focusedIdx = 0;
    /** null = Select list owns focus; 0/1 = auto-memory/auto-dream row. */
    private Integer focusedToggle;
    private volatile boolean autoMemoryOn;
    private volatile boolean autoDreamOn;
    private volatile boolean showDreamRow;
    private volatile boolean dreamRunning;
    private volatile long lastDreamAt;

    public void setKeybindingsStore(UserKeybindingsStore store) {
        keybindings.setStore(store);
    }

    /** Posts scan results onto the owning Lanterna GUI thread. */
    public void setGuiInvoker(Consumer<Runnable> guiInvoker) {
        this.guiInvoker = guiInvoker != null ? guiInvoker : Runnable::run;
    }


    private static volatile Path lastPickedPath;

    private Consumer<Result> onResult;

    /**
     * Per-agent memory folder entry — one row appended after file rows so the
     * user can browse {@code ~/.claude/agent-memory/<type>/} etc.
     *
     * @param label       display text (e.g. {@code "Open <b>Explore</b> agent memory"});
     *                    plain-text here — Lanterna doesn't render inline bold
     * @param dir         absolute directory path opened via the platform folder opener
     * @param description right-aligned hint (e.g. {@code "user scope"})
     */
    public record AgentMemoryFolder(String label, Path dir, String description) {}

    private final Supplier<List<AgentMemoryFolder>> agentFoldersSupplier;

    public MemorySelectorDialog(MemoryCatalog catalog, Path cwd, Path homeDir) {
        this(catalog, cwd, homeDir, List::of);
    }


    public MemorySelectorDialog(MemoryCatalog catalog, Path cwd, Path homeDir,
                                Supplier<List<AgentMemoryFolder>> agentFoldersSupplier) {
        this(catalog, cwd, homeDir, homeDir.resolve(".claude"), agentFoldersSupplier);
    }

    public MemorySelectorDialog(MemoryCatalog catalog, Path cwd, Path homeDir,
                                Path configHome,
                                Supplier<List<AgentMemoryFolder>> agentFoldersSupplier) {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        this.catalog = catalog != null ? catalog : MemoryCatalog.empty();
        this.cwd = cwd;
        this.homeDir = homeDir;
        this.configHome = configHome;
        this.agentFoldersSupplier = agentFoldersSupplier != null ? agentFoldersSupplier : List::of;
        this.body = new Body();
        this.body.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(body);
    }

    /**
     * Activate. Must be called from the GUI thread; the scan itself is off-loaded
     * to a virtual thread so a large tree doesn't stall rendering.
     */
    public synchronized void show(Consumer<Result> onResult) {
        this.onResult = onResult;
        this.state = State.LOADING;
        this.rows = List.of();
        this.memoryFiles = List.of();
        this.focusedIdx = 0;
        this.focusedToggle = null;
        this.autoMemoryOn = catalog.autoMemoryEnabled();
        this.autoDreamOn = catalog.autoDreamEnabled();
        this.showDreamRow = autoMemoryOn;
        this.dreamRunning = catalog.autoDreamRunning();
        this.lastDreamAt = showDreamRow ? catalog.lastDreamAtMillis(cwd) : 0L;
        invalidate();


        catalog.clearCache();

        Thread.ofVirtual().name("memory-scan").start(this::loadRows);
    }

    @Override public boolean isActive() { return state != State.HIDDEN; }

    /**
     * Scan memory files, merge in "(new)" defaults for missing User/Project
     * CLAUDE.md, and hand off to the PICKING state.
     */
    private void loadRows() {
        try {
            List<File> found = catalog.scan(cwd);
            List<Row> built = buildRows(found);
            guiInvoker.accept(() -> applyLoadedRows(List.copyOf(found), built));
        } catch (Throwable _) {
            // Scan failure → still open picker with just the "(new)" defaults so
            // the user isn't blocked from creating a new memory file.
            List<Row> fallback = buildRows(List.of());
            guiInvoker.accept(() -> applyLoadedRows(List.of(), fallback));
        }
    }

    private void applyLoadedRows(List<File> files, List<Row> loadedRows) {
        memoryFiles = files;
        rows = loadedRows;
        focusedIdx = initialFocusIndex(loadedRows);
        state = State.PICKING;
        invalidate();
    }


    private int initialFocusIndex(List<Row> built) {
        Path last = lastPickedPath;
        if (last == null) return 0;
        for (int i = 0; i < built.size(); i++) {
            if (last.equals(built.get(i).path())) return i;
        }
        return 0;
    }


    private List<Row> buildRows(List<File> found) {
        Path userClaudeMd = configHome.resolve("CLAUDE.md");
        Path projectClaudeMd = cwd.toAbsolutePath().normalize().resolve("CLAUDE.md");

        boolean hasUser = found.stream().anyMatch(f -> f.path().equals(userClaudeMd));
        boolean hasProject = found.stream().anyMatch(f -> f.path().equals(projectClaudeMd));

        List<Row> out = new ArrayList<>();
        for (File file : found) {
            String description = describeExisting(file, projectClaudeMd);
            String label = labelForExisting(file, userClaudeMd, projectClaudeMd);
            out.add(new Row(file.path(), file.scope(), file.parent(), true, label, description));
        }
        if (!hasUser) {
            out.add(new Row(userClaudeMd, Scope.USER, null, false,
                "User memory (new)", "Saved in ~/.claude/CLAUDE.md"));
        }
        if (!hasProject) {
            out.add(new Row(projectClaudeMd, Scope.PROJECT, null, false,
                "Project memory (new)", "Saved in ./CLAUDE.md"));
        }


        // these after the file list when auto-memory is enabled.
        if (autoMemoryOn && catalog.autoMemoryDirectory(cwd) != null) {
            Path autoMemDir = catalog.autoMemoryDirectory(cwd);
            out.add(new Row(autoMemDir, Scope.USER, null, true,
                "Open auto-memory folder", "", true));
        }

        if (autoMemoryOn && catalog.teamMemoryEnabled()
                && catalog.teamMemoryDirectory(cwd) != null) {
            out.add(new Row(catalog.teamMemoryDirectory(cwd), Scope.USER, null, true,
                "Open team memory folder", "", true));
        }

        if (autoMemoryOn) {
            for (AgentMemoryFolder f : agentFoldersSupplier.get()) {
                out.add(new Row(f.dir(), Scope.USER, null, true,
                    f.label(), f.description() == null ? "" : f.description(), true));
            }
        }
        return out;
    }

    /**
     * User/Project defaults get friendly labels; nested @-imports and rules files show a short relative
     * path with a └ indent marker.
     */
    private String labelForExisting(File file, Path userClaudeMd, Path projectClaudeMd) {
        if (file.scope() == Scope.USER && file.parent() == null && file.path().equals(userClaudeMd)) {
            return "User memory";
        }
        if (file.scope() == Scope.PROJECT && file.parent() == null && file.path().equals(projectClaudeMd)) {
            return "Project memory";
        }
        String display = displayPath(file.path());
        return file.parent() != null ? "  └ " + display : display;
    }

    private String describeExisting(File file, Path projectClaudeMd) {


        //   4. else                          → ""
        if (file.parent() != null) return "@-imported";
        if (file.scope() == Scope.USER) {
            return "Saved in ~/.claude/CLAUDE.md";
        }
        if (file.scope() == Scope.PROJECT && file.path().equals(projectClaudeMd)) {
            boolean git = Files.isDirectory(cwd.resolve(".git"));
            return (git ? "Checked in at " : "Saved in ") + "./CLAUDE.md";
        }
        return "";
    }

    /** Path relative to cwd for project files, ~ for HOME, else raw absolute. */
    private String displayPath(Path abs) {
        Path normalized = abs.toAbsolutePath().normalize();
        Path cwdAbs = cwd.toAbsolutePath().normalize();
        if (normalized.startsWith(cwdAbs)) {
            Path relative = cwdAbs.relativize(normalized);
            return "./" + relative;
        }
        Path homeAbs = homeDir.toAbsolutePath().normalize();
        if (normalized.startsWith(homeAbs)) {
            return "~/" + homeAbs.relativize(normalized);
        }
        return normalized.toString();
    }

    @Override
    public synchronized void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (state == State.HIDDEN) return;
        // LOADING swallows keys.
        if (state == State.LOADING) { deliver.set(false); return; }
        KeyType t = key.getKeyType();
        List<String> contexts = focusedToggle != null
            ? List.of("Confirmation", "Select")
            : List.of("Select", "Confirmation");
        ContextKeybindingDispatcher.Result resolved = keybindings.resolve(contexts, key);
        if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) {
            deliver.set(false);
            return;
        }
        if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String value)
                && dispatchKeybindingAction(value)) {
            deliver.set(false);
            return;
        }
        if (focusedToggle != null) {
            if (t == KeyType.ARROW_UP) {
                focusedToggle = Math.max(0, focusedToggle - 1);
                invalidate();
                deliver.set(false);
                return;
            }
            if (t == KeyType.ARROW_DOWN) {
                focusedToggle = focusedToggle < toggleCount() - 1
                    ? focusedToggle + 1 : null;
                invalidate();
                deliver.set(false);
                return;
            }
            if (t == KeyType.ENTER) {
                if (focusedToggle == 0) toggleAutoMemory();
                else if (focusedToggle == 1) toggleAutoDream();
                deliver.set(false);
                return;
            }
            if (t == KeyType.ESCAPE) {
                resolve(Result.cancel());
                deliver.set(false);
                return;
            }
        }
        if (t == KeyType.ARROW_UP) {
            if (focusedIdx == 0) {
                focusedToggle = toggleCount() - 1;
            } else if (!rows.isEmpty()) {
                focusedIdx--;
            }
            invalidate();
            deliver.set(false);
            return;
        }
        if (t == KeyType.ARROW_DOWN) {
            if (!rows.isEmpty()) focusedIdx = (focusedIdx + 1) % rows.size();
            invalidate();
            deliver.set(false);
            return;
        }
        if (t == KeyType.ENTER) {
            if (!rows.isEmpty()) {
                Row row = rows.get(focusedIdx);
                if (!row.isFolderOpen()) lastPickedPath = row.path();
                resolve(row.isFolderOpen() ? Result.folder(row.path()) : Result.of(row.path()));
            }
            deliver.set(false);
            return;
        }
        if (t == KeyType.ESCAPE) {
            resolve(Result.cancel());
            deliver.set(false);
        }
    }

    private boolean dispatchKeybindingAction(String action) {
        return switch (action) {
            case "confirm:no", "select:cancel" -> {
                resolve(Result.cancel());
                yield true;
            }
            case "confirm:yes" -> {
                if (focusedToggle == null) yield false;
                if (focusedToggle == 0) toggleAutoMemory();
                else if (focusedToggle == 1) toggleAutoDream();
                yield true;
            }
            case "select:previous" -> {
                if (focusedToggle != null) {
                    focusedToggle = Math.max(0, focusedToggle - 1);
                } else if (focusedIdx == 0) {
                    focusedToggle = toggleCount() - 1;
                } else if (!rows.isEmpty()) {
                    focusedIdx--;
                }
                invalidate();
                yield true;
            }
            case "select:next" -> {
                if (focusedToggle != null) {
                    focusedToggle = focusedToggle < toggleCount() - 1
                        ? focusedToggle + 1 : null;
                } else if (!rows.isEmpty()) {
                    focusedIdx = (focusedIdx + 1) % rows.size();
                }
                invalidate();
                yield true;
            }
            case "select:accept" -> {
                if (focusedToggle != null || rows.isEmpty()) yield false;
                Row row = rows.get(focusedIdx);
                if (!row.isFolderOpen()) lastPickedPath = row.path();
                resolve(row.isFolderOpen() ? Result.folder(row.path()) : Result.of(row.path()));
                yield true;
            }
            default -> false;
        };
    }

    private synchronized void toggleAutoMemory() {
        autoMemoryOn = !autoMemoryOn;
        catalog.setAutoMemoryEnabled(autoMemoryOn);
        rows = buildRows(memoryFiles);
        if (focusedIdx >= rows.size()) focusedIdx = Math.max(0, rows.size() - 1);
        invalidate();
    }

    private synchronized void toggleAutoDream() {
        autoDreamOn = !autoDreamOn;
        catalog.setAutoDreamEnabled(autoDreamOn);
        invalidate();
    }

    private synchronized void resolve(Result r) {
        if (state == State.HIDDEN) return;
        Consumer<Result> cb = onResult;
        state = State.HIDDEN;
        onResult = null;
        rows = List.of();
        invalidate();
        if (cb != null) cb.accept(r);
    }

    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        if (state == State.HIDDEN) return new TerminalSize(0, 0);
        int listRows = state == State.LOADING ? 1 : Math.max(1, rows.size());
        int toggleRows = toggleCount() + 1; // toggle block + marginBottom
        // Layout row indices:
        //   0 divider · 1 "Memory" · 2 blank
        //   [3..4] auto-memory row + blank (when enabled)
        //   [listStart..listStart+listRows-1] items
        //   +1 blank · +1 Learn more · +1 blank · +1 footer  = 4 tail rows
        int rowsTotal = 3 + toggleRows + listRows + 4;
        return new TerminalSize(72, rowsTotal);
    }

    @Override public Interactable nextFocus(Interactable fromThis) { return isActive() ? super.nextFocus(fromThis) : null; }
    @Override public Interactable previousFocus(Interactable fromThis) { return isActive() ? super.previousFocus(fromThis) : null; }

    // Test-facing accessors — package-private on purpose so tests can drive
    // through the state machine without going through a real GUI thread.
    int rowCount() { return rows.size(); }
    int focusedIndex() { return focusedIdx; }
    Integer focusedToggle() { return focusedToggle; }
    int toggleCount() { return showDreamRow ? 2 : 1; }
    boolean autoMemoryOn() { return autoMemoryOn; }
    boolean autoDreamOn() { return autoDreamOn; }
    String dreamStatus() {
        if (dreamRunning) return "running";
        if (lastDreamAt == 0L) return "never";
        return "last ran " + FormatUtils.formatRelativeTimeAgo(Instant.ofEpochMilli(lastDreamAt),
            FormatUtils.RelativeTimeStyle.NARROW);
    }

    enum PublicState { HIDDEN_S, LOADING_S, PICKING_S }
    /** Test-only accessor for external assertion. */
    PublicState visibleState() {
        return switch (state) {
            case HIDDEN -> PublicState.HIDDEN_S;
            case LOADING -> PublicState.LOADING_S;
            case PICKING -> PublicState.PICKING_S;
        };
    }

    // ── Rendering ────────────────────────────────────────────────────────

    private final class Body extends AbstractComponent<Body> {
        @Override protected ComponentRenderer<Body> createDefaultRenderer() {
            return new BodyRenderer();
        }
    }

    private final class BodyRenderer implements ComponentRenderer<Body> {
        @Override public TerminalSize getPreferredSize(Body c) {
            return isActive() ? calculatePreferredSize() : new TerminalSize(0, 0);
        }

        @Override public void drawComponent(TextGUIGraphics g, Body c) {
            if (state == State.HIDDEN) return;
            g.fill(' ');
            g.setForegroundColor(LanternaTheme.divider());
            g.putString(0, 0, "─".repeat(Math.max(0, g.getSize().getColumns())));


            g.setForegroundColor(LanternaTheme.statusCost());
            g.enableModifiers(SGR.BOLD);
            g.putString(LEFT_PAD, 1, "Memory");
            g.disableModifiers(SGR.BOLD);

            int toggleRow = 3;
            drawToggle(g, toggleRow, 0,
                "Auto-memory: " + (autoMemoryOn ? "on" : "off"));
            if (showDreamRow) {
                String status = dreamStatus();
                String text = "Auto-dream: " + (autoDreamOn ? "on" : "off")
                    + (org.apache.commons.lang3.StringUtils.isBlank(status) ? "" : " · " + status)
                    + (!dreamRunning && autoDreamOn ? " · /dream to run" : "");
                drawToggle(g, toggleRow + 1, 1, text);
            }
            int listStart = 3 + toggleCount() + 1;

            if (state == State.LOADING) {
                g.setForegroundColor(LanternaTheme.welcomeDim());
                g.putString(LEFT_PAD, listStart, "◐ Scanning memory files…");
                return;
            }

            // PICKING: list + "Learn more:" + footer hint.
            int cols = g.getSize().getColumns();

            int maxIndexWidth = String.valueOf(rows.size()).length();


            int maxLabelWidth = 0;
            for (Row row : rows) {
                int w = numberedPrefixWidth(maxIndexWidth) + row.label().length();
                if (w > maxLabelWidth)
                    maxLabelWidth = w;
            }
            int descCol = LEFT_PAD + maxLabelWidth + 4;
            for (int i = 0; i < rows.size(); i++) {
                drawRow(g, listStart + i, cols, i, rows.get(i), i == focusedIdx, descCol, maxIndexWidth);
            }
            int learnMoreY = listStart + rows.size() + 1;
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, learnMoreY,
                "Learn more: https://code.claude.com/docs/en/memory");
            g.putString(LEFT_PAD, learnMoreY + 2,
                "Enter to confirm · Esc to cancel");
        }

        private void drawToggle(TextGUIGraphics g, int row, int index, String text) {
            boolean focused = focusedToggle != null && focusedToggle == index;
            g.setForegroundColor(focused
                ? LanternaTheme.statusCost() : LanternaTheme.welcomeDim());
            if (focused) g.enableModifiers(SGR.BOLD);
            g.putString(LEFT_PAD, row, (focused ? "❯ " : "  ") + text);
            if (focused) g.disableModifiers(SGR.BOLD);
        }

        /** Total prefix width: pointer(2) + "NN." padded to maxIndexWidth+2. */
        private int numberedPrefixWidth(int maxIndexWidth) {
            return 2 + maxIndexWidth + 2;
        }

        private void drawRow(TextGUIGraphics g, int y, int cols, int idx, Row row, boolean focused,
                             int descCol, int maxIndexWidth) {
            String pointer = focused ? "❯ " : "  ";
            // Right-pad the "N." string so `1.` and `10.` occupy the same slot,

            String numStr = (idx + 1) + ".";
            String prefix = pointer + StringUtils.padEnd(numStr, maxIndexWidth + 2);
            if (focused) {
                g.setForegroundColor(LanternaTheme.statusCost());
                g.enableModifiers(SGR.BOLD);
            } else {
                g.setForegroundColor(LanternaTheme.welcomeDim());
            }
            g.putString(LEFT_PAD, y, prefix + row.label());
            if (focused) g.disableModifiers(SGR.BOLD);

            // Description aligned to shared column across all rows.
            String desc = row.description() != null ? row.description() : "";
            if (!desc.isEmpty() && descCol + desc.length() <= cols - 1) {
                g.setForegroundColor(LanternaTheme.welcomeDim());
                g.putString(descCol, y, desc);
            }
        }

    }

}
