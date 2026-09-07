package com.claudecode.commands.impl.session;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.recap.RecapPort;
import com.claudecode.core.message.Message;

import java.util.List;

/**
 * {@code /recap} — generate a one-line session recap.
 *
 * <p>Mirrors the {@code recap} local command of the authoritative 2.1.236
 * bundle ({@code type:"local"}, {@code supportsNonInteractive:true},
 * {@code description:"Generate a one-line session recap now"}, dispatched via
 * the shared away-summary execution path). Generation is delegated to a
 * {@link RecapPort} wired by the composition root so the command module stays
 * decoupled from the model-facing {@code AwaySummaryService}. The five
 * terminal states of 236 ({@code ok / api-error / no-turn / aborted / failed})
 * are collapsed onto the recap outcome; api-error and aborted cannot arise in a
 * blocking synchronous command, so both surface as {@code failed}.
 *
 * <p>TS coverage:
 * <ul>
 *   <li>{@code src/commands/recap.ts} (236 binary chunk {@code Bbm}, command
 *       def {@code UlT} + handler {@code BlT}) — the command does not exist in
 *       the weflow tree; the binary is the only source</li>
 * </ul>
 */
@SlashCommand(
    name = "recap",
    description = "Generate a one-line session recap now"
)
public class RecapCommand implements AnnotatedCommand {

    @Override
    public boolean supportsNonInteractive() { return true; }

    /** Synthesis makes a model call — never run it on the GUI thread. */
    @Override
    public boolean isLongRunning() { return true; }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        RecapPort port = context.application().recap();
        List<Message> messages = context.session().messagesSupplier().get();
        RecapPort.Outcome outcome = port.synthesize(messages);
        return switch (outcome.kind()) {
            case OK -> CommandResult.local(outcome.text());
            case NO_TURN -> CommandResult.local("Nothing to recap yet — send a message first.");
            case FAILED -> CommandResult.local("Couldn't generate a recap. Run with --debug for details.");
        };
    }
}