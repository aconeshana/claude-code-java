package com.claudecode.ui.lanterna.dialog;

import org.apache.commons.lang3.Strings;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskType;
import com.claudecode.ui.lanterna.transcript.BackgroundTasksRenderer;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.input.ContextKeybindingDispatcher;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Inline {@code /tasks} (alias {@code /bashes}) background-tasks panel — sits in the SmartLayout
 * stack like {@link MessageSelectorDialog} / {@link SkillsDialog}, occupying zero rows when idle.
 */
public final class BackgroundTasksDialog extends Panel implements InlineOverlay {

    private static final int LEFT_PAD = 2;
    private static final int MIN_WIDTH = 64;
    private static final int MAX_VISIBLE_ROWS = 12;

    /** Package-private (not {@code private}) so {@code BackgroundTasksDialogTest}
     *  can assert on it directly, same pattern as {@code MessageSelectorDialog.Phase}. */
    enum Mode { LIST, DETAIL }

    /** One row in list mode: either a Shells or Local agents entry.
     *  Package-private for the same test-visibility reason as {@link Mode}. */
    record ListItem(TaskState task, String label) {}

    /** One visual row of the list body: a group header or a selectable item. */
    private record RenderRow(String header, int itemIndex) {
        static RenderRow header(String text) { return new RenderRow(text, -1); }
        static RenderRow item(int index) { return new RenderRow(null, index); }
        boolean isHeader() { return header != null; }
    }

    private final TaskRegistry registry;
    private final BackgroundTasksRenderer detailRenderer;
    private final ScheduledExecutorService refreshTimer =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tasks-dialog-refresh");
            t.setDaemon(true);
            return t;
        });
    private ScheduledFuture<?> refreshFuture;

    private boolean active;
    private Mode mode;
    private Runnable onClose;
    private Consumer<TaskState> onViewAgent = _ -> {};
    private BiConsumer<TaskState, Boolean> onViewWorkflow = (_, _) -> {};

    // list mode
    private List<ListItem> items = List.of();
    private int selectedIndex;
    private int scrollOffset;
    /** Set true when the dialog opened straight into detail mode because
     *  there was exactly one background task. Drives {@link #goBackToList}. */
    private boolean skippedListOnMount;

    // detail mode
    private String detailTaskId;
    private final ContextKeybindingDispatcher keybindings =
        new ContextKeybindingDispatcher();

    public BackgroundTasksDialog(TaskRegistry registry) {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        this.registry = Objects.requireNonNull(registry, "registry");
        this.detailRenderer = new BackgroundTasksRenderer(registry);
        Body body = new Body();
        body.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(body);
    }

    public void setKeybindingsStore(UserKeybindingsStore store) {
        keybindings.setStore(store);
    }

    public void setOnViewAgent(Consumer<TaskState> onViewAgent) {
        this.onViewAgent = onViewAgent == null ? _ -> {} : onViewAgent;
    }

    public void setOnViewWorkflow(Consumer<TaskState> onViewWorkflow) {
        this.onViewWorkflow = onViewWorkflow == null ? (_, _) -> {}
            : (task, _) -> onViewWorkflow.accept(task);
    }

    /**
     * Supplies the workflow route plus whether Back should restore the task list.
     */
    public void setOnViewWorkflowRoute(BiConsumer<TaskState, Boolean> onViewWorkflow) {
        this.onViewWorkflow = onViewWorkflow == null ? (_, _) -> {} : onViewWorkflow;
    }

    /**
     * Activates the dialog against the live {@link TaskRegistry}.
     */
    public synchronized void show(Runnable onClose) {
        this.onClose = onClose;
        List<TaskState> all = sortedBackgroundTasks();
        this.active = true;
        this.scrollOffset = 0;
        if (all.size() == 1 && all.getFirst().type() == TaskType.LOCAL_WORKFLOW) {
            routeToWorkflow(all.getFirst(), false);
            return;
        }
        if (all.size() == 1 && all.getFirst().type() != TaskType.MONITOR_WS) {
            this.skippedListOnMount = true;
            this.detailTaskId = all.getFirst().id();
            this.mode = Mode.DETAIL;
            refreshDetailSnapshot();
        } else {
            this.skippedListOnMount = false;
            rebuildListItems(all);
            this.selectedIndex = 0;
            this.mode = Mode.LIST;
        }
        startRefreshTimer();
        invalidate();
    }

    @Override public boolean isActive() { return active; }

    private void startRefreshTimer() {
        stopRefreshTimer();
        refreshFuture = refreshTimer.scheduleAtFixedRate(
            // Marshal onto the GUI thread when attached: tickRefresh can close
            // the dialog, and the close callback appends to the transcript —
            // not safe from the timer thread. Unattached (tests) → run direct.
            () -> runOnGUIThreadIfExistsOtherwiseRunDirect(this::tickRefresh),
            1, 1, TimeUnit.SECONDS);
    }

    private void stopRefreshTimer() {
        if (refreshFuture != null) {
            refreshFuture.cancel(false);
            refreshFuture = null;
        }
    }


    synchronized void tickRefresh() {
        if (!active) return;
        if (mode == Mode.LIST) {
            refreshListItems();
        } else if (mode == Mode.DETAIL && !isBackground(detailTaskId)) {
            if (skippedListOnMount) {
                close();
                return;
            }
            refreshListItems();
            this.mode = Mode.LIST;
        } else if (mode == Mode.DETAIL) {
            refreshDetailSnapshot();
        }
        invalidate();
    }

    // ── data ─────────────────────────────────────────────────────────────


    private List<TaskState> sortedBackgroundTasks() {
        List<TaskState> all = new ArrayList<>(registry.listBackground());
        all.sort((a, b) -> {
            boolean aRunning = a.status() == TaskStatus.RUNNING;
            boolean bRunning = b.status() == TaskStatus.RUNNING;
            if (aRunning != bRunning) return aRunning ? -1 : 1;
            return b.startTime().compareTo(a.startTime());
        });
        return all;
    }

    /**
     * Globally sorted, then partitioned into [shells…, monitors…, agents…] — the exact order the
     * renderer draws.
     */
    private void rebuildListItems(List<TaskState> sorted) {
        List<ListItem> shells = new ArrayList<>();
        List<ListItem> monitors = new ArrayList<>();
        List<ListItem> agents = new ArrayList<>();
        List<ListItem> workflows = new ArrayList<>();
        List<ListItem> dreams = new ArrayList<>();
        for (TaskState t : sorted) {
            if (t.type() == TaskType.LOCAL_BASH) {
                shells.add(new ListItem(t, t.description()));
            }
            else if (t.type() == TaskType.MONITOR_MCP
                    || t.type() == TaskType.MONITOR_WS) {
                monitors.add(new ListItem(t, t.description()));
            }
            else if (t.type() == TaskType.LOCAL_AGENT) agents.add(new ListItem(t, t.description()));
            else if (t.type() == TaskType.LOCAL_WORKFLOW) {
                workflows.add(new ListItem(t, t.description()));
            }
            else if (t.type() == TaskType.DREAM) dreams.add(new ListItem(t, t.description()));
        }
        shells.addAll(monitors);
        shells.addAll(agents);
        shells.addAll(workflows);
        shells.addAll(dreams);
        this.items = shells;
    }

    /** Re-reads the registry and re-clamps the cursor — the list-mode data path. */
    private void refreshListItems() {
        rebuildListItems(sortedBackgroundTasks());
        clampSelectedIndex();
    }

    private boolean isBackground(String taskId) {
        return registry.listBackground().stream().anyMatch(t -> t.id().equals(taskId));
    }

    // ── key handling ─────────────────────────────────────────────────────

    @Override
    public synchronized void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!active) return;
        if (key.getKeyType() == KeyType.PASTE) {
            // Blood-earned rule (see InlineOverlay / MessageSelectorDialog):
            // this overlay has no real Interactable holding GUI focus, so an
            // unconsumed PASTE leaks straight into the main input behind it.
            // This dialog has no text field of its own — just swallow it.
            deliver.set(false);
            return;
        }
        ContextKeybindingDispatcher.Result resolved = keybindings.resolve("Confirmation", key);
        if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) {
            deliver.set(false);
            return;
        }
        if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String value)
                && dispatchConfirmationAction(value)) {
            deliver.set(false);
            return;
        }
        switch (mode) {
            case LIST -> handleListKey(key, deliver);
            case DETAIL -> handleDetailKey(key, deliver);
        }

        // handled above must not leak into the suppressed prompt input behind
        // the overlay (it would reappear there when the dialog closes).
        // Ctrl/Alt chords stay deliverable so global bindings keep working.
        if (deliver.get() && key.getKeyType() == KeyType.CHARACTER
                && !key.isCtrlDown() && !key.isAltDown()) {
            deliver.set(false);
        }
    }

    private boolean dispatchConfirmationAction(String action) {
        if (Strings.CS.equals("confirm:no", action)) {
            close();
            return true;
        }
        if (mode != Mode.LIST) return false;
        refreshListItems();
        return switch (action) {
            case "confirm:previous" -> {
                if (!items.isEmpty()) selectedIndex = Math.max(0, selectedIndex - 1);
                invalidate();
                yield true;
            }
            case "confirm:next" -> {
                if (!items.isEmpty()) selectedIndex = Math.min(items.size() - 1, selectedIndex + 1);
                invalidate();
                yield true;
            }
            case "confirm:yes" -> {
                if (!items.isEmpty()) enterDetail(items.get(selectedIndex).task().id());
                yield true;
            }
            default -> false;
        };
    }

    private void handleListKey(KeyStroke key, AtomicBoolean deliver) {
        // Every key press sees the live registry, not the last tick's snapshot
        // — Enter must open the task the user sees, and x must judge kill
        // eligibility on real statuses.
        refreshListItems();
        KeyType t = key.getKeyType();
        if (t == KeyType.ESCAPE || t == KeyType.ARROW_LEFT) {
            close();
            deliver.set(false);
            return;
        }
        if (items.isEmpty()) return;
        if (t == KeyType.ARROW_UP) {
            selectedIndex = Math.max(0, selectedIndex - 1);
            invalidate();
            deliver.set(false);
            return;
        }
        if (t == KeyType.ARROW_DOWN) {
            selectedIndex = Math.min(items.size() - 1, selectedIndex + 1);
            invalidate();
            deliver.set(false);
            return;
        }
        if (t == KeyType.ENTER) {
            enterDetail(items.get(selectedIndex).task().id());
            deliver.set(false);
            return;
        }
        if (t == KeyType.CHARACTER && key.getCharacter() != null
                && Character.toLowerCase(key.getCharacter()) == 'f') {
            TaskState selected = items.get(selectedIndex).task();
            if (selected.type() == TaskType.LOCAL_AGENT) {
                onViewAgent.accept(selected);
                close();
            }
            deliver.set(false);
            return;
        }
        if (t == KeyType.CHARACTER && key.getCharacter() != null && key.getCharacter() == 'x') {
            killSelected();
            deliver.set(false);
        }
    }

    private void handleDetailKey(KeyStroke key, AtomicBoolean deliver) {
        KeyType t = key.getKeyType();
        if (t == KeyType.CHARACTER && key.getCharacter() != null
                && Character.toLowerCase(key.getCharacter()) == 'f') {
            registry.get(detailTaskId).filter(task -> task.type() == TaskType.LOCAL_AGENT)
                .ifPresent(task -> {
                    onViewAgent.accept(task);
                    close();
                });
            deliver.set(false);
            return;
        }
        if (t == KeyType.ARROW_LEFT) {
            goBackToList();
            deliver.set(false);
            return;
        }
        if (t == KeyType.ESCAPE || t == KeyType.ENTER
                || (t == KeyType.CHARACTER && key.getCharacter() != null && key.getCharacter() == ' ')) {
            close();
            deliver.set(false);
            return;
        }
        if (t == KeyType.CHARACTER && key.getCharacter() != null && key.getCharacter() == 'x') {
            killDetailTask();
            deliver.set(false);
        }
    }

    // ── actions ──────────────────────────────────────────────────────────

    private void enterDetail(String taskId) {
        Optional<TaskState> task = registry.get(taskId);
        if (task.isPresent() && task.get().type() == TaskType.LOCAL_WORKFLOW) {
            routeToWorkflow(task.get(), true);
            return;
        }
        this.detailTaskId = taskId;
        this.mode = Mode.DETAIL;
        refreshDetailSnapshot();
        invalidate();
    }

    private void routeToWorkflow(TaskState task, boolean dismissList) {
        active = false;
        mode = null;
        items = List.of();
        onClose = null;
        detailTaskId = null;
        scrollOffset = 0;
        stopRefreshTimer();
        invalidate();
        onViewWorkflow.accept(task, dismissList);
    }

    private void refreshDetailSnapshot() {
        String taskId = detailTaskId;
        detailRenderer.refreshShellOutputAsync(taskId,
            () -> runOnGUIThreadIfExistsOtherwiseRunDirect(() -> {
                if (active && mode == Mode.DETAIL && Objects.equals(taskId, detailTaskId)) {
                    invalidate();
                }
            }));
    }


    private void goBackToList() {
        if (skippedListOnMount && registry.listBackground().size() <= 1) {
            close();
            return;
        }
        skippedListOnMount = false;
        refreshListItems();
        this.mode = Mode.LIST;
        invalidate();
    }

    private void killSelected() {
        if (items.isEmpty()) return;
        String taskId = items.get(selectedIndex).task().id();
        // Judge kill eligibility on the LIVE status — the row snapshot can be
        // up to a tick stale (a PENDING row may really be RUNNING by now).
        registry.get(taskId).ifPresent(this::killTask);
        refreshListItems();
        invalidate();
    }

    /**
     * {@code x} in detail mode.
     */
    private void killDetailTask() {
        Optional<TaskState> live = registry.get(detailTaskId);
        if (live.isEmpty() || live.get().status() != TaskStatus.RUNNING) {
            return;
        }
        killTask(live.get());
        if (!isBackground(detailTaskId)) {
            if (skippedListOnMount) {
                close();
                return;
            }
            refreshListItems();
            this.mode = Mode.LIST;
        }
        invalidate();
    }

    private void killTask(TaskState live) {
        if (live.status() != TaskStatus.RUNNING) return;
        registry.killTask(live.id());
    }

    private void clampSelectedIndex() {
        if (items.isEmpty()) { selectedIndex = 0; return; }
        selectedIndex = Math.min(selectedIndex, items.size() - 1);
    }

    private synchronized void close() {
        if (!active) return;
        Runnable cb = onClose;
        active = false;
        mode = null;
        items = List.of();
        onClose = null;
        detailTaskId = null;
        scrollOffset = 0;
        stopRefreshTimer();
        invalidate();
        if (cb != null) cb.run();
    }

    // ── sizing ───────────────────────────────────────────────────────────

    /** The list body's visual rows (group headers + item rows) in render order. */
    private List<RenderRow> buildRenderRows() {
        List<RenderRow> rows = new ArrayList<>();
        int shellCount = 0;
        int monitorCount = 0;
        int agentCount = 0;
        int workflowCount = 0;
        int dreamCount = 0;
        for (ListItem item : items) {
            TaskType ty = item.task().type();
            if (ty == TaskType.LOCAL_BASH) shellCount++;
            else if (ty == TaskType.MONITOR_MCP || ty == TaskType.MONITOR_WS) monitorCount++;
            else if (ty == TaskType.LOCAL_AGENT) agentCount++;
            else if (ty == TaskType.LOCAL_WORKFLOW) workflowCount++;
            else if (ty == TaskType.DREAM) dreamCount++;
        }
// Group headers only render when >1 group is present.
        int groupCount = (shellCount > 0 ? 1 : 0) + (monitorCount > 0 ? 1 : 0)
            + (agentCount > 0 ? 1 : 0)
            + (workflowCount > 0 ? 1 : 0)
            + (dreamCount > 0 ? 1 : 0);
        boolean showHeaders = groupCount > 1;
        int i = 0;
        if (shellCount > 0) {
            if (showHeaders) rows.add(RenderRow.header("Shells (" + shellCount + ")"));
            for (; i < shellCount; i++) rows.add(RenderRow.item(i));
        }
        if (monitorCount > 0) {

            rows.add(RenderRow.header("Monitors (" + monitorCount + ")"));
            for (; i < shellCount + monitorCount; i++) rows.add(RenderRow.item(i));
        }
        if (agentCount > 0) {
            if (showHeaders) rows.add(RenderRow.header("Local agents (" + agentCount + ")"));
            for (; i < shellCount + monitorCount + agentCount; i++) rows.add(RenderRow.item(i));
        }
        if (workflowCount > 0) {
            rows.add(RenderRow.header("Dynamic workflows (" + workflowCount + ")"));
            for (; i < shellCount + monitorCount + agentCount + workflowCount; i++) {
                rows.add(RenderRow.item(i));
            }
        }
        if (dreamCount > 0) {
            if (showHeaders) rows.add(RenderRow.header("Dreams (" + dreamCount + ")"));
            for (; i < shellCount + monitorCount + agentCount + workflowCount + dreamCount; i++) {
                rows.add(RenderRow.item(i));
            }
        }
        return rows;
    }

    /** Scroll-window clamp — same contract as {@code MessageSelectorDialog}'s:
     *  keeps {@code selectedRow} inside a {@code visible}-row window over
     *  {@code totalRows} rows. Package-private static for direct unit tests. */
    static int clampScroll(int selectedRow, int totalRows, int visible, int currentOffset) {
        int offset = currentOffset;
        if (selectedRow < offset) offset = selectedRow;
        if (selectedRow >= offset + visible) offset = selectedRow - visible + 1;
        return Math.max(0, Math.clamp(totalRows - visible, 0, offset));
    }

    private int totalRows() {
        if (!active) return 0;
        if (mode == Mode.LIST) {
            // divider(1) + title(1) + subtitle(1) + body + blank(1) + footer(1) + pad(1)
            int body = items.isEmpty() ? 1 : Math.min(buildRenderRows().size(), MAX_VISIBLE_ROWS);
            return 6 + body;
        }
        return detailRowCount();
    }

    /** DETAIL-mode row count — delegates to {@link BackgroundTasksRenderer};
     *  package-private so tests can pin the sizing arithmetic. */
    int detailRowCount() {
        return detailRenderer.detailRowCount(detailTaskId);
    }

    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        if (!active) return new TerminalSize(0, 0);
        TerminalSize parent = super.calculatePreferredSize();
        return new TerminalSize(Math.max(MIN_WIDTH, parent.getColumns()), totalRows());
    }

    @Override public Interactable nextFocus(Interactable fromThis) {
        return active ? super.nextFocus(fromThis) : null;
    }

    @Override public Interactable previousFocus(Interactable fromThis) {
        return active ? super.previousFocus(fromThis) : null;
    }

    // ── test-facing accessors (package-private; drive the state machine
    // directly the same way MessageSelectorDialogTest does) ──────────────

    Mode mode() { return mode; }
    List<ListItem> items() { return items; }
    int selectedIndex() { return selectedIndex; }
    boolean skippedListOnMount() { return skippedListOnMount; }
    String detailTaskId() { return detailTaskId; }

    // ── Rendering (list mode; detail mode delegates to BackgroundTasksRenderer) ──

    private final class Body extends AbstractComponent<Body> {
        @Override protected ComponentRenderer<Body> createDefaultRenderer() {
            return new BodyRenderer();
        }
    }

    private final class BodyRenderer implements ComponentRenderer<Body> {
        @Override public TerminalSize getPreferredSize(Body c) {
            return active ? new TerminalSize(MIN_WIDTH, totalRows()) : new TerminalSize(0, 0);
        }

        @Override public void drawComponent(TextGUIGraphics g, Body c) {
            if (!active) return;
            g.fill(' ');
            int cols = g.getSize().getColumns();

            g.setForegroundColor(LanternaTheme.divider());
            g.putString(0, 0, "─".repeat(Math.max(0, cols)));

            switch (mode) {
                case LIST -> drawList(g, cols);
                case DETAIL -> detailRenderer.drawDetail(g, cols, detailTaskId);
            }
        }

        private void drawList(TextGUIGraphics g, int cols) {
            g.setForegroundColor(LanternaTheme.permission());
            g.enableModifiers(SGR.BOLD);
            g.putString(LEFT_PAD, 1, "Background tasks");
            g.disableModifiers(SGR.BOLD);

            String subtitle = buildSubtitle();
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, 2, subtitle);

            int row = 3;
            if (items.isEmpty()) {
                g.setForegroundColor(LanternaTheme.welcomeDim());
                g.putString(LEFT_PAD, row, "No tasks currently running");
                row++;
            } else {
                row = drawWindowedRows(g, cols, row);
            }

            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.enableModifiers(SGR.ITALIC);
            g.putString(LEFT_PAD, row + 1, listFooterHint());
            g.disableModifiers(SGR.ITALIC);
        }

        /** Draws the scroll window over the header+item render rows. Returns
         *  the row index just past the last drawn row. */
        private int drawWindowedRows(TextGUIGraphics g, int cols, int startRow) {
            List<RenderRow> rows = buildRenderRows();
            int selectedRow = 0;
            for (int i = 0; i < rows.size(); i++) {
                if (!rows.get(i).isHeader() && rows.get(i).itemIndex() == selectedIndex) {
                    selectedRow = i;
                    break;
                }
            }
            int visible = Math.min(MAX_VISIBLE_ROWS, rows.size());
            scrollOffset = clampScroll(selectedRow, rows.size(), visible, scrollOffset);

            int maxActivityWidth = Math.max(30, cols - 26);
            int y = startRow;
            for (int i = scrollOffset; i < scrollOffset + visible; i++) {
                RenderRow rr = rows.get(i);
                if (rr.isHeader()) {
                    g.setForegroundColor(LanternaTheme.welcomeDim());
                    g.putString(LEFT_PAD, y, rr.header());
                } else {
                    drawItemRow(g, y, items.get(rr.itemIndex()), rr.itemIndex(), maxActivityWidth);
                }
                y++;
            }
            // Scroll indicators, same shape as SkillsDialog.
            g.setForegroundColor(LanternaTheme.welcomeDim());
            if (scrollOffset > 0) g.putString(cols - 2, startRow, "↑");
            if (scrollOffset + visible < rows.size()) g.putString(cols - 2, y - 1, "↓");
            return y;
        }


        private void drawItemRow(TextGUIGraphics g, int row, ListItem item,
                                 int itemIndex, int maxActivityWidth) {
            boolean selected = itemIndex == selectedIndex;
            TaskState task = item.task();
            String prefix = selected ? "❯ " : "  ";

            // InlineOverlay owns UI clipping; its width semantics match FormatUtils.
            String label = InlineOverlay.clip(item.label(), maxActivityWidth);
            String suffix = " " + BackgroundTasksRenderer.rowStatusSuffix(task);

            g.setForegroundColor(selected ? LanternaTheme.suggestion() : LanternaTheme.inputText());
            g.putString(LEFT_PAD, row, prefix + label);
            g.setForegroundColor(BackgroundTasksRenderer.statusColor(task.status()));
            g.putString(LEFT_PAD + prefix.length() + label.length(), row, suffix);
        }

        private String buildSubtitle() {
            long runningShells = items.stream()
                .filter(i -> i.task().type() == TaskType.LOCAL_BASH && i.task().status() == TaskStatus.RUNNING)
                .count();
            long runningAgents = items.stream()
                .filter(i -> i.task().type() == TaskType.LOCAL_AGENT && i.task().status() == TaskStatus.RUNNING)
                .count();
            List<String> parts = new ArrayList<>();
            if (runningShells > 0) {
                parts.add(runningShells + " active shell" + (runningShells != 1 ? "s" : ""));
            }
            if (runningAgents > 0) {
                parts.add(runningAgents + " active agent" + (runningAgents != 1 ? "s" : ""));
            }
            long runningDreams = items.stream()
                .filter(i -> i.task().type() == TaskType.DREAM && i.task().status() == TaskStatus.RUNNING)
                .count();
            if (runningDreams > 0) {
                parts.add(runningDreams + " active dream" + (runningDreams != 1 ? "s" : ""));
            }
            return String.join(" · ", parts);
        }

        private String listFooterHint() {
            StringBuilder sb = new StringBuilder("↑/↓ select · Enter view");
            if (!items.isEmpty() && items.get(selectedIndex).task().type() == TaskType.LOCAL_AGENT) {
                sb.append(" · f transcript");
            }
            if (!items.isEmpty() && items.get(selectedIndex).task().status() == TaskStatus.RUNNING) {
                sb.append(" · x stop");
            }
            sb.append(" · ←/Esc close");
            return sb.toString();
        }
    }
}
