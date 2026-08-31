package com.claudecode.cli.daemon.scheduled;

/** Result summary emitted by one scheduled Agent query. */
record ScheduledTaskResult(boolean success, String detail) {

    static ScheduledTaskResult success(String detail) {
        return new ScheduledTaskResult(true, detail);
    }

    static ScheduledTaskResult failure(String detail) {
        return new ScheduledTaskResult(false, detail);
    }
}
