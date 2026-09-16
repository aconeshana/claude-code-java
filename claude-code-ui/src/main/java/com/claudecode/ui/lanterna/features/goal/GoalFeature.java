package com.claudecode.ui.lanterna.features.goal;

import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.message.GoalStatusAttachment;
import com.claudecode.core.message.Usage;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.ui.lanterna.dialog.GoalDialog;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.features.ReplFeature;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import java.util.List;
import com.claudecode.ui.lanterna.repl.ReplCommandUiBridge;
import com.claudecode.ui.lanterna.status.GoalStatusHistory;
import com.googlecode.lanterna.gui2.Component;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import java.util.Objects;

/**
 * The {@code /goal} overlay: the active goal (live token/elapsed counters), the latest
 * successful goal-status attachment, or the empty state.
 *
 * <p>TS coverage (paths relative to the claude-code repo root):
 * <ul>
 *   <li>{@code commands/goal/} — {@code /goal} status view over the active goal hook and the
 *       most recent goal-status attachment in the conversation.</li>
 * </ul>
 */
public final class GoalFeature implements ReplCommandUiBridge.Goal, ReplFeature {

    private final WindowBasedTextGUI gui;
    private final InputPanel inputPanel;
    private final QuerySession queryEngine;
    private final GoalDialog dialog;

    public GoalFeature(WindowBasedTextGUI gui, InputPanel inputPanel, QuerySession queryEngine) {
        this.gui = Objects.requireNonNull(gui, "gui");
        this.inputPanel = inputPanel;
        this.queryEngine = Objects.requireNonNull(queryEngine, "queryEngine");
        this.dialog = new GoalDialog();   // inline, zero height until shown
    }

    @Override public List<InlineOverlay> overlays() { return List.of(dialog); }
    public Component view() { return dialog; }

    @Override
    public void openGoal() {
        gui.getGUIThread().invokeLater(() -> {
            if (inputPanel != null) inputPanel.setSuppressed(true);
            Runnable onClose = () -> {
                if (inputPanel != null) {
                    inputPanel.setSuppressed(false);
                    inputPanel.takeFocus();
                }
            };

            HookDispatcher hooks = queryEngine.execution().getHookDispatcher();
            if (hooks != null && hooks.activeGoal().isPresent()) {
                dialog.showActive(
                    () -> hooks.activeGoal().orElse(null),
                    System::currentTimeMillis,
                    this::currentTokenCount,
                    onClose);
                return;
            }
            GoalStatusAttachment latest = GoalStatusHistory.latestSuccessful(queryEngine.conversation().getMessages());
            if (latest != null) dialog.showLatest(latest, onClose);
            else dialog.showNone(onClose);
        });
    }

    private long currentTokenCount() {
        Usage usage = queryEngine.execution().getTotalUsage();
        return usage == null ? 0L
            : usage.inputTokens() + usage.outputTokens()
                + usage.cacheCreationInputTokens() + usage.cacheReadInputTokens();
    }
}
