package com.claudecode.ui.lanterna.repl;

import com.claudecode.ui.lanterna.components.SpinnerComponent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LanternaReplScreenTaskBoardTest {

    @Test
    void released197ColorsOwnerNamesButNotAgentIds() {
        SpinnerComponent.TeammateMetric teammate = new SpinnerComponent.TeammateMetric(
            "agent-1", "reviewer", "blue", false, false, false,
            "Reading build.gradle.kts", "working", "worked",
            1L, 0L, 10L, 1);

        var owners = LanternaReplScreen.activeTaskOwners(List.of(teammate));

        assertNull(owners.get("agent-1").colorName());
        assertEquals("Reading build.gradle.kts", owners.get("agent-1").activity());
        assertEquals("blue", owners.get("reviewer").colorName());
        assertEquals("Reading build.gradle.kts", owners.get("reviewer").activity());
    }
}
