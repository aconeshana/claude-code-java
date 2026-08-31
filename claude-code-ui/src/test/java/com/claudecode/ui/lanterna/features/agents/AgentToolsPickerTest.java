package com.claudecode.ui.lanterna.features.agents;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.graphics.BasicTextImage;
import com.googlecode.lanterna.gui2.TextGUIGraphicsBridge;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgentToolsPicker}.
 */
class AgentToolsPickerTest {

    private static final List<String> TOOLS = List.of("Read", "Edit", "Bash", "mcp__github__list_prs");

    private static final KeyStroke DOWN = new KeyStroke(KeyType.ARROW_DOWN);
    private static final KeyStroke ENTER = new KeyStroke(KeyType.ENTER);
    private static final KeyStroke SPACE = new KeyStroke(' ', false, false);
    private static final KeyStroke ESC = new KeyStroke(KeyType.ESCAPE);

    private static void send(AgentToolsPicker p, KeyStroke k) {
        p.handleKey(k, new AtomicBoolean(true));
    }

    @Test
    void activate_nullTools_selectsEverythingAvailable() {
        AgentToolsPicker p = new AgentToolsPicker();
        p.activate(null, TOOLS, _ -> {}, () -> {});
        assertEquals(Set.copyOf(TOOLS), p.selectedTools());
    }

    @Test
    void activate_wildcardTools_selectsEverythingAvailable() {
        AgentToolsPicker p = new AgentToolsPicker();
        p.activate(List.of("*"), TOOLS, _ -> {}, () -> {});
        assertEquals(Set.copyOf(TOOLS), p.selectedTools());
    }

    @Test
    void activate_explicitTools_seedsExactSelection() {
        AgentToolsPicker p = new AgentToolsPicker();
        p.activate(List.of("Read", "Bash"), TOOLS, _ -> {}, () -> {});
        assertEquals(Set.of("Read", "Bash"), p.selectedTools());
    }

    @Test
    void allToolsToggle_deselectsEverythingWhenAllSelected() {
        AgentToolsPicker p = new AgentToolsPicker();
        p.activate(null, TOOLS, _ -> {}, () -> {}); // starts fully selected
        send(p, DOWN); // Continue -> All tools row
        send(p, ENTER); // toggle off
        assertTrue(p.selectedTools().isEmpty());
    }

    @Test
    void allToolsToggle_selectsEverythingWhenNoneSelected() {
        AgentToolsPicker p = new AgentToolsPicker();
        p.activate(List.of(), TOOLS, _ -> {}, () -> {});
        send(p, DOWN); // -> All tools row
        send(p, ENTER);
        assertEquals(Set.copyOf(TOOLS), p.selectedTools());
    }

    @Test
    void bucketToggle_selectsAllToolsInThatBucket() {
        AgentToolsPicker p = new AgentToolsPicker();
        p.activate(List.of(), TOOLS, _ -> {}, () -> {});
        // Continue(0), All tools(1), Read-only bucket(2, contains "Read"), Edit bucket(3, "Edit")...
        send(p, DOWN); // All tools
        send(p, DOWN); // Read-only bucket
        send(p, ENTER);
        assertEquals(Set.of("Read"), p.selectedTools());
    }

    @Test
    void spaceKeyDoesNotToggleTheManualReleasedPicker() {
        AgentToolsPicker p = new AgentToolsPicker();
        p.activate(List.of(), TOOLS, _ -> {}, () -> {});
        send(p, DOWN);
        send(p, DOWN);
        send(p, SPACE);
        assertTrue(p.selectedTools().isEmpty());
    }

    @Test
    void upAtContinueClampsInsteadOfWrapping() {
        AgentToolsPicker p = new AgentToolsPicker();
        boolean[] confirmed = {false};
        p.activate(List.of(), TOOLS, _ -> confirmed[0] = true, () -> {});

        send(p, new KeyStroke(KeyType.ARROW_UP));
        send(p, ENTER);

        assertTrue(confirmed[0], "Enter must still activate Continue after Up at the first row");
    }

    @Test
    void downAtAdvancedToggleClampsInsteadOfWrapping() {
        AgentToolsPicker p = new AgentToolsPicker();
        p.activate(List.of(), TOOLS, _ -> {}, () -> {});
        for (int i = 0; i < 6; i++) send(p, DOWN);

        send(p, DOWN);
        send(p, ENTER);

        assertTrue(p.isAdvancedExpanded(),
            "Down at the final row must remain on the advanced toggle");
    }

    @Test
    void advancedToggle_expandsIndividualToolRows() {
        AgentToolsPicker p = new AgentToolsPicker();
        p.activate(List.of(), TOOLS, _ -> {}, () -> {});
        int collapsedCount = p.itemCount();
        assertFalse(p.isAdvancedExpanded());

        // Navigate to the "Show advanced options" row: Continue(0), All tools(1),
        // then one row per non-empty bucket (Read-only(2), Edit(3), Execution(4), MCP(5)),
        // then advanced toggle(6).
        for (int i = 0; i < 6; i++) send(p, DOWN);
        send(p, ENTER);

        assertTrue(p.isAdvancedExpanded());
        assertTrue(p.itemCount() > collapsedCount);
        assertEquals(14, p.itemCount(),
            "released advanced rows add MCP header + per-server row + individual header");
        assertEquals(List.of(
            "MCP servers:",
            "☐ github (1 tool)",
            "Individual tools:",
            "☐ Read",
            "☐ Edit",
            "☐ Bash",
            "☐ list_prs (github)"), p.advancedLabels());
    }

    @Test
    void continueRow_confirmsAllSelected_asNull() {
        AgentToolsPicker p = new AgentToolsPicker();
        List<String>[] result = new List[] {List.of("sentinel")};
        p.activate(null, TOOLS, ts -> result[0] = ts, () -> {});
        send(p, ENTER); // Continue row, everything still selected from activate(null,...)
        assertNull(result[0], "fully-selected confirm must report null (the \"all tools\" analog)");
    }

    @Test
    void continueRow_withNoAvailableToolsStillConfirmsReleasedAllToolsNull() {
        AgentToolsPicker p = new AgentToolsPicker();
        List<String>[] result = new List[] {List.of("sentinel")};
        p.activate(null, List.of(), ts -> result[0] = ts, () -> {});

        send(p, ENTER);

        assertNull(result[0],
            "released areAllToolsSelected is true when both available and selected are empty");
    }

    @Test
    void continueRow_confirmsPartialSelection_asExplicitList() {
        AgentToolsPicker p = new AgentToolsPicker();
        List<String>[] result = new List[] {null};
        p.activate(List.of("Read"), TOOLS, ts -> result[0] = ts, () -> {});
        send(p, ENTER); // Continue row
        assertEquals(List.of("Read"), result[0]);
    }

    @Test
    void esc_cancelsWithoutConfirming() {
        AgentToolsPicker p = new AgentToolsPicker();
        boolean[] confirmed = {false};
        boolean[] cancelled = {false};
        p.activate(null, TOOLS, _ -> confirmed[0] = true, () -> cancelled[0] = true);
        send(p, ESC);
        assertFalse(confirmed[0]);
        assertTrue(cancelled[0]);
    }

    @Test
    void collapsedRenderMatchesReleasedContinueDividersAndSummary() {
        AgentToolsPicker p = new AgentToolsPicker();
        p.activate(List.of(), TOOLS, _ -> {}, () -> {});

        String rendered = render(p);
        assertTrue(rendered.contains("Create new agent"), rendered);
        assertTrue(rendered.contains("Select tools"), rendered);
        assertTrue(rendered.contains("[ Continue ]"), rendered);
        assertTrue(rendered.contains("─".repeat(40)), rendered);
        assertTrue(rendered.contains("☐ Read-only tools"), rendered);
        assertTrue(rendered.contains("[ Show advanced options ]"), rendered);
        assertTrue(rendered.contains("0 of 4 tools selected"), rendered);
        assertFalse(rendered.contains("(0/1)"), rendered);
    }

    private static String render(AgentToolsPicker picker) {
        TerminalSize size = new TerminalSize(90, 28);
        picker.setSize(size);
        BasicTextImage image = new BasicTextImage(size);
        picker.draw(TextGUIGraphicsBridge.wrap(null, image.newTextGraphics()));
        StringBuilder text = new StringBuilder();
        for (int row = 0; row < size.getRows(); row++) {
            for (int column = 0; column < size.getColumns(); column++) {
                text.append(image.getCharacterAt(column, row).getCharacterString());
            }
            text.append('\n');
        }
        return text.toString();
    }
}
