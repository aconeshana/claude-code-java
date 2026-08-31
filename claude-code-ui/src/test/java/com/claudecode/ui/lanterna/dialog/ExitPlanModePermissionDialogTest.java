package com.claudecode.ui.lanterna.dialog;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.engine.PermissionAskCallback;
import com.claudecode.core.engine.PermissionAskContext;
import com.claudecode.core.engine.PermissionUpdate;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.model.PermissionModeKind;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.core.serialization.JsonUtils;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ExitPlanModePermissionDialogTest {

    @Test
    void enterPlanModeUsesTheReleasedDedicatedApprovalUi() {
        PermissionDialog dialog = new PermissionDialog();

        dialog.show(PermissionPreviewPreparer.standard().prepare(
                PermissionAskContext.simple("EnterPlanMode",
                    JsonUtils.getMapper().createObjectNode(), "toolu_enter_plan")),
            null, _ -> {}, _ -> {}, () -> {});

        assertEquals("Enter plan mode?", dialog.titleForTest());
        assertEquals(LanternaTheme.modePlan(), dialog.accentForTest());
        assertEquals("Claude wants to enter plan mode to explore and design an implementation approach.",
            dialog.questionForTest());
        assertEquals(List.of(
            "In plan mode, Claude will:",
            " · Explore the codebase thoroughly",
            " · Identify existing patterns",
            " · Design an implementation strategy",
            " · Present a plan for your approval",
            "No code changes will be made until you approve the plan."),
            dialog.specialBodyLinesForTest());
        assertEquals("1. Yes, enter plan mode", dialog.primaryLabelForTest());
        assertEquals("2. No, start implementing now", dialog.noLabelForTest());
    }

    @Test
    void rendersReleasedPlanApprovalProfileAndAppliesSelectedMode() {
        var input = JsonUtils.getMapper().createObjectNode()
            .put("plan", "# Plan\n\n- change the parser\n- run tests")
            .put("planFilePath", "/tmp/plan.md")
            .put("_uiBypassPermissionsAvailable", false);
        input.putArray("allowedPrompts").addObject()
            .put("tool", "Bash")
            .put("prompt", "  run the focused tests  ");
        var context = PermissionAskContext.simple(
            "ExitPlanMode", input, "toolu_exit_plan");
        AtomicReference<List<PermissionUpdate>> applied = new AtomicReference<>();
        PermissionDialog dialog = new PermissionDialog();

        dialog.show(PermissionPreviewPreparer.standard().prepare(context),
            null, applied::set, _ -> {}, () -> {});

        assertEquals("Ready to code?", dialog.titleForTest());
        assertEquals(LanternaTheme.modePlan(), dialog.accentForTest());
        assertTrue(Strings.CS.contains(dialog.planContentForTest(), "change the parser"));
        assertEquals(List.of(
            "Requested permissions:",
            "  · Bash(prompt: run the focused tests)"),
            dialog.requestedPermissionsForTest());
        assertEquals("1. Yes, auto-accept edits", dialog.primaryLabelForTest());
        assertEquals("2. Yes, manually approve edits", dialog.allowSuggestionLabelForTest());

        dialog.resolvePrimaryForTest();
        assertEquals(List.of(
            new PermissionUpdate.SetMode(
                PermissionModeKind.ACCEPT_EDITS, PermissionUpdate.Destination.SESSION),
            new PermissionUpdate.AddRules(
                List.of(new PermissionUpdate.RuleValue(
                    "Bash", "prompt: run the focused tests")),
                PermissionUpdate.Behavior.ALLOW,
                PermissionUpdate.Destination.SESSION)),
            applied.get());
    }

    @Test
    void emptyPlanUsesSimpleExitConfirmation() {
        PermissionDialog dialog = new PermissionDialog();

        dialog.show(PermissionPreviewPreparer.standard().prepare(
                PermissionAskContext.simple("ExitPlanMode",
                    JsonUtils.getMapper().createObjectNode().put("plan", ""),
                    "toolu_exit_plan")),
            null, _ -> {}, _ -> {}, () -> {});

        assertEquals("Exit plan mode?", dialog.titleForTest());
        assertEquals("Claude wants to exit plan mode", dialog.questionForTest());
        assertEquals("1. Yes", dialog.primaryLabelForTest());
        assertEquals(null, dialog.allowSuggestionLabelForTest());
        assertEquals("2. No", dialog.noLabelForTest());
    }

    @Test
    void clearContextSettingAddsReleasedApprovalBranchAndPayload() {
        var input = JsonUtils.getMapper().createObjectNode()
            .put("plan", "Implement the parser")
            .put("_uiShowClearContext", true)
            .put("_uiContextUsedPercent", 37)
            .put("_uiBypassPermissionsAvailable", false)
            .put("_uiAutoModeAvailable", false);
        input.putArray("allowedPrompts").addObject()
            .put("tool", "Bash")
            .put("prompt", "run the focused tests");
        AtomicReference<PermissionDialog.PlanClearApproval> cleared = new AtomicReference<>();
        AtomicReference<PermissionAskCallback.Result> result = new AtomicReference<>();
        PermissionDialog dialog = new PermissionDialog();
        dialog.setPlanClearApprovalConsumer(cleared::set);

        dialog.show(PermissionPreviewPreparer.standard().prepare(
                PermissionAskContext.simple("ExitPlanMode", input, "toolu_exit_plan")),
            null, _ -> {}, result::set, () -> {});

        assertEquals("1. Yes, clear context (37% used) and auto-accept edits",
            dialog.primaryLabelForTest());
        assertEquals("2. Yes, auto-accept edits", dialog.keepContextLabelForTest());
        assertEquals("3. Yes, manually approve edits", dialog.allowSuggestionLabelForTest());
        assertEquals("4. No, keep planning", dialog.noLabelForTest());

        dialog.resolvePrimaryForTest();
        assertEquals("Implement the parser", cleared.get().plan());
        assertEquals(PermissionModeKind.ACCEPT_EDITS, cleared.get().mode());
        assertEquals(null, cleared.get().feedback());
        assertEquals(List.of(
            new PermissionUpdate.SetMode(
                PermissionModeKind.ACCEPT_EDITS, PermissionUpdate.Destination.SESSION),
            new PermissionUpdate.AddRules(
                List.of(new PermissionUpdate.RuleValue(
                    "Bash", "prompt: run the focused tests")),
                PermissionUpdate.Behavior.ALLOW,
                PermissionUpdate.Destination.SESSION)),
            cleared.get().permissionUpdates());
        assertFalse(result.get().allowed());
    }

    @Test
    void shiftTabFeedbackApprovesClearContextInsteadOfRejectingThePlan() {
        var input = JsonUtils.getMapper().createObjectNode()
            .put("plan", "Implement it")
            .put("_uiShowClearContext", true);
        AtomicReference<PermissionDialog.PlanClearApproval> cleared = new AtomicReference<>();
        PermissionDialog dialog = new PermissionDialog();
        dialog.setPlanClearApprovalConsumer(cleared::set);
        dialog.show(PermissionPreviewPreparer.standard().prepare(
                PermissionAskContext.simple("ExitPlanMode", input, "toolu_exit_plan")),
            null, _ -> {}, _ -> {}, () -> {});

        dialog.approvePlanFeedbackForTest("  keep the public API compatible  ");

        assertEquals("keep the public API compatible", cleared.get().feedback());
    }

    @Test
    void keepPlanningFeedbackCarriesPastedImagesLikeReleasedSelectInput() {
        var input = JsonUtils.getMapper().createObjectNode().put("plan", "Implement it");
        AtomicReference<PermissionAskCallback.Result> result = new AtomicReference<>();
        PermissionDialog dialog = new PermissionDialog();
        dialog.show(PermissionPreviewPreparer.standard().prepare(
                PermissionAskContext.simple("ExitPlanMode", input, "toolu_exit_plan")),
            null, _ -> {}, result::set, () -> {});

        dialog.addPlanFeedbackImageForTest("aW1hZ2U=", "image/png");
        dialog.rejectPlanFeedbackForTest("[Image #1]");

        assertFalse(result.get().allowed());
        assertEquals("(See attached image)", result.get().feedback());
        assertEquals(1, result.get().feedbackContentBlocks().size());
        assertTrue(result.get().feedbackContentBlocks().getFirst() instanceof ImageBlock);
    }

    @Test
    void keepPlanningInputDoesNotResolveUntilTextOrAnImageIsPresent() {
        AtomicReference<PermissionAskCallback.Result> result = new AtomicReference<>();
        PermissionDialog dialog = new PermissionDialog();
        dialog.show(PermissionPreviewPreparer.standard().prepare(
                PermissionAskContext.simple("ExitPlanMode",
                    JsonUtils.getMapper().createObjectNode().put("plan", "Implement it"),
                    "toolu_exit_plan")),
            null, _ -> {}, result::set, () -> {});

        dialog.rejectPlanFeedbackForTest("");

        assertEquals(null, result.get());
        assertTrue(dialog.isActiveForTest());
    }

    @Test
    void manualApprovalKeepsAllowedPromptRules() {
        var input = JsonUtils.getMapper().createObjectNode()
            .put("plan", "Implement it");
        input.putArray("allowedPrompts").addObject()
            .put("tool", "PowerShell")
            .put("prompt", "  run the formatter  ");
        AtomicReference<List<PermissionUpdate>> applied = new AtomicReference<>();
        PermissionDialog dialog = new PermissionDialog();

        dialog.show(PermissionPreviewPreparer.standard().prepare(
                PermissionAskContext.simple("ExitPlanMode", input, "toolu_exit_plan")),
            null, applied::set, _ -> {}, () -> {});

        dialog.resolveSuggestionForTest();
        assertEquals(List.of(
            new PermissionUpdate.SetMode(
                PermissionModeKind.DEFAULT, PermissionUpdate.Destination.SESSION),
            new PermissionUpdate.AddRules(
                List.of(new PermissionUpdate.RuleValue(
                    "PowerShell", "prompt: run the formatter")),
                PermissionUpdate.Behavior.ALLOW,
                PermissionUpdate.Destination.SESSION)),
            applied.get());
    }

    @Test
    void bypassCapableSessionsOfferBypassAsThePrimaryApproval() {
        var input = JsonUtils.getMapper().createObjectNode()
            .put("plan", "Implement it")
            .put("_uiBypassPermissionsAvailable", true);
        PermissionDialog dialog = new PermissionDialog();

        dialog.show(PermissionPreviewPreparer.standard().prepare(
                PermissionAskContext.simple("ExitPlanMode", input, "toolu_exit_plan")),
            null, _ -> {}, _ -> {}, () -> {});

        assertEquals("1. Yes, and bypass permissions", dialog.primaryLabelForTest());
    }

    @Test
    void bypassTakesPriorityWhenAutoModeIsAlsoAvailable() {
        var input = JsonUtils.getMapper().createObjectNode()
            .put("plan", "Implement it")
            .put("_uiShowClearContext", true)
            .put("_uiBypassPermissionsAvailable", true)
            .put("_uiAutoModeAvailable", true);
        AtomicReference<PermissionDialog.PlanClearApproval> cleared = new AtomicReference<>();
        PermissionDialog dialog = new PermissionDialog();
        dialog.setPlanClearApprovalConsumer(cleared::set);

        dialog.show(PermissionPreviewPreparer.standard().prepare(
                PermissionAskContext.simple("ExitPlanMode", input, "toolu_exit_plan")),
            null, _ -> {}, _ -> {}, () -> {});

        assertEquals("1. Yes, clear context and bypass permissions", dialog.primaryLabelForTest());
        assertEquals("2. Yes, and bypass permissions", dialog.keepContextLabelForTest());
        dialog.resolvePrimaryForTest();
        assertEquals(PermissionModeKind.BYPASS_PERMISSIONS, cleared.get().mode());
    }

    @Test
    void scrollableReplProjectionKeepsTheLongPlanOutOfThePinnedDialog() {
        var input = JsonUtils.getMapper().createObjectNode()
            .put("plan", "# Long plan\n\n" + "detail\n".repeat(100))
            .put("_uiPlanPreviewInTranscript", true);
        PermissionDialog dialog = new PermissionDialog();

        dialog.show(PermissionPreviewPreparer.standard().prepare(
                PermissionAskContext.simple("ExitPlanMode", input, "toolu_exit_plan")),
            null, _ -> {}, _ -> {}, () -> {});

        assertEquals("", dialog.planContentForTest());
        assertEquals("Ready to code?", dialog.titleForTest());
        assertTrue(Strings.CS.contains(dialog.questionForTest(), "ready to execute"));
    }
}
