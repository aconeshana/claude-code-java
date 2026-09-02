package com.claudecode.ui.lanterna.features.projects;

import com.claudecode.core.text.FormatUtils;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.repl.ProjectCatalogPort.ProjectEntry;
import com.claudecode.ui.lanterna.repl.ProjectCatalogPort.ProjectPreferences;
import com.claudecode.ui.lanterna.repl.ProjectCatalogPort.ProjectSessionEntry;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import org.apache.commons.lang3.StringUtils;

/**
 * Left-docked project-management drawer: a two-level project→session tree that
 * paints over the left strip of the transcript (Codex-desktop sidebar style).
 * A Java-side extension with no 197 counterpart — released 2.1.197 has no
 * project grouping UI at all (its resume picker lists sessions flat per
 * project; see {@code SessionSelectorDialog}).
 *
 * <p>Geometry: the component reports the full terminal size when active, and
 * {@link com.claudecode.ui.lanterna.components.SmartLayout} anchors it over the
 * transcript viewport as a covering overlay. The renderer only paints the left
 * strip; every other cell of its rect is left untouched so the transcript keeps
 * showing through on the right.
 *
 * <p>Keyboard: ↑/↓ walk the flattened visible rows (wrapping, cycleIndex-style
 * like {@code HelpPanel}), → expands a project, ← collapses it (or moves from a
 * session back to its project, or closes the drawer at the top level), Enter on
 * a project toggles it, Enter on a session resumes it via the host callback, x
 * on a session arms a two-stage delete (second x confirms, any other key
 * disarms), p opens a wide scrollable preview of the session's transcript
 * (Esc/←/p returns to the tree), Esc closes. Every key — including PASTE — is
 * consumed while active so nothing leaks into the prompt behind the overlay.
 */
public final class ProjectPanel extends Panel implements InlineOverlay {

    /** Host callbacks. Null members mean "not wired yet" (the action is then inert). */
    public record Actions(
        Consumer<ProjectSessionEntry> onResume,
        Consumer<ProjectSessionEntry> onDelete,
        Consumer<ProjectSessionEntry> onPreview,
        Runnable onClose,
        BiConsumer<List<String>, Map<String, Boolean>> onPreferencesChanged
    ) {
        public Actions(Consumer<ProjectSessionEntry> onResume,
                       Consumer<ProjectSessionEntry> onDelete, Runnable onClose) {
            this(onResume, onDelete, null, onClose, null);
        }

        public Actions(Consumer<ProjectSessionEntry> onResume,
                       Consumer<ProjectSessionEntry> onDelete, Runnable onClose,
                       BiConsumer<List<String>, Map<String, Boolean>> onPreferencesChanged) {
            this(onResume, onDelete, null, onClose, onPreferencesChanged);
        }
    }

    private sealed interface Row {
        record ProjectRow(int projectIndex) implements Row {}
        record SessionRow(int projectIndex, int sessionIndex) implements Row {}
    }

    private static final int STRIP_MIN = 20;
    private static final int STRIP_MAX = 38;
    private static final int HEADER_ROWS = 2; // title + divider
    private static final int FOOTER_ROWS = 1; // key hints
    private static final String TITLE = "Projects";
    private static final String EMPTY_HINT = "No sessions found";
    private static final String LOADING_HINT = "Loading projects…";
    private static final String LOADING_PREVIEW_HINT = "Loading preview…";
    private static final String FOOTER_HINT = "↑↓ move · → expand · Enter open · Esc close";
    private static final String FOOTER_HINT_ARMED = "x again to delete · Esc cancel";
    private static final String FOOTER_HINT_PREVIEW = "↑↓ scroll · Esc back";

    private final IntSupplier terminalColumns;
    private final IntSupplier terminalRows;

    private boolean active;
    private boolean loading;
    private List<ProjectEntry> projects = List.of();
    private List<String> pinned = List.of();
    private final Map<String, Boolean> collapsed = new HashMap<>();
    private List<Row> rows = List.of();
    private int focus;
    private int visibleFrom;
    private Actions actions = new Actions(null, null, null);
    /** Session id armed for deletion by the first x; the second x confirms. */
    private String pendingDeleteId;
    /** Preview mode: the session being skimmed; null lines mean "still loading". */
    private ProjectSessionEntry previewSession;
    private List<String> previewLines;
    private int previewScroll;

    public ProjectPanel(IntSupplier terminalColumns, IntSupplier terminalRows) {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        this.terminalColumns = terminalColumns != null ? terminalColumns : () -> 80;
        this.terminalRows = terminalRows != null ? terminalRows : () -> 24;
        DrawerArea area = new DrawerArea();
        area.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(area);
    }

    /** Shows the drawer with a loading placeholder. Must run on the GUI thread. */
    public synchronized void showLoading() {
        active = true;
        loading = true;
        projects = List.of();
        rows = List.of();
        focus = 0;
        visibleFrom = 0;
        invalidate();
    }

    /**
     * Populates the drawer. Projects arrive catalog-ordered (recent activity
     * first); pinned projects float to the top in their pinned order. Must run
     * on the GUI thread.
     */
    public synchronized void show(List<ProjectEntry> catalog, ProjectPreferences prefs,
                                  Actions actions) {
        this.actions = actions != null ? actions : new Actions(null, null, null);
        this.pinned = prefs != null ? prefs.pinnedProjects() : List.of();
        this.collapsed.clear();
        if (prefs != null) this.collapsed.putAll(prefs.collapsedProjects());
        this.projects = orderPinnedFirst(catalog != null ? catalog : List.of(), pinned);
        this.active = true;
        this.loading = false;
        this.focus = 0;
        this.visibleFrom = 0;
        this.pendingDeleteId = null;
        this.previewSession = null;
        this.previewLines = null;
        this.previewScroll = 0;
        rebuildRows();
        invalidate();
    }

    /** Closes the drawer without firing callbacks (host teardown path). */
    public synchronized void hide() {
        active = false;
        loading = false;
        pendingDeleteId = null;
        previewSession = null;
        previewLines = null;
        invalidate();
    }

    /**
     * Delivers the asynchronously loaded preview content. A result for any
     * session other than the one being previewed is dropped — the host's read
     * may resolve after the user moved on. Must run on the GUI thread.
     */
    public synchronized void showPreviewLines(ProjectSessionEntry session, List<String> lines) {
        if (previewSession == null || session == null
                || !previewSession.id().equals(session.id())) return;
        previewLines = lines != null ? List.copyOf(lines) : List.of();
        previewScroll = 0;
        invalidate();
    }

    @Override public boolean isActive() { return active; }

    @Override
    public synchronized void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!active) return;

        // Every key is consumed, including PASTE, so nothing leaks into the
        // main input behind this overlay (InlineOverlay has no real GUI focus).
        deliver.set(false);
        if (key.getKeyType() == KeyType.PASTE || loading) return;

        if (previewSession != null) {
            handlePreviewKey(key);
            return;
        }

        boolean plainX = key.getKeyType() == KeyType.CHARACTER
            && key.getCharacter() != null && key.getCharacter() == 'x'
            && !key.isCtrlDown() && !key.isAltDown();
        if (pendingDeleteId != null && !plainX) {
            // Any non-x key disarms the confirmation; Esc is consumed by the
            // disarm alone (it must not also close the drawer).
            pendingDeleteId = null;
            invalidate();
            if (key.getKeyType() == KeyType.ESCAPE) return;
        }

        switch (key.getKeyType()) {
            case ARROW_UP -> moveFocus(-1);
            case ARROW_DOWN -> moveFocus(1);
            case ARROW_RIGHT -> expandFocused();
            case ARROW_LEFT -> collapseOrRetreat();
            case ENTER -> activateFocused();
            case ESCAPE -> close();
            case CHARACTER -> {
                if (plainX) handleDeleteKey();
                else if (key.getCharacter() != null && key.getCharacter() == 'p'
                        && !key.isCtrlDown() && !key.isAltDown()) {
                    startPreview();
                }
            }
            default -> { /* swallowed: the drawer owns the input stream while active */ }
        }
    }

    /** x on a session arms; a second x fires the host delete and drops the row optimistically. */
    private void handleDeleteKey() {
        if (!(focusedRow() instanceof Row.SessionRow(int projectIndex, int sessionIndex))) return;
        ProjectSessionEntry session = projects.get(projectIndex).sessions().get(sessionIndex);
        if (!session.id().equals(pendingDeleteId)) {
            pendingDeleteId = session.id();
            invalidate();
            return;
        }
        pendingDeleteId = null;
        if (actions.onDelete() != null) actions.onDelete().accept(session);
        removeSessionOptimistically(projectIndex, sessionIndex);
    }

    /**
     * The picker removes a deleted session from its model immediately
     * (deletedSessionIds); the drawer does the same so the row vanishes before
     * the next catalog refresh confirms it on disk.
     */
    private void removeSessionOptimistically(int projectIndex, int sessionIndex) {
        ProjectEntry project = projects.get(projectIndex);
        List<ProjectSessionEntry> remaining = new ArrayList<>(project.sessions());
        remaining.remove(sessionIndex);
        List<ProjectEntry> rebuilt = new ArrayList<>(projects);
        if (remaining.isEmpty()) {
            rebuilt.remove(projectIndex);
        } else {
            rebuilt.set(projectIndex, new ProjectEntry(project.projectPath(), project.projectName(),
                remaining.size(), project.lastActivityMs(), remaining));
        }
        projects = List.copyOf(rebuilt);
        rebuildRows();
        focus = Math.min(focus, Math.max(0, rows.size() - 1));
        adjustWindow();
        invalidate();
    }

    private void startPreview() {
        if (!(focusedRow() instanceof Row.SessionRow(int projectIndex, int sessionIndex))) return;
        previewSession = projects.get(projectIndex).sessions().get(sessionIndex);
        previewLines = null;
        previewScroll = 0;
        invalidate();
        if (actions.onPreview() != null) actions.onPreview().accept(previewSession);
    }

    private void handlePreviewKey(KeyStroke key) {
        int lineCount = previewLines != null ? previewLines.size() : 0;
        switch (key.getKeyType()) {
            case ARROW_UP -> previewScroll = Math.max(0, previewScroll - 1);
            case ARROW_DOWN -> previewScroll = Math.min(Math.max(0, lineCount - 1), previewScroll + 1);
            case PAGE_UP -> previewScroll = Math.max(0, previewScroll - visibleRowCount());
            case PAGE_DOWN -> previewScroll = Math.min(Math.max(0, lineCount - 1),
                previewScroll + visibleRowCount());
            case ESCAPE, ARROW_LEFT -> exitPreview();
            case CHARACTER -> {
                if (key.getCharacter() != null && key.getCharacter() == 'p'
                        && !key.isCtrlDown() && !key.isAltDown()) exitPreview();
            }
            default -> { /* swallowed */ }
        }
        invalidate();
    }

    private void exitPreview() {
        previewSession = null;
        previewLines = null;
        previewScroll = 0;
        invalidate();
    }

    private void moveFocus(int delta) {
        if (rows.isEmpty()) return;
        focus = InlineOverlay.cycleIndex(focus, delta, rows.size());
        adjustWindow();
        invalidate();
    }

    private void expandFocused() {
        if (focusedRow() instanceof Row.ProjectRow(int projectIndex)
                && isCollapsed(projects.get(projectIndex).projectPath())) {
            setCollapsed(projects.get(projectIndex).projectPath(), false);
        }
    }

    private void collapseOrRetreat() {
        switch (focusedRow()) {
            case Row.ProjectRow(int projectIndex) -> {
                String path = projects.get(projectIndex).projectPath();
                if (isCollapsed(path)) close();
                else setCollapsed(path, true);
            }
            case Row.SessionRow(int projectIndex, int ignored) -> {
                setCollapsed(projects.get(projectIndex).projectPath(), true);
                focus = indexOfProjectRow(projectIndex);
                adjustWindow();
                invalidate();
            }
            case null -> close();
        }
    }

    private void activateFocused() {
        switch (focusedRow()) {
            case Row.ProjectRow(int projectIndex) -> {
                String path = projects.get(projectIndex).projectPath();
                setCollapsed(path, !isCollapsed(path));
            }
            case Row.SessionRow(int projectIndex, int sessionIndex) -> {
                ProjectSessionEntry session =
                    projects.get(projectIndex).sessions().get(sessionIndex);
                if (actions.onResume() != null) actions.onResume().accept(session);
                close();
            }
            case null -> close();
        }
    }

    private void setCollapsed(String projectPath, boolean value) {
        collapsed.put(projectPath, value);
        rebuildRows();
        focus = Math.min(focus, Math.max(0, rows.size() - 1));
        adjustWindow();
        invalidate();
        if (actions.onPreferencesChanged() != null) {
            actions.onPreferencesChanged().accept(pinned, Map.copyOf(collapsed));
        }
    }

    private boolean isCollapsed(String projectPath) {
        return collapsed.getOrDefault(projectPath, Boolean.TRUE);
    }

    private void close() {
        if (!active) return;
        Runnable cb = actions.onClose();
        hide();
        if (cb != null) cb.run();
    }

    private Row focusedRow() {
        return rows.isEmpty() ? null : rows.get(Math.min(focus, rows.size() - 1));
    }

    private int indexOfProjectRow(int projectIndex) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) instanceof Row.ProjectRow(int p) && p == projectIndex) return i;
        }
        return 0;
    }

    private void rebuildRows() {
        List<Row> rebuilt = new ArrayList<>();
        for (int p = 0; p < projects.size(); p++) {
            rebuilt.add(new Row.ProjectRow(p));
            if (!isCollapsed(projects.get(p).projectPath())) {
                for (int s = 0; s < projects.get(p).sessions().size(); s++) {
                    rebuilt.add(new Row.SessionRow(p, s));
                }
            }
        }
        rows = List.copyOf(rebuilt);
    }

    private static List<ProjectEntry> orderPinnedFirst(List<ProjectEntry> catalog,
                                                       List<String> pinned) {
        if (pinned.isEmpty()) return List.copyOf(catalog);
        Map<String, ProjectEntry> byPath = new LinkedHashMap<>();
        for (ProjectEntry entry : catalog) byPath.put(entry.projectPath(), entry);
        List<ProjectEntry> ordered = new ArrayList<>(catalog.size());
        for (String path : pinned) {
            ProjectEntry entry = byPath.remove(path);
            if (entry != null) ordered.add(entry);
        }
        ordered.addAll(byPath.values());
        return List.copyOf(ordered);
    }

    /** Scroll-window bookkeeping, same shape as {@code HelpPanel#adjustWindow}. */
    private void adjustWindow() {
        int count = visibleRowCount();
        if (focus < visibleFrom) visibleFrom = focus;
        else if (focus >= visibleFrom + count) visibleFrom = focus - count + 1;
    }

    private int visibleRowCount() {
        return Math.max(1, safeRows() - HEADER_ROWS - FOOTER_ROWS);
    }

    private int safeColumns() {
        try {
            return Math.max(1, terminalColumns.getAsInt());
        } catch (RuntimeException _) {
            return 80;
        }
    }

    private int safeRows() {
        try {
            return Math.max(1, terminalRows.getAsInt());
        } catch (RuntimeException _) {
            return 24;
        }
    }

    static int stripWidth(int columns) {
        return Math.min(columns, Math.min(STRIP_MAX, Math.max(STRIP_MIN, columns * 2 / 5)));
    }

    /** Preview mode uses up to three quarters of the terminal (a skimmable column). */
    static int previewWidth(int columns) {
        return Math.min(columns, Math.max(stripWidth(columns), columns * 3 / 4));
    }

    /** customTitle → summary → firstPrompt → id prefix, matching the session picker's priority. */
    static String sessionLabel(ProjectSessionEntry session) {
        if (StringUtils.isNotBlank(session.customTitle())) return session.customTitle().strip();
        if (StringUtils.isNotBlank(session.summary())) return session.summary().strip();
        if (StringUtils.isNotBlank(session.firstPrompt())) return session.firstPrompt().strip();
        return session.id().substring(0, Math.min(8, session.id().length()));
    }

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

    // ── test accessors (package-private) ─────────────────────────────────────

    String focusedProjectPathForTest() {
        return switch (focusedRow()) {
            case Row.ProjectRow(int p) -> projects.get(p).projectPath();
            case Row.SessionRow(int p, int ignored) -> projects.get(p).projectPath();
            case null -> null;
        };
    }

    String focusedSessionIdForTest() {
        return focusedRow() instanceof Row.SessionRow(int p, int s)
            ? projects.get(p).sessions().get(s).id() : null;
    }

    // ── renderer ─────────────────────────────────────────────────────────────

    private final class DrawerArea extends AbstractComponent<DrawerArea> {
        @Override protected ComponentRenderer<DrawerArea> createDefaultRenderer() {
            return new DrawerRenderer();
        }
    }

    private final class DrawerRenderer implements ComponentRenderer<DrawerArea> {

        @Override
        public TerminalSize getPreferredSize(DrawerArea c) {
            return active ? new TerminalSize(safeColumns(), safeRows()) : new TerminalSize(0, 0);
        }

        @Override
        public void drawComponent(TextGUIGraphics g, DrawerArea c) {
            if (!active) return;
            int height = g.getSize().getRows();
            if (height <= 0) return;
            if (previewSession != null) {
                drawPreview(g, height);
                return;
            }
            int width = stripWidth(g.getSize().getColumns());
            if (width <= 0) return;

            int row = 0;
            row = paintLine(g, row, width, TITLE, LanternaTheme.professionalBlue(), true);
            row = paintLine(g, row, width, "─".repeat(Math.max(0, width - 1)),
                LanternaTheme.professionalBlue(), false);

            int listBottom = Math.max(row, height - FOOTER_ROWS);
            if (loading) {
                row = paintLine(g, row, width, LOADING_HINT, LanternaTheme.welcomeDim(), false);
            } else if (rows.isEmpty()) {
                row = paintLine(g, row, width, EMPTY_HINT, LanternaTheme.welcomeDim(), false);
            } else {
                int visible = Math.max(1, listBottom - row);
                if (focus < visibleFrom) visibleFrom = focus;
                if (focus >= visibleFrom + visible) visibleFrom = focus - visible + 1;
                int to = Math.min(rows.size(), visibleFrom + visible);
                for (int i = visibleFrom; i < to && row < listBottom; i++) {
                    row = paintTreeRow(g, row, width, i);
                }
            }
            while (row < listBottom) row = paintLine(g, row, width, "", null, false);
            paintLine(g, height - 1, width,
                pendingDeleteId != null ? FOOTER_HINT_ARMED : FOOTER_HINT,
                LanternaTheme.welcomeDim(), false);
        }

        /** Preview mode paints a wider area (the tree strip alone is too narrow to skim). */
        private void drawPreview(TextGUIGraphics g, int height) {
            int width = previewWidth(g.getSize().getColumns());
            if (width <= 0) return;
            int row = 0;
            row = paintLine(g, row, width, "▸ " + sessionLabel(previewSession),
                LanternaTheme.professionalBlue(), true);
            row = paintLine(g, row, width, "─".repeat(Math.max(0, width - 1)),
                LanternaTheme.professionalBlue(), false);

            int listBottom = Math.max(row, height - FOOTER_ROWS);
            if (previewLines == null) {
                row = paintLine(g, row, width, LOADING_PREVIEW_HINT,
                    LanternaTheme.welcomeDim(), false);
            } else {
                int visible = Math.max(1, listBottom - row);
                int maxScroll = Math.max(0, previewLines.size() - visible);
                if (previewScroll > maxScroll) previewScroll = maxScroll;
                int to = Math.min(previewLines.size(), previewScroll + visible);
                for (int i = previewScroll; i < to && row < listBottom; i++) {
                    row = paintLine(g, row, width, previewLines.get(i),
                        LanternaTheme.inputText(), false);
                }
            }
            while (row < listBottom) row = paintLine(g, row, width, "", null, false);
            paintLine(g, height - 1, width, FOOTER_HINT_PREVIEW, LanternaTheme.welcomeDim(), false);
        }

        private int paintTreeRow(TextGUIGraphics g, int row, int width, int index) {
            boolean focused = index == focus;
            TextColor color = focused ? LanternaTheme.suggestion() : LanternaTheme.inputText();
            String text = switch (rows.get(index)) {
                case Row.ProjectRow(int p) -> {
                    ProjectEntry project = projects.get(p);
                    String glyph = isCollapsed(project.projectPath()) ? "▸ " : "▾ ";
                    yield glyph + project.projectName() + " (" + project.sessionCount() + ")";
                }
                case Row.SessionRow(int p, int s) -> {
                    ProjectSessionEntry session = projects.get(p).sessions().get(s);
                    if (session.id().equals(pendingDeleteId)) {
                        yield "✗ " + sessionLabel(session) + " (x to confirm)";
                    }
                    String time = FormatUtils.formatRelativeTimeAgo(
                        Instant.ofEpochMilli(session.lastModified()),
                        FormatUtils.RelativeTimeStyle.NARROW);
                    yield "  " + sessionLabel(session) + " · " + time;
                }
            };
            String pointer = focused ? "❯" : " ";
            boolean armed = rows.get(index) instanceof Row.SessionRow(int p, int s)
                && projects.get(p).sessions().get(s).id().equals(pendingDeleteId);
            return paintLine(g, row, width, pointer + text,
                armed ? LanternaTheme.toolError() : color, focused);
        }

        /**
         * Paints one full strip row: every cell is written (text padded with
         * spaces) so the transcript never bleeds through inside the strip.
         */
        private int paintLine(TextGUIGraphics g, int row, int width, String text,
                              TextColor color, boolean bold) {
            if (row < 0 || row >= g.getSize().getRows()) return row + 1;
            g.setBackgroundColor(TextColor.ANSI.DEFAULT);
            g.setForegroundColor(color != null ? color : LanternaTheme.inputText());
            if (bold) g.enableModifiers(SGR.BOLD);
            String clipped = FormatUtils.truncateNoEllipsis(text, Math.max(0, width));
            g.putString(0, row, clipped + " ".repeat(Math.max(0, width
                - FormatUtils.displayWidth(clipped))));
            if (bold) g.disableModifiers(SGR.BOLD);
            return row + 1;
        }
    }
}
