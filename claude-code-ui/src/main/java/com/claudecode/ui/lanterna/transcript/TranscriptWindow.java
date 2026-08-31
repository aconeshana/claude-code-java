package com.claudecode.ui.lanterna.transcript;

import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.claudecode.ui.lanterna.input.ContextKeybindingDispatcher;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * Full-screen transcript view — Ctrl+O toggles in / out of this window.
 */
public final class TranscriptWindow extends BasicWindow {

    private final MessageHistory history;
    private final Runnable onClose;

    private final MessagePanel transcriptPanel;
    private final LanternaMessageDispatcher transcriptDispatcher;
    private final MessageCollapser transcriptCollapser;
    private final Label footer;

    private boolean showAll = true;
    private final ContextKeybindingDispatcher keybindings =
        new ContextKeybindingDispatcher();

    // ── Search state ─────────────────────────────────────────────────────
    /** True while the user is typing a search query (after pressing '/'). */
    private boolean searching = false;
    /** Buffer for the in-progress search query (during {@link #searching}). */
    private final StringBuilder searchBuffer = new StringBuilder();
    /** Committed query — last submitted search string. Empty = no active search. */
    private String activeQuery = "";

    private List<Integer> matches = new ArrayList<>();
    /** Currently focused match index (0-based into {@link #matches}); -1 = none. */
    private int matchIdx = -1;

    public TranscriptWindow(MessageHistory history, Runnable onClose) {
        super();
        this.history = history;
        this.onClose = onClose;

        setHints(Set.of(
            Window.Hint.FULL_SCREEN,
            Window.Hint.NO_DECORATIONS,
            Window.Hint.NO_POST_RENDERING,
            Window.Hint.FIT_TERMINAL_WINDOW));

        // Independent renderers — main view's dispatcher / panel are untouched.
        transcriptDispatcher = new LanternaMessageDispatcher();
        transcriptDispatcher.setTranscriptMode(true);
        transcriptDispatcher.showOnlyTranscriptThinkingBlock(
            findLastThinkingBlockId(history.events()));
        transcriptCollapser = new MessageCollapser(transcriptDispatcher, false);
        transcriptCollapser.setShowAll(true);

        transcriptPanel = new MessagePanel();

        footer = new Label(buildFooterText());
        footer.setForegroundColor(LanternaTheme.welcomeDim());

        Panel root = new Panel(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        transcriptPanel.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL, LinearLayout.GrowPolicy.CAN_GROW));
        root.addComponent(transcriptPanel);
        root.addComponent(footer);
        setComponent(root);

        replay();

        transcriptPanel.scrollToBottom();
    }

    void setKeybindingsStore(UserKeybindingsStore store) {
        keybindings.setStore(store);
    }

    /** Footer text reflects current mode (idle / searching / showing results). */
    private String buildFooterText() {
        if (searching) {
            return "  /" + searchBuffer + "  (Enter to commit · Esc to cancel)";
        }
        if (!activeQuery.isEmpty() && !matches.isEmpty()) {
            return "  Match " + (matchIdx + 1) + " / " + matches.size()
                + " · n/N navigate · Esc clear · ctrl+o close";
        }
        if (!activeQuery.isEmpty()) {
            return "  No matches for '" + activeQuery + "' · Esc clear · ctrl+o close";
        }
        return "  Showing detailed transcript · ctrl+o close · ↑↓/jk scroll · g/G top/bottom ·"
            + " ctrl+u/d half · ctrl+b/f page · / search · ctrl+e "
            + (showAll ? "collapse" : "expand all");
    }

    private void refreshFooter() {
        footer.setText(buildFooterText());
    }

    /** Replay every recorded event into the transcript panel from scratch. */
    private void replay() {
        transcriptPanel.clear();
        transcriptCollapser.resetTurn();
        TranscriptRenderModel model = TranscriptRenderModel.from(history.events());
        transcriptDispatcher.setTranscriptRenderModel(model);
        for (SDKMessage msg : model.events()) {
            transcriptCollapser.dispatch(msg, transcriptPanel);
        }
    }

    static String findLastThinkingBlockId(List<SDKMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            SDKMessage message = messages.get(i);
            if (message instanceof SDKMessage.Assistant assistant
                    && assistant.message() != null
                    && assistant.message().message() != null
                    && assistant.message().message().content() != null) {
                List<ContentBlock> content = assistant.message().message().content();
                for (int blockIndex = content.size() - 1; blockIndex >= 0; blockIndex--) {
                    if (content.get(blockIndex) instanceof ThinkingBlock) {
                        return assistant.message().uuid() + ":" + blockIndex;
                    }
                }
            } else if (message instanceof SDKMessage.User user
                    && !containsToolResult(user)) {
                return null;
            }
        }
        return null;
    }

    private static boolean containsToolResult(SDKMessage.User user) {
        if (user.message() == null || user.message().message() == null
                || user.message().message().blocks() == null) {
            return false;
        }
        return user.message().message().blocks().stream()
            .anyMatch(ToolResultBlock.class::isInstance);
    }

    @Override
    public boolean handleInput(KeyStroke key) {
        // ── Search input mode ────────────────────────────────────────────
        // While searching, printable chars build the query (committed on
        // Enter, cancelled on Esc). The ModalPagerAction letters (g/G/j/k/
        // b/space, ctrl u/d/b/f/n/p) are suppressed so they don't scroll

        // Scroll context (PageUp/PageDown/ctrl+home/ctrl+end) stays active
        // during search (isActive, not isModal), so those still scroll.
        if (searching) {
            if (key.getKeyType() == KeyType.ENTER) {
                commitSearch();
                return true;
            }
            if (key.getKeyType() == KeyType.ESCAPE) {
                cancelSearch();
                return true;
            }
            if (key.getKeyType() == KeyType.BACKSPACE) {
                if (!searchBuffer.isEmpty()) {
                    searchBuffer.deleteCharAt(searchBuffer.length() - 1);
                    refreshFooter();
                }
                return true;
            }

            if (key.getKeyType() == KeyType.PAGE_UP) {
                transcriptPanel.scrollUp(Math.max(1, rows() / 2));
                return true;
            }
            if (key.getKeyType() == KeyType.PAGE_DOWN) {
                transcriptPanel.scrollDown(Math.max(1, rows() / 2));
                return true;
            }
            if (key.getKeyType() == KeyType.HOME && key.isCtrlDown()) {
                transcriptPanel.scrollUp(Integer.MAX_VALUE / 2);
                return true;
            }
            if (key.getKeyType() == KeyType.END && key.isCtrlDown()) {
                transcriptPanel.scrollToBottom();
                return true;
            }
            if (key.getKeyType() == KeyType.CHARACTER
                    && !key.isCtrlDown() && !key.isAltDown()) {
                Character c = key.getCharacter();
                if (c != null && c >= 0x20) {
                    searchBuffer.append(c);
                    refreshFooter();
                    return true;
                }
            }
            // Other keys while searching — swallow (letters are query chars).
            return true;
        }

        // ── Normal mode ──────────────────────────────────────────────────
        ContextKeybindingDispatcher.Result resolved =
            keybindings.resolve(List.of("Transcript", "Global"), key);
        if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) {
            return true;
        }
        if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String value)) {
            boolean handled = switch (value) {
                case "transcript:exit", "app:toggleTranscript" -> {
                    close();
                    yield true;
                }
                case "transcript:toggleShowAll" -> {
                    toggleShowAll();
                    yield true;
                }
                default -> false;
            };
            if (handled) return true;
        }

        // '/' starts a new search.
        if (key.getKeyType() == KeyType.CHARACTER
                && !key.isCtrlDown() && !key.isAltDown()
                && key.getCharacter() != null
                && key.getCharacter() == '/') {
            startSearch();
            return true;
        }
        // n / N navigate matches (only meaningful when activeQuery has hits).
        if (key.getKeyType() == KeyType.CHARACTER
                && !key.isCtrlDown() && !key.isAltDown()
                && key.getCharacter() != null
                && !matches.isEmpty()) {
            char ch = key.getCharacter();
            if (ch == 'n') { navigateMatch(+1); return true; }
            if (ch == 'N') { navigateMatch(-1); return true; }
        }


        // Escape exits transcript even if highlights remain active.
        if (key.getKeyType() == KeyType.ESCAPE) {
            close();
            return true;
        }
        if (key.getKeyType() == KeyType.CHARACTER && key.isCtrlDown() && !key.isAltDown()) {
            char ch = Character.toLowerCase(key.getCharacter());
            switch (ch) {
                case 'o', 'c':
                    close();
                    return true;
                case 'e':
                    toggleShowAll();
                    return true;
              default:
                    // fall through to scroll keys
            }
        }
        // Scroll navigation.
        if (key.getKeyType() == KeyType.ARROW_UP) {
            transcriptPanel.scrollUp(1);
            return true;
        }
        if (key.getKeyType() == KeyType.ARROW_DOWN) {
            transcriptPanel.scrollDown(1);
            return true;
        }
        if (key.getKeyType() == KeyType.PAGE_UP) {

            transcriptPanel.scrollUp(Math.max(1, rows() / 2));
            return true;
        }
        if (key.getKeyType() == KeyType.PAGE_DOWN) {
            transcriptPanel.scrollDown(Math.max(1, rows() / 2));
            return true;
        }
        if (key.getKeyType() == KeyType.HOME) {
            // Scroll to top: scrollUp by a very large amount (clamped internally).
            transcriptPanel.scrollUp(Integer.MAX_VALUE / 2);
            return true;
        }
        if (key.getKeyType() == KeyType.END) {
            transcriptPanel.scrollToBottom();
            return true;
        }

        if (!searching) {
            if (key.getKeyType() == KeyType.CHARACTER && !key.isCtrlDown() && !key.isAltDown()) {
                char c = key.getCharacter();

                // (kitty protocol); both must land on bottom.
                if (c == 'G' || (c == 'g' && key.isShiftDown())) {
                    transcriptPanel.scrollToBottom();
                    return true;
                }
                if (c == 'g') {
                    // Clamp path (same as Home) so offset stays within maxOffset;
                    // scrollToTop() would overshoot to lines.size()-1 and later
                    // line-scrolls would be no-ops until the excess drains.
                    transcriptPanel.scrollUp(Integer.MAX_VALUE / 2);
                    return true;
                }
                return switch (c) {
                    case 'j' -> { transcriptPanel.scrollDown(1);    yield true; }
                    case 'k' -> { transcriptPanel.scrollUp(1);      yield true; }
                    case 'b' -> { transcriptPanel.scrollUp(rows()); yield true; }   // less: b = page up
                    case ' ' -> { transcriptPanel.scrollDown(rows()); yield true; } // less: space = page down
                    case 'q' -> { close();                          yield true; }   // pager exit (less/tmux)
                    default  -> super.handleInput(key);
                };
            }
            if (key.getKeyType() == KeyType.CHARACTER && key.isCtrlDown() && !key.isAltDown()) {
                char c = Character.toLowerCase(key.getCharacter());
                int rows = rows();
                int half = Math.max(1, rows / 2);
                return switch (c) {
                    case 'u' -> { transcriptPanel.scrollUp(half);   yield true; }
                    case 'd' -> { transcriptPanel.scrollDown(half); yield true; }
                    case 'b' -> { transcriptPanel.scrollUp(rows);   yield true; }
                    case 'f' -> { transcriptPanel.scrollDown(rows); yield true; }
                    case 'n' -> { transcriptPanel.scrollDown(1);    yield true; } // emacs-style line scroll
                    case 'p' -> { transcriptPanel.scrollUp(1);      yield true; }
                    default  -> super.handleInput(key);
                };
            }
        }
        // Otherwise let BasicWindow handle (e.g. focus moves) — we don't need
        // to forward to the underlying main REPL because the window is modal.
        return super.handleInput(key);
    }


    private int rows() {
        TerminalSize sz = transcriptPanel.getSize();
        return (sz == null) ? 24 : sz.getRows();
    }

    private void toggleShowAll() {
        showAll = !showAll;
        transcriptCollapser.setShowAll(showAll);
        replay();
        // Re-apply active search after a re-replay (line indices change).
        if (!activeQuery.isEmpty()) commitSearch();
        else refreshFooter();
        transcriptPanel.scrollToBottom();
    }

    // ── Search helpers ───────────────────────────────────────────────────

    private void startSearch() {
        searching = true;
        searchBuffer.setLength(0);
        refreshFooter();
    }

    private void cancelSearch() {
        searching = false;
        searchBuffer.setLength(0);
        refreshFooter();
    }

    private void commitSearch() {
        searching = false;
        activeQuery = searchBuffer.toString();
        searchBuffer.setLength(0);
        if (activeQuery.isEmpty()) {
            matches = new ArrayList<>();
            matchIdx = -1;
            transcriptPanel.setSearchHighlight(null);
            refreshFooter();
            return;
        }
        matches = transcriptPanel.searchLines(activeQuery);
        transcriptPanel.setSearchHighlight(activeQuery);
        if (matches.isEmpty()) {
            matchIdx = -1;
        } else {
            matchIdx = 0;
            transcriptPanel.scrollToLine(matches.getFirst());
        }
        refreshFooter();
    }

    private void navigateMatch(int delta) {
        if (matches.isEmpty()) return;
        int size = matches.size();
        matchIdx = (matchIdx + delta + size) % size;
        transcriptPanel.scrollToLine(matches.get(matchIdx));
        refreshFooter();
    }

    @Override
    public void close() {
        super.close();
        if (onClose != null) onClose.run();
    }

    // ── Test hooks (package-private) ─────────────────────────────────────

    /** Visible for tests. */
    boolean isSearching() { return searching; }
    /** Visible for tests. */
    String activeQuery() { return activeQuery; }
    /** Visible for tests. */
    int matchCount() { return matches.size(); }
    /** Visible for tests. */
    int currentMatchIndex() { return matchIdx; }
    /** Visible for tests. */
    String footerText() { return buildFooterText(); }
    /** Visible for tests — direct access to the transcript panel so tests can
     *  seed plain-text lines without round-tripping through the dispatcher. */
    MessagePanel transcriptPanel() { return transcriptPanel; }
}
