package com.claudecode.ui.lanterna.features.settings;

import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.ui.lanterna.dialog.AddDirDialog;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.Component;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.claudecode.ui.lanterna.input.InputPanel;
import org.apache.commons.lang3.StringUtils;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.repl.ReplCommandUiBridge;
import com.claudecode.ui.lanterna.repl.ReplTranscriptSink;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * Permission-rule and workspace-directory feature.
 */
public final class PermissionsFeature implements ReplCommandUiBridge.Permissions {

    private record ChangeLine(String text, TextColor color) {}

    private final WindowBasedTextGUI gui;
    private final InputPanel inputPanel;
    private final CommandContext commandContext;
    private final PermissionGate permissionGate;
    private final ReplTranscriptSink sink;
    private final PermissionsPanel permissionsPanel;
    private final AddDirDialog addDirDialog;

    PermissionsFeature(CommandContext commandContext, ReplTranscriptSink sink) {
        this(null, null, commandContext, null, sink, null, null);
    }

    public PermissionsFeature(WindowBasedTextGUI gui,
                       InputPanel inputPanel,
                       CommandContext commandContext,
                       PermissionGate permissionGate,
                       ReplTranscriptSink sink) {
        this(gui, inputPanel, commandContext, permissionGate, sink,
            new PermissionsPanel(), new AddDirDialog());
    }

    private PermissionsFeature(WindowBasedTextGUI gui,
                               InputPanel inputPanel,
                               CommandContext commandContext,
                               PermissionGate permissionGate,
                               ReplTranscriptSink sink,
                               PermissionsPanel permissionsPanel,
                               AddDirDialog addDirDialog) {
        this.gui = gui;
        this.inputPanel = inputPanel;
        this.commandContext = commandContext;
        this.permissionGate = permissionGate;
        this.sink = sink;
        this.permissionsPanel = permissionsPanel;
        this.addDirDialog = addDirDialog;
        if (gui != null) {
            Consumer<Runnable> invoker =
                task -> gui.getGUIThread().invokeLater(task);
            this.addDirDialog.setGuiInvoker(invoker);
            this.permissionsPanel.setGuiInvoker(invoker);
        }
    }

    public List<InlineOverlay> overlays() {
        return List.of(addDirDialog, permissionsPanel);
    }

    public Component addDirectoryView() { return addDirDialog; }
    public Component rulesView() { return permissionsPanel; }

    public void setKeybindingsStore(UserKeybindingsStore store) {
        permissionsPanel.setKeybindingsStore(store);
        addDirDialog.setKeybindingsStore(store);
    }

    @Override
    public void openRules() {
        if (gui == null || permissionsPanel == null) return;
        gui.getGUIThread().invokeLater(() -> {
            suppressInput(true);
            List<ChangeLine> changeLog = new ArrayList<>();
            permissionsPanel.show(PermissionsPanel.Tab.ALLOW,
                () -> permissionGate,
                commandContext.session()::workingDirectory,
                this::validateAddDirPath,
                this::handleAddDirResultQuiet,
                (text, color) -> changeLog.add(new ChangeLine(text, color)),
                () -> {
                    suppressInput(false);
                    flushChangeLog(changeLog);
                });
        });
    }

    @Override
    public void openAddDirectory(String path) {
        if (gui == null || addDirDialog == null) return;
        gui.getGUIThread().invokeLater(() -> {
            suppressInput(true);
            addDirDialog.show(path, this::validateAddDirPath, (resolved, remember) -> {
                suppressInput(false);
                if (resolved != null && remember != null) {
                    applyAddDirInBackground(resolved, remember, false);
                } else {
                    handleAddDirResult(resolved, remember);
                }
            });
        });
    }

    AddDirDialog.ValidationOutcome validateAddDirPath(String path) {
        if (commandContext.presentation().addDirValidator() == null) {
            return new AddDirDialog.ValidationOutcome(null, "No validator wired.");
        }
        CommandContext.AddDirValidationOutcome outcome =
            commandContext.presentation().addDirValidator().apply(path);
        return new AddDirDialog.ValidationOutcome(
            outcome.resolvedPath(), outcome.errorMessage());
    }

    void handleAddDirResult(String path, Boolean remember) {
        if (path == null) {
            sink.line("  Did not add a working directory.", LanternaTheme.welcomeDim());
            return;
        }
        if (remember == null) {
            sink.line("  Did not add " + path + " as a working directory.",
                LanternaTheme.welcomeDim());
            return;
        }
        sink.breadcrumb("/add-dir");
        CommandResult result = commandContext.presentation().addDirApply() != null
            ? commandContext.presentation().addDirApply().apply(commandContext, path, remember)
            : null;
        appendResult(result);
    }

    private void handleAddDirResultQuiet(String path, Boolean remember) {
        if (path == null || remember == null || commandContext.presentation().addDirApply() == null) return;
        applyAddDirInBackground(path, remember, true);
    }

    private void applyAddDirInBackground(String path, boolean remember, boolean quiet) {
        Thread.ofVirtual().name("add-dir-apply").start(() -> {
            CommandResult result = commandContext.presentation().addDirApply() != null
                ? commandContext.presentation().addDirApply().apply(commandContext, path, remember)
                : null;
            if (gui == null) {
                if (!quiet) {
                    sink.breadcrumb("/add-dir");
                    appendResult(result);
                }
                return;
            }
            gui.getGUIThread().invokeLater(() -> {
                if (quiet) {
                    permissionsPanel.workspaceTab().reload();
                } else {
                    sink.breadcrumb("/add-dir");
                    appendResult(result);
                }
            });
        });
    }

    private void flushChangeLog(List<ChangeLine> changeLog) {
        if (changeLog.isEmpty()) return;
        sink.line("", TextColor.ANSI.DEFAULT);
        for (ChangeLine line : changeLog) {
            sink.line("  " + line.text(), line.color());
        }
    }

    private void appendResult(CommandResult result) {
        if (result != null && result.output() != null && !StringUtils.isBlank(result.output())) {
            sink.line("  ⎿  " + result.output(), LanternaTheme.welcomeDim());
        }
    }

    private void suppressInput(boolean suppressed) {
        if (inputPanel != null) inputPanel.setSuppressed(suppressed);
    }
}
