package com.claudecode.cli.daemon;

import java.time.Duration;

/** Process operations needed by the configured-worker supervisor. */
interface DaemonWorkerProcess {
    boolean sendShutdown();
    int awaitExit() throws InterruptedException;
    boolean awaitExit(Duration timeout) throws InterruptedException;
    void terminate();
    void kill();
    boolean isAlive();
}
