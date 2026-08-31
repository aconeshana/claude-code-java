package com.claudecode.permissions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.claudecode.core.engine.PermissionUpdate;
import com.claudecode.core.model.PermissionModeKind;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;


class PermissionUpdateApplicationTest {

    @Test
    void appliesDirectoryAndModeSuggestionsInOrder() {
        PermissionGate gate = new PermissionGate(
            ToolPermissionContext.of(Path.of("/Users/test/project")));

        gate.applyUpdates(List.of(
            new PermissionUpdate.AddDirectories(
                List.of("/private/tmp"), PermissionUpdate.Destination.SESSION),
            new PermissionUpdate.SetMode(
                PermissionModeKind.ACCEPT_EDITS, PermissionUpdate.Destination.SESSION)));

        assertEquals(PermissionMode.ACCEPT_EDITS, gate.currentMode());
        assertEquals(RuleSource.SESSION,
            gate.currentContext().additionalDirs().get(Path.of("/private/tmp")));
    }

    @Test
    void appliesSessionReadRuleWithExactReleasedPattern() {
        PermissionGate gate = new PermissionGate(
            ToolPermissionContext.of(Path.of("/Users/test/project")));

        gate.applyUpdates(List.of(new PermissionUpdate.AddRules(
            List.of(new PermissionUpdate.RuleValue("Read", "//private/tmp/**")),
            PermissionUpdate.Behavior.ALLOW,
            PermissionUpdate.Destination.SESSION)));

        PermissionRule rule = gate.currentContext().rules().getFirst();
        assertEquals("Read", rule.toolName());
        assertEquals("//private/tmp/**", rule.pattern().orElseThrow());
        assertEquals(RuleSource.SESSION, rule.source());
    }
}
