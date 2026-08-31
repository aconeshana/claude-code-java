package com.claudecode.commands.workflows;

import com.claudecode.commands.CommandRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowCommandSyncTest {

    @Test
    void replacesPreviousGenerationAndFiltersHiddenWorkflows() {
        CommandRegistry registry = new CommandRegistry();
        WorkflowCommandSync sync = new WorkflowCommandSync();

        assertEquals(1, sync.sync(registry, List.of(definition("one", false), definition("hidden", true))));
        assertTrue(registry.find("one").isPresent());
        assertFalse(registry.find("hidden").isPresent());

        assertEquals(1, sync.sync(registry, List.of(definition("two", false))));
        assertFalse(registry.find("one").isPresent());
        assertTrue(registry.find("two").isPresent());
    }

    private static WorkflowCommandDefinition definition(String name, boolean hidden) {
        String script = "export const meta = { name: \"" + name + "\", description: \"desc\" }; return 1;";
        return new WorkflowCommandDefinition(name, name, "desc", null, List.of(), script,
            WorkflowCommandDefinition.Source.USER, null, hidden);
    }
}
