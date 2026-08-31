package com.claudecode.ui.lanterna.features.tasks;

import com.claudecode.runtime.tasks.TaskBoardPort;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Tracks presentation-only task transitions that are intentionally absent from persistence. */
public final class TaskBoardPresentationState {

    private final Map<String, TaskBoardPort.Status> statuses = new HashMap<>();
    private final Map<String, Long> completionTimes = new HashMap<>();
    private String listId = "";
    private boolean baselineEstablished;

    public synchronized void update(TaskBoardPort.Snapshot snapshot, long nowMillis) {
        TaskBoardPort.Snapshot current = snapshot == null
            ? TaskBoardPort.Snapshot.EMPTY : snapshot;
        if (!Objects.equals(listId, current.listId())) {
            listId = current.listId();
            statuses.clear();
            completionTimes.clear();
            baselineEstablished = false;
        }
        boolean establishingBaseline = !baselineEstablished;
        Set<String> present = new HashSet<>();
        for (TaskBoardPort.TaskItem task : current.tasks()) {
            present.add(task.id());
            TaskBoardPort.Status previous = statuses.put(task.id(), task.status());
            if (task.status() == TaskBoardPort.Status.COMPLETED) {
                if (!establishingBaseline && previous != TaskBoardPort.Status.COMPLETED) {
                    completionTimes.put(task.id(), nowMillis);
                }
            } else {
                completionTimes.remove(task.id());
            }
        }
        statuses.keySet().removeIf(id -> !present.contains(id));
        completionTimes.keySet().removeIf(id -> !present.contains(id));
        baselineEstablished = true;
    }

    public synchronized Map<String, Long> completionTimes(long nowMillis) {
        expire(nowMillis);
        return Map.copyOf(completionTimes);
    }

    public synchronized long nextExpiryDelayMillis(long nowMillis) {
        expire(nowMillis);
        return completionTimes.values().stream()
            .mapToLong(completedAt -> completedAt + TaskBoardProjection.RECENT_COMPLETED_MS - nowMillis)
            .filter(remaining -> remaining > 0L)
            .min()
            .orElse(-1L);
    }

    private void expire(long nowMillis) {
        completionTimes.values().removeIf(completedAt ->
            nowMillis - completedAt >= TaskBoardProjection.RECENT_COMPLETED_MS);
    }
}
