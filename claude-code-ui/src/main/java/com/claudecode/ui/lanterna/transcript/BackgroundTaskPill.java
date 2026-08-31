package com.claudecode.ui.lanterna.transcript;

import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import com.claudecode.ui.lanterna.input.InputPanel;

/**
 * Pure label mapping for the footer background-task pill. Same-type tasks use
 * a per-type label; mixed tasks use a generic count. Rendering, selection, and
 * refresh cadence remain owned by {@link InputPanel}.
 */
public final class BackgroundTaskPill {

    private BackgroundTaskPill() {}


    static String labelFor(List<TaskState> tasks) {
        return labelFor(tasks, _ -> false);
    }

    public static String labelFor(List<TaskState> tasks, Predicate<String> isMonitorTask) {
        int n = tasks.size();
        if (n == 0) return "";
        TaskType first = tasks.getFirst().type();
        boolean allSameType = tasks.stream().allMatch(t -> t.type() == first);
        if (allSameType) {
            if (first == TaskType.LOCAL_BASH) {
                long monitors = tasks.stream().filter(t -> isMonitorTask.test(t.id())).count();
                long shells = n - monitors;
                ArrayList<String> parts = new ArrayList<>();
                if (shells > 0) parts.add(shells == 1 ? "1 shell" : shells + " shells");
                if (monitors > 0) {
                    parts.add(monitors == 1 ? "1 monitor" : monitors + " monitors");
                }
                return String.join(", ", parts);
            }
            if (first == TaskType.LOCAL_AGENT) {
                return n == 1 ? "1 local agent" : n + " local agents";
            }
            if (first == TaskType.LOCAL_WORKFLOW) {
                return n == 1 ? "1 background workflow" : n + " background workflows";
            }
            if (first == TaskType.MONITOR_MCP || first == TaskType.MONITOR_WS) {
                return n == 1 ? "1 monitor" : n + " monitors";
            }
            if (first == TaskType.DREAM) return "dreaming";

            // remote-agent) have no Java subsystem yet — fall through to the
            // generic mixed label rather than being silently mislabeled.
        }
        return n + " background " + (n == 1 ? "task" : "tasks");
    }
}
