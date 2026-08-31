package com.claudecode.cli.daemon.scheduled;

import java.util.concurrent.CompletableFuture;

/** Asynchronous execution boundary for one scheduled Agent query. */
interface ScheduledTaskRunner {
    CompletableFuture<ScheduledTaskResult> run(ScheduledTaskConfig task);
}
