package com.claudecode.ui.lanterna.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.claudecode.commands.CommandContext;
import com.claudecode.commands.impl.terminal.CopyCommand;
import com.claudecode.commands.session.ResumeRequest;
import com.claudecode.core.engine.CompactProgressEvent;
import com.claudecode.core.pokemon.PokemonProfile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
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
            bridge.openMessageSelector();
            bridge.openHelp();
            bridge.openDiff();
            bridge.openExport("x");
            bridge.openCopyPicker("x", List.of(), false);
            bridge.showContextVisualization();
            bridge.openDoctor();
            bridge.openStats();
            bridge.openSkills();
            bridge.openHooks();
            bridge.openMcp();
            bridge.openTasks();
            bridge.openWorkflows();
            bridge.openWorkflows("t", true);
            bridge.openPluginPanel("");
            bridge.openGoal();
            bridge.compactProgress(null);
            bridge.setWelcomePokemon(null);
            bridge.showWelcomePokemon(null);
            bridge.openPokemonHatch(null);
            bridge.openBtw("q", Function.identity());
            bridge.startWebGateway();
        });
    }

    @Test
    void installedCapabilitiesReceiveOnlyTheirOwnedActions() {
        ReplCommandUiBridge bridge = new ReplCommandUiBridge();
        List<String> calls = new ArrayList<>();
        bridge.install(new ReplCommandUiBridge.Capabilities(
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
                @Override public void resume(ResumeRequest request) { calls.add("resume"); }
                @Override public void openMessageSelector() { calls.add("rewind"); }
            },
            new ReplCommandUiBridge.Conversation() {
                @Override public void openHelp() { calls.add("help"); }
                @Override public void openDiff() { calls.add("diff"); }
                @Override public void openExport(String content) { calls.add("export:" + content); }
                @Override public void openCopyPicker(String fullText,
                                                     List<CopyCommand.CodeBlock> codeBlocks,
                                                     boolean skipPicker) {
                    calls.add("copy:" + fullText + ":" + skipPicker);
                }
                @Override public void showContextVisualization() { calls.add("context"); }
                @Override public void openTagRemoval(CommandContext.TagRemovalRequest request) {
                    calls.add("tag-removal");
                }
            },
            new ReplCommandUiBridge.Diagnostics() {
                @Override public void openDoctor() { calls.add("doctor"); }
                @Override public void openStats() { calls.add("stats"); }
                @Override public void openSkills() { calls.add("skills"); }
            },
            () -> calls.add("hooks"),
            () -> calls.add("mcp"),
            new ReplCommandUiBridge.Tasks() {
                @Override public void openTasks() { calls.add("tasks"); }
                @Override public void openWorkflows() { calls.add("workflows"); }
                @Override public void openWorkflows(String taskId, boolean returnToTasks) {
                    calls.add("workflows:" + taskId + ":" + returnToTasks);
                }
            },
            args -> calls.add("plugin:" + args),
            () -> calls.add("goal"),
            _ -> calls.add("compact"),
            new ReplCommandUiBridge.Pokemon() {
                @Override public void setWelcomePokemon(PokemonProfile pokemon) { calls.add("pokemon-set"); }
                @Override public void showWelcomePokemon(PokemonProfile pokemon) { calls.add("pokemon-show"); }
                @Override public void openHatchDialog(CommandContext.PokemonHatchRequest request) {
                    calls.add("pokemon-hatch");
                }
            },
            (question, _) -> calls.add("btw:" + question),
            () -> calls.add("web")));

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
        bridge.resumeSession(null);
        bridge.openMessageSelector();
        bridge.openHelp();
        bridge.openDiff();
        bridge.openExport("md");
        bridge.openCopyPicker("txt", List.of(), true);
        bridge.showContextVisualization();
        bridge.openTagRemoval(null);
        bridge.openDoctor();
        bridge.openStats();
        bridge.openSkills();
        bridge.openHooks();
        bridge.openMcp();
        bridge.openTasks();
        bridge.openWorkflows();
        bridge.openWorkflows("t-1", true);
        bridge.openPluginPanel("discover");
        bridge.openGoal();
        bridge.compactProgress((CompactProgressEvent) null);
        bridge.setWelcomePokemon(null);
        bridge.showWelcomePokemon(null);
        bridge.openPokemonHatch(null);
        bridge.openBtw("why", Function.identity());
        bridge.startWebGateway();

        assertEquals(List.of(
            "effort", "model", "notice:high", "theme:dark", "config", "status", "usage",
            "permissions", "add:/work", "agents", "sandbox", "memory", "edit:/tmp/x.md",
            "clear", "switch:s-1", "reset-cost", "resume", "rewind",
            "help", "diff", "export:md", "copy:txt:true", "context", "tag-removal",
            "doctor", "stats", "skills", "hooks", "mcp",
            "tasks", "workflows", "workflows:t-1:true", "plugin:discover", "goal", "compact",
            "pokemon-set", "pokemon-show", "pokemon-hatch", "btw:why", "web"), calls);
    }
}
