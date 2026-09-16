package com.claudecode.ui.lanterna.repl;

import com.claudecode.core.message.MessageFactory;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.queue.QueuedCommand;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.runtime.turn.TurnEngine;
import com.claudecode.tools.cron.CronScheduler;
import com.claudecode.ui.lanterna.transcript.LanternaMessageDispatcher;
import com.claudecode.ui.lanterna.transcript.MessagePanel;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Consumer;
import org.apache.commons.lang3.Strings;

/**
 * Surfaces a cron / {@code /loop} task that fired for the lead session: writes the
 * "Running scheduled task (…)" system row to the transcript, enqueues the resolved prompt as a
 * model-scheduled command, and pokes the turn engine so an idle REPL picks it up immediately.
 *
 * <p>TS coverage (paths relative to the claude-code repo root):
 * <ul>
 *   <li>{@code services/cron/} — scheduled-task fire handling for the main session (system
 *       message + queued prompt).</li>
 * </ul>
 */
final class LeadScheduledTaskPresenter {

    private final Consumer<Runnable> guiInvoker;
    private final QuerySession queryEngine;
    private final LanternaMessageDispatcher dispatcher;
    private final MessagePanel messagePanel;
    private final TurnEngine turnEngine;

    LeadScheduledTaskPresenter(Consumer<Runnable> guiInvoker, QuerySession queryEngine,
                               LanternaMessageDispatcher dispatcher, MessagePanel messagePanel,
                               TurnEngine turnEngine) {
        this.guiInvoker = guiInvoker;
        this.queryEngine = queryEngine;
        this.dispatcher = dispatcher;
        this.messagePanel = messagePanel;
        this.turnEngine = turnEngine;
    }

    void present(CronScheduler.FiredTask task) {
        guiInvoker.accept(() -> {
            ZonedDateTime now = ZonedDateTime.now();
            String displayTime = now.format(DateTimeFormatter.ofPattern("MMM d h:mm", Locale.US))
                + now.format(DateTimeFormatter.ofPattern("a", Locale.US)).toLowerCase(Locale.US);
            String label = Strings.CS.equals("loop", task.kind())
                ? "Claude resuming /loop wakeup (" + displayTime + ")"
                : "Running scheduled task (" + displayTime + ")";
            SystemMessage fireMsg = MessageFactory.createScheduledTaskFireMessage(label);
            queryEngine.conversation().appendTranscriptMessage(fireMsg);
            dispatcher.dispatch(new SDKMessage.System(fireMsg), messagePanel);
            queryEngine.conversation().getMessageQueue().enqueuePendingNotification(
                QueuedCommand.modelScheduled(
                    task.resolvedPrompt(), task.prompt(), "cron", null, task.model()));
            turnEngine.drainIfIdle();
        });
    }
}
