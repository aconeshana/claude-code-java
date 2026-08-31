package com.claudecode.ui.lanterna.transcript;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.claudecode.keybindings.UserKeybindingsStore;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;

/**
 * Unit tests for {@link TranscriptWindow}'s in-window search + n/N navigation.
 */
class TranscriptWindowSearchTest {

    private MessageHistory history;
    private TranscriptWindow win;
    private boolean closed;

    @BeforeEach
    void setUp() {
        history = new MessageHistory();
        // No need to seed history — MessageHistory filters out streaming
        // delta events anyway. We populate transcriptPanel directly so the
        // search test is independent of the dispatcher pipeline.
        closed = false;
        win = new TranscriptWindow(history, () -> closed = true);
        // Seed the panel with searchable plain-text lines.
        win.transcriptPanel().appendLine("Hello world from foo",
            TextColor.ANSI.DEFAULT);
        win.transcriptPanel().appendLine("Second line about bar",
            TextColor.ANSI.DEFAULT);
        win.transcriptPanel().appendLine("Third line mentions foo again",
            TextColor.ANSI.DEFAULT);
    }

    private static KeyStroke chr(char c) { return new KeyStroke(c, false, false); }
    private static KeyStroke ctrl(char c) { return new KeyStroke(c, true, false); }
    private static KeyStroke special(KeyType t) { return new KeyStroke(t, false, false); }

    @Test
    void slashStartsSearchMode() {
        assertFalse(win.isSearching());
        win.handleInput(chr('/'));
        assertTrue(win.isSearching());
        assertTrue(Strings.CS.contains(win.footerText(), "/"));
        assertTrue(Strings.CS.contains(win.footerText(), "Enter to commit"));
    }

    @Test
    void escapeCancelsSearchWithoutCommitting() {
        win.handleInput(chr('/'));
        win.handleInput(chr('f'));
        win.handleInput(chr('o'));
        win.handleInput(chr('o'));
        assertTrue(win.isSearching());
        win.handleInput(special(KeyType.ESCAPE));
        assertFalse(win.isSearching());
        assertEquals("", win.activeQuery());
        assertFalse(closed, "Esc during search must NOT close the window");
    }

    @Test
    void backspaceTrimsQueryBuffer() {
        win.handleInput(chr('/'));
        win.handleInput(chr('a'));
        win.handleInput(chr('b'));
        win.handleInput(special(KeyType.BACKSPACE));
        // footer reflects single-char buffer
        assertTrue(Strings.CS.contains(win.footerText(), "/a "), "got: " + win.footerText());
    }

    @Test
    void enterCommitsAndPopulatesMatches() {
        win.handleInput(chr('/'));
        for (char c : "foo".toCharArray()) win.handleInput(chr(c));
        win.handleInput(special(KeyType.ENTER));
        assertFalse(win.isSearching());
        assertEquals("foo", win.activeQuery());

        assertTrue(win.matchCount() >= 2, "expected ≥2 matches, got " + win.matchCount());
        assertEquals(0, win.currentMatchIndex());
        assertTrue(Strings.CS.startsWith(win.footerText(), "  Match 1 / "), "got: " + win.footerText());
    }

    @Test
    void nNavigatesForward_NNavigatesBack() {

        win.handleInput(chr('/'));
        for (char c : "foo".toCharArray()) win.handleInput(chr(c));
        win.handleInput(special(KeyType.ENTER));
        int total = win.matchCount();
        assumeAtLeast(2, total);

        win.handleInput(chr('n'));
        assertEquals(1, win.currentMatchIndex());
        win.handleInput(chr('n'));
        assertEquals(2 % total, win.currentMatchIndex(), "wraps around modulo total");

        win.handleInput(chr('N'));
        assertEquals(1, win.currentMatchIndex());
    }

    @Test
    void noMatchesFooterShowsHint() {
        win.handleInput(chr('/'));
        for (char c : "nonexistent_xyz".toCharArray()) win.handleInput(chr(c));
        win.handleInput(special(KeyType.ENTER));
        assertEquals(0, win.matchCount());
        assertTrue(Strings.CS.contains(win.footerText(), "No matches"), "got: " + win.footerText());
    }

    @Test
    void escAfterCommitExitsTranscriptWithoutIntermediateClear() {
        win.handleInput(chr('/'));
        for (char c : "foo".toCharArray()) win.handleInput(chr(c));
        win.handleInput(special(KeyType.ENTER));
        assertFalse(win.activeQuery().isEmpty());

        win.handleInput(special(KeyType.ESCAPE));
        assertTrue(closed, "TS navigating mode does not own Esc; transcript:exit closes directly");
    }

    @Test
    void ctrlOClosesWindow() {
        win.handleInput(ctrl('o'));
        assertTrue(closed);
    }

    @Test
    void transcriptContextSupportsRebindingAndNullUnbinding(
            @TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("keybindings.json");
        Files.writeString(file, """
            [{"context":"Transcript","bindings":{
              "x":"transcript:exit",
              "t":"transcript:toggleShowAll",
              "escape":null,
              "ctrl+e":null
            }}]
            """);
        UserKeybindingsStore store = createStore(file);
        try {
            win.setKeybindingsStore(store);
            win.handleInput(special(KeyType.ESCAPE));
            assertFalse(closed);
            win.handleInput(ctrl('e'));
            assertTrue(Strings.CS.contains(win.footerText(), "collapse"));
            win.handleInput(chr('t'));
            assertTrue(Strings.CS.contains(win.footerText(), "expand all"));
            win.handleInput(chr('x'));
            assertTrue(closed);
        } finally {
            store.dispose();
        }
    }

    @Test
    void typingPlainNDoesNotNavigateBeforeSearchCommitted() {
        // n / N are only active after a successful search.
        win.handleInput(chr('n'));
        win.handleInput(chr('N'));
        assertEquals(-1, win.currentMatchIndex());
    }

    // ctrlE-reapplies-search is covered by integration testing — unit setUp
    // bypasses MessageHistory and appends directly to the panel, so Ctrl+E
    // (which replays from history) clears those test-only lines. The
    // re-apply-after-replay behaviour is verified by manual smoke testing
    // until we wire a richer dispatcher fixture.

    private static void assumeAtLeast(int min, int actual) {
        if (actual < min) {
            throw new TestAbortedException("test requires ≥" + min + " matches, got " + actual);
        }
    }

    private static UserKeybindingsStore createStore(Path file) throws Exception {
        Method create = UserKeybindingsStore.class
            .getDeclaredMethod("create", Path.class, boolean.class);
        create.setAccessible(true);
        return (UserKeybindingsStore) create.invoke(null, file, true);
    }
}
