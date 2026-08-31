package com.claudecode.ui.lanterna.dialog;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.commands.diff.DiffData;
import com.claudecode.commands.diff.TurnDiffExtractor.TurnDiff;
import com.claudecode.commands.diff.TurnDiffExtractor.TurnFileDiff;
import com.claudecode.core.diff.StructuredPatchHunk;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.ui.DiffRenderer;
import com.claudecode.ui.lanterna.input.ContextKeybindingDispatcher;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import com.claudecode.ui.lanterna.input.InputPanel;

/**
 * Inline {@code /diff} dialog — uncommitted-changes browser plus per-turn diffs.
 */
public final class DiffDialog extends Panel implements InlineOverlay {

    private static final int MAX_VISIBLE_FILES = 5;
    private static final int LEFT_PAD = 2;

    private enum ViewMode { LIST, DETAIL }

    /** One selectable diff source: the working-tree diff or a turn. */
    private record Source(TurnDiff turn) {
        boolean isCurrent() { return turn == null; }
    }


    private record View(DiffData.Stats stats, List<DiffData.DiffFile> files,
                        Map<String, List<StructuredPatchHunk>> hunks, boolean isTurn) {}

    private final int terminalRows;
    private final ContextKeybindingDispatcher keybindings =
        new ContextKeybindingDispatcher();

    private boolean active;
    private ViewMode viewMode = ViewMode.LIST;
    private int sourceIndex;
    private int selectedIndex;
    private int detailScroll;
    private DiffData gitDiff;
    private List<TurnDiff> turnDiffs = List.of();
    private List<Source> sources = List.of();
    private Runnable onClose;

    public DiffDialog(int terminalRows) {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        this.terminalRows = terminalRows;
        DialogArea area = new DialogArea();
        area.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(area);
    }

    /** Attach the live merged user/default keybinding resolver. */
    public void setKeybindingsStore(UserKeybindingsStore store) {
        keybindings.setStore(store);
    }

    /**
     * Activate. Must run on the GUI thread.
     *
     * @param gitDiff   working-tree diff (null when not a git repo — the
     *                  Current source then shows "Working tree is clean")
     * @param turnDiffs per-turn diffs, most recent first
     * @param onClose   invoked once when the dialog closes
     */
    public synchronized void show(DiffData gitDiff, List<TurnDiff> turnDiffs, Runnable onClose) {
        this.gitDiff = gitDiff;
        this.turnDiffs = turnDiffs != null ? turnDiffs : List.of();
        this.onClose = onClose;
        List<Source> list = new ArrayList<>();
        list.add(new Source(null));
        for (TurnDiff turn : this.turnDiffs) {
            list.add(new Source(turn));
        }
        this.sources = list;
        this.sourceIndex = 0;
        this.selectedIndex = 0;
        this.detailScroll = 0;
        this.viewMode = ViewMode.LIST;
        this.active = true;
        invalidate();
    }

    @Override public boolean isActive() { return active; }

    @Override
    public synchronized void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!active) return;
        KeyType t = key.getKeyType();
        if (t == KeyType.PASTE) {
            deliver.set(false);
            return;
        }
        // These reserved global keys retain the dialog's established cancel
        // semantics; user keybinding validation does not allow rebinding them.
        if (t == KeyType.CHARACTER && key.isCtrlDown()
                && (key.getCharacter() == 'c' || key.getCharacter() == 'd')) {
            dismiss();
            deliver.set(false);
            return;
        }

        ContextKeybindingDispatcher.Result resolved =
            keybindings.resolve("DiffDialog", key);
        if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) {
            deliver.set(false);
            return;
        }
        if (resolved instanceof ContextKeybindingDispatcher.Result.Action action) {
            dispatchAction(action.value());
            deliver.set(false);
            return;
        }

        // Compatibility fallback for terminals whose key events cannot be
        // represented by the keybinding parser.
        if (t == KeyType.ESCAPE) {
            dismiss();
            deliver.set(false);
            return;
        }
        if (t == KeyType.ARROW_LEFT) {
            if (viewMode == ViewMode.DETAIL) {
                viewMode = ViewMode.LIST;
                invalidate();
            } else if (sources.size() > 1) {
                // diff:previousSource — no wraparound.
                sourceIndex = Math.max(0, sourceIndex - 1);
                selectedIndex = 0;
                invalidate();
            }
            deliver.set(false);
            return;
        }
        if (t == KeyType.ARROW_RIGHT) {
            if (viewMode == ViewMode.LIST && sources.size() > 1) {
                sourceIndex = Math.min(sources.size() - 1, sourceIndex + 1);
                selectedIndex = 0;
                invalidate();
            }
            deliver.set(false);
            return;
        }
        if (t == KeyType.ARROW_UP) {
            if (viewMode == ViewMode.LIST) {
                selectedIndex = Math.max(0, selectedIndex - 1);
            } else {
                detailScroll = Math.max(0, detailScroll - 1);
            }
            invalidate();
            deliver.set(false);
            return;
        }
        if (t == KeyType.ARROW_DOWN) {
            if (viewMode == ViewMode.LIST) {
                selectedIndex = Math.clamp(currentView().files().size() - 1, 0,
                    selectedIndex + 1);
            } else {
                detailScroll++;
            }
            invalidate();
            deliver.set(false);
            return;
        }
        if (t == KeyType.ENTER) {
            if (viewMode == ViewMode.LIST && !currentView().files().isEmpty()) {
                viewMode = ViewMode.DETAIL;
                detailScroll = 0;
                invalidate();
            }
            deliver.set(false);
        }
    }

    private void dispatchAction(String action) {
        switch (action) {
            case "diff:dismiss", "diff:back" -> dismiss();
            case "diff:previousSource" -> {
                if (viewMode == ViewMode.DETAIL) {
                    viewMode = ViewMode.LIST;
                } else if (sources.size() > 1) {
                    sourceIndex = Math.max(0, sourceIndex - 1);
                    selectedIndex = 0;
                }
                invalidate();
            }
            case "diff:nextSource" -> {
                if (viewMode == ViewMode.LIST && sources.size() > 1) {
                    sourceIndex = Math.min(sources.size() - 1, sourceIndex + 1);
                    selectedIndex = 0;
                    invalidate();
                }
            }
            case "diff:previousFile" -> {
                if (viewMode == ViewMode.LIST) {
                    selectedIndex = Math.max(0, selectedIndex - 1);
                } else {
                    detailScroll = Math.max(0, detailScroll - 1);
                }
                invalidate();
            }
            case "diff:nextFile" -> {
                if (viewMode == ViewMode.LIST) {
                    selectedIndex = Math.clamp(currentView().files().size() - 1, 0,
                        selectedIndex + 1);
                } else {
                    detailScroll++;
                }
                invalidate();
            }
            case "diff:viewDetails" -> {
                if (viewMode == ViewMode.LIST && !currentView().files().isEmpty()) {
                    viewMode = ViewMode.DETAIL;
                    detailScroll = 0;
                    invalidate();
                }
            }
            default -> {
                // A Global action belongs to the outer screen; leave this
                // overlay unchanged while consuming the resolver match.
            }
        }
    }

    private void dismiss() {

        if (viewMode == ViewMode.DETAIL) {
            viewMode = ViewMode.LIST;
            invalidate();
        } else {
            close();
        }
    }

    private synchronized void close() {
        if (!active) return;
        Runnable cb = onClose;
        active = false;
        onClose = null;
        gitDiff = null;
        turnDiffs = List.of();
        sources = List.of();
        invalidate();
        if (cb != null) cb.run();
    }

    // ── data ────────────────────────────────────────────────────────────────


    private View currentView() {
        Source source = sourceIndex < sources.size() ? sources.get(sourceIndex) : new Source(null);
        if (source.isCurrent()) {
            if (gitDiff == null) {
                return new View(null, List.of(), Map.of(), false);
            }
            return new View(gitDiff.stats(), gitDiff.files(), gitDiff.hunks(), false);
        }
        TurnDiff turn = source.turn();
        List<DiffData.DiffFile> files = new ArrayList<>();
        Map<String, List<StructuredPatchHunk>> hunks = new LinkedHashMap<>();
        for (TurnFileDiff f : turn.files()) {
            files.add(new DiffData.DiffFile(f.filePath(), f.linesAdded(), f.linesRemoved(),
                false, false, false, f.isNewFile(), false));
            hunks.put(f.filePath(), f.hunks());
        }
        files.sort((a, b) -> a.path().compareTo(b.path()));
        return new View(turn.stats(), files, hunks, true);
    }

    // ── layout ──────────────────────────────────────────────────────────────

    private int maxBodyRows() {
        // Leave room for the message panel + input; chrome rows are counted
        // inside bodyRows() already.
        return Math.max(8, terminalRows - 12);
    }

    private List<Row> buildRows(int width) {
        View view = currentView();
        List<Row> rows = new ArrayList<>();
        rows.add(Row.blank());
        rows.add(Row.divider());

        // Header: title + dim subtitle.
        Source source = sourceIndex < sources.size() ? sources.get(sourceIndex) : new Source(null);
        TurnDiff turn = source.turn();
        String title = turn != null ? "Turn " + turn.turnIndex() : "Uncommitted changes";
        String subtitle = turn != null
            ? (StringUtils.isNotEmpty(turn.userPromptPreview())
                ? "\"" + turn.userPromptPreview() + "\"" : "")
            : "(git diff HEAD)";
        rows.add(Row.title(title, subtitle));

        // Source selector (only when there are turn sources).
        if (sources.size() > 1) {
            rows.add(Row.sourceSelector());
        }

        // Stats subtitle.
        if (view.stats() != null) {
            rows.add(Row.stats(view.stats()));
        }
        rows.add(Row.blank());

        if (view.files().isEmpty()) {
            rows.add(Row.text(emptyMessage(view), LanternaTheme.welcomeDim(), false, false));
        } else if (viewMode == ViewMode.LIST) {
            appendFileListRows(rows, view);
        } else {
            appendDetailRows(rows, view, width);
        }

        rows.add(Row.blank());
        rows.add(Row.footer());
        return rows;
    }

    private String emptyMessage(View view) {
        if (view.isTurn()) return "No file changes in this turn";
        if (view.stats() != null && view.stats().filesCount() > 0) {
            return "Too many files to display details";
        }
        return "Working tree is clean";
    }

    private void appendFileListRows(List<Row> rows, View view) {
        List<DiffData.DiffFile> files = view.files();
        int start = 0;
        int end = files.size();
        boolean paginate = files.size() > MAX_VISIBLE_FILES;
        if (paginate) {
            start = Math.max(0, selectedIndex - MAX_VISIBLE_FILES / 2);
            end = start + MAX_VISIBLE_FILES;
            if (end > files.size()) {
                end = files.size();
                start = Math.max(0, end - MAX_VISIBLE_FILES);
            }
        }
        if (paginate) {
            rows.add(Row.text(start > 0
                    ? " ↑ " + start + (start == 1 ? " more file" : " more files") : " ",
                LanternaTheme.welcomeDim(), false, false));
        }
        for (int i = start; i < end; i++) {
            rows.add(Row.file(files.get(i), i == selectedIndex));
        }
        if (paginate) {
            int below = files.size() - end;
            rows.add(Row.text(below > 0
                    ? " ↓ " + below + (below == 1 ? " more file" : " more files") : " ",
                LanternaTheme.welcomeDim(), false, false));
        }
    }

    private void appendDetailRows(List<Row> rows, View view, int width) {
        DiffData.DiffFile file = selectedIndex < view.files().size()
            ? view.files().get(selectedIndex) : null;
        if (file == null) return;
        List<StructuredPatchHunk> hunks = view.hunks().getOrDefault(file.path(), List.of());

        rows.add(Row.text(file.path() + (file.isTruncated() ? " (truncated)" : ""),
            LanternaTheme.inputText(), true, false));
        rows.add(Row.divider());

        List<Row> body = new ArrayList<>();
        if (file.isUntracked()) {
            body.add(Row.text("New file not yet staged.", LanternaTheme.welcomeDim(), false, true));
            body.add(Row.text("Run `git add " + file.path() + "` to see line counts.",
                LanternaTheme.welcomeDim(), false, true));
        } else if (file.isBinary()) {
            body.add(Row.text("Binary file - cannot display diff",
                LanternaTheme.welcomeDim(), false, true));
        } else if (file.isLargeFile()) {
            body.add(Row.text("Large file - diff exceeds 1 MB limit",
                LanternaTheme.welcomeDim(), false, true));
        } else if (hunks.isEmpty()) {
            body.add(Row.text("No diff content", LanternaTheme.welcomeDim(), false, false));
        } else {
            for (StructuredPatchHunk hunk : hunks) {
                appendHunkRows(body, hunk, width, languageForPath(file.path()));
            }
            if (file.isTruncated()) {
                body.add(Row.text("… diff truncated (exceeded 400 line limit)",
                    LanternaTheme.welcomeDim(), false, true));
            }
        }

        // Viewport scroll for tall diffs (Lanterna fixed-viewport adaptation).
        int cap = maxBodyRows();
        if (body.size() > cap) {
            int maxScroll = body.size() - cap;
            if (detailScroll > maxScroll) detailScroll = maxScroll;
            List<Row> windowed = new ArrayList<>();
            if (detailScroll > 0) {
                windowed.add(Row.text(" ↑ " + detailScroll + " more lines",
                    LanternaTheme.welcomeDim(), false, false));
            }
            int take = cap - (detailScroll > 0 ? 1 : 0);
            int from = detailScroll;
            int to = Math.min(body.size(), from + take);
            boolean hasBelow = to < body.size();
            if (hasBelow) to--;
            windowed.addAll(body.subList(from, Math.max(from, to)));
            if (hasBelow) {
                windowed.add(Row.text(" ↓ " + (body.size() - to) + " more lines",
                    LanternaTheme.welcomeDim(), false, false));
            }
            rows.addAll(windowed);
        } else {
            detailScroll = 0;
            rows.addAll(body);
        }
    }

    private void appendHunkRows(List<Row> out, StructuredPatchHunk hunk, int width,
                                String language) {
        List<DiffRenderer.DiffLineView> views = DiffRenderer.renderHunk(hunk, language);

        int maxNum = 0;
        for (DiffRenderer.DiffLineView v : views) {
            if (v.lineNo() != null) maxNum = Math.max(maxNum, v.lineNo());
        }
        int digits = String.valueOf(maxNum).length();
        int gutterWidth = digits + 3;
        int avail = Math.max(1, width - LEFT_PAD - gutterWidth);
        for (DiffRenderer.DiffLineView v : views) {
            List<DiffRenderer.DiffLineView> wrapped = wrapDiffLineView(v, avail);
            for (int i = 0; i < wrapped.size(); i++) {
                out.add(Row.diffView(wrapped.get(i), gutterWidth, i > 0));
            }
        }
    }

/**
     * Wrap a rendered hunk line by character width.
     */
    private List<DiffRenderer.DiffLineView> wrapDiffLineView(DiffRenderer.DiffLineView lv, int avail) {
        int total = 0;
        for (DiffRenderer.Segment s : lv.segments()) total += s.text().length();
        if (total <= avail) return List.of(lv);
        List<DiffRenderer.DiffLineView> out = new ArrayList<>();
        List<DiffRenderer.Segment> cur = new ArrayList<>();
        int used = 0;
        for (DiffRenderer.Segment seg : lv.segments()) {
            String t = seg.text();
            int i = 0;
            while (i < t.length()) {
                if (used >= avail) {
                    out.add(chunk(lv, cur, out.isEmpty()));
                    cur = new ArrayList<>();
                    used = 0;
                }
                int take = Math.min(avail - used, t.length() - i);
                cur.add(new DiffRenderer.Segment(
                    t.substring(i, i + take), seg.kind(), seg.foreground()));
                used += take;
                i += take;
            }
        }
        if (!cur.isEmpty()) out.add(chunk(lv, cur, out.isEmpty()));
        return out;
    }

    private DiffRenderer.DiffLineView chunk(DiffRenderer.DiffLineView lv,
                                            List<DiffRenderer.Segment> segs, boolean first) {
        if (first) {
            return new DiffRenderer.DiffLineView(lv.lineNo(), lv.marker(), List.copyOf(segs));
        }
        // Continuation: no gutter, but keep marker so the bar color matches.
        return new DiffRenderer.DiffLineView(null, lv.marker(), List.copyOf(segs));
    }

    private static String languageForPath(String path) {
        if (StringUtils.isBlank(path)) return null;
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (Strings.CS.equals(name, "dockerfile")) return "dockerfile";
        if (Strings.CS.equals(name, "makefile")) return "makefile";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return null;
        return switch (name.substring(dot + 1)) {
            case "mjs", "cjs" -> "javascript";
            case "mts", "cts" -> "typescript";
            case "yml" -> "yaml";
            case "sh", "bash", "zsh" -> "shell";
            default -> name.substring(dot + 1);
        };
    }

    // ── row model ───────────────────────────────────────────────────────────

    private record Row(RowKind kind, String text, TextColor color, boolean bold, boolean italic,
                       DiffData.DiffFile file, boolean selected,
                       DiffRenderer.DiffLineView diffView,
                       String title, String subtitle, DiffData.Stats stats,
                       int gutterWidth, boolean continuation) {

        enum RowKind { BLANK, DIVIDER, TITLE, SOURCE_SELECTOR, STATS, TEXT, FILE, DIFF_LINE, FOOTER }

        static Row blank() { return new Row(RowKind.BLANK, null, null, false, false, null, false, null, null, null, null, 0, false); }
        static Row divider() { return new Row(RowKind.DIVIDER, null, null, false, false, null, false, null, null, null, null, 0, false); }
        static Row title(String title, String subtitle) { return new Row(RowKind.TITLE, null, null, false, false, null, false, null, title, subtitle, null, 0, false); }
        static Row sourceSelector() { return new Row(RowKind.SOURCE_SELECTOR, null, null, false, false, null, false, null, null, null, null, 0, false); }
        static Row stats(DiffData.Stats stats) { return new Row(RowKind.STATS, null, null, false, false, null, false, null, null, null, stats, 0, false); }
        static Row text(String text, TextColor color, boolean bold, boolean italic) { return new Row(RowKind.TEXT, text, color, bold, italic, null, false, null, null, null, null, 0, false); }
        static Row file(DiffData.DiffFile file, boolean selected) { return new Row(RowKind.FILE, null, null, false, false, file, selected, null, null, null, null, 0, false); }
        static Row diffView(DiffRenderer.DiffLineView diffView, int gutterWidth, boolean continuation) {
            return new Row(RowKind.DIFF_LINE, null, null, false, false, null, false, diffView, null, null, null, gutterWidth, continuation);
        }
        static Row footer() { return new Row(RowKind.FOOTER, null, null, false, false, null, false, null, null, null, null, 0, false); }
    }

    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        if (!active) return new TerminalSize(0, 0);
        TerminalSize parent = super.calculatePreferredSize();
        int rows = buildRows(Math.max(60, parent.getColumns())).size();
        return new TerminalSize(Math.max(60, parent.getColumns()), rows);
    }

    @Override public Interactable nextFocus(Interactable fromThis) {
        return active ? super.nextFocus(fromThis) : null;
    }

    @Override public Interactable previousFocus(Interactable fromThis) {
        return active ? super.previousFocus(fromThis) : null;
    }

    // ── renderer ────────────────────────────────────────────────────────────

    private final class DialogArea extends AbstractComponent<DialogArea> {
        @Override protected ComponentRenderer<DialogArea> createDefaultRenderer() {
            return new DialogRenderer();
        }
    }

    private final class DialogRenderer implements ComponentRenderer<DialogArea> {

        @Override
        public TerminalSize getPreferredSize(DialogArea c) {
            if (!active) return new TerminalSize(0, 0);
            return new TerminalSize(LEFT_PAD * 2 + 60, buildRows(60).size());
        }

        @Override
        public void drawComponent(TextGUIGraphics g, DialogArea c) {
            if (!active) return;
            g.fill(' ');
            int cols = g.getSize().getColumns();
            List<Row> rows = buildRows(cols);
            for (int y = 0; y < rows.size() && y < g.getSize().getRows(); y++) {
                drawRow(g, rows.get(y), y, cols);
            }
        }

        private void drawRow(TextGUIGraphics g, Row row, int y, int cols) {
            switch (row.kind()) {
                case BLANK -> { /* leave empty */ }
                case DIVIDER -> {
                    g.setForegroundColor(LanternaTheme.divider());
                    g.putString(0, y, "─".repeat(Math.max(0, cols)));
                }
                case TITLE -> {
                    g.setForegroundColor(LanternaTheme.inputText());
                    g.enableModifiers(SGR.BOLD);
                    g.putString(LEFT_PAD, y, row.title());
                    g.disableModifiers(SGR.BOLD);
                    if (StringUtils.isNotEmpty(row.subtitle())) {
                        g.setForegroundColor(LanternaTheme.welcomeDim());
                        g.putString(LEFT_PAD + row.title().length() + 1, y, row.subtitle());
                    }
                }
                case SOURCE_SELECTOR -> drawSourceSelector(g, y);
                case STATS -> drawStats(g, row.stats(), y);
                case TEXT -> {
                    g.setForegroundColor(row.color() != null ? row.color() : LanternaTheme.inputText());
                    if (row.bold()) g.enableModifiers(SGR.BOLD);
                    if (row.italic()) g.enableModifiers(SGR.ITALIC);
                    g.putString(LEFT_PAD, y, row.text());
                    if (row.italic()) g.disableModifiers(SGR.ITALIC);
                    if (row.bold()) g.disableModifiers(SGR.BOLD);
                }
                case FILE -> drawFileRow(g, row, y, cols);
                case DIFF_LINE -> drawDiffLineView(g, row, y, cols);
                case FOOTER -> {
                    g.setForegroundColor(LanternaTheme.welcomeDim());
                    String hint = viewMode == ViewMode.LIST
                        ? (sources.size() > 1
                            ? "←/→ source · ↑/↓ select · Enter view · esc close"
                            : "↑/↓ select · Enter view · esc close")
                        : "← back · esc close";
                    g.putString(LEFT_PAD, y, hint);
                }
            }
        }

        private void drawSourceSelector(TextGUIGraphics g, int y) {
            int x = LEFT_PAD;
            g.setForegroundColor(LanternaTheme.welcomeDim());
            if (sourceIndex > 0) {
                g.putString(x, y, "◀ ");
                x += 2;
            }
            for (int i = 0; i < sources.size(); i++) {
                String label = sources.get(i).isCurrent()
                    ? "Current" : "T" + sources.get(i).turn().turnIndex();
                if (i > 0) {
                    g.setForegroundColor(LanternaTheme.welcomeDim());
                    g.putString(x, y, " · ");
                    x += 3;
                }
                boolean isSelected = i == sourceIndex;
                g.setForegroundColor(isSelected
                    ? LanternaTheme.inputText() : LanternaTheme.welcomeDim());
                if (isSelected) g.enableModifiers(SGR.BOLD);
                g.putString(x, y, label);
                if (isSelected) g.disableModifiers(SGR.BOLD);
                x += label.length();
            }
            if (sourceIndex < sources.size() - 1) {
                g.setForegroundColor(LanternaTheme.welcomeDim());
                g.putString(x, y, " ▶");
            }
        }

        private void drawStats(TextGUIGraphics g, DiffData.Stats stats, int y) {
            int x = LEFT_PAD;
            g.setForegroundColor(LanternaTheme.welcomeDim());
            String base = stats.filesCount() + (stats.filesCount() == 1 ? " file" : " files")
                + " changed";
            g.putString(x, y, base);
            x += base.length();
            if (stats.linesAdded() > 0) {
                g.setForegroundColor(LanternaTheme.diffAddedWord());
                String s = " +" + stats.linesAdded();
                g.putString(x, y, s);
                x += s.length();
            }
            if (stats.linesRemoved() > 0) {
                g.setForegroundColor(LanternaTheme.diffRemovedWord());
                g.putString(x, y, " -" + stats.linesRemoved());
            }
        }

        private void drawFileRow(TextGUIGraphics g, Row row, int y, int cols) {
            DiffData.DiffFile file = row.file();
            boolean sel = row.selected();
            String pointer = sel ? "❯ " : "  ";
            // Right-aligned stats column, path truncated from the start.
            String statsText;
            boolean statsItalic = false;
            if (file.isUntracked()) {
                statsText = "untracked";
                statsItalic = true;
            } else if (file.isBinary()) {
                statsText = "Binary file";
                statsItalic = true;
            } else if (file.isLargeFile()) {
                statsText = "Large file modified";
                statsItalic = true;
            } else {
                StringBuilder sb = new StringBuilder();
                if (file.linesAdded() > 0) sb.append("+").append(file.linesAdded());
                if (file.linesAdded() > 0 && file.linesRemoved() > 0) sb.append(' ');
                if (file.linesRemoved() > 0) sb.append("-").append(file.linesRemoved());
                if (file.isTruncated()) sb.append(" (truncated)");
                statsText = sb.toString();
            }
            int statsWidth = statsText.length();
            int maxPathWidth = Math.max(20, cols - LEFT_PAD * 2 - statsWidth - 4);
            String path = truncateStart(file.path(), maxPathWidth);

            g.setForegroundColor(sel ? LanternaTheme.suggestion() : LanternaTheme.inputText());
            if (sel) g.enableModifiers(SGR.BOLD);
            g.putString(LEFT_PAD, y, pointer + path);
            if (sel) g.disableModifiers(SGR.BOLD);

            int statsX = Math.max(LEFT_PAD + pointer.length() + path.length() + 2,
                cols - LEFT_PAD - statsWidth);
            if (statsItalic) {
                g.setForegroundColor(sel ? LanternaTheme.inputText() : LanternaTheme.welcomeDim());
                g.enableModifiers(SGR.ITALIC);
                g.putString(statsX, y, statsText);
                g.disableModifiers(SGR.ITALIC);
            } else {
                // "+N -M (truncated)" with per-segment colors.
                int x = statsX;
                if (file.linesAdded() > 0) {
                    g.setForegroundColor(LanternaTheme.diffAddedWord());
                    String s = "+" + file.linesAdded();
                    g.putString(x, y, s);
                    x += s.length();
                    if (file.linesRemoved() > 0) x++;
                }
                if (file.linesRemoved() > 0) {
                    g.setForegroundColor(LanternaTheme.diffRemovedWord());
                    String s = "-" + file.linesRemoved();
                    g.putString(x, y, s);
                    x += s.length();
                }
                if (file.isTruncated()) {
                    g.setForegroundColor(LanternaTheme.welcomeDim());
                    g.putString(x, y, " (truncated)");
                }
            }
        }

        private void drawDiffLineView(TextGUIGraphics g, Row row, int y, int cols) {
            DiffRenderer.DiffLineView lv = row.diffView();
            char marker = lv.marker();
            int gw = row.gutterWidth();

            // Whole-line background bar from color-diff-napi's dedicated palette;
            // changed words get its stronger word background. Context lines and
            // the @@ header have no bar.
            TextColor barBg = null, wordBg = null;
            LanternaTheme.DiffRenderPalette palette = LanternaTheme.diffRenderPalette();
            switch (marker) {
                case '+' -> {
                    barBg = palette.addedLineBackground();
                    wordBg = palette.addedWordBackground();
                }
                case '-' -> {
                    barBg = palette.removedLineBackground();
                    wordBg = palette.removedWordBackground();
                }
                default  -> { /* context + hunk-header ('@'): no background bar */ }
            }

            int x = LEFT_PAD;
            if (!row.continuation()) {
                // Gutter: right-aligned number, a space, the marker, a space.

                // inside the gutter so the background bar starts at the content.
                int digits = Math.max(1, gw - 3);
                String num = lv.lineNo() != null ? String.valueOf(lv.lineNo()) : "";
                char mk = marker == '@' ? ' ' : marker;
                String gutter = " ".repeat(Math.max(0, digits - num.length()))
                    + num + " " + mk + " ";
                g.setBackgroundColor(barBg != null ? barBg : TextColor.ANSI.DEFAULT);
                g.setForegroundColor(switch (marker) {
                    case '+' -> palette.addedDecoration();
                    case '-' -> palette.removedDecoration();
                    default -> LanternaTheme.welcomeDim();
                });
                g.putString(x, y, gutter);
                x += gutter.length();
            } else {
                // Continuation line: blank gutter, but keep the bar color so the
                // wrapped portion stays visually attached to its line.
                String gutter = " ".repeat(gw);
                g.setBackgroundColor(barBg != null ? barBg : TextColor.ANSI.DEFAULT);
                g.putString(x, y, gutter);
                x += gutter.length();
            }

            for (DiffRenderer.Segment seg : lv.segments()) {
                TextColor bg = TextColor.ANSI.DEFAULT;
                TextColor segFg = seg.foreground() != null
                    ? LanternaTheme.toLC(seg.foreground()) : LanternaTheme.inputText();
                switch (seg.kind()) {
                    case COMMON  -> bg = barBg != null ? barBg : TextColor.ANSI.DEFAULT;
                    case ADDED   -> bg = wordBg != null ? wordBg : TextColor.ANSI.DEFAULT;
                    case REMOVED -> bg = wordBg != null ? wordBg : TextColor.ANSI.DEFAULT;
                    case HUNK    -> { segFg = LanternaTheme.subtle(); }
                }
                g.setBackgroundColor(bg);
                g.setForegroundColor(segFg);
                g.putString(x, y, seg.text());
                x += seg.text().length();
            }


            if (barBg != null && x < cols) {
                g.setBackgroundColor(barBg);
                g.putString(x, y, " ".repeat(cols - x));
            }
            g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        }
    }

    private static String truncateStart(String s, int maxWidth) {
        if (s.length() <= maxWidth) return s;
        return "…" + s.substring(s.length() - maxWidth + 1);
    }
}
