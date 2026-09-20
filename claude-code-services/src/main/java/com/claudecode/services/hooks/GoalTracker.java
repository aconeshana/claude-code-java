package com.claudecode.services.hooks;

import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.GoalStatusAttachment;
import com.claudecode.core.message.Message;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * The /goal state machine: installs the goal condition as a session-persistent
 * Stop prompt hook, tracks iterations across Stop dispatches, defers evaluation
 * while background work runs, and publishes MET / FAILED / PENDING transitions
 * with duration and token statistics.
 *
 * <ul>
 *   <li>{@code src/utils/hooks/sessionHooks.ts} — registering / clearing the
 *       goal's Stop prompt hook in the session layer.</li>
 *   <li>2.1.236 bundle {@code /goal} command (no counterpart in the decompiled
 *       TS tree) — active goal bookkeeping (condition, iterations, tokens at
 *       start), completion statistics, and restoration from the last
 *       {@code goal_status} attachment on resume.</li>
 * </ul>
 */
public final class GoalTracker {

    private final HookRegistry registry;
    private final Object lock = new Object();
    private volatile PromptHook goalPromptHook;
    private volatile HookDispatcher.ActiveGoal activeGoal;
    private final AtomicReference<HookDispatcher.GoalTransition> transition = new AtomicReference<>();
    private volatile LongSupplier tokenCountSupplier = () -> 0L;
    private volatile BooleanSupplier backgroundTasksRunningSupplier = () -> false;

    GoalTracker(HookRegistry registry) {
        this.registry = registry;
    }

    /** Token counter used for the final goal statistics. */
    public void setTokenCountSupplier(LongSupplier supplier) {
        this.tokenCountSupplier = supplier != null ? supplier : () -> 0L;
    }

    /** Defers goal evaluation while a shell/agent background task is active. */
    public void setBackgroundTasksRunningSupplier(BooleanSupplier supplier) {
        this.backgroundTasksRunningSupplier = supplier != null ? supplier : () -> false;
    }

    boolean set(String condition, long tokensAtStart) {
        synchronized (lock) {
            removeHookLocked();
            PromptHook hook = new PromptHook(condition);
            goalPromptHook = hook;
            registry.replaceSessionHooks(HookEvent.STOP, List.of(
                new HookMatcher(Optional.of(""), List.of(hook))));
            activeGoal = new HookDispatcher.ActiveGoal(
                condition, 0, System.currentTimeMillis(), tokensAtStart, null);
            transition.set(null);
            return true;
        }
    }

    String clear() {
        synchronized (lock) {
            String condition = activeGoal != null ? activeGoal.condition()
                : goalPromptHook != null ? goalPromptHook.prompt() : null;
            removeHookLocked();
            activeGoal = null;
            transition.set(null);
            return condition;
        }
    }

    Optional<HookDispatcher.ActiveGoal> active() {
        return Optional.ofNullable(activeGoal);
    }

    Optional<HookDispatcher.GoalTransition> consumeTransition() {
        return Optional.ofNullable(transition.getAndSet(null));
    }

    /** The goal's Stop prompt hook, or {@code null} when no goal is set. */
    PromptHook currentHook() {
        return goalPromptHook;
    }

    /** Whether the given goal hook must sit out this Stop because background work is still running. */
    boolean shouldDefer(PromptHook goal) {
        return goal != null && backgroundTasksRunningSupplier.getAsBoolean();
    }

    void restoreFromTranscript(List<Message> messages, long tokensAtStart) {
        String condition = findGoalToRestore(messages);
        if (condition == null) clear();
        else set(condition, tokensAtStart);
    }

    static String findGoalToRestore(List<Message> messages) {
        if (messages == null) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (!(message instanceof AttachmentMessage attachment)
                    || !(attachment.payload() instanceof GoalStatusAttachment goal)) {
                continue;
            }
            return goal.met() || goal.hasFailedMarker() ? null : goal.condition();
        }
        return null;
    }

    /** Applies the evaluator verdict for {@code evaluatedGoal} found in this Stop's executions. */
    void record(PromptHook evaluatedGoal, List<HookExecution> executions) {
        if (evaluatedGoal == null) return;
        HookExecution execution = executions.stream()
            .filter(item -> item.command() == evaluatedGoal)
            .findFirst().orElse(null);
        if (execution == null) return;
        HookDispatcher.ActiveGoal current = activeGoal;
        if (current == null || !current.condition().equals(evaluatedGoal.prompt())) return;

        int iterations = current.iterations() + 1;
        HookResult result = HookOutcomes.baseResult(execution.result());
        if (result instanceof HookResult.ConditionNotMet notMet) {
            activeGoal = new HookDispatcher.ActiveGoal(current.condition(), iterations,
                current.setAtMillis(), current.tokensAtStart(), notMet.reason());
            transition.set(new HookDispatcher.GoalTransition(
                HookDispatcher.GoalTransitionKind.PENDING, current.condition(),
                notMet.reason(), iterations, 0L, 0L));
            return;
        }
        if (result instanceof HookResult.ConditionMet(String reason)) {
            finish(current, iterations, reason, HookDispatcher.GoalTransitionKind.MET);
        } else if (result instanceof HookResult.ConditionImpossible(String reason)) {
            finish(current, iterations, reason, HookDispatcher.GoalTransitionKind.FAILED);
        }
    }

    private void finish(HookDispatcher.ActiveGoal current, int iterations,
                        String reason, HookDispatcher.GoalTransitionKind kind) {
        long duration = Math.max(0L, System.currentTimeMillis() - current.setAtMillis());
        long tokens = Math.max(0L, tokenCountSupplier.getAsLong() - current.tokensAtStart());
        synchronized (lock) {
            if (activeGoal == null || !activeGoal.condition().equals(current.condition())) return;
            removeHookLocked();
            activeGoal = null;
        }
        transition.set(new HookDispatcher.GoalTransition(
            kind, current.condition(), reason, iterations, duration, tokens));
    }

    private void removeHookLocked() {
        registry.removeSessionHooks(HookEvent.STOP);
        goalPromptHook = null;
    }
}
