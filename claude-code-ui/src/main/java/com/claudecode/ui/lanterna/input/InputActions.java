package com.claudecode.ui.lanterna.input;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.message.PastedContent;
import com.claudecode.runtime.turn.QueuedInputDraft;
import com.googlecode.lanterna.CursorStyle;
import java.util.Map;

/**
 * The single outward port through which {@link InputPanel} asks its owning REPL to <em>do</em>
 * something or tells it something <em>changed</em>.
 */
public interface InputActions {

    // ── Command flow ─────────────────────────────────────────────────────────
    /** Submit the composed prompt (text, with mode prefix already applied). */
    void submit(String text);
    /** Cancel the in-flight turn (Esc while loading / Ctrl+D on empty / Vim Esc). */
    void cancel();
    /**
     * Confirmed {@code chat:killAgents} action — stop all running local background agents, clear queued
     * commands, and enqueue the aggregate model-facing notification.
     */
    default void killBackgroundAgents() { /* no-op */ }
    /**
     * Ctrl+B {@code task:background}: move all live foreground shell/agent tasks
     * into the background. Returns true only when at least one task claimed the
     * transition; false lets the input retain readline cursor-left semantics.
     */
    default boolean backgroundForegroundTasks() { return false; }
    /**
     * Ctrl+D on empty input — request EOF-style exit.
     */
    default void exitOnEmptyEof() { /* no-op */ }
    /** Open the message selector (double-Esc on empty input). */
    void showMessageSelector();

    // ── Overlay / screen actions ─────────────────────────────────────────────
    /** Ctrl+O — toggle transcript mode. */
    void toggleTranscript();
    /** Ctrl+E while in transcript mode — expand all messages. */
    void transcriptShowAll();
    /** Ctrl+L — redraw the screen. */
    void redrawScreen();
    /** Ctrl+G — open the external editor. */
    void externalEditor();
    /** Empty prompt + Left Arrow — open the multi-session agents view. */
    @Explanation("Matches the released empty-prompt shortcut for opening the agents view")
    default void openAgents() { /* no-op for hosts without the agents view */ }
    /** Ctrl+S pushed a prompt stash; host records {@code hasUsedStash}. */
    void stash();





    void undo();
    /** Ctrl+R — opens the current-project history picker; false keeps reverse-i-search. */
    default boolean openHistorySearch() { return false; }
    /**
     * Pull every editable queued prompt back into the draft buffer.
     */
    default QueuedInputDraft popEditableQueuedCommands(
            String currentInput, int currentCursorOffset) {
        return null;
    }

    // ── Voice / push-to-talk (reserved) ─────────────────────────────────────
    /**
     * Push-to-talk input primitive, reserved for a future voice subsystem.
     * {@code held} is true when the space binding arrived as a terminal
     * auto-repeat drain (a sustained hold) and false for an ordinary single
     * tap. Returning true consumes the keystroke (voice is recording); false
     * lets the space fall through to the input.
     */
    default boolean handlePushToTalk(boolean held) {
        // No voice backend yet — never consume, keep the space typeable.
        return false;
    }

    // ── Model / thinking ─────────────────────────────────────────────────────
    /** Meta+T — open the session thinking-mode picker. */
    default void toggleThinking() { /* no-op for hosts that don't support thinking */ }
    /**
     * Meta+P — open the interactive model picker.
     */
    default void openModelPicker() { /* no-op for hosts that don't support picker */ }
    /** Meta+O — toggle Fast Mode for the live query session. */
    default void toggleFastMode() { /* no-op for hosts that don't support Fast Mode */ }
    /** Ctrl+T — cycle compact, expanded, and hidden task views. */
    default void toggleTodos() { /* no-op for hosts that don't support todo panel */ }
    default void setTeammateTreeExpanded(boolean expanded) { /* no-op */ }
    default boolean isTeammateTreeExpanded() { return false; }

    // ── Permission ───────────────────────────────────────────────────────────
    /** Shift+Tab cycled the permission mode; {@code uiMode} is the new mode. */
    void permissionModeChanged(String uiMode);

    // ── Background-tasks footer pill ─────────────────────────────────────────
    /**
     * Open the {@code /tasks} BackgroundTasksDialog — fired by ↓ while the footer tasks pill is
     * selected, Enter on the selected pill, or Shift+↓ with background tasks present.
     */
    default void openTasksDialog() { /* no-op */ }


    default void openWorkflowDialog(String taskId) { /* no-op */ }

    /** Opens the session collaboration channel picker. */
    @Explanation("Opens the semantic IM collaboration picker")
    default void openCollaborationPicker() { /* no-op */ }

    // ── Message-actions overlay (Shift+Up browse mode) ───────────────────────
    /** Toggle the message-actions overlay on/off. */
    void toggleMessageActions();
    /** Navigate to the previous message (Up / k). */
    void messageActionsPrev();
    /** Navigate to the next message (Down / j). */
    void messageActionsNext();
    default void messageActionsPrevUser() { messageActionsPrev(); }
    default void messageActionsNextUser() { messageActionsNext(); }
    default void messageActionsTop() {}
    default void messageActionsBottom() {}
    default void messageActionsEscape() { toggleMessageActions(); }
    default void messageActionsForceExit() { toggleMessageActions(); }
    /** Copy the focused message (c). */
    void messageActionsCopy();
    /** Edit the focused message (Enter). */
    void messageActionsEdit();
    default void messageActionsCopyPrimaryInput() {}

    // ── Notifications (data-out) ─────────────────────────────────────────────
    /** Fired on every keystroke: current text + cursor column. */
    void queryChanged(String text, int cursor);
    /** Fired when the pasted-content set (image / text chips) changed. */
    void pastedContentsChanged(Map<Integer, PastedContent> contents);
    /** Fired on Vim mode change so the terminal cursor shape can follow. */
    void cursorStyleChanged(CursorStyle style);
    /** Terminal focus gained/lost (DECSET 1004). */
    void focusChanged(boolean focused);

    // ── Teammate-view transcript swap ───────────────────────────────────────
    /**
     * Fired when the REPL enters/exits/steps through a teammate view, so the
     * screen can swap the main transcript between the leader's history and the
     * viewed teammate's conversation log. Default no-op for hosts without the
     * full-screen transcript.
     */
    default void teammateViewChanged() { /* no-op */ }
}
