package com.claudecode.ui.lanterna.slash;

import com.claudecode.commands.prompt.PromptInvocation;
import com.claudecode.core.message.PastedContent;
import com.claudecode.core.queue.QueuedCommand;
import java.util.Map;
import java.util.function.BooleanSupplier;

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

    /**
     * The prompt of a long-running command the interrupt gesture already force-ended: 236's
     * {@code onQuery} still appends the command's messages and runs the turn, but against the
     * aborted controller, so it unwinds as user-cancelled before reaching the model.
     */
    default void executeInterruptedPrompt(String displayText, PromptInvocation invocation,
                                          Map<Integer, PastedContent> pasted) {
        executePrompt(displayText, invocation, pasted);
    }

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

    /** Whether a long-running local command ({@code /compact}, ...) or a {@code !} bash command is active. */
    default boolean isLongRunningCommandInFlight() { return false; }

    /**
     * One long-running command's hold on the REPL's loading state, returned by
     * {@link #longRunningCommandStarted}.
     */
    interface LongRunningCommand {
        LongRunningCommand NONE = new LongRunningCommand() {
            @Override public boolean interrupted() { return false; }
            @Override public void finished() { }
        };

        /**
         * The interrupt gesture already released the loading state (236's
         * {@code queryGuard.forceEnd()}); a prompt-type result goes through
         * {@link #executeInterruptedPrompt}.
         */
        boolean interrupted();

        /**
         * GUI thread, after the command's result is rendered: clears the busy flag and drains
         * one command queued during it (each drained turn drains the next at its own
         * completion — same chain as the turn-end drain). A no-op once interrupted.
         */
        void finished();
    }

    /**
     * Marks the start of a background long-running command ({@code Command.isLongRunning}): the
     * host shows {@code input} as a processing placeholder with the loading spinner, and routes
     * the interrupt gesture to {@code cancel} until the returned handle finishes.
     */
    default LongRunningCommand longRunningCommandStarted(String input, BooleanSupplier cancel) {
        return LongRunningCommand.NONE;
    }

    /**
     * Materialize native interactive metadata immediately before a local long-running command writes
     * transcript content.
     */
    default void prepareLongRunningCommandTranscript() {}
}
