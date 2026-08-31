package com.claudecode.ui.lanterna.dialog;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.core.model.ModelNames;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.workflows.WorkflowRun;
import com.claudecode.tools.workflows.WorkflowRunStore;
import com.claudecode.tools.workflows.WorkflowScriptParser;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.git.GitUtils;
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
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;


public final class WorkflowsDialog extends Panel implements InlineOverlay {
    private static final int MIN_WIDTH = 64;
    private static final ScheduledExecutorService REFRESHER =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "workflows-dialog-refresh");
            thread.setDaemon(true);
            return thread;
        });
    private enum Mode { LIST, PHASES, AGENTS, TRANSCRIPT, SAVE }
    private enum SaveScope { PROJECT, USER }
    private final WorkflowRunStore runs;
    private final TaskRegistry tasks;
    private final Consumer<String> resumeHandler;
    private final Consumer<String> systemMessageHandler;
    private final Path cwd;
    private final Path userWorkflowsDir;
    private boolean active;
    private Runnable onClose;
    private List<WorkflowRun> items = List.of();
    private int selected;
    private int selectedPhase;
    private int selectedAgent;
    private String selectedAgentId;
    private int transcriptOffset;
    private boolean promptExpanded;
    private int agentDetailContentWidth = 80;
    private Mode mode = Mode.LIST;
    private String notice;
    private boolean autoOpenedSingle;
    /** True when Background Tasks opened one workflow and owns the back target. */
    private boolean directTaskRoute;
    private Mode saveReturnMode = Mode.LIST;
    private String saveName = "";
    private SaveScope saveScope = SaveScope.PROJECT;
    private Path overwritePending;
    private volatile boolean saveInFlight;
    private final AtomicLong saveGeneration = new AtomicLong();
    private String agentFilter = "all";
    private ScheduledFuture<?> refreshTask;
    private final AtomicLong transcriptLoadGeneration = new AtomicLong();
    private volatile List<String> transcriptSnapshot = List.of();
    private volatile boolean transcriptLoading;
    private volatile String transcriptFingerprint;

    public WorkflowsDialog(WorkflowRunStore runs, TaskRegistry tasks,
                           Consumer<String> resumeHandler,
                           Consumer<String> systemMessageHandler) {
        this(runs, tasks, resumeHandler,
            systemMessageHandler, Path.of(System.getProperty("user.dir")),
            ClaudePaths.WORKFLOWS_DIR);
    }

    WorkflowsDialog(WorkflowRunStore runs, TaskRegistry tasks) {
        this(runs, tasks, null, null,
            Path.of(System.getProperty("user.dir")), ClaudePaths.WORKFLOWS_DIR);
    }

    WorkflowsDialog(WorkflowRunStore runs, TaskRegistry tasks,
                    Consumer<String> resumeHandler) {
        this(runs, tasks, resumeHandler, null,
            Path.of(System.getProperty("user.dir")), ClaudePaths.WORKFLOWS_DIR);
    }

    WorkflowsDialog(WorkflowRunStore runs, TaskRegistry tasks,
                    Consumer<String> resumeHandler,
                    Consumer<String> systemMessageHandler,
                    Path cwd, Path userWorkflowsDir) {
        super(new LinearLayout(Direction.VERTICAL));
        this.runs = runs;
        this.tasks = tasks;
        this.resumeHandler = resumeHandler;
        this.systemMessageHandler = systemMessageHandler;
        this.cwd = cwd.toAbsolutePath().normalize();
        this.userWorkflowsDir = userWorkflowsDir.toAbsolutePath().normalize();
        Body body = new Body();
        body.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(body);
    }

    public synchronized void show(Runnable onClose) {
        this.onClose = onClose;
        this.active = true;
        this.saveInFlight = false;
        this.saveGeneration.incrementAndGet();
        refresh();
        this.selected = 0;
        this.selectedAgent = 0;
        this.selectedAgentId = null;
        this.promptExpanded = false;
        this.autoOpenedSingle = items.size() == 1;
        this.directTaskRoute = false;
        this.mode = autoOpenedSingle ? initialDetailMode(items.getFirst()) : Mode.LIST;
        this.notice = null;
        this.agentFilter = "all";
        startRefresh();
    }




    public synchronized boolean showTask(String taskId, Runnable onClose) {
        this.onClose = onClose;
        this.active = true;
        this.saveInFlight = false;
        this.saveGeneration.incrementAndGet();
        refresh();
        int index = -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).taskId().equals(taskId)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            this.active = false;
            this.onClose = null;
            this.items = List.of();
            return false;
        }
        this.selected = index;
        this.selectedPhase = 0;
        this.selectedAgent = 0;
        this.selectedAgentId = null;
        this.promptExpanded = false;
        this.autoOpenedSingle = true;
        this.directTaskRoute = true;
        this.mode = initialDetailMode(items.get(index));
        this.notice = null;
        this.agentFilter = "all";
        startRefresh();
        return true;
    }

    private void startRefresh() {
        if (refreshTask != null) refreshTask.cancel(false);
        refreshTask = REFRESHER.scheduleAtFixedRate(this::invalidate,
            1, 1, TimeUnit.SECONDS);
        invalidate();
    }

    @Override public boolean isActive() { return active; }

    @Override
    public synchronized void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!active) return;
        if (mode == Mode.SAVE) {
            handleSaveKey(key);
            deliver.set(false);
            return;
        }
        KeyType type = key.getKeyType();
        if (type == KeyType.ESCAPE || type == KeyType.ARROW_LEFT) {
            navigateBack();
            deliver.set(false);
        } else if (type == KeyType.ENTER || type == KeyType.ARROW_RIGHT) {
            refresh();
            if (!items.isEmpty()) {
                WorkflowRun chosen = items.get(selected);
                if (mode == Mode.LIST) {
                    mode = initialDetailMode(chosen);
                } else if (mode == Mode.PHASES) {
                    mode = Mode.AGENTS;
                    selectedAgent = 0;
                    selectedAgentId = null;
                    agentFilter = "all";
                } else if (mode == Mode.AGENTS && selectedAgentNode(chosen) != null) {
                    mode = Mode.TRANSCRIPT;
                    transcriptOffset = 0;
                    promptExpanded = false;
                    loadSelectedTranscriptAsync();
                } else if (mode == Mode.TRANSCRIPT && promptExpandable()) {
                    promptExpanded = !promptExpanded;
                    transcriptOffset = 0;
                }
                invalidate();
            }
            deliver.set(false);
        } else if (type == KeyType.ARROW_UP) {
            refresh();
            moveSelection(-1);
            invalidate();
            deliver.set(false);
        } else if (type == KeyType.ARROW_DOWN) {
            refresh();
            moveSelection(1);
            invalidate();
            deliver.set(false);
        } else if (type == KeyType.CHARACTER && key.getCharacter() != null
                && (key.getCharacter() == 'j' || key.getCharacter() == 'k')) {
            refresh();
            int delta = key.getCharacter() == 'j' ? 1 : -1;
            moveSelection(delta);
            invalidate();
            deliver.set(false);
        } else if (type == KeyType.CHARACTER && key.getCharacter() != null
                && key.getCharacter() == 'x') {
            refresh();
            WorkflowRun chosen = items.isEmpty() ? null : items.get(selected);
            if (chosen != null && isAgentMode()) {
                String agentId = selectedActiveAgentId(chosen);
                if (agentId != null) tasks.skipWorkflowAgent(chosen.taskId(), agentId);
            } else if (chosen != null && chosen.status() == TaskStatus.RUNNING) {
                tasks.killWorkflow(chosen.taskId());
            }
            if (chosen != null) {
                refresh();
                invalidate();
            }
            deliver.set(false);
        } else if (type == KeyType.CHARACTER && key.getCharacter() != null
                && key.getCharacter() == 'p') {
            refresh();
            WorkflowRun chosen = items.isEmpty() ? null : items.get(selected);
            if (isDetailMode() && chosen != null) {
                if (chosen.status() == TaskStatus.RUNNING) {
                    tasks.pauseWorkflow(chosen.taskId());
                } else if (canResume(chosen)) {
                    resumeHandler.accept(resumePrompt(chosen));
                    close();
                }
            }
            invalidate();
            deliver.set(false);
        } else if (type == KeyType.CHARACTER && key.getCharacter() != null
                && key.getCharacter() == 'r') {
            refresh();
            WorkflowRun chosen = items.isEmpty() ? null : items.get(selected);
            String agentId = chosen == null ? null : selectedRetryableAgentId(chosen);
            if (isAgentMode() && agentId != null) {
                tasks.retryWorkflowAgent(chosen.taskId(), agentId);
            }
            deliver.set(false);
        } else if (type == KeyType.CHARACTER && key.getCharacter() != null
                && key.getCharacter() == 'f' && mode == Mode.AGENTS) {
            cycleAgentFilter();
            invalidate();
            deliver.set(false);
        } else if (type == KeyType.CHARACTER && key.getCharacter() != null
                && key.getCharacter() == 's') {
            refresh();
            if (!items.isEmpty() && !items.get(selected).script().isEmpty()) beginSave();
            invalidate();
            deliver.set(false);
        } else if (type == KeyType.CHARACTER && key.getCharacter() != null
                && key.getCharacter() == ' ' && isDetailMode()) {
            close();
            deliver.set(false);
        }
    }

    synchronized List<WorkflowRun> items() { return items; }

    synchronized String subtitle() {
        long running = items.stream().filter(run -> run.status() == TaskStatus.RUNNING).count();
        long completed = items.size() - running;
        List<String> parts = new ArrayList<>();
        if (running > 0) parts.add(running + " running");
        if (completed > 0) parts.add(completed + " completed");
        return String.join(" · ", parts);
    }

    synchronized boolean isDetailMode() {
        return mode == Mode.PHASES || mode == Mode.AGENTS || mode == Mode.TRANSCRIPT;
    }

    synchronized boolean isSaveMode() { return mode == Mode.SAVE; }

    synchronized boolean isPhaseMode() { return mode == Mode.PHASES; }

    synchronized boolean isAgentListMode() { return mode == Mode.AGENTS; }

    synchronized boolean isTranscriptMode() { return mode == Mode.TRANSCRIPT; }

    synchronized List<String> selectedTranscriptLines() {
        return transcriptSnapshot;
    }

    synchronized List<String> phaseTitlesForTest() {
        if (items.isEmpty()) return List.of();
        return phaseRows(items.get(selected)).stream().map(PhaseRow::title).toList();
    }

    synchronized List<Integer> phaseAgentCountsForTest() {
        if (items.isEmpty()) return List.of();
        return phaseRows(items.get(selected)).stream()
            .map(phase -> phase.agents().size()).toList();
    }

    boolean saveInFlightForTest() { return saveInFlight; }

    static String singleBorderForTest(int width, String label) {
        return singleBorder('┌', '┐', width, label);
    }

    private void refresh() {
        List<WorkflowRun> sorted = new ArrayList<>(runs.list());
        sorted.sort((a, b) -> Long.compare(b.startTime(), a.startTime()));
        items = List.copyOf(sorted);
        selected = Math.min(selected, Math.max(0, items.size() - 1));
        reloadTranscriptWhenReleasedFingerprintChanges();
    }

    private synchronized void close() {
        if (!active) return;
        active = false;
        saveGeneration.incrementAndGet();
        saveInFlight = false;
        transcriptLoadGeneration.incrementAndGet();
        transcriptSnapshot = List.of();
        transcriptLoading = false;
        transcriptFingerprint = null;
        if (refreshTask != null) refreshTask.cancel(false);
        refreshTask = null;
        Runnable callback = onClose;
        onClose = null;
        items = List.of();
        invalidate();
        if (callback != null) callback.run();
    }

    @Override public TerminalSize calculatePreferredSize() {
        return active ? new TerminalSize(MIN_WIDTH, isDetailMode() ? detailPreferredRows()
            : mode == Mode.SAVE ? 10
            : Math.max(7, items.size() + 6))
            : new TerminalSize(0, 0);
    }


    private int detailPreferredRows() {
        int terminalRows = 40;
        try {
            if (getTextGUI() != null) {
                terminalRows = getTextGUI().getScreen().getTerminalSize().getRows();
            }
        } catch (RuntimeException _) {

        }
        return Math.max(20, terminalRows - 1);
    }

    @Override public Interactable nextFocus(Interactable fromThis) { return active ? super.nextFocus(fromThis) : null; }
    @Override public Interactable previousFocus(Interactable fromThis) { return active ? super.previousFocus(fromThis) : null; }

    private final class Body extends AbstractComponent<Body> {
        @Override protected ComponentRenderer<Body> createDefaultRenderer() { return new Renderer(); }
    }

    private final class Renderer implements ComponentRenderer<Body> {
        @Override public TerminalSize getPreferredSize(Body component) { return calculatePreferredSize(); }

        @Override public synchronized void drawComponent(TextGUIGraphics graphics, Body component) {
            if (!active) return;
            refresh();
            graphics.fill(' ');
            int columns = graphics.getSize().getColumns();
            int contentColumns = isDetailMode()
                ? Math.min(columns, Math.max(24, columns - 6)) : columns;
            graphics.setForegroundColor(LanternaTheme.divider());
            graphics.putString(0, 0, "─".repeat(Math.max(0, contentColumns)));
            if (mode == Mode.SAVE && !items.isEmpty()) {
                graphics.setForegroundColor(LanternaTheme.permission());
                graphics.enableModifiers(SGR.BOLD);
                graphics.putString(2, 1, "Save dynamic workflow");
                graphics.disableModifiers(SGR.BOLD);
                drawSave(graphics, columns);
                return;
            }
            if (isDetailMode() && !items.isEmpty()) {
                drawDetail(graphics, items.get(selected), contentColumns);
                return;
            }
            graphics.setForegroundColor(LanternaTheme.permission());
            graphics.enableModifiers(SGR.BOLD);
            graphics.putString(2, 1, "Dynamic workflows");
            graphics.disableModifiers(SGR.BOLD);
            graphics.setForegroundColor(LanternaTheme.welcomeDim());
            graphics.putString(2, 2, subtitle());
            if (items.isEmpty()) {
                graphics.putString(2, 3, "No dynamic workflows in this session");
            } else {
                for (int i = 0; i < items.size(); i++) {
                    WorkflowRun run = items.get(i);
                    String prefix = i == selected ? "❯ " : "  ";
                    String status = run.status().name().toLowerCase(Locale.ROOT);
                    graphics.setForegroundColor(i == selected ? LanternaTheme.suggestion() : LanternaTheme.inputText());
                    graphics.putString(2, 3 + i, prefix + InlineOverlay.clip(run.summary(), Math.max(20, columns - 22)));
                    graphics.setForegroundColor(LanternaTheme.welcomeDim());
                    graphics.putString(Math.max(2, columns - status.length() - 2), 3 + i, status);
                }
            }
            graphics.setForegroundColor(LanternaTheme.welcomeDim());
            graphics.enableModifiers(SGR.ITALIC);
            String footer = notice != null ? notice
                : "↑/↓ select · Enter view · x stop · s save · Esc close";
            graphics.putString(2, Math.max(5, items.size() + 5),
                InlineOverlay.clip(footer, Math.max(1, columns - 4)));
            graphics.disableModifiers(SGR.ITALIC);
        }
    }

    private void drawDetail(TextGUIGraphics graphics, WorkflowRun run, int columns) {
        drawDetailHeader(graphics, run, columns);
        if (mode == Mode.TRANSCRIPT) {
            drawTranscript(graphics, run, columns);
            return;
        }
        drawOverview(graphics, run, columns);
    }

    private void drawDetailHeader(TextGUIGraphics graphics, WorkflowRun run, int columns) {
        String name = firstNonBlank(run.workflowName(), run.title(), run.summary(),
            "Dynamic workflow");
        String subtext = firstNonBlank(run.summary(), run.title(), "");
        List<PhaseRow> phases = phaseRows(run);
        int done = phases.stream().mapToInt(this::doneAgentCount).sum();
        int phaseTotal = phases.stream().mapToInt(phase -> phase.agents().size()).sum();
        int total = Math.max(run.agentCount(), Math.max(done, phaseTotal));
        String stateSuffix = switch (run.status()) {
            case COMPLETED -> " · done";
            case KILLED -> " · stopped";
            case PAUSED -> " · paused";
            case FAILED -> " · failed";
            default -> "";
        };
        String stats = done + "/" + total + " " + (total == 1 ? "agent" : "agents")
            + " · " + FormatUtils.formatDuration(workflowElapsed(run)) + stateSuffix;

        graphics.setForegroundColor(LanternaTheme.permission());
        graphics.enableModifiers(SGR.BOLD);
        graphics.putString(1, 1, InlineOverlay.clip(name, Math.max(1, columns - 2)));
        graphics.disableModifiers(SGR.BOLD);
        graphics.setForegroundColor(LanternaTheme.welcomeDim());
        String clippedStats = InlineOverlay.clip(stats, Math.max(1, columns - 2));
        int statsX = Math.max(1, columns - FormatUtils.displayWidth(clippedStats) - 1);
        int subtextWidth = Math.max(0, statsX - 2);
        if (subtextWidth > 0) {
            graphics.putString(1, 2, InlineOverlay.clip(subtext, subtextWidth));
        }
        graphics.putString(statsX, 2, clippedStats);
    }

    private void drawOverview(TextGUIGraphics graphics, WorkflowRun run, int columns) {
        List<PhaseRow> phases = phaseRows(run);
        if (phases.isEmpty()) {
            graphics.setForegroundColor(LanternaTheme.welcomeDim());
            graphics.putString(2, 4, "No agents yet.");
            drawDetailFooter(graphics, run, columns);
            return;
        }
        selectedPhase = Math.min(selectedPhase, phases.size() - 1);
        PhaseRow phase = phases.get(selectedPhase);
        List<JsonNode> agents = filteredAgentRows(run);
        reconcileSelectedAgent(agents);
        selectedAgent = Math.min(selectedAgent, Math.max(0, agents.size() - 1));
        int rows = graphics.getSize().getRows();

        int available = Math.max(12, columns - 9);
        int longestPhase = Math.max(14,
            phases.stream().mapToInt(this::phasePreferredWidth).max().orElse(14));
        int leftWidth = Math.max(12, Math.min(34,
            Math.min(longestPhase, available - 24)));
        int rightWidth = available - leftWidth;
        boolean wide = columns >= 64 && rightWidth >= 20;
        int top = rows < 18 ? 3 : 4;
        int bottom = rows - 2;
        if (wide) {
            drawWideOverview(graphics, run, phases, phase, agents,
                top, bottom, leftWidth, rightWidth);
        } else {
            drawCompactOverview(graphics, run, phases, phase, agents, top, bottom, columns);
        }
        drawDetailFooter(graphics, run, columns);
    }

    private void drawWideOverview(TextGUIGraphics graphics, WorkflowRun run,
                                  List<PhaseRow> phases, PhaseRow selectedPhaseRow,
                                  List<JsonNode> agents, int top, int bottom,
                                  int leftWidth, int rightWidth) {
        int x = 1;
        String rightTitle = selectedPhaseRow.title() + " · "
            + agentCountLabel(agents.size(), agentFilter);
        graphics.setForegroundColor(LanternaTheme.divider());
        graphics.putString(x, top, splitBorder('┌', '┬', '┐', leftWidth + 2, rightWidth + 2,
            "Phases", rightTitle));
        int viewport = Math.max(1, bottom - top - 1);
        ViewWindow phaseWindow = windowAround(selectedPhase, phases.size(), viewport);
        ViewWindow agentWindow = windowAround(selectedAgent, agents.size(), viewport);
        for (int rowOffset = 0; rowOffset < viewport; rowOffset++) {
            int row = top + 1 + rowOffset;
            graphics.setForegroundColor(LanternaTheme.divider());
            graphics.putString(x, row, "│");
            graphics.putString(x + leftWidth + 3, row, "│");
            graphics.putString(x + leftWidth + rightWidth + 6, row, "│");
            int phaseIndex = phaseWindow.from() + rowOffset;
            if (phaseIndex < phaseWindow.to()) {
                drawPhaseRow(graphics, x + 2, row, leftWidth,
                    phases.get(phaseIndex), phaseIndex, run);
            }
            int agentIndex = agentWindow.from() + rowOffset;
            if (agentIndex < agentWindow.to()) {
                drawOverviewAgentRow(graphics, x + leftWidth + 5, row, rightWidth,
                    agents.get(agentIndex), agentIndex, run);
            } else if (agents.isEmpty() && rowOffset == 0) {
                graphics.setForegroundColor(LanternaTheme.welcomeDim());
                graphics.putString(x + leftWidth + 5, row,
                    InlineOverlay.clip(emptyAgentLabel(selectedPhaseRow), Math.max(1, rightWidth - 2)));
            }
        }
        graphics.setForegroundColor(LanternaTheme.divider());
        graphics.putString(x, bottom, splitTaggedBorder('└', '┴', '┘',
            leftWidth + 2, rightWidth + 2,
            phases.size() > viewport ? rangeLabel(phaseWindow, phases.size()) : null,
            agents.size() > viewport ? rangeLabel(agentWindow, agents.size()) : null));
    }

    private void drawCompactOverview(TextGUIGraphics graphics, WorkflowRun run,
                                     List<PhaseRow> phases, PhaseRow selectedPhaseRow,
                                     List<JsonNode> agents, int top, int bottom, int columns) {
        int contentWidth = Math.max(12, columns - 6);
        int rows = graphics.getSize().getRows();
        boolean tight = rows < 18;
        int frameBudget = rows - (tight ? 8 : 11);
        int phaseCapacity = Math.max(1, frameBudget - 3);
        boolean phaseOverflow = phases.size() > phaseCapacity;
        int phaseViewport = phaseOverflow ? Math.max(1, phaseCapacity - 1) : phases.size();
        int agentViewport = Math.max(1,
            frameBudget - phaseViewport - (phaseOverflow ? 1 : 0));
        ViewWindow phaseWindow = windowAround(selectedPhase, phases.size(), phaseViewport);
        int row = top;
        for (int i = phaseWindow.from(); i < phaseWindow.to() && row < bottom; i++) {
            drawCompactPhaseRow(graphics, 0, row++, columns, phases.get(i), i, run);
        }
        if (phaseOverflow && row < bottom) {
            graphics.setForegroundColor(LanternaTheme.welcomeDim());
            graphics.putString(2, row++, "  " + rangeLabel(phaseWindow, phases.size()));
        }
        if (row < bottom) row++;
        if (row >= bottom) return;

        graphics.setForegroundColor(LanternaTheme.divider());
        graphics.putString(1, row++, singleBorder('┌', '┐', contentWidth + 2, null));
        String count = agentCountLabel(agents.size(), agentFilter);
        if (tight) {
            String suffix = InlineOverlay.clip(" · " + count, Math.max(1, contentWidth - 1));
            String title = InlineOverlay.clip(selectedPhaseRow.title(),
                Math.max(1, contentWidth - FormatUtils.displayWidth(suffix)));
            drawCompactCardLine(graphics, row++, contentWidth, title + suffix,
                LanternaTheme.permission(), true);
        } else {
            drawCompactCardLine(graphics, row++, contentWidth, selectedPhaseRow.title(),
                LanternaTheme.permission(), true);
            drawCompactCardLine(graphics, row++, contentWidth, count,
                LanternaTheme.welcomeDim(), false);
            drawCompactCardLine(graphics, row++, contentWidth, "",
                LanternaTheme.inputText(), false);
        }

        ViewWindow agentWindow = windowAround(selectedAgent, agents.size(), agentViewport);
        for (int offset = 0; offset < agentViewport && row < bottom; offset++) {
            int agentIndex = agentWindow.from() + offset;
            graphics.setForegroundColor(LanternaTheme.divider());
            graphics.putString(1, row, "│");
            graphics.putString(contentWidth + 4, row, "│");
            if (agentIndex < agentWindow.to()) {
                drawCompactAgentRow(graphics, 3, row, contentWidth,
                    agents.get(agentIndex), agentIndex, run);
            } else if (agents.isEmpty() && offset == 0) {
                graphics.setForegroundColor(LanternaTheme.welcomeDim());
                graphics.putString(3, row,
                    InlineOverlay.clip(emptyAgentLabel(selectedPhaseRow), contentWidth));
            }
            row++;
        }
        graphics.setForegroundColor(LanternaTheme.divider());
        graphics.putString(1, Math.min(bottom, row), singleTaggedBorder('└', '┘', contentWidth + 2,
            agents.size() > agentViewport ? rangeLabel(agentWindow, agents.size()) : null));
    }

    private static void drawCompactCardLine(TextGUIGraphics graphics, int row, int contentWidth,
                                            String text, TextColor color,
                                            boolean bold) {
        graphics.setForegroundColor(LanternaTheme.divider());
        graphics.putString(1, row, "│");
        graphics.putString(contentWidth + 4, row, "│");
        graphics.setForegroundColor(color);
        if (bold) graphics.enableModifiers(SGR.BOLD);
        graphics.putString(3, row, InlineOverlay.clip(text, contentWidth));
        if (bold) graphics.disableModifiers(SGR.BOLD);
    }

    private void drawPhaseRow(TextGUIGraphics graphics, int x, int row, int width,
                              PhaseRow phase, int phaseIndex, WorkflowRun run) {
        String status = phaseStatus(phase, run);
        boolean selectedRow = phaseIndex == selectedPhase;
        String pointer = mode == Mode.PHASES && selectedRow ? "❯ " : "  ";
        String marker = switch (status) {
            case "done" -> "✓";
            case "failed" -> "×";
            default -> Integer.toString(phaseIndex + 1);
        };
        String count = phase.agents().isEmpty() ? ""
            : doneAgentCount(phase) + "/" + phase.agents().size();
        int reserved = FormatUtils.displayWidth(pointer + marker + " ")
            + (count.isEmpty() ? 0 : FormatUtils.displayWidth(count) + 1);
        String title = InlineOverlay.clip(phase.title(), Math.max(1, width - reserved));
        String line = pointer + marker + " " + title;
        if (!count.isEmpty()) {
            line += " ".repeat(Math.max(1, width - FormatUtils.displayWidth(line)
                - FormatUtils.displayWidth(count))) + count;
        }
        graphics.setForegroundColor(selectedRow ? LanternaTheme.permission() : phaseColor(status));
        graphics.putString(x, row, InlineOverlay.clip(line, width));
    }


    private void drawCompactPhaseRow(TextGUIGraphics graphics, int x, int row, int width,
                                     PhaseRow phase, int phaseIndex, WorkflowRun run) {
        String status = phaseStatus(phase, run);
        boolean selectedRow = phaseIndex == selectedPhase;
        String pointer = selectedRow ? "❯" : " ";
        String marker = switch (status) {
            case "done" -> "✓";
            case "failed" -> "×";
            default -> Integer.toString(phaseIndex + 1);
        };
        String head = pointer + " " + marker + " ";
        int laneWidth = 17;
        String title = InlineOverlay.clip(phase.title(),
            Math.max(1, laneWidth - FormatUtils.displayWidth(head)));
        String lane = head + title;
        lane += " ".repeat(Math.max(0, laneWidth - FormatUtils.displayWidth(lane)));
        String count = phase.agents().isEmpty() ? ""
            : doneAgentCount(phase) + "/" + phase.agents().size();
        graphics.setForegroundColor(selectedRow ? LanternaTheme.permission() : phaseColor(status));
        graphics.putString(x, row, InlineOverlay.clip(lane + count, width));
    }


    private void drawOverviewAgentRow(TextGUIGraphics graphics, int x, int row, int width,
                                      JsonNode agent, int agentIndex, WorkflowRun run) {
        String status = agentStatus(agent, run.status() == TaskStatus.RUNNING);
        boolean selectedRow = (mode == Mode.AGENTS || mode == Mode.TRANSCRIPT)
            && agentIndex == selectedAgent;
        String prefix = (selectedRow ? "❯" : " ") + agentGlyph(status) + " ";
        int labelWidth = Math.min(24, Math.max(6, (int) Math.floor(width * 0.42)));
        String label = InlineOverlay.clip(agent.path("label").asText("agent"), labelWidth);
        String model = ModelNames.displayName(agent.path("model").asText(run.defaultModel()));
        if (Strings.CS.equals("unknown", model)) model = "";
        String stats = agentStats(agent, status);
        int tailWidth = Math.max(0, width - labelWidth - 4);
        String tail = alignModelAndStats(model, stats, tailWidth);
        String line = prefix + label
            + " ".repeat(Math.max(1, labelWidth - FormatUtils.displayWidth(label) + 1)) + tail;
        graphics.setForegroundColor(selectedRow ? LanternaTheme.permission() : agentColor(status));
        graphics.putString(x, row, InlineOverlay.clip(line, width));
    }


    private void drawDetailAgentRow(TextGUIGraphics graphics, int x, int row, int width,
                                    JsonNode agent, int agentIndex, WorkflowRun run) {
        String status = agentStatus(agent, run.status() == TaskStatus.RUNNING);
        boolean selectedRow = agentIndex == selectedAgent;
        String prefix = (selectedRow ? "❯ " : "  ") + agentGlyph(status) + " ";
        String label = InlineOverlay.clip(agent.path("label").asText("agent"),
            Math.max(1, width - 4));
        graphics.setForegroundColor(selectedRow ? LanternaTheme.permission() : agentColor(status));
        graphics.putString(x, row, InlineOverlay.clip(prefix + label, width));
    }


    private void drawCompactAgentRow(TextGUIGraphics graphics, int x, int row, int width,
                                     JsonNode agent, int agentIndex, WorkflowRun run) {
        String status = agentStatus(agent, run.status() == TaskStatus.RUNNING);
        boolean selectedRow = mode == Mode.AGENTS && agentIndex == selectedAgent;
        String prefix = (selectedRow ? "❯ " : "  ") + agentGlyph(status) + " ";
        int labelWidth = Math.min(22, Math.max(4, width - 5));
        String label = InlineOverlay.clip(agent.path("label").asText("agent"), labelWidth);
        String model = ModelNames.displayName(agent.path("model").asText(run.defaultModel()));
        if (Strings.CS.equals("unknown", model)) model = "";
        String stats = agentStats(agent, status);
        String tail = alignModelAndStats(model, stats, Math.max(0, width - labelWidth - 5));
        String line = prefix + label
            + " ".repeat(Math.max(1, labelWidth - FormatUtils.displayWidth(label) + 1)) + tail;
        graphics.setForegroundColor(selectedRow ? LanternaTheme.permission() : agentColor(status));
        graphics.putString(x, row, InlineOverlay.clip(line, width));
    }

    private void drawDetailFooter(TextGUIGraphics graphics, WorkflowRun run, int columns) {
        List<String> actions = new ArrayList<>();
        actions.add(mode == Mode.TRANSCRIPT ? "↑↓ agent" : "↑↓ select");
        if (mode == Mode.TRANSCRIPT) actions.add("j/k scroll");
        if (mode == Mode.TRANSCRIPT && promptExpandable()) actions.add("⏎ prompt");
        JsonNode agent = selectedAgentNode(run);
        String status = agent == null ? "" : agentStatus(agent, run.status() == TaskStatus.RUNNING);
        if (mode != Mode.PHASES && selectedActiveAgentId(run) != null) actions.add("x stop");
        if (mode == Mode.PHASES && run.status() == TaskStatus.RUNNING) actions.add("x stop workflow");
        if (mode != Mode.PHASES && selectedRetryableAgentId(run) != null
                && Strings.CS.equals("failed", status)) actions.add("r restart");
        if (run.status() == TaskStatus.RUNNING) actions.add("p pause");
        else if (canResume(run)) actions.add("p resume");
        if (mode == Mode.AGENTS) actions.add(Strings.CS.equals("all", agentFilter)
            ? "f filter" : "f filter: " + agentFilter);
        actions.add("esc back");
        if (!run.script().isEmpty()) actions.add("s save");
        String footer = notice == null ? String.join(" · ", actions) : notice;
        graphics.setForegroundColor(LanternaTheme.welcomeDim());
        graphics.enableModifiers(SGR.ITALIC);
        graphics.putString(1, graphics.getSize().getRows() - 1,
            InlineOverlay.clip(footer, Math.max(1, columns - 2)));
        graphics.disableModifiers(SGR.ITALIC);
    }

    private static long workflowElapsed(WorkflowRun run) {
        if (run.durationMs() > 0) return run.durationMs();
        long end = run.endTime() == null ? System.currentTimeMillis() : run.endTime();
        return Math.max(0, end - run.startTime());
    }

    private int phasePreferredWidth(PhaseRow phase) {
        String count = phase.agents().isEmpty() ? "" : " " + doneAgentCount(phase)
            + "/" + phase.agents().size();
        return 4 + FormatUtils.displayWidth(phase.title()) + FormatUtils.displayWidth(count);
    }

    private int doneAgentCount(PhaseRow phase) {
        return (int) phase.agents().stream()
            .filter(agent -> Strings.CS.equals("done", agentStatus(agent, true)))
            .count();
    }

    private String phaseStatus(PhaseRow phase, WorkflowRun run) {
        if (phase.agents().isEmpty()) return "not-started";
        int done = 0;
        int failed = 0;
        for (JsonNode agent : phase.agents()) {
            String status = agentStatus(agent, run.status() == TaskStatus.RUNNING);
            if (Strings.CS.equals("done", status)) done++;
            else if (Strings.CS.equals("failed", status)) failed++;
        }
        if (done + failed == phase.agents().size()) return failed > 0 ? "failed" : "done";
        return "running";
    }

    private static String agentStats(JsonNode agent, String status) {
        List<String> stats = new ArrayList<>();
        if (agent.hasNonNull("isolation")) stats.add(agent.path("isolation").asText());
        if (agent.has("tokens")) {
            stats.add(FormatUtils.formatTokens(agent.path("tokens").asLong()) + " tok");
        }
        if (agent.path("toolCalls").asInt() > 0) {
            int calls = agent.path("toolCalls").asInt();
            stats.add(calls + " " + (calls == 1 ? "tool" : "tools"));
        }
        if (agent.has("durationMs")) {
            stats.add(FormatUtils.formatDuration(agent.path("durationMs").asLong()));
        }
        if (Strings.CS.equals("running", status) && agent.has("lastProgressAt")) {
            long idleMs = System.currentTimeMillis() - agent.path("lastProgressAt").asLong();
            if (idleMs >= 30_000) stats.add("idle " + FormatUtils.formatDuration(idleMs));
        }
        if (Strings.CS.equals("queued", status)) stats.add("queued");
        if (Strings.CS.equals("interrupted", status)) stats.add("stopped");
        if (Strings.CS.equals("skipped", status)) stats.add("skipped");
        if (Strings.CS.equals("failed", status)) {
            String error = agent.path("error").asText("").trim();
            int newline = error.indexOf('\n');
            if (newline >= 0) error = error.substring(0, newline).trim();
            stats.add(error.isEmpty() ? "failed" : "failed: " + error);
        }
        return String.join(" · ", stats);
    }

    private static String alignModelAndStats(String model, String stats, int width) {
        if (width <= 0) return "";
        String clippedModel = model;
        String clippedStats = stats;
        int separator = !clippedModel.isEmpty() && !clippedStats.isEmpty() ? 1 : 0;
        if (FormatUtils.displayWidth(clippedModel) + separator
                + FormatUtils.displayWidth(clippedStats) > width) {
            clippedStats = InlineOverlay.clip(clippedStats,
                Math.max(0, width - FormatUtils.displayWidth(clippedModel) - separator));
            separator = !clippedModel.isEmpty() && !clippedStats.isEmpty() ? 1 : 0;
            if (FormatUtils.displayWidth(clippedModel) + separator
                    + FormatUtils.displayWidth(clippedStats) > width) {
                clippedModel = InlineOverlay.clip(clippedModel,
                    Math.max(0, width - FormatUtils.displayWidth(clippedStats)
                        - (clippedStats.isEmpty() ? 0 : 1)));
            }
        }
        int padding = Math.max(0, width - FormatUtils.displayWidth(clippedModel)
            - FormatUtils.displayWidth(clippedStats));
        return clippedModel + " ".repeat(padding) + clippedStats;
    }

    static String agentTailForTest(String model, String stats, int width) {
        return alignModelAndStats(model, stats, width);
    }

    static String windowRangeForTest(int selected, int total, int viewport) {
        return rangeLabel(windowAround(selected, total, viewport), total);
    }

    static String agentDetailStatsForTest(JsonNode agent, String status) {
        return agentDetailStats(agent, status);
    }

    private static String agentGlyph(String status) {
        return switch (status) {
            case "done" -> "✓";
            case "failed", "skipped" -> "×";
            case "queued", "interrupted" -> "○";
            default -> "●";
        };
    }

    private static TextColor phaseColor(String status) {
        return switch (status) {
            case "done" -> LanternaTheme.toolSuccess();
            case "failed" -> LanternaTheme.toolError();
            default -> LanternaTheme.welcomeDim();
        };
    }

    private static TextColor agentColor(String status) {
        return switch (status) {
            case "done" -> LanternaTheme.toolSuccess();
            case "failed" -> LanternaTheme.toolError();
            default -> LanternaTheme.welcomeDim();
        };
    }

    private String emptyAgentLabel(PhaseRow phase) {
        if (phase.agents().isEmpty()) return "Not started yet";
        return Strings.CS.equals("all", agentFilter)
            ? "No agents" : "No " + filterDisplay(agentFilter) + " agents";
    }

    private static String agentCountLabel(int count, String filter) {
        if (!Strings.CS.equals("all", filter)) {
            return "showing " + count + " " + filterDisplay(filter);
        }
        return count + " " + (count == 1 ? "agent" : "agents");
    }

    private static String filterDisplay(String filter) {
        return switch (filter) {
            case "done" -> "completed";
            case "interrupted" -> "stopped";
            default -> filter.toLowerCase(Locale.ROOT);
        };
    }

    private static ViewWindow windowAround(int selected, int total, int viewport) {
        if (total <= viewport) return new ViewWindow(0, total);
        int radius = viewport / 2;
        int from = Math.max(0, Math.min(selected - radius, total - viewport));
        return new ViewWindow(from, from + viewport);
    }

    private static String rangeLabel(ViewWindow window, int total) {
        String above = window.from() > 0 ? "↑" : " ";
        String below = window.to() < total ? "↓" : " ";
        return above + " " + (window.from() + 1) + "–" + window.to()
            + " of " + total + " " + below;
    }

    private record ViewWindow(int from, int to) {}

    private static String splitBorder(char left, char middle, char right,
                                      int leftWidth, int rightWidth,
                                      String leftLabel, String rightLabel) {
        return left + labeledRule(leftWidth, leftLabel) + middle
            + labeledRule(rightWidth, rightLabel) + right;
    }

    private static String splitTaggedBorder(char left, char middle, char right,
                                            int leftWidth, int rightWidth,
                                            String leftTag, String rightTag) {
        return left + taggedRule(leftWidth, leftTag) + middle
            + taggedRule(rightWidth, rightTag) + right;
    }

    private static String singleBorder(char left, char right, int width, String label) {
        return left + labeledRule(width, label) + right;
    }

    private static String singleTaggedBorder(char left, char right, int width, String tag) {
        return left + taggedRule(width, tag) + right;
    }

    private static String labeledRule(int width, String label) {
        if (StringUtils.isBlank(label)) return "─".repeat(Math.max(0, width));
        String clipped = InlineOverlay.clip(label, Math.max(1, width - 2));
        String decorated = " " + clipped + " ";
        return decorated + "─".repeat(Math.max(0,
            width - FormatUtils.displayWidth(decorated)));
    }

    private static String taggedRule(int width, String tag) {
        if (StringUtils.isBlank(tag)) return "─".repeat(Math.max(0, width));
        String clipped = InlineOverlay.clip(tag, Math.max(0, width - 2));
        String decorated = " " + clipped + " ";
        return "─".repeat(Math.max(0, width - FormatUtils.displayWidth(decorated)))
            + decorated;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!StringUtils.isBlank(value)) return value;
        }
        return "";
    }

    private void drawTranscript(TextGUIGraphics graphics, WorkflowRun run, int columns) {
        JsonNode agent = selectedAgentNode(run);
        if (agent == null) {
            graphics.setForegroundColor(LanternaTheme.welcomeDim());
            graphics.putString(2, 4, "No agent transcript selected");
            drawDetailFooter(graphics, run, columns);
            return;
        }
        List<JsonNode> agents = filteredAgentRows(run);
        reconcileSelectedAgent(agents);
        int rows = graphics.getSize().getRows();
        int top = rows < 18 ? 3 : 4;
        int bottom = rows - 2;
        int available = Math.max(12, columns - 9);
        int longestAgent = Math.max(14, agents.stream()
            .mapToInt(item -> 4
                + FormatUtils.displayWidth(item.path("label").asText("agent")))
            .max().orElse(14));
        int leftWidth = Math.max(12, Math.min(30,
            Math.min(longestAgent, available - 30)));
        int rightWidth = available - leftWidth;
        boolean wide = columns >= 64 && rightWidth >= 30;
        agentDetailContentWidth = wide ? rightWidth : Math.max(12, columns - 6);
        List<String> lines = agentDetailLines(run, agent, agentDetailContentWidth);
        if (wide) {
            drawWideAgentDetail(graphics, run, agents, agent, lines,
                top, bottom, leftWidth, rightWidth);
        } else {
            drawCompactAgentDetail(graphics, agents, agent, lines,
                top, bottom, columns);
        }
        drawDetailFooter(graphics, run, columns);
    }

    private void drawWideAgentDetail(TextGUIGraphics graphics, WorkflowRun run,
                                     List<JsonNode> agents, JsonNode agent,
                                     List<String> lines, int top, int bottom,
                                     int leftWidth, int rightWidth) {
        int x = 1;
        List<PhaseRow> phases = phaseRows(run);
        PhaseRow phase = phases.isEmpty() ? new PhaseRow(0, "Agents", null, agents)
            : phases.get(Math.min(selectedPhase, phases.size() - 1));
        String leftTitle = phase.title() + " · " + agentCountLabel(agents.size(), agentFilter);
        String rightTitle = agent.path("label").asText("agent");
        graphics.setForegroundColor(LanternaTheme.divider());
        graphics.putString(x, top, splitBorder('┌', '┬', '┐', leftWidth + 2, rightWidth + 2,
            leftTitle, rightTitle));
        int viewport = Math.max(1, bottom - top - 1);
        ViewWindow agentWindow = windowAround(selectedAgent, agents.size(), viewport);
        transcriptOffset = Math.max(0, Math.min(transcriptOffset,
            Math.max(0, lines.size() - viewport)));
        for (int offset = 0; offset < viewport; offset++) {
            int row = top + 1 + offset;
            graphics.setForegroundColor(LanternaTheme.divider());
            graphics.putString(x, row, "│");
            graphics.putString(x + leftWidth + 3, row, "│");
            graphics.putString(x + leftWidth + rightWidth + 6, row, "│");
            int agentIndex = agentWindow.from() + offset;
            if (agentIndex < agentWindow.to()) {
                drawDetailAgentRow(graphics, x + 2, row, leftWidth,
                    agents.get(agentIndex), agentIndex, run);
            }
            int lineIndex = transcriptOffset + offset;
            if (lineIndex < lines.size()) {
                drawAgentDetailLine(graphics, x + leftWidth + 5, row,
                    rightWidth, lines.get(lineIndex), lineIndex, lines,
                    agentStatus(agent, run.status() == TaskStatus.RUNNING));
            }
        }
        graphics.setForegroundColor(LanternaTheme.divider());
        graphics.putString(x, bottom, splitTaggedBorder('└', '┴', '┘',
            leftWidth + 2, rightWidth + 2,
            agents.size() > viewport ? rangeLabel(agentWindow, agents.size()) : null,
            lines.size() > viewport
                ? rangeLabel(new ViewWindow(transcriptOffset,
                    Math.min(lines.size(), transcriptOffset + viewport)), lines.size()) : null));
    }

    private void drawCompactAgentDetail(TextGUIGraphics graphics, List<JsonNode> agents,
                                        JsonNode agent, List<String> lines,
                                        int top, int bottom, int columns) {
        int contentWidth = Math.max(12, columns - 6);
        boolean tight = graphics.getSize().getRows() < 18;
        String position = (selectedAgent + 1) + "/" + Math.max(1, agents.size());
        String suffix = " · " + position;
        String title = InlineOverlay.clip(agent.path("label").asText("agent"),
            Math.max(1, contentWidth - FormatUtils.displayWidth(suffix))) + suffix;
        graphics.setForegroundColor(LanternaTheme.divider());
        graphics.putString(1, top, singleBorder('┌', '┐', contentWidth + 2, null));
        drawCompactCardLine(graphics, top + 1, contentWidth, title,
            LanternaTheme.permission(), true);
        int viewport = Math.max(3, graphics.getSize().getRows() - (tight ? 8 : 9));
        transcriptOffset = Math.max(0, Math.min(transcriptOffset,
            Math.max(0, lines.size() - viewport)));
        for (int offset = 0; offset < viewport; offset++) {
            int row = top + 2 + offset;
            graphics.setForegroundColor(LanternaTheme.divider());
            graphics.putString(1, row, "│");
            graphics.putString(contentWidth + 4, row, "│");
            int lineIndex = transcriptOffset + offset;
            if (lineIndex < lines.size()) {
                drawAgentDetailLine(graphics, 3, row, contentWidth, lines.get(lineIndex),
                    lineIndex, lines, agentStatus(agent,
                        items.get(selected).status() == TaskStatus.RUNNING));
            }
        }
        graphics.setForegroundColor(LanternaTheme.divider());
        int cardBottom = Math.min(bottom, top + viewport + 2);
        graphics.putString(1, cardBottom, singleTaggedBorder('└', '┘', contentWidth + 2,
            lines.size() > viewport
                ? rangeLabel(new ViewWindow(transcriptOffset,
                    Math.min(lines.size(), transcriptOffset + viewport)), lines.size()) : null));
    }

    private void drawAgentDetailLine(TextGUIGraphics graphics, int x, int row,
                                     int width, String line, int lineIndex,
                                     List<String> lines, String status) {
        boolean heading = Strings.CS.startsWith(line, "Prompt")
            || Strings.CS.startsWith(line, "Activity")
            || Strings.CS.equals(line, "Outcome");
        boolean statusLine = lineIndex == 0;
        boolean failure = Strings.CS.equals("failed", status)
            && lineIndex > lines.indexOf("Outcome");
        if (statusLine) {
            int metadata = line.indexOf(" · ");
            String state = metadata < 0 ? line : line.substring(0, metadata);
            String suffix = metadata < 0 ? "" : line.substring(metadata);
            graphics.setForegroundColor(switch (status) {
                case "done" -> LanternaTheme.toolSuccess();
                case "failed" -> LanternaTheme.toolError();
                default -> LanternaTheme.welcomeDim();
            });
            graphics.enableModifiers(SGR.BOLD);
            graphics.putString(x, row, InlineOverlay.clip(state, Math.max(1, width)));
            graphics.disableModifiers(SGR.BOLD);
            int used = FormatUtils.displayWidth(state);
            if (!suffix.isEmpty() && used < width) {
                graphics.setForegroundColor(LanternaTheme.welcomeDim());
                graphics.putString(x + used, row,
                    InlineOverlay.clip(suffix, Math.max(1, width - used)));
            }
            return;
        }
        graphics.setForegroundColor(failure ? LanternaTheme.toolError()
            : heading ? LanternaTheme.welcomeDim() : LanternaTheme.inputText());
        if (heading) graphics.enableModifiers(SGR.BOLD);
        graphics.putString(x, row, InlineOverlay.clip(line, Math.max(1, width)));
        if (heading) graphics.disableModifiers(SGR.BOLD);
    }

    private List<String> agentDetailLines(WorkflowRun run, JsonNode agent, int contentWidth) {
        String status = agentStatus(agent, run.status() == TaskStatus.RUNNING);
        List<String> metadata = new ArrayList<>();
        String model = ModelNames.displayName(agent.path("model").asText(run.defaultModel()));
        if (!Strings.CS.equals("unknown", model)) metadata.add(model);
        if (agent.hasNonNull("agentType")) metadata.add(agent.path("agentType").asText());
        if (agent.hasNonNull("isolation")) {
            String isolation = agent.path("isolation").asText();
            metadata.add(Strings.CS.equals("remote", isolation) && agent.hasNonNull("remoteSessionId")
                ? "remote " + agent.path("remoteSessionId").asText() : isolation);
        }
        if (agent.path("cached").asBoolean(false)) metadata.add("from resume journal");
        if (agent.path("attempt").asInt() > 1) {
            String reason = switch (agent.path("lastAttemptReason").asText()) {
                case "throttled" -> "throttled";
                case "user-retry" -> "user retry";
                default -> "stalled";
            };
            metadata.add("attempt " + agent.path("attempt").asInt() + " (" + reason + ")");
        }
        List<String> result = new ArrayList<>();
        result.add(agentGlyph(status) + " " + statusDisplay(status)
            + (metadata.isEmpty() ? "" : " · " + String.join(" · ", metadata)));
        String stats = agentDetailStats(agent, status);
        if (!stats.isEmpty()) result.add(stats);
        result.add("");
        if (transcriptLoading && transcriptSnapshot.isEmpty()) {
            result.add("Prompt");
            result.add("  Loading…");
            result.add("");
            result.add("Activity");
            result.add("  Loading…");
            result.add("");
            result.add("Outcome");
            result.add("  Loading…");
        } else {
            result.addAll(displayTranscriptLines(agent, contentWidth));
        }
        return List.copyOf(result);
    }


    private static String agentDetailStats(JsonNode agent, String status) {
        List<String> stats = new ArrayList<>();
        if (agent.has("tokens")) {
            stats.add(FormatUtils.formatTokens(agent.path("tokens").asLong()) + " tok");
        }
        int calls = agent.path("toolCalls").asInt();
        if (calls > 0) stats.add(calls + " " + (calls == 1 ? "tool call" : "tool calls"));
        if (agent.has("durationMs")) {
            stats.add(FormatUtils.formatDuration(agent.path("durationMs").asLong()));
        }
        long now = System.currentTimeMillis();
        if (Strings.CS.equals("queued", status) && agent.has("queuedAt")) {
            stats.add("waiting " + FormatUtils.formatDuration(
                Math.max(0, now - agent.path("queuedAt").asLong())));
        }
        if (Strings.CS.equals("running", status) && agent.has("lastProgressAt")) {
            long idleMs = now - agent.path("lastProgressAt").asLong();
            if (idleMs >= 30_000) stats.add("idle " + FormatUtils.formatDuration(idleMs));
        }
        return String.join(" · ", stats);
    }

    private static String statusDisplay(String status) {
        return switch (status) {
            case "queued" -> "Queued";
            case "running" -> "Running";
            case "done" -> "Completed";
            case "failed" -> "Failed";
            case "skipped" -> "Skipped";
            default -> "Stopped";
        };
    }

    private static List<JsonNode> agentRows(WorkflowRun run) {
        return run.workflowProgress().stream()
            .filter(item -> Strings.CS.equals("workflow_agent", item.path("type").asText()))
            .toList();
    }

    private List<JsonNode> filteredAgentRows(WorkflowRun run) {
        List<JsonNode> agents = agentsForSelectedPhase(run);
        if (Strings.CS.equals("all", agentFilter)) return agents;
        return agents.stream()
            .filter(agent -> agentFilter.equals(agentStatus(agent, run.status() == TaskStatus.RUNNING)))
            .toList();
    }

    private List<JsonNode> selectedAgents() {
        if (items.isEmpty()) return List.of();
        return filteredAgentRows(items.get(selected));
    }

    private List<JsonNode> agentsForSelectedPhase(WorkflowRun run) {
        List<PhaseRow> phases = phaseRows(run);
        if (phases.isEmpty()) return agentRows(run);
        selectedPhase = Math.min(selectedPhase, phases.size() - 1);
        return phases.get(selectedPhase).agents();
    }

    private JsonNode selectedAgentNode(WorkflowRun run) {
        List<JsonNode> agents = filteredAgentRows(run);
        if (agents.isEmpty()) return null;
        reconcileSelectedAgent(agents);
        JsonNode selectedNode = agents.get(selectedAgent);
        selectedAgentId = selectedNode.path("agentId").asText(null);
        return selectedNode;
    }

    private String selectedActiveAgentId(WorkflowRun run) {
        JsonNode agent = selectedAgentNode(run);
        if (agent == null) return null;
        String status = agentStatus(agent, run.status() == TaskStatus.RUNNING);
        if (!Strings.CS.equals("running", status) && !Strings.CS.equals("queued", status)) return null;
        return agent.path("agentId").asText(null);
    }


    private String selectedRetryableAgentId(WorkflowRun run) {
        JsonNode agent = selectedAgentNode(run);
        if (agent == null) return null;
        String status = agentStatus(agent, run.status() == TaskStatus.RUNNING);
        if (!Strings.CS.equals("failed", status)) return null;
        return agent.path("agentId").asText(null);
    }

    private void cycleAgentFilter() {
        if (items.isEmpty()) return;
        WorkflowRun run = items.get(selected);
        List<String> order = List.of("all", "running", "queued", "failed", "done",
            "skipped", "interrupted");
        Set<String> present = agentsForSelectedPhase(run).stream()
            .map(agent -> agentStatus(agent, run.status() == TaskStatus.RUNNING))
            .collect(Collectors.toSet());
        int index = order.indexOf(agentFilter);
        do {
            index = (index + 1) % order.size();
        } while (!Strings.CS.equals("all", order.get(index)) && !present.contains(order.get(index)));
        agentFilter = order.get(index);
        selectedAgent = 0;
        selectedAgentId = null;
        promptExpanded = false;
    }

    private void reconcileSelectedAgent(List<JsonNode> agents) {
        if (agents.isEmpty()) {
            selectedAgent = 0;
            selectedAgentId = null;
            return;
        }
        if (selectedAgentId != null) {
            for (int index = 0; index < agents.size(); index++) {
                if (Strings.CS.equals(selectedAgentId,
                        agents.get(index).path("agentId").asText(null))) {
                    selectedAgent = index;
                    return;
                }
            }
        }
        selectedAgent = Math.min(selectedAgent, agents.size() - 1);
        selectedAgentId = agents.get(selectedAgent).path("agentId").asText(null);
    }

    private boolean promptExpandable() {
        List<String> reflowed = reflowDetailText(transcriptSnapshot, agentDetailContentWidth);
        int prompt = reflowed.indexOf("Prompt");
        if (prompt < 0) return false;
        int end = prompt + 1;
        while (end < reflowed.size() && !reflowed.get(end).isEmpty()) end++;
        return end - prompt - 1 > 2;
    }

    private List<String> displayTranscriptLines(JsonNode agent, int contentWidth) {
        List<String> source = transcriptSnapshot.isEmpty()
            ? transcriptFallback(items.get(selected), agent) : transcriptSnapshot;
        source = reflowDetailText(source, contentWidth);
        int prompt = source.indexOf("Prompt");
        if (prompt < 0) return source;
        int promptEnd = prompt + 1;
        while (promptEnd < source.size() && !source.get(promptEnd).isEmpty()) promptEnd++;
        int lineCount = promptEnd - prompt - 1;
        if (lineCount <= 2) return source;
        if (promptExpanded) {
            List<String> expanded = new ArrayList<>(source);
            expanded.set(prompt, "Prompt · " + lineCount + " lines");
            return List.copyOf(expanded);
        }
        List<String> displayed = new ArrayList<>(source.size());
        displayed.addAll(source.subList(0, prompt));
        displayed.add("Prompt · " + lineCount + " lines · ⏎ expand");
        displayed.addAll(source.subList(prompt + 1, prompt + 3));
        int hidden = lineCount - 2;
        displayed.add("  … " + hidden + " more " + (hidden == 1 ? "line" : "lines"));
        displayed.addAll(source.subList(promptEnd, source.size()));
        return List.copyOf(displayed);
    }


    private static List<String> reflowDetailText(List<String> source, int contentWidth) {
        if (source.isEmpty()) return source;
        int textWidth = Math.max(8, contentWidth - 2);
        String section = "";
        List<String> result = new ArrayList<>(source.size());
        for (String line : source) {
            boolean heading = Strings.CS.equals(line, "Prompt")
                || Strings.CS.startsWith(line, "Activity")
                || Strings.CS.equals(line, "Outcome");
            if (Strings.CS.equals(line, "Prompt")) section = "prompt";
            else if (Strings.CS.startsWith(line, "Activity")) section = "activity";
            else if (Strings.CS.equals(line, "Outcome")) section = "outcome";
            if (!heading && StringUtils.isNotEmpty(line) && Strings.CS.equals("prompt", section)) {
                String text = Strings.CS.startsWith(line, "  ") ? line.substring(2) : line;
                List<String> wrapped = FormatUtils.wrapText(text, textWidth);
                if (wrapped.isEmpty()) result.add("  ");
                else wrapped.forEach(part -> result.add("  " + part));
            } else if (Strings.CS.startsWith(line, "  ")
                    && Strings.CS.equals("outcome", section)) {
                List<String> wrapped = FormatUtils.wrapText(line.substring(2), textWidth);
                if (wrapped.isEmpty()) result.add("  ");
                else wrapped.forEach(part -> result.add("  " + part));
            } else if (Strings.CS.startsWith(line, "  ")
                    && Strings.CS.equals("activity", section)) {
                result.add(InlineOverlay.clip(line, contentWidth));
            } else {
                result.add(line);
            }
        }
        return List.copyOf(result);
    }

    private void reloadTranscriptWhenReleasedFingerprintChanges() {
        if (mode != Mode.TRANSCRIPT || items.isEmpty()) return;
        WorkflowRun run = items.get(selected);
        JsonNode agent = selectedAgentNode(run);
        if (agent == null) return;
        String current = transcriptFingerprint(run, agent);
        if (!Strings.CS.equals(current, transcriptFingerprint) && !transcriptLoading) {
            loadSelectedTranscriptAsync();
        }
    }

    private static String transcriptFingerprint(WorkflowRun run, JsonNode agent) {
        return run.runId() + ':' + agent.path("agentId").asText("") + ':'
            + agent.path("toolCalls").asInt();
    }

    private List<PhaseRow> phaseRows(WorkflowRun run) {
        List<JsonNode> agents = agentRows(run);
        boolean hasPhaseIndexes = agents.stream().anyMatch(agent -> agent.hasNonNull("phaseIndex"));
        List<MutablePhase> runtimeGroups = new ArrayList<>();
        Map<Integer, MutablePhase> byIndex = new LinkedHashMap<>();
        Map<Integer, String> phaseTitles = new LinkedHashMap<>();
        run.workflowProgress().stream()
            .filter(item -> Strings.CS.equals("workflow_phase", item.path("type").asText()))
            .forEach(item -> {
                int index = item.path("index").asInt();
                phaseTitles.put(index, item.path("title").asText("Phase " + index));
            });
        if (hasPhaseIndexes) {
            for (JsonNode agent : agents) {
                int index = agent.path("phaseIndex").asInt(0);
                MutablePhase group = byIndex.computeIfAbsent(index, key -> {
                    String title = phaseTitles.getOrDefault(key, "Phase " + key);
                    MutablePhase created = new MutablePhase(key, title, null);
                    runtimeGroups.add(created);
                    return created;
                });
                group.agents.add(agent);
            }
            runtimeGroups.sort(Comparator.comparingInt(group -> group.index));
        }

        Set<Integer> matchedRuntimeIndexes = new HashSet<>();
        List<PhaseRow> merged = new ArrayList<>();
        for (int i = 0; i < run.phases().size(); i++) {
            var declared = run.phases().get(i);
            String normalized = normalizePhaseTitle(declared.title());
            MutablePhase match = runtimeGroups.stream()
                .filter(group -> !matchedRuntimeIndexes.contains(group.index))
                .filter(group -> phaseTitlesMatch(normalized, normalizePhaseTitle(group.title)))
                .findFirst().orElse(null);
            if (match == null) {
                merged.add(new PhaseRow(i + 1, declared.title(), declared.detail(), List.of()));
            } else {
                matchedRuntimeIndexes.add(match.index);
                merged.add(new PhaseRow(match.index, match.title, declared.detail(),
                    List.copyOf(match.agents)));
            }
        }
        runtimeGroups.stream()
            .filter(group -> !matchedRuntimeIndexes.contains(group.index))
            .map(group -> new PhaseRow(group.index, group.title, group.detail,
                List.copyOf(group.agents)))
            .forEach(merged::add);
        if (merged.isEmpty() && !agents.isEmpty()) {
            return List.of(new PhaseRow(0, "Agents", null, agents));
        }
        return List.copyOf(merged);
    }

    private static String normalizePhaseTitle(String title) {
        return title == null ? "" : title.toLowerCase(Locale.ROOT).trim();
    }

    private static boolean phaseTitlesMatch(String left, String right) {
        return Strings.CS.equals(left, right)
            || Strings.CS.startsWith(left, right)
            || Strings.CS.startsWith(right, left);
    }

    private static Mode initialDetailMode(WorkflowRun run) {
        return run.phases().isEmpty()
            && run.workflowProgress().stream().noneMatch(item ->
                Strings.CS.equals("workflow_phase", item.path("type").asText())
                    || item.hasNonNull("phaseTitle"))
            ? Mode.AGENTS : Mode.PHASES;
    }

    private boolean isAgentMode() {
        return mode == Mode.AGENTS || mode == Mode.TRANSCRIPT;
    }

    private void navigateBack() {
        if (mode == Mode.TRANSCRIPT) {
            mode = Mode.AGENTS;
            transcriptLoadGeneration.incrementAndGet();
            transcriptSnapshot = List.of();
            transcriptLoading = false;
            transcriptFingerprint = null;
            transcriptOffset = 0;
            promptExpanded = false;
        } else if (mode == Mode.AGENTS && !items.isEmpty()
                && !phaseRows(items.get(selected)).isEmpty()) {
            mode = Mode.PHASES;
            selectedAgent = 0;
            selectedAgentId = null;
            agentFilter = "all";
        } else if (isDetailMode()) {
            if (directTaskRoute || (autoOpenedSingle && items.size() <= 1)) close();
            else {
                mode = Mode.LIST;
                selectedPhase = 0;
                selectedAgent = 0;
                selectedAgentId = null;
                agentFilter = "all";
            }
        } else {
            close();
        }
        invalidate();
    }

    private void loadSelectedTranscriptAsync() {
        if (items.isEmpty()) return;
        WorkflowRun run = items.get(selected);
        JsonNode agent = selectedAgentNode(run);
        if (agent == null) return;
        boolean firstLoad = transcriptFingerprint == null;
        transcriptFingerprint = transcriptFingerprint(run, agent);
        long generation = transcriptLoadGeneration.incrementAndGet();
        if (firstLoad) transcriptSnapshot = List.of();
        transcriptLoading = true;
        Thread.ofVirtual().name("workflow-transcript").start(() -> {
            List<String> loaded = transcriptLines(run, agent);
            runOnGuiThreadIfAttached(() -> {
                if (!active || mode != Mode.TRANSCRIPT
                        || transcriptLoadGeneration.get() != generation) return;
                transcriptSnapshot = loaded;
                transcriptLoading = false;
                invalidate();
            });
        });
    }

    private void runOnGuiThreadIfAttached(Runnable action) {
        var textGui = getTextGUI();
        if (textGui != null) textGui.getGUIThread().invokeLater(action);
        else action.run();
    }

    private void moveSelection(int delta) {
        refresh();
        if (mode == Mode.LIST) {
            selected = Math.max(0, Math.min(Math.max(0, items.size() - 1), selected + delta));
        } else if (mode == Mode.PHASES && !items.isEmpty()) {
            int size = phaseRows(items.get(selected)).size();
            selectedPhase = Math.max(0, Math.min(Math.max(0, size - 1), selectedPhase + delta));
            selectedAgent = 0;
            selectedAgentId = null;
            promptExpanded = false;
        } else if (mode == Mode.AGENTS) {
            List<JsonNode> agents = selectedAgents();
            selectedAgent = Math.max(0, Math.min(Math.max(0, agents.size() - 1), selectedAgent + delta));
            selectedAgentId = agents.isEmpty() ? null
                : agents.get(selectedAgent).path("agentId").asText(null);
            promptExpanded = false;
        } else if (mode == Mode.TRANSCRIPT) {
            transcriptOffset = Math.max(0, transcriptOffset + delta);
        }
    }

    private static String agentStatus(JsonNode agent, boolean workflowRunning) {
        String state = agent.path("state").asText();
        if (Strings.CS.equals("done", state)) return "done";
        if (Strings.CS.equals("error", state)) {
            return agent.path("skipped").asBoolean(false)
                || Strings.CS.equals("skipped by user", agent.path("error").asText())
                ? "skipped" : "failed";
        }
        if (!workflowRunning) return "interrupted";
        if (agent.hasNonNull("queuedAt") && !agent.hasNonNull("startedAt")) return "queued";
        return "running";
    }

    private static List<String> transcriptLines(WorkflowRun run, JsonNode agent) {
        String agentId = agent.path("agentId").asText("");
        if (!agentId.matches("a[0-9a-f]{16}")) return transcriptFallback(run, agent);
        Path base = run.transcriptDir().toAbsolutePath().normalize();
        Path transcript = base.resolve("agent-" + agentId + ".jsonl").normalize();
        if (!transcript.getParent().equals(base) || !Files.isRegularFile(transcript)) {
            return transcriptFallback(run, agent);
        }
        String prompt = null;
        String finalText = null;
        List<ToolActivity> tools = new ArrayList<>();
        try (var reader = Files.newBufferedReader(transcript, StandardCharsets.UTF_8)) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null && count++ < 5_000) {
                if (StringUtils.isBlank(line)) continue;
                JsonNode entry;
                try {
                    entry = JsonUtils.parseTree(line);
                } catch (RuntimeException _) {
                    continue;
                }
                JsonNode message = entry.path("message");
                if (prompt == null && Strings.CS.equals("user", entry.path("type").asText())
                        && !entry.path("isMeta").asBoolean(false)) {
                    prompt = messageText(message.path("content"));
                }
                if (Strings.CS.equals("assistant", entry.path("type").asText())) {
                    JsonNode content = message.path("content");
                    if (!content.isArray()) continue;
                    StringBuilder assistantText = new StringBuilder();
                    for (JsonNode block : content) {
                        if (Strings.CS.equals("tool_use", block.path("type").asText())) {
                            String name = block.path("name").asText("tool");
                            tools.add(new ToolActivity(name, toolSummary(block.path("input"))));
                        } else if (Strings.CS.equals("text", block.path("type").asText())) {
                            if (!assistantText.isEmpty()) assistantText.append('\n');
                            assistantText.append(block.path("text").asText());
                        }
                    }
                    if (!assistantText.isEmpty()) finalText = assistantText.toString();
                }
            }
        } catch (Exception e) {
            return List.of("Transcript unavailable: "
                + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
        List<String> result = new ArrayList<>();
        result.add("Prompt");
        addTextLines(result, StringUtils.isBlank(prompt)
            ? agent.path("promptPreview").asText("(unavailable)") : prompt);
        result.add("");
        String status = agentStatus(agent, run.status() == TaskStatus.RUNNING);
        if (!Strings.CS.equals("queued", status)) {
            if (tools.size() > 3) result.add("Activity · last 3 of " + tools.size() + " tool calls");
            else result.add("Activity");
            if (tools.isEmpty()) result.add(Strings.CS.equals("running", status)
                ? "  No tool calls yet." : "  No tool calls.");
            else tools.subList(Math.max(0, tools.size() - 3), tools.size()).forEach(tool ->
                result.add("  " + tool.name() + (StringUtils.isBlank(tool.summary())
                    ? "" : "(" + tool.summary() + ")")));
            result.add("");
        }
        result.add("Outcome");
        addOutcomeLines(result, run, agent, finalText);
        return List.copyOf(result);
    }

    private static List<String> transcriptFallback(WorkflowRun run, JsonNode agent) {
        String status = agentStatus(agent, run.status() == TaskStatus.RUNNING);
        List<String> result = new ArrayList<>();
        result.add("Prompt");
        String prompt = agent.path("promptPreview").asText();
        if (StringUtils.isNotBlank(prompt)) addIndentedTextLines(result, prompt);
        else if (Strings.CS.equals("queued", status)) result.add("  Available once the agent starts.");
        else if (Strings.CS.equals("running", status)) result.add("  Not available yet (agent still running).");
        else result.add("  Transcript not available.");
        result.add("");
        if (!Strings.CS.equals("queued", status)) {
            result.add("Activity");
            if (agent.hasNonNull("lastToolName")) {
                String summary = agent.path("lastToolSummary").asText();
                result.add("  " + agent.path("lastToolName").asText()
                    + (StringUtils.isBlank(summary) ? "" : "(" + summary + ")"));
            } else {
                result.add(Strings.CS.equals("running", status)
                    ? "  No tool calls yet." : "  No tool calls.");
            }
            result.add("");
        }
        result.add("Outcome");
        addOutcomeLines(result, run, agent, null);
        return List.copyOf(result);
    }

    private static void addOutcomeLines(List<String> result, WorkflowRun run,
                                        JsonNode agent, String finalText) {
        String status = agentStatus(agent, run.status() == TaskStatus.RUNNING);
        switch (status) {
            case "queued" -> result.add("  Waiting for an agent slot.");
            case "running" -> result.add("  Still running…");
            case "interrupted" -> result.add("  The workflow stopped before this agent finished.");
            case "skipped" -> result.add("  Skipped by user.");
            case "failed" -> addIndentedTextLines(result, agent.path("error").asText("failed"));
            case "done" -> {
                String outcome = StringUtils.isBlank(finalText)
                    ? agent.path("resultPreview").asText() : finalText;
                if (StringUtils.isBlank(outcome)) result.add("  (empty)");
                else addIndentedTextLines(result, outcome);
            }
            default -> result.add("  (empty)");
        }
    }

    private static String toolSummary(JsonNode input) {
        if (!input.isObject()) return "";
        for (String key : List.of("file_path", "command", "pattern", "query", "url",
                "path", "description")) {
            JsonNode value = input.path(key);
            if (value.isTextual() && StringUtils.isNotBlank(value.asText())) {
                return InlineOverlay.clip(value.asText().replace('\n', ' '), 72);
            }
        }
        var fields = input.fields();
        while (fields.hasNext()) {
            JsonNode value = fields.next().getValue();
            if (value.isTextual() && StringUtils.isNotBlank(value.asText())) {
                return InlineOverlay.clip(value.asText().replace('\n', ' '), 72);
            }
        }
        return "";
    }

    private static String messageText(JsonNode content) {
        if (content.isTextual()) return content.asText();
        if (!content.isArray()) return null;
        StringBuilder text = new StringBuilder();
        for (JsonNode block : content) {
            if (Strings.CS.equals("text", block.path("type").asText())) {
                if (!text.isEmpty()) text.append('\n');
                text.append(block.path("text").asText());
            }
        }
        return text.toString();
    }

    private static void addTextLines(List<String> target, String text) {
        Collections.addAll(target, text.split("\\R", -1));
    }

    private static void addIndentedTextLines(List<String> target, String text) {
        for (String line : text.split("\\R", -1)) target.add("  " + line);
    }

    private record ToolActivity(String name, String summary) {}

    private record PhaseRow(int index, String title, String detail,
                            List<JsonNode> agents) {}

    private static final class MutablePhase {
        private final int index;
        private final String title;
        private final String detail;
        private final List<JsonNode> agents = new ArrayList<>();

        private MutablePhase(int index, String title, String detail) {
            this.index = index;
            this.title = title;
            this.detail = detail;
        }
    }

    private static String resumePrompt(WorkflowRun run) {
        String path = run.scriptPath().toString().replace("\\", "\\\\").replace("'", "\\'");
        String args = run.args() == null || run.args().isNull()
            ? "" : ", args: " + run.args();
        return "Resume the paused workflow by calling: Workflow({scriptPath: '"
            + path + "', resumeFromRunId: '" + run.runId() + "'" + args
            + "}) — completed agents return cached results.";
    }

    private boolean canResume(WorkflowRun run) {
        return run.status() == TaskStatus.PAUSED
            && run.scriptPath() != null
            && StringUtils.isNotBlank(run.runId())
            && resumeHandler != null;
    }

    private void beginSave() {
        WorkflowRun run = items.get(selected);
        saveReturnMode = mode;
        try {
            saveName = WorkflowScriptParser.parse(run.script()).metadata().name();
        } catch (Exception _) {
            saveName = sanitizeName(run.summary());
        }
        saveScope = SaveScope.PROJECT;
        overwritePending = null;
        notice = null;
        mode = Mode.SAVE;
    }

    private void handleSaveKey(KeyStroke key) {
        if (saveInFlight) return;
        KeyType type = key.getKeyType();
        if (type == KeyType.ESCAPE) {
            mode = saveReturnMode;
            overwritePending = null;
        } else if (type == KeyType.TAB) {
            saveScope = saveScope == SaveScope.PROJECT ? SaveScope.USER : SaveScope.PROJECT;
            overwritePending = null;
            notice = null;
        } else if (type == KeyType.ENTER) {
            saveSelectedWorkflow();
        } else if (type == KeyType.BACKSPACE && !saveName.isEmpty()) {
            saveName = saveName.substring(0, saveName.length() - 1);
            overwritePending = null;
            notice = null;
        } else if (type == KeyType.CHARACTER && key.getCharacter() != null
                && !key.isCtrlDown() && !key.isAltDown()) {
            saveName += key.getCharacter();
            overwritePending = null;
            notice = null;
        }
        invalidate();
    }

    private void saveSelectedWorkflow() {
        if (items.isEmpty() || saveName.trim().isEmpty()) return;
        WorkflowRun run = items.get(selected);
        String safeName = sanitizeName(saveName);
        SaveScope scope = saveScope;
        Path confirmedTarget = overwritePending;
        String script = run.script();
        long ticket = saveGeneration.incrementAndGet();
        saveInFlight = true;
        notice = null;
        invalidate();
        Thread.ofVirtual().name("workflow-save").start(() -> {
            Path target = confirmedTarget;
            String failure = null;
            boolean exists = false;
        try {
                if (target == null) {
                    Path directory;
                    if (scope == SaveScope.USER) {
                        directory = userWorkflowsDir;
                    } else {
                        Path root = GitUtils.findCanonicalGitRoot(cwd);
                        directory = (root == null ? cwd : root)
                            .resolve(".claude").resolve("workflows");
                    }
                    target = directory.resolve(safeName + ".js").normalize();
                }
            Files.createDirectories(target.getParent());
            setPermissions(target.getParent(), true);
                if (confirmedTarget == null) {
                    Files.writeString(target, script, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                } else {
                    Files.writeString(target, script, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
                }
            setPermissions(target, false);
            } catch (FileAlreadyExistsException _) {
                exists = true;
            } catch (Exception e) {
                failure = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            Path finalTarget = target;
            String finalFailure = failure;
            boolean finalExists = exists;
            runOnGuiThreadIfAttached(() -> {
                if (!active || saveGeneration.get() != ticket || mode != Mode.SAVE) return;
                saveInFlight = false;
                if (finalExists) {
                    overwritePending = finalTarget;
                    notice = finalTarget
                        + " already exists. Press Enter again to overwrite, or change the name.";
                } else if (finalFailure != null) {
                    notice = finalFailure;
                } else {
                    String message = "Dynamic workflow saved to " + finalTarget + ". Invoke as /"
                + safeName + " or Workflow({name: \"" + safeName
                + "\"}) in future sessions.";
            if (systemMessageHandler != null) {
                systemMessageHandler.accept(message);
                close();
            } else {
                notice = message;
                mode = saveReturnMode;
            }
        }
                invalidate();
            });
        });
    }

    private static String sanitizeName(String value) {
        String safe = value == null ? "" : value.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
        return safe.isEmpty() ? "workflow" : safe;
    }

    private static void setPermissions(Path path, boolean directory) {
        try {
            Files.setPosixFilePermissions(path, directory
                ? Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE)
                : Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException _) {
            // Windows/non-POSIX filesystems have no equivalent mode bits.
        }
    }

    private void drawSave(TextGUIGraphics graphics, int columns) {
        String safe = sanitizeName(saveName.trim().isEmpty() ? "workflow" : saveName);
        String relative = saveScope == SaveScope.PROJECT
            ? ".claude/workflows/" + safe + ".js"
            : "~/.claude/workflows/" + safe + ".js";
        graphics.setForegroundColor(LanternaTheme.welcomeDim());
        graphics.putString(2, 2, (saveScope == SaveScope.PROJECT ? "Project" : "User")
            + " scope · " + relative);
        graphics.setForegroundColor(LanternaTheme.inputText());
        graphics.putString(2, 4, "Save as:");
        graphics.putString(2, 5, "> " + saveName);
        if (saveInFlight) {
            graphics.setForegroundColor(LanternaTheme.welcomeDim());
            graphics.putString(2, 7, "Saving…");
        }
        if (notice != null) {
            graphics.setForegroundColor(LanternaTheme.toolWarning());
            graphics.putString(2, 7, InlineOverlay.clip(notice, Math.max(1, columns - 4)));
        }
        graphics.setForegroundColor(LanternaTheme.welcomeDim());
        graphics.putString(2, 9, InlineOverlay.clip(
            "Enter " + (overwritePending == null ? "save" : "overwrite")
                + " · Tab toggle scope · Esc cancel", Math.max(1, columns - 4)));
    }
}
