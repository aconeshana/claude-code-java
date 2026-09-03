package com.claudecode.ui.lanterna.features.agents;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.runtime.memory.MemoryCatalog;
import com.claudecode.tools.agent.AgentDefinitionLoader;
import com.claudecode.tools.agent.AgentDisplay;
import com.claudecode.tools.agent.AgentFileWriter;
import com.claudecode.tools.agent.AgentModelOptions;
import com.claudecode.core.agent.AgentSource;
import com.claudecode.tools.agent.AgentToolResolver;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskType;
import com.claudecode.core.agent.BuiltInAgentDefinitions;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Interactable;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.PasteKeyStroke;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import com.claudecode.ui.lanterna.input.ContextKeybindingDispatcher;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * Inline {@code /agents} agent-management panel — sits above {@link InputPanel} in the SmartLayout
 * stack, occupying zero rows when idle.
 */
final class AgentsPanel extends Panel implements InlineOverlay {

    private static final Logger log = LoggerFactory.getLogger(AgentsPanel.class);

    enum Mode { LIST, AGENT_MENU, RUN, VIEW, EDIT_MENU, EDIT_TOOLS, EDIT_MODEL, EDIT_COLOR, DELETE_CONFIRM, CREATE }
    enum Tab { ACTIVE, LIBRARY }

    private sealed interface Row permits CreateRow, Header, AgentRow {}
    private record CreateRow() implements Row {}
    private record Header(String label) implements Row {}
    private record AgentRow(AgentDisplay.ResolvedAgent resolved) implements Row {}
    private record DetailLine(String text, TextColor color, boolean bold) {}

    record Inventory(
        List<BuiltInAgentDefinitions.AgentDefinition> all,
        List<BuiltInAgentDefinitions.AgentDefinition> active) {
        Inventory {
            all = all == null ? List.of() : List.copyOf(all);
            active = active == null ? List.of() : List.copyOf(active);
        }
    }

    private static final List<String> AGENT_MENU_FULL = List.of("Run agent", "View agent", "Edit agent", "Delete agent", "Back");
    private static final List<String> AGENT_MENU_READ_ONLY = List.of("Run agent", "View agent", "Back");
    private static final List<String> EDIT_MENU = List.of("Open in editor", "Edit tools", "Edit model", "Edit color");

    private static final int LEFT_PAD = 2;

    private boolean active;
    private Mode mode = Mode.LIST;
    private Tab tab = Tab.ACTIVE;
    private boolean headerFocused = true;

    private Supplier<String> cwdSupplier;
    private Function<String, String> sideQuestionRunner;
    private List<String> availableToolNames = List.of();
    private BiConsumer<String, TextColor> changeRecorder;
    private Consumer<String> runAgent;
    private Consumer<String> viewTask;
    private Consumer<Path> openEditor;
    private Runnable onClose;

    private List<BuiltInAgentDefinitions.AgentDefinition> allAgents = List.of();
    private List<AgentDisplay.ResolvedAgent> resolvedAgents = List.of();
    private int selectedIndex;
    private int libraryVisibleFromIndex;
    private int libraryViewportSize = 5;

    private BuiltInAgentDefinitions.AgentDefinition selectedAgent;
    private int agentMenuIdx;
    private int editMenuIdx;
    private int deleteConfirmIdx;
    private int activeTaskIndex;
    private final StringBuilder runPrompt = new StringBuilder();
    private int runCursor;
    private String editError;

    private final AgentToolsPicker toolsPicker = new AgentToolsPicker();
    private final AgentModelPicker modelPicker = new AgentModelPicker();
    private final AgentColorPicker colorPicker = new AgentColorPicker();
    private final AgentCreateWizard createWizard;
    private final MemoryCatalog memoryCatalog;
    private final TaskRegistry taskRegistry;
    private final ContextKeybindingDispatcher keybindings = new ContextKeybindingDispatcher();
    private final ScheduledExecutorService refreshTimer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "agents-panel-refresh");
        thread.setDaemon(true);
        return thread;
    });
    private ScheduledFuture<?> refreshFuture;

    AgentsPanel() {
        this(MemoryCatalog.empty(), null);
    }

    AgentsPanel(MemoryCatalog memoryCatalog) {
        this(memoryCatalog, null);
    }

    AgentsPanel(MemoryCatalog memoryCatalog, TaskRegistry taskRegistry) {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        this.memoryCatalog = memoryCatalog != null ? memoryCatalog : MemoryCatalog.empty();
        this.taskRegistry = taskRegistry;
        this.createWizard = new AgentCreateWizard(this.memoryCatalog);
        Area area = new Area();
        area.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(area);
        for (Panel p : new Panel[] {toolsPicker, modelPicker, colorPicker, createWizard}) {
            p.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
            addComponent(p);
        }
    }

    void setKeybindingsStore(UserKeybindingsStore store) {
        keybindings.setStore(store);
        toolsPicker.setKeybindingsStore(store);
        modelPicker.setKeybindingsStore(store);
        colorPicker.setKeybindingsStore(store);
        createWizard.setKeybindingsStore(store);
    }

    /**
     * Activates the panel.
     *
     * @param cwdSupplier          working directory, used to resolve custom agent dirs
     * @param sideQuestionRunner   LLM round-trip for the create wizard's Generate step
     * @param availableToolNames   every tool name the agent could possibly use
     * @param changeRecorder       one {@code (line, color)} call per create/update/delete
     * @param openEditor           opens a file in the user's $EDITOR (the panel's
     *                             "Open in editor" quick-edit action); {@code null} disables it
     * @param onClose              close callback, no arguments
     */
    synchronized void show(Supplier<String> cwdSupplier, Function<String, String> sideQuestionRunner,
            List<String> availableToolNames, BiConsumer<String, TextColor> changeRecorder,
            Consumer<Path> openEditor, Runnable onClose) {
        String cwd = cwdSupplier != null ? cwdSupplier.get() : null;
        show(cwdSupplier, sideQuestionRunner, availableToolNames, loadInventory(cwd),
            _ -> {}, _ -> {}, changeRecorder, openEditor, onClose);
    }

    synchronized void show(Supplier<String> cwdSupplier, Function<String, String> sideQuestionRunner,
            List<String> availableToolNames, Inventory inventory,
            BiConsumer<String, TextColor> changeRecorder,
            Consumer<Path> openEditor, Runnable onClose) {
        show(cwdSupplier, sideQuestionRunner, availableToolNames, inventory, _ -> {},
            _ -> {}, changeRecorder, openEditor, onClose);
    }

    synchronized void show(Supplier<String> cwdSupplier, Function<String, String> sideQuestionRunner,
            List<String> availableToolNames, Inventory inventory, Consumer<String> runAgent,
            BiConsumer<String, TextColor> changeRecorder,
            Consumer<Path> openEditor, Runnable onClose) {
        show(cwdSupplier, sideQuestionRunner, availableToolNames, inventory, runAgent, _ -> {},
            changeRecorder, openEditor, onClose);
    }

    synchronized void show(Supplier<String> cwdSupplier, Function<String, String> sideQuestionRunner,
            List<String> availableToolNames, Inventory inventory, Consumer<String> runAgent,
            Consumer<String> viewTask,
            BiConsumer<String, TextColor> changeRecorder,
            Consumer<Path> openEditor, Runnable onClose) {
        this.cwdSupplier = cwdSupplier;
        this.sideQuestionRunner = sideQuestionRunner;
        this.availableToolNames = availableToolNames == null ? List.of() : List.copyOf(availableToolNames);
        this.changeRecorder = changeRecorder;
        this.runAgent = runAgent != null ? runAgent : _ -> {};
        this.viewTask = viewTask != null ? viewTask : _ -> {};
        this.openEditor = openEditor;
        this.onClose = onClose;
        this.active = true;
        applyInventory(inventory);
        tab = Tab.ACTIVE;
        headerFocused = true;
        activeTaskIndex = 0;
        startRefreshTimer();
    }

    static Inventory loadInventory(String cwd) {
        return new Inventory(
            AgentDefinitionLoader.getAll(cwd),
            AgentDefinitionLoader.getActive(cwd));
    }

    private void applyInventory(Inventory inventory) {
        Inventory snapshot = inventory != null ? inventory : new Inventory(List.of(), List.of());
        allAgents = snapshot.all();
        resolvedAgents = AgentDisplay.resolveOverrides(snapshot.all(), snapshot.active());
        mode = Mode.LIST;
        headerFocused = true;
        selectedIndex = 0;
        libraryVisibleFromIndex = 0;
        invalidate();
    }

    /** Re-reads live agent definitions and resets to LIST mode. */
    private void reload() {
        String cwd = cwdSupplier != null ? cwdSupplier.get() : null;
        applyInventory(loadInventory(cwd));
    }

    private void closePanel() {
        active = false;
        stopRefreshTimer();
        invalidate();
        Runnable cb = onClose;
        onClose = null;
        if (cb != null) cb.run();
    }

    @Override public boolean isActive() { return active; }

    private void startRefreshTimer() {
        stopRefreshTimer();
        refreshFuture = refreshTimer.scheduleAtFixedRate(this::invalidate, 1, 1, TimeUnit.SECONDS);
    }

    private void stopRefreshTimer() {
        if (refreshFuture != null) {
            refreshFuture.cancel(false);
            refreshFuture = null;
        }
    }

    private List<TaskState> activeTasks() {
        if (taskRegistry == null) return List.of();
        List<TaskState> all = taskRegistry.store().list().stream()
            .filter(task -> task.type() == TaskType.LOCAL_AGENT)
            .filter(task -> !TaskRegistry.MAIN_SESSION_AGENT_TYPE.equals(
                taskRegistry.store().agentType(task.id()).orElse(null)))
            .toList();
        List<TaskState> running = all.stream()
            .filter(task -> !task.status().hasResult())
            .sorted(Comparator.comparing(TaskState::startTime))
            .toList();
        List<TaskState> completed = all.stream()
            .filter(task -> task.status().hasResult())
            .sorted(Comparator.comparing((TaskState task) -> task.endTime().orElse(Instant.EPOCH)).reversed())
            .limit(5)
            .toList();
        List<TaskState> result = new ArrayList<>(running.size() + completed.size());
        result.addAll(running);
        result.addAll(completed);
        return List.copyOf(result);
    }

    private long runningAgentCount() {
        return activeTasks().stream().filter(task -> !task.status().hasResult()).count();
    }

    // ── rows ─────────────────────────────────────────────────────────────────

    private List<Row> buildRows() {
        List<Row> rows = new ArrayList<>();
        rows.add(new CreateRow());

        for (AgentSource src : List.of(AgentSource.USER, AgentSource.PROJECT, AgentSource.MANAGED,
                AgentSource.PLUGIN, AgentSource.FLAG_SETTINGS, AgentSource.BUILT_IN)) {
            List<AgentDisplay.ResolvedAgent> group = resolvedAgents.stream()
                .filter(r -> r.agent().source() == src)
                .sorted((a, b) -> AgentDisplay.compareByName(a.agent(), b.agent()))
                .toList();
            if (group.isEmpty()) continue;
            rows.add(new Header(headerLabel(src)));
            for (AgentDisplay.ResolvedAgent r : group) rows.add(new AgentRow(r));
        }
        return rows;
    }

    private static String headerLabel(AgentSource src) {
        return switch (src) {
            case BUILT_IN -> "Built-in (always available):";
            case MANAGED -> "Managed agents";
            case USER -> "User agents";
            case PROJECT -> "Project agents";
            case FLAG_SETTINGS -> "CLI arg agents";
            case PLUGIN -> "Plugin agents";
        };
    }

    private static String headerLabel(AgentSource source,
            BuiltInAgentDefinitions.AgentDefinition firstAgent) {
        String label = headerLabel(source);
        Path filePath = firstAgent.filePath();
        Path baseDirectory = filePath != null ? filePath.getParent() : null;
        return baseDirectory != null ? label + " (" + baseDirectory + ")" : label;
    }

    private List<Row> selectableRows(List<Row> rows) {
        return rows.stream()
            .filter(r -> !(r instanceof Header))
            .filter(r -> !(r instanceof AgentRow(var resolved)) || !resolved.agent().isBuiltIn())
            .toList();
    }

    // ── key handling ─────────────────────────────────────────────────────────

    @Override
    public synchronized void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!active) return;
        switch (mode) {
            case LIST -> handleListKey(key, deliver);
            case AGENT_MENU -> handleAgentMenuKey(key, deliver);
            case RUN -> handleRunKey(key, deliver);
            case VIEW -> handleViewKey(key, deliver);
            case EDIT_MENU -> handleEditMenuKey(key, deliver);
            case EDIT_TOOLS -> toolsPicker.handleKey(key, deliver);
            case EDIT_MODEL -> modelPicker.handleKey(key, deliver);
            case EDIT_COLOR -> colorPicker.handleKey(key, deliver);
            case DELETE_CONFIRM -> handleDeleteConfirmKey(key, deliver);
            case CREATE -> createWizard.handleKey(key, deliver);
        }
    }

    private void handleListKey(KeyStroke key, AtomicBoolean deliver) {
        KeyType t = key.getKeyType();
        deliver.set(false);
        ContextKeybindingDispatcher.Result bindingResult =
            keybindings.resolve(List.of("Tabs", "Confirmation"), key);
        if (bindingResult instanceof ContextKeybindingDispatcher.Result.Consumed) return;
        if (bindingResult instanceof ContextKeybindingDispatcher.Result.Action(String action)) {
            boolean handled = switch (action) {
                case "tabs:next" -> { switchTab(1); yield true; }
                case "tabs:previous" -> { switchTab(-1); yield true; }
                case "confirm:no" -> { closePanel(); yield true; }
                default -> false;
            };
            if (handled) return;
        }
        if (t == KeyType.ESCAPE) { closePanel(); return; }
        if (t == KeyType.ARROW_LEFT) { switchTab(-1); return; }
        if (t == KeyType.ARROW_RIGHT) { switchTab(1); return; }
        if (headerFocused) {
            if (t == KeyType.ARROW_DOWN) {
                headerFocused = false;
                invalidate();
            }
            return;
        }
        if (tab == Tab.ACTIVE) {
            handleActiveKey(key);
            return;
        }
        List<Row> selectable = selectableRows(buildRows());
        if (t == KeyType.ARROW_UP) {
            if (selectedIndex == 0) headerFocused = true;
            else {
                selectedIndex--;
                keepLibrarySelectionVisible();
            }
            invalidate();
        } else if (t == KeyType.ARROW_DOWN) {
            selectedIndex = Math.min(Math.max(0, selectable.size() - 1), selectedIndex + 1);
            keepLibrarySelectionVisible();
            invalidate();
        } else if (t == KeyType.ENTER) {
            Row row = selectable.get(Math.min(selectedIndex, selectable.size() - 1));
            if (row instanceof CreateRow) {
                mode = Mode.CREATE;
                createWizard.activate(cwdSupplier != null ? cwdSupplier.get() : null, allAgents, availableToolNames,
                    sideQuestionRunner, openEditor, this::onAgentCreated, this::backToList);
                invalidate();
            } else if (row instanceof AgentRow(var resolved)) {
                selectedAgent = resolved.agent();
                agentMenuIdx = 0;
                mode = Mode.AGENT_MENU;
                invalidate();
            }
        }
    }

    private void keepLibrarySelectionVisible() {
        if (selectedIndex <= 0) {
            libraryVisibleFromIndex = 0;
            return;
        }
        int customIndex = selectedIndex - 1;
        if (customIndex < libraryVisibleFromIndex) {
            libraryVisibleFromIndex = customIndex;
        } else if (customIndex >= libraryVisibleFromIndex + libraryViewportSize) {
            libraryVisibleFromIndex = customIndex - libraryViewportSize + 1;
        }
    }

    private void switchTab(int delta) {
        Tab[] tabs = Tab.values();
        tab = tabs[InlineOverlay.cycleIndex(tab.ordinal(), delta, tabs.length)];
        headerFocused = true;
        invalidate();
    }

    private void handleActiveKey(KeyStroke key) {
        List<TaskState> tasks = activeTasks();
        activeTaskIndex = tasks.isEmpty() ? 0 : Math.min(activeTaskIndex, tasks.size() - 1);
        switch (key.getKeyType()) {
            case ARROW_UP -> {
                if (activeTaskIndex == 0) headerFocused = true;
                else activeTaskIndex--;
                invalidate();
            }
            case ARROW_DOWN -> {
                activeTaskIndex = Math.min(Math.max(0, tasks.size() - 1), activeTaskIndex + 1);
                invalidate();
            }
            case ENTER -> {
                if (tasks.isEmpty()) return;
                String taskId = tasks.get(activeTaskIndex).id();
                Consumer<String> viewer = viewTask;
                closePanel();
                viewer.accept(taskId);
            }
            case CHARACTER -> {
                if (tasks.isEmpty() || key.getCharacter() == null || key.getCharacter() != 'x'
                        || key.isCtrlDown() || key.isAltDown()) return;
                TaskState selected = tasks.get(activeTaskIndex);
                if (selected.status() == TaskStatus.RUNNING && taskRegistry != null) {
                    taskRegistry.killAgentByUser(selected.id());
                    invalidate();
                }
            }
            default -> { }
        }
    }

    private void onAgentCreated(String agentType) {
        if (changeRecorder != null) changeRecorder.accept("Created agent: " + agentType, LanternaTheme.inputText());
        reload();
    }

    private void backToList() {
        mode = Mode.LIST;
        headerFocused = true;
        invalidate();
    }

    private List<String> currentAgentMenu() {
        // Plugin agents are read-only like built-ins: their files live inside

        // only user/project agents get the edit/delete affordances.
        boolean readOnly = selectedAgent.isBuiltIn()
            || selectedAgent.source() == AgentSource.PLUGIN
            || selectedAgent.source() == AgentSource.FLAG_SETTINGS;
        List<String> base = readOnly ? AGENT_MENU_READ_ONLY : AGENT_MENU_FULL;
        if (runningTasksForSelectedAgent().isEmpty()) return base;
        List<String> withRunning = new ArrayList<>(base);
        withRunning.add(1, "View running instance");
        return List.copyOf(withRunning);
    }

    private List<TaskState> runningTasksForSelectedAgent() {
        if (taskRegistry == null || selectedAgent == null) return List.of();
        return activeTasks().stream()
            .filter(task -> !task.status().hasResult())
            .filter(task -> Strings.CS.equals(selectedAgent.agentType(),
                taskRegistry.store().agentType(task.id()).orElse(null)))
            .toList();
    }

    private void handleAgentMenuKey(KeyStroke key, AtomicBoolean deliver) {
        KeyType t = key.getKeyType();
        deliver.set(false);
        List<String> menu = currentAgentMenu();
        if (dispatchSelectBinding(key, deliver,
                () -> { agentMenuIdx = InlineOverlay.cycleIndex(agentMenuIdx, -1, menu.size()); invalidate(); },
                () -> { agentMenuIdx = InlineOverlay.cycleIndex(agentMenuIdx, 1, menu.size()); invalidate(); },
                () -> activateAgentMenuChoice(menu.get(agentMenuIdx)),
                this::backToList)) return;
        if (t == KeyType.ARROW_UP) { agentMenuIdx = InlineOverlay.cycleIndex(agentMenuIdx, -1, menu.size()); invalidate(); }
        else if (t == KeyType.ARROW_DOWN) { agentMenuIdx = InlineOverlay.cycleIndex(agentMenuIdx, 1, menu.size()); invalidate(); }
        else if (t == KeyType.ESCAPE) { backToList(); }
        else if (t == KeyType.ENTER) {
            activateAgentMenuChoice(menu.get(agentMenuIdx));
        }
    }

    private void activateAgentMenuChoice(String choice) {
        switch (choice) {
            case "Run agent" -> {
                runPrompt.setLength(0);
                runCursor = 0;
                mode = Mode.RUN;
                invalidate();
            }
            case "View running instance" -> {
                List<TaskState> running = runningTasksForSelectedAgent();
                if (running.isEmpty()) return;
                Consumer<String> viewer = viewTask;
                closePanel();
                viewer.accept(running.getFirst().id());
            }
            case "View agent" -> { mode = Mode.VIEW; invalidate(); }
            case "Edit agent" -> { editMenuIdx = 0; mode = Mode.EDIT_MENU; invalidate(); }
            case "Delete agent" -> { deleteConfirmIdx = 0; mode = Mode.DELETE_CONFIRM; invalidate(); }
            case "Back" -> backToList();
            default -> { /* unreachable */ }
        }
    }

    private void handleRunKey(KeyStroke key, AtomicBoolean deliver) {
        deliver.set(false);
        KeyType type = key.getKeyType();
        if (type == KeyType.ESCAPE) {
            mode = Mode.AGENT_MENU;
            invalidate();
            return;
        }
        if (type == KeyType.ENTER) {
            String prompt = runPrompt.toString().trim();
            if (prompt.isEmpty()) return;
            String submission = "@agent-" + selectedAgent.agentType() + " " + prompt;
            Consumer<String> submit = runAgent;
            closePanel();
            submit.accept(submission);
            return;
        }
        if (type == KeyType.BACKSPACE) {
            if (runCursor > 0) {
                runPrompt.deleteCharAt(runCursor - 1);
                runCursor--;
            }
            invalidate();
            return;
        }
        if (type == KeyType.DELETE) {
            if (runCursor < runPrompt.length()) runPrompt.deleteCharAt(runCursor);
            invalidate();
            return;
        }
        if (type == KeyType.ARROW_LEFT) {
            runCursor = Math.max(0, runCursor - 1);
            invalidate();
            return;
        }
        if (type == KeyType.ARROW_RIGHT) {
            runCursor = Math.min(runPrompt.length(), runCursor + 1);
            invalidate();
            return;
        }
        if (type == KeyType.HOME) {
            runCursor = 0;
            invalidate();
            return;
        }
        if (type == KeyType.END) {
            runCursor = runPrompt.length();
            invalidate();
            return;
        }
        if (type == KeyType.PASTE && key instanceof PasteKeyStroke paste) {
            String pasted = paste.getPastedText();
            if (StringUtils.isNotEmpty(pasted)) {
                String normalized = pasted.replace("\r\n", " ")
                    .replace('\n', ' ').replace('\r', ' ');
                runPrompt.insert(runCursor, normalized);
                runCursor += normalized.length();
                invalidate();
            }
            return;
        }
        if (type == KeyType.CHARACTER && key.isCtrlDown() && key.getCharacter() != null) {
            switch (Character.toLowerCase(key.getCharacter())) {
                case 'a' -> runCursor = 0;
                case 'e' -> runCursor = runPrompt.length();
                case 'u' -> {
                    runPrompt.delete(0, runCursor);
                    runCursor = 0;
                }
                case 'k' -> runPrompt.delete(runCursor, runPrompt.length());
                default -> { return; }
            }
            invalidate();
            return;
        }
        if (type == KeyType.CHARACTER && key.getCharacter() != null
                && !key.isCtrlDown() && !key.isAltDown()) {
            runPrompt.insert(runCursor++, key.getCharacter());
            invalidate();
        }
    }

    private void handleViewKey(KeyStroke key, AtomicBoolean deliver) {
        KeyType t = key.getKeyType();
        deliver.set(false);
        if (dispatchActionBinding(key, deliver, List.of("Confirmation"), action -> {
                if (!Strings.CS.equals("confirm:no", action)) return false;
                mode = Mode.AGENT_MENU;
                invalidate();
                return true;
            })) return;
        if (t == KeyType.ENTER || t == KeyType.ESCAPE) { mode = Mode.AGENT_MENU; invalidate(); }
    }

    private void handleEditMenuKey(KeyStroke key, AtomicBoolean deliver) {
        KeyType t = key.getKeyType();
        deliver.set(false);
        if (dispatchSelectBinding(key, deliver,
                () -> moveEditMenu(-1),
                () -> moveEditMenu(1),
                () -> activateEditMenuChoice(EDIT_MENU.get(editMenuIdx)),
                () -> { mode = Mode.AGENT_MENU; invalidate(); })) return;
        if (dispatchActionBinding(key, deliver, List.of("Confirmation"), action -> {
                if (!Strings.CS.equals("confirm:no", action)) return false;
                mode = Mode.AGENT_MENU;
                invalidate();
                return true;
            })) return;
        if (t == KeyType.ARROW_UP) moveEditMenu(-1);
        else if (t == KeyType.ARROW_DOWN) moveEditMenu(1);
        else if (t == KeyType.ESCAPE) { mode = Mode.AGENT_MENU; invalidate(); }
        else if (t == KeyType.ENTER) activateEditMenuChoice(EDIT_MENU.get(editMenuIdx));
    }

    private void moveEditMenu(int delta) {
        editMenuIdx = Math.clamp(editMenuIdx + delta, 0, EDIT_MENU.size() - 1);
        invalidate();
    }

    private void activateEditMenuChoice(String choice) {
            switch (choice) {
                case "Open in editor" -> {
                    if (openEditor != null) openEditor.accept(AgentFileWriter.actualFilePath(selectedAgent));
                    mode = Mode.AGENT_MENU;
                    invalidate();
                }
                case "Edit tools" -> {
                    mode = Mode.EDIT_TOOLS;
                    toolsPicker.activate(selectedAgent.tools(), availableToolNames,
                        this::applyToolsEdit, this::backToEditMenu,
                        "Edit agent: " + selectedAgent.agentType(), null, false,
                        LanternaTheme.permission());
                }
                case "Edit model" -> {
                    mode = Mode.EDIT_MODEL;
                    modelPicker.activate(selectedAgent.model(), this::applyModelEdit,
                        this::backToEditMenu, "Edit agent: " + selectedAgent.agentType(),
                        null, false, LanternaTheme.permission());
                }
                case "Edit color" -> {
                    mode = Mode.EDIT_COLOR;
                    colorPicker.activate(selectedAgent.color(), selectedAgent.agentType(),
                        this::applyColorEdit, this::backToEditMenu,
                        "Edit agent: " + selectedAgent.agentType(), null, false,
                        LanternaTheme.permission());
                }
                default -> { /* unreachable */ }
            }
    }

    private void backToEditMenu() {
        mode = Mode.EDIT_MENU;
        invalidate();
    }

    private void applyToolsEdit(List<String> tools) {
        persistUpdate(selectedAgent.whenToUse(), tools, selectedAgent.systemPrompt(),
            selectedAgent.color(), selectedAgent.model(), selectedAgent.memory());
    }

    private void applyModelEdit(String model) {
        persistUpdate(selectedAgent.whenToUse(), selectedAgent.tools(), selectedAgent.systemPrompt(),
            selectedAgent.color(), model, selectedAgent.memory());
    }

    private void applyColorEdit(String color) {
        persistUpdate(selectedAgent.whenToUse(), selectedAgent.tools(), selectedAgent.systemPrompt(),
            color, selectedAgent.model(), selectedAgent.memory());
    }

    private void persistUpdate(String whenToUse, List<String> tools, String systemPrompt,
            String color, String model, String memory) {
        if (Objects.equals(selectedAgent.whenToUse(), whenToUse)
                && Objects.equals(selectedAgent.tools(), tools)
                && Objects.equals(selectedAgent.systemPrompt(), systemPrompt)
                && Objects.equals(selectedAgent.color(), color)
                && Objects.equals(selectedAgent.model(), model)
                && Objects.equals(selectedAgent.memory(), memory)) {
            mode = Mode.EDIT_MENU;
            editError = null;
            invalidate();
            return;
        }
        try {
            AgentFileWriter.update(selectedAgent, whenToUse, tools, systemPrompt, color, model, memory);
            AgentDefinitionLoader.clearCache();
            if (changeRecorder != null) {
                changeRecorder.accept("Updated agent: " + selectedAgent.agentType(), LanternaTheme.inputText());
            }
            reloadToAgentMenu();
        } catch (Exception failure) {
            editError = failure.getMessage() != null ? failure.getMessage() : "Failed to save agent";
            mode = Mode.EDIT_MENU;
            invalidate();
        }
    }

    private void reloadToAgentMenu() {
        String agentType = selectedAgent.agentType();
        AgentSource source = selectedAgent.source();
        String cwd = cwdSupplier != null ? cwdSupplier.get() : null;
        Inventory snapshot = loadInventory(cwd);
        allAgents = snapshot.all();
        resolvedAgents = AgentDisplay.resolveOverrides(snapshot.all(), snapshot.active());
        selectedAgent = allAgents.stream()
            .filter(agent -> Strings.CS.equals(agent.agentType(), agentType) && agent.source() == source)
            .findFirst()
            .orElse(selectedAgent);
        agentMenuIdx = 0;
        editError = null;
        mode = Mode.AGENT_MENU;
        invalidate();
    }

    private void handleDeleteConfirmKey(KeyStroke key, AtomicBoolean deliver) {
        KeyType t = key.getKeyType();
        deliver.set(false);
        if (dispatchSelectBinding(key, deliver,
                () -> { deleteConfirmIdx = InlineOverlay.cycleIndex(deleteConfirmIdx, -1, 2); invalidate(); },
                () -> { deleteConfirmIdx = InlineOverlay.cycleIndex(deleteConfirmIdx, 1, 2); invalidate(); },
                this::activateDeleteChoice,
                () -> { mode = Mode.AGENT_MENU; invalidate(); })) return;
        if (t == KeyType.ARROW_UP) { deleteConfirmIdx = InlineOverlay.cycleIndex(deleteConfirmIdx, -1, 2); invalidate(); }
        else if (t == KeyType.ARROW_DOWN) { deleteConfirmIdx = InlineOverlay.cycleIndex(deleteConfirmIdx, 1, 2); invalidate(); }
        else if (t == KeyType.ENTER) activateDeleteChoice();
        else if (t == KeyType.ESCAPE) { mode = Mode.AGENT_MENU; invalidate(); }
    }

    private void activateDeleteChoice() {
        if (deleteConfirmIdx != 0) {
            mode = Mode.AGENT_MENU;
            invalidate();
            return;
        }
        try {
            AgentFileWriter.delete(selectedAgent);
            AgentDefinitionLoader.clearCache();
            if (changeRecorder != null) {
                changeRecorder.accept("Deleted agent: " + selectedAgent.agentType(), LanternaTheme.inputText());
            }
            reload();
        } catch (Exception deleteFailure) {

            // intact instead of reporting a deletion that did not happen.
            log.warn("Failed to delete agent '{}'", selectedAgent.agentType(), deleteFailure);
        }
    }

    private boolean dispatchSelectBinding(KeyStroke key, AtomicBoolean deliver,
            Runnable previous, Runnable next, Runnable accept, Runnable cancel) {
        return dispatchActionBinding(key, deliver, List.of("Select", "Confirmation"), action -> switch (action) {
            case "select:previous" -> { previous.run(); yield true; }
            case "select:next" -> { next.run(); yield true; }
            case "select:accept" -> { accept.run(); yield true; }
            case "select:cancel", "confirm:no" -> { cancel.run(); yield true; }
            default -> false;
        });
    }

    private boolean dispatchActionBinding(KeyStroke key, AtomicBoolean deliver,
            List<String> contexts, Function<String, Boolean> handler) {
        ContextKeybindingDispatcher.Result resolved = keybindings.resolve(contexts, key);
        if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) {
            deliver.set(false);
            return true;
        }
        if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String value)
                && Boolean.TRUE.equals(handler.apply(value))) {
            deliver.set(false);
            return true;
        }
        return false;
    }

    // ── sizing ───────────────────────────────────────────────────────────────

    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        if (!active) return new TerminalSize(0, 0);
        return super.calculatePreferredSize();
    }

    @Override public Interactable nextFocus(Interactable fromThis) { return active ? super.nextFocus(fromThis) : null; }
    @Override public Interactable previousFocus(Interactable fromThis) { return active ? super.previousFocus(fromThis) : null; }

    // ── Test accessors (package-private) ────────────────────────────────────

    Mode mode() { return mode; }
    Tab tab() { return tab; }
    boolean headerFocusedForTest() { return headerFocused; }
    int activeTaskIndexForTest() { return activeTaskIndex; }
    int librarySelectionIndexForTest() { return selectedIndex; }
    BuiltInAgentDefinitions.AgentDefinition selectedAgent() { return selectedAgent; }
    List<String> listedAgentTypes() {
        return buildRows().stream()
            .filter(AgentRow.class::isInstance)
            .map(r -> ((AgentRow) r).resolved().agent().agentType())
            .toList();
    }
    List<String> selectableAgentTypes() {
        return selectableRows(buildRows()).stream()
            .filter(AgentRow.class::isInstance)
            .map(r -> ((AgentRow) r).resolved().agent().agentType())
            .toList();
    }
    List<String> activeTaskIds() { return activeTasks().stream().map(TaskState::id).toList(); }
    AgentToolsPicker toolsPicker() { return toolsPicker; }
    AgentModelPicker modelPicker() { return modelPicker; }
    AgentColorPicker colorPicker() { return colorPicker; }
    AgentCreateWizard createWizard() { return createWizard; }

    // ──────────────────────────────────────────────────────────────────────────

    private boolean stepAreaVisible() {
        return active && mode != Mode.EDIT_TOOLS && mode != Mode.EDIT_MODEL
            && mode != Mode.EDIT_COLOR && mode != Mode.CREATE;
    }

    private final class Area extends AbstractComponent<Area> {
        @Override protected ComponentRenderer<Area> createDefaultRenderer() { return new Renderer(); }
    }

    private final class Renderer implements ComponentRenderer<Area> {

        @Override
        public TerminalSize getPreferredSize(Area c) {
            if (!stepAreaVisible()) return new TerminalSize(0, 0);
            return new TerminalSize(76, totalRows());
        }

        private int totalRows() {
            List<Row> rows = buildRows();
            return switch (mode) {
                case LIST -> tab == Tab.ACTIVE ? activeRows() + 7
                    : 4 + rows.size() + (rows.size() == 1 ? 4 : 0) + 2;
                case AGENT_MENU -> 4 + currentAgentMenu().size() + 2;
                case RUN -> 9;
                case VIEW -> detailLines(72).size() + 4;
                case EDIT_MENU -> 4 + EDIT_MENU.size() + 2 + (editError != null ? 2 : 0);
                case DELETE_CONFIRM -> 10;
                default -> 0;
            };
        }

        private int activeRows() {
            List<TaskState> tasks = activeTasks();
            boolean hasRunning = tasks.stream().anyMatch(task -> !task.status().hasResult());
            boolean hasCompleted = tasks.stream().anyMatch(task -> task.status().hasResult());
            return tasks.size() + (hasCompleted && hasRunning ? 1 : 0);
        }

        @Override
        public void drawComponent(TextGUIGraphics g, Area c) {
            if (!stepAreaVisible()) return;
            g.fill(' ');
            g.setForegroundColor(LanternaTheme.remember());
            g.enableModifiers(SGR.BOLD);
            g.putString(LEFT_PAD, 0, panelTitle());
            g.disableModifiers(SGR.BOLD);

            switch (mode) {
                case LIST -> {
                    drawTabs(g);
                    if (tab == Tab.ACTIVE) drawActive(g); else drawList(g);
                }
                case AGENT_MENU -> drawAgentMenu(g);
                case RUN -> drawRun(g);
                case VIEW -> drawView(g);
                case EDIT_MENU -> drawEditMenu(g);
                case DELETE_CONFIRM -> drawDeleteConfirm(g);
                default -> { /* CREATE/EDIT_* delegate to their own components */ }
            }
        }

        private String panelTitle() {
            if (selectedAgent == null) return "Agents";
            return switch (mode) {
                case AGENT_MENU, VIEW -> selectedAgent.agentType();
                case RUN -> "Run " + selectedAgent.agentType();
                case EDIT_MENU -> "Edit agent: " + selectedAgent.agentType();
                case DELETE_CONFIRM -> "Delete agent";
                default -> "Agents";
            };
        }

        private void drawTabs(TextGUIGraphics g) {
            String running = runningAgentCount() > 0 ? "Running (" + runningAgentCount() + ")" : "Running";
            int column = LEFT_PAD;
            for (Tab candidate : Tab.values()) {
                boolean current = candidate == tab;
                String label = " " + (candidate == Tab.ACTIVE ? running : "Library") + " ";
                if (current && headerFocused) {
                    g.setBackgroundColor(LanternaTheme.permission());
                    g.setForegroundColor(LanternaTheme.inverseText());
                } else {
                    g.setBackgroundColor(TextColor.ANSI.DEFAULT);
                    g.setForegroundColor(current
                        ? LanternaTheme.inputText() : LanternaTheme.welcomeDim());
                }
                if (current) g.enableModifiers(SGR.BOLD);
                if (current && !headerFocused) g.enableModifiers(SGR.REVERSE);
                g.putString(column, 2, label);
                g.disableModifiers(SGR.BOLD, SGR.REVERSE);
                column += label.length() + 1;
            }
            g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        }

        private void drawActive(TextGUIGraphics g) {
            List<TaskState> tasks = activeTasks();
            int row = 4;
            if (tasks.isEmpty()) {
                g.setForegroundColor(LanternaTheme.welcomeDim());
                g.putString(LEFT_PAD, row, "No subagents are currently running.");
            } else {
                boolean completedHeaderDrawn = false;
                for (int i = 0; i < tasks.size(); i++) {
                    TaskState task = tasks.get(i);
                    if (task.status().hasResult() && !completedHeaderDrawn) {
                        if (i > 0) row++;
                        g.setForegroundColor(LanternaTheme.welcomeDim());
                        g.enableModifiers(SGR.BOLD);
                        g.putString(LEFT_PAD, row++, "Recently completed");
                        g.disableModifiers(SGR.BOLD);
                        completedHeaderDrawn = true;
                    }
                    drawActiveTask(g, row++, task, i == activeTaskIndex);
                }
            }
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.enableModifiers(SGR.ITALIC);
            g.putString(LEFT_PAD, row + 1, "←/→ switch · ↑/↓ navigate · Enter select · Esc close");
            g.disableModifiers(SGR.ITALIC);
        }

        private void drawActiveTask(TextGUIGraphics g, int row, TaskState task, boolean selected) {
            String name = taskRegistry != null ? taskRegistry.resolveAgentName(task.id()) : task.id();
            String agentType = taskRegistry != null
                ? taskRegistry.store().agentType(task.id()).orElse("") : "";
            if (Strings.CS.equals(name, task.id()) && !agentType.isEmpty()) name = agentType;
            StringBuilder line = new StringBuilder(selected ? "❯ " : "  ");
            if (task.status().hasResult()) {
                line.append(task.status() == TaskStatus.COMPLETED ? "✓ " : "× ");
                line.append(name);
                String preview = task.finalMessage().or(task::errorMessage)
                    .orElse(task.description());
                line.append(" · ").append(FormatUtils.truncate(preview.replace('\n', ' '), 60));
            } else {
                line.append("▶ ").append(name);
                if (!Strings.CS.equals(name, agentType) && !agentType.isEmpty()) line.append(" · ").append(agentType);
                String summary = task.progressSummary()
                    .filter(StringUtils::isNotBlank)
                    .orElse(task.description());
                line.append(" · ").append(FormatUtils.truncate(summary.replace('\n', ' '), 50));
                long elapsed = Math.max(0, Duration.between(task.startTime(), Instant.now()).toMillis());
                line.append(" · ").append(FormatUtils.formatDuration(elapsed));
                task.usage().filter(usage -> usage.totalTokens() > 0)
                    .ifPresent(usage -> line.append(" · ").append(FormatUtils.formatTokens(usage.totalTokens())).append(" tokens"));
                if (selected && task.status() == TaskStatus.RUNNING) line.append(" · x to stop");
            }
            g.setForegroundColor(selected ? LanternaTheme.suggestion()
                : task.status().hasResult() ? LanternaTheme.welcomeDim() : LanternaTheme.inputText());
            g.putString(LEFT_PAD, row, line.toString());
        }

        private void drawList(TextGUIGraphics g) {
            List<Row> rows = buildRows();
            List<AgentDisplay.ResolvedAgent> customAgents = rows.stream()
                .filter(AgentRow.class::isInstance)
                .map(AgentRow.class::cast)
                .map(AgentRow::resolved)
                .filter(resolved -> !resolved.agent().isBuiltIn())
                .toList();
            List<AgentDisplay.ResolvedAgent> builtIns = rows.stream()
                .filter(AgentRow.class::isInstance)
                .map(AgentRow.class::cast)
                .map(AgentRow::resolved)
                .filter(resolved -> resolved.agent().isBuiltIn())
                .toList();
            libraryViewportSize = Math.max(5, AgentsPanel.this.getSize().getRows() - 14);
            int maxStart = Math.max(0, customAgents.size() - libraryViewportSize);
            libraryVisibleFromIndex = Math.clamp(libraryVisibleFromIndex, 0, maxStart);
            int visibleTo = Math.min(customAgents.size(), libraryVisibleFromIndex + libraryViewportSize);
            int row = 4;
            if (libraryVisibleFromIndex == 0) {
                boolean selected = selectedIndex == 0;
                g.setForegroundColor(selected ? LanternaTheme.suggestion() : LanternaTheme.inputText());
                g.putString(LEFT_PAD, row++, (selected ? "❯ " : "  ") + "Create new agent");
            }
            if (libraryVisibleFromIndex > 0) {
                g.setForegroundColor(LanternaTheme.welcomeDim());
                g.putString(LEFT_PAD, row++, "↑ " + libraryVisibleFromIndex + " more");
            }
            AgentSource previousSource = null;
            for (int i = libraryVisibleFromIndex; i < visibleTo; i++) {
                AgentDisplay.ResolvedAgent resolved = customAgents.get(i);
                AgentSource source = resolved.agent().source();
                if (source != previousSource) {
                    g.setForegroundColor(LanternaTheme.welcomeDim());
                    g.putString(LEFT_PAD, row++, headerLabel(source, resolved.agent()));
                    previousSource = source;
                }
                boolean selected = selectedIndex == i + 1;
                String text = libraryAgentText(resolved);
                if (resolved.overriddenBy() != null) {
                    text += "  ⚠ shadowed by " + resolved.overriddenBy().displayName();
                }
                g.setForegroundColor(selected ? LanternaTheme.suggestion() : LanternaTheme.inputText());
                g.putString(LEFT_PAD, row++, (selected ? "❯ " : "  ") + text);
            }
            if (customAgents.isEmpty()) {
                row++;
                g.setForegroundColor(LanternaTheme.welcomeDim());
                g.putString(LEFT_PAD, row++, "No agents found. Create specialized subagents that Claude can delegate to.");
                g.putString(LEFT_PAD, row++, "Each subagent has its own context window, custom system prompt, and specific tools.");
                g.putString(LEFT_PAD, row++, "Try creating: Code Reviewer, Code Simplifier, Security Reviewer, Tech Lead, or UX Reviewer.");
                if (!builtIns.isEmpty()) row++;
            }
            int remaining = customAgents.size() - visibleTo;
            if (remaining > 0) {
                g.setForegroundColor(LanternaTheme.welcomeDim());
                g.putString(LEFT_PAD, row++, "↓ " + remaining + " more");
            } else if (!builtIns.isEmpty()) {
                g.setForegroundColor(LanternaTheme.welcomeDim());
                g.putString(LEFT_PAD, row++, headerLabel(AgentSource.BUILT_IN));
                for (AgentDisplay.ResolvedAgent resolved : builtIns) {
                    g.putString(LEFT_PAD, row++, "  " + libraryAgentText(resolved));
                }
            }
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.enableModifiers(SGR.ITALIC);
            g.putString(LEFT_PAD, row + 1, "↑↓ navigate · Enter select · Esc close");
            g.disableModifiers(SGR.ITALIC);
        }

        private String libraryAgentText(AgentDisplay.ResolvedAgent resolved) {
            var agent = resolved.agent();
            StringBuilder text = new StringBuilder(agent.agentType());
            if (agent.model() != null) {
                text.append(" · ").append(AgentModelOptions.displayName(agent.model()));
            }
            if (agent.memory() != null) {
                text.append(" · ").append(agent.memory()).append(" memory");
            }
            if (resolved.overriddenBy() == null && taskRegistry != null) {
                long running = activeTasks().stream()
                    .filter(task -> !task.status().hasResult())
                    .filter(task -> Strings.CS.equals(agent.agentType(),
                        taskRegistry.store().agentType(task.id()).orElse(null)))
                    .count();
                if (running > 0) text.append(" ● ").append(running).append(" running");
            }
            return text.toString();
        }

        private void drawAgentMenu(TextGUIGraphics g) {
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, 2, selectedAgent.agentType());
            drawChoiceMenuBody(g, currentAgentMenu(), agentMenuIdx, 4);
        }

        private void drawRun(TextGUIGraphics g) {
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, 2, "Enter a prompt for this subagent");
            g.setForegroundColor(LanternaTheme.inputText());
            String value = runPrompt.isEmpty() ? "Describe the task…"
                : runPrompt.substring(0, runCursor) + "▏" + runPrompt.substring(runCursor);
            g.putString(LEFT_PAD, 4, "> " + value);
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.enableModifiers(SGR.ITALIC);
            g.putString(LEFT_PAD, 6, "Enter to run · Esc to go back");
            g.disableModifiers(SGR.ITALIC);
        }

        private void drawEditMenu(TextGUIGraphics g) {
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, 2, "Source: " + selectedAgent.source().displayName());
            drawChoiceMenuBody(g, EDIT_MENU, editMenuIdx, 4);
            if (editError != null) {
                g.setForegroundColor(LanternaTheme.toolError());
                g.putString(LEFT_PAD, 4 + EDIT_MENU.size() + 2, editError);
            }
        }

        private void drawChoiceMenuBody(TextGUIGraphics g, List<String> items, int idx, int startRow) {
            for (int i = 0; i < items.size(); i++) {
                int row = startRow + i;
                boolean selected = i == idx;
                g.setForegroundColor(selected ? LanternaTheme.suggestion() : LanternaTheme.inputText());
                g.putString(LEFT_PAD, row, (selected ? "❯ " : "  ") + items.get(i));
            }
            int footerRow = startRow + items.size() + 1;
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.enableModifiers(SGR.ITALIC);
            g.putString(LEFT_PAD, footerRow, "↑↓ navigate · Enter select · Esc back");
            g.disableModifiers(SGR.ITALIC);
        }

        private void drawView(TextGUIGraphics g) {
            int row = 2;
            for (DetailLine line : detailLines(Math.max(20, g.getSize().getColumns() - LEFT_PAD * 2))) {
                g.setForegroundColor(line.color());
                if (line.bold()) g.enableModifiers(SGR.BOLD);
                g.putString(LEFT_PAD, row++, line.text());
                if (line.bold()) g.disableModifiers(SGR.BOLD);
            }
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.enableModifiers(SGR.ITALIC);
            g.putString(LEFT_PAD, row + 1, "Enter/Esc back");
            g.disableModifiers(SGR.ITALIC);
        }

        private List<DetailLine> detailLines(int width) {
            List<DetailLine> lines = new ArrayList<>();
            String path = selectedAgent.isBuiltIn() ? "Built-in"
                : selectedAgent.filePath() != null ? selectedAgent.filePath().toString() : "(unknown path)";
            lines.add(new DetailLine(path, LanternaTheme.welcomeDim(), false));
            lines.add(new DetailLine("", LanternaTheme.inputText(), false));
            lines.add(new DetailLine("Description (tells Claude when to use this agent):",
                LanternaTheme.inputText(), true));
            appendWrapped(lines, selectedAgent.whenToUse(), width - 2, "  ", LanternaTheme.inputText());

            var resolvedTools = AgentToolResolver.resolve(selectedAgent.tools(), availableToolNames);
            String toolsDisplay = resolvedTools.hasWildcard() ? "All tools"
                : selectedAgent.tools().isEmpty() ? "None" : String.join(", ", resolvedTools.validTools());
            lines.add(new DetailLine("Tools: " + toolsDisplay, LanternaTheme.inputText(), false));
            if (!resolvedTools.invalidTools().isEmpty()) {
                lines.add(new DetailLine("⚠ Unrecognized: " + String.join(", ", resolvedTools.invalidTools()),
                    LanternaTheme.toolWarning(), false));
            }
            lines.add(new DetailLine("Model: " + AgentModelOptions.displayName(selectedAgent.model()),
                LanternaTheme.inputText(), false));
            if (selectedAgent.permissionMode() != null) {
                lines.add(new DetailLine("Permission mode: " + selectedAgent.permissionMode(),
                    LanternaTheme.inputText(), false));
            }
            if (selectedAgent.memory() != null) {
                lines.add(new DetailLine("Memory: " + selectedAgent.memory(), LanternaTheme.inputText(), false));
            }
            if (selectedAgent.hooks() != null && !selectedAgent.hooks().isEmpty()) {
                List<String> hookNames = new ArrayList<>();
                selectedAgent.hooks().fieldNames().forEachRemaining(hookNames::add);
                lines.add(new DetailLine("Hooks: " + String.join(", ", hookNames),
                    LanternaTheme.inputText(), false));
            }
            if (!selectedAgent.skills().isEmpty()) {
                String skills = selectedAgent.skills().size() > 10
                    ? selectedAgent.skills().size() + " skills" : String.join(", ", selectedAgent.skills());
                lines.add(new DetailLine("Skills: " + skills, LanternaTheme.inputText(), false));
            }
            if (selectedAgent.color() != null) {
                lines.add(new DetailLine("Color: " + selectedAgent.color(),
                    LanternaTheme.agentColor(selectedAgent.color()), false));
            }
            if (!selectedAgent.isBuiltIn() && selectedAgent.systemPrompt() != null) {
                lines.add(new DetailLine("", LanternaTheme.inputText(), false));
                lines.add(new DetailLine("System prompt:", LanternaTheme.inputText(), true));
                appendWrapped(lines, selectedAgent.systemPrompt(), width - 2, "  ", LanternaTheme.inputText());
            }
            return List.copyOf(lines);
        }

        private void appendWrapped(List<DetailLine> lines, String value, int width,
                String prefix, TextColor color) {
            if (StringUtils.isEmpty(value)) {
                lines.add(new DetailLine(prefix, color, false));
                return;
            }
            for (String logical : value.split("\\R", -1)) {
                List<String> wrapped = logical.isEmpty() ? List.of("")
                    : FormatUtils.wrapText(logical, Math.max(1, width));
                for (String part : wrapped) lines.add(new DetailLine(prefix + part, color, false));
            }
        }

        private void drawDeleteConfirm(TextGUIGraphics g) {
            g.setForegroundColor(LanternaTheme.toolWarning());
            g.putString(LEFT_PAD, 2, "Delete agent \"" + selectedAgent.agentType() + "\"?");
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, 4, "This removes " + AgentFileWriter.actualFilePath(selectedAgent));
            drawChoiceMenuBody(g, List.of("Yes, delete", "No, cancel"), deleteConfirmIdx, 6);
        }
    }
}
