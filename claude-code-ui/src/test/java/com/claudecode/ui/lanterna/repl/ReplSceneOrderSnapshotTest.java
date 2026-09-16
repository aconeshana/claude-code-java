package com.claudecode.ui.lanterna.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;

/**
 * Golden snapshot of the REPL scene mount order.
 *
 * <p>Mount order is z-order and layout position: every component below {@code messagePanel}
 * collapses to zero rows when idle, so a reordering silently changes where a dialog appears
 * relative to the spinner, the startup gates, and the prompt. This test pins the order as a
 * readable list so that a refactor of the composition root cannot move a view without the
 * change showing up in review.
 */
class ReplSceneOrderSnapshotTest {

    /** The single file that declares {@code scene.mount(...)}. */
    static final Path COMPOSITION_SOURCE = Path.of(
        "src/main/java/com/claudecode/ui/lanterna/repl/ReplSceneLayout.java");

    static final List<String> MOUNT_ORDER = List.of(
        "messagePanel",
        "spinnerComponent",
        "toolApproval.leaderView()",
        "preferences.effortView()",
        "toolApproval.questionView()",
        "toolApproval.refusalView()",
        "startupGates.trustView()",
        "startupGates.managedSettingsView()",
        "bypassPermissionsGate.view()",
        "sandbox.view()",
        "startupGates.externalIncludesView()",
        "lspRecommendationDialog",
        "preferences.modelView()",
        "preferences.customModelView()",
        "taskBoard.view()",
        "thinkingToggleDialog",
        "collaborationPickerDialog",
        "feishuSetupDialog",
        "conversationTools.exportView()",
        "hooks.view()",
        "goal.view()",
        "btw.view()",
        "preferences.themeView()",
        "conversationTools.copyView()",
        "conversationTools.diffView()",
        "conversationTools.helpView()",
        "projectPanel",
        "plugins.view()",
        "permissions.addDirectoryView()",
        "preferences.settingsView()",
        "permissions.rulesView()",
        "agents.view()",
        "mcp.view()",
        "exit.view()",
        "conversationTools.tagRemovalView()",
        "pokemon.view()",
        "memory.view()",
        "session.view()",
        "diagnostics.doctorView()",
        "diagnostics.skillsView()",
        "tasks.tasksView()",
        "tasks.workflowsView()",
        "diagnostics.statsView()",
        "new EmptySpace(new TerminalSize(0, 1))",
        "inputPanel");

    @Test
    void mountOrderMatchesTheGoldenSnapshot() throws IOException {
        String source = Files.readString(COMPOSITION_SOURCE);
        int start = source.indexOf("scene.mount(");
        assertTrue(start >= 0, "scene.mount(...) must live in " + COMPOSITION_SOURCE);
        int end = source.indexOf(");", start);
        String body = source.substring(start + "scene.mount(".length(), end);

        assertEquals(MOUNT_ORDER, topLevelArguments(body),
            "REPL mount order (z-order / layout position) changed; update the snapshot deliberately");
    }

    /** Splits an argument list on top-level commas, dropping comments and whitespace. */
    static List<String> topLevelArguments(String body) {
        String noComments = body.replaceAll("//[^\n]*", "");
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char c : noComments.toCharArray()) {
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (c == ',' && depth == 0) {
                out.add(current.toString().strip());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (StringUtils.isNotBlank(current)) out.add(current.toString().strip());
        return out.stream().map(s -> s.replaceAll("\\s+", " ")).toList();
    }
}
