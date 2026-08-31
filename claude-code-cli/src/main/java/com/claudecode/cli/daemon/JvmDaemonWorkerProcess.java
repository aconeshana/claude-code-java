package com.claudecode.cli.daemon;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Configured daemon worker backed by a child invocation of this CLI. */
final class JvmDaemonWorkerProcess implements DaemonWorkerProcess {

    private final Process process;

    private JvmDaemonWorkerProcess(Process process) {
        this.process = process;
    }

    static JvmDaemonWorkerProcess launch(String kind, String config) throws Exception {
        List<String> command = new ArrayList<>(CurrentCliCommand.resolve());
        command.add("--daemon-worker");
        command.add(kind);
        ProcessBuilder builder = new ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT);
        Process process = builder.start();
        try (OutputStream input = process.getOutputStream()) {
            input.write(config.getBytes(StandardCharsets.UTF_8));
        }
        return new JvmDaemonWorkerProcess(process);
    }

    @Override public boolean sendShutdown() {
        if (!process.isAlive()) return true;
        process.destroy();
        return true;
    }

    @Override public int awaitExit() throws InterruptedException {
        return process.waitFor();
    }

    @Override public boolean awaitExit(Duration timeout) throws InterruptedException {
        return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override public void terminate() {
        process.destroy();
    }

    @Override public void kill() {
        process.destroyForcibly();
    }

    @Override public boolean isAlive() {
        return process.isAlive();
    }
}
