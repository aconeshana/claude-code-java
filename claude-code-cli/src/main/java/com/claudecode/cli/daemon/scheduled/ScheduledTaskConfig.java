package com.claudecode.cli.daemon.scheduled;

import java.nio.file.Path;

/** Immutable validated task configuration consumed by the scheduled daemon worker. */
record ScheduledTaskConfig(
    String id,
    String cron,
    String prompt,
    Path directory,
    boolean enabled,
    ScheduledPermissionMode permissionMode,
    String model,
    int runTimeoutMinutes,
    int maxQueued
) {}
