package com.claudecode.ui.lanterna.repl;

import com.claudecode.core.constants.Figures;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.workflows.WorkflowRun;
import com.fasterxml.jackson.databind.JsonNode;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.TextGUIGraphics;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.input.CoordinatorPanelView;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * The subagent coordinator panel — a persistent vertical list rendered inside {@link InputPanel},
 * before its final Collaboration row, showing a {@code main} leader row followed by one row per
 * visible {@code local_agent} subagent.
 */
public final class CoordinatorTaskPanel extends AbstractComponent<CoordinatorTaskPanel>
        implements CoordinatorPanelView {


    static final String MAIN_LABEL = "main";
    private static final int AGENT_VIEWPORT = 5;

    /** An immutable snapshot of everything the renderer needs for one frame. */
    record Snapshot(
        List<TaskState> agents,
        List<WorkflowRun> workflows,
        int selectedIndex,      // -1 = nothing selected, 0 = main, 1..n = nth agent
        int selectedWorkflowIndex,
        String viewingTaskId,   // null when viewing main
        Instant now,
        Function<String, String> nameResolver,
        ToIntFunction<String> pendingCountResolver
    ) {
        Snapshot(List<TaskState> agents, List<WorkflowRun> workflows,
                 int selectedIndex, int selectedWorkflowIndex,
                 String viewingTaskId, Instant now,
                 Function<String, String> nameResolver) {
            this(agents, workflows, selectedIndex, selectedWorkflowIndex,
                viewingTaskId, now, nameResolver, _ -> 0);
        }

        static Snapshot empty() {
            return new Snapshot(List.of(), List.of(), -1, -1, null, Instant.EPOCH,
                _ -> null, _ -> 0);
        }
    }

    private volatile Snapshot snapshot = Snapshot.empty();

    /** Replace the displayed snapshot. Safe to call from any thread. */
    @Override
    public void refresh(List<TaskState> agents,
                        List<WorkflowRun> workflows,
                        int selectedIndex,
                        int selectedWorkflowIndex,
                        String viewingTaskId,
                        Instant now,
                        Function<String, String> nameResolver) {
        refresh(agents, workflows, selectedIndex, selectedWorkflowIndex,
            viewingTaskId, now, nameResolver, _ -> 0);
    }

    @Override
    public void refresh(List<TaskState> agents,
                        List<WorkflowRun> workflows,
                        int selectedIndex,
                        int selectedWorkflowIndex,
                        String viewingTaskId,
                        Instant now,
                        Function<String, String> nameResolver,
                        ToIntFunction<String> pendingCountResolver) {
        this.snapshot = new Snapshot(
            agents != null ? List.copyOf(agents) : List.of(),
            workflows != null ? List.copyOf(workflows) : List.of(),
            selectedIndex,
            selectedWorkflowIndex,
            viewingTaskId,
            now != null ? now : Instant.EPOCH,
            nameResolver != null ? nameResolver : _ -> null,
            pendingCountResolver != null ? pendingCountResolver : _ -> 0);
        invalidate();
    }

    @Override
    protected ComponentRenderer<CoordinatorTaskPanel> createDefaultRenderer() {
        return new CoordinatorRenderer();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Pure projection (testable without Lanterna)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * A single rendered row: its text plus the styling flags the renderer needs.
     * {@code bulletColor} names the AgentLine bullet's completion color
     * ({@code "success"}/{@code "error"}/{@code null}); the leader row is always
     * {@code null} (uncolored). {@code bulletStart}/{@code bulletEnd} bound the
     * substring the color applies to so the rest of the row stays default-colored.
     */
    record Row(String text, boolean viewed, boolean dim, boolean bold,
               String bulletColor, int bulletStart, int bulletEnd) {}

    private record AgentProjection(TaskState task, String label, String description,
                                   String status) {}

    private record WorkflowProjection(WorkflowRun run, String label, String description,
                                      String status, String bulletColor) {}

    /**
 * Projects the snapshot into content rows (leading margin excluded): the {@code main} leader
 * followed by one row per visible agent, laid out for a terminal {@code columns} wide.
     */
    static List<Row> projectRows(Snapshot s, int columns) {
        List<Row> rows = new ArrayList<>();
        List<AgentProjection> agents = new ArrayList<>(s.agents().size());
        List<WorkflowProjection> workflows = new ArrayList<>(s.workflows().size());
        int labelWidth = 4;
        int statusWidth = 0;
        for (TaskState task : s.agents()) {
            String resolved = s.nameResolver().apply(task.id());
            String label = StringUtils.isNotBlank(resolved) && !resolved.equals(task.id())
                ? resolved + ":" : "";
            String description = task.progressSummary()
                .filter(x -> !StringUtils.isBlank(x))
                .orElse(task.description());
            String status = FormatUtils.formatDuration(elapsedMillis(task, s.now()));
            int pending = Math.max(0, s.pendingCountResolver().applyAsInt(task.id()));
            if (pending > 0) status += " · " + pending + " queued";
            if (task.usage().isPresent() && task.usage().get().totalTokens() > 0) {
                String arrow = task.usage().get().toolUses() > 0
                    ? Figures.DOWN_ARROW : Figures.UP_ARROW;
                status += " · " + arrow + " "
                    + FormatUtils.formatNumber(task.usage().get().totalTokens()) + " tokens";
            }
            agents.add(new AgentProjection(task, label, description, status));
            labelWidth = Math.max(labelWidth, FormatUtils.displayWidth(label));
            statusWidth = Math.max(statusWidth, FormatUtils.displayWidth(status));
        }
        for (WorkflowRun run : s.workflows()) {
            WorkflowProjection projection = workflowProjection(run, s.now());
            workflows.add(projection);
            labelWidth = Math.max(labelWidth, FormatUtils.displayWidth(projection.label()));
            statusWidth = Math.max(statusWidth, FormatUtils.displayWidth(projection.status()));
        }
        labelWidth = Math.min(28, labelWidth);

        int viewedAgent = -1;
        for (int i = 0; i < agents.size(); i++) {
            if (agents.get(i).task().id().equals(s.viewingTaskId())) {
                viewedAgent = i;
                break;
            }
        }
        int anchor = s.selectedIndex() >= 1 ? s.selectedIndex() - 1 : viewedAgent;
        anchor = Math.max(0, Math.min(anchor, Math.max(0, agents.size() - 1)));
        int windowStart = Math.max(0,
            Math.min(anchor - AGENT_VIEWPORT + 1, Math.max(0, agents.size() - AGENT_VIEWPORT)));
        int windowEnd = Math.min(windowStart + AGENT_VIEWPORT, agents.size());
        int moreAbove = windowStart;
        int moreBelow = agents.size() - windowEnd;

        if (!s.agents().isEmpty()) {
            boolean mainViewed = s.viewingTaskId() == null;
            boolean mainSelected = s.selectedIndex() == 0;
            String mainPrefix = mainSelected ? Figures.POINTER + " " : "  ";
            String mainBullet = mainViewed ? Figures.BLACK_CIRCLE : Figures.CIRCLE;
            String mainText = mainPrefix + mainBullet + " " + MAIN_LABEL;
            if (moreAbove > 0) {
                String more = "↑ " + moreAbove + " more";
                mainText += " ".repeat(Math.max(1, columns
                    - FormatUtils.displayWidth(mainText) - FormatUtils.displayWidth(more))) + more;
            }
            rows.add(new Row(
                mainText,
                mainViewed,
                !mainSelected && !mainViewed,
                mainViewed,
                null, -1, -1));
        }

        for (int i = windowStart; i < windowEnd; i++) {
            AgentProjection projection = agents.get(i);
            TaskState task = projection.task();
            boolean viewed = task.id().equals(s.viewingTaskId());
            boolean selected = s.selectedIndex() == i + 1;
            boolean highlighted = selected;

            String prefix = highlighted ? Figures.POINTER + " " : "  ";
            String bullet = viewed ? Figures.BLACK_CIRCLE : Figures.CIRCLE;


            // subagent's bullet takes its status color (green/red); running is dim.
            String bulletColor = bulletColor(task);

            String head = prefix + bullet + " ";
            int bulletStart = prefix.length();
            int bulletEnd = bulletStart + bullet.length();
            rows.add(new Row(
                alignedRow(head, projection.label(), projection.description(),
                    projection.status(), labelWidth, statusWidth, columns),
                viewed,
                !highlighted && !viewed,
                viewed,
                bulletColor, bulletStart, bulletEnd));
        }
        if (agents.size() > AGENT_VIEWPORT) {
            String more = moreBelow > 0 ? "↓ " + moreBelow + " more" : "";
            rows.add(new Row(" ".repeat(Math.max(0,
                columns - FormatUtils.displayWidth(more))) + more,
                false, true, false, null, -1, -1));
        }
        for (int i = 0; i < workflows.size(); i++) {
            rows.add(workflowRow(workflows.get(i), columns, s.selectedWorkflowIndex() == i,
                labelWidth, statusWidth));
        }
        return rows;
    }


    private static Row workflowRow(WorkflowProjection projection, int columns,
                                   boolean selected, int labelWidth, int statusWidth) {
        String prefix = selected ? Figures.POINTER + " " : "  ";
        String bullet = Figures.CIRCLE;
        String head = prefix + bullet + " ";
        int bulletStart = prefix.length();
        return new Row(alignedRow(head, projection.label(), projection.description(),
            projection.status(), labelWidth, statusWidth, columns),
            false, !selected, false, projection.bulletColor(),
            bulletStart, bulletStart + bullet.length());
    }

    private static WorkflowProjection workflowProjection(WorkflowRun run, Instant now) {
        String name = firstNonBlank(run.workflowName(), run.title(), run.summary(), run.runId());
        String description = firstNonBlank(run.summary(), run.title(), "");
        int colon = description.indexOf(':');
        if (colon >= 0) description = description.substring(0, colon).trim();
        if (description.equals(name)) description = "";

        int done = 0;
        int failed = 0;
        int observed = 0;
        for (JsonNode item : run.workflowProgress()) {
            if (!Strings.CS.equals("workflow_agent", item.path("type").asText())) continue;
            observed++;
            String state = item.path("state").asText();
            if (Strings.CS.equalsAny(state, "done", "completed")) done++;
            else if (Strings.CS.equalsAny(state, "error", "failed")) failed++;
        }
        int total = Math.max(run.agentCount(), observed);
        long duration = run.status().isTerminal() && run.durationMs() > 0
            ? run.durationMs() : Math.max(0L, now.toEpochMilli() - run.startTime());
        StringBuilder status = new StringBuilder()
            .append(done).append('/').append(total).append(" agents done");
        if (failed > 0) status.append(" · ").append(failed).append(" failed");
        status.append(" · ").append(FormatUtils.formatDuration(duration));
        if (run.totalTokens() > 0) {
            status.append(" · ↓ ").append(FormatUtils.formatTokens(run.totalTokens())).append(" tokens");
        }
        String bulletColor = run.status().isTerminal()
            ? workflowBulletColor(run.status()) : failed > 0 ? "error" : null;
        return new WorkflowProjection(run, name, description, status.toString(), bulletColor);
    }


    private static String alignedRow(String head, String label, String description, String status,
                                     int labelWidth, int statusWidth, int columns) {
        int headWidth = FormatUtils.displayWidth(head);
        int available = Math.max(0, columns - headWidth);
        int actualLabelWidth = Math.min(labelWidth, available);
        String labelCell = padRight(FormatUtils.truncate(label, actualLabelWidth), actualLabelWidth);
        available -= actualLabelWidth;
        int actualStatusWidth = Math.min(statusWidth, Math.max(0, available - 1));
        int descriptionWidth = Math.max(0, available - actualStatusWidth - (actualStatusWidth > 0 ? 1 : 0));
        String descriptionText = descriptionWidth >= 2 && StringUtils.isNotEmpty(description)
            ? "  " + FormatUtils.truncate(description, descriptionWidth - 2) : "";
        String descriptionCell = padRight(descriptionText, descriptionWidth);
        String statusCell = actualStatusWidth > 0
            ? " " + padLeft(FormatUtils.truncate(status, actualStatusWidth), actualStatusWidth) : "";
        return head + labelCell + descriptionCell + statusCell;
    }

    private static String padRight(String value, int width) {
        return value + " ".repeat(Math.max(0, width - FormatUtils.displayWidth(value)));
    }

    private static String padLeft(String value, int width) {
        return " ".repeat(Math.max(0, width - FormatUtils.displayWidth(value))) + value;
    }

    private static String workflowBulletColor(TaskStatus status) {
        return switch (status) {
            case COMPLETED -> "success";
            case FAILED, KILLED -> "error";
            default -> null;
        };
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) return value;
        }
        return "";
    }


    static String bulletColor(TaskState task) {
        if (!task.status().isTerminal()) return null;
        return switch (task.status()) {
            case COMPLETED -> "success";
            case FAILED, KILLED -> "error";
            default -> null;
        };
    }


    private static long elapsedMillis(TaskState task, Instant now) {
        Instant start = task.startTime();
        Instant end = task.status().isTerminal()
            ? task.endTime().orElse(start)
            : now;
        long ms = end.toEpochMilli() - start.toEpochMilli();
        return Math.max(0, ms);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Renderer
    // ──────────────────────────────────────────────────────────────────────────

    private final class CoordinatorRenderer implements ComponentRenderer<CoordinatorTaskPanel> {

        @Override
        public TerminalSize getPreferredSize(CoordinatorTaskPanel c) {
            if (!c.isVisible()) return TerminalSize.of(0, 0);
            Snapshot s = snapshot;
            if (s.agents().isEmpty() && s.workflows().isEmpty()) return TerminalSize.of(0, 0);
// 1 leading margin + optional main/agents +.
            int visibleAgents = Math.min(AGENT_VIEWPORT, s.agents().size());
            int rows = 1 + (s.agents().isEmpty() ? 0 : 1 + visibleAgents
                + (s.agents().size() > AGENT_VIEWPORT ? 1 : 0))
                + s.workflows().size();
            // The parent stretches this component to the prompt's full width,
            // but Lanterna first negotiates from preferred size. Advertising a
            // single column can leave the ETf workflow row pinned to only its
            // pointer/circle during a dynamic relayout. Keep a compact natural
            // width while allowing the vertical FILL layout to expand it.
            int naturalWidth = projectRows(s, 80).stream()
                .mapToInt(row -> FormatUtils.displayWidth(row.text()))
                .max().orElse(40);
            return new TerminalSize(Math.max(40, Math.min(80, naturalWidth)), rows);
        }

        @Override
        public void drawComponent(TextGUIGraphics g, CoordinatorTaskPanel c) {
            if (!c.isVisible()) return;
            Snapshot s = snapshot;
            if (s.agents().isEmpty() && s.workflows().isEmpty()) return;

            int cols = g.getSize().getColumns();
            g.fill(' ');

            List<Row> rows = projectRows(s, cols);
            int y = 1; // row 0 is the marginTop blank line
            for (Row row : rows) {
                TextColor base = row.dim() ? LanternaTheme.welcomeDim() : LanternaTheme.inputText();
                if (row.bold()) g.enableModifiers(SGR.BOLD); else g.disableModifiers(SGR.BOLD);

                String text = FormatUtils.truncate(row.text(), Math.max(1, cols));
                TextColor bulletColor = resolveBulletColor(row.bulletColor());
                if (bulletColor == null || row.bulletStart() < 0 || row.bulletEnd() > text.length()) {
                    g.setForegroundColor(base);
                    g.putString(0, y, text);
                } else {
                    // Paint the bullet in its completion color, the rest in base.
                    String before = text.substring(0, row.bulletStart());
                    String bullet = text.substring(row.bulletStart(), row.bulletEnd());
                    String after = text.substring(row.bulletEnd());
                    g.setForegroundColor(base);
                    g.putString(0, y, before);
                    g.setForegroundColor(bulletColor);
                    g.putString(FormatUtils.displayWidth(before), y, bullet);
                    g.setForegroundColor(base);
                    g.putString(FormatUtils.displayWidth(before + bullet), y, after);
                }
                y++;
            }
            g.disableModifiers(SGR.BOLD);
        }


        private static TextColor resolveBulletColor(String name) {
            if (name == null) return null;
            return switch (name) {
                case "success" -> LanternaTheme.toolSuccess();
                case "error" -> LanternaTheme.toolError();
                default -> null;
            };
        }
    }
}
