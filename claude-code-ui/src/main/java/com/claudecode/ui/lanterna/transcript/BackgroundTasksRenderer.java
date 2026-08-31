package com.claudecode.ui.lanterna.transcript;

import java.util.Locale;
import com.claudecode.tools.tasks.*;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.core.io.FileUtils;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.TextGUIGraphics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import org.apache.commons.lang3.StringUtils;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * Rendering companion of {@link com.claudecode.ui.lanterna.dialog.BackgroundTasksDialog}: status
 * text/color mappings shared by the list rows, plus the full DETAIL-mode views (shell + agent) and
 * their exact row-count arithmetic.
 */
public final class BackgroundTasksRenderer {

    private static final int LEFT_PAD = 2;
    private static final int DREAM_VISIBLE_TURNS = 6;
    static final int SHELL_OUTPUT_TAIL_BYTES = 8192;
    static final int SHELL_OUTPUT_MAX_LINES = 10;

    private final TaskRegistry registry;
    private final ConcurrentHashMap<String, TailResult> tailSnapshots = new ConcurrentHashMap<>();
    private final Set<String> tailLoadsInFlight = ConcurrentHashMap.newKeySet();

    public BackgroundTasksRenderer(TaskRegistry registry) {
        this.registry = registry;
    }

    // ── shared status mappings ───────────────────────────────────────────


    // ── shell output tail ────────────────────────────────────────────────


    record TailResult(List<String> lines, long totalBytes, boolean truncated) {}

    static TailResult tailOutput(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return new TailResult(List.of(), 0, false);
        }
        try {
            FileUtils.FileRange range = FileUtils.tailFile(path, SHELL_OUTPUT_TAIL_BYTES);
            long total = range.bytesTotal();
            String content = range.content();
            String[] allLines = content.split("\n", -1);
            List<String> nonEmptyTrailing = new ArrayList<>();
            for (String line : allLines) {
                if (!line.isEmpty()) nonEmptyTrailing.add(line);
            }
            int from = Math.max(0, nonEmptyTrailing.size() - SHELL_OUTPUT_MAX_LINES);
            List<String> lastLines = nonEmptyTrailing.subList(from, nonEmptyTrailing.size());
            return new TailResult(new ArrayList<>(lastLines), total,
                range.bytesRead() < range.bytesTotal());
        } catch (IOException _) {
            return new TailResult(List.of(), 0, false);
        }
    }

    Path shellOutputPath(String taskId) {
        return registry.getShellHandle(taskId).map(LocalShellTask::getOutputPath)
            .or(() -> registry.getForegroundShellHandle(taskId)
                .map(ForegroundShellTask::getOutputPath))
            .or(() -> registry.getMonitorHandle(taskId)
                .map(MonitorTaskHandle::getOutputPath))
            .orElseGet(() -> TaskOutputPaths.outputPath(taskId));
    }

    /** Refreshes the shell-output tail off the rendering thread. */
    public void refreshShellOutputAsync(String taskId, Runnable onUpdated) {
        if (taskId == null || !tailLoadsInFlight.add(taskId)) return;
        Path path = shellOutputPath(taskId);
        Thread.ofVirtual().name("task-output-tail-" + taskId).start(() -> {
            try {
                tailSnapshots.put(taskId, tailOutput(path));
            } finally {
                tailLoadsInFlight.remove(taskId);
            }
            if (onUpdated != null) onUpdated.run();
        });
    }

    private TailResult tailSnapshot(String taskId) {
        return tailSnapshots.getOrDefault(taskId, new TailResult(List.of(), 0, false));
    }

    // ── detail sizing ────────────────────────────────────────────────────

    /**
     * Exact row count the DETAIL renderer draws — kept in lockstep with
     * {@link #drawDetail} (tests pin the numbers). A fixed constant here used
     * to clip the footer as soon as the shell output grew past a couple of
     * lines.
     */
    public int detailRowCount(String taskId) {
        Optional<TaskState> maybeTask = registry.get(taskId);
        if (maybeTask.isEmpty()) return 3;
        TaskState task = maybeTask.get();
        if (task.type() == TaskType.LOCAL_BASH
                || task.type() == TaskType.MONITOR_MCP
                || task.type() == TaskType.MONITOR_WS) {
            TailResult tail = tailSnapshot(task.id());
            // divider(0) title(1) status(2) runtime(3) command(4) blank(5)
            // "Output:"(6), then N lines + note — or 1 placeholder — then
            // blank + footer.
            int outputRows = tail.lines().isEmpty() ? 1 : tail.lines().size() + 1;
            return 9 + outputRows;
        }
        // divider(0) title(1) subtitle(2) blank(3) [Progress(3 rows)]
        // "Prompt"(1) prompt(1) blank(1) footer(1)
        boolean progressShown = task.status() == TaskStatus.RUNNING
            && registry.getAgentHandle(task.id()).isPresent();
        if (task.type() == TaskType.DREAM) {
            DreamTaskDetails details = task.dreamDetails()
                .orElseGet(() -> DreamTaskDetails.starting(0));
            List<DreamTaskDetails.DreamTurn> shown = visibleDreamTurns(details);
            int hiddenRows = details.turns().stream().filter(t -> !t.text().isEmpty()).count()
                > shown.size() ? 1 : 0;
            int turnRows = shown.stream().mapToInt(t -> 1 + (t.toolUseCount() > 0 ? 1 : 0)).sum();
            // divider, title, subtitle, gap, status, gap, content, gap, footer.
            return 8 + hiddenRows + Math.max(1, turnRows);
        }
        return progressShown ? 11 : 8;
    }

    // ── detail drawing ───────────────────────────────────────────────────

    /** Draws the DETAIL view for {@code taskId} (shell, agent, or dream). */
    public void drawDetail(TextGUIGraphics g, int cols, String taskId) {
        Optional<TaskState> maybeTask = registry.get(taskId);
        if (maybeTask.isEmpty()) {
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, 1, "Task no longer available.");
            return;
        }
        TaskState task = maybeTask.get();
        if (task.type() == TaskType.LOCAL_BASH
                || task.type() == TaskType.MONITOR_MCP
                || task.type() == TaskType.MONITOR_WS) {
            drawShellDetail(g, cols, task);
        } else if (task.type() == TaskType.DREAM) {
            drawDreamDetail(g, cols, task);
        } else {
            drawAgentDetail(g, cols, task);
        }
    }


    private int drawStatusAndRuntime(TextGUIGraphics g, TaskState task) {
        int row = 2;
        g.setForegroundColor(LanternaTheme.inputText());
        g.putString(LEFT_PAD, row, "Status: ");
        // Verbatim status: running→dim, completed→success, else→error.
        TaskStatus st = task.status();
        g.setForegroundColor(switch (st) {
            case RUNNING, PENDING, PAUSED -> LanternaTheme.welcomeDim();
            case COMPLETED -> LanternaTheme.toolSuccess();
            case FAILED, KILLED -> LanternaTheme.toolError();
        });
        g.putString(LEFT_PAD + 8, row, statusText(task));
        row++;

        long endMs = task.endTime().map(Instant::toEpochMilli).orElse(System.currentTimeMillis());
        long runtimeMs = endMs - task.startTime().toEpochMilli();
        g.setForegroundColor(LanternaTheme.inputText());
        g.putString(LEFT_PAD, row, "Runtime: " + FormatUtils.formatDuration(Math.max(0, runtimeMs)));
        return row + 1;
    }

    static String statusText(TaskState task) {
        String status = task.status().name().toLowerCase(Locale.ROOT);
        return task.exitCode().map(code -> status + " (exit code: " + code + ")")
            .orElse(status);
    }

    private void drawShellDetail(TextGUIGraphics g, int cols, TaskState task) {
        boolean monitor = registry.isMonitorTask(task.id());
        g.setForegroundColor(LanternaTheme.permission());
        g.enableModifiers(SGR.BOLD);
        g.putString(LEFT_PAD, 1, monitor ? "Monitor details" : "Shell details");
        g.disableModifiers(SGR.BOLD);

        int row = drawStatusAndRuntime(g, task);

        Optional<LocalShellTask> handle = registry.getShellHandle(task.id());
        String command = handle.map(LocalShellTask::getCommand)
            .or(() -> registry.getMonitorHandle(task.id())
                .map(MonitorTaskHandle::displaySource))
            .orElse(task.description());
        // UI-local width-aware clip; core FormatUtils now has matching width semantics.
        g.putString(LEFT_PAD, row, (monitor ? "Script: " : "Command: ")
            + InlineOverlay.clip(command, 280));
        row += 2;

        g.enableModifiers(SGR.BOLD);
        g.putString(LEFT_PAD, row, "Output:");
        g.disableModifiers(SGR.BOLD);
        row++;

        // NOTE: keep this block's row arithmetic in lockstep with
        // detailRowCount() — it sizes the component around these rows.
        TailResult tail = tailSnapshot(task.id());
        // Both branches end by writing one summary line in the dim colour and
        // advancing the row, so compute the text and emit it once.
        String outputText;
        if (tail.lines().isEmpty()) {
            outputText = "No output available";
        } else {
            for (String line : tail.lines()) {
                g.setForegroundColor(LanternaTheme.inputText());
                g.putString(LEFT_PAD, row, InlineOverlay.clip(line, cols - LEFT_PAD - 2));
                row++;
            }
            outputText = "Showing " + tail.lines().size() + " lines"
                + (tail.truncated() ? " of " + tail.totalBytes() + " bytes" : "");
        }
        g.setForegroundColor(LanternaTheme.welcomeDim());
        g.putString(LEFT_PAD, row, outputText);
        row++;

        g.setForegroundColor(LanternaTheme.welcomeDim());
        g.enableModifiers(SGR.ITALIC);
        g.putString(LEFT_PAD, row + 1, detailFooterHint(task));
        g.disableModifiers(SGR.ITALIC);
    }


    private void drawDreamDetail(TextGUIGraphics g, int cols, TaskState task) {
        g.setForegroundColor(LanternaTheme.permission());
        g.enableModifiers(SGR.BOLD);
        g.putString(LEFT_PAD, 1, "Memory consolidation");
        g.disableModifiers(SGR.BOLD);

        DreamTaskDetails details = task.dreamDetails()
            .orElseGet(() -> DreamTaskDetails.starting(0));
        long endMs = task.endTime().map(Instant::toEpochMilli).orElse(System.currentTimeMillis());
        long runtimeMs = Math.max(0, endMs - task.startTime().toEpochMilli());
        String sessionWord = details.sessionsReviewing() == 1 ? "session" : "sessions";
        StringBuilder subtitle = new StringBuilder(FormatUtils.formatDuration(runtimeMs))
            .append(" · reviewing ").append(details.sessionsReviewing()).append(' ').append(sessionWord);
        if (!details.filesTouched().isEmpty()) {
            subtitle.append(" · ").append(details.filesTouched().size()).append(' ')
                .append(details.filesTouched().size() == 1 ? "file" : "files").append(" touched");
        }
        g.setForegroundColor(LanternaTheme.welcomeDim());
        g.putString(LEFT_PAD, 2, InlineOverlay.clip(subtitle.toString(), cols - LEFT_PAD - 2));

        int row = 4;
        g.setForegroundColor(LanternaTheme.inputText());
        g.putString(LEFT_PAD, row, "Status: ");
        g.setForegroundColor(switch (task.status()) {
            case RUNNING, PENDING, PAUSED -> LanternaTheme.welcomeDim();
            case COMPLETED -> LanternaTheme.toolSuccess();
            case FAILED, KILLED -> LanternaTheme.toolError();
        });
        g.putString(LEFT_PAD + 8, row, task.status().name().toLowerCase(Locale.ROOT));
        row += 2;

        List<DreamTaskDetails.DreamTurn> shown = visibleDreamTurns(details);
        long visibleCount = details.turns().stream().filter(t -> !t.text().isEmpty()).count();
        long hidden = visibleCount - shown.size();
        if (shown.isEmpty()) {
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, row,
                task.status() == TaskStatus.RUNNING ? "Starting…" : "(no text output)");
            row++;
        } else {
            if (hidden > 0) {
                g.setForegroundColor(LanternaTheme.welcomeDim());
                g.putString(LEFT_PAD, row, "(" + hidden + " earlier "
                    + (hidden == 1 ? "turn" : "turns") + ")");
                row++;
            }
            for (DreamTaskDetails.DreamTurn turn : shown) {
                g.setForegroundColor(LanternaTheme.inputText());
                g.putString(LEFT_PAD, row,
                    InlineOverlay.clip(turn.text(), cols - LEFT_PAD - 2));
                row++;
                if (turn.toolUseCount() > 0) {
                    g.setForegroundColor(LanternaTheme.welcomeDim());
                    g.putString(LEFT_PAD + 2, row, "(" + turn.toolUseCount() + " "
                        + (turn.toolUseCount() == 1 ? "tool" : "tools") + ")");
                    row++;
                }
            }
        }

        g.setForegroundColor(LanternaTheme.welcomeDim());
        g.enableModifiers(SGR.ITALIC);
        g.putString(LEFT_PAD, row + 1, detailFooterHint(task));
        g.disableModifiers(SGR.ITALIC);
    }

    static List<DreamTaskDetails.DreamTurn> visibleDreamTurns(DreamTaskDetails details) {
        List<DreamTaskDetails.DreamTurn> textTurns = details.turns().stream()
            .filter(turn -> !turn.text().isEmpty())
            .toList();
        int from = Math.max(0, textTurns.size() - DREAM_VISIBLE_TURNS);
        return textTurns.subList(from, textTurns.size());
    }

    private void drawAgentDetail(TextGUIGraphics g, int cols, TaskState task) {
        g.setForegroundColor(LanternaTheme.permission());
        g.enableModifiers(SGR.BOLD);
        g.putString(LEFT_PAD, 1,
            InlineOverlay.clip(agentDetailTitle(task), cols - LEFT_PAD - 2));
        g.disableModifiers(SGR.BOLD);


        int row = 2;
        int x = LEFT_PAD;
        TaskStatus st = task.status();
        if (st.isTerminal()) {
            String word = switch (st) {
                case COMPLETED -> "Completed";
                case FAILED -> "Failed";
                case PAUSED -> "Paused";
                default -> "Stopped";
            };
            String terminalPart = statusIcon(st) + " " + word + " · ";
            g.setForegroundColor(statusColor(st));
            g.putString(x, row, terminalPart);
            x += terminalPart.length();
        }
        long endMs = task.endTime().map(Instant::toEpochMilli).orElse(System.currentTimeMillis());
        long elapsedMs = endMs - task.startTime().toEpochMilli();
        g.setForegroundColor(LanternaTheme.welcomeDim());
        g.putString(x, row, FormatUtils.formatDuration(Math.max(0, elapsedMs)));
        row += 2;

        // NOTE: keep the progress-block row arithmetic in lockstep with
// detailRowCount.
        Optional<LocalAgentTask> handle = registry.getAgentHandle(task.id());
        if (task.status() == TaskStatus.RUNNING && handle.isPresent()) {
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.enableModifiers(SGR.BOLD);
            g.putString(LEFT_PAD, row, "Progress");
            g.disableModifiers(SGR.BOLD);
            row++;
            LocalAgentTask h = handle.get();
            int pct = (int) Math.round(h.getProgress() * 100);
            g.setForegroundColor(LanternaTheme.inputText());
            g.putString(LEFT_PAD, row, "— " + h.getCurrentStep() + " (" + pct + "%)");
            row += 2;
        }

        g.setForegroundColor(LanternaTheme.welcomeDim());
        g.enableModifiers(SGR.BOLD);
        g.putString(LEFT_PAD, row, "Prompt");
        g.disableModifiers(SGR.BOLD);
        row++;
        g.setForegroundColor(LanternaTheme.inputText());
        g.putString(LEFT_PAD, row, InlineOverlay.clip(agentPrompt(task), 300));
        row++;

        g.setForegroundColor(LanternaTheme.welcomeDim());
        g.enableModifiers(SGR.ITALIC);
        g.putString(LEFT_PAD, row + 1, detailFooterHint(task));
        g.disableModifiers(SGR.ITALIC);
    }

    String agentDetailTitle(TaskState task) {
        String agentType = registry.store().agentType(task.id()).orElse("agent");
        String description = StringUtils.isBlank(task.description())
            ? "Async agent" : task.description();
        return agentType + " › " + description;
    }

    String agentPrompt(TaskState task) {
        return registry.store().prompt(task.id()).orElse(task.description());
    }

    private static String detailFooterHint(TaskState task) {
        StringBuilder sb = new StringBuilder("→ go back · Esc/Enter/Space close");
        if (task.status() == TaskStatus.RUNNING) sb.append(" · x stop");
        return sb.toString();
    }

    // The three helpers below were removed by an unrelated in-progress UI
    // refactor but are still referenced by drawAgentDetail / BackgroundTasksDialog
    // (and are needed to compile this module). Restored here as conservative
    // ASCII-safe implementations so the dream changes can be verified; the
    // in-progress refactor should reconcile/re-remove these and update callers.
    private static String statusIcon(TaskStatus st) {
        return switch (st) {
            case COMPLETED -> "*";
            case FAILED, KILLED -> "x";
            case RUNNING, PENDING, PAUSED -> "~";
            default -> "?";
        };
    }

    public static TextColor statusColor(TaskStatus st) {
        return switch (st) {
            case COMPLETED -> LanternaTheme.toolSuccess();
            case FAILED, KILLED -> LanternaTheme.toolError();
            case RUNNING, PENDING, PAUSED -> LanternaTheme.welcomeDim();
            default -> LanternaTheme.inputText();
        };
    }

    public static String rowStatusSuffix(TaskState task) {
        TaskStatus st = task.status();
        return switch (task.type()) {
            case LOCAL_BASH, MONITOR_MCP, MONITOR_WS -> shellSuffix(st);
            case LOCAL_AGENT -> agentSuffix(task, st);
            default -> "(" + st.name().toLowerCase(Locale.ROOT) + ")";
        };
    }

    private static String shellSuffix(TaskStatus st) {

        if (st == TaskStatus.RUNNING || st == TaskStatus.PENDING) return "(running)";
        return switch (st) {
            case COMPLETED -> "(done)";
            case FAILED -> "(error)";
            case KILLED -> "(stopped)";
            default -> "(" + st.name().toLowerCase(Locale.ROOT) + ")";
        };
    }

    private static String agentSuffix(TaskState task, TaskStatus st) {

        // not yet notified); other states render as "(status)".
        if (st == TaskStatus.COMPLETED) {
            return task.notified() ? "(done)" : "(done, unread)";
        }
        return "(" + st.name().toLowerCase(Locale.ROOT) + ")";
    }
}
