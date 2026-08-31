package com.claudecode.ui.lanterna.features.tasks;

import com.claudecode.runtime.tasks.TaskBoardPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskBoardProjectionTest {

    @Test
    void released197CapacityIsZeroThreeFourOrFive() {
        TaskBoardPort.Snapshot snapshot = snapshot(7);

        assertEquals(0, TaskBoardProjection.project(snapshot, 10, 80, false, 0L, Map.of()).rows().size());
        assertEquals(3, TaskBoardProjection.project(snapshot, 11, 80, false, 0L, Map.of()).rows().size());
        assertEquals(4, TaskBoardProjection.project(snapshot, 18, 80, false, 0L, Map.of()).rows().size());
        assertEquals(5, TaskBoardProjection.project(snapshot, 19, 80, false, 0L, Map.of()).rows().size());
    }

    @Test
    void expandedModeUsesTheTerminalCapacityBeyondTheReleasedFiveRowCap() {
        TaskBoardPort.Snapshot snapshot = snapshot(12);

        TaskBoardProjection.View compact = TaskBoardProjection.project(
            snapshot, 24, 80, false, false, 0L, Map.of());
        TaskBoardProjection.View expanded = TaskBoardProjection.project(
            snapshot, 24, 80, false, true, 0L, Map.of());

        assertEquals(5, compact.rows().size());
        assertTrue(compact.expandable());
        assertEquals(10, expanded.rows().size());
        assertTrue(expanded.expandable());
        assertFalse(expanded.overflow().isEmpty(),
            "the terminal-safe expanded view still summarizes rows that cannot fit");
    }

    @Test
    void expandedModeShowsEveryTaskWhenTheTerminalHasRoom() {
        TaskBoardPort.Snapshot snapshot = snapshot(7);

        TaskBoardProjection.View expanded = TaskBoardProjection.project(
            snapshot, 24, 80, false, true, 0L, Map.of());

        assertEquals(7, expanded.rows().size());
        assertTrue(expanded.overflow().isEmpty());
    }

    @Test
    void compactBoardIsNotExpandableWhenTheTerminalCannotAddRows() {
        TaskBoardPort.Snapshot snapshot = snapshot(7);

        TaskBoardProjection.View view = TaskBoardProjection.project(
            snapshot, 19, 80, false, false, 0L, Map.of());

        assertEquals(5, view.rows().size());
        assertFalse(view.expandable());
    }

    @Test
    void expandingPreservesTheCompactPriorityOrderBeforeAppendingRows() {
        List<TaskBoardPort.TaskItem> tasks = IntStream.rangeClosed(1, 6)
            .mapToObj(id -> item(String.valueOf(id), "task " + id,
                TaskBoardPort.Status.PENDING, List.of()))
            .collect(Collectors.toCollection(ArrayList::new));
        tasks.add(item("7", "active", TaskBoardPort.Status.IN_PROGRESS, List.of()));
        TaskBoardPort.Snapshot snapshot = new TaskBoardPort.Snapshot(
            "session", 1L, tasks, false);

        TaskBoardProjection.View compact = TaskBoardProjection.project(
            snapshot, 24, 80, false, false, 0L, Map.of());
        TaskBoardProjection.View expanded = TaskBoardProjection.project(
            snapshot, 24, 80, false, true, 0L, Map.of());

        assertEquals(compact.rows().stream().map(TaskBoardProjection.Row::id).toList(),
            expanded.rows().stream().limit(compact.rows().size())
                .map(TaskBoardProjection.Row::id).toList());
    }

    @Test
    void expandedCapacityCountsTwoLineActivityRows() {
        List<TaskBoardPort.TaskItem> tasks = IntStream.rangeClosed(1, 7)
            .mapToObj(id -> new TaskBoardPort.TaskItem(
                String.valueOf(id), "task " + id, "", "Working", "agent-" + id,
                TaskBoardPort.Status.IN_PROGRESS, List.of(), List.of()))
            .toList();
        Map<String, TaskBoardProjection.ActiveOwner> owners = IntStream.rangeClosed(1, 7)
            .boxed()
            .collect(Collectors.toMap(
                id -> "agent-" + id,
                id -> new TaskBoardProjection.ActiveOwner("blue", "Working on " + id)));
        TaskBoardPort.Snapshot snapshot = new TaskBoardPort.Snapshot(
            "session", 1L, tasks, false);

        TaskBoardProjection.View expanded = TaskBoardProjection.project(
            snapshot, 24, 80, false, true, 0L, Map.of(), owners);

        assertEquals(5, expanded.rows().size(),
            "five two-line rows already consume the ten safely available rows");
        assertFalse(expanded.expandable());
    }

    @Test
    void standaloneTitleAndBlockedPriorityMatchReleasedBoard() {
        TaskBoardPort.TaskItem completed = item("10", "done", TaskBoardPort.Status.COMPLETED, List.of());
        TaskBoardPort.TaskItem blocked = item("2", "blocked", TaskBoardPort.Status.PENDING, List.of("1"));
        TaskBoardPort.TaskItem blocker = item("1", "active", TaskBoardPort.Status.IN_PROGRESS, List.of());
        TaskBoardPort.Snapshot snapshot = new TaskBoardPort.Snapshot(
            "session", 1, List.of(completed, blocked, blocker), false);

        TaskBoardProjection.View view = TaskBoardProjection.project(
            snapshot, 24, 80, true, 0L, Map.of());

        assertTrue(view.standalone());
        assertEquals("3 tasks (1 done, 1 in progress, 1 open)", view.title());
        assertEquals(List.of("1", "2", "10"), view.rows().stream()
            .map(TaskBoardProjection.Row::id).toList());
        assertTrue(view.rows().get(1).blocked());
        assertEquals(List.of("1"), view.rows().get(1).openBlockers());
        assertEquals(5, view.preferredRows(), "top margin + title + three task rows");
    }

    @Test
    void taskAndBlockerOrderingUsesJavascriptParseIntPrefixes() {
        TaskBoardPort.TaskItem prefixed = item(
            "2stale", "prefixed", TaskBoardPort.Status.PENDING, List.of());
        TaskBoardPort.TaskItem numeric = item(
            "10", "numeric", TaskBoardPort.Status.PENDING, List.of("10", "2stale"));
        TaskBoardPort.Snapshot snapshot = new TaskBoardPort.Snapshot(
            "session", 1L, List.of(numeric, prefixed), false);

        TaskBoardProjection.View view = TaskBoardProjection.project(
            snapshot, 24, 80, false, 0L, Map.of());

        assertEquals(List.of("2stale", "10"), view.rows().stream()
            .map(TaskBoardProjection.Row::id).toList());
        assertEquals(List.of("2stale", "10"), view.rows().get(1).openBlockers());
    }

    @Test
    void taskAndBlockerOrderingUsesJavascriptNumberRangeBeyondLong() {
        String smaller = "100000000000000000000";
        String larger = "200000000000000000000";
        TaskBoardPort.TaskItem large = item(
            larger, "large", TaskBoardPort.Status.PENDING, List.of());
        TaskBoardPort.TaskItem small = item(
            smaller, "small", TaskBoardPort.Status.PENDING, List.of());
        TaskBoardPort.TaskItem blocked = item(
            "300000000000000000000", "blocked", TaskBoardPort.Status.PENDING,
            List.of(larger, smaller));
        TaskBoardPort.Snapshot snapshot = new TaskBoardPort.Snapshot(
            "session", 1L, List.of(large, small, blocked), false);

        TaskBoardProjection.View view = TaskBoardProjection.project(
            snapshot, 24, 80, false, 0L, Map.of());

        assertEquals(List.of(smaller, larger, "300000000000000000000"),
            view.rows().stream().map(TaskBoardProjection.Row::id).toList());
        assertEquals(List.of(smaller, larger), view.rows().get(2).openBlockers());
    }

    @Test
    void nonNumericTaskIdsUseLocaleCompareLikeReleased197() {
        TaskBoardPort.Snapshot snapshot = new TaskBoardPort.Snapshot(
            "session", 1L, List.of(
                item("B", "uppercase", TaskBoardPort.Status.PENDING, List.of()),
                item("a", "lowercase", TaskBoardPort.Status.PENDING, List.of())), false);

        TaskBoardProjection.View view = TaskBoardProjection.project(
            snapshot, 24, 80, false, 0L, Map.of());

        assertEquals(List.of("a", "B"), view.rows().stream()
            .map(TaskBoardProjection.Row::id).toList());
    }

    @Test
    void taskIdParsingUsesTheExactEcmascriptWhitespaceSet() {
        TaskBoardPort.Snapshot snapshot = new TaskBoardPort.Snapshot(
            "session", 1L, List.of(
                item("3", "numeric", TaskBoardPort.Status.PENDING, List.of()),
                item("\u001c20", "control-prefixed", TaskBoardPort.Status.PENDING, List.of())),
            false);

        TaskBoardProjection.View view = TaskBoardProjection.project(
            snapshot, 24, 80, false, 0L, Map.of());

        assertEquals(List.of("\u001c20", "3"), view.rows().stream()
            .map(TaskBoardProjection.Row::id).toList());
    }

    @Test
    void activeOwnerAddsColorAndUnblockedActivity() {
        TaskBoardPort.TaskItem active = new TaskBoardPort.TaskItem(
            "1", "research", "", "Researching", "alice",
            TaskBoardPort.Status.IN_PROGRESS, List.of(), List.of());
        TaskBoardPort.Snapshot snapshot = new TaskBoardPort.Snapshot(
            "session", 1, List.of(active), false);

        TaskBoardProjection.View view = TaskBoardProjection.project(
            snapshot, 24, 80, false, 0L, Map.of(),
            Map.of("alice", new TaskBoardProjection.ActiveOwner(
                "blue", "Reading build.gradle.kts")));

        TaskBoardProjection.Row row = view.rows().getFirst();
        assertEquals("alice", row.owner());
        assertEquals("blue", row.ownerColor());
        assertEquals("Reading build.gradle.kts", row.activity());
        assertEquals(2, row.height());
    }

    @Test
    void emptyActivityIsFalsyLikeReleased197() {
        TaskBoardPort.TaskItem active = new TaskBoardPort.TaskItem(
            "1", "research", "", "Researching", "alice",
            TaskBoardPort.Status.IN_PROGRESS, List.of(), List.of());
        TaskBoardPort.Snapshot snapshot = new TaskBoardPort.Snapshot(
            "session", 1, List.of(active), false);

        TaskBoardProjection.View view = TaskBoardProjection.project(
            snapshot, 24, 80, false, 0L, Map.of(),
            Map.of("alice", new TaskBoardProjection.ActiveOwner("blue", "")));

        TaskBoardProjection.Row row = view.rows().getFirst();
        assertNull(row.activity());
        assertEquals(1, row.height());
    }

    private static TaskBoardPort.Snapshot snapshot(int count) {
        List<TaskBoardPort.TaskItem> tasks = IntStream.rangeClosed(1, count)
            .mapToObj(id -> item(String.valueOf(id), "task " + id,
                TaskBoardPort.Status.PENDING, List.of()))
            .toList();
        return new TaskBoardPort.Snapshot("session", 1, tasks, false);
    }

    private static TaskBoardPort.TaskItem item(
            String id, String subject, TaskBoardPort.Status status, List<String> blockedBy) {
        return new TaskBoardPort.TaskItem(
            id, subject, "", null, null, status, List.of(), blockedBy);
    }
}
