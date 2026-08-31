package com.claudecode.ui.lanterna.features.tasks;

import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.runtime.tasks.TaskBoardPort;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.TextGUIGraphicsBridge;
import com.googlecode.lanterna.graphics.BasicTextImage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskListPanelTest {

    @AfterEach
    void clearEnvironmentOverrides() {
        SubprocessEnvironment.clearRuntimeOverrides();
    }

    @Test
    void releasedIconsUseFilledAndEmptySmallSquares() {
        TaskListPanel panel = panel(List.of(
            row("1", "active", TaskBoardPort.Status.IN_PROGRESS, false, null, null),
            row("2", "pending", TaskBoardPort.Status.PENDING, false, null, null)), 2);

        BasicTextImage image = render(panel, 40, 2);

        assertEquals("◼", image.getCharacterAt(0, 0).getCharacterString());
        assertEquals("◻", image.getCharacterAt(0, 1).getCharacterString());
    }

    @Test
    void linuxTermUsesReleasedFallbackTaskIcons() {
        SubprocessEnvironment.updateRuntime(Map.of("TERM", "linux"));
        TaskListPanel panel = panel(List.of(
            row("1", "done", TaskBoardPort.Status.COMPLETED, false, null, null),
            row("2", "active", TaskBoardPort.Status.IN_PROGRESS, false, null, null),
            row("3", "pending", TaskBoardPort.Status.PENDING, false, null, null)), 3);

        BasicTextImage image = render(panel, 40, 3);

        assertEquals("√", image.getCharacterAt(0, 0).getCharacterString());
        assertEquals("■", image.getCharacterAt(0, 1).getCharacterString());
        assertEquals("□", image.getCharacterAt(0, 2).getCharacterString());
    }

    @Test
    void blockerSuffixStartsAfterTheDisplayWidthOfCjkSubjects() {
        TaskBoardProjection.Row row = new TaskBoardProjection.Row(
            "1", "中文任务", TaskBoardPort.Status.PENDING, true, List.of("2"),
            null, null, null, false, true, false);
        TaskListPanel panel = panel(List.of(row), 1);

        BasicTextImage image = render(panel, 40, 1);

        assertEquals("›", image.getCharacterAt(11, 0).getCharacterString());
    }

    @Test
    void activeOwnerUsesItsAssignedAgentColor() {
        TaskListPanel panel = panel(List.of(
            row("1", "work", TaskBoardPort.Status.IN_PROGRESS, false, "alice", "blue")), 1);

        BasicTextImage image = render(panel, 40, 1);

        assertEquals(LanternaTheme.agentColor("blue"),
            image.getCharacterAt(8, 0).getForegroundColor());
    }

    @Test
    void standaloneTitleIsDimWithOnlyTheCountsBold() {
        TaskListPanel panel = new TaskListPanel();
        panel.refresh(new TaskBoardProjection.View(
            true, false, "3 tasks (1 done, 1 in progress, 1 open)", List.of(), "", 2));
        panel.setVisible(true);

        BasicTextImage image = render(panel, 60, 2);

        assertEquals(LanternaTheme.welcomeDim(),
            image.getCharacterAt(2, 1).getForegroundColor());
        assertTrue(image.getCharacterAt(2, 1).getModifiers().contains(SGR.BOLD));
        assertFalse(image.getCharacterAt(4, 1).getModifiers().contains(SGR.BOLD));
        assertTrue(image.getCharacterAt(11, 1).getModifiers().contains(SGR.BOLD));
        assertTrue(image.getCharacterAt(19, 1).getModifiers().contains(SGR.BOLD));
        assertTrue(image.getCharacterAt(34, 1).getModifiers().contains(SGR.BOLD));
    }

    @Test
    void ownerPunctuationStaysDimWhileOnlyTheHandleUsesTheAgentColor() {
        TaskListPanel panel = panel(List.of(
            row("1", "work", TaskBoardPort.Status.IN_PROGRESS, false, "alice", "blue")), 1);

        BasicTextImage image = render(panel, 80, 1);

        assertEquals(LanternaTheme.welcomeDim(),
            image.getCharacterAt(6, 0).getForegroundColor());
        assertEquals(LanternaTheme.welcomeDim(),
            image.getCharacterAt(7, 0).getForegroundColor());
        assertEquals(LanternaTheme.agentColor("blue"),
            image.getCharacterAt(8, 0).getForegroundColor());
        assertEquals(LanternaTheme.welcomeDim(),
            image.getCharacterAt(14, 0).getForegroundColor());
    }

    @Test
    void subjectUsesReleased197MetadataAllowanceInsteadOfFillingTheWholeRow() {
        TaskListPanel panel = panel(List.of(
            row("1", "x".repeat(40), TaskBoardPort.Status.PENDING, false, null, null)), 1);

        BasicTextImage image = render(panel, 40, 1);

        assertEquals("…", image.getCharacterAt(26, 0).getCharacterString());
        assertEquals(" ", image.getCharacterAt(27, 0).getCharacterString());
    }

    @Test
    void activityUsesReleased197WidthThenAppendsItsOwnEllipsis() {
        TaskBoardProjection.Row active = new TaskBoardProjection.Row(
            "1", "work", TaskBoardPort.Status.IN_PROGRESS, false, List.of(),
            null, null, "x".repeat(30), true, false, false);
        TaskListPanel panel = panel(List.of(active), 2);

        BasicTextImage image = render(panel, 40, 2);

        assertEquals("…", image.getCharacterAt(26, 1).getCharacterString());
        assertEquals("…", image.getCharacterAt(27, 1).getCharacterString());
        assertEquals(" ", image.getCharacterAt(28, 1).getCharacterString());
    }

    private static TaskListPanel panel(List<TaskBoardProjection.Row> rows, int preferredRows) {
        TaskListPanel panel = new TaskListPanel();
        panel.refresh(new TaskBoardProjection.View(
            false, false, "", rows, "", preferredRows));
        panel.setVisible(true);
        return panel;
    }

    private static TaskBoardProjection.Row row(
            String id,
            String subject,
            TaskBoardPort.Status status,
            boolean blocked,
            String owner,
            String ownerColor) {
        return new TaskBoardProjection.Row(
            id, subject, status, blocked, blocked ? List.of("2") : List.of(),
            owner, ownerColor, null, status == TaskBoardPort.Status.IN_PROGRESS,
            status == TaskBoardPort.Status.COMPLETED || blocked,
            status == TaskBoardPort.Status.COMPLETED);
    }

    private static BasicTextImage render(TaskListPanel panel, int columns, int rows) {
        TerminalSize size = new TerminalSize(columns, rows);
        panel.setSize(size);
        BasicTextImage image = new BasicTextImage(size);
        panel.getRenderer().drawComponent(
            TextGUIGraphicsBridge.wrap(null, image.newTextGraphics()), panel);
        return image;
    }
}
