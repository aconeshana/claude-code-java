package com.claudecode.ui.lanterna.dialog;

import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.message.GoalStatusAttachment;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalDialogTest {

    @Test
    void rendersActiveGoalWithLiveStatistics() {
        GoalDialog dialog = new GoalDialog();
        HookDispatcher.ActiveGoal active = new HookDispatcher.ActiveGoal(
            "all tests pass", 2, 1_000L, 100L, "one test remains");

        dialog.showActive(active, 2_500L, 340L, () -> { });

        assertTrue(dialog.isActive());
        assertEquals(List.of(
            "✶ Goal active",
            "running 1s · 2 turns · 240 tokens",
            "Goal",
            "all tests pass",
            "Last check",
            "one test remains",
            "/goal clear to stop early"), dialog.lineTexts());
    }

    @Test
    void refreshesActiveGoalStatisticsAndOnlyEscapeDismisses() {
        GoalDialog dialog = new GoalDialog();
        AtomicReference<HookDispatcher.ActiveGoal> goal = new AtomicReference<>(
            new HookDispatcher.ActiveGoal("finish migration", 1, 1_000L, 100L, "checking"));
        AtomicLong now = new AtomicLong(2_000L);
        AtomicLong tokens = new AtomicLong(200L);

        dialog.showActive(goal::get, now::get, tokens::get, () -> { });
        assertEquals("running 1s · 1 turn · 100 tokens", dialog.lineTexts().get(1));

        goal.set(new HookDispatcher.ActiveGoal(
            "finish migration", 2, 1_000L, 100L, "one gap remains"));
        now.set(4_000L);
        tokens.set(450L);
        dialog.refreshActive();

        assertEquals("running 3s · 2 turns · 350 tokens", dialog.lineTexts().get(1));
        assertEquals("one gap remains", dialog.lineTexts().get(5));

        dialog.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));
        assertTrue(dialog.isActive(), "2.1.197 only dismisses the goal panel with Escape");
        dialog.handleKey(new KeyStroke(KeyType.ESCAPE), new AtomicBoolean(true));
        assertFalse(dialog.isActive());
    }

    @Test
    void rendersLatestAchievedGoal() {
        GoalDialog dialog = new GoalDialog();
        dialog.showLatest(GoalStatusAttachment.achieved(
            "ship it", "done", 1, 90_000L, 1_200L), () -> { });

        assertEquals(List.of(
            "Goal achieved",
            "1m · 1 turn · 1.2k tokens",
            "Goal",
            "ship it",
            "/goal <condition> to set another"), dialog.lineTexts());
    }

    @Test
    void rendersEmptyStateAndDismisses() {
        GoalDialog dialog = new GoalDialog();
        dialog.showNone(() -> { });

        assertEquals(List.of("Goal", "No goal set", "/goal <condition> to set one"),
            dialog.lineTexts());
        dialog.hide();
        assertFalse(dialog.isActive());
    }
}
