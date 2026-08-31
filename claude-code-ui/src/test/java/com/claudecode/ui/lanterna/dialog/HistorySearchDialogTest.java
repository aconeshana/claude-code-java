package com.claudecode.ui.lanterna.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.ui.lanterna.input.PromptHistory;
import org.apache.commons.lang3.Strings;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.SameTextGUIThread;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HistorySearchDialogTest {

    private static PromptHistory.TimestampedEntry entry(String text) {
        return PromptHistory.TimestampedEntry.deferred(text, 1L, () ->
            CompletableFuture.failedFuture(new AssertionError(
                "filtering metadata must not resolve pasted contents")));
    }

    @Test
    void filterPlacesExactMatchesBeforeSubsequenceMatches() {
        List<PromptHistory.TimestampedEntry> matches = HistorySearchDialog.filter(List.of(
            entry("git status"), entry("gts"), entry("go test")), "gts");

        assertEquals(List.of("gts", "git status", "go test"),
            matches.stream().map(PromptHistory.TimestampedEntry::display).toList());
    }

    @Test
    void filteringDoesNotResolvePastedContents() {
        AtomicInteger resolutions = new AtomicInteger();
        PromptHistory.TimestampedEntry metadata = PromptHistory.TimestampedEntry.deferred(
            "git status", 1L, () -> {
                resolutions.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });

        assertEquals(List.of(metadata), HistorySearchDialog.filter(List.of(metadata), "git"));
        assertEquals(0, resolutions.get());
    }

    @Test
    void loadingStateIsVisibleBeforeMetadataArrives() {
        assertEquals("Loading…", HistorySearchDialog.emptyMessage(true, ""));
        assertEquals("No history yet", HistorySearchDialog.emptyMessage(false, ""));
        assertEquals("No matching prompts", HistorySearchDialog.emptyMessage(false, "git"));
    }

    @Test
    void selectionWaitsForLazyPasteResolutionBeforeClosing() throws Exception {
        var terminal = new DefaultVirtualTerminal(new TerminalSize(100, 40));
        var screen = new TerminalScreen(terminal);
        screen.startScreen();
        var gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);
        CompletableFuture<PromptHistory.Entry> resolution = new CompletableFuture<>();
        PromptHistory.TimestampedEntry metadata = PromptHistory.TimestampedEntry.deferred(
            "selected", 1L, () -> resolution);
        AtomicInteger selections = new AtomicInteger();

        Thread.ofVirtual().start(() -> HistorySearchDialog.open(gui,
            _ -> CompletableFuture.completedFuture(List.of(metadata)), "", null,
            _ -> selections.incrementAndGet()));
        HistorySearchDialog dialog = awaitDialog(gui);
        pumpUntil(gui, () -> Strings.CS.contains(
            terminalFrame(terminal, new TerminalSize(100, 40)), "selected"));
        dialog.handleInput(new KeyStroke(KeyType.ENTER));

        assertEquals(0, selections.get());
        assertEquals(dialog, gui.getActiveWindow(),
            "released 2.1.197 keeps the picker open until entry.resolve() completes");

        resolution.complete(new PromptHistory.Entry(
            "selected", "s", 1L, "/p", "/p", Map.of()));
        pumpUntil(gui, () -> selections.get() == 1);
        assertEquals(1, selections.get());
        assertTrue(gui.getActiveWindow() != dialog);
    }

    @Test
    void aHungLazyResolutionDoesNotBlockASecondSelectionAttempt() throws Exception {
        var terminal = new DefaultVirtualTerminal(new TerminalSize(100, 40));
        var screen = new TerminalScreen(terminal);
        screen.startScreen();
        var gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);
        CompletableFuture<PromptHistory.Entry> hung = new CompletableFuture<>();
        AtomicInteger attempts = new AtomicInteger();
        PromptHistory.Entry resolved = new PromptHistory.Entry(
            "selected", "s", 1L, "/p", "/p", Map.of());
        PromptHistory.TimestampedEntry metadata = PromptHistory.TimestampedEntry.deferred(
            "selected", 1L, () -> attempts.getAndIncrement() == 0
                ? hung : CompletableFuture.completedFuture(resolved));
        CompletableFuture<String> selected = new CompletableFuture<>();

        Thread.ofVirtual().start(() -> HistorySearchDialog.open(gui,
            _ -> CompletableFuture.completedFuture(List.of(metadata)), "", null,
            entry -> selected.complete(entry.display())));
        HistorySearchDialog dialog = awaitDialog(gui);
        pumpUntil(gui, () -> Strings.CS.contains(
            terminalFrame(terminal, new TerminalSize(100, 40)), "selected"));
        dialog.handleInput(new KeyStroke(KeyType.ENTER));
        dialog.handleInput(new KeyStroke(KeyType.ENTER));
        pumpUntil(gui, selected::isDone);

        assertEquals(2, attempts.get());
        assertEquals("selected", selected.join());
    }

    @Test
    void ctrlPAndTabUseTheSameUpwardPickerProtocolAsReleased197() throws Exception {
        var terminal = new DefaultVirtualTerminal(new TerminalSize(80, 24));
        var screen = new TerminalScreen(terminal);
        screen.startScreen();
        var gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);
        List<PromptHistory.TimestampedEntry> entries = List.of(
            PromptHistory.TimestampedEntry.resolved(resolved("newest")),
            PromptHistory.TimestampedEntry.resolved(resolved("older")));
        CompletableFuture<String> selected = new CompletableFuture<>();

        Thread.ofVirtual().start(() -> HistorySearchDialog.open(gui,
            _ -> CompletableFuture.completedFuture(entries), "", null,
            entry -> selected.complete(entry.display())));
        HistorySearchDialog dialog = awaitDialog(gui);
        pumpUntil(gui, () -> Strings.CS.contains(
            terminalFrame(terminal, new TerminalSize(80, 24)), "newest"));
        dialog.handleInput(new KeyStroke('p', true, false));
        dialog.handleInput(new KeyStroke(KeyType.TAB));
        pumpUntil(gui, selected::isDone);

        assertEquals("older", selected.join());
    }

    @Test
    void pageUpMovesOneVisiblePageTowardOlderEntriesInUpwardPicker() throws Exception {
        var terminal = new DefaultVirtualTerminal(new TerminalSize(80, 24));
        var screen = new TerminalScreen(terminal);
        screen.startScreen();
        var gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);
        List<PromptHistory.TimestampedEntry> entries = java.util.stream.IntStream.range(0, 12)
            .mapToObj(index -> PromptHistory.TimestampedEntry.resolved(resolved("entry-" + index)))
            .toList();
        CompletableFuture<String> selected = new CompletableFuture<>();

        Thread.ofVirtual().start(() -> HistorySearchDialog.open(gui,
            _ -> CompletableFuture.completedFuture(entries), "", null,
            entry -> selected.complete(entry.display())));
        HistorySearchDialog dialog = awaitDialog(gui);
        pumpUntil(gui, () -> Strings.CS.contains(
            terminalFrame(terminal, new TerminalSize(80, 24)), "entry-0"));
        dialog.handleInput(new KeyStroke(KeyType.PAGE_UP));
        dialog.handleInput(new KeyStroke(KeyType.ENTER));
        pumpUntil(gui, selected::isDone);

        assertEquals("entry-8", selected.join(),
            "released Iar moves by the visible row count for direction=up");
    }

    @Test
    void wheelScrollsTheVisibleWindowWithoutChangingTheFocusedEntry() throws Exception {
        var size = new TerminalSize(80, 24);
        var terminal = new DefaultVirtualTerminal(size);
        var screen = new TerminalScreen(terminal);
        screen.startScreen();
        var gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);
        List<PromptHistory.TimestampedEntry> entries = java.util.stream.IntStream.range(0, 12)
            .mapToObj(index -> PromptHistory.TimestampedEntry.resolved(resolved("entry-" + index)))
            .toList();
        CompletableFuture<String> selected = new CompletableFuture<>();

        Thread.ofVirtual().start(() -> HistorySearchDialog.open(gui,
            _ -> CompletableFuture.completedFuture(entries), "", null,
            entry -> selected.complete(entry.display())));
        HistorySearchDialog dialog = awaitDialog(gui);
        pumpUntil(gui, () -> Strings.CS.contains(terminalFrame(terminal, size), "entry-0"));

        dialog.handleInput(new MouseAction(MouseActionType.SCROLL_UP, 0,
            TerminalPosition.TOP_LEFT_CORNER));
        pumpUntil(gui, () -> Strings.CS.contains(terminalFrame(terminal, size), "entry-8"));
        dialog.handleInput(new KeyStroke(KeyType.ENTER));
        pumpUntil(gui, selected::isDone);

        assertEquals("entry-0", selected.join(),
            "released Iar changes only window on wheel; focus remains on the newest item");
    }

    @Test
    void mouseHoverPreviewsAndClickSelectsThePointedHistoryRow() throws Exception {
        var size = new TerminalSize(100, 30);
        var terminal = new DefaultVirtualTerminal(size);
        var screen = new TerminalScreen(terminal);
        screen.startScreen();
        var gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);
        List<PromptHistory.TimestampedEntry> entries = List.of(
            PromptHistory.TimestampedEntry.resolved(resolved("newest\nnewest preview")),
            PromptHistory.TimestampedEntry.resolved(resolved("older\nhover preview")));
        CompletableFuture<String> selected = new CompletableFuture<>();

        Thread.ofVirtual().start(() -> HistorySearchDialog.open(gui,
            _ -> CompletableFuture.completedFuture(entries), "", null,
            entry -> selected.complete(entry.display())));
        HistorySearchDialog dialog = awaitDialog(gui);
        pumpUntil(gui, () -> Strings.CS.contains(terminalFrame(terminal, size), "older"));
        TerminalPosition olderRow = findText(terminalFrame(terminal, size), "older");

        dialog.handleInput(new MouseAction(MouseActionType.MOVE, 0, olderRow));
        pumpUntil(gui, () -> Strings.CS.contains(
            terminalFrame(terminal, size), "hover preview"));
        dialog.handleInput(new MouseAction(MouseActionType.CLICK_RELEASE, 0, olderRow));
        pumpUntil(gui, selected::isDone);

        assertEquals("older\nhover preview", selected.join());
    }

    @Test
    void ctrlDDeletesAtTheQueryCursorInsteadOfClosingWhenQueryIsNonEmpty() throws Exception {
        var terminal = new DefaultVirtualTerminal(new TerminalSize(80, 24));
        var screen = new TerminalScreen(terminal);
        screen.startScreen();
        var gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);
        List<PromptHistory.TimestampedEntry> entries = List.of(
            PromptHistory.TimestampedEntry.resolved(resolved("ax")),
            PromptHistory.TimestampedEntry.resolved(resolved("ab")));
        CompletableFuture<String> selected = new CompletableFuture<>();

        Thread.ofVirtual().start(() -> HistorySearchDialog.open(gui,
            _ -> CompletableFuture.completedFuture(entries), "ab", null,
            entry -> selected.complete(entry.display())));
        HistorySearchDialog dialog = awaitDialog(gui);
        pumpUntil(gui, () -> Strings.CS.contains(
            terminalFrame(terminal, new TerminalSize(80, 24)), "ab"));
        dialog.handleInput(new KeyStroke(KeyType.ARROW_LEFT));
        dialog.handleInput(new KeyStroke('d', true, false));

        assertEquals(dialog, gui.getActiveWindow(),
            "released eH treats Ctrl+D as delete unless the query is empty");
        dialog.handleInput(new KeyStroke(KeyType.ENTER));
        pumpUntil(gui, selected::isDone);
        assertEquals("ax", selected.join());
    }

    @Test
    void backspaceOnAnEmptyPickerQueryDoesNotCloseTheDialog() throws Exception {
        var terminal = new DefaultVirtualTerminal(new TerminalSize(80, 24));
        var screen = new TerminalScreen(terminal);
        screen.startScreen();
        var gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);

        Thread.ofVirtual().start(() -> HistorySearchDialog.open(gui,
            _ -> CompletableFuture.completedFuture(List.of(
                PromptHistory.TimestampedEntry.resolved(resolved("entry")))),
            "", null, _ -> {}));
        HistorySearchDialog dialog = awaitDialog(gui);
        pumpUntil(gui, () -> Strings.CS.contains(
            terminalFrame(terminal, new TerminalSize(80, 24)), "entry"));

        dialog.handleInput(new KeyStroke(KeyType.BACKSPACE));

        assertEquals(dialog, gui.getActiveWindow(),
            "released Iar configures eH with backspaceExitsOnEmpty=false");
        dialog.close();
    }

    @Test
    void previewHardWrapsBeforeApplyingTheSixRowOverflowRule() {
        assertEquals("abcd\nefgh\nijkl\nmnop\nqrst\n… +2 more lines",
            HistorySearchDialog.renderPreview("abcdefghijklmnopqrstuvwxyz12", 4));
    }

    private static PromptHistory.Entry resolved(String display) {
        return new PromptHistory.Entry(display, "s", 1L, "/p", "/p", Map.of());
    }

    private static HistorySearchDialog awaitDialog(MultiWindowTextGUI gui) throws Exception {
        final HistorySearchDialog[] found = new HistorySearchDialog[1];
        pumpUntil(gui, () -> {
            if (gui.getActiveWindow() instanceof HistorySearchDialog dialog) found[0] = dialog;
            return found[0] != null;
        });
        return assertInstanceOf(HistorySearchDialog.class, found[0]);
    }

    private static void pumpUntil(MultiWindowTextGUI gui,
                                  java.util.function.BooleanSupplier done) throws Exception {
        long deadline = System.currentTimeMillis() + 2_000;
        while (!done.getAsBoolean() && System.currentTimeMillis() < deadline) {
            gui.getGUIThread().processEventsAndUpdate();
            Thread.sleep(5);
        }
        assertTrue(done.getAsBoolean(), "condition did not become true before timeout");
    }

    private static String terminalFrame(DefaultVirtualTerminal terminal, TerminalSize size) {
        StringBuilder frame = new StringBuilder();
        for (int row = 0; row < size.getRows(); row++) {
            for (int column = 0; column < size.getColumns(); column++) {
                frame.append(terminal.getCharacter(column, row).getCharacterString());
            }
            frame.append('\n');
        }
        return frame.toString();
    }

    private static TerminalPosition findText(String frame, String needle) {
        String[] lines = frame.split("\\n", -1);
        for (int row = 0; row < lines.length; row++) {
            int column = lines[row].indexOf(needle);
            if (column >= 0) return new TerminalPosition(column, row);
        }
        throw new AssertionError("text not found in terminal frame: " + needle);
    }

}
