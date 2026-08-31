package com.claudecode.ui.lanterna.slash;

import com.claudecode.commands.prompt.PromptInvocation;
import com.claudecode.core.message.PastedContent;
import com.claudecode.core.queue.QueuedCommand;
import java.util.Map;

/**
 * The command port {@link SlashCommandDispatcher} talks to its owning REPL through — the
 * irreducible behaviors only the screen can perform, plus the one piece of live turn state a
 * dispatch decision needs. Split out so the dispatcher is unaware of the concrete screen
 * implementation and can be built/tested independently.
 *
 * <p>This is a pure <em>behavior</em> port: every method is something the REPL <em>does</em>
 * (submit a query, queue, exit, toggle fast mode) or a live-state query that cannot be captured
 * as a snapshot ({@link #isTurnInFlight}). The concrete components a slash command reads and
 * renders into — message panel, input panel, gui, query engine, permission gate — are not
 * behaviors and need no inversion; they are injected as plain references via {@link ReplRefs}.
 */
public interface SlashHost {

    void handleQuery(String input);
    void executeQuery(String displayText, String queryContent,
                      Map<Integer, PastedContent> pasted);

    default void executeQuery(String displayText, String queryContent,
                              Map<Integer, PastedContent> pasted, boolean isSlash) {
        executeQuery(displayText, queryContent, pasted);
    }

    /**
     * Submit a prompt-command invocation without flattening its content blocks
     * or dropping turn-scoped permissions/model metadata.
     */
    void executePrompt(String displayText, PromptInvocation invocation,
                       Map<Integer, PastedContent> pasted);

    void renderAndQueue(QueuedCommand cmd, String displayText);
    void showSessionPicker();
    void toggleFastMode();

    /**
     * A local command changed the live permission gate outside a model turn.
     * The dispatcher has already synchronized the prompt widget; the owning
     * screen uses this callback for dependent status-line/entry-warning state.
     */
    default void permissionModeSynchronized(String mode) { }

    void stop();

    /**
     * Request an orderly shutdown of the REPL.
     */
    default void requestShutdown(String reason, int exitCode) { stop(); }

    /** Backwards-compatible overload — implies {@code exitCode = 0}. */
    default void requestShutdown(String reason) { requestShutdown(reason, 0); }

    /** Live turn state — a genuine query (cannot be a construction-time snapshot). */
    boolean isTurnInFlight();

    /** Whether a manual long-running local command such as {@code /compact} is active. */
    default boolean isLongRunningCommandInFlight() { return false; }

    /**
     * Marks the start of a background long-running command ({@code Command.isLongRunning}, currently
     * {@code /compact}).
     */
    default void longRunningCommandStarted() {}

    /**
     * Materialize native interactive metadata immediately before a local long-running command writes
     * transcript content.
     */
    default void prepareLongRunningCommandTranscript() {}

    /**
     * Marks the end of a background long-running command: the host clears the
     * busy flag and drains one command queued during it (each drained turn
     * drains the next at its own completion — same chain as the turn-end
     * drain). Called on the GUI thread after the command's result is rendered.
     */
    default void longRunningCommandFinished() {}
}
