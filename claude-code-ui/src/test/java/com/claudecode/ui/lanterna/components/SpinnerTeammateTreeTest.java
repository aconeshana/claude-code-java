package com.claudecode.ui.lanterna.components;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.graphics.BasicTextImage;
import com.googlecode.lanterna.gui2.TextGUIGraphicsBridge;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SpinnerTeammateTreeTest {

    @Test
    void expandedTreeRendersReleasedLeaderTeammateStatsAndHideRow() {
        SpinnerComponent spinner = new SpinnerComponent();
        AtomicBoolean selecting = new AtomicBoolean(true);
        AtomicInteger selected = new AtomicInteger(0);
        AtomicReference<String> viewed = new AtomicReference<>();
        spinner.setRunningTeammateMetricsSupplier(() -> List.of(
            teammate("task-1", "alice", false, "Researching", 1_250L, 2)));
        spinner.setViewedTeammateIdSupplier(viewed::get);
        spinner.setTeammateSelectionSuppliers(selecting::get, selected::get);
        spinner.setTeammateTreeExpanded(true);
        try {
            spinner.start("Leading");

            String selectedTeammate = render(spinner, 140, 8);
            assertTrue(selectedTeammate.contains("╒═ team-lead"), selectedTeammate);
            assertTrue(selectedTeammate.contains("❯╞═ @alice:"), selectedTeammate);
            assertTrue(selectedTeammate.contains("2 tool uses · 1.3k tokens"), selectedTeammate);
            assertTrue(selectedTeammate.contains("shift + ↑/↓ to select"), selectedTeammate);
            assertFalse(selectedTeammate.contains("Researching…"),
                "highlighted teammate activity belongs to the main spinner");

            selected.set(1);
            String hideSelected = render(spinner, 140, 8);
            assertTrue(hideSelected.contains("❯╘═ hide · enter to collapse"), hideSelected);
        } finally {
            spinner.stop();
        }
    }

    @Test
    void foregroundedTeammateOwnsMainSpinnerAndIdleRowsMatchReleasedText() {
        SpinnerComponent spinner = new SpinnerComponent();
        AtomicReference<List<SpinnerComponent.TeammateMetric>> metrics =
            new AtomicReference<>(List.of(
                teammate("task-1", "alice", false, "Researching", 320L, 1),
                teammate("task-2", "bob", true, "Testing", 640L, 3)));
        spinner.setRunningTeammateMetricsSupplier(metrics::get);
        spinner.setViewedTeammateIdSupplier(() -> "task-1");
        spinner.setTeammateTreeExpanded(true);
        try {
            spinner.start("Leading");

            String foregrounded = render(spinner, 120, 8);
            assertTrue(foregrounded.contains("Researching… (esc to interrupt alice)"),
                foregrounded);
            assertTrue(foregrounded.contains("Idle for 0s"), foregrounded);

            metrics.set(List.of(
                teammate("task-1", "alice", true, "Researching", 320L, 1),
                teammate("task-2", "bob", true, "Testing", 640L, 3)));
            String allIdle = render(spinner, 120, 8);
            assertTrue(allIdle.contains("Worked for"), allIdle);
        } finally {
            spinner.stop();
        }
    }

    @Test
    void nonHighlightedRowPrefersLiveActivityOverItsStableSpinnerVerb() {
        SpinnerComponent spinner = new SpinnerComponent();
        spinner.setRunningTeammateMetricsSupplier(() -> List.of(
            new SpinnerComponent.TeammateMetric("task-1", "alice", "blue", false,
                false, false, "Reading build.gradle.kts", "Researching", "Worked",
                System.currentTimeMillis() - 5_000L, 0L, 120L, 1)));
        spinner.setTeammateTreeExpanded(true);
        try {
            spinner.start("Leading");
            String rendered = render(spinner, 100, 8);
            assertTrue(rendered.contains("Reading build.gradle.kts…"), rendered);
            assertFalse(rendered.contains("Researching…"), rendered);
        } finally {
            spinner.stop();
        }
    }

    private static SpinnerComponent.TeammateMetric teammate(
            String id, String name, boolean idle, String verb, long tokens, int tools) {
        return new SpinnerComponent.TeammateMetric(id, name, "blue", idle,
            false, false, verb, verb, "Worked", System.currentTimeMillis() - 5_000L,
            0L, tokens, tools);
    }

    private static String render(SpinnerComponent spinner, int columns, int rows) {
        TerminalSize size = new TerminalSize(columns, rows);
        spinner.setSize(size);
        BasicTextImage image = new BasicTextImage(size);
        spinner.draw(TextGUIGraphicsBridge.wrap(null, image.newTextGraphics()));
        StringBuilder text = new StringBuilder();
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                text.append(image.getCharacterAt(column, row).getCharacterString());
            }
            text.append('\n');
        }
        return text.toString();
    }
}
