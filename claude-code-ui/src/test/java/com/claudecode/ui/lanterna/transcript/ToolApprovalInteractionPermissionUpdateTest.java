package com.claudecode.ui.lanterna.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.claudecode.core.engine.PermissionUpdate;
import com.claudecode.core.engine.SandboxConfig;
import com.claudecode.core.model.PermissionModeKind;
import com.claudecode.core.message.Usage;
import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.PermissionMode;
import com.claudecode.permissions.RuleSource;
import com.claudecode.permissions.ToolPermissionContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.claudecode.ui.lanterna.features.settings.UiSettings;
import com.claudecode.ui.lanterna.input.InputPanel;




class ToolApprovalInteractionPermissionUpdateTest {

    @Test
    void contextUsedPercentMatchesReleasedInputTokenAccounting() {
        assertEquals(50, ToolApprovalInteraction.contextUsedPercent(
            new Usage(40_000, 9_000, 20_000, 40_000), "claude-sonnet-4-5"));
        assertEquals(100, ToolApprovalInteraction.contextUsedPercent(
            new Usage(300_000, 0, 0, 0), "claude-sonnet-4-5"));
    }

    @AfterEach
    void resetBackend() {
        UiSettings.configure(null);
    }

    @Test
    void applySuggestionsUpdatesLiveGateAndOnlyPersistsEditableDestinations() {
        List<PermissionUpdate> persisted = new ArrayList<>();
        UiSettings.configure(new RecordingBackend(persisted));
        PermissionGate gate = new PermissionGate(
            ToolPermissionContext.of(Path.of("/Users/test/project")));
        ToolApprovalInteraction interaction = new ToolApprovalInteraction(
            null, null, null, null, gate, null, () -> false, _ -> {});
        PermissionUpdate.AddRules localRule = new PermissionUpdate.AddRules(
            List.of(new PermissionUpdate.RuleValue("Bash", "git:*")),
            PermissionUpdate.Behavior.ALLOW,
            PermissionUpdate.Destination.LOCAL_SETTINGS);
        PermissionUpdate.AddDirectories sessionDirectory =
            new PermissionUpdate.AddDirectories(
                List.of("/private/tmp"), PermissionUpdate.Destination.SESSION);
        PermissionUpdate.SetMode sessionMode = new PermissionUpdate.SetMode(
            PermissionModeKind.ACCEPT_EDITS, PermissionUpdate.Destination.SESSION);

        interaction.applySuggestedUpdates(List.of(
            sessionDirectory, sessionMode, localRule));

        assertEquals(PermissionMode.ACCEPT_EDITS, gate.currentMode());
        assertEquals(RuleSource.SESSION,
            gate.currentContext().additionalDirs().get(Path.of("/private/tmp")));
        assertEquals(RuleSource.LOCAL_SETTINGS,
            gate.currentContext().rules().getFirst().source());
        assertEquals(List.of(sessionDirectory, sessionMode, localRule), persisted,
            "backend receives all updates and decides which destinations persist");
    }

    @Test
    void exitPlanBypassApprovalUpdatesThePromptModeBeforeTheTurnCompletes() {
        PermissionGate gate = new PermissionGate(
            ToolPermissionContext.of(Path.of("/Users/test/project")));
        gate.setMode(PermissionMode.PLAN);
        InputPanel input = new InputPanel("plan");
        ToolApprovalInteraction interaction = new ToolApprovalInteraction(
            null, input, null, null, gate, null, () -> true, _ -> {});

        interaction.applySuggestedUpdates(List.of(new PermissionUpdate.SetMode(
            PermissionModeKind.BYPASS_PERMISSIONS, PermissionUpdate.Destination.SESSION)));

        assertEquals(PermissionMode.BYPASS_PERMISSIONS, gate.currentMode());
        assertEquals("bypassPermissions", input.getPermissionMode(),
            "197 updates the live permission context/footer during approval, not at turn end");
    }

    private record RecordingBackend(List<PermissionUpdate> updates)
            implements UiSettings.Backend {
        @Override public boolean globalBoolean(String key, boolean defaultValue) { return defaultValue; }
        @Override public String globalString(String key, String defaultValue) { return defaultValue; }
        @Override public int globalInt(String key, int defaultValue) { return defaultValue; }
        @Override public void setGlobal(String key, Object value) { }
        @Override public boolean spinnerTipsEnabled() { return true; }
        @Override public boolean prefersReducedMotion() { return false; }
        @Override public Boolean policyBoolean(String key) { return null; }
        @Override public SandboxConfig sandboxConfig() { return SandboxConfig.disabled(); }
        @Override public void addPermissionRule(String cwd, PermissionBehavior behavior,
                                                String ruleString, RuleSource tier) { }
        @Override public void removePermissionRule(String cwd, PermissionBehavior behavior,
                                                   String ruleString, RuleSource tier) { }
        @Override public void persistPermissionUpdate(String cwd, PermissionUpdate update) {
            updates.add(update);
        }
    }
}
