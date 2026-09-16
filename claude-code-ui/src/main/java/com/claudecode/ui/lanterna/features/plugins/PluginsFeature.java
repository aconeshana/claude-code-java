package com.claudecode.ui.lanterna.features.plugins;

import com.claudecode.commands.CommandContext;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.runtime.mcp.McpManagementPort;
import com.claudecode.runtime.plugins.PluginMarketplacePort;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.features.ReplFeature;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.plugin.PluginPanelServices;
import com.claudecode.ui.lanterna.plugin.PluginRoute;
import com.claudecode.ui.lanterna.plugin.PluginSettingsPanel;
import com.claudecode.ui.lanterna.repl.ReplCommandUiBridge;
import com.claudecode.ui.lanterna.repl.ReplTranscriptSink;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.Component;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The {@code /plugin} settings panel: marketplace browsing, install/uninstall, and the
 * post-close change log that triggers a plugin runtime refresh.
 *
 * <p>TS coverage (paths relative to the claude-code repo root):
 * <ul>
 *   <li>{@code commands/plugin/} — the {@code /plugin} route parsing and settings panel
 *       (Discover / Installed / Marketplaces / Errors tabs).</li>
 * </ul>
 */
public final class PluginsFeature implements ReplCommandUiBridge.Plugins, ReplFeature {

    private final WindowBasedTextGUI gui;
    private final InputPanel inputPanel;
    private final ReplTranscriptSink sink;
    private final CommandContext commandContext;
    private final PluginSettingsPanel panel;

    public PluginsFeature(WindowBasedTextGUI gui,
                          InputPanel inputPanel,
                          ReplTranscriptSink sink,
                          CommandContext commandContext,
                          PluginMarketplacePort plugins,
                          McpManagementPort mcpManagement,
                          UserKeybindingsStore keybindingsStore) {
        this.gui = Objects.requireNonNull(gui, "gui");
        this.inputPanel = inputPanel;
        this.sink = Objects.requireNonNull(sink, "sink");
        this.commandContext = Objects.requireNonNull(commandContext, "commandContext");
        // inline, zero height until shown
        this.panel = new PluginSettingsPanel(
            new PluginPanelServices(plugins,
                task -> Thread.ofVirtual().name("plugin-panel-io").start(task),
                mcpManagement));
        panel.setKeybindingsStore(keybindingsStore);
    }

    @Override public List<InlineOverlay> overlays() { return List.of(panel); }
    public Component view() { return panel; }

    @Override
    public void openPluginPanel(String args) {
        PluginRoute route = PluginRoute.parse(args);
        List<Map.Entry<String, TextColor>> changeLog = new ArrayList<>();
        gui.getGUIThread().invokeLater(() -> {
            if (inputPanel != null) inputPanel.setSuppressed(true);
            panel.show(route,
                (line, color) -> changeLog.add(Map.entry(line, color)),
                () -> {
                    if (inputPanel != null) inputPanel.setSuppressed(false);
                    if (!changeLog.isEmpty()) {
                        sink.line("", TextColor.ANSI.DEFAULT);
                        for (var entry : changeLog) {
                            sink.line("  " + entry.getKey(), entry.getValue());
                        }
                        // Plugins changed — re-inject runtime state.
                        Thread.startVirtualThread(() -> {
                            var rt = commandContext.application().plugins();
                            if (rt != null) {
                                try { rt.refresh(); } catch (Exception _) { }
                            }
                        });
                    }
                });
        });
    }
}
