package com.claudecode.cli.daemon.scheduled;

import com.claudecode.tools.cron.CronJitterConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduledDaemonRuntimeTest {

    @TempDir
    Path tempDir;

    @Test
    void closeStopsEveryOwnedResourceAndIsIdempotent() {
        ScheduledWorker worker = new ScheduledWorker(
            new ScheduledWorkerConfig(List.of(), 1),
            _ -> CompletableFuture.completedFuture(ScheduledTaskResult.success("done")),
            CronJitterConfig.DEFAULT, 0L, _ -> {});
        var ticker = Executors.newSingleThreadScheduledExecutor();
        var watchdog = Executors.newSingleThreadScheduledExecutor();
        ScheduledDaemonRuntime runtime = new ScheduledDaemonRuntime(
            worker, new ScheduledWorkerStatusWriter(tempDir.resolve("status.json")),
            ticker, watchdog, _ -> {});

        runtime.close();
        runtime.close();

        assertFalse(worker.snapshot().acceptingTasks());
        assertTrue(ticker.isShutdown());
        assertTrue(watchdog.isShutdown());
        assertTrue(runtime.isStopped());
    }
}
