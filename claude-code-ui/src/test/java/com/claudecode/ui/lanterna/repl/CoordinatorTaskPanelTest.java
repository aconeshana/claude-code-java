package com.claudecode.ui.lanterna.repl;

import com.claudecode.core.constants.Figures;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskStore;
import com.claudecode.tools.tasks.TaskType;
import com.claudecode.tools.tasks.TaskUsage;
import com.claudecode.tools.workflows.WorkflowRun;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.graphics.BasicTextImage;
import com.googlecode.lanterna.gui2.TextGUIGraphicsBridge;
import org.apache.commons.lang3.Strings;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CoordinatorTaskPanel#projectRows} — the pure row projection behind the subagent
 * coordinator panel.
 */
class CoordinatorTaskPanelTest {

    private static TaskState runningAgent(TaskStore store, String desc) {
        TaskState task = store.create(TaskType.LOCAL_AGENT, desc);
        store.updateStatus(task.id(), TaskStatus.RUNNING);
        return store.get(task.id()).orElseThrow();
    }

    private static CoordinatorTaskPanel.Snapshot snapshot(
            List<TaskState> agents, int selectedIndex, String viewingTaskId) {
        return new CoordinatorTaskPanel.Snapshot(
            agents, List.of(), selectedIndex, -1, viewingTaskId, Instant.now(), _ -> null, _ -> 0);
    }

    private static CoordinatorTaskPanel.Row mainRow(List<CoordinatorTaskPanel.Row> rows) {
        return rows.stream()
            .filter(r -> Strings.CS.contains(r.text(), CoordinatorTaskPanel.MAIN_LABEL))
            .findFirst().orElseThrow();
    }

    private static CoordinatorTaskPanel.Row rowContaining(List<CoordinatorTaskPanel.Row> rows, String needle) {
        return rows.stream()
            .filter(r -> Strings.CS.contains(r.text(), needle))
            .findFirst().orElseThrow();
    }

    @Test
    void emptyAgentsProjectsNoRows() {
        List<CoordinatorTaskPanel.Row> rows =
            CoordinatorTaskPanel.projectRows(CoordinatorTaskPanel.Snapshot.empty(), 80);
        assertTrue(rows.isEmpty(), "no subagents → panel collapses to zero rows");
    }

    @Test
    void mainLeaderRowIsAlwaysFirstWhenAgentsExist() {
        TaskStore store = TaskStore.inMemory();
        TaskState agent = runningAgent(store, "explore repo");

        List<CoordinatorTaskPanel.Row> rows =
            CoordinatorTaskPanel.projectRows(snapshot(List.of(agent), -1, null), 80);

        assertEquals(2, rows.size(), "main leader + one agent row");
        assertTrue(Strings.CS.contains(rows.getFirst().text(), CoordinatorTaskPanel.MAIN_LABEL),
            "the leader row is projected first");
    }

    @Test
    void viewedMainUsesFilledBulletUnselectedOthersUseCircle() {
        TaskStore store = TaskStore.inMemory();
        TaskState agent = runningAgent(store, "explore repo");

        // Viewing main (viewingTaskId == null), nothing selected.
        List<CoordinatorTaskPanel.Row> rows =
            CoordinatorTaskPanel.projectRows(snapshot(List.of(agent), -1, null), 80);

        CoordinatorTaskPanel.Row main = mainRow(rows);
        assertTrue(Strings.CS.contains(main.text(), Figures.BLACK_CIRCLE), "viewed main shows the filled bullet");
        assertTrue(main.viewed());

        CoordinatorTaskPanel.Row row = rowContaining(rows, "explore repo");
        assertTrue(Strings.CS.contains(row.text(), Figures.CIRCLE), "an un-viewed agent shows the hollow circle");
        assertFalse(row.viewed());
    }

    @Test
    void viewedAgentGetsFilledBulletAndMainGoesHollow() {
        TaskStore store = TaskStore.inMemory();
        TaskState agent = runningAgent(store, "explore repo");

        List<CoordinatorTaskPanel.Row> rows =
            CoordinatorTaskPanel.projectRows(snapshot(List.of(agent), -1, agent.id()), 80);

        assertTrue(Strings.CS.contains(mainRow(rows).text(), Figures.CIRCLE), "main is hollow while viewing an agent");
        CoordinatorTaskPanel.Row row = rowContaining(rows, "explore repo");
        assertTrue(Strings.CS.contains(row.text(), Figures.BLACK_CIRCLE), "the viewed agent shows the filled bullet");
        assertTrue(row.viewed());
    }

    @Test
    void selectedRowGetsPointerPrefix() {
        TaskStore store = TaskStore.inMemory();
        TaskState agent = runningAgent(store, "explore repo");

        // Select the agent row (index 1).
        List<CoordinatorTaskPanel.Row> rows =
            CoordinatorTaskPanel.projectRows(snapshot(List.of(agent), 1, null), 80);

        CoordinatorTaskPanel.Row row = rowContaining(rows, "explore repo");
        assertTrue(Strings.CS.startsWith(row.text(), Figures.POINTER), "the selected row is prefixed with the pointer");
        assertFalse(Strings.CS.startsWith(mainRow(rows).text(), Figures.POINTER),
            "the unselected main row has no pointer");
    }

    @Test
    void releasedCoordinatorKeepsOnlyFiveAgentsAndTrailsTheSelection() {
        TaskStore store = TaskStore.inMemory();
        List<TaskState> agents = IntStream.rangeClosed(1, 7)
            .mapToObj(index -> runningAgent(store, "agent-" + index))
            .toList();

        List<CoordinatorTaskPanel.Row> rows = CoordinatorTaskPanel.projectRows(
            snapshot(agents, 7, null), 80);

        assertEquals(7, rows.size(), "main + five agents + the released overflow row");
        assertTrue(Strings.CS.contains(mainRow(rows).text(), "↑ 2 more"));
        assertFalse(rows.stream().anyMatch(row -> Strings.CS.contains(row.text(), "agent-1")));
        assertFalse(rows.stream().anyMatch(row -> Strings.CS.contains(row.text(), "agent-2")));
        assertTrue(Strings.CS.startsWith(rowContaining(rows, "agent-7").text(), Figures.POINTER));
        assertEquals("", rows.get(6).text().trim(),
            "197 retains the overflow row but leaves it blank at the lower edge");
    }

    @Test
    void completedAgentBulletIsGreenRunningIsUncolored() {
        TaskStore store = TaskStore.inMemory();
        TaskState running = runningAgent(store, "still going");
        TaskState done = runningAgent(store, "all finished");
        store.updateStatus(done.id(), TaskStatus.COMPLETED);
        TaskState doneState = store.get(done.id()).orElseThrow();

        List<CoordinatorTaskPanel.Row> rows =
            CoordinatorTaskPanel.projectRows(snapshot(List.of(running, doneState), -1, null), 80);

        assertNull(rowContaining(rows, "still going").bulletColor(),
            "a running agent's bullet has no completion color");
        assertEquals("success", rowContaining(rows, "all finished").bulletColor(),
            "a completed agent's bullet is green (REc → success)");
    }

    @Test
    void failedAndKilledBulletsAreRed() {
        TaskStore store = TaskStore.inMemory();
        TaskState failed = runningAgent(store, "boom");
        store.updateStatus(failed.id(), TaskStatus.FAILED);
        TaskState killed = runningAgent(store, "stopped");
        store.updateStatus(killed.id(), TaskStatus.KILLED);

        List<CoordinatorTaskPanel.Row> rows = CoordinatorTaskPanel.projectRows(
            snapshot(List.of(store.get(failed.id()).orElseThrow(),
                             store.get(killed.id()).orElseThrow()), -1, null), 80);

        assertEquals("error", rowContaining(rows, "boom").bulletColor(),
            "a failed agent's bullet is red (REc → error)");
        assertEquals("error", rowContaining(rows, "stopped").bulletColor(),
            "a killed agent's bullet is red (REc → error)");
    }

    @Test
    void mainLeaderRowIsNeverColored() {
        TaskStore store = TaskStore.inMemory();
        TaskState done = runningAgent(store, "finished");
        store.updateStatus(done.id(), TaskStatus.COMPLETED);

        List<CoordinatorTaskPanel.Row> rows = CoordinatorTaskPanel.projectRows(
            snapshot(List.of(store.get(done.id()).orElseThrow()), -1, null), 80);

        assertNull(mainRow(rows).bulletColor(),
            "the main leader bullet is styled with dim/bold only, never colored");
    }

    @Test
    void longDescriptionIsTruncatedToTerminalWidth() {
        TaskStore store = TaskStore.inMemory();
        String longDesc = "x".repeat(200);
        TaskState agent = runningAgent(store, longDesc);

        int cols = 40;
        List<CoordinatorTaskPanel.Row> rows =
            CoordinatorTaskPanel.projectRows(snapshot(List.of(agent), -1, null), cols);

        CoordinatorTaskPanel.Row row = rows.get(1);
        assertTrue(row.text().length() <= cols + 2,
            "the agent row is truncated near the terminal width, not left overflowing");
        assertFalse(Strings.CS.contains(row.text(), longDesc), "the full 200-char description is not rendered verbatim");
    }

    @Test
    void progressSummaryOverridesDescription() {
        TaskStore store = TaskStore.inMemory();
        TaskState agent = runningAgent(store, "original description");
        store.updateProgressSummary(agent.id(), "searching for the bug");
        TaskState withProgress = store.get(agent.id()).orElseThrow();

        List<CoordinatorTaskPanel.Row> rows =
            CoordinatorTaskPanel.projectRows(snapshot(List.of(withProgress), -1, null), 80);

        assertTrue(Strings.CS.contains(rowContaining(rows, "searching for the bug").text(), "searching for the bug"),
            "the live progress summary replaces the static description");
    }

    @Test
    void runningAgentShowsReleasedLiveTokenStatus() {
        TaskStore store = TaskStore.inMemory();
        TaskState agent = runningAgent(store, "inspect usage");
        store.updateUsage(agent.id(), new TaskUsage(12_345, 7, 1_000));
        TaskState withUsage = store.get(agent.id()).orElseThrow();

        List<CoordinatorTaskPanel.Row> rows =
            CoordinatorTaskPanel.projectRows(snapshot(List.of(withUsage), -1, null), 100);

        assertTrue(Strings.CS.contains(rowContaining(rows, "inspect usage").text(),
            "↓ 12.3k tokens"));
    }

    @Test
    void runningAgentShowsItsOwnPendingMessageCount() {
        TaskStore store = TaskStore.inMemory();
        TaskState agent = runningAgent(store, "inspect queue");
        CoordinatorTaskPanel.Snapshot snapshot = new CoordinatorTaskPanel.Snapshot(
            List.of(agent), List.of(), -1, -1, agent.id(), Instant.now(), _ -> null, _ -> 2);

        List<CoordinatorTaskPanel.Row> rows = CoordinatorTaskPanel.projectRows(snapshot, 100);

        assertTrue(Strings.CS.contains(rowContaining(rows, "inspect queue").text(), "2 queued"));
    }

    @Test
    void resolvedNamePrefixesDescription() {
        TaskStore store = TaskStore.inMemory();
        TaskState agent = runningAgent(store, "do the thing");

        CoordinatorTaskPanel.Snapshot s = new CoordinatorTaskPanel.Snapshot(
            List.of(agent), List.of(), -1, -1, null, Instant.now(),
            id -> id.equals(agent.id()) ? "explorer" : null);

        List<CoordinatorTaskPanel.Row> rows = CoordinatorTaskPanel.projectRows(s, 80);
        assertTrue(Strings.CS.contains(rowContaining(rows, "do the thing").text(), "explorer: "),
            "a resolved agent name is rendered as a 'name: ' prefix");
    }

    @Test
    void workflowRowsMatchReleasedCoordinatorStatusProjection() {
        long now = Instant.parse("2026-08-25T12:03:41Z").toEpochMilli();
        WorkflowRun workflow = WorkflowRun.builder(
                "wf_research", "task_workflow", TaskStatus.RUNNING)
            .workflowName("claude-code-ecosystem-top10")
            .summary("调查并核验 GitHub 上 Claude Code 生态高星项目")
            .script("")
            .scriptPath(Path.of("/tmp/workflow.js"))
            .transcriptDir(Path.of("/tmp"))
            .startTime(now - 221_000)
            .agentCount(5)
            .totalTokens(153_600)
            .workflowProgress(List.of(
                workflowAgent(1, "done"),
                workflowAgent(2, "done"),
                workflowAgent(3, "done"),
                workflowAgent(4, "progress"),
                workflowAgent(5, "progress")))
            .build();
        CoordinatorTaskPanel.Snapshot snapshot = new CoordinatorTaskPanel.Snapshot(
            List.of(), List.of(workflow), -1, -1, null,
            Instant.ofEpochMilli(now), _ -> null);

        List<CoordinatorTaskPanel.Row> rows = CoordinatorTaskPanel.projectRows(snapshot, 120);

        assertEquals(1, rows.size(), "a workflow-only scene still renders the workflow footer row");
        String text = rows.getFirst().text();
        assertTrue(Strings.CS.contains(text, "claude-code-ecosystem-top10"), text);
        assertTrue(Strings.CS.contains(text, "3/5 agents done"), text);
        assertTrue(Strings.CS.contains(text, "3m 41s"), text);
        assertTrue(Strings.CS.contains(text, "153.6k tokens"), text);
    }

    @Test
    void selectedWorkflowRowUsesReleasedPointerAndUndims() {
        long now = Instant.parse("2026-08-25T12:03:41Z").toEpochMilli();
        WorkflowRun workflow = WorkflowRun.builder(
                "wf_selected", "task_selected", TaskStatus.RUNNING)
            .workflowName("selected-workflow")
            .summary("Selected")
            .script("")
            .scriptPath(Path.of("/tmp/workflow.js"))
            .transcriptDir(Path.of("/tmp"))
            .startTime(now)
            .build();
        CoordinatorTaskPanel.Snapshot snapshot = new CoordinatorTaskPanel.Snapshot(
            List.of(), List.of(workflow), -1, 0, null,
            Instant.ofEpochMilli(now), _ -> null);

        CoordinatorTaskPanel.Row row = CoordinatorTaskPanel.projectRows(snapshot, 100).getFirst();

        assertTrue(Strings.CS.startsWith(row.text(), Figures.POINTER));
        assertFalse(row.dim());
    }

    @Test
    void workflowRowsShareReleasedLabelAndStatusColumns() {
        long now = Instant.parse("2026-08-25T12:03:41Z").toEpochMilli();
        WorkflowRun shortName = WorkflowRun.builder(
                "wf_short", "task_short", TaskStatus.RUNNING)
            .workflowName("short")
            .summary("FIRST-DESC")
            .script("")
            .scriptPath(Path.of("/tmp/short.js"))
            .transcriptDir(Path.of("/tmp"))
            .startTime(now - 1_000)
            .agentCount(1)
            .build();
        WorkflowRun longName = WorkflowRun.builder(
                "wf_long", "task_long", TaskStatus.RUNNING)
            .workflowName("a-much-longer-workflow-name")
            .summary("SECOND-DESC")
            .script("")
            .scriptPath(Path.of("/tmp/long.js"))
            .transcriptDir(Path.of("/tmp"))
            .startTime(now - 221_000)
            .agentCount(5)
            .totalTokens(153_600)
            .workflowProgress(List.of(
                workflowAgent(1, "done"),
                workflowAgent(2, "failed")))
            .build();
        CoordinatorTaskPanel.Snapshot snapshot = new CoordinatorTaskPanel.Snapshot(
            List.of(), List.of(shortName, longName), -1, -1, null,
            Instant.ofEpochMilli(now), _ -> null);

        List<CoordinatorTaskPanel.Row> rows = CoordinatorTaskPanel.projectRows(snapshot, 120);

        assertEquals(rows.getFirst().text().indexOf("FIRST-DESC"),
            rows.getLast().text().indexOf("SECOND-DESC"),
            "197 computes one capped labelWidth for every visible workflow row");
        assertEquals(120, FormatUtils.displayWidth(rows.getFirst().text()));
        assertEquals(120, FormatUtils.displayWidth(rows.getLast().text()));
    }

    @Test
    void runningWorkflowShowsReleasedAgentErrorCountAndErrorBullet() {
        long now = Instant.parse("2026-08-25T12:03:41Z").toEpochMilli();
        WorkflowRun workflow = WorkflowRun.builder(
                "wf_partial_failure", "task_partial_failure", TaskStatus.RUNNING)
            .workflowName("partially-failed")
            .summary("Still running")
            .script("")
            .scriptPath(Path.of("/tmp/partial.js"))
            .transcriptDir(Path.of("/tmp"))
            .startTime(now - 10_000)
            .workflowProgress(List.of(
                workflowAgent(1, "done"),
                workflowAgent(2, "error"),
                workflowAgent(3, "progress")))
            .build();
        CoordinatorTaskPanel.Snapshot snapshot = new CoordinatorTaskPanel.Snapshot(
            List.of(), List.of(workflow), -1, -1, null,
            Instant.ofEpochMilli(now), _ -> null);

        CoordinatorTaskPanel.Row row = CoordinatorTaskPanel.projectRows(snapshot, 100).getFirst();

        assertTrue(Strings.CS.contains(row.text(), "1/3 agents done · 1 failed"), row.text());
        assertEquals("error", row.bulletColor());
    }

    @Test
    void workflowFooterUsesReleasedDescriptionPrefixBeforeColon() {
        long now = Instant.parse("2026-08-25T12:03:41Z").toEpochMilli();
        WorkflowRun workflow = WorkflowRun.builder(
                "wf_colon", "task_colon", TaskStatus.RUNNING)
            .workflowName("research")
            .summary("Search repositories: then verify every README")
            .script("")
            .scriptPath(Path.of("/tmp/colon.js"))
            .transcriptDir(Path.of("/tmp"))
            .startTime(now)
            .build();
        CoordinatorTaskPanel.Snapshot snapshot = new CoordinatorTaskPanel.Snapshot(
            List.of(), List.of(workflow), -1, -1, null,
            Instant.ofEpochMilli(now), _ -> null);

        String row = CoordinatorTaskPanel.projectRows(snapshot, 100).getFirst().text();

        assertTrue(Strings.CS.contains(row, "Search repositories"), row);
        assertFalse(Strings.CS.contains(row, "then verify"), row);
    }

    @Test
    void workflowPanelAdvertisesEnoughWidthForTheReleasedRichRow() {
        long now = Instant.parse("2026-08-25T12:03:41Z").toEpochMilli();
        WorkflowRun workflow = WorkflowRun.builder(
                "wf_width", "task_width", TaskStatus.RUNNING)
            .workflowName("claude-code-ecosystem-top10")
            .summary("调查并核验 GitHub 上 Claude Code 生态高星项目")
            .script("")
            .scriptPath(Path.of("/tmp/workflow.js"))
            .transcriptDir(Path.of("/tmp"))
            .startTime(now - 221_000)
            .agentCount(5)
            .build();
        CoordinatorTaskPanel panel = new CoordinatorTaskPanel();
        panel.refresh(List.of(), List.of(workflow), -1, -1, null,
            Instant.ofEpochMilli(now), _ -> null);

        assertTrue(panel.getPreferredSize().getColumns() >= 40,
            "a one-column preferred width can collapse ETf to only its circle glyph");
    }

    @Test
    void workflowFramebufferUsesTheFullAssignedWidthAndPaintsTheRichEntry() {
        long now = Instant.parse("2026-08-25T12:03:41Z").toEpochMilli();
        WorkflowRun workflow = WorkflowRun.builder(
                "wf_frame", "task_frame", TaskStatus.RUNNING)
            .workflowName("claude-code-ecosystem-top10")
            .summary("调查并核验 GitHub 上 Claude Code 生态高星项目")
            .script("")
            .scriptPath(Path.of("/tmp/workflow.js"))
            .transcriptDir(Path.of("/tmp"))
            .startTime(now - 221_000)
            .agentCount(5)
            .totalTokens(153_600)
            .workflowProgress(List.of(
                workflowAgent(1, "done"),
                workflowAgent(2, "done"),
                workflowAgent(3, "done")))
            .build();
        CoordinatorTaskPanel panel = new CoordinatorTaskPanel();
        panel.refresh(List.of(), List.of(workflow), -1, 0, null,
            Instant.ofEpochMilli(now), _ -> null);
        TerminalSize size = new TerminalSize(120, 2);
        panel.setSize(size);
        BasicTextImage image = new BasicTextImage(size);

        panel.getRenderer().drawComponent(
            TextGUIGraphicsBridge.wrap(null, image.newTextGraphics()), panel);

        String row = rowText(image, 1);
        assertTrue(Strings.CS.startsWith(row, Figures.POINTER + " " + Figures.CIRCLE), row);
        assertTrue(Strings.CS.contains(row, "claude-code-ecosystem-top10"), row);
        // BasicTextImage exposes the trailing cell of each full-width CJK glyph
        // as a space, so assert the ASCII portion of the description here.
        assertTrue(Strings.CS.contains(row, "GitHub"), row);
        assertTrue(Strings.CS.contains(row, "3/5 agents done"), row);
        assertTrue(Strings.CS.contains(row, "153.6k tokens"), row);
        assertEquals(120, row.length(), "the released ETf row consumes the assigned prompt width");
    }

    private static ObjectNode workflowAgent(
            int index, String state) {
        var agent = JsonUtils.getMapper().createObjectNode();
        agent.put("type", "workflow_agent");
        agent.put("index", index);
        agent.put("state", state);
        return agent;
    }

    private static String rowText(BasicTextImage image, int row) {
        StringBuilder text = new StringBuilder(image.getSize().getColumns());
        for (int column = 0; column < image.getSize().getColumns(); column++) {
            text.append(image.getCharacterAt(column, row).getCharacter());
        }
        return text.toString();
    }
}
