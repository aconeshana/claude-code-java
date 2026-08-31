package com.claudecode.ui.lanterna.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ViewedTeammateHolderTest {

    private final ViewedTeammateHolder holder = ViewedTeammateHolder.instance();

    @AfterEach
    void reset() {
        holder.resetForTest();
    }

    @Test
    void selectingRowsPreservesForegroundedTeammateLikeReleasedAppState() {
        holder.enterViewing("task-1", 0);

        holder.enterSelecting(1);

        assertTrue(holder.isSelecting());
        assertTrue(holder.hasForegroundedTeammate());
        assertEquals("task-1", holder.viewingTaskId());
        assertEquals(1, holder.selectedIndex());

        holder.leaveSelecting();

        assertFalse(holder.isActive());
        assertTrue(holder.hasForegroundedTeammate());
        assertEquals("task-1", holder.viewingTaskId());
        assertEquals(-1, holder.selectedIndex());
    }
}
