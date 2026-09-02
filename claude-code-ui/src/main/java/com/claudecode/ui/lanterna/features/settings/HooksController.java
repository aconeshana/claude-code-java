package com.claudecode.ui.lanterna.features.settings;

import com.claudecode.runtime.hooks.HookConfigurationPort;
import com.claudecode.runtime.hooks.HookConfigurationSnapshot;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.claudecode.ui.lanterna.dialog.HooksConfigMenuDialog;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.repl.ReplTranscriptSink;

/**
 * Drives the inline {@code /hooks} configuration browser: loads the merged hook snapshot, shows the
 * {@link HooksConfigMenuDialog}, and keeps it live-refreshed while the user edits the corresponding
 * behavioron from another terminal through {@link HookConfigurationPort}.
 */
public final class HooksController {

    private static final Logger log = LoggerFactory.getLogger(HooksController.class);

    private final WindowBasedTextGUI gui;
    private final HooksConfigMenuDialog dialog;
    private final InputPanel inputPanel;
    private final ReplTranscriptSink sink;
    private final Supplier<List<String>> toolNames;
    private final HookConfigurationPort hookConfiguration;

    /** Active hot-reload subscription for the currently-open dialog; null while closed. */
    private AutoCloseable reloadSubscription;

    public HooksController(WindowBasedTextGUI gui, HooksConfigMenuDialog dialog, InputPanel inputPanel,
                    ReplTranscriptSink sink, Supplier<List<String>> toolNames,
                    HookConfigurationPort hookConfiguration) {
        this.gui = gui;
        this.dialog = dialog;
        this.inputPanel = inputPanel;
        this.sink = sink;
        this.toolNames = toolNames;
        this.hookConfiguration = Objects.requireNonNull(hookConfiguration, "hookConfiguration");
    }

    /**
     * Opens the inline hooks configuration browser. Loads hooks from all settings sources,
     * suppresses the prompt bar while active, subscribes to settings hot-reload, and emits a
     * transcript breadcrumb when the user closes the dialog.
     */
    public void open() {
        if (gui == null || dialog == null) return;
        final String workingDir = System.getProperty("user.dir");
        gui.getGUIThread().invokeLater(() -> {
            if (inputPanel != null) inputPanel.setSuppressed(true);
        });
        Thread.ofVirtual().name("hooks-dialog-load").start(() -> {
            HookConfigurationSnapshot snapshot;
            boolean disabled;
            boolean disabledByPolicy;
            boolean restrictedByPolicy;
            try {
                snapshot = hookConfiguration.snapshot(workingDir, toolNames.get());
                disabled = UiSettings.readDisableAllHooks();
                disabledByPolicy = UiSettings.readDisableAllHooksByPolicy();
                restrictedByPolicy = UiSettings.readAllowManagedHooksOnlyByPolicy();
            } catch (RuntimeException failure) {
                gui.getGUIThread().invokeLater(() -> {
                    if (inputPanel != null) inputPanel.setSuppressed(false);
                    sink.system("⚠ Failed to load hooks configuration: " + failure.getMessage());
                    if (inputPanel != null) inputPanel.takeFocus();
                });
                return;
            }
            gui.getGUIThread().invokeLater(() -> {
                dialog.show(snapshot, disabled || disabledByPolicy,
                    disabledByPolicy, restrictedByPolicy, () -> {
                    if (inputPanel != null) inputPanel.setSuppressed(false);
                    closeReloadSubscription();
                    sink.breadcrumb("/hooks");
                });
// Subscribe to settings hot-reload so external edits repaint the dialog live.
                closeReloadSubscription();  // paranoia: cancel any leftover
                try {
                    reloadSubscription = hookConfiguration.subscribeReload(() -> refresh(workingDir));
                } catch (Exception e) {
                    log.debug("Failed to subscribe hooks dialog to reloader: {}", e.getMessage());
                }
            });
        });
    }

    /**
     * Callback invoked by the application hook port after a successful reload.
     */
    private void refresh(String workingDir) {
        if (gui == null || dialog == null || !dialog.isActive()) return;
        Thread.ofVirtual().name("hooks-dialog-refresh").start(() -> {
            var snapshot = hookConfiguration.snapshot(workingDir, toolNames.get());
            boolean disabled           = UiSettings.readDisableAllHooks();
            boolean disabledByPolicy   = UiSettings.readDisableAllHooksByPolicy();
            boolean restrictedByPolicy = UiSettings.readAllowManagedHooksOnlyByPolicy();
            gui.getGUIThread().invokeLater(() -> {
                if (!dialog.isActive()) return;
                dialog.refresh(snapshot, disabled || disabledByPolicy,
                    disabledByPolicy, restrictedByPolicy);
            });
        });
    }

    private void closeReloadSubscription() {
        if (reloadSubscription == null) return;
        try {
            reloadSubscription.close();
        } catch (Exception _) {}
        reloadSubscription = null;
    }
}
