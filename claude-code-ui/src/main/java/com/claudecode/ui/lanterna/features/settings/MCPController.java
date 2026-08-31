package com.claudecode.ui.lanterna.features.settings;

import com.claudecode.runtime.mcp.McpManagementPort;
import com.claudecode.runtime.mcp.McpManagementPort.Action;
import com.claudecode.runtime.mcp.McpManagementPort.Server;
import com.claudecode.ui.lanterna.dialog.MCPSettingsDialog;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.repl.LanternaReplScreen;
import com.claudecode.ui.lanterna.repl.ReplTranscriptSink;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.lang3.StringUtils;

/**
 * Presentation controller for the inline {@code /mcp} browser.
 */
public final class MCPController {

    private final WindowBasedTextGUI gui;
    private final MCPSettingsDialog dialog;
    private final InputPanel inputPanel;
    private final ReplTranscriptSink sink;
    private final McpManagementPort management;
    private final AtomicBoolean actionInFlight = new AtomicBoolean();
    private final AtomicLong dialogEpoch = new AtomicLong();

    public MCPController(WindowBasedTextGUI gui, MCPSettingsDialog dialog, InputPanel inputPanel,
                         ReplTranscriptSink sink, McpManagementPort management) {
        this.gui = gui;
        this.dialog = dialog;
        this.inputPanel = inputPanel;
        this.sink = sink;
        this.management = management != null ? management : McpManagementPort.none();
    }

    public void open() {
        if (gui == null || dialog == null) return;
        long epoch = dialogEpoch.incrementAndGet();
        gui.getGUIThread().invokeLater(() -> {
            if (inputPanel != null) inputPanel.setSuppressed(true);
        });
        Thread.ofVirtual().name("mcp-dialog-load").start(() -> {
            try {
                var snapshot = management.snapshot();
                gui.getGUIThread().invokeLater(() -> {
                    if (dialogEpoch.get() != epoch) return;
                    dialog.setToolsProvider(management::tools);
                    dialog.setActionHandler(this::handleMcpAction);
                    dialog.show(snapshot, () -> {
                        dialogEpoch.incrementAndGet();
                        if (inputPanel != null) inputPanel.setSuppressed(false);
                        sink.breadcrumb("/mcp");
                    });
                });
            } catch (RuntimeException failure) {
                gui.getGUIThread().invokeLater(() -> {
                    if (dialogEpoch.get() != epoch) return;
                    if (inputPanel != null) inputPanel.setSuppressed(false);
                    sink.system("⚠ Failed to load MCP configuration: " + failure.getMessage());
                    if (inputPanel != null) inputPanel.takeFocus();
                });
            }
        });
    }

    void handleMcpAction(MCPSettingsDialog.MenuAction action, Server server) {
        if (server == null) return;
        Action mapped = switch (action) {
            case ENABLE -> Action.ENABLE;
            case DISABLE -> Action.DISABLE;
            case RECONNECT -> Action.RECONNECT;
            case AUTHENTICATE -> Action.AUTHENTICATE;
            case CLEAR_AUTH -> Action.CLEAR_AUTHENTICATION;
            case VIEW_TOOLS, BACK -> null;
        };
        if (mapped == null) return;
        if (!actionInFlight.compareAndSet(false, true)) return;

        if (action == MCPSettingsDialog.MenuAction.RECONNECT && dialog != null) {
            dialog.beginReconnect(server.name());
        }
        Runnable task = () -> runAction(mapped, server.name(),
            action == MCPSettingsDialog.MenuAction.RECONNECT);
        if (gui == null) task.run();
        else Thread.ofVirtual().name("mcp-" + mapped.name().toLowerCase(Locale.ROOT) + "-" + server.name())
            .start(task);
    }

    private void runAction(Action action, String serverName, boolean reconnect) {
        String message;
        try {
            message = management.execute(action, serverName);
        } catch (RuntimeException failure) {
            String failureMessage = failure.getMessage() == null
                ? failure.getClass().getSimpleName() : failure.getMessage();
            completeOnGui(() -> {
                if (reconnect && dialog != null) dialog.endReconnectFailure(failureMessage);
                sink.system("⚠ " + failureMessage);
                actionInFlight.set(false);
            });
            return;
        }

        McpManagementPort.Snapshot snapshot = null;
        String refreshFailure = null;
        try {
            snapshot = management.snapshot();
        } catch (RuntimeException failure) {
            refreshFailure = failure.getMessage() == null
                ? failure.getClass().getSimpleName() : failure.getMessage();
        }
        McpManagementPort.Snapshot finalSnapshot = snapshot;
        String finalRefreshFailure = refreshFailure;
        completeOnGui(() -> {
            try {
                if (reconnect && dialog != null) dialog.endReconnectSuccess(message);
                if (finalSnapshot != null && dialog != null && dialog.isActive()) {
                    dialog.refresh(finalSnapshot);
                }
                if (StringUtils.isNotBlank(message)) sink.system(message);
                if (finalRefreshFailure != null) {
                    sink.system("⚠ MCP action completed, but configuration refresh failed: "
                        + finalRefreshFailure);
                }
            } finally {
                actionInFlight.set(false);
            }
            });
    }

    private void completeOnGui(Runnable action) {
        if (gui == null) action.run();
        else gui.getGUIThread().invokeLater(action);
    }
}
