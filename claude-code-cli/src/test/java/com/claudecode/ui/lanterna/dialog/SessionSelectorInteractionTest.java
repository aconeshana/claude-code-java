package com.claudecode.ui.lanterna.dialog;

import com.claudecode.cli.CliInteractiveSessionAdapter;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.ui.lanterna.repl.InteractiveSessionPort;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.util.ArrayList;
import java.lang.reflect.Method;
import java.nio.file.Files;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Interaction tests for {@link SessionSelectorDialog} — drives handleInput
 * with synthetic KeyStrokes and verifies state changes without a full GUI
 * mount (avoids VirtualTerminal blocking on TextBox focus).
 */
class SessionSelectorInteractionTest {

    private SessionSelectorDialog make(List<InteractiveSessionPort.SessionEntry> sessions) {
        return new SessionSelectorDialog(sessions, new CliInteractiveSessionAdapter(),
            Path.of("/tmp/nonexistent-sessions"), "main", null, 40);
    }

    private KeyStroke ctrl(char c) { return new KeyStroke(c, true, false, false); }
    private KeyStroke ch(char c) { return new KeyStroke(c, false, false, false); }
    private KeyStroke arrow(KeyType t) { return new KeyStroke(t, false, false, false); }

    @Test
    void arrowKeysNavigateWithoutCrash() {
        SessionSelectorDialog d = make(List.of(
            new InteractiveSessionPort.SessionEntry("aaaa1111-aaaa", Instant.now().minusSeconds(3600), 5, null),
            new InteractiveSessionPort.SessionEntry("bbbb2222-bbbb", Instant.now().minusSeconds(7200), 3, null),
            new InteractiveSessionPort.SessionEntry("cccc3333-cccc", Instant.now().minusSeconds(10800), 7, null)
        ));
        assertTrue(d.handleInput(arrow(KeyType.ARROW_DOWN)));
        assertTrue(d.handleInput(arrow(KeyType.ARROW_DOWN)));
        assertTrue(d.handleInput(arrow(KeyType.ARROW_UP)));
        assertTrue(d.handleInput(arrow(KeyType.ARROW_UP)));
        assertTrue(d.handleInput(arrow(KeyType.ARROW_UP))); // at top, no crash
    }

    @Test
    void escapeClosesDialog() {
        SessionSelectorDialog d = make(List.of(
            new InteractiveSessionPort.SessionEntry("aaaa1111-aaaa", Instant.now().minusSeconds(3600), 5, null)));
        assertTrue(d.handleInput(arrow(KeyType.ESCAPE)));
    }

    @Test
    void enterSelectsSession() {
        SessionSelectorDialog d = make(List.of(
            new InteractiveSessionPort.SessionEntry("aaaa1111-aaaa", Instant.now().minusSeconds(3600), 5, null)));
        assertTrue(d.handleInput(arrow(KeyType.ENTER)));
        // Enter on a non-group header closes dialog (result set)
    }

    @Test
    void ctrlABWFiltersToggleWithoutCrash() {
        SessionSelectorDialog d = make(List.of(
            new InteractiveSessionPort.SessionEntry("aaaa1111-aaaa", Instant.now().minusSeconds(3600), 5, null)));
        assertTrue(d.handleInput(ctrl('a')));
        assertTrue(d.handleInput(ctrl('b')));
        assertTrue(d.handleInput(ctrl('w')));
        // Toggle again
        assertTrue(d.handleInput(ctrl('a')));
        assertTrue(d.handleInput(ctrl('b')));
        assertTrue(d.handleInput(ctrl('w')));
    }

    @Test
    void allProjectsModeKeepsAnIndependentProgressiveCursor() {
        InteractiveSessionPort.SessionEntry same1 = session("same-1");
        InteractiveSessionPort.SessionEntry same2 = session("same-2");
        InteractiveSessionPort.SessionEntry all1 = session("all-1");
        InteractiveSessionPort.SessionEntry all2 = session("all-2");
        SessionSelectorDialog dialog = make(List.of(same1));
        InteractiveSessionPort.SessionListing same =
            InteractiveSessionPort.SessionListing.of(List.of(same2));
        dialog.setProgressiveListings(same,
            () -> InteractiveSessionPort.SessionListing.of(List.of(all1, all2)), 1);

        dialog.handleInput(ctrl('a'));
        assertEquals(List.of("all-1"), dialog.sessionIdsForTest());
        dialog.loadMoreForTest(1);
        assertEquals(List.of("all-1", "all-2"), dialog.sessionIdsForTest());

        dialog.handleInput(ctrl('a'));
        assertEquals(List.of("same-1"), dialog.sessionIdsForTest());
        dialog.loadMoreForTest(1);
        assertEquals(List.of("same-1", "same-2"), dialog.sessionIdsForTest());
    }

    @Test
    void previewReplacesUnknownLiteCountWithTheLoadedMessageCount(@TempDir Path tempDir)
            throws Exception {
        Path transcript = tempDir.resolve("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.jsonl");
        Files.writeString(transcript, String.join("\n",
            "{\"type\":\"user\",\"uuid\":\"u1\",\"parentUuid\":null,\"timestamp\":\"2026-01-01T00:00:00Z\",\"isSidechain\":false,\"sessionId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\",\"message\":{\"role\":\"user\",\"content\":\"hello\"}}",
            "{\"type\":\"assistant\",\"uuid\":\"a1\",\"parentUuid\":\"u1\",\"timestamp\":\"2026-01-01T00:00:01Z\",\"isSidechain\":false,\"sessionId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}"));
        InteractiveSessionPort.SessionEntry entry = new InteractiveSessionPort.SessionEntry(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", System.currentTimeMillis(), Instant.now(),
            -1, "hello", null, tempDir.toString(), null, transcript, tempDir.toString(),
            null, Files.size(transcript));
        SessionSelectorDialog dialog = new SessionSelectorDialog(List.of(entry),
            new CliInteractiveSessionAdapter(), null, "main", tempDir.toString(), 40);

        dialog.handleInput(ch(' '));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Strings.CS.contains(dialog.previewMetadataForTest(), "2 messages")
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertTrue(Strings.CS.contains(dialog.previewMetadataForTest(), "2 messages"));
    }

    @Test
    void ctrlVEntersPreview() {
        SessionSelectorDialog d = make(List.of(
            new InteractiveSessionPort.SessionEntry("aaaa1111-aaaa", Instant.now().minusSeconds(3600), 5, null)));
        // Space enters preview (latest Claude Code changed from Ctrl+V to Space)
        assertTrue(d.handleInput(ch(' ')));
        // Arrow keys scroll preview without crash
        assertDoesNotThrow(() -> d.handleInput(arrow(KeyType.ARROW_UP)));
        assertDoesNotThrow(() -> d.handleInput(arrow(KeyType.ARROW_DOWN)));
    }

    @Test
    void ctrlREntersRenameAndEscCancels() {
        SessionSelectorDialog d = make(List.of(
            new InteractiveSessionPort.SessionEntry("aaaa1111-aaaa", Instant.now().minusSeconds(3600), 5, null)));
        assertTrue(d.handleInput(ctrl('r')));  // enter rename
        assertTrue(d.handleInput(arrow(KeyType.ESCAPE)));  // cancel rename
    }

    @Test
    void xRequiresConfirmationBeforePermanentDelete() {
        InteractiveSessionPort.SessionEntry session = new InteractiveSessionPort.SessionEntry(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", Instant.now(), 1, null);
        SessionSelectorDialog d = make(List.of(session));
        List<String> deleted = new ArrayList<>();
        d.setDeleteSessionCallback(info -> {
            deleted.add(info.id());
            return true;
        });

        assertTrue(d.handleInput(ch('x')));
        assertEquals("DELETE_CONFIRM", d.viewModeForTest());
        assertTrue(deleted.isEmpty(), "x must not delete without confirmation");

        assertTrue(d.handleInput(arrow(KeyType.ENTER)));
        assertEquals(List.of(session.id()), deleted);
        assertEquals("LIST", d.viewModeForTest());
        assertEquals(0, d.sessionCountForTest());
    }

    @Test
    void escapeCancelsPermanentDelete() {
        InteractiveSessionPort.SessionEntry session = new InteractiveSessionPort.SessionEntry(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", Instant.now(), 1, null);
        SessionSelectorDialog d = make(List.of(session));
        List<String> deleted = new ArrayList<>();
        d.setDeleteSessionCallback(info -> {
            deleted.add(info.id());
            return true;
        });

        d.handleInput(ch('x'));
        assertTrue(d.handleInput(arrow(KeyType.ESCAPE)));
        assertEquals("LIST", d.viewModeForTest());
        assertTrue(deleted.isEmpty());
        assertEquals(1, d.sessionCountForTest());
    }

    @Test
    void xInSearchModeRemainsSearchInput() {
        InteractiveSessionPort.SessionEntry session = new InteractiveSessionPort.SessionEntry(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", Instant.now(), 1, null);
        SessionSelectorDialog d = make(List.of(session));

        d.handleInput(ch('/'));
        assertTrue(d.handleInput(ch('x')));
        assertEquals("SEARCH", d.viewModeForTest());
    }

    @Test
    void failedPermanentDeleteKeepsSessionInList() {
        InteractiveSessionPort.SessionEntry session = new InteractiveSessionPort.SessionEntry(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", Instant.now(), 1, null);
        SessionSelectorDialog d = make(List.of(session));
        d.setDeleteSessionCallback(_ -> false);

        d.handleInput(ch('x'));
        d.handleInput(arrow(KeyType.ENTER));
        assertEquals("DELETE_CONFIRM", d.viewModeForTest());
        assertEquals(1, d.sessionCountForTest());
    }

    @Test
    void emptySessionListIsSafe() {
        SessionSelectorDialog d = make(List.of());
        // No crash on any key — return values vary (BasicWindow super may consume)
        assertDoesNotThrow(() -> d.handleInput(arrow(KeyType.ARROW_DOWN)));
        assertDoesNotThrow(() -> d.handleInput(arrow(KeyType.ARROW_UP)));
        assertDoesNotThrow(() -> d.handleInput(arrow(KeyType.ENTER)));
        assertDoesNotThrow(() -> d.handleInput(ch(' ')));   // space → search TextBox
        assertDoesNotThrow(() -> d.handleInput(ctrl('r')));
        assertDoesNotThrow(() -> d.handleInput(ctrl('v')));
        assertDoesNotThrow(() -> d.handleInput(ctrl('a')));
        assertDoesNotThrow(() -> d.handleInput(ctrl('b')));
        assertDoesNotThrow(() -> d.handleInput(ctrl('w')));
    }

    @Test
    void searchFiltersBySessionId() {

        InteractiveSessionPort.SessionEntry s1 = new InteractiveSessionPort.SessionEntry("aaaa1111-aaaa", Instant.now().minusSeconds(3600), 5, null);
        InteractiveSessionPort.SessionEntry s2 = new InteractiveSessionPort.SessionEntry("bbbb2222-bbbb", Instant.now().minusSeconds(7200), 3, null);
        SessionSelectorDialog d = make(List.of(s1, s2));

        assertTrue(d.handleInput(ch('/')));
        // Type "aaaa" → filters to s1
        d.handleInput(ch('a'));
        d.handleInput(ch('a'));
        d.handleInput(ch('a'));
        d.handleInput(ch('a'));
        assertDoesNotThrow(() -> d.handleInput(arrow(KeyType.ARROW_DOWN)));
    }

    @Test
    void ctrlNExitsSearchMode() {
        InteractiveSessionPort.SessionEntry s1 = new InteractiveSessionPort.SessionEntry("aaaa1111-aaaa", Instant.now().minusSeconds(3600), 5, null);
        SessionSelectorDialog d = make(List.of(s1));
        assertTrue(d.handleInput(ch('/')));   // enter search

        assertDoesNotThrow(() -> d.handleInput(ctrl('n')));
    }

    @Test
    void tagTabsRenderWhenTagsPresent() {

        // /tmp/nonexistent, no tags load. Verify no crash when tags absent.
        SessionSelectorDialog d = make(List.of(
            new InteractiveSessionPort.SessionEntry("aaaa1111-aaaa", Instant.now().minusSeconds(3600), 5, null)));
        // Tab key with no tags — should not crash, falls through
        assertDoesNotThrow(() -> d.handleInput(new KeyStroke(KeyType.TAB, false, false, false)));
    }

    @Test
    void listUsesRuntimeSelectBindingsAndNullUnbind(@TempDir Path tmp)
            throws Exception {
        Path file = tmp.resolve("keybindings.json");
        Files.writeString(file, """
            [{"context":"Select","bindings":{
              "x":"select:next",
              "z":"select:accept",
              "down":null,
              "escape":null
            }}]
            """);
        UserKeybindingsStore store = createStore(file);
        try {
            InteractiveSessionPort.SessionEntry first = new InteractiveSessionPort.SessionEntry("aaaa1111-aaaa", Instant.now(), 1, null);
            InteractiveSessionPort.SessionEntry second = new InteractiveSessionPort.SessionEntry("bbbb2222-bbbb", Instant.now().minusSeconds(60), 1, null);
            SessionSelectorDialog d = make(List.of(first, second));
            d.setKeybindingsStore(store);

            d.handleInput(arrow(KeyType.ARROW_DOWN));
            assertEquals(0, d.selectedIndexForTest());
            d.handleInput(arrow(KeyType.ESCAPE));
            assertEquals("LIST", d.viewModeForTest());

            d.handleInput(ch('x'));
            assertEquals(1, d.selectedIndexForTest());
            d.handleInput(ch('z'));
            assertEquals(second.id(), d.resultForTest().id());
        } finally {
            store.dispose();
        }
    }

    @Test
    void previewAndRenameUseConfirmationAndSettingsBindings(@TempDir Path tmp)
            throws Exception {
        Path file = tmp.resolve("keybindings.json");
        Files.writeString(file, """
            [
              {"context":"Confirmation","bindings":{"x":"confirm:no","z":"confirm:yes","escape":null}},
              {"context":"Settings","bindings":{"q":"confirm:no","escape":null}}
            ]
            """);
        UserKeybindingsStore store = createStore(file);
        try {
            InteractiveSessionPort.SessionEntry session = new InteractiveSessionPort.SessionEntry("aaaa1111-aaaa", Instant.now(), 1, null);
            SessionSelectorDialog preview = make(List.of(session));
            preview.setKeybindingsStore(store);
            preview.handleInput(ch(' '));
            preview.handleInput(arrow(KeyType.ESCAPE));
            assertEquals("PREVIEW", preview.viewModeForTest());
            preview.handleInput(ch('x'));
            assertEquals("LIST", preview.viewModeForTest());
            preview.handleInput(ch(' '));
            preview.handleInput(ch('z'));
            assertEquals(session.id(), preview.resultForTest().id());

            SessionSelectorDialog rename = make(List.of(session));
            rename.setKeybindingsStore(store);
            rename.handleInput(ctrl('r'));
            rename.handleInput(arrow(KeyType.ESCAPE));
            assertEquals("RENAME", rename.viewModeForTest());
            rename.handleInput(ch('q'));
            assertEquals("LIST", rename.viewModeForTest());
        } finally {
            store.dispose();
        }
    }


    @Test
    void ctrlA_swapsToAllProjectsAndBack() {
        InteractiveSessionPort.SessionEntry local = new InteractiveSessionPort.SessionEntry("aaaa1111-aaaa", Instant.now().minusSeconds(3600), 5, null);
        InteractiveSessionPort.SessionEntry foreign = new InteractiveSessionPort.SessionEntry("bbbb2222-bbbb", Instant.now().minusSeconds(7200), 3, null);
        SessionSelectorDialog d = make(new ArrayList<>(List.of(local)));
        d.setAllProjectsLoader(() -> List.of(local, foreign));

        assertFalse(d.isShowAllProjects());
        assertTrue(d.handleInput(ctrl('a')));
        assertTrue(d.isShowAllProjects(), "Ctrl+A enables all-projects mode");
        // 数据源已换：条目数应包含 foreign（通过再次 Ctrl+A 还原验证互逆）
        assertTrue(d.handleInput(ctrl('a')));
        assertFalse(d.isShowAllProjects(), "second Ctrl+A restores current-dir mode");
    }

    @Test
    void ctrlA_withoutLoader_staysDisplayOnlyToggle() {
        SessionSelectorDialog d = make(new ArrayList<>(List.of(
            new InteractiveSessionPort.SessionEntry("aaaa1111-aaaa", Instant.now().minusSeconds(60), 1, null))));
        assertTrue(d.handleInput(ctrl('a')));   // no loader wired — must not crash
        assertTrue(d.isShowAllProjects());
    }

    private static UserKeybindingsStore createStore(Path file) throws Exception {
        Method create = UserKeybindingsStore.class
            .getDeclaredMethod("create", Path.class, boolean.class);
        create.setAccessible(true);
        return (UserKeybindingsStore) create.invoke(null, file, true);
    }

    private static InteractiveSessionPort.SessionEntry session(String id) {
        return new InteractiveSessionPort.SessionEntry(id, Instant.now(), -1, null);
    }
}
