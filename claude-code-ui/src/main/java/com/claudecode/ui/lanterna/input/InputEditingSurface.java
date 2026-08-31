package com.claudecode.ui.lanterna.input;

import com.claudecode.core.message.PastedContent;
import com.googlecode.lanterna.TextColor;
import java.util.Map;

/**
 * The narrow set of input-box operations {@link InputHistoryController} drives, so the history
 * navigation state machine never reaches back into {@link InputPanel}'s widgets and fields
 * directly.
 */
interface InputEditingSurface {

    /** Current text-box contents — for draft save and bash-mode-filter detection. */
    String currentText();

    /** Current explicit mode override (BASH/null) — for draft save and bash filter. */
    InputPanel.Mode currentModeOverride();

    /** A fresh snapshot copy of the current pasted-content chips — saved as the draft's chips. */
    Map<Integer, PastedContent> snapshotPasted();

    /** Current absolute UTF-16 cursor offset, saved by legacy reverse-i-search. */
    int currentCursorOffset();

    /**
     * Apply a history entry to the box (strip mode prefix, set text / mode override, restore the
     * entry's chips, collapse multi-line bodies to a chip, and place the caret.
     */
    void applyEntry(PromptHistory.Entry entry, boolean cursorToStart);

/**
     * Restore the saved draft on Down-to-index-0, including.
     */
    void restoreDraft(String text, InputPanel.Mode modeOverride,
                      Map<Integer, PastedContent> pasted, boolean cursorToStart);

    /** Apply a reverse-search match at its substring position. */
    void applySearchEntry(PromptHistory.Entry entry, int cursorOffset);

    /** Restore every pre-search input facet on cancellation or an empty query. */
    void restoreSearchDraft(String text, InputPanel.Mode modeOverride,
                            Map<Integer, PastedContent> pasted, int cursorOffset);

    /** Set box text without moving the caret (reverse-i-search cancel restores the pre-search draft). */
    void setText(String text);

    /** Set box text and move the caret to the end (reverse-i-search applies the current match). */
    void setTextCaretEnd(String text);

    /** Recompute the mode from the text and fire the query-changed notification. */
    void refreshModeAndQuery();

    /** Show a transient hint line (search prompt / "Ctrl+R to search" affordance). */
    void showHint(String text, TextColor color, long timeoutMs);


    void setHistoryLabel(String label);

    /** Live display text for the configurable Global history-search binding. */
    String historySearchShortcut();

    /** Persistent legacy-search footer; {@code query == null} clears it. */
    void setHistorySearchStatus(String query, boolean failedMatch);

    /** Highlight the active legacy-search match, or clear it when {@code length <= 0}. */
    void setHistorySearchHighlight(int start, int length);

    /** Publish an asynchronous history result on the owning UI thread. */
    void invokeLater(Runnable task);
}
