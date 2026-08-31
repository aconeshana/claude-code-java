package com.claudecode.tools.cron;

/**
 * Public scheduling boundary for consumers that need cron validation and deterministic next-fire
 * projection without depending on tool implementations.
 */
public final class CronSchedule {

    private CronSchedule() {}

    public static boolean isValid(String expression) {
        return CronUtils.isValid(expression);
    }

    public static Long nextRunAfterMs(String expression, long fromEpochMs) {
        return CronUtils.nextRunAfterMs(expression, fromEpochMs);
    }

    public static Long recurringJitteredNextRunMs(
            String expression, long fromEpochMs, String taskId, CronJitterConfig config) {
        return CronScheduler.jitteredNextCronRunMs(expression, fromEpochMs, taskId,
            config == null ? CronJitterConfig.DEFAULT : config);
    }
}
