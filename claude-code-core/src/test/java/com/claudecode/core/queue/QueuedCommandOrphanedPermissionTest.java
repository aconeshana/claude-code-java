package com.claudecode.core.queue;

import com.claudecode.core.engine.OrphanedPermission;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueuedCommandOrphanedPermissionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void factoryProducesOrphanedModeWithDefaults() {
        OrphanedPermission payload = new OrphanedPermission("tu1", mapper.createObjectNode());
        QueuedCommand cmd = QueuedCommand.orphanedPermission(payload);

        assertEquals("orphaned-permission", cmd.mode());
        assertSame(QueuePriority.NEXT, cmd.priority());
        assertTrue(cmd.isMeta());
        assertNull(cmd.agentId());
        assertEquals(payload, cmd.orphanedPermission());
    }

    @Test
    void backwardCompatible11ArgConstructorLeavesPayloadNull() {
// matches the existing 11-arg call sites that must keep compiling.
        QueuedCommand cmd = new QueuedCommand(
            "text", null, "prompt", QueuePriority.NEXT,
            false, null, false, false, null, null, null);
        assertNull(cmd.orphanedPermission());
        assertEquals("prompt", cmd.mode());
    }
}
