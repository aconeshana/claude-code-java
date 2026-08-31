package com.claudecode.ui.lanterna.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.claudecode.core.engine.PermissionAskContext;
import com.claudecode.core.engine.PermissionUpdate;
import com.claudecode.core.model.PermissionModeKind;
import com.claudecode.core.serialization.JsonUtils;
import java.util.List;
import org.junit.jupiter.api.Test;


class PermissionDialogReleased197LayoutTest {

    @Test
    void bashApprovalUsesReleasedRowsAndListItemGutters() {
        var input = JsonUtils.getMapper().createObjectNode()
            .put("command", "touch /private/tmp/cc197-default-perm-marker")
            .put("description", "Create permission test marker");
        var suggestions = List.<PermissionUpdate>of(
            new PermissionUpdate.AddDirectories(
                List.of("/private/tmp"), PermissionUpdate.Destination.SESSION),
            new PermissionUpdate.SetMode(
                PermissionModeKind.ACCEPT_EDITS, PermissionUpdate.Destination.SESSION));
        var context = PermissionAskContext.builder("Bash", input)
            .toolUseId("toolu_197_bash_probe")
            .decisionReason("mode", "DEFAULT")
            .blockedPath("/private/tmp/cc197-default-perm-marker")
            .suggestions(suggestions)
            .build();

        PermissionDialog dialog = new PermissionDialog();
        dialog.show(PermissionPreviewPreparer.standard().prepare(context), (_, _, _) -> null,
            _ -> {}, _ -> {}, () -> {});

        assertEquals(13, dialog.calculatePreferredSize().getRows(),
            "empty explainer/debug placeholders and an options spacer must not consume rows");
        assertEquals(" ❯ 1. Yes", PermissionDialog.optionLineForTest(true, "1. Yes"));
        assertEquals("   2. Yes, and always allow access to tmp/ from this project",
            PermissionDialog.optionLineForTest(false,
                "2. Yes, and always allow access to tmp/ from this project"));
    }
}
