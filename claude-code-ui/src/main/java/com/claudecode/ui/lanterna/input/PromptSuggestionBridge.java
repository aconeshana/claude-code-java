package com.claudecode.ui.lanterna.input;

import com.claudecode.ui.lanterna.suggest.SuggestionPanel;
import com.googlecode.lanterna.gui2.Component;
import com.googlecode.lanterna.gui2.Interactable.Result;
import com.googlecode.lanterna.input.KeyStroke;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.Strings;

/**
 * Binds the slash/@/bash-path suggestion dropdown to the prompt text: owns the
 * {@link SuggestionPanel}, remembers which kind of completion is showing, routes
 * navigation/accept/dismiss keys, and rewrites the prompt when a suggestion is
 * accepted (whole-input replacement for commands, {@code @token} splice for
 * files, last-shell-token splice for bash paths).
 *
 * <p>Text rewriting is expressed as pure {@link Edit}s over the current text and
 * caret so the splice rules can be reasoned about without a live text box; the
 * {@link Host} applies the edit and performs the panel's post-edit bookkeeping.
 *
 * <ul>
 *   <li>{@code src/components/PromptInput/PromptInput.tsx} — suggestion
 *       keyboard protocol: Up/Down navigate, Tab accepts, Escape dismisses,
 *       Enter accepts a command and lets the same keystroke fall through to
 *       submit ({@code executeOnReturn}); bash-path completion is accept-only.</li>
 *   <li>{@code src/hooks/useTypeahead.ts} / {@code src/utils/typeahead/*} —
 *       {@code AT_TOKEN_HEAD_RE}, {@code PATH_CHAR_HEAD_RE} and
 *       {@code formatReplacementValue}: locating the {@code @token} around the
 *       caret, extending it over trailing path characters, quoting values with
 *       spaces and appending a trailing space.</li>
 * </ul>
 */
final class PromptSuggestionBridge {

    /** Editor access plus the prompt's post-edit bookkeeping. */
    interface Host {
        String text();
        int caret();
        /**
         * Replaces the prompt text, moves the caret, and runs mode re-detection
         * plus query-changed notification. The dropdown is already hidden.
         */
        void apply(Edit edit);
    }

    /** A rewritten prompt with the caret that should follow it. */
    record Edit(String text, int caret) {}

    private enum Context { NONE, STANDARD, BASH_PATH }

    /** Leading path characters after the caret that belong to the {@code @token}. */
    private static final Pattern PATH_CHAR_HEAD =
        Pattern.compile("^[\\w\\p{L}\\p{N}\\p{M}_\\-./\\\\()\\[\\]~:]+");

    private final Host host;
    private final SuggestionPanel panel = new SuggestionPanel();
    private Context context = Context.NONE;

    PromptSuggestionBridge(Host host) {
        this.host = host;
    }

    Component component() { return panel; }

    boolean isVisible() { return panel.isVisible(); }

    // ── show / hide ───────────────────────────────────────────────────────────

    void show(List<SuggestionPanel.Suggestion> items, int termW) {
        context = Context.STANDARD;
        panel.setSuggestions(items, termW);
    }

    /** See {@link SuggestionPanel#setSuggestions(List, int, int)} for the pre-computed column. */
    void show(List<SuggestionPanel.Suggestion> items, int termW, int commandColumnWidth) {
        context = Context.STANDARD;
        panel.setSuggestions(items, termW, commandColumnWidth);
    }

    void showBashPaths(List<SuggestionPanel.Suggestion> items, int termW) {
        context = Context.BASH_PATH;
        panel.setSuggestions(items, termW);
    }

    void hide() {
        context = Context.NONE;
        panel.hide();
    }

    // ── keyboard protocol ─────────────────────────────────────────────────────

    void moveUp() { panel.moveUp(); }

    void moveDown() { panel.moveDown(); }

    /**
     * Handles a key while the dropdown is visible; null when the key is not part
     * of the suggestion protocol (or must continue into the ordinary pipeline).
     */
    Result handleKey(KeyStroke key) {
        return switch (key.getKeyType()) {
            case ARROW_UP -> { panel.moveUp(); yield Result.HANDLED; }
            case ARROW_DOWN -> { panel.moveDown(); yield Result.HANDLED; }
            case TAB -> { accept(); yield Result.HANDLED; }
            case ESCAPE -> { hide(); yield Result.HANDLED; }
            case ENTER -> handleEnter();
            default -> null;
        };
    }

    /**
     * Enter accepts a standard suggestion. A completed command hides the panel
     * and returns null so the same keystroke continues into the submit stage
     * instead of requiring a second Enter; file suggestions stay accept-only.
     * Bash-path completion is Tab-only, so Enter merely dismisses it.
     */
    private Result handleEnter() {
        if (context == Context.BASH_PATH) {
            hide();
            return null;
        }
        SuggestionPanel.Suggestion s = takeSelected();
        if (s == null) return Result.HANDLED;
        fillStandard(s.primary());
        return Strings.CS.startsWith(s.primary(), "/") ? null : Result.HANDLED;
    }

    /** Applies the highlighted suggestion according to the active context. */
    void accept() {
        Context active = context;
        SuggestionPanel.Suggestion s = takeSelected();
        if (s == null) return;
        if (active == Context.BASH_PATH) {
            fillBashPath(s.primary());
        } else {
            fillStandard(s.primary());
        }
    }

    private SuggestionPanel.Suggestion takeSelected() {
        SuggestionPanel.Suggestion s = panel.acceptSelected();
        context = Context.NONE;
        return s;
    }

    // ── text rewriting ────────────────────────────────────────────────────────

    private void fillBashPath(String path) {
        Edit edit = bashPathEdit(host.text(), host.caret(), path);
        hide();
        host.apply(edit);
    }

    private void fillStandard(String primary) {
        Edit edit = standardEdit(host.text(), host.caret(), primary);
        hide();
        host.apply(edit);
    }

    /** Replaces only the shell token immediately before the caret. */
    static Edit bashPathEdit(String text, int caret, String path) {
        int cursor = Math.min(caret, text.length());
        int tokenStart = text.substring(0, cursor).lastIndexOf(' ') + 1;
        String replacement = path + (Strings.CS.endsWith(path, "/") ? "" : " ");
        String updated = text.substring(0, tokenStart) + replacement + text.substring(cursor);
        return new Edit(updated, tokenStart + replacement.length());
    }

    /**
     * Command suggestions replace the whole input; file suggestions splice over
     * the {@code @token} around the caret (quoted when the value has spaces);
     * anything else replaces the input with the value.
     */
    static Edit standardEdit(String text, int caret, String primary) {
        // "* " marks skills in the dropdown and is not part of the value.
        String clean = Strings.CS.startsWith(primary, "* ") ? primary.substring(2) : primary;
        if (Strings.CS.startsWith(clean, "/")) {
            String filled = clean + " ";
            return new Edit(filled, filled.length());
        }

        int cursor = Math.min(caret, text.length());
        String before = text.substring(0, cursor);
        String after = text.substring(cursor);

        int atIdx = atTokenStart(before);
        if (atIdx < 0) {
            String filled = clean + " ";
            return new Edit(filled, filled.length());
        }

        Matcher tail = PATH_CHAR_HEAD.matcher(after);
        int tokenLen = (before.length() - atIdx) + (tail.find() ? tail.group().length() : 0);
        String replacement = Strings.CS.contains(clean, " ")
            ? "@\"" + clean + "\" "
            : "@" + clean + " ";
        String updated = text.substring(0, atIdx) + replacement + text.substring(atIdx + tokenLen);
        return new Edit(updated, atIdx + replacement.length());
    }

    /** Index of the last {@code @} before the caret that starts a token, or -1. */
    private static int atTokenStart(String before) {
        for (int i = before.length() - 1; i >= 0; i--) {
            if (before.charAt(i) == '@'
                && (i == 0 || Character.isWhitespace(before.charAt(i - 1)))) {
                return i;
            }
        }
        return -1;
    }
}
