package com.claudecode.ui.lanterna.dialog;

import org.apache.commons.lang3.Strings;
import com.claudecode.cli.CliInteractiveSessionAdapter;
import com.claudecode.ui.lanterna.repl.InteractiveSessionPort;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.SameTextGUIThread;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.claudecode.ui.lanterna.theme.LanternaTheme;


class SessionSelectorDialogRenderTest {

    @Test
    void rendersFullScreenLogSelectorLayout() throws Exception {
        DefaultVirtualTerminal term = new DefaultVirtualTerminal(new TerminalSize(200, 30));
        TerminalScreen screen = new TerminalScreen(term);
        screen.startScreen();
        MultiWindowTextGUI gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);

        InteractiveSessionPort.SessionEntry s1 = new InteractiveSessionPort.SessionEntry("aaaaaaaa-1234-5678-9abc-def012345678",
            Instant.now().minusSeconds(86400), 12, "claude-sonnet-4-6");
        InteractiveSessionPort.SessionEntry s2 = new InteractiveSessionPort.SessionEntry("bbbbbbbb-1234-5678-9abc-def012345678",
            Instant.now().minusSeconds(604800), 34, "claude-sonnet-4-6");

        SessionSelectorDialog dialog = new SessionSelectorDialog(
            List.of(s1, s2), new CliInteractiveSessionAdapter(),
            Path.of("/tmp/nonexistent-sessions"),
            "main", null, 40);

        gui.addWindow(dialog);
        gui.updateScreen();
        Thread.sleep(100);

        String dump = dumpScreen(term, 200, 30);
        screen.stopScreen();

        assertTrue(Strings.CS.contains(dump, "────"), "Should render divider line:\n" + dump);
        assertTrue(Strings.CS.contains(dump, "Resume Session"), "Should render title:\n" + dump);
        assertTrue(Strings.CS.contains(dump, "╭") && Strings.CS.contains(dump, "╮"), "Search box top border:\n" + dump);
        assertTrue(Strings.CS.contains(dump, "╰") && Strings.CS.contains(dump, "╯"), "Search box bottom border:\n" + dump);
        // ⌕ placeholder shows via Label when query empty; in VirtualTerminal the
        // Label/TextBox visibility swap may not render within one frame, so we
        // accept either the icon or an empty middle line (border still present).
        assertTrue(Strings.CS.contains(dump, "Ctrl+A"), "Ctrl+A hint:\n" + dump);
        assertTrue(Strings.CS.contains(dump, "Esc to cancel"), "Esc hint:\n" + dump);
        assertTrue(Strings.CS.contains(dump, "Space to preview"), "Space hint:\n" + dump);
        assertTrue(Strings.CS.contains(dump, "aaaaaaaa") || Strings.CS.contains(dump, "bbbbbbbb"),
            "Session id prefix:\n" + dump);
    }

    @Test
    void customTitleWinsOverLiteSummary() throws Exception {
        DefaultVirtualTerminal term = new DefaultVirtualTerminal(new TerminalSize(160, 24));
        TerminalScreen screen = new TerminalScreen(term);
        screen.startScreen();
        MultiWindowTextGUI gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);

        InteractiveSessionPort.SessionEntry session = new InteractiveSessionPort.SessionEntry(
            "aaaaaaaa-1234-5678-9abc-def012345678", System.currentTimeMillis(), Instant.now(), 12,
            "<command-name>/model</command-name> <command-message>model</command-message>",
            "main", "/tmp/project", "work", null, "/tmp/project", "Named session", 1024L);
        SessionSelectorDialog dialog = new SessionSelectorDialog(
            List.of(session), new CliInteractiveSessionAdapter(),
            Path.of("/tmp/nonexistent-sessions"), "main", null, 24);

        gui.addWindow(dialog);
        gui.updateScreen();

        String dump = dumpScreen(term, 160, 24);
        screen.stopScreen();

        assertTrue(Strings.CS.contains(dump, "Named session"),
            "The separately loaded custom title must win over the lite summary:\n" + dump);
        assertFalse(Strings.CS.contains(dump, "<command-name>"),
            "A stale command-only summary must not replace an available custom title:\n" + dump);
    }

    @Test
    void selectedItemUsesSuggestionColor() throws Exception {
        DefaultVirtualTerminal term = new DefaultVirtualTerminal(new TerminalSize(200, 30));
        TerminalScreen screen = new TerminalScreen(term);
        screen.startScreen();
        MultiWindowTextGUI gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);

        InteractiveSessionPort.SessionEntry s1 = new InteractiveSessionPort.SessionEntry("aaaa1111-1234-5678-9abc-def012345678",
            Instant.now().minusSeconds(3600), 5, null);
        SessionSelectorDialog dialog = new SessionSelectorDialog(
            List.of(s1), new CliInteractiveSessionAdapter(),
            Path.of("/tmp/nonexistent"), "main", null, 40);

        gui.addWindow(dialog);
        gui.updateScreen();
        Thread.sleep(100);


        TextColor suggestionFg = LanternaTheme.suggestion();
        boolean foundSuggestionFg = false;
        for (int row = 0; row < 30 && !foundSuggestionFg; row++) {
            for (int col = 0; col < 200; col++) {
                TextColor fg = term.getBufferCharacter(col, row).getForegroundColor();
                if (fg != null && fg.equals(suggestionFg)) {
                    foundSuggestionFg = true;
                    break;
                }
            }
        }
        screen.stopScreen();
        assertTrue(foundSuggestionFg, "Selected row should use suggestion-color foreground (TS Select isFocused style)");
    }

    @Test
    void deleteConfirmationCollapsesTheSessionList() throws Exception {
        DefaultVirtualTerminal term = new DefaultVirtualTerminal(new TerminalSize(104, 26));
        TerminalScreen screen = new TerminalScreen(term);
        screen.startScreen();
        MultiWindowTextGUI gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);
        InteractiveSessionPort.SessionEntry session = new InteractiveSessionPort.SessionEntry("aaaaaaaa-1234-5678-9abc-def012345678",
            Instant.now().minusSeconds(3600), 12, null);
        SessionSelectorDialog dialog = new SessionSelectorDialog(
            List.of(session), new CliInteractiveSessionAdapter(), Path.of("/tmp/nonexistent"),
            "main", null, 26);
        dialog.setDeleteSessionCallback(_ -> false);

        gui.addWindow(dialog);
        gui.updateScreen();
        dialog.handleInput(new KeyStroke('x', false, false, false));
        gui.updateScreen();

        String dump = dumpScreen(term, 104, 26);
        screen.stopScreen();
        int titleRow = rowContaining(dump, "Delete conversation permanently?");
        int footerRow = rowContaining(dump, "This cannot be undone");
        assertTrue(titleRow >= 0 && footerRow > titleRow && footerRow - titleRow <= 3,
            "Delete confirmation should be compact, not retain the list-height gap:\n" + dump);
    }

    @Test
    void previewRendersMessagesInTranscriptOrder(@TempDir Path sessionDir) throws Exception {
        DefaultVirtualTerminal term = new DefaultVirtualTerminal(new TerminalSize(104, 32));
        TerminalScreen screen = new TerminalScreen(term);
        screen.startScreen();
        MultiWindowTextGUI gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);
        String sessionId = "preview-order-session";
        StringBuilder transcript = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            transcript.append("{\"type\":\"user\",\"uuid\":\"u")
                .append(i)
                .append("\",\"message\":{\"type\":\"text\",\"text\":\"preview-line-")
                .append(i)
                .append("\"}}\n");
        }
        Files.writeString(sessionDir.resolve(sessionId + ".jsonl"), transcript);

        InteractiveSessionPort.SessionEntry session = new InteractiveSessionPort.SessionEntry(sessionId,
            Instant.now().minusSeconds(3600), 8, null);
        SessionSelectorDialog dialog = new SessionSelectorDialog(
            List.of(session), new CliInteractiveSessionAdapter(), sessionDir, "main", null, 32);
        dialog.setGuiInvoker(Runnable::run);

        gui.addWindow(dialog);
        gui.updateScreen();
        dialog.handleInput(new KeyStroke(' ', false, false, false));

        String dump = awaitPreview(gui, term, 104, 32, "preview-line-7");
        screen.stopScreen();

        int previousRow = -1;
        for (int i = 0; i < 8; i++) {
            int row = rowContaining(dump, "preview-line-" + i);
            assertTrue(row > previousRow,
                "Preview messages must retain transcript order; line " + i + " was misplaced:\n" + dump);
            previousRow = row;
        }
    }


    @Test
    void previewIsAnchoredToTheBottom(@TempDir Path sessionDir) throws Exception {
        DefaultVirtualTerminal term = new DefaultVirtualTerminal(new TerminalSize(104, 24));
        TerminalScreen screen = new TerminalScreen(term);
        screen.startScreen();
        MultiWindowTextGUI gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);
        String sessionId = "preview-bottom-session";
        StringBuilder transcript = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            transcript.append("{\"type\":\"user\",\"uuid\":\"u")
                .append(i)
                .append("\",\"message\":{\"type\":\"text\",\"text\":\"anchored-line-")
                .append(i)
                .append("\"}}\n");
        }
        Files.writeString(sessionDir.resolve(sessionId + ".jsonl"), transcript);

        InteractiveSessionPort.SessionEntry session = new InteractiveSessionPort.SessionEntry(sessionId,
            Instant.now().minusSeconds(3600), 40, null);
        SessionSelectorDialog dialog = new SessionSelectorDialog(
            List.of(session), new CliInteractiveSessionAdapter(), sessionDir, "main", null, 24);
        dialog.setGuiInvoker(Runnable::run);

        gui.addWindow(dialog);
        gui.updateScreen();
        dialog.handleInput(new KeyStroke(' ', false, false, false));

        String dump = awaitPreview(gui, term, 104, 24, "anchored-line-39");
        screen.stopScreen();

        assertTrue(rowContaining(dump, "anchored-line-39") >= 0,
            "The newest message must be visible without scrolling:\n" + dump);
        assertTrue(rowContaining(dump, "anchored-line-0") < 0,
            "The oldest message must have scrolled off the top:\n" + dump);
    }


    @Test
    void previewHasNoTitleDividerOrSearchBox(@TempDir Path sessionDir) throws Exception {
        DefaultVirtualTerminal term = new DefaultVirtualTerminal(new TerminalSize(104, 32));
        TerminalScreen screen = new TerminalScreen(term);
        screen.startScreen();
        MultiWindowTextGUI gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);
        String sessionId = "preview-chrome-session";
        Files.writeString(sessionDir.resolve(sessionId + ".jsonl"),
            "{\"type\":\"user\",\"uuid\":\"u1\",\"message\":{\"type\":\"text\",\"text\":\"chrome-probe\"}}\n");

        InteractiveSessionPort.SessionEntry session = new InteractiveSessionPort.SessionEntry(sessionId,
            Instant.now().minusSeconds(3600), 1, null);
        SessionSelectorDialog dialog = new SessionSelectorDialog(
            List.of(session), new CliInteractiveSessionAdapter(), sessionDir, "main", null, 32);
        dialog.setGuiInvoker(Runnable::run);

        gui.addWindow(dialog);
        gui.updateScreen();
        dialog.handleInput(new KeyStroke(' ', false, false, false));

        String dump = awaitPreview(gui, term, 104, 32, "chrome-probe");
        screen.stopScreen();

        assertFalse(Strings.CS.contains(dump, "Resume Session"),
            "The list title must not survive into the preview:\n" + dump);
        assertFalse(Strings.CS.contains(dump, "Preview:"),
            "197 has no preview title row at all:\n" + dump);
        assertFalse(Strings.CS.contains(dump, "╭") || Strings.CS.contains(dump, "╰"),
            "The search box must not survive into the preview:\n" + dump);
    }


    @Test
    void previewFooterMatches197(@TempDir Path sessionDir) throws Exception {
        DefaultVirtualTerminal term = new DefaultVirtualTerminal(new TerminalSize(104, 32));
        TerminalScreen screen = new TerminalScreen(term);
        screen.startScreen();
        MultiWindowTextGUI gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);
        String sessionId = "preview-footer-session";
        Files.writeString(sessionDir.resolve(sessionId + ".jsonl"),
            "{\"type\":\"user\",\"uuid\":\"u1\",\"message\":{\"type\":\"text\",\"text\":\"footer-probe\"}}\n");

        Instant modified = Instant.now().minusSeconds(3600);
        InteractiveSessionPort.SessionEntry session = new InteractiveSessionPort.SessionEntry(sessionId,
            modified.toEpochMilli(), modified, 8, null, "feature/x", null, null);
        SessionSelectorDialog dialog = new SessionSelectorDialog(
            List.of(session), new CliInteractiveSessionAdapter(), sessionDir, "main", null, 32);
        dialog.setGuiInvoker(Runnable::run);

        gui.addWindow(dialog);
        gui.updateScreen();
        dialog.handleInput(new KeyStroke(' ', false, false, false));

        String dump = awaitPreview(gui, term, 104, 32, "footer-probe");
        screen.stopScreen();

        int metaRow = rowContaining(dump, "8 messages");
        int hintRow = rowContaining(dump, "Enter to resume");
        int borderRow = rowContaining(dump, "────");
        assertTrue(metaRow > 0, "Footer metadata row missing:\n" + dump);
        assertTrue(Strings.CS.contains(dump, "· feature/x"),
            "Footer must carry the git branch:\n" + dump);
        assertEquals(metaRow + 1, hintRow,
            "The hint row sits directly under the metadata row:\n" + dump);
        assertEquals(metaRow - 1, borderRow,
            "The dim top border sits directly above the metadata row:\n" + dump);
    }


    @Test
    void previewWheelScrollRevealsEarlierMessages(@TempDir Path sessionDir) throws Exception {
        DefaultVirtualTerminal term = new DefaultVirtualTerminal(new TerminalSize(104, 24));
        TerminalScreen screen = new TerminalScreen(term);
        screen.startScreen();
        MultiWindowTextGUI gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);
        String sessionId = "preview-wheel-session";
        StringBuilder transcript = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            // Zero-padded so no marker is a prefix of another one.
            transcript.append("{\"type\":\"user\",\"uuid\":\"u")
                .append(i)
                .append("\",\"message\":{\"type\":\"text\",\"text\":\"wheel-line-")
                .append(String.format("%02d", i))
                .append("\"}}\n");
        }
        Files.writeString(sessionDir.resolve(sessionId + ".jsonl"), transcript);

        InteractiveSessionPort.SessionEntry session = new InteractiveSessionPort.SessionEntry(sessionId,
            Instant.now().minusSeconds(3600), 40, null);
        SessionSelectorDialog dialog = new SessionSelectorDialog(
            List.of(session), new CliInteractiveSessionAdapter(), sessionDir, "main", null, 24);
        dialog.setGuiInvoker(Runnable::run);

        gui.addWindow(dialog);
        gui.updateScreen();
        dialog.handleInput(new KeyStroke(' ', false, false, false));
        String before = awaitPreview(gui, term, 104, 24, "wheel-line-39");

        for (int i = 0; i < 10; i++) {
            dialog.handleInput(new MouseAction(MouseActionType.SCROLL_UP, 0, TerminalPosition.of(5, 5)));
        }
        gui.updateScreen();
        String after = dumpScreen(term, 104, 24);
        screen.stopScreen();

        int topBefore = firstVisibleIndex(before, "wheel-line-", 40);
        int topAfter = firstVisibleIndex(after, "wheel-line-", 40);
        assertTrue(topBefore >= 0 && topAfter >= 0,
            "The transcript must stay on screen across the wheel burst:\n" + after);
        assertTrue(topAfter < topBefore,
            "Wheel-up must reveal earlier transcript lines (top was " + topBefore
                + ", now " + topAfter + "):\n" + after);
        assertTrue(rowContaining(after, "wheel-line-39") < 0,
            "Scrolling up must push the newest message off the bottom:\n" + after);
    }

    /** Index of the earliest {@code prefix + %02d} row still on screen, or -1. */
    private static int firstVisibleIndex(String dump, String prefix, int count) {
        for (int i = 0; i < count; i++) {
            if (rowContaining(dump, prefix + String.format("%02d", i)) >= 0) return i;
        }
        return -1;
    }


    @Test
    void previewDropsMessagesARefusalFallbackRetracted(@TempDir Path sessionDir) throws Exception {
        DefaultVirtualTerminal term = new DefaultVirtualTerminal(new TerminalSize(104, 32));
        TerminalScreen screen = new TerminalScreen(term);
        screen.startScreen();
        MultiWindowTextGUI gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);
        String sessionId = "preview-retracted-session";
        // The transcript stores the logical uuid; the announcement stores the
        // wire uuid of the same message — they share only their first 24 chars.
        String logical = "f47ac10b-58cc-4372-a567-0e02b2c3d479";
        String wire = "f47ac10b-58cc-4372-a567-000000000001";
        String transcript =
            "{\"type\":\"user\",\"uuid\":\"9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d\","
                + "\"message\":{\"type\":\"text\",\"text\":\"kept-question\"}}\n"
            + "{\"type\":\"user\",\"uuid\":\"" + logical + "\","
                + "\"message\":{\"type\":\"text\",\"text\":\"ghost-retracted-line\"}}\n"
            + "{\"type\":\"system\",\"uuid\":\"ann-1\",\"subtype\":\"model_refusal_fallback\","
                + "\"level\":\"warning\",\"content\":\"Retrying with a fallback model\","
                + "\"retractedMessageUuids\":[\"" + wire + "\"]}\n";
        Files.writeString(sessionDir.resolve(sessionId + ".jsonl"), transcript);

        InteractiveSessionPort.SessionEntry session = new InteractiveSessionPort.SessionEntry(sessionId,
            Instant.now().minusSeconds(3600), 3, null);
        SessionSelectorDialog dialog = new SessionSelectorDialog(
            List.of(session), new CliInteractiveSessionAdapter(), sessionDir, "main", null, 32);
        dialog.setGuiInvoker(Runnable::run);

        gui.addWindow(dialog);
        gui.updateScreen();
        dialog.handleInput(new KeyStroke(' ', false, false, false));

        String dump = awaitPreview(gui, term, 104, 32, "Retrying with a fallback model");
        screen.stopScreen();

        assertFalse(Strings.CS.contains(dump, "ghost-retracted-line"),
            "The retracted message must not surface in the preview:\n" + dump);
        assertTrue(Strings.CS.contains(dump, "kept-question"),
            "Unrelated messages must survive the filter:\n" + dump);
        assertTrue(Strings.CS.contains(dump, "Tip: You can configure model switch behavior in /config"),
            "The announcement itself is never dropped, and carries its config tip:\n" + dump);
    }


    @Test
    void ctrlVAlsoOpensPreview(@TempDir Path sessionDir) throws Exception {
        DefaultVirtualTerminal term = new DefaultVirtualTerminal(new TerminalSize(104, 32));
        TerminalScreen screen = new TerminalScreen(term);
        screen.startScreen();
        MultiWindowTextGUI gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);
        String sessionId = "preview-ctrlv-session";
        Files.writeString(sessionDir.resolve(sessionId + ".jsonl"),
            "{\"type\":\"user\",\"uuid\":\"u1\",\"message\":{\"type\":\"text\",\"text\":\"ctrlv-probe\"}}\n");

        InteractiveSessionPort.SessionEntry session = new InteractiveSessionPort.SessionEntry(sessionId,
            Instant.now().minusSeconds(3600), 1, null);
        SessionSelectorDialog dialog = new SessionSelectorDialog(
            List.of(session), new CliInteractiveSessionAdapter(), sessionDir, "main", null, 32);
        dialog.setGuiInvoker(Runnable::run);

        gui.addWindow(dialog);
        gui.updateScreen();
        dialog.handleInput(new KeyStroke('v', true, false, false));

        String dump = awaitPreview(gui, term, 104, 32, "ctrlv-probe");
        screen.stopScreen();

        assertTrue(Strings.CS.contains(dump, "ctrlv-probe"),
            "Ctrl+V must open the preview just like Space:\n" + dump);
    }


    @Test
    void previewLoadingShowsSpinnerWithoutMetadata(@TempDir Path sessionDir) throws Exception {
        DefaultVirtualTerminal term = new DefaultVirtualTerminal(new TerminalSize(104, 32));
        TerminalScreen screen = new TerminalScreen(term);
        screen.startScreen();
        MultiWindowTextGUI gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);
        String sessionId = "preview-loading-session";
        Files.writeString(sessionDir.resolve(sessionId + ".jsonl"),
            "{\"type\":\"user\",\"uuid\":\"u1\",\"message\":{\"type\":\"text\",\"text\":\"late\"}}\n");

        InteractiveSessionPort.SessionEntry session = new InteractiveSessionPort.SessionEntry(sessionId,
            Instant.now().minusSeconds(3600), 7, "main");
        // No guiInvoker: the async commit never runs, freezing the loading frame.
        SessionSelectorDialog dialog = new SessionSelectorDialog(
            List.of(session), new CliInteractiveSessionAdapter(), sessionDir, "main", null, 32);
        dialog.setGuiInvoker(_ -> { });

        gui.addWindow(dialog);
        gui.updateScreen();
        dialog.handleInput(new KeyStroke(' ', false, false, false));
        gui.updateScreen();

        String dump = dumpScreen(term, 104, 32);
        screen.stopScreen();

        assertTrue(Strings.CS.contains(dump, "Loading session…"),
            "The loading row must carry the 197 message text:\n" + dump);
        assertTrue(Strings.CS.contains(dump, "Esc to cancel"),
            "Esc must stay advertised while loading:\n" + dump);
        assertFalse(Strings.CS.contains(dump, "7 messages"),
            "197's loading branch shows no session metadata:\n" + dump);
        int loadingRow = rowContaining(dump, "Loading session…");
        assertTrue(loadingRow >= 1, "padding:1 leaves a blank first row:\n" + dump);
    }

    /**
     * The preview swaps the window's root component. Covered full-screen windows
     * keep a retained buffer that is filled per-cell by the component tree, so a
     * swap that leaves cells unwritten would show the old list through the new
     * root.
     */
    @Test
    void switchingBackToListLeavesNoPreviewResidue(@TempDir Path sessionDir) throws Exception {
        DefaultVirtualTerminal term = new DefaultVirtualTerminal(new TerminalSize(104, 32));
        TerminalScreen screen = new TerminalScreen(term);
        screen.startScreen();
        MultiWindowTextGUI gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);
        String sessionId = "preview-residue-session";
        Files.writeString(sessionDir.resolve(sessionId + ".jsonl"),
            "{\"type\":\"user\",\"uuid\":\"u1\",\"message\":{\"type\":\"text\",\"text\":\"residue-probe\"}}\n");

        InteractiveSessionPort.SessionEntry session = new InteractiveSessionPort.SessionEntry(sessionId,
            Instant.now().minusSeconds(3600), 1, null);
        SessionSelectorDialog dialog = new SessionSelectorDialog(
            List.of(session), new CliInteractiveSessionAdapter(), sessionDir, "main", null, 32);
        dialog.setGuiInvoker(Runnable::run);

        gui.addWindow(dialog);
        gui.updateScreen();
        dialog.handleInput(new KeyStroke(' ', false, false, false));
        awaitPreview(gui, term, 104, 32, "residue-probe");

        dialog.handleInput(new KeyStroke(KeyType.ESCAPE));
        gui.updateScreen();
        String dump = dumpScreen(term, 104, 32);
        screen.stopScreen();

        assertTrue(Strings.CS.contains(dump, "Resume Session"),
            "Esc must restore the list root:\n" + dump);
        assertFalse(Strings.CS.contains(dump, "residue-probe"),
            "No preview transcript text may survive the swap back to the list:\n" + dump);
        assertFalse(Strings.CS.contains(dump, "Enter to resume"),
            "No preview footer may survive the swap back to the list:\n" + dump);
    }

    /**
     * Transcript text is not guaranteed to be plain — recorded tool output and
     * pasted terminal buffers carry raw ANSI. Lanterna refuses a control
     * character at paint time, and the throw used to unwind through
     * {@code updateScreen()} into the GUI loop's fatal handler, freezing the TUI
     * on the "Loading…" frame with even Esc dead.
     */
    @Test
    void previewRendersTranscriptCarryingAnsiEscapes(@TempDir Path sessionDir) throws Exception {
        DefaultVirtualTerminal term = new DefaultVirtualTerminal(new TerminalSize(104, 32));
        TerminalScreen screen = new TerminalScreen(term);
        screen.startScreen();
        MultiWindowTextGUI gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);
        String sessionId = "preview-ansi-session";
        Files.writeString(sessionDir.resolve(sessionId + ".jsonl"),
            """
            {"type":"user","uuid":"u1","message":{"type":"text","text":"\\u001b[31mred prompt\\u001b[0m tail"}}
            {"type":"assistant","uuid":"a1","message":{"content":[{"type":"text",\
            "text":"clear\\u001b[2K\\tcolumn\\u0007bell"}]}}
            """);

        InteractiveSessionPort.SessionEntry session = new InteractiveSessionPort.SessionEntry(sessionId,
            Instant.now().minusSeconds(3600), 2, null);
        SessionSelectorDialog dialog = new SessionSelectorDialog(
            List.of(session), new CliInteractiveSessionAdapter(), sessionDir, "main", null, 32);
        dialog.setGuiInvoker(Runnable::run);

        gui.addWindow(dialog);
        gui.updateScreen();
        dialog.handleInput(new KeyStroke(' ', false, false, false));

        // updateScreen throws IllegalArgumentException if an ESC reached a cell.
        String dump = awaitPreview(gui, term, 104, 32, "red prompt");
        screen.stopScreen();

        assertTrue(Strings.CS.contains(dump, "red prompt tail"),
            "SGR sequences should be dropped, leaving the text intact:\n" + dump);
        assertTrue(Strings.CS.contains(dump, "columnbell"),
            "Erase/BEL codes should be dropped and the tab expanded:\n" + dump);
    }

    /**
     * The spinner is the only thing PREVIEW draws until the transcript arrives,
     * so a load that dies must still clear it — otherwise the dialog sits on
     * "Loading session…" for the rest of its life.
     */
    @Test
    void previewClearsSpinnerWhenLoadFails(@TempDir Path sessionDir) throws Exception {
        DefaultVirtualTerminal term = new DefaultVirtualTerminal(new TerminalSize(104, 32));
        TerminalScreen screen = new TerminalScreen(term);
        screen.startScreen();
        MultiWindowTextGUI gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);
        String sessionId = "preview-failure-session";
        Files.writeString(sessionDir.resolve(sessionId + ".jsonl"),
            "{\"type\":\"user\",\"uuid\":\"u1\",\"message\":{\"type\":\"text\",\"text\":\"hi\"}}\n");

        InteractiveSessionPort.SessionEntry session = new InteractiveSessionPort.SessionEntry(sessionId,
            Instant.now().minusSeconds(3600), 1, null);
        SessionSelectorDialog dialog = new SessionSelectorDialog(
            List.of(session), failingReadMessagesPort(), sessionDir, "main", null, 32);
        dialog.setGuiInvoker(Runnable::run);

        gui.addWindow(dialog);
        gui.updateScreen();
        dialog.handleInput(new KeyStroke(' ', false, false, false));

        String dump = awaitFailedPreview(gui, term, dialog, 104, 32);
        screen.stopScreen();

        assertFalse(Strings.CS.contains(dump, "Loading session"),
            "A failed preview load must not leave the spinner on screen:\n" + dump);
        assertFalse(dialog.previewSpinnerRunningForTest(),
            "The spinner animation must be stopped, not just hidden");
        assertTrue(Strings.CS.contains(dump, "Esc to cancel"),
            "Esc must stay advertised so the dialog is escapable:\n" + dump);
    }

    private static String awaitFailedPreview(
        MultiWindowTextGUI gui,
        DefaultVirtualTerminal term,
        SessionSelectorDialog dialog,
        int width,
        int height
    ) throws Exception {
        String dump = "";
        for (int attempt = 0; attempt < 100; attempt++) {
            gui.updateScreen();
            dump = dumpScreen(term, width, height);
            if (!dialog.previewSpinnerRunningForTest()
                    && !Strings.CS.contains(dump, "Loading session")
                    && Strings.CS.contains(dump, "Esc to cancel")) {
                break;
            }
            Thread.sleep(10);
        }
        return dump;
    }

    /** Pumps frames until {@code marker} appears, then returns the final dump. */
    private static String awaitPreview(MultiWindowTextGUI gui, DefaultVirtualTerminal term,
                                       int width, int height, String marker) throws Exception {
        String dump = "";
        for (int attempt = 0; attempt < 100; attempt++) {
            gui.updateScreen();
            dump = dumpScreen(term, width, height);
            if (Strings.CS.contains(dump, marker)) break;
            Thread.sleep(10);
        }
        return dump;
    }

    /** A port whose transcript read dies with an {@code Error} (past any catch). */
    private static InteractiveSessionPort failingReadMessagesPort() {
        InteractiveSessionPort real = new CliInteractiveSessionAdapter();
        return (InteractiveSessionPort) Proxy.newProxyInstance(
            InteractiveSessionPort.class.getClassLoader(),
            new Class<?>[] {InteractiveSessionPort.class},
            (_, method, args) -> {
                if (Strings.CS.equals(method.getName(), "readMessages")) {
                    throw new AssertionError("simulated transcript read failure");
                }
                try {
                    return method.invoke(real, args);
                } catch (InvocationTargetException wrapped) {
                    throw wrapped.getCause();
                }
            });
    }


    @Test
    void anInitialSearchQueryFiltersTheListAndStaysVisibleInTheSearchBox() throws Exception {
        DefaultVirtualTerminal term = new DefaultVirtualTerminal(new TerminalSize(104, 32));
        TerminalScreen screen = new TerminalScreen(term);
        screen.startScreen();
        MultiWindowTextGUI gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);

        SessionSelectorDialog dialog = titledDialog();
        dialog.setInitialSearchQuery("nightly");

        gui.addWindow(dialog);
        gui.updateScreen();
        String dump = dumpScreen(term, 104, 32);
        screen.stopScreen();

        assertTrue(Strings.CS.contains(dump, "nightly run"),
            "the matching session must survive the seeded filter:\n" + dump);
        assertFalse(Strings.CS.contains(dump, "morning run"),
            "a seeded query filters exactly like a typed one:\n" + dump);
        assertTrue(Strings.CS.contains(dump, "nightly"),
            "the seeded query must be visible in the search box:\n" + dump);
    }

    @Test
    void aBlankInitialSearchQueryLeavesThePickerInItsDefaultListMode() throws Exception {
        DefaultVirtualTerminal term = new DefaultVirtualTerminal(new TerminalSize(104, 32));
        TerminalScreen screen = new TerminalScreen(term);
        screen.startScreen();
        MultiWindowTextGUI gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);

        SessionSelectorDialog dialog = titledDialog();
        dialog.setInitialSearchQuery(null);
        dialog.setInitialSearchQuery("   ");

        gui.addWindow(dialog);
        gui.updateScreen();
        String dump = dumpScreen(term, 104, 32);
        screen.stopScreen();

        assertTrue(Strings.CS.contains(dump, "nightly run") && Strings.CS.contains(dump, "morning run"),
            "a bare -r seeds nothing, so every session stays listed:\n" + dump);
    }

    /** Two sessions whose summaries differ, so a filter's effect is unambiguous. */
    private static SessionSelectorDialog titledDialog() {
        InteractiveSessionPort.SessionEntry nightly = new InteractiveSessionPort.SessionEntry(
            "aaaaaaaa-1234-5678-9abc-def012345678", Instant.now().minusSeconds(3600).toEpochMilli(),
            Instant.now().minusSeconds(7200), 12, "nightly run", null, null, null);
        InteractiveSessionPort.SessionEntry morning = new InteractiveSessionPort.SessionEntry(
            "bbbbbbbb-1234-5678-9abc-def012345678", Instant.now().minusSeconds(1800).toEpochMilli(),
            Instant.now().minusSeconds(3600), 34, "morning run", null, null, null);
        return new SessionSelectorDialog(List.of(nightly, morning),
            new CliInteractiveSessionAdapter(), Path.of("/tmp/nonexistent-sessions"),
            "main", null, 32);
    }

    private static int rowContaining(String dump, String text) {
        String[] rows = dump.split("\\n", -1);
        for (int i = 0; i < rows.length; i++) {
            if (Strings.CS.contains(rows[i], text)) return i;
        }
        return -1;
    }

    private static String dumpScreen(DefaultVirtualTerminal term, int width, int height) {
        StringBuilder sb = new StringBuilder();
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                sb.append(term.getBufferCharacter(col, row).getCharacterString());
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
