package com.claudecode.tools.tasks;

/**
 * Kill hook for a running DREAM (auto-dream) background task.
 *
 * <p>matches the {@link LocalShellTask} / {@link LocalAgentTask} / {@link
 * InProcessTeammateTask} live handles that {@link TaskRegistry} keeps keyed by
 * task id, so the {@code /tasks} dialog's kill path can abort an in-flight
 * memory-consolidation pass. The concrete implementation lives in
 * {@code com.claudecode.services.dream.AutoDreamEngineImpl} (which owns the
 * running sub-engine + lock), registered here via
 * {@link TaskRegistry#registerDream(String, AutoDreamHandle)} when a dream
 * starts and unregistered when it ends.
 */
public interface AutoDreamHandle {

    /**
     * Aborts the running dream: stops the forked sub-agent, rolls back the
     * consolidation lock, and marks the task {@code KILLED}. Returns
     * {@code false} (no-op) if the task is unknown or already finished.
     */
    boolean kill();
}
