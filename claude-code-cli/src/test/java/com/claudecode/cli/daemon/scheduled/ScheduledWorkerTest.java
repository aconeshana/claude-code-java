package com.claudecode.cli.daemon.scheduled;

import com.claudecode.tools.cron.CronJitterConfig;
import com.claudecode.tools.cron.CronSchedule;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduledWorkerTest {

    private static final CronJitterConfig NO_JITTER = new CronJitterConfig(
        0d, 0L, 0L, 0L, 30, 0L, 0L);

    @Test
    void runsDueTasksThroughGlobalFifoConcurrencyLimit() {
        ControlledRunner runner = new ControlledRunner();
        ScheduledWorker worker = new ScheduledWorker(
            new ScheduledWorkerConfig(List.of(task("a"), task("b")), 1),
            runner, NO_JITTER, 0L, _ -> {});
        long due = CronSchedule.recurringJitteredNextRunMs("* * * * *", 0L, "a", NO_JITTER);

        worker.checkNow(due);

        assertEquals(List.of("a"), runner.startedIds());
        assertEquals(1, worker.snapshot().running());
        assertEquals(1, worker.snapshot().queued());

        runner.complete("a");

        assertEquals(List.of("a", "b"), runner.startedIds());
        assertEquals(1, worker.snapshot().running());
        assertEquals(0, worker.snapshot().queued());
    }

    @Test
    void dropsOccurrencesBeyondPerTaskQueueLimit() {
        ControlledRunner runner = new ControlledRunner();
        List<String> logs = new ArrayList<>();
        ScheduledWorker worker = new ScheduledWorker(
            new ScheduledWorkerConfig(List.of(task("a")), 1),
            runner, NO_JITTER, 0L, logs::add);
        long first = CronSchedule.recurringJitteredNextRunMs("* * * * *", 0L, "a", NO_JITTER);
        long second = CronSchedule.recurringJitteredNextRunMs("* * * * *", first, "a", NO_JITTER);
        long third = CronSchedule.recurringJitteredNextRunMs("* * * * *", second, "a", NO_JITTER);

        worker.checkNow(first);
        worker.checkNow(second);
        worker.checkNow(third);

        assertEquals(1, worker.snapshot().running());
        assertEquals(1, worker.snapshot().queued());
        assertTrue(logs.stream().anyMatch(line -> line.contains("queue full: 1/1")));
    }

    @Test
    void closeCancelsRunningTasksAndPreventsNewDispatch() {
        ControlledRunner runner = new ControlledRunner();
        ScheduledWorker worker = new ScheduledWorker(
            new ScheduledWorkerConfig(List.of(task("a")), 1),
            runner, NO_JITTER, 0L, _ -> {});
        long due = CronSchedule.recurringJitteredNextRunMs("* * * * *", 0L, "a", NO_JITTER);
        worker.checkNow(due);

        worker.close();
        worker.checkNow(due + 60_000L);

        assertTrue(runner.future("a").isCancelled());
        assertEquals(List.of("a"), runner.startedIds());
        assertFalse(worker.snapshot().acceptingTasks());
    }

    private static ScheduledTaskConfig task(String id) {
        return new ScheduledTaskConfig(id, "* * * * *", "prompt " + id,
            Path.of("/tmp"), true, ScheduledPermissionMode.DONT_ASK,
            null, 30, 1);
    }

    private static final class ControlledRunner implements ScheduledTaskRunner {
        private final List<String> started = new ArrayList<>();
        private final Map<String, CompletableFuture<ScheduledTaskResult>> futures =
            new LinkedHashMap<>();

        @Override
        public synchronized CompletableFuture<ScheduledTaskResult> run(ScheduledTaskConfig task) {
            started.add(task.id());
            CompletableFuture<ScheduledTaskResult> future = new CompletableFuture<>();
            futures.put(task.id(), future);
            return future;
        }

        synchronized List<String> startedIds() {
            return List.copyOf(started);
        }

        synchronized CompletableFuture<ScheduledTaskResult> future(String id) {
            return futures.get(id);
        }

        void complete(String id) {
            future(id).complete(ScheduledTaskResult.success("done"));
        }
    }
}
