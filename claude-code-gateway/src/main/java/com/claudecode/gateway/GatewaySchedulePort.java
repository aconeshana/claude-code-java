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
        String model) {}

    /** Every scheduled task known to this process. */
    default List<ScheduleEntry> list() { return List.of(); }

    /**
     * Pre-validates one add: the cron expression's syntax, its next fire time,
     * and the job capacity. Returns {@code null} when the add may proceed;
     * otherwise the user-facing rejection message.
     */
    default String validateAdd(String cron) { return null; }

    /**
     * Adds one scheduled task, returning its generated id. {@code model} is
     * the optional per-task model override (null keeps the session model).
     */
    default String add(String cron, String prompt, boolean recurring, boolean durable,
            String model) {
        throw new UnsupportedOperationException("scheduling is not configured");
    }

    /** Removes the task with {@code id}; false when absent. */
    default boolean remove(String id) { return false; }
}
