package com.claudecode.cli;

import com.claudecode.cli.daemon.CurrentCliCommand;
import com.claudecode.core.process.DetachedSession;
import com.claudecode.runtime.gateway.GatewaySupervisorPort;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Starts the global web gateway as its own {@code claude web} process rather than
 * hosting it inside the TUI, so the gateway outlives any single terminal session.
 *
 * <p>An already published gateway is reused. Otherwise a detached child is launched
 * and its discovery record is polled until it publishes. {@code claude web} itself
 * serializes concurrent starts with a file lock, so two TUIs racing here still end
 * up with one gateway.
 */
final class DetachedWebGateway implements GatewaySupervisorPort {

    private static final long DEFAULT_READY_TIMEOUT_MILLIS = 10_000;
    private static final long POLL_MILLIS = 100;
    private static final Path NOHUP = Path.of("/usr/bin/nohup");

    /**
     * Starts a detached process running {@code command}, appending its output to {@code logFile}.
     * Returns the spawned {@link Process} handle when available (or {@code null} in test doubles).
     */
    @FunctionalInterface
    interface ProcessLauncher {
        Process launch(List<String> command, Path logFile) throws IOException;
    }

    private final Path recordFile;
    private final Path logFile;
    private final ProcessLauncher launcher;
    private final long readyTimeoutMillis;

    DetachedWebGateway(Path recordFile, Path logFile) {
        this(recordFile, logFile, DetachedWebGateway::spawn, DEFAULT_READY_TIMEOUT_MILLIS);
    }

    DetachedWebGateway(Path recordFile, Path logFile, ProcessLauncher launcher,
            long readyTimeoutMillis) {
        this.recordFile = recordFile;
        this.logFile = logFile;
        this.launcher = launcher;
        this.readyTimeoutMillis = readyTimeoutMillis;
    }

    @Override
    public synchronized Started start() {
        Optional<WebGatewayRecord> running = WebGatewayRecord.readLive(recordFile);
        if (running.isEmpty()) {
            Process child = launch();
            running = awaitPublished(child);
        }
        return new Started(running.orElseThrow().url());
    }

    private Process launch() {
        List<String> command = new ArrayList<>();
        // Only a fallback: spawn already gives the child a session of its own,
        // but where no session launcher exists SIGHUP from a closing terminal
        // would otherwise reach it through the shared process group.
        if (Files.isExecutable(NOHUP)) command.add(NOHUP.toString());
        command.addAll(CurrentCliCommand.resolve());
        command.add("web");
        try {
            return launcher.launch(command, logFile);
        } catch (IOException failure) {
            throw new IllegalStateException("failed to launch claude web", failure);
        }
    }

    private static Process spawn(List<String> command, Path logFile) throws IOException {
        Files.createDirectories(logFile.getParent());
        // A session of its own, so the gateway has no controlling terminal: the
        // shell that ran `claude web -d` (or the TUI that ran /web) can close
        // without its hangup or job-control signals reaching the child. nohup
        // stays as the fallback where no session launcher exists.
        Process process = DetachedSession.apply(new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                .redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile())))
            .start();
        process.getOutputStream().close();
        return process;
    }

    private Optional<WebGatewayRecord> awaitPublished(Process child) {
        if (child == null) {
            try {
                Optional<WebGatewayRecord> published =
                    WebGatewayRecord.awaitLive(recordFile, Duration.ofMillis(readyTimeoutMillis));
                if (published.isPresent()) return published;
                throw new IllegalStateException(
                    "claude web did not publish its gateway; see " + logFile);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                    "interrupted while waiting for claude web", interrupted);
            } catch (IOException failure) {
                throw new IllegalStateException(
                    "failed while waiting for claude web; see " + logFile, failure);
            }
        }
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readyTimeoutMillis);
        while (true) {
            Optional<WebGatewayRecord> published = WebGatewayRecord.readLive(recordFile);
            if (published.isPresent()) return published;
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            if (remainingMs <= 0) {
                throw new IllegalStateException(
                    "claude web did not publish its gateway; see " + logFile);
            }
            try {
                if (child.waitFor(Math.min(POLL_MILLIS, remainingMs), TimeUnit.MILLISECONDS)) {
                    // Re-check once: a racing child that reused another gateway exits 0 right as
                    // the record appears.
                    Optional<WebGatewayRecord> raced = WebGatewayRecord.readLive(recordFile);
                    if (raced.isPresent()) return raced;
                    throw new IllegalStateException(
                        "claude web exited early (code " + child.exitValue() + "); see " + logFile);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                    "interrupted while waiting for claude web", interrupted);
            }
        }
    }
}
