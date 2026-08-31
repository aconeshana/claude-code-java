package com.claudecode.ui.lanterna.input;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.Test;

/** Guards CLI-selected permission mode propagation into the first TUI submit. */
class InputPanelInitialPermissionModeTest {

    @Test
    void constructorPreservesCliSelectedPlanMode() {
        InputPanel panel = new InputPanel("plan");

        assertEquals("plan", panel.getPermissionMode());
    }

    @Test
    void activePermissionModeFooterUsesReleasedLowercaseTitle() {
        assertEquals("  ⏵⏵ accept edits on (shift+tab to cycle) · ← for agents",
            panelWithAgentsHint("acceptEdits").leaderHintTextForTest());
        assertEquals("  ⏵⏵ don't ask on (shift+tab to cycle) · ← for agents",
            panelWithAgentsHint("dontAsk").leaderHintTextForTest());
        assertEquals("  ⏵⏵ bypass permissions on (shift+tab to cycle) · ← for agents",
            panelWithAgentsHint("bypassPermissions").leaderHintTextForTest());
    }

    @Test
    void defaultPermissionModeFooterUsesShortcutsAndAgentsHint() {
        assertEquals("  ? for shortcuts · ← for agents",
            panelWithAgentsHint("default").leaderHintTextForTest());
    }

    @Test
    void persistentStatusLineSuppressesIdleShortcutsHint() {
        InputPanel panel = panelWithAgentsHint("default");

        panel.setStatusLine("custom hud", 0);

        assertEquals("", panel.leaderHintTextForTest());
    }

    @Test
    void agentsHintHonorsGlobalSettingAndIdleState() {
        InputPanel panel = new InputPanel("auto");
        panel.setLeftArrowOpensAgentsForTest(() -> false);

        assertEquals("  ⏵⏵ auto mode on (shift+tab to cycle)", panel.leaderHintTextForTest());

        panel.setLeftArrowOpensAgentsForTest(() -> true);
        panel.setIsLoading(true);

        assertEquals("  ⏵⏵ auto mode on (shift+tab to cycle)", panel.leaderHintTextForTest());
    }

    private static InputPanel panelWithAgentsHint(String mode) {
        InputPanel panel = new InputPanel(mode);
        panel.setLeftArrowOpensAgentsForTest(() -> true);
        return panel;
    }

    @Test
    void cycleSkipsBypassWhenSessionPolicyDoesNotAllowIt() {
        InputPanel panel = new InputPanel("plan");
        panel.setBypassPermissionsModeAvailable(() -> false);

        panel.cyclePermissionMode();

        assertEquals("default", panel.getPermissionMode());
    }

    @Test
    void shiftModifiedTabCyclesPermissionMode() {
        InputPanel panel = new InputPanel("default");

        panel.handleKeyForTest(new KeyStroke(KeyType.TAB, false, false, true));

        assertEquals("acceptEdits", panel.getPermissionMode());
    }

}
