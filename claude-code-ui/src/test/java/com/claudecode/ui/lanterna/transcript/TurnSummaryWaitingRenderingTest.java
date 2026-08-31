package com.claudecode.ui.lanterna.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.commons.lang3.Strings;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TurnSummaryWaitingRenderingTest {

    @Test
    void oneOutstandingAgentReplacesTheDurationWithTheWaitingLine() {
        StubPanel panel = new StubPanel();

        new LanternaMessageDispatcher().renderTurnSummary(panel, 1_500L,
            1, null, null);

        assertEquals("✻ Waiting for 1 background agent to finish", panel.lines.getLast());
    }

    @Test
    void agentsAndWorkflowsArePluralizedAndJoinedWithAnd() {
        StubPanel panel = new StubPanel();

        new LanternaMessageDispatcher().renderTurnSummary(panel, 1_500L,
            2, 3, null);

        assertEquals("✻ Waiting for 2 background agents and 3 dynamic workflows to finish",
            panel.lines.getLast());
    }

    @Test
    void aLoneWorkflowDropsTheAgentClauseEntirely() {
        StubPanel panel = new StubPanel();

        new LanternaMessageDispatcher().renderTurnSummary(panel, 1_500L,
            null, 1, null);

        assertEquals("✻ Waiting for 1 dynamic workflow to finish", panel.lines.getLast());
    }

    @Test
    void theCountsThemselvesAreBold() {
        StubPanel panel = new StubPanel();

        new LanternaMessageDispatcher().renderTurnSummary(panel, 1_500L,
            4, null, null);

        List<MessagePanel.Segment> line = panel.mixed.getLast();
        MessagePanel.Segment count = line.stream()
            .filter(s -> Strings.CS.equals("4", s.text())).findFirst().orElseThrow();
        assertEquals(Set.of(SGR.BOLD), count.modifiers());
    }

    @Test
    void aWaitingTurnSuppressesTheStillRunningTail() {
        StubPanel panel = new StubPanel();

        new LanternaMessageDispatcher().renderTurnSummary(panel, 1_500L,
            1, null, "2 background tasks");

        assertFalse(Strings.CS.contains(panel.lines.getLast(), "still running"),
            "released renders the pill tail only when the waiting branch is off");
    }

    @Test
    void anOrdinaryTurnKeepsItsDurationAndGainsTheStillRunningTail() {
        StubPanel panel = new StubPanel();

        new LanternaMessageDispatcher().renderTurnSummary(panel, 1_500L,
            null, null, "2 background tasks");

        String line = panel.lines.getLast();
        assertTrue(Strings.CS.contains(line, " for 1s"), line);
        assertTrue(Strings.CS.endsWith(line, " · 2 background tasks still running"), line);
    }


    @Test
    void anExplicitZeroDoesNotTriggerTheWaitingBranch() {
        StubPanel panel = new StubPanel();

        new LanternaMessageDispatcher().renderTurnSummary(panel, 1_500L,
            0, 0, null);

        assertFalse(Strings.CS.contains(panel.lines.getLast(), "Waiting for"),
            panel.lines.getLast());
    }

    @Test
    void ordinaryTurnUsesTheReleasedLongDurationShapeWithoutEffortMetadata() {
        StubPanel panel = new StubPanel();

        new LanternaMessageDispatcher().renderTurnSummary(panel, 3_600_000L,
            null, null, null);

        String line = panel.lines.getLast();
        assertTrue(Strings.CS.endsWith(line, " for 1h 0m 0s"), line);
        assertFalse(Strings.CS.contains(line, "effort"), line);
    }

    @Test
    void budgetAndBriefHiddenMetadataMatchTheReleasedSuffixes() {
        StubPanel panel = new StubPanel();

        new LanternaMessageDispatcher().renderTurnSummary(panel, 1_500L,
            null, null, null, 12_500L, 20_000L, 2, 3);

        String line = panel.lines.getLast();
        assertTrue(Strings.CS.contains(line, " · 12.5k / 20.0k (63%) · 2 nudges"), line);
        assertTrue(Strings.CS.endsWith(line,
            " · 3 messages hidden (/focus to show)"), line);
    }

    @Test
    void budgetStillRendersWhenTurnDurationSettingIsDisabled() {
        StubPanel panel = new StubPanel();
        new LanternaMessageDispatcher().renderTurnSummaryWithVisibility(panel, 1_500L,
            null, null, null, 25_000L, 20_000L, 1, null, false);

        assertEquals("✻ 25.0k used (20.0k min ✔) · 1 nudge", panel.lines.getLast());
    }

    private static final class StubPanel extends MessagePanel {
        private final List<String> lines = new ArrayList<>();
        private final List<List<MessagePanel.Segment>> mixed = new ArrayList<>();

        @Override public void appendMixed(List<MessagePanel.Segment> segments) {
            mixed.add(List.copyOf(segments));
            lines.add(segments.stream().map(MessagePanel.Segment::text)
                .reduce("", String::concat));
        }

        @Override public void appendMixed(List<MessagePanel.Segment> segments, int wrapWidthInset) {
            appendMixed(segments);
        }

        @Override public void appendLine(String text, TextColor color) {
            lines.add(text);
        }

        @Override public void appendLine(String text, TextColor color, int wrapWidthInset) {
            appendLine(text, color);
        }
    }
}
