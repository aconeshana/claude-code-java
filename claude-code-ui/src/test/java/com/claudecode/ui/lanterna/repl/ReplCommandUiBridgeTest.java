package com.claudecode.ui.lanterna.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The CLI may bind command launchers before the Lanterna scene is initialized. */
class ReplCommandUiBridgeTest {

    @Test
    void callsAreSafeBeforeFeaturesAreInstalled() {
        ReplCommandUiBridge bridge = new ReplCommandUiBridge();

        assertDoesNotThrow(() -> {
            bridge.openModelPicker();
            bridge.openPermissions();
            bridge.openAgents();
            bridge.openSandbox();
            bridge.openMemoryDialog();
            bridge.openFileInEditor(Path.of("/tmp/CLAUDE.md"));
            bridge.clearConversation();
            bridge.switchActiveSession("session");
            bridge.resetSessionCost();
        });
    }

    @Test
    void installedCapabilitiesReceiveOnlyTheirOwnedActions() {
        ReplCommandUiBridge bridge = new ReplCommandUiBridge();
        List<String> calls = new ArrayList<>();
        bridge.install(
            new ReplCommandUiBridge.Preferences() {
                @Override public void openEffort() { calls.add("effort"); }
                @Override public void openModel() { calls.add("model"); }
                @Override public void showEffortNotification(String value) {
                    calls.add("notice:" + value);
                }
                @Override public void openTheme(String current) { calls.add("theme:" + current); }
                @Override public void openConfig() { calls.add("config"); }
                @Override public void openStatus() { calls.add("status"); }
                @Override public void openUsage() { calls.add("usage"); }
            },
            new ReplCommandUiBridge.Permissions() {
                @Override public void openRules() { calls.add("permissions"); }
                @Override public void openAddDirectory(String path) { calls.add("add:" + path); }
            },
            () -> calls.add("agents"),
            () -> calls.add("sandbox"),
            new ReplCommandUiBridge.Memory() {
                @Override public void openMemoryDialog() { calls.add("memory"); }
                @Override public void openFileInEditor(Path file) {
                    calls.add("edit:" + file);
                }
            },
            new ReplCommandUiBridge.Session() {
                @Override public void clearConversation() { calls.add("clear"); }
                @Override public void switchActiveSession(String id) { calls.add("switch:" + id); }
                @Override public void resetSessionCost() { calls.add("reset-cost"); }
            });

        bridge.openEffort();
        bridge.openModelPicker();
        bridge.showEffortNotification("high");
        bridge.openTheme("dark");
        bridge.openConfig();
        bridge.openStatus();
        bridge.openUsage();
        bridge.openPermissions();
        bridge.openAddDirectory("/work");
        bridge.openAgents();
        bridge.openSandbox();
        bridge.openMemoryDialog();
        bridge.openFileInEditor(Path.of("/tmp/x.md"));
        bridge.clearConversation();
        bridge.switchActiveSession("s-1");
        bridge.resetSessionCost();

        assertEquals(List.of(
            "effort", "model", "notice:high", "theme:dark", "config", "status", "usage",
            "permissions", "add:/work", "agents", "sandbox", "memory", "edit:/tmp/x.md",
            "clear", "switch:s-1", "reset-cost"), calls);
    }
}
