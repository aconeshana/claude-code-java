package com.claudecode.ui.lanterna.dialog;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.input.PromptHistory;
import com.claudecode.ui.lanterna.input.ContextKeybindingDispatcher;
import com.claudecode.ui.lanterna.input.SearchInput;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.keybindings.KeybindingHints;
import com.claudecode.core.text.FormatUtils;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Ctrl+R prompt picker.
 */
public final class HistorySearchDialog extends BasicWindow {

    @FunctionalInterface
    public interface HistoryLoader {
        CompletionStage<List<PromptHistory.TimestampedEntry>> load(
            PromptHistory.HistoryScope scope);
    }

    private static final int DEFAULT_VISIBLE_ROWS = 8;
    private static final int MIN_VISIBLE_ROWS = 2;
    private static final int CHROME_ROWS = 10;
    private static final int PREVIEW_ROWS = 6;
    private List<PromptHistory.TimestampedEntry> all = List.of();
    private final Consumer<PromptHistory.Entry> onSelect;
    private final HistoryLoader loader;
    private final WindowBasedTextGUI gui;
    private final ContextKeybindingDispatcher keybindings =
        new ContextKeybindingDispatcher();
    private final Map<PromptHistory.HistoryScope, List<PromptHistory.TimestampedEntry>> cache =
        new EnumMap<>(PromptHistory.HistoryScope.class);
    private final Label title = new Label("");
    private final Label queryLabel = new Label("");
    private final Label[] rows;
    private final Label preview = new Label("");
    private List<PromptHistory.TimestampedEntry> filtered = List.of();
    private boolean loading = true;
    private PromptHistory.HistoryScope scope = PromptHistory.HistoryScope.EVERYWHERE;
    private String query;
    private int queryCursor;
    private int selected;
    private int offset;
    private Integer hovered;
    private CompletableFuture<List<PromptHistory.TimestampedEntry>> scopeLoad;
    private final int rowWidth;
    private final int previewWidth;
    private final SearchInput queryEditor;

    private HistorySearchDialog(WindowBasedTextGUI gui, HistoryLoader loader, String initialQuery,
                                UserKeybindingsStore keybindingsStore,
                                Consumer<PromptHistory.Entry> onSelect) {
        super("Search prompts");
        this.gui = gui;
        this.loader = loader;
        this.query = initialQuery == null ? "" : initialQuery;
        this.queryCursor = this.query.length();
        this.queryEditor = new SearchInput(new SearchInput.Listener() {
            @Override public void onExit() { close(); }
            @Override public void onChange() {}
        }, false);
        this.queryEditor.reset(this.query);
        this.onSelect = onSelect;
        int terminalRows = gui.getScreen().getTerminalSize().getRows();
        int terminalColumns = gui.getScreen().getTerminalSize().getColumns();
        int visibleRows = Math.max(MIN_VISIBLE_ROWS,
            Math.min(DEFAULT_VISIBLE_ROWS, terminalRows - CHROME_ROWS));
        this.rows = new Label[visibleRows];
        boolean previewOnRight = terminalColumns >= 100;
        int listWidth = previewOnRight
            ? Math.floorDiv(terminalColumns - 6, 2) : terminalColumns - 6;
        this.rowWidth = Math.max(20, listWidth - 9);
        this.previewWidth = previewOnRight
            ? Math.max(20, terminalColumns - listWidth - 12)
            : Math.max(20, terminalColumns - 10);
        setHints(Set.of(Window.Hint.CENTERED));
        Panel root = new Panel(new LinearLayout(Direction.VERTICAL));
        title.addStyle(SGR.BOLD);
        title.setForegroundColor(LanternaTheme.suggestion());
        root.addComponent(title);
        Panel list = new Panel(new LinearLayout(Direction.VERTICAL));
        for (int i = 0; i < rows.length; i++) {
            rows[i] = new Label("");
            list.addComponent(rows[i]);
        }
        if (previewOnRight) {
            Panel content = new Panel(new LinearLayout(Direction.HORIZONTAL));
            content.addComponent(list);
            Panel previewPanel = new Panel(new LinearLayout(Direction.VERTICAL));
            previewPanel.addComponent(new Label("Preview:"));
            previewPanel.addComponent(preview);
            content.addComponent(previewPanel);
            root.addComponent(content);
        } else {
            root.addComponent(list);
            root.addComponent(new Label("Preview:"));
            root.addComponent(preview);
        }
        root.addComponent(queryLabel);
        String scopeShortcut = KeybindingHints.shortcut(keybindingsStore,
            "historySearch:cycleScope", "HistorySearch", "ctrl+s");
        root.addComponent(new Label("↑↓ select · Enter use · " + scopeShortcut
            + " scope · Esc cancel"));
        setComponent(root);
        refilter();
    }

    public static void open(WindowBasedTextGUI gui,
                            HistoryLoader loader,
                            String initialQuery,
                            UserKeybindingsStore keybindingsStore,
                            Consumer<PromptHistory.Entry> onSelect) {
        HistorySearchDialog dialog = new HistorySearchDialog(
            gui, loader, initialQuery, keybindingsStore, onSelect);
        dialog.keybindings.setStore(keybindingsStore);
        dialog.loadScope();
        gui.addWindowAndWait(dialog);
    }

    @Override
    public boolean handleInput(KeyStroke key) {
        if (key instanceof MouseAction mouse && handleMouse(mouse)) return true;
        ContextKeybindingDispatcher.Result binding = keybindings.resolve("HistorySearch", key);
        if (binding instanceof ContextKeybindingDispatcher.Result.Consumed) return true;
        if (binding instanceof ContextKeybindingDispatcher.Result.Action action
                && Strings.CS.equals(action.value(), "historySearch:cycleScope")) {
            cycleScope();
            return true;
        }
        if (key.getKeyType() == KeyType.ESCAPE || isCtrl(key, 'c') || isCtrl(key, 'g')
                || (isCtrl(key, 'd') && query.isEmpty())) {
            close();
            return true;
        }
        if (key.getKeyType() == KeyType.ENTER || key.getKeyType() == KeyType.TAB) {
            selectFocused();
            return true;
        }
        if (key.getKeyType() == KeyType.ARROW_UP || isCtrl(key, 'p')) {
            moveFocus(1);
            render();
            return true;
        }
        if (key.getKeyType() == KeyType.ARROW_DOWN || isCtrl(key, 'n')) {
            moveFocus(-1);
            render();
            return true;
        }
        if (key.getKeyType() == KeyType.PAGE_UP || key.getKeyType() == KeyType.PAGE_DOWN) {
            int direction = key.getKeyType() == KeyType.PAGE_UP ? 1 : -1;
            moveFocus(direction * rows.length);
            render();
            return true;
        }
        String previousQuery = queryEditor.query();
        int previousCursor = queryEditor.cursorOffset();
        queryEditor.handleKey(key);
        query = queryEditor.query();
        queryCursor = queryEditor.cursorOffset();
        if (!Strings.CS.equals(previousQuery, query)) refilter();
        else if (previousCursor != queryCursor) render();
        return true;
    }

    private static boolean isCtrl(KeyStroke key, char character) {
        return key.getKeyType() == KeyType.CHARACTER && key.getCharacter() != null
            && key.isCtrlDown()
            && Character.toLowerCase(key.getCharacter()) == character;
    }

    private void cycleScope() {
        scope = scope.next();
        loadScope();
    }

    private void selectFocused() {
        if (filtered.isEmpty()) return;
        selectEntry(filtered.get(selected));
    }

    private void selectEntry(PromptHistory.TimestampedEntry selectedEntry) {
        selectedEntry.resolveAsync().whenComplete((entry, failure) ->
            gui.getGUIThread().invokeLater(() -> {
                if (failure != null || entry == null) return;
                onSelect.accept(entry);
                close();
            }));
    }

    private void moveFocus(int delta) {
        hovered = null;
        if (filtered.isEmpty()) return;
        selected = Math.max(0, Math.min(filtered.size() - 1, selected + delta));
        if (selected < offset) offset = selected;
        if (selected >= offset + rows.length) offset = selected - rows.length + 1;
    }


    private boolean handleMouse(MouseAction mouse) {
        MouseActionType action = mouse.getActionType();
        if (action == MouseActionType.SCROLL_UP || action == MouseActionType.SCROLL_DOWN) {
            int maximumOffset = Math.max(0, filtered.size() - rows.length);
            int delta = action == MouseActionType.SCROLL_UP ? 1 : -1;
            offset = Math.max(0, Math.min(maximumOffset, offset + delta));
            render();
            return true;
        }
        Integer rowIndex = entryIndexAt(mouse);
        if (action == MouseActionType.MOVE) {
            if (!Objects.equals(hovered, rowIndex)) {
                hovered = rowIndex;
                render();
            }
            return true;
        }
        if (action == MouseActionType.CLICK_RELEASE && rowIndex != null) {
            selectEntry(filtered.get(rowIndex));
            return true;
        }
        return action == MouseActionType.CLICK_DOWN && rowIndex != null;
    }

    private Integer entryIndexAt(MouseAction mouse) {
        int mouseRow = mouse.getPosition().getRow();
        int mouseColumn = mouse.getPosition().getColumn();
        int visibleCount = Math.min(rows.length, Math.max(0, filtered.size() - offset));
        int emptyRows = rows.length - visibleCount;
        for (int visualRow = emptyRows; visualRow < rows.length; visualRow++) {
            Label row = rows[visualRow];
            int top = row.getGlobalPosition().getRow();
            int left = row.getGlobalPosition().getColumn();
            if (mouseRow != top || mouseColumn < left
                    || mouseColumn >= left + row.getSize().getColumns()) continue;
            int index = offset + visibleCount - 1 - (visualRow - emptyRows);
            return index >= 0 && index < filtered.size() ? index : null;
        }
        return null;
    }

    static List<PromptHistory.TimestampedEntry> filter(
            List<PromptHistory.TimestampedEntry> entries, String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return List.copyOf(entries);
        List<PromptHistory.TimestampedEntry> exact = new ArrayList<>();
        List<PromptHistory.TimestampedEntry> fuzzy = new ArrayList<>();
        for (PromptHistory.TimestampedEntry entry : entries) {
            String text = entry.display().toLowerCase(Locale.ROOT);
            if (Strings.CS.contains(text, needle)) exact.add(entry);
            else if (isSubsequence(text, needle)) fuzzy.add(entry);
        }
        exact.addAll(fuzzy);
        return exact;
    }

    private static boolean isSubsequence(String text, String query) {
        int matched = 0;
        for (int i = 0; i < text.length() && matched < query.length(); i++) {
            if (text.charAt(i) == query.charAt(matched)) matched++;
        }
        return matched == query.length();
    }

    private void refilter() {
        filtered = filter(all, query);
        selected = 0;
        offset = 0;
        hovered = null;
        render();
    }

    private void render() {
        title.setText("Search prompts · " + scope.label());
        if (query.isEmpty()) {
            queryLabel.setText("⌕ ▏Filter history…");
        } else {
            queryLabel.setText("⌕ " + query.substring(0, queryCursor) + "▏"
                + query.substring(queryCursor));
        }
        int maximumOffset = Math.max(0, filtered.size() - rows.length);
        offset = Math.max(0, Math.min(maximumOffset, offset));
        int visibleCount = Math.min(rows.length, Math.max(0, filtered.size() - offset));
        int emptyRows = rows.length - visibleCount;
        for (int i = 0; i < rows.length; i++) {
            if (i < emptyRows) {
                rows[i].setText(i == rows.length - 1 && filtered.isEmpty()
                    ? "  " + emptyMessage(loading, query) : "");
                rows[i].setForegroundColor(null);
                continue;
            }
            int index = offset + visibleCount - 1 - (i - emptyRows);
            PromptHistory.TimestampedEntry entry = filtered.get(index);
            String age = FormatUtils.formatRelativeTimeAgo(
                Instant.ofEpochMilli(entry.timestamp()), FormatUtils.RelativeTimeStyle.NARROW);
            String firstLine = entry.display().split("\\n", 2)[0];
            rows[i].setText((index == selected ? "❯ " : "  ") + String.format("%-8s", age)
                + " " + FormatUtils.truncate(firstLine, rowWidth));
            rows[i].setForegroundColor(index == selected ? LanternaTheme.suggestion() : null);
        }
        if (filtered.isEmpty()) {
            preview.setText("");
        } else {
            int previewIndex = hovered != null && hovered >= 0 && hovered < filtered.size()
                ? hovered : selected;
            preview.setText(renderPreview(filtered.get(previewIndex).display(), previewWidth));
        }
    }

    static String renderPreview(String display, int width) {
        List<String> wrapped = new ArrayList<>();
        for (String logicalLine : display.split("\\n", -1)) {
            if (StringUtils.isBlank(logicalLine)) continue;
            wrapped.addAll(FormatUtils.wrapText(logicalLine, width));
        }
        boolean overflow = wrapped.size() > PREVIEW_ROWS;
        int shownCount = Math.min(wrapped.size(), overflow ? PREVIEW_ROWS - 1 : PREVIEW_ROWS);
        StringBuilder shown = new StringBuilder();
        for (int i = 0; i < shownCount; i++) shown.append(wrapped.get(i)).append('\n');
        int more = wrapped.size() - shownCount;
        if (more > 0) shown.append("… +").append(more).append(" more lines");
        return shown.toString().stripTrailing();
    }

    static String emptyMessage(boolean loading, String query) {
        if (loading) return "Loading…";
        return StringUtils.isBlank(query) ? "No history yet" : "No matching prompts";
    }

    private void loadScope() {
        if (scopeLoad != null && !scopeLoad.isDone()) scopeLoad.cancel(true);
        List<PromptHistory.TimestampedEntry> cached = cache.get(scope);
        if (cached != null) {
            loading = false;
            all = cached;
            refilter();
            return;
        }
        PromptHistory.HistoryScope requested = scope;
        loading = true;
        all = List.of();
        refilter();
        CompletableFuture<List<PromptHistory.TimestampedEntry>> requestedLoad =
            loader.load(requested).toCompletableFuture();
        scopeLoad = requestedLoad;
        requestedLoad.whenComplete((loaded, failure) ->
            gui.getGUIThread().invokeLater(() -> {
                if (requestedLoad.isCancelled()) return;
                List<PromptHistory.TimestampedEntry> result =
                    failure == null && loaded != null ? List.copyOf(loaded) : List.of();
                cache.put(requested, result);
                if (scope != requested) return;
                loading = false;
                all = result;
                refilter();
            }));
    }

    @Override
    public void close() {
        if (scopeLoad != null && !scopeLoad.isDone()) scopeLoad.cancel(true);
        super.close();
    }
}
