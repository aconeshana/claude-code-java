package com.claudecode.ui.lanterna.features.sandbox;

import com.claudecode.ui.lanterna.dialog.SandboxSettingsDialog;
import com.claudecode.core.engine.SandboxConfig;
import com.claudecode.ui.lanterna.features.settings.UiSettings;
import com.googlecode.lanterna.gui2.Component;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import java.util.List;
import com.claudecode.ui.lanterna.input.InputPanel;
import org.apache.commons.lang3.StringUtils;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.repl.ReplCommandUiBridge;
import com.claudecode.ui.lanterna.repl.ReplTranscriptSink;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * Sandbox feature owning the full settings panel and confirmation lifecycle.
 */
public final class SandboxFeature implements ReplCommandUiBridge.Sandbox {

    private final WindowBasedTextGUI gui;
    private final InputPanel inputPanel;
    private final ReplTranscriptSink sink;
    private final SandboxSettingsDialog dialog;

    public SandboxFeature(WindowBasedTextGUI gui,
                   InputPanel inputPanel,
                   ReplTranscriptSink sink) {
        this.gui = gui;
        this.inputPanel = inputPanel;
        this.sink = sink;
        this.dialog = new SandboxSettingsDialog();
        if (gui != null) {
            this.dialog.setGuiInvoker(task -> gui.getGUIThread().invokeLater(task));
        }
    }

    public List<InlineOverlay> overlays() { return List.of(dialog); }
    public Component view() { return dialog; }

    @Override
    public void openSandbox() {
        if (gui == null) return;
        gui.getGUIThread().invokeLater(() -> inputPanel.setSuppressed(true));
        Thread.ofVirtual().name("sandbox-dialog-load").start(() -> {
            SandboxConfig config;
            UiSettings.SandboxDependencyStatus dependencies;
            boolean policyLocked;
            try {
                config = UiSettings.readSandboxConfig();
                dependencies = UiSettings.readSandboxDependencyStatus();
                policyLocked = UiSettings.areSandboxSettingsLockedByPolicy();
            } catch (RuntimeException failure) {
                gui.getGUIThread().invokeLater(() -> {
                    inputPanel.setSuppressed(false);
                    sink.line("Unable to load sandbox settings: " + failure.getMessage(),
                        LanternaTheme.toolError());
                    inputPanel.takeFocus();
                });
                return;
            }
            gui.getGUIThread().invokeLater(() -> dialog.prompt(
                config, dependencies, policyLocked, message -> {
                inputPanel.setSuppressed(false);
                if (StringUtils.isNotBlank(message)) {
                    sink.line(message, LanternaTheme.suggestion());
                }
                inputPanel.takeFocus();
            }));
        });
    }
}
