package com.claudecode.tools.skills;

import java.io.Serial;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * One-shot dynamic Skill directory triggers with post-consumption callbacks.
 */
public final class DynamicSkillTriggerSet extends CopyOnWriteArraySet<String> {

    @Serial
    private static final long serialVersionUID = 1L;
    private final ConcurrentLinkedQueue<Runnable> afterConsume =
        new ConcurrentLinkedQueue<>();

    /** Schedules work after the next attachment collection consumes this set. */
    public void afterNextConsumption(Runnable action) {
        if (action != null) afterConsume.add(action);
    }

    @Override
    public void clear() {
        super.clear();
        Runnable action;
        while ((action = afterConsume.poll()) != null) {
            try {
                action.run();
            } catch (RuntimeException _) {

            }
        }
    }
}
