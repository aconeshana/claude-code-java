package com.claudecode.ui.lanterna.features.tasks;

import com.claudecode.runtime.tasks.TaskBoardPort;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Pure projection of a task-board snapshot into terminal rows. */
public final class TaskBoardProjection {

    static final long RECENT_COMPLETED_MS = 30_000L;

    public record ActiveOwner(String colorName, String activity) {}

    public record Row(
            String id,
            String subject,
            TaskBoardPort.Status status,
            boolean blocked,
            List<String> openBlockers,
            String owner,
            String ownerColor,
            String activity,
            boolean bold,
            boolean dim,
            boolean strikethrough) {
        public Row {
            openBlockers = List.copyOf(openBlockers);
        }

        public int height() {
            return activity == null ? 1 : 2;
        }
    }

    public record View(
            boolean standalone,
            boolean expandable,
            String title,
            List<Row> rows,
            String overflow,
            int preferredRows) {
        public static final View EMPTY = new View(false, false, "", List.of(), "", 0);

        public View {
            rows = List.copyOf(rows);
        }
    }

    private TaskBoardProjection() {}

    public static View project(
            TaskBoardPort.Snapshot snapshot,
            int terminalRows,
            int columns,
            boolean standalone,
            long nowMillis,
            Map<String, Long> completionTimes) {
        return project(snapshot, terminalRows, columns, standalone, false, nowMillis,
            completionTimes, Map.of());
    }

    public static View project(
            TaskBoardPort.Snapshot snapshot,
            int terminalRows,
            int columns,
            boolean standalone,
            long nowMillis,
            Map<String, Long> completionTimes,
            Map<String, ActiveOwner> activeOwners) {
        return project(snapshot, terminalRows, columns, standalone, false, nowMillis,
            completionTimes, activeOwners);
    }

    public static View project(
            TaskBoardPort.Snapshot snapshot,
            int terminalRows,
            int columns,
            boolean standalone,
            boolean expanded,
            long nowMillis,
            Map<String, Long> completionTimes) {
        return project(snapshot, terminalRows, columns, standalone, expanded, nowMillis,
            completionTimes, Map.of());
    }

    public static View project(
            TaskBoardPort.Snapshot snapshot,
            int terminalRows,
            int columns,
            boolean standalone,
            boolean expanded,
            long nowMillis,
            Map<String, Long> completionTimes,
            Map<String, ActiveOwner> activeOwners) {
        if (snapshot == null || snapshot.hidden() || snapshot.tasks().isEmpty()) {
            return View.EMPTY;
        }
        int expandedCapacity = terminalRows <= 10
            ? 0 : Math.max(3, terminalRows - 14);
        int compactCapacity = Math.min(5, expandedCapacity);
        List<TaskBoardPort.TaskItem> tasks = snapshot.tasks();
        Set<String> unresolved = tasks.stream()
            .filter(task -> task.status() != TaskBoardPort.Status.COMPLETED)
            .map(TaskBoardPort.TaskItem::id)
            .collect(Collectors.toCollection(HashSet::new));
        List<TaskBoardPort.TaskItem> ordered = order(
            tasks, unresolved, compactCapacity, nowMillis, completionTimes);
        List<Row> orderedRows = ordered.stream()
            .map(task -> row(task, unresolved, columns, activeOwners))
            .toList();
        int compactDisplay = Math.min(compactCapacity, orderedRows.size());
        int expandedDisplay = expandedDisplayCount(
            orderedRows, compactDisplay, expandedCapacity);
        boolean expandable = expandedDisplay > compactDisplay;
        int maxDisplay = expanded && expandable ? expandedDisplay : compactDisplay;
        List<TaskBoardPort.TaskItem> hidden = ordered.stream().skip(maxDisplay).toList();
        List<Row> rows = orderedRows.stream().limit(maxDisplay).toList();
        String title = standalone ? title(tasks) : "";
        String overflow = maxDisplay > 0 ? overflow(hidden) : "";
        int preferredRows = rows.stream().mapToInt(Row::height).sum()
            + (standalone ? 1 : 0)
            + (title.isEmpty() ? 0 : 1)
            + (overflow.isEmpty() ? 0 : 1);
        return new View(standalone, expandable, title, rows, overflow, preferredRows);
    }

    private static int expandedDisplayCount(
            List<Row> rows, int compactDisplay, int expandedCapacity) {
        int displayed = compactDisplay;
        int usedRows = rows.stream().limit(compactDisplay).mapToInt(Row::height).sum();
        int rowBudget = Math.max(expandedCapacity, usedRows);
        while (displayed < rows.size()) {
            int nextHeight = rows.get(displayed).height();
            if (usedRows + nextHeight > rowBudget) break;
            usedRows += nextHeight;
            displayed++;
        }
        return displayed;
    }

    private static List<TaskBoardPort.TaskItem> order(
            List<TaskBoardPort.TaskItem> tasks,
            Set<String> unresolved,
            int maxDisplay,
            long nowMillis,
            Map<String, Long> completionTimes) {
        if (tasks.size() <= maxDisplay) return tasks.stream().sorted(ID_ORDER).toList();
        Map<Integer, List<TaskBoardPort.TaskItem>> buckets = new LinkedHashMap<>();
        for (int index = 0; index < 5; index++) buckets.put(index, new ArrayList<>());
        for (TaskBoardPort.TaskItem task : tasks) {
            int bucket;
            if (isRecentlyCompleted(task, nowMillis, completionTimes)) bucket = 0;
            else if (task.status() == TaskBoardPort.Status.IN_PROGRESS) bucket = 1;
            else if (task.status() == TaskBoardPort.Status.PENDING) {
                boolean blocked = task.blockedBy().stream().anyMatch(unresolved::contains);
                bucket = blocked ? 3 : 2;
            } else bucket = 4;
            buckets.get(bucket).add(task);
        }
        return buckets.values().stream()
            .flatMap(bucket -> bucket.stream().sorted(ID_ORDER))
            .toList();
    }

    private static boolean isRecentlyCompleted(
            TaskBoardPort.TaskItem task, long nowMillis, Map<String, Long> completionTimes) {
        if (task.status() != TaskBoardPort.Status.COMPLETED) return false;
        Long completedAt = completionTimes.get(task.id());
        return completedAt != null && nowMillis - completedAt < RECENT_COMPLETED_MS;
    }

    private static Row row(
            TaskBoardPort.TaskItem task,
            Set<String> unresolved,
            int columns,
            Map<String, ActiveOwner> activeOwners) {
        List<String> blockers = task.blockedBy().stream()
            .filter(unresolved::contains).sorted(BLOCKER_ID_ORDER).toList();
        boolean blocked = !blockers.isEmpty();
        ActiveOwner activeOwner = task.owner() == null ? null : activeOwners.get(task.owner());
        String owner = columns >= 60 && activeOwner != null ? task.owner() : null;
        String activity = task.status() == TaskBoardPort.Status.IN_PROGRESS
            && !blocked && activeOwner != null && activeOwner.activity() != null
            && !activeOwner.activity().isEmpty() ? activeOwner.activity() : null;
        boolean completed = task.status() == TaskBoardPort.Status.COMPLETED;
        boolean inProgress = task.status() == TaskBoardPort.Status.IN_PROGRESS;
        return new Row(task.id(), task.subject(), task.status(), blocked, blockers,
            owner, activeOwner == null ? null : activeOwner.colorName(), activity,
            inProgress, completed || blocked, completed);
    }

    private static String title(List<TaskBoardPort.TaskItem> tasks) {
        long completed = count(tasks, TaskBoardPort.Status.COMPLETED);
        long pending = count(tasks, TaskBoardPort.Status.PENDING);
        long inProgress = tasks.size() - completed - pending;
        String middle = inProgress > 0 ? inProgress + " in progress, " : "";
        return tasks.size() + " tasks (" + completed + " done, " + middle + pending + " open)";
    }

    private static String overflow(List<TaskBoardPort.TaskItem> hidden) {
        if (hidden.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        long inProgress = count(hidden, TaskBoardPort.Status.IN_PROGRESS);
        long pending = count(hidden, TaskBoardPort.Status.PENDING);
        long completed = count(hidden, TaskBoardPort.Status.COMPLETED);
        if (inProgress > 0) parts.add(inProgress + " in progress");
        if (pending > 0) parts.add(pending + " pending");
        if (completed > 0) parts.add(completed + " completed");
        return " … +" + String.join(", ", parts);
    }

    private static long count(List<TaskBoardPort.TaskItem> tasks, TaskBoardPort.Status status) {
        return tasks.stream().filter(task -> task.status() == status).count();
    }

    private static final Comparator<String> ID_STRING_ORDER = (left, right) -> {
        Double leftNumber = parseId(left);
        Double rightNumber = parseId(right);
        if (leftNumber != null && rightNumber != null) {
            return compareJavaScriptNumbers(leftNumber, rightNumber);
        }
        return Collator.getInstance().compare(left, right);
    };

    private static final Comparator<String> BLOCKER_ID_ORDER = (left, right) -> {
        Double leftNumber = parseId(left);
        Double rightNumber = parseId(right);
        return leftNumber == null || rightNumber == null
            ? 0 : compareJavaScriptNumbers(leftNumber, rightNumber);
    };

    private static final Comparator<TaskBoardPort.TaskItem> ID_ORDER =
        Comparator.comparing(TaskBoardPort.TaskItem::id, ID_STRING_ORDER);

    private static int compareJavaScriptNumbers(double left, double right) {
        double difference = left - right;
        if (Double.isNaN(difference) || difference == 0.0d) return 0;
        return difference < 0.0d ? -1 : 1;
    }

    private static Double parseId(String id) {
        if (id == null) return null;
        int index = 0;
        while (index < id.length() && isJavaScriptWhitespace(id.charAt(index))) {
            index++;
        }
        int numberStart = index;
        if (index < id.length() && (id.charAt(index) == '+' || id.charAt(index) == '-')) {
            index++;
        }
        int digitsStart = index;
        while (index < id.length()) {
            char current = id.charAt(index);
            if (current < '0' || current > '9') break;
            index++;
        }
        if (index == digitsStart) return null;
        try {
            return Double.parseDouble(id.substring(numberStart, index));
        } catch (NumberFormatException _) {
            return null;
        }
    }

    private static boolean isJavaScriptWhitespace(char value) {
        return switch (value) {
            case 0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x0020,
                 0x00A0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F,
                 0x3000, 0xFEFF -> true;
            default -> value >= 0x2000 && value <= 0x200A;
        };
    }
}
