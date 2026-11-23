package com.claudecode.gateway;

import java.util.List;

/**
 * Consumer-owned boundary for the gateway's scheduled-task (cron) panel.
 *
 * <p>The gateway must not depend on {@code claude-code-tools} (where
 * {@code CronStore} lives), so the CLI composition root implements this port
 * against {@code CronStore}'s static list/add/remove methods.
 */
public interface GatewaySchedulePort {

    /** Advanced per-task execution options (Codex-style automations settings). */
    record TaskOptions(
        String model,
        String effort,
        String workingDirectory,
        boolean newSessionPerRun,
        String notificationMode,
        boolean archiveOnSuccess,
        boolean enabled) {
        public static final TaskOptions DEFAULT =
            new TaskOptions(null, null, null, true, "off", false, true);

        public TaskOptions(String model, String effort, String workingDirectory,
                boolean newSessionPerRun, String notificationMode, boolean archiveOnSuccess) {
            this(model, effort, workingDirectory, newSessionPerRun, notificationMode,
                archiveOnSuccess, true);
        }
    }

    /** One scheduled task row, projected from {@code CronJob}. */
    record ScheduleEntry(
        String id,
        String cron,
        String prompt,
        boolean recurring,
        boolean durable,
        long createdAt,
        Long lastFiredAt,
        String kind,
        String agentId,
        String createdBySessionId,
        String model,
        String effort,
        String workingDirectory,
        boolean newSessionPerRun,
        String notificationMode,
        boolean archiveOnSuccess,
        boolean enabled) {
        public ScheduleEntry(
            String id, String cron, String prompt, boolean recurring, boolean durable,
            long createdAt, Long lastFiredAt, String kind, String agentId,
            String createdBySessionId, String model) {
            this(id, cron, prompt, recurring, durable, createdAt, lastFiredAt, kind,
                agentId, createdBySessionId, model, null, null, true, "off", false, true);
        }
    }

    /** Every scheduled task known to this process. */
    default List<ScheduleEntry> list() { return List.of(); }

    /**
     * Pre-validates one add: the cron expression's syntax, its next fire time,
     * and the job capacity. Returns {@code null} when the add may proceed;
     * otherwise the user-facing rejection message.
     */
    default String validateAdd(String cron) { return null; }

    /** Pre-validates cron syntax and next fire time for an in-place update. */
    default String validateUpdate(String cron) { return validateAdd(cron); }

    /**
     * Adds one scheduled task, returning its generated id. {@code model} is
     * the optional per-task model override (null keeps the session model).
     */
    default String add(String cron, String prompt, boolean recurring, boolean durable,
            String model) {
        throw new UnsupportedOperationException("scheduling is not configured");
    }

    /** Adds one scheduled task with full advanced execution options. */
    default String add(String cron, String prompt, boolean recurring, boolean durable,
            TaskOptions options) {
        return add(cron, prompt, recurring, durable, options == null ? null : options.model());
    }

    /** Updates an existing scheduled task in place; false when absent. */
    default boolean update(String id, String cron, String prompt, boolean recurring,
            boolean durable, TaskOptions options) {
        return false;
    }

    /** Enables or pauses one scheduled task without deleting it; false when absent. */
    default boolean setEnabled(String id, boolean enabled) { return false; }

    /** Removes the task with {@code id}; false when absent. */
    default boolean remove(String id) { return false; }

    /** Triggers one task immediately out-of-band; false when unknown or unsupported. */
    default boolean runNow(String id) { return false; }
}
