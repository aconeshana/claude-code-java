package com.claudecode.ui.lanterna.features.tasks;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.workflows.WorkflowRunStore;
import com.claudecode.ui.lanterna.dialog.BackgroundTasksDialog;
import com.claudecode.ui.lanterna.dialog.WorkflowsDialog;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.features.ReplFeature;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.repl.ReplCommandUiBridge;
import com.claudecode.ui.lanterna.repl.ReplTranscriptSink;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.transcript.ViewedTeammateHolder;
import com.googlecode.lanterna.gui2.Component;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Background task management overlays: the {@code /tasks} (alias {@code /bashes}) panel, the
 * {@code /workflows} run browser, and the "view this agent" transcript switch they share.
 *
 * <p>TS coverage (paths relative to the claude-code repo root):
 * <ul>
 *   <li>{@code commands/tasks/} — {@code /tasks} background task list, kill, and view.</li>
 *   <li>{@code components/tasks/} — the agent-view switch (viewed teammate holder + "Viewing
 *       @name — Esc to return" status line).</li>
 *   <li>Dynamic workflows browser — Java-side extension without a 197 counterpart.</li>
 * </ul>
 */
public final class BackgroundTasksFeature implements ReplCommandUiBridge.Tasks, ReplFeature {

    private final WindowBasedTextGUI gui;
    private final InputPanel inputPanel;
    private final ReplTranscriptSink sink;
    private final TaskRegistry taskRegistry;
    private final WorkflowRunStore workflowRuns;
    private final Supplier<Path> workflowHistoryDirectory;
    private final Runnable teammateViewChanged;
    private final BackgroundTasksDialog tasksDialog;
    private final WorkflowsDialog workflowsDialog;

    /**
     * @param workflowHistoryDirectory resolves the current session's workflow-run history
     *                                 directory (read on a virtual thread when the browser opens)
     * @param teammateViewChanged      notifies the transcript that the viewed agent changed
     * @param resumeWorkflow           routes a workflow resume prompt back through normal input
     */
    public BackgroundTasksFeature(WindowBasedTextGUI gui,
                                  InputPanel inputPanel,
                                  ReplTranscriptSink sink,
                                  TaskRegistry taskRegistry,
                                  WorkflowRunStore workflowRuns,
                                  Supplier<Path> workflowHistoryDirectory,
                                  Runnable teammateViewChanged,
                                  Consumer<String> resumeWorkflow,
                                  UserKeybindingsStore keybindingsStore) {
        this.gui = Objects.requireNonNull(gui, "gui");
        this.inputPanel = inputPanel;
        this.sink = Objects.requireNonNull(sink, "sink");
        this.taskRegistry = Objects.requireNonNull(taskRegistry, "taskRegistry");
        this.workflowRuns = Objects.requireNonNull(workflowRuns, "workflowRuns");
        this.workflowHistoryDirectory = Objects.requireNonNull(workflowHistoryDirectory, "workflowHistoryDirectory");
        this.teammateViewChanged = Objects.requireNonNull(teammateViewChanged, "teammateViewChanged");
        this.workflowsDialog = new WorkflowsDialog(workflowRuns, taskRegistry, resumeWorkflow, sink::system);
        this.tasksDialog = new BackgroundTasksDialog(taskRegistry);
        tasksDialog.setKeybindingsStore(keybindingsStore);
        tasksDialog.setOnViewAgent(this::viewAgentTask);
        tasksDialog.setOnViewWorkflowRoute((task, returnToTasks) ->
            openWorkflows(task.id(), returnToTasks));
    }

    @Override public List<InlineOverlay> overlays() { return List.of(tasksDialog, workflowsDialog); }
    public Component tasksView() { return tasksDialog; }
    public Component workflowsView() { return workflowsDialog; }

    /** Opens the {@code /tasks} (alias {@code /bashes}) background-tasks panel. */
    @Override
    public void openTasks() {
        gui.getGUIThread().invokeLater(() -> {
            // The prompt bar must vanish while the dialog is open (same
            // command-to-feature wiring pattern as other inline dialogs). The
            // dialog's close is the single exit for every dismiss path
            // (Esc/←/Space/Enter, kill-last-task auto-close, goBackToList's
            // close branch) and always fires this callback, so the suppression
            // flag cannot leak.
            if (inputPanel != null) inputPanel.setSuppressed(true);
            tasksDialog.show(() -> {
                if (inputPanel != null) inputPanel.setSuppressed(false);
                sink.line("  Background tasks dialog dismissed", LanternaTheme.welcomeDim());
            });
        });
    }

    @Override
    public void openWorkflows() {
        openWorkflows(null, false);
    }

    /** Opens the workflow browser on one run; {@code returnToTasks} re-opens {@code /tasks} on close. */
    @Override public void openWorkflows(String taskId, boolean returnToTasks) {
        gui.getGUIThread().invokeLater(() -> {
            if (inputPanel != null) inputPanel.setSuppressed(true);
        });
        Thread.ofVirtual().name("workflows-dialog-load").start(() -> {
            workflowRuns.loadDirectory(workflowHistoryDirectory.get());
            gui.getGUIThread().invokeLater(() -> {
                Runnable onClose = () -> {
                    if (returnToTasks) {
                        openTasks();
                    } else {
                        if (inputPanel != null) inputPanel.setSuppressed(false);
                        sink.line("  Dynamic workflows dialog dismissed", LanternaTheme.welcomeDim());
                    }
                };
                if (taskId == null) {
                    workflowsDialog.show(onClose);
                } else if (!workflowsDialog.showTask(taskId, onClose)) {
                    if (returnToTasks) openTasks();
                    else if (inputPanel != null) inputPanel.setSuppressed(false);
                    sink.line("  Dynamic workflow is no longer available", LanternaTheme.welcomeDim());
                }
            });
        });
    }

    /** Switches the transcript to a local agent's view and shows the return hint. */
    public void viewAgentTask(TaskState task) {
        if (task == null) return;
        ViewedTeammateHolder.instance().enterLocalAgentViewing(task.id());
        teammateViewChanged.run();
        if (inputPanel != null) {
            String name = taskRegistry.resolveAgentName(task.id());
            inputPanel.setTransientStatusLine(
                "Viewing @" + name + " — Esc to return to main", 0);
        }
    }
}
