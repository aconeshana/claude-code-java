package com.claudecode.ui.lanterna.repl;

import com.claudecode.tools.cron.CronScheduler;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/** Routes interactive cron fires to the lead or their owning live teammate. */
final class ScheduledTaskInteractionRouter {

    private final BiPredicate<String, String> teammateInjector;
    private final Consumer<String> orphanRemover;
    private final Consumer<CronScheduler.FiredTask> leadHandler;

    ScheduledTaskInteractionRouter(BiPredicate<String, String> teammateInjector,
                                   Consumer<String> orphanRemover,
                                   Consumer<CronScheduler.FiredTask> leadHandler) {
        this.teammateInjector = teammateInjector;
        this.orphanRemover = orphanRemover;
        this.leadHandler = leadHandler;
    }

    void route(CronScheduler.FiredTask task) {
        if (task.agentId() == null) {
            leadHandler.accept(task);
            return;
        }
        if (!teammateInjector.test(task.agentId(), task.prompt())) {
            orphanRemover.accept(task.id());
        }
    }
}
