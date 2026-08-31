package com.claudecode.ui.lanterna.dialog;

import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.message.GoalStatusAttachment;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.core.text.FormatUtils;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
/**
 * Read-only status panel opened by interactive {@code /goal} with no argument.
 */
public final class GoalDialog extends Panel implements InlineOverlay {

    private static final int LEFT_PAD = 2;
    private static final int WIDTH = 78;
    private static final ScheduledExecutorService REFRESH_TIMER =
        Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "goal-dialog-refresh");
            thread.setDaemon(true);
            return thread;
        });

    private record Row(String text, TextColor color, boolean bold) {
        static Row title(String text, TextColor color) { return new Row(text, color, true); }
        static Row text(String text) { return new Row(text, LanternaTheme.inputText(), false); }
        static Row dim(String text) { return new Row(text, LanternaTheme.welcomeDim(), false); }
    }

    private final Body body = new Body();
    private boolean active;
    private List<Row> rows = List.of();
    private Runnable onClose;
    private Supplier<HookDispatcher.ActiveGoal> activeGoalSupplier;
    private LongSupplier nowSupplier;
    private LongSupplier tokenSupplier;
    private ScheduledFuture<?> refreshTask;

    public GoalDialog() {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        body.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(body);
    }

    public synchronized void showActive(HookDispatcher.ActiveGoal goal,
                                        long nowMillis,
                                        long currentTokens,
                                        Runnable onClose) {
        showActive(() -> goal, () -> nowMillis, () -> currentTokens, onClose, false);
    }

    /**
     * Live active-goal view.
     */
    public synchronized void showActive(Supplier<HookDispatcher.ActiveGoal> goalSupplier,
                                        LongSupplier nowSupplier,
                                        LongSupplier tokenSupplier,
                                        Runnable onClose) {
        showActive(goalSupplier, nowSupplier, tokenSupplier, onClose, true);
    }

    private void showActive(Supplier<HookDispatcher.ActiveGoal> goalSupplier,
                            LongSupplier nowSupplier,
                            LongSupplier tokenSupplier,
                            Runnable onClose,
                            boolean scheduleRefresh) {
        cancelRefresh();
        this.activeGoalSupplier = goalSupplier;
        this.nowSupplier = nowSupplier;
        this.tokenSupplier = tokenSupplier;
        this.onClose = onClose;
        this.active = true;
        refreshActive();
        if (scheduleRefresh) {
            refreshTask = REFRESH_TIMER.scheduleAtFixedRate(this::refreshActive,
                1, 1, TimeUnit.SECONDS);
        }
    }

    synchronized void refreshActive() {
        if (!active || activeGoalSupplier == null) return;
        HookDispatcher.ActiveGoal goal = activeGoalSupplier.get();
        if (goal == null) return;
        long nowMillis = nowSupplier.getAsLong();
        long currentTokens = tokenSupplier.getAsLong();
        long duration = Math.max(0L, nowMillis - goal.setAtMillis());
        long tokens = Math.max(0L, currentTokens - goal.tokensAtStart());
        List<Row> next = new ArrayList<>();
        next.add(Row.title("✶ Goal active", LanternaTheme.claude()));
        next.add(Row.dim("running " + FormatUtils.formatDuration(duration, true, true)
            + (goal.iterations() > 0
                ? " · " + goal.iterations() + " " + plural(goal.iterations(), "turn") : "")
            + " · " + FormatUtils.formatTokens(tokens) + " tokens"));
        next.add(Row.dim("Goal"));
        next.add(Row.text(goal.condition()));
        if (StringUtils.isNotBlank(goal.lastReason())) {
            next.add(Row.dim("Last check"));
            next.add(Row.text(goal.lastReason()));
        }
        next.add(Row.dim("/goal clear to stop early"));
        this.rows = List.copyOf(next);
        invalidate();
    }

    public synchronized void showLatest(GoalStatusAttachment status, Runnable onClose) {
        int turns = status.iterations() != null ? status.iterations() : 0;
        long duration = status.durationMs() != null ? status.durationMs() : 0L;
        long tokens = status.tokens() != null ? status.tokens() : 0L;
        List<Row> next = new ArrayList<>();
        next.add(Row.title("Goal achieved", LanternaTheme.toolSuccess()));
        next.add(Row.dim(FormatUtils.formatDuration(duration, true, true)
            + " · " + turns + " " + plural(turns, "turn")
            + " · " + FormatUtils.formatTokens(tokens) + " tokens"));
        next.add(Row.dim("Goal"));
        next.add(Row.text(status.condition()));
        next.add(Row.dim("/goal <condition> to set another"));
        show(next, onClose);
    }

    public synchronized void showNone(Runnable onClose) {
        show(List.of(
            Row.title("Goal", LanternaTheme.claude()),
            Row.dim("No goal set"),
            Row.dim("/goal <condition> to set one")), onClose);
    }

    private void show(List<Row> rows, Runnable onClose) {
        cancelRefresh();
        activeGoalSupplier = null;
        nowSupplier = null;
        tokenSupplier = null;
        this.rows = List.copyOf(rows);
        this.onClose = onClose;
        this.active = true;
        invalidate();
    }

    public synchronized void hide() {
        if (!active) return;
        Runnable callback = onClose;
        cancelRefresh();
        activeGoalSupplier = null;
        nowSupplier = null;
        tokenSupplier = null;
        active = false;
        rows = List.of();
        onClose = null;
        invalidate();
        if (callback != null) callback.run();
    }

    @Override public synchronized boolean isActive() { return active; }

    @Override
    public synchronized void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!active) return;
        if (key.getKeyType() == KeyType.ESCAPE) {
            hide();
            deliver.set(false);
        }
    }

    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        return active ? new TerminalSize(WIDTH, rows.size() + 2) : new TerminalSize(0, 0);
    }

    @Override public Interactable nextFocus(Interactable fromThis) {
        return isActive() ? super.nextFocus(fromThis) : null;
    }

    @Override public Interactable previousFocus(Interactable fromThis) {
        return isActive() ? super.previousFocus(fromThis) : null;
    }

    synchronized List<String> lineTexts() {
        return rows.stream().map(Row::text).toList();
    }

    private static String plural(int count, String singular) {
        return count == 1 ? singular : singular + "s";
    }

    private void cancelRefresh() {
        if (refreshTask != null) {
            refreshTask.cancel(false);
            refreshTask = null;
        }
    }

    private final class Body extends AbstractComponent<Body> {
        @Override protected ComponentRenderer<Body> createDefaultRenderer() {
            return new BodyRenderer();
        }
    }

    private final class BodyRenderer implements ComponentRenderer<Body> {
        @Override public TerminalSize getPreferredSize(Body component) {
            return calculatePreferredSize();
        }

        @Override public void drawComponent(TextGUIGraphics graphics, Body component) {
            List<Row> snapshot;
            synchronized (GoalDialog.this) {
                if (!active) return;
                refreshActive();
                snapshot = rows;
            }
            graphics.fill(' ');
            int cols = graphics.getSize().getColumns();
            graphics.setForegroundColor(LanternaTheme.divider());
            graphics.putString(0, 0, "─".repeat(Math.max(0, cols)));
            for (int i = 0; i < snapshot.size(); i++) {
                Row row = snapshot.get(i);
                graphics.setForegroundColor(row.color());
                if (row.bold()) graphics.enableModifiers(SGR.BOLD);
                graphics.putString(LEFT_PAD, i + 1,
                    InlineOverlay.clip(row.text(), cols - LEFT_PAD - 2));
                if (row.bold()) graphics.disableModifiers(SGR.BOLD);
            }
            graphics.setForegroundColor(LanternaTheme.welcomeDim());
            graphics.putString(Math.max(LEFT_PAD, cols - 12), snapshot.size() + 1,
                "Esc to close");
        }
    }
}
