package com.claudecode.ui.lanterna.input;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.message.PastedContent;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * Up/Down/Ctrl+R history navigation for {@link InputPanel}, extracted so the index / draft / cache
 * state machine and the reverse-i-search logic live in one cohesive, Lanterna-light unit that is
 * unit-testable through a fake {@link InputEditingSurface}.
 */
final class InputHistoryController {

    private static final Logger log = LoggerFactory.getLogger(InputHistoryController.class);
    private static final int HISTORY_CHUNK_SIZE = 10;
    private static final int MAX_HISTORY = 100;

    private final InputEditingSurface box;

    private PromptHistory promptHistory;
    private String historySessionId = null;
    private String historyProject = null;
    private Supplier<List<PromptHistory.Entry>> liveHistorySupplier;

    // ── Navigation state ─────────────────────────────────────────────────────
    private int historyIndex = 0;
    private int appliedHistoryIndex = 0;
    private String historyDraft = null;                 // draft saved on first Up
    private InputPanel.Mode historyDraftMode = null;    // modeOverride saved with draft
    private String historyModeFilter = null;            // "!" for bash, null for all
    private List<PromptHistory.Entry> historyCache = null; // lazy, cleared on submit
    private int historyCacheLoadTarget;
    private boolean historyExhausted;
    private Integer historyTotal;
    private boolean historyCountLoading;
    private static final Object HISTORY_LOAD_LOCK = new Object();
    private static CompletableFuture<List<PromptHistory.Entry>> pendingHistoryLoad;
    private static int pendingHistoryLoadTarget;
    private static PromptHistory pendingHistorySource;
    private static String pendingHistoryProject;
    private static String pendingHistorySessionId;
    private static String pendingHistoryModeFilter;
    private long historyContextGeneration;
    private long navigationRequestGeneration;
    private long searchRequestGeneration;
    private long historyCountRequestGeneration;
    private Map<Integer, PastedContent> historyDraftPasted = null;
    private final Map<Integer, PromptHistory.Entry> editedHistoryEntries = new HashMap<>();
    private boolean searchHintShown = false;            // once per session (persists across submits)

    // ── Reverse-i-search state ───────────────────────────────────────────────
    private boolean searching = false;
    private String searchQuery = "";
    private String searchDraft = null;
    private InputPanel.Mode searchDraftMode = InputPanel.Mode.NORMAL;
    private Map<Integer, PastedContent> searchDraftPasted = Map.of();
    private int searchDraftCursor;
    private PromptHistory.Entry searchMatch;
    private PromptHistory.HistoryReader searchReader;
    private final Set<String> seenSearchDisplays = new LinkedHashSet<>();
    private boolean searchFailedMatch;

    /**
     * True once the user has typed/edited the text since the last history apply. Lifts suggestion
     * suppression (the edited line is detached from the history cursor) WITHOUT tearing down the
     * navigation state — so arrowing still steps correctly and Down still returns to the draft.
     * Reset to false whenever navigation itself drives the text (up/down/search).
     */
    private boolean editedSinceApply = false;

    InputHistoryController(InputEditingSurface box) {
        this.box = box;
    }

    void setPromptHistory(PromptHistory history) {
        if (this.promptHistory == history) return;
        this.promptHistory = history;
        invalidateHistoryContext();
    }

    void setContext(String sessionId, String project) {
        if (Objects.equals(historySessionId, sessionId)
                && Objects.equals(historyProject, project)) return;
        this.historySessionId = sessionId;
        this.historyProject = project;
        invalidateHistoryContext();
    }

    void setLiveHistorySupplier(Supplier<List<PromptHistory.Entry>> supplier) {
        liveHistorySupplier = supplier;
    }

    /** Record a submitted entry into history (no-op if no PromptHistory is wired). */
    void addEntry(String display, String cwd) {
        if (promptHistory != null) {
            promptHistory.addEntry(display, historySessionId, cwd,
                historyProject != null ? historyProject : cwd, Map.of());
        }
    }

    boolean isSearching() {
        return searching;
    }

    String searchDraftForTest() { return searchDraft; }
    String historyModeFilterForTest() { return historyModeFilter; }

    boolean isNavigating() {
        return (historyIndex > 0 || searching) && !editedSinceApply;
    }


    boolean atBottom() {
        return historyIndex == 0;
    }

    boolean hasHistoryCursor() {
        return historyIndex > 0;
    }

    /**
     * The user typed/edited the text. Detaches the current line from the history cursor for
     * suppression purposes only — navigation state (index / draft / cache) is preserved so Up
     * keeps stepping and Down still returns to the saved draft. Called by {@link InputPanel} from
     * its {@code byUser} text-change listener.
     */
    void onUserEdit() {
        editedSinceApply = historyIndex > 0 && !Objects.equals(
            box.currentText(), appliedEntryText());
        updateHistoryLabel();
    }

    /**
     * Reset the navigation state (index / draft / cache / mode filter) after a submit. Does NOT
     * touch {@code searchHintShown} (the search affordance is once-per-session) nor the caller's
     * own escOnce / modeOverride / hint — {@link InputPanel#resetHistory} handles those.
     */
    void reset() {
        historyIndex = 0;
        appliedHistoryIndex = 0;
        historyDraft = null;
        historyDraftMode = null;
        historyDraftPasted = null;
        historyCache = null;
        historyCacheLoadTarget = 0;
        historyExhausted = false;
        historyTotal = null;
        historyCountLoading = false;
        historyModeFilter = null;
        editedHistoryEntries.clear();
        editedSinceApply = false;
        navigationRequestGeneration++;
        searchRequestGeneration++;
        historyCountRequestGeneration++;
    }

    /**
     * Navigate backward (Up / Ctrl+P).
     */
    TextBox.Result up() {
        if (promptHistory == null) {
            log.debug("[history-up] promptHistory is null, returning MOVE_FOCUS_UP");
            // Do NOT re-enter the textBox override — let Lanterna move focus up instead.
            return TextBox.Result.MOVE_FOCUS_UP;
        }
        editedSinceApply = false; // navigating again re-attaches to the history cursor
        // First press: save draft + pasted contents and set mode filter.
        if (historyIndex == 0) {
            boolean hasDraft = !StringUtils.isBlank(box.currentText());
            historyDraft = hasDraft ? box.currentText() : null;
            historyDraftMode = hasDraft ? box.currentModeOverride() : null;
            historyDraftPasted = hasDraft ? box.snapshotPasted() : null;
            String nextModeFilter = box.currentModeOverride() == InputPanel.Mode.BASH ? "!" : null;
            if (!Objects.equals(historyModeFilter, nextModeFilter)) {
                historyCache = null;
                historyCacheLoadTarget = 0;
                historyExhausted = false;
                historyTotal = null;
                historyCountLoading = false;
                historyCountRequestGeneration++;
                editedHistoryEntries.clear();
            }
            historyModeFilter = nextModeFilter;
        } else {
            rememberCurrentEdit();
        }
        List<PromptHistory.Entry> liveHistory = liveHistorySupplier == null
            ? null : liveHistorySupplier.get();
        if (liveHistory != null) mergeLiveHistory(liveHistory);
        if (historyIndex == 0) {
            if (liveHistory != null) {
                historyTotal = historyCache == null ? 0 : historyCache.size();
            } else {
                loadHistoryTotal();
            }
        }

        int target = historyIndex;
        if (historyCache != null && target >= historyCache.size() && historyExhausted) {
            return TextBox.Result.HANDLED;
        }
        long requestGeneration = ++navigationRequestGeneration;
        long contextGeneration = historyContextGeneration;

        if (historyCache != null && target < historyCache.size()) {
            historyIndex = target + 1;
            applyHistoryTarget(target, false);
            return TextBox.Result.HANDLED;
        }

        int loadTarget = historyLoadTarget(target + 1);
        if (liveHistory != null) return TextBox.Result.HANDLED;
        loadHistoryEntries(loadTarget, historyModeFilter).whenComplete((entries, failure) ->
            box.invokeLater(() -> completeHistoryUp(
                requestGeneration, contextGeneration, target, loadTarget, entries, failure)));

        return TextBox.Result.HANDLED;
    }

    /**
     * Navigate forward (Down / Ctrl+N).
     */
    TextBox.Result down() {
        if (promptHistory == null) {
            return TextBox.Result.HANDLED;
        }
        navigationRequestGeneration++;
        if (historyIndex == 0) {
            // Not navigating history — Down is a no-op on a single-line box; do NOT re-enter the
            // override (SINGLE_LINE row always == lineCount-1, which would recurse).
            return TextBox.Result.HANDLED;
        }
        editedSinceApply = false; // navigating again re-attaches to the history cursor
        rememberCurrentEdit();
        historyIndex--;
        if (historyIndex == 0) {
            appliedHistoryIndex = 0;
            InputPanel.Mode restoredMode = historyDraft != null
                ? historyDraftMode
                : (Strings.CS.equals("!", historyModeFilter)
                    ? InputPanel.Mode.BASH : InputPanel.Mode.NORMAL);
            box.restoreDraft(historyDraft != null ? historyDraft : "", restoredMode,
                historyDraftPasted != null ? historyDraftPasted : Map.of(), true);
        } else {

            if (historyCache != null && historyIndex <= historyCache.size()) {
                PromptHistory.Entry entry = entryAt(historyIndex - 1);
                box.applyEntry(entry, true);
                appliedHistoryIndex = historyIndex;
            }
        }
        updateHistoryLabel();
        box.refreshModeAndQuery();
        return TextBox.Result.HANDLED;
    }

    /**
     * Ctrl+R — enter reverse history search, or cycle to the next match when already searching.
     * The user types a substring and the box shows the most recent matching entry; Enter accepts,
     * Escape cancels (see {@link #handleSearchKey}).
     */
    TextBox.Result toggleSearch() {
        editedSinceApply = false;
        if (!searching) {
            searching = true;
            searchQuery = "";
            searchDraft = box.currentText();
            searchDraftMode = box.currentModeOverride();
            searchDraftPasted = Map.copyOf(box.snapshotPasted());
            searchDraftCursor = box.currentCursorOffset();
            searchMatch = null;
            searchFailedMatch = false;
            restartSearchReader();
            box.setHistorySearchStatus("", false);
            box.setHistorySearchHighlight(0, 0);
        } else {
            searchHistory(true);
        }
        return TextBox.Result.HANDLED;
    }

    /** Handle a key while in search mode. Returns HANDLED if consumed, {@code null} to let it pass. */
    TextBox.Result handleSearchKey(KeyStroke key) {
        if (key.getKeyType() == KeyType.ESCAPE || key.getKeyType() == KeyType.TAB) {
            handleSearchAction("historySearch:accept");
            return TextBox.Result.HANDLED;
        }
        if (key.getKeyType() == KeyType.ENTER) {
            return handleSearchAction("historySearch:execute")
                ? TextBox.Result.HANDLED : null;
        }
        if (key.getKeyType() == KeyType.CHARACTER) {
            char ch = key.getCharacter();
            if (key.isCtrlDown() && Character.toLowerCase(ch) == 'r') {
                handleSearchAction("historySearch:next");
                return TextBox.Result.HANDLED;
            }
            if (key.isCtrlDown() && Character.toLowerCase(ch) == 'c') {
                handleSearchAction("historySearch:cancel");
                return TextBox.Result.HANDLED;
            }
            if (!Character.isISOControl(ch)) {
                searchQuery += ch;
                box.setHistorySearchStatus(searchQuery, searchFailedMatch);
                if (searchMatch != null && !searchFailedMatch) {
                    box.setHistorySearchHighlight(box.currentCursorOffset(), searchQuery.length());
                }
                searchHistory(false);
                return TextBox.Result.HANDLED;
            }
        }
        if (key.getKeyType() == KeyType.BACKSPACE) {
            if (searchQuery.isEmpty()) {
                handleSearchAction("historySearch:cancel");
            } else {
                searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
                box.setHistorySearchStatus(searchQuery, searchFailedMatch);
                if (searchMatch != null && !searchFailedMatch) {
                    box.setHistorySearchHighlight(box.currentCursorOffset(), searchQuery.length());
                }
                searchHistory(false);
            }
            return TextBox.Result.HANDLED;
        }
        return null; // not consumed
    }

    /**
     * Dispatch a resolved HistorySearch-context action. Returns {@code false}
     * only for execute, signalling that the owning input should submit after
     * the search state has been cleared.
     */
    boolean handleSearchAction(String action) {
        return switch (action) {
            case "historySearch:next" -> {
                searchHistory(true);
                yield true;
            }
            case "historySearch:accept" -> {

                clearSearchState();
                yield true;
            }
            case "historySearch:cancel" -> {

                box.restoreSearchDraft(searchDraft, box.currentModeOverride(),
                    searchDraftPasted, searchDraftCursor);
                clearSearchState();
                yield true;
            }
            case "historySearch:execute" -> {
                boolean shouldSubmit = searchQuery.isEmpty() || searchMatch != null;
                if (!searchQuery.isEmpty() && searchMatch != null) applySearchMatch(searchMatch);
                else if (searchQuery.isEmpty()) box.restoreSearchDraft(searchDraft,
                    searchDraftMode, searchDraftPasted, searchDraftCursor);
                clearSearchState();
                yield !shouldSubmit;
            }
            default -> false;
        };
    }

    private void clearSearchState() {
        searching = false;
        searchQuery = "";
        searchMatch = null;
        searchDraft = null;
        searchDraftMode = InputPanel.Mode.NORMAL;
        searchDraftPasted = Map.of();
        searchDraftCursor = 0;
        searchFailedMatch = false;
        box.setHistorySearchStatus(null, false);
        box.setHistorySearchHighlight(0, 0);
        closeSearchReader();
        seenSearchDisplays.clear();
        searchRequestGeneration++;
    }

    /** Exit search without touching the box — used when a search key falls through unconsumed. */
    void exitSearch() {
        clearSearchState();
    }

    private void searchHistory(boolean resume) {
        if (!searching || promptHistory == null) return;
        if (searchQuery.isEmpty()) {
            restartSearchReader();
            searchMatch = null;
            searchFailedMatch = false;
            box.setHistorySearchStatus("", false);
            box.setHistorySearchHighlight(0, 0);
            box.restoreSearchDraft(searchDraft, searchDraftMode,
                searchDraftPasted, searchDraftCursor);
            return;
        }
        if (!resume) restartSearchReader();
        if (searchReader == null) return;
        String queryAtRequest = searchQuery;
        long requestGeneration = ++searchRequestGeneration;
        long contextGeneration = historyContextGeneration;
        searchReader.findNextAsync(queryAtRequest, seenSearchDisplays)
            .whenComplete((entry, failure) -> box.invokeLater(() -> {
                if (failure != null || !searching
                        || requestGeneration != searchRequestGeneration
                        || contextGeneration != historyContextGeneration
                        || !Objects.equals(queryAtRequest, searchQuery)) return;
                if (entry == null) {
                    searchFailedMatch = true;
                    box.setHistorySearchStatus(searchQuery, true);
                    box.setHistorySearchHighlight(0, 0);
                    return;
                }
                searchMatch = entry;
                searchFailedMatch = false;
                box.setHistorySearchStatus(searchQuery, false);
                applySearchMatch(entry);
                box.setHistorySearchHighlight(box.currentCursorOffset(), searchQuery.length());
            }));
    }

    private void restartSearchReader() {
        closeSearchReader();
        searchReader = promptHistory != null ? promptHistory.openGlobalHistoryReader() : null;
        seenSearchDisplays.clear();
    }

    private void closeSearchReader() {
        if (searchReader == null) return;
        searchReader.close();
        searchReader = null;
    }


    private CompletableFuture<List<PromptHistory.Entry>> loadHistoryEntries(
            int minimumCount, String modeFilter) {
        PromptHistory history = promptHistory;
        if (history == null) return CompletableFuture.completedFuture(List.of());
        int target = historyLoadTarget(minimumCount);
        String project = historyProject != null
            ? historyProject : System.getProperty("user.dir");
        String sessionId = historySessionId;

        synchronized (HISTORY_LOAD_LOCK) {
            boolean sameRequest = pendingHistoryLoad != null
                && pendingHistorySource == history
                && Objects.equals(pendingHistoryProject, project)
                && Objects.equals(pendingHistorySessionId, sessionId)
                && Objects.equals(pendingHistoryModeFilter, modeFilter);
            if (sameRequest && pendingHistoryLoadTarget >= target) {
                return pendingHistoryLoad;
            }

            CompletableFuture<List<PromptHistory.Entry>> previous = pendingHistoryLoad;
            CompletableFuture<Void> ready = previous == null
                ? CompletableFuture.completedFuture(null)
                : previous.handle((_, _) -> null);
            CompletableFuture<List<PromptHistory.Entry>> next = ready.thenCompose(_ ->
                history.getEntriesWithPastedAsync(target, project, sessionId, modeFilter));
            pendingHistoryLoad = next;
            pendingHistoryLoadTarget = target;
            pendingHistorySource = history;
            pendingHistoryProject = project;
            pendingHistorySessionId = sessionId;
            pendingHistoryModeFilter = modeFilter;
            next.whenComplete((_, _) -> clearPendingHistoryLoad(next));
            return next;
        }
    }

    private static void clearPendingHistoryLoad(
            CompletableFuture<List<PromptHistory.Entry>> completed) {
        synchronized (HISTORY_LOAD_LOCK) {
            if (pendingHistoryLoad != completed) return;
            pendingHistoryLoad = null;
            pendingHistoryLoadTarget = 0;
            pendingHistorySource = null;
            pendingHistoryProject = null;
            pendingHistorySessionId = null;
            pendingHistoryModeFilter = null;
        }
    }

    private void completeHistoryUp(long requestGeneration, long contextGeneration, int target,
                                   int loadTarget,
                                   List<PromptHistory.Entry> entries, Throwable failure) {
        if (requestGeneration != navigationRequestGeneration
                || contextGeneration != historyContextGeneration) return;
        if (failure != null || entries == null || entries.isEmpty()) return;
        if (historyCache == null || entries.size() > historyCache.size()) {
            historyCache = new ArrayList<>(entries);
        }
        historyCacheLoadTarget = Math.max(historyCacheLoadTarget, loadTarget);
        historyExhausted = entries.size() < loadTarget;
        if (target >= historyCache.size()) return;
        historyIndex = target + 1;
        applyHistoryTarget(target, false);
    }

    private void applyHistoryTarget(int target, boolean cursorToStart) {
        if (historyCache == null || target < 0 || target >= historyCache.size()) return;
        PromptHistory.Entry entry = entryAt(target);
        log.debug("[history-up] applying entry[{}]: display='{}'", target,
            entry.display().length() > 60 ? entry.display().substring(0, 60) + "..." : entry.display());
        box.applyEntry(entry, cursorToStart);
        appliedHistoryIndex = target + 1;
        if (historyIndex >= 2 && liveHistorySupplier == null && !searchHintShown) {
            searchHintShown = true;
            box.showHint(box.historySearchShortcut() + " to search history",
                LanternaTheme.welcomeDim(), 5_000);
        }
        updateHistoryLabel();
    }

    private PromptHistory.Entry entryAt(int target) {
        PromptHistory.Entry edited = editedHistoryEntries.get(target);
        return edited != null ? edited : historyCache.get(target);
    }

    private void rememberCurrentEdit() {
        if (historyCache == null || historyIndex <= 0 || appliedHistoryIndex != historyIndex
                || historyIndex > historyCache.size()) return;
        PromptHistory.Entry original = historyCache.get(historyIndex - 1);
        String text = box.currentText();
        String display = box.currentModeOverride() == InputPanel.Mode.BASH ? "!" + text : text;
        editedHistoryEntries.put(historyIndex - 1, new PromptHistory.Entry(
            display, original.sessionId(), original.timestamp(), original.project(), original.cwd(),
            Map.copyOf(box.snapshotPasted())));
    }

    private void invalidateHistoryContext() {
        historyContextGeneration++;
        navigationRequestGeneration++;
        searchRequestGeneration++;
        historyCountRequestGeneration++;
        historyCache = null;
        historyCacheLoadTarget = 0;
        historyExhausted = false;
        historyTotal = null;
        historyCountLoading = false;
        editedHistoryEntries.clear();
        box.setHistoryLabel(null);
    }

    private void mergeLiveHistory(List<PromptHistory.Entry> supplied) {
        List<PromptHistory.Entry> filtered = historyModeFilter == null
            ? List.copyOf(supplied)
            : supplied.stream()
                .filter(entry -> Strings.CS.startsWith(entry.display(), historyModeFilter))
                .toList();
        List<PromptHistory.Entry> previous = historyCache == null ? List.of() : historyCache;
        if (filtered.size() <= previous.size()) {
            if (historyCache == null) historyCache = filtered;
            historyExhausted = true;
            return;
        }
        int added = filtered.size() - previous.size();
        boolean prepended = previous.isEmpty()
            || (added < filtered.size()
                && Objects.equals(filtered.get(added).display(), previous.getFirst().display()));
        if (!prepended) {
            historyIndex = 0;
            appliedHistoryIndex = 0;
            editedHistoryEntries.clear();
        } else if (!previous.isEmpty()) {
            if (historyIndex > 0) historyIndex += added;
            if (appliedHistoryIndex > 0) appliedHistoryIndex += added;
            if (!editedHistoryEntries.isEmpty()) {
                Map<Integer, PromptHistory.Entry> shifted = new HashMap<>();
                editedHistoryEntries.forEach((index, entry) -> shifted.put(index + added, entry));
                editedHistoryEntries.clear();
                editedHistoryEntries.putAll(shifted);
            }
        }
        historyCache = new ArrayList<>(filtered);
        historyCacheLoadTarget = filtered.size();
        historyExhausted = true;
        historyTotal = filtered.size();
        updateHistoryLabel();
    }

    private void loadHistoryTotal() {
        if (historyCountLoading || promptHistory == null) return;
        historyCountLoading = true;
        long contextGeneration = historyContextGeneration;
        long requestGeneration = ++historyCountRequestGeneration;
        String project = historyProject != null
            ? historyProject : System.getProperty("user.dir");
        promptHistory.countEntriesAsync(project, historyModeFilter).whenComplete((count, failure) ->
            box.invokeLater(() -> {
                if (contextGeneration != historyContextGeneration
                        || requestGeneration != historyCountRequestGeneration) return;
                historyCountLoading = false;
                historyTotal = failure == null ? count : null;
                updateHistoryLabel();
            }));
    }

    private String appliedEntryText() {
        if (historyCache == null || appliedHistoryIndex <= 0
                || appliedHistoryIndex > historyCache.size()) return null;
        String display = entryAt(appliedHistoryIndex - 1).display();
        return Strings.CS.startsWith(display, "!") ? display.substring(1) : display;
    }

    private void updateHistoryLabel() {
        if (historyIndex == 0 || editedSinceApply) {
            box.setHistoryLabel(null);
            return;
        }
        if (historyTotal == null) {
            box.setHistoryLabel("History");
            return;
        }
        box.setHistoryLabel("History "
            + Math.max(1, historyTotal - historyIndex + 1) + "/" + historyTotal);
    }

    private static int historyLoadTarget(int minimumCount) {
        return Math.min(MAX_HISTORY,
            Math.max(HISTORY_CHUNK_SIZE,
                ((minimumCount + HISTORY_CHUNK_SIZE - 1) / HISTORY_CHUNK_SIZE)
                    * HISTORY_CHUNK_SIZE));
    }

    private void applySearchMatch(PromptHistory.Entry match) {
        String clean = Strings.CS.startsWith(match.display(), "!")
            ? match.display().substring(1) : match.display();
        int cursor = clean.toLowerCase(java.util.Locale.ROOT)
            .lastIndexOf(searchQuery.toLowerCase(java.util.Locale.ROOT));
        if (cursor < 0) cursor = match.display().toLowerCase(java.util.Locale.ROOT)
            .lastIndexOf(searchQuery.toLowerCase(java.util.Locale.ROOT));
        box.applySearchEntry(match, Math.max(0, cursor));
        box.showHint((searchFailedMatch ? "no matching prompt: " : "(reverse-i-search) `")
                + searchQuery + (searchFailedMatch ? "" : "': " + match.display()),
            LanternaTheme.welcomeDim(), 2000);
    }
}
