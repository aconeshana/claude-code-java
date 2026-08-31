package com.claudecode.ui.lanterna.repl;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.commands.Command;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandRegistry;
import com.claudecode.core.message.PastedContent;
import com.claudecode.core.queue.QueuePriority;
import com.claudecode.core.queue.QueuedCommand;
import com.claudecode.runtime.turn.TurnEngine;
import com.claudecode.ui.lanterna.bashmode.BashModeExecutor;
import com.claudecode.ui.lanterna.slash.SlashCommandDispatcher;
import com.claudecode.core.paste.PastedRefParser;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.input.PromptHistory;

/**
 * Routes submitted prompt text through history, bash, slash, queue, or a new turn.
 */
final class ReplSubmissionCoordinator {
    private final InputPanel input;
    private final PromptHistory history;
    private final CommandRegistry commands;
    private final CommandContext commandContext;
    private final ImmediateCommandUiAdapter immediate;
    private final BashModeExecutor bash;
    private final SlashCommandDispatcher slash;
    private final TurnEngine turns;
    private final BiConsumer<String, Map<Integer, PastedContent>> submitTurn;
    private final BiConsumer<String, Map<Integer, PastedContent>> submitRemoteTurn;
    private final BiConsumer<QueuedCommand, String> queue;
    private final Supplier<String> sessionId;
    private final String projectRoot;
    private volatile boolean longRunning;

    ReplSubmissionCoordinator(InputPanel input, PromptHistory history, CommandRegistry commands,
                              CommandContext commandContext, ImmediateCommandUiAdapter immediate,
                              BashModeExecutor bash, SlashCommandDispatcher slash, TurnEngine turns,
                              BiConsumer<String, Map<Integer, PastedContent>> submitTurn,
                              BiConsumer<String, Map<Integer, PastedContent>> submitRemoteTurn,
                              BiConsumer<QueuedCommand, String> queue, Supplier<String> sessionId,
                              String projectRoot) {
        this.input = input;
        this.history = history;
        this.commands = commands;
        this.commandContext = commandContext;
        this.immediate = immediate;
        this.bash = bash;
        this.slash = slash;
        this.turns = turns;
        this.submitTurn = submitTurn;
        this.submitRemoteTurn = submitRemoteTurn;
        this.queue = queue;
        this.sessionId = sessionId;
        this.projectRoot = projectRoot;
    }

    void handleInput(String value) {
        if (StringUtils.isBlank(value)) return;
        Map<Integer, PastedContent> raw = input.getPastedContents();
        Map<Integer, PastedContent> pasted = dropUnreferencedImages(value, raw);
        boolean hasImages = pasted.values().stream().anyMatch(PastedRefParser::isValidImagePaste);

        // History keeps the UNEXPANDED display text plus the full chip map, so ↑ replays
        // the compact "[Pasted text #1 +40 lines]" line the user actually saw.
        history.addEntry(value, sessionId.get(), System.getProperty("user.dir"), projectRoot, raw);
        input.resetHistory();


        // dispatching, "so queued commands and immediate commands both receive the
        // expanded text from when it was submitted". Without this the model only ever
        // saw the chip label and the pasted body was silently dropped.
        String finalInput = PastedRefParser.expandPastedTextRefs(value, pasted);

        // inlined the map is only still needed for image blocks.
        Map<Integer, PastedContent> forTurn = hasImages ? pasted : Map.of();

        if (Strings.CS.startsWith(finalInput, "!")) {
            bash.handle(finalInput.substring(1));
            return;
        }
        if (Strings.CS.startsWith(finalInput, "/")) {
            String[] parts = finalInput.substring(1).split("\\s+", 2);
            String name = parts[0].toLowerCase(Locale.ROOT);
            String args = parts.length > 1 ? parts[1].trim() : "";
            Command command = commands.find(name).orElse(null);
            boolean busy = turns.isInFlight() || longRunning;
            if (command != null && immediate.tryDispatchImmediate(command, args, false, busy, commandContext)) return;
            if (longRunning) {
                enqueue(finalInput, forTurn, value);
                return;
            }
            slash.dispatch(finalInput);
            return;
        }
        handleQuery(finalInput, forTurn, value, false);
    }

    void handleQuery(String value) {
        handleQuery(value, input.getPastedContents(), null, false);
    }

    /** Direct semantic endpoint submit that does not mutate the terminal input widget. */
    void handleRemoteQuery(String value, Map<Integer, PastedContent> pasted) {
        if (StringUtils.isBlank(value)) return;
        handleQuery(value, pasted == null ? Map.of() : Map.copyOf(pasted), null, true);
    }

    void longRunningStarted() { longRunning = true; }
    void longRunningFinished() { longRunning = false; turns.drainIfIdle(); }
    boolean longRunningInFlight() { return longRunning; }

    private void handleQuery(String value, Map<Integer, PastedContent> pasted,
                             String preExpansionValue, boolean remote) {
        if (turns.isInFlight() || longRunning) {
            enqueue(value, pasted, preExpansionValue, remote ? "session-host" : null);
            return;
        }
        (remote ? submitRemoteTurn : submitTurn).accept(value, pasted);
    }

    /**
     * Queue an already-expanded submission.
     */
    private void enqueue(String value, Map<Integer, PastedContent> pasted,
                         String preExpansionValue) {
        enqueue(value, pasted, preExpansionValue, null);
    }

    private void enqueue(String value, Map<Integer, PastedContent> pasted,
                         String preExpansionValue, String originKind) {
        String preview = preExpansionValue != null ? preExpansionValue : value;
        queue.accept(new QueuedCommand(value, pasted, "prompt", QueuePriority.NEXT,
            false, originKind, false, false, preExpansionValue, null, null), preview);
        input.setQueuedHint(true);
    }


    private static Map<Integer, PastedContent> dropUnreferencedImages(
            String value, Map<Integer, PastedContent> raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        Set<Integer> referenced = new HashSet<>();
        for (PastedRefParser.Ref ref : PastedRefParser.parseReferences(value)) {
            referenced.add(ref.id());
        }
        Map<Integer, PastedContent> kept = new LinkedHashMap<>();
        for (Map.Entry<Integer, PastedContent> e : raw.entrySet()) {
            if (e.getValue().isImage() && !referenced.contains(e.getKey())) continue;
            kept.put(e.getKey(), e.getValue());
        }
        return kept;
    }
}
