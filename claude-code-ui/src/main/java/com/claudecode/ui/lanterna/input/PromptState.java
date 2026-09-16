package com.claudecode.ui.lanterna.input;

import com.claudecode.core.message.PastedContent;
import com.claudecode.ui.lanterna.features.settings.UiSettings;
import com.claudecode.ui.lanterna.input.InputPanel.Mode;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * The prompt's draft state that is not the widget's own text buffer: the displayed input
 * mode and its history-restored override, the immutable text snapshot every consumer reads,
 * the REPL-owned flags (loading, transcript, message-actions overlay) that gate key routing,
 * and the draft-only undo history. Both {@link InputPanel} (view refresh) and
 * {@link PromptTextBox} (key routing) read it; neither reaches into the other for it.
 *
 * <ul>
 *   <li>{@code src/components/PromptInput/PromptInput.tsx} — the {@code input} / {@code mode}
 *       props and {@code isLoading} flag the key handlers and footer both consult.</li>
 *   <li>{@code src/components/PromptInput/inputModes.ts} — {@code getModeFromInput} /
 *       {@code getValueFromInput}: a leading {@code !} selects bash mode; a recalled history
 *       entry keeps its mode after the prefix is stripped from the visible text.</li>
 *   <li>{@code src/hooks/useInputBuffer.ts} — the 50-entry, 1 s debounced undo buffer behind
 *       {@code chat:undo}, with recording suppressed for programmatic edits.</li>
 * </ul>
 */
final class PromptState {

    private Mode mode = Mode.NORMAL;
    /**
     * Mode carried by a history entry whose prefix was stripped from the visible text. It
     * survives until submit, reset, or the user typing a new prefix at position 0.
     */
    private Mode modeOverride;
    /**
     * Last value delivered by Lanterna's text-change listener. Keeping this immutable
     * snapshot avoids rebuilding the whole multi-line value several times for every key
     * (undo, mode detection, chip pruning, typeahead, highlights and ghost text all read it).
     */
    private String text = "";
    /** Whether a query is in flight — set by LanternaReplScreen. */
    private volatile boolean loading;
    /** Whether transcript mode is active; Ctrl+E then fires transcriptShowAll instead of end. */
    private volatile boolean transcriptMode;
    private boolean messageActionsActive;
    private String messageActionsHint = "";
    private BooleanSupplier hasMessages = () -> true;
    private BooleanSupplier leftArrowOpensAgents =
        () -> UiSettings.readGlobalBoolean("leftArrowOpensAgents", true);

    /** Draft-only undo history; never rewinds QuerySession conversation state. */
    private final DraftUndoBuffer draftUndo = new DraftUndoBuffer(50);
    /** Monotonic suppression token observed by key dispatches to skip undo recording. */
    private long undoSuppressionGeneration;

    // ── Mode ────────────────────────────────────────────────────────────────

    Mode mode() { return mode; }

    Mode modeOverride() { return modeOverride; }

    void setModeOverride(Mode override) { this.modeOverride = override; }

    boolean isBashMode() { return mode == Mode.BASH; }

    /** Text to submit: the visible text with the override's prefix restored. */
    String prependModePrefix(String visibleText) {
        return InputModes.prependPrefix(visibleText, modeOverride);
    }

    /**
     * Re-derives the displayed mode from {@code editorText} and the override.
     *
     * @return {@code true} when the displayed mode changed and the view must repaint
     */
    boolean recomputeMode(String editorText) {
        if (modeOverride == null && mode == Mode.NORMAL
                && (editorText.isEmpty() || editorText.charAt(0) != '!')) {
            return false;
        }
        Mode prefixMode = InputModes.fromPrefix(editorText);
        Mode newMode;
        if (modeOverride != null) {
            // The override keeps the mode regardless of text content until the user
            // types a new prefix at position 0; then text-based detection takes over.
            if (prefixMode != Mode.NORMAL) {
                modeOverride = null;
                newMode = prefixMode;
            } else {
                newMode = modeOverride;
            }
        } else {
            newMode = prefixMode;
        }
        if (newMode == mode) return false;
        mode = newMode;
        return true;
    }

    /** Back to NORMAL with no override (submit, Escape, queued-command pop). */
    void resetMode() {
        mode = Mode.NORMAL;
        modeOverride = null;
    }

    // ── Text snapshot ───────────────────────────────────────────────────────

    String text() { return text; }

    void setText(String snapshot) { this.text = snapshot == null ? "" : snapshot; }

    // ── REPL-owned flags ────────────────────────────────────────────────────

    boolean isLoading() { return loading; }

    /** @return {@code true} when the flag actually changed */
    boolean setLoading(boolean value) {
        boolean changed = loading != value;
        loading = value;
        return changed;
    }

    boolean isTranscriptMode() { return transcriptMode; }

    void setTranscriptMode(boolean value) { this.transcriptMode = value; }

    boolean isMessageActionsActive() { return messageActionsActive; }

    void setMessageActionsActive(boolean value) {
        this.messageActionsActive = value;
        if (!value) messageActionsHint = "";
    }

    String messageActionsHint() { return messageActionsHint; }

    void setMessageActionsHint(String hint) { this.messageActionsHint = hint == null ? "" : hint; }

    /** Whether the transcript has anything a double-Escape message selector could show. */
    boolean hasMessages() { return hasMessages.getAsBoolean(); }

    void setHasMessages(BooleanSupplier supplier) {
        this.hasMessages = supplier == null ? () -> true : supplier;
    }

    boolean leftArrowOpensAgents() { return leftArrowOpensAgents.getAsBoolean(); }

    void setLeftArrowOpensAgents(BooleanSupplier supplier) {
        this.leftArrowOpensAgents = supplier == null ? () -> true : supplier;
    }

    // ── Draft undo ──────────────────────────────────────────────────────────

    DraftUndoBuffer draftUndo() { return draftUndo; }

    long undoSuppressionGeneration() { return undoSuppressionGeneration; }

    /** Marks the current key dispatch as programmatic so it records no undo snapshot. */
    void suppressUndoRecording() { undoSuppressionGeneration++; }

    DraftUndoBuffer.Snapshot draftSnapshot(int caret, Map<Integer, PastedContent> pasted) {
        return new DraftUndoBuffer.Snapshot(text, caret, pasted, modeOverride);
    }
}
