package com.claudecode.ui.lanterna.repl;

import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandRegistry;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.ui.lanterna.input.PromptHistory;
import com.claudecode.ui.lanterna.slash.SlashHost;
import com.claudecode.ui.lanterna.suggest.DirectorySuggestionService;
import com.claudecode.ui.lanterna.transcript.LanternaMessageDispatcher;
import com.claudecode.ui.lanterna.transcript.MessageCollapser;
import com.claudecode.ui.lanterna.transcript.MessageHistory;
import com.claudecode.ui.lanterna.transcript.SelectionAwareTextGUI;
import com.claudecode.ui.lanterna.transcript.ToolPresentationSnapshotStore;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.terminal.Terminal;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Everything the scene composer needs that exists <em>before</em> the scene is built: the live
 * Lanterna terminal/GUI, the session engine, the startup wiring, and the handful of screen-scoped
 * collaborators the screen creates in its constructor or terminal bootstrap.
 *
 * <p>{@code guiInvoker} is the single GUI-thread hop shared by every feature; it replaces the
 * per-site {@code gui.getGUIThread().invokeLater} lambdas the screen used to build inline.
 *
 * @param terminalRows    live terminal height (falls back to a sane default when no screen)
 * @param terminalColumns live terminal width (falls back to 80 when no screen)
 * @param lastSubmittedInput the last text the user submitted, written by the turn engine and
 *                           read by interrupt salvage and {@code /undo}
 */
record ReplContext(
    SelectionAwareTextGUI gui,
    Screen screen,
    Terminal terminal,
    Consumer<Runnable> guiInvoker,
    IntSupplier terminalRows,
    IntSupplier terminalColumns,
    QuerySession queryEngine,
    CommandRegistry commandRegistry,
    CommandContext commandContext,
    ReplWiring wiring,
    SlashHost slashHost,
    LanternaMessageDispatcher dispatcher,
    MessageCollapser collapser,
    ToolPresentationSnapshotStore presentationSnapshots,
    MessageHistory messageHistory,
    PromptHistory promptHistory,
    String historyProjectRoot,
    DirectorySuggestionService directorySuggestions,
    SessionHostPublisher sessionHostPublisher,
    SessionTopicTitleCoordinator sessionTopicTitleCoordinator,
    TerminalController terminalController,
    AtomicReference<String> lastSubmittedInput,
    boolean verbose) {

    ReplContext {
        Objects.requireNonNull(gui, "gui");
        Objects.requireNonNull(screen, "screen");
        Objects.requireNonNull(guiInvoker, "guiInvoker");
        Objects.requireNonNull(queryEngine, "queryEngine");
        Objects.requireNonNull(wiring, "wiring");
        Objects.requireNonNull(slashHost, "slashHost");
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(sessionHostPublisher, "sessionHostPublisher");
        Objects.requireNonNull(lastSubmittedInput, "lastSubmittedInput");
    }

    ReplApplicationPorts application() { return wiring.application(); }
    ReplFeatureRuntime features() { return wiring.features(); }
    ReplLaunchState launch() { return wiring.launch(); }
}
