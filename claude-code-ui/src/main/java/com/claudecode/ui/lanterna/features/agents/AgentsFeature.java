package com.claudecode.ui.lanterna.features.agents;

import com.claudecode.commands.CommandContext;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.runtime.memory.MemoryCatalog;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.Component;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicLong;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.repl.ReplCommandUiBridge;
import com.claudecode.ui.lanterna.repl.ReplTranscriptSink;

/**
 * Agent-management feature owning the agents panel and its accumulated transcript output.
 */
public final class AgentsFeature implements ReplCommandUiBridge.Agents {

    private record ChangeLine(String text, TextColor color) {}

    private final WindowBasedTextGUI gui;
    private final InputPanel inputPanel;
    private final CommandContext commandContext;
    private final Supplier<List<String>> toolNames;
    private final ReplTranscriptSink sink;
    private final Consumer<String> promptSubmit;
    private final TaskRegistry taskRegistry;
    private final Consumer<TaskState> viewTask;
    private final AgentsPanel panel;
    private final AtomicLong loadGeneration = new AtomicLong();

    public AgentsFeature(WindowBasedTextGUI gui,
                  InputPanel inputPanel,
                  MemoryCatalog memoryCatalog,
                  CommandContext commandContext,
                  Supplier<List<String>> toolNames,
                  ReplTranscriptSink sink) {
        this(gui, inputPanel, memoryCatalog, commandContext, toolNames, sink, _ -> {});
    }

    public AgentsFeature(WindowBasedTextGUI gui,
                  InputPanel inputPanel,
                  MemoryCatalog memoryCatalog,
                  CommandContext commandContext,
                  Supplier<List<String>> toolNames,
                  ReplTranscriptSink sink,
                  Consumer<String> promptSubmit) {
        this(gui, inputPanel, memoryCatalog, commandContext, toolNames, sink, promptSubmit,
            null, _ -> {});
    }

    public AgentsFeature(WindowBasedTextGUI gui,
                  InputPanel inputPanel,
                  MemoryCatalog memoryCatalog,
                  CommandContext commandContext,
                  Supplier<List<String>> toolNames,
                  ReplTranscriptSink sink,
                  Consumer<String> promptSubmit,
                  TaskRegistry taskRegistry,
                  Consumer<TaskState> viewTask) {
        this.gui = gui;
        this.inputPanel = inputPanel;
        this.commandContext = commandContext;
        this.toolNames = toolNames != null ? toolNames : List::of;
        this.sink = sink;
        this.promptSubmit = promptSubmit != null ? promptSubmit : _ -> {};
        this.taskRegistry = taskRegistry;
        this.viewTask = viewTask != null ? viewTask : _ -> {};
        this.panel = new AgentsPanel(memoryCatalog, taskRegistry);
    }

    public List<InlineOverlay> overlays() { return List.of(panel); }
    public Component view() { return panel; }

    public void setKeybindingsStore(UserKeybindingsStore store) {
        panel.setKeybindingsStore(store);
    }

    @Override
    public void openAgents() {
        if (gui == null) return;
        long generation = loadGeneration.incrementAndGet();
        gui.getGUIThread().invokeLater(() -> inputPanel.setSuppressed(true));
        Thread.ofVirtual().name("agents-dialog-load").start(() -> {
            String cwd = commandContext.session().workingDirectory();
            AgentsPanel.Inventory loadedInventory;
            List<String> loadedTools;
            try {
                loadedInventory = AgentsPanel.loadInventory(cwd);
                loadedTools = toolNames.get();
            } catch (RuntimeException _) {
                loadedInventory = new AgentsPanel.Inventory(List.of(), List.of());
                loadedTools = List.of();
            }
            AgentsPanel.Inventory inventory = loadedInventory;
            List<String> availableTools = loadedTools;
            gui.getGUIThread().invokeLater(() -> {
                if (loadGeneration.get() != generation) return;
                List<ChangeLine> changeLog = new ArrayList<>();
                panel.show(
                    commandContext.session()::workingDirectory,
                    commandContext.session().sideQuestionRunner(),
                    availableTools,
                    inventory,
                    promptSubmit,
                    taskId -> {
                        if (taskRegistry != null) {
                            taskRegistry.store().get(taskId).ifPresent(this.viewTask);
                        }
                    },
                    (text, color) -> changeLog.add(new ChangeLine(text, color)),
                    commandContext.presentation().openEditor(),
                    () -> {
                        inputPanel.setSuppressed(false);
                        flushChangeLog(changeLog);
                    });
            });
        });
    }

    private void flushChangeLog(List<ChangeLine> changeLog) {
        if (changeLog.isEmpty()) return;
        sink.line("", TextColor.ANSI.DEFAULT);
        for (ChangeLine line : changeLog) sink.line("  " + line.text(), line.color());
    }
}
