package com.claudecode.cli.daemon.scheduled;

import com.claudecode.tools.cron.CronJitterConfig;
import com.claudecode.tools.cron.CronSchedule;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

final class ScheduledWorker implements AutoCloseable {

    private final ScheduledWorkerConfig config;
    private final ScheduledTaskRunner runner;
    private final CronJitterConfig jitterConfig;
    private final Consumer<String> log;
    private final ArrayDeque<ScheduledTaskConfig> queue = new ArrayDeque<>();
    private final Map<String, Integer> queuedByTask = new HashMap<>();
    private final Map<String, Long> nextFireAt = new LinkedHashMap<>();
    private final Map<String, Long> lastFiredAt = new LinkedHashMap<>();
    private final List<CompletableFuture<ScheduledTaskResult>> running = new ArrayList<>();
    private boolean acceptingTasks = true;

    ScheduledWorker(ScheduledWorkerConfig config, ScheduledTaskRunner runner,
                    CronJitterConfig jitterConfig, long startedAt,
                    Consumer<String> log) {
        this.config = config;
        this.runner = runner;
        this.jitterConfig = jitterConfig == null ? CronJitterConfig.DEFAULT : jitterConfig;
        this.log = log == null ? _ -> {} : log;
        for (ScheduledTaskConfig task : config.tasks()) {
            if (!task.enabled()) continue;
            Long next = CronSchedule.recurringJitteredNextRunMs(
                task.cron(), startedAt, task.id(), this.jitterConfig);
            if (next != null) nextFireAt.put(task.id(), next);
        }
    }

    synchronized void checkNow(long now) {
        if (!acceptingTasks) return;
        for (ScheduledTaskConfig task : config.tasks()) {
            if (!task.enabled()) continue;
            Long next = nextFireAt.get(task.id());
            if (next == null || now < next) continue;
            int queued = queuedByTask.getOrDefault(task.id(), 0);
            if (queued >= task.maxQueued()) {
                log.accept("task=" + task.id() + " dropped (queue full: "
                    + queued + "/" + task.maxQueued() + ")");
            } else {
                queue.addLast(task);
                queuedByTask.put(task.id(), queued + 1);
                lastFiredAt.put(task.id(), now);
            }
            Long following = CronSchedule.recurringJitteredNextRunMs(
                task.cron(), now, task.id(), jitterConfig);
            if (following == null) nextFireAt.remove(task.id());
            else nextFireAt.put(task.id(), following);
        }
        drainQueue();
    }

    synchronized ScheduledWorkerSnapshot snapshot() {
        return new ScheduledWorkerSnapshot(
            acceptingTasks, running.size(), queue.size(), lastFiredAt);
    }

    private void drainQueue() {
        while (acceptingTasks && running.size() < config.maxConcurrent() && !queue.isEmpty()) {
            ScheduledTaskConfig task = queue.removeFirst();
            decrementQueued(task.id());
            CompletableFuture<ScheduledTaskResult> future;
            try {
                future = runner.run(task);
                if (future == null) {
                    future = CompletableFuture.completedFuture(
                        ScheduledTaskResult.failure("runner returned no result"));
                }
            } catch (RuntimeException failure) {
                future = CompletableFuture.failedFuture(failure);
            }
            running.add(future);
            CompletableFuture<ScheduledTaskResult> tracked = future;
            tracked.whenComplete((result, failure) -> taskFinished(task, tracked, result, failure));
        }
    }

    private synchronized void taskFinished(
            ScheduledTaskConfig task,
            CompletableFuture<ScheduledTaskResult> future,
            ScheduledTaskResult result,
            Throwable failure) {
        running.remove(future);
        if (failure != null) {
            log.accept("task=" + task.id() + " threw: " + failure.getMessage());
        } else if (result != null) {
            log.accept("task=" + task.id() + " result success=" + result.success()
                + (result.detail() == null ? "" : " detail=" + result.detail()));
        }
        drainQueue();
    }

    private void decrementQueued(String taskId) {
        int remaining = queuedByTask.getOrDefault(taskId, 0) - 1;
        if (remaining <= 0) queuedByTask.remove(taskId);
        else queuedByTask.put(taskId, remaining);
    }

    @Override
    public synchronized void close() {
        if (!acceptingTasks) return;
        acceptingTasks = false;
        queue.clear();
        queuedByTask.clear();
        List<CompletableFuture<ScheduledTaskResult>> toCancel = List.copyOf(running);
        toCancel.forEach(future -> future.cancel(true));
        running.clear();
    }
}
