package com.claudecode.ui.lanterna.input;

import org.apache.commons.lang3.Strings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.message.PastedContent;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.input.KeyStroke;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link InputHistoryController} state machine, exercised through a fake {@link
 * InputEditingSurface} and a fake {@link PromptHistory} (no Lanterna widgets, no disk).
 */
class InputHistoryControllerTest {

    /** Records surface calls and supplies controllable box state. */
    private static final class RecordingSurface implements InputEditingSurface {
        String text = "";
        InputPanel.Mode modeOverride = InputPanel.Mode.NORMAL;
        Map<Integer, PastedContent> pasted = Map.of();

        final List<PromptHistory.Entry> applied = new ArrayList<>();
        final List<Boolean> appliedCursorToStart = new ArrayList<>();
        String restoredDraftText = null;
        InputPanel.Mode restoredDraftMode = null;
        Map<Integer, PastedContent> restoredDraftPasted = null;
        boolean restoredDraftCursorToStart;
        boolean restoreDraftCalled = false;
        String lastSetText = null;
        String lastSetTextCaretEnd = null;
        int cursorOffset;
        int refreshCount = 0;
        String lastHint = null;
        String historyLabel = null;
        String historySearchShortcut = "ctrl+r";
        String historySearchStatus = null;
        int historySearchHighlightStart;
        int historySearchHighlightLength;

        @Override public String currentText() { return text; }
        @Override public InputPanel.Mode currentModeOverride() { return modeOverride; }
        @Override public Map<Integer, PastedContent> snapshotPasted() { return pasted; }
        @Override public int currentCursorOffset() { return cursorOffset; }
        @Override public void applyEntry(PromptHistory.Entry entry, boolean cursorToStart) {
            applied.add(entry);
            appliedCursorToStart.add(cursorToStart);
            if (Strings.CS.startsWith(entry.display(), "!")) {
                modeOverride = InputPanel.Mode.BASH;
                text = entry.display().substring(1);
            } else {
                modeOverride = InputPanel.Mode.NORMAL;
                text = entry.display();
            }
            pasted = entry.pastedContents();
        }
        @Override public void restoreDraft(String t, InputPanel.Mode m,
                                           Map<Integer, PastedContent> p,
                                           boolean cursorToStart) {
            restoreDraftCalled = true;
            restoredDraftText = t;
            restoredDraftMode = m;
            restoredDraftPasted = p;
            restoredDraftCursorToStart = cursorToStart;
            text = t;
            modeOverride = m;
            pasted = p;
        }
        @Override public void setText(String t) { lastSetText = t; }
        @Override public void setTextCaretEnd(String t) { lastSetTextCaretEnd = t; }
        @Override public void applySearchEntry(PromptHistory.Entry entry, int cursor) {
            applyEntry(entry, false);
            cursorOffset = cursor;
            lastSetTextCaretEnd = entry.display();
        }
        @Override public void restoreSearchDraft(String t, InputPanel.Mode m,
                                                 Map<Integer, PastedContent> p, int cursor) {
            restoreDraft(t, m, p, false);
            cursorOffset = cursor;
            lastSetText = t;
        }
        @Override public void refreshModeAndQuery() { refreshCount++; }
        @Override public void showHint(String t, TextColor color, long ms) { lastHint = t; }
        @Override public void setHistoryLabel(String label) { historyLabel = label; }
        @Override public String historySearchShortcut() { return historySearchShortcut; }
        @Override public void setHistorySearchStatus(String query, boolean failedMatch) {
            historySearchStatus = query == null ? null
                : (failedMatch ? "no matching prompt: " : "search prompts: ") + query;
        }
        @Override public void setHistorySearchHighlight(int start, int length) {
            historySearchHighlightStart = start;
            historySearchHighlightLength = length;
        }
        @Override public void invokeLater(Runnable task) { task.run(); }
    }

    /** Fake history returning fixed entries and capturing the mode filter it is queried with. */
    private static final class FakeHistory extends PromptHistory {
        final List<Entry> entries;
        String capturedModeFilter = "UNSET";

        FakeHistory(List<Entry> entries) {
            super(Path.of("/nonexistent/history.jsonl"));
            this.entries = entries;
        }
        @Override public List<Entry> getEntriesWithPasted(String project, String sessionId, String modeFilter) {
            capturedModeFilter = modeFilter;
            return entries;
        }
        @Override public CompletableFuture<List<Entry>> getEntriesWithPastedAsync(
                String project, String sessionId, String modeFilter) {
            capturedModeFilter = modeFilter;
            return CompletableFuture.completedFuture(entries);
        }
        @Override public CompletableFuture<List<Entry>> getEntriesWithPastedAsync(
                int limit, String project, String sessionId, String modeFilter) {
            capturedModeFilter = modeFilter;
            return CompletableFuture.completedFuture(entries.stream().limit(limit).toList());
        }
        @Override HistoryReader openGlobalHistoryReader() {
            return new HistoryReader(entries);
        }
        @Override public CompletableFuture<Integer> countEntriesAsync(
                String project, String modeFilter) {
            long count = modeFilter == null ? entries.size() : entries.stream()
                .filter(entry -> Strings.CS.startsWith(entry.display(), modeFilter)).count();
            return CompletableFuture.completedFuture((int) count);
        }
    }

    private static final class ControlledHistory extends PromptHistory {
        final AtomicInteger readCount = new AtomicInteger();
        final CompletableFuture<List<Entry>> pending = new CompletableFuture<>();

        ControlledHistory() {
            super(Path.of("/nonexistent/history.jsonl"));
        }

        @Override public CompletableFuture<List<Entry>> getEntriesWithPastedAsync(
                String project, String sessionId, String modeFilter) {
            readCount.incrementAndGet();
            return pending;
        }
        @Override public CompletableFuture<List<Entry>> getEntriesWithPastedAsync(
                int limit, String project, String sessionId, String modeFilter) {
            readCount.incrementAndGet();
            return pending;
        }
    }

    private static final class ControlledCountHistory extends PromptHistory {
        final List<CompletableFuture<Integer>> counts = new ArrayList<>();

        ControlledCountHistory() {
            super(Path.of("/nonexistent/history.jsonl"));
        }

        @Override public CompletableFuture<List<Entry>> getEntriesWithPastedAsync(
                int limit, String project, String sessionId, String modeFilter) {
            return CompletableFuture.completedFuture(List.of(
                entry(Strings.CS.equals("!", modeFilter) ? "!bash" : "prompt")));
        }

        @Override public CompletableFuture<Integer> countEntriesAsync(
                String project, String modeFilter) {
            CompletableFuture<Integer> result = new CompletableFuture<>();
            counts.add(result);
            return result;
        }
    }

    private static final class CapturingHistory extends PromptHistory {
        String capturedDisplay;
        String capturedSessionId;
        String capturedCwd;
        String capturedProject;

        CapturingHistory() {
            super(Path.of("/nonexistent/history.jsonl"));
        }

        @Override public void addEntry(String display, String sessionId, String cwd) {
            capturedDisplay = display;
            capturedSessionId = sessionId;
            capturedCwd = cwd;
            capturedProject = null;
        }

        @Override public void addEntry(
                String display,
                String sessionId,
                String cwd,
                String project,
                Map<Integer, PastedContent> pastedContents) {
            capturedDisplay = display;
            capturedSessionId = sessionId;
            capturedCwd = cwd;
            capturedProject = project;
        }
    }

    private static PromptHistory.Entry entry(String display) {
        return new PromptHistory.Entry(display, "s1", 0L, "proj", "cwd", Map.of());
    }

    private static InputHistoryController wired(RecordingSurface surface, PromptHistory history) {
        InputHistoryController c = new InputHistoryController(surface);
        c.setContext("s1", "proj");
        c.setPromptHistory(history);
        return c;
    }

    @Test
    void up_withoutHistory_movesFocusUp() {
        InputHistoryController c = new InputHistoryController(new RecordingSurface());
        // No promptHistory wired.
        assertEquals(TextBox.Result.MOVE_FOCUS_UP, c.up());
    }

    @Test
    void wiringAndResetDoNotReadUntilHistoryIsRequested() {
        RecordingSurface surface = new RecordingSurface();
        ControlledHistory history = new ControlledHistory();
        InputHistoryController controller = wired(surface, history);

        assertEquals(0, history.readCount.get(), "wiring should not eagerly scan history");
        controller.reset();
        assertEquals(0, history.readCount.get(), "submit reset should only clear navigation state");
    }

    @Test
    void rapidHistoryRequestsShareOneInFlightReadWithoutSkippingAnEntry() {
        RecordingSurface surface = new RecordingSurface();
        ControlledHistory history = new ControlledHistory();
        InputHistoryController controller = wired(surface, history);

        controller.up();
        controller.up();

        assertEquals(1, history.readCount.get(), "rapid Up presses must coalesce onto one scan");
        history.pending.complete(List.of(entry("one"), entry("two")));
        assertEquals(1, surface.applied.size(),
            "released 2.1.197 advances only after a history row is successfully applied");
        assertEquals("one", surface.applied.getLast().display(),
            "two Up presses during the same unresolved read must not skip the newest row");
    }

    @Test
    void downWhileFirstUpIsLoadingCancelsThePendingNavigationResult() {
        RecordingSurface surface = new RecordingSurface();
        ControlledHistory history = new ControlledHistory();
        InputHistoryController controller = wired(surface, history);

        controller.up();
        controller.down();
        history.pending.complete(List.of(entry("must not appear")));

        assertTrue(surface.applied.isEmpty(),
            "released wbc increments the pending-read generation when Down cancels an unresolved Up");
    }

    @Test
    void secondaryAddEntryUsesStableProjectFromContextInsteadOfCurrentCwd() {
        RecordingSurface surface = new RecordingSurface();
        CapturingHistory history = new CapturingHistory();
        InputHistoryController controller = new InputHistoryController(surface);
        controller.setContext("session-stable", "/repo/stable-root");
        controller.setPromptHistory(history);

        controller.addEntry("restored prompt", "/repo/stable-root/nested/cwd");

        assertEquals("restored prompt", history.capturedDisplay);
        assertEquals("session-stable", history.capturedSessionId);
        assertEquals("/repo/stable-root/nested/cwd", history.capturedCwd);
        assertEquals("/repo/stable-root", history.capturedProject,
            "secondary history writes must preserve the project identity injected by setContext");
    }

    @Test
    void submitResetReusesAnInFlightReadInsteadOfInvalidatingItsResult() {
        RecordingSurface surface = new RecordingSurface();
        ControlledHistory history = new ControlledHistory();
        InputHistoryController controller = wired(surface, history);

        controller.up();
        controller.reset();
        controller.up();

        assertEquals(1, history.readCount.get());
        history.pending.complete(List.of(entry("latest")));
        assertEquals("latest", surface.applied.getLast().display());
    }

    @Test
    void pressingUpAtExhaustedHistoryDoesNotRescan() {
        RecordingSurface surface = new RecordingSurface();
        ControlledHistory history = new ControlledHistory();
        InputHistoryController controller = wired(surface, history);

        controller.up();
        history.pending.complete(List.of(entry("only")));
        controller.up();

        assertEquals(1, history.readCount.get());
        assertEquals(1, surface.applied.size());
    }

    @Test
    void up_appliesEntriesInOrder_thenStopsAtTop() {
        RecordingSurface s = new RecordingSurface();
        InputHistoryController c = wired(s, new FakeHistory(List.of(entry("one"), entry("two"))));

        assertEquals(TextBox.Result.HANDLED, c.up());
        assertEquals(1, s.applied.size());
        assertEquals("one", s.applied.getFirst().display());
        assertEquals("History 2/2", s.historyLabel);
        assertFalse(s.appliedCursorToStart.getFirst(),
            "released 2.1.197 places the caret at the end on Up");

        assertEquals(TextBox.Result.HANDLED, c.up());
        assertEquals("two", s.applied.get(1).display());
        assertEquals("History 1/2", s.historyLabel);

        // At the top — no further entry applied.
        assertEquals(TextBox.Result.HANDLED, c.up());
        assertEquals(2, s.applied.size());
    }

    @Test
    void editingARecalledEntryHidesTheHistoryBorderLabelAndHintUsesLiveBinding() {
        RecordingSurface surface = new RecordingSurface();
        surface.historySearchShortcut = "alt+r";
        InputHistoryController controller = wired(surface,
            new FakeHistory(List.of(entry("one"), entry("two"))));

        controller.up();
        controller.up();
        assertEquals("alt+r to search history", surface.lastHint);

        surface.text = "two edited";
        controller.onUserEdit();
        assertNull(surface.historyLabel);

        surface.text = "two";
        controller.onUserEdit();
        assertEquals("History 1/2", surface.historyLabel,
            "historyEdited is derived from the current text and clears again after a revert");
    }

    @Test
    void viewedAgentPromptsReplaceDiskArrowHistory() {
        RecordingSurface surface = new RecordingSurface();
        FakeHistory disk = new FakeHistory(List.of(entry("disk prompt")));
        InputHistoryController controller = wired(surface, disk);
        controller.setLiveHistorySupplier(() -> List.of(
            entry("agent newest"), entry("agent older")));

        controller.up();
        controller.up();

        assertEquals(List.of("agent newest", "agent older"),
            surface.applied.stream().map(PromptHistory.Entry::display).toList());
        assertEquals("UNSET", disk.capturedModeFilter,
            "released 2.1.197 does not scan disk history while an agent transcript is active");
        assertNull(surface.lastHint,
            "released wbc suppresses the Ctrl+R hint for supplied/live agent history");
    }

    @Test
    void firstUp_savesDraft_andDownToZeroRestoresIt() {
        RecordingSurface s = new RecordingSurface();
        s.text = "my draft";
        s.modeOverride = InputPanel.Mode.BASH;
        InputHistoryController c = wired(s, new FakeHistory(List.of(entry("one"))));

        c.up();                       // index 0 → 1, draft saved
        assertEquals(TextBox.Result.HANDLED, c.down()); // index 1 → 0, restore draft
        assertTrue(s.restoreDraftCalled);
        assertEquals("my draft", s.restoredDraftText);
        assertEquals(InputPanel.Mode.BASH, s.restoredDraftMode);
        assertTrue(s.restoredDraftCursorToStart,
            "released 2.1.197 restores the draft with the caret at the start");
    }

    @Test
    void down_atIndexZero_isNoOp() {
        RecordingSurface s = new RecordingSurface();
        InputHistoryController c = wired(s, new FakeHistory(List.of(entry("one"))));
        assertEquals(TextBox.Result.HANDLED, c.down());
        assertFalse(s.restoreDraftCalled);
        assertTrue(s.applied.isEmpty());
    }

    @Test
    void firstUp_inBashMode_setsBashModeFilter() {
        RecordingSurface s = new RecordingSurface();
        s.text = "ls -la";
        s.modeOverride = InputPanel.Mode.BASH;
        FakeHistory h = new FakeHistory(List.of(entry("!ls")));
        InputHistoryController c = wired(s, h);
        c.up();
        assertEquals("!", c.historyModeFilterForTest());
    }

    @Test
    void firstUp_plainText_setsNoModeFilter() {
        RecordingSurface s = new RecordingSurface();
        s.text = "hello";
        FakeHistory h = new FakeHistory(List.of(entry("hello world")));
        InputHistoryController c = wired(s, h);
        c.up();
        assertNull(c.historyModeFilterForTest());
    }

    @Test
    void changingHistoryModeInvalidatesTheOldCountAndStartsANewOne() {
        RecordingSurface surface = new RecordingSurface();
        ControlledCountHistory history = new ControlledCountHistory();
        InputHistoryController controller = wired(surface, history);

        controller.up();
        assertEquals(1, history.counts.size());
        controller.down();
        surface.modeOverride = InputPanel.Mode.BASH;
        surface.text = "";
        controller.up();

        assertEquals(2, history.counts.size(),
            "released wbc resets its Dzi in-flight gate when the mode filter changes");
        assertEquals("History", surface.historyLabel,
            "the old prompt-history denominator must disappear immediately");
        history.counts.getFirst().complete(20);
        assertEquals("History", surface.historyLabel,
            "a stale prompt count must not overwrite the bash projection");
        history.counts.get(1).complete(1);
        assertEquals("History 1/1", surface.historyLabel);
    }

    @Test
    void search_typeChar_appliesMatchingEntry() throws Exception {
        RecordingSurface s = new RecordingSurface();
        InputHistoryController c = wired(s, new FakeHistory(List.of(entry("git status"), entry("ls"))));

        assertEquals(TextBox.Result.HANDLED, c.toggleSearch());
        assertTrue(c.isSearching());

        c.handleSearchKey(new KeyStroke('g', false, false));
        await(() -> s.lastSetTextCaretEnd != null);
        assertNotNull(s.lastSetTextCaretEnd);
        assertTrue(Strings.CS.contains(s.lastSetTextCaretEnd, "git"), "match applied to the box");
    }

    @Test
    void search_acceptKeepsCurrentMatchAndExits() throws Exception {
        RecordingSurface s = new RecordingSurface();
        s.text = "original";
        InputHistoryController c = wired(s, new FakeHistory(List.of(entry("git status"))));

        c.toggleSearch();
        c.handleSearchKey(new KeyStroke('g', false, false));
        await(() -> s.lastSetTextCaretEnd != null);
        assertEquals("git status", s.lastSetTextCaretEnd);

        assertTrue(c.handleSearchAction("historySearch:accept"));

        assertFalse(c.isSearching());
        assertNull(s.lastSetText, "accept retains the match already projected into the box");
    }

    @Test
    void acceptingAfterAFailedExtensionKeepsTheLastSuccessfulMatchCursor() throws Exception {
        RecordingSurface surface = new RecordingSurface();
        InputHistoryController controller = wired(surface,
            new FakeHistory(List.of(entry("echo status"))));
        controller.toggleSearch();
        for (char ch : "sta".toCharArray()) {
            controller.handleSearchKey(new KeyStroke(ch, false, false));
        }
        await(() -> surface.cursorOffset == "echo ".length());
        controller.handleSearchKey(new KeyStroke('z', false, false));
        await(() -> Strings.CS.equals(
            "no matching prompt: staz", surface.historySearchStatus));

        controller.handleSearchAction("historySearch:accept");

        assertEquals("echo ".length(), surface.cursorOffset,
            "released kbc accept does not reapply the stale match using the failed query");
    }

    @Test
    void search_cancelRestoresDraftAndExits() throws Exception {
        RecordingSurface s = new RecordingSurface();
        s.text = "original";
        s.modeOverride = InputPanel.Mode.BASH;
        s.cursorOffset = 3;
        s.pasted = Map.of(7, PastedContent.text(7, "draft paste"));
        InputHistoryController c = wired(s, new FakeHistory(List.of(entry("git status"))));
        c.toggleSearch();
        c.handleSearchKey(new KeyStroke('g', false, false));
        await(() -> s.lastSetTextCaretEnd != null);

        assertTrue(c.handleSearchAction("historySearch:cancel"));

        assertFalse(c.isSearching());
        assertEquals("original", s.lastSetText);
        assertEquals(InputPanel.Mode.NORMAL, s.restoredDraftMode,
            "released kbc cancel restores text/cursor/pastes but leaves the matched mode active");
        assertEquals(3, s.cursorOffset);
        assertEquals("draft paste", s.restoredDraftPasted.get(7).content());
    }

    @Test
    void legacySearchPlacesCursorAtTheLastSubstringMatch() throws Exception {
        RecordingSurface s = new RecordingSurface();
        InputHistoryController c = wired(s,
            new FakeHistory(List.of(entry("echo status then status"))));

        c.toggleSearch();
        for (char ch : "status".toCharArray()) {
            c.handleSearchKey(new KeyStroke(ch, false, false));
        }
        int expectedOffset = "echo status then ".length();
        await(() -> s.cursorOffset == expectedOffset);

        assertEquals(expectedOffset, s.cursorOffset);
    }

    @Test
    void search_executeExitsAndRequestsOuterSubmit() {
        RecordingSurface s = new RecordingSurface();
        InputHistoryController c = wired(s, new FakeHistory(List.of(entry("git status"))));
        c.toggleSearch();
        assertFalse(c.handleSearchAction("historySearch:execute"));
        assertFalse(c.isSearching());
    }

    @Test
    void search_executeWithNonEmptyQueryAndNoMatchDoesNotSubmit() throws Exception {
        RecordingSurface s = new RecordingSurface();
        InputHistoryController c = wired(s, new FakeHistory(List.of(entry("git status"))));
        c.toggleSearch();
        assertEquals("search prompts: ", s.historySearchStatus);
        for (char ch : "missing".toCharArray()) {
            c.handleSearchKey(new KeyStroke(ch, false, false));
        }
        await(() -> Strings.CS.equals(
            "no matching prompt: missing", s.historySearchStatus));
        assertEquals("no matching prompt: missing", s.historySearchStatus);

        assertTrue(c.handleSearchAction("historySearch:execute"));
        assertFalse(c.isSearching());
        assertNull(s.historySearchStatus);
    }

    @Test
    void nativeEnterIsConsumedWhenReverseSearchHasNoMatch() throws Exception {
        RecordingSurface surface = new RecordingSurface();
        InputHistoryController controller = wired(surface,
            new FakeHistory(List.of(entry("git status"))));
        controller.toggleSearch();
        for (char ch : "missing".toCharArray()) {
            controller.handleSearchKey(new KeyStroke(ch, false, false));
        }
        await(() -> Strings.CS.equals(
            "no matching prompt: missing", surface.historySearchStatus));

        assertEquals(TextBox.Result.HANDLED,
            controller.handleSearchKey(new KeyStroke(com.googlecode.lanterna.input.KeyType.ENTER)),
            "released kbc clears the overlay without submitting a non-matching query");
        assertFalse(controller.isSearching());
    }

    @Test
    void isNavigating_offDraftTrue_backAtDraftFalse() {
        RecordingSurface s = new RecordingSurface();
        InputHistoryController c = wired(s, new FakeHistory(List.of(entry("/model"))));
        assertFalse(c.isNavigating(), "fresh input is not navigating");
        c.up();
        assertTrue(c.isNavigating(), "after Up, historyIndex>0 → suppress suggestions");
        c.down(); // back to draft (index 0)
        assertFalse(c.isNavigating(), "back at the draft, suggestions allowed again");
    }

    @Test
    void isNavigating_trueWhileSearching() {
        RecordingSurface s = new RecordingSurface();
        InputHistoryController c = wired(s, new FakeHistory(List.of(entry("git status"))));
        assertFalse(c.isNavigating());
        c.toggleSearch();
        assertTrue(c.isNavigating(), "reverse-i-search suppresses suggestions");
        c.handleSearchAction("historySearch:cancel");
        assertFalse(c.isNavigating(), "search cancelled → suggestions allowed");
    }

    @Test
    void onUserEdit_liftsSuppression_butKeepsNavigationState() {
        RecordingSurface s = new RecordingSurface();
        s.text = "draft";
        InputHistoryController c = wired(s, new FakeHistory(List.of(entry("/model"), entry("/effort"))));
        c.up(); // index 1, draft "draft" saved
        assertTrue(c.isNavigating(), "recalled a command → suppressed");
        s.text = "/model edited";
        c.onUserEdit(); // user types → detach for suppression only
        assertFalse(c.isNavigating(), "edited → suggestions resume");
        // Navigation state preserved: Down still returns to the saved draft.
        c.down();
        assertTrue(s.restoreDraftCalled, "Down still restores the draft after an edit");
        assertEquals("draft", s.restoredDraftText);
    }

    @Test
    void editingMidNavigation_doesNotCorruptTheIndex() {
// Regression: an edit used to reset the whole state, so the next Up restarted from the
        // top ("history forking" — 4 Ups then only a couple Downs back to empty). onUserEdit must
        // NOT touch the index.
        RecordingSurface s = new RecordingSurface();
        InputHistoryController c = wired(s, new FakeHistory(List.of(entry("a"), entry("b"), entry("c"))));
        c.up();          // index 1 → applied "a"
        c.onUserEdit();  // edit the recalled line
        c.up();          // index 2 → applied "b" (NOT "a" again)
        assertEquals(2, s.applied.size());
        assertEquals("a", s.applied.getFirst().display());
        assertEquals("b", s.applied.get(1).display(), "index kept advancing after an edit");
    }

    @Test
    void editsToARecalledEntryAreRestoredWhenNavigatingBackDown() {
        RecordingSurface s = new RecordingSurface();
        InputHistoryController c = wired(s, new FakeHistory(List.of(entry("a"), entry("b"))));

        c.up();
        s.text = "edited a";
        s.modeOverride = InputPanel.Mode.NORMAL;
        s.pasted = Map.of(9, PastedContent.text(9, "edited paste"));
        c.onUserEdit();
        c.up();
        c.down();

        PromptHistory.Entry restored = s.applied.getLast();
        assertEquals("edited a", restored.display());
        assertEquals("edited paste", restored.pastedContents().get(9).content());
        assertTrue(s.appliedCursorToStart.getLast(),
            "released 2.1.197 places the caret at the start on Down");
    }

    @Test
    void emptyDraftDoesNotRestoreOrphanedPastedContents() {
        RecordingSurface s = new RecordingSurface();
        s.pasted = Map.of(4, PastedContent.text(4, "orphan"));
        InputHistoryController c = wired(s, new FakeHistory(List.of(entry("a"))));

        c.up();
        c.down();

        assertTrue(s.restoreDraftCalled);
        assertEquals("", s.restoredDraftText);
        assertTrue(s.restoredDraftPasted.isEmpty());
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 2_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean(), "asynchronous history result did not arrive");
    }
}
