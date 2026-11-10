package com.claudecode.cli;

import com.claudecode.cli.daemon.CurrentCliCommand;
import com.claudecode.runtime.gateway.GatewaySupervisorPort;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    private static final long READY_TIMEOUT_MILLIS = 10_000;
    private static final long POLL_MILLIS = 100;
    private static final Path NOHUP = Path.of("/usr/bin/nohup");

    private final Path recordFile;
    private final Path logFile;

    DetachedWebGateway(Path recordFile, Path logFile) {
        this.recordFile = recordFile;
        this.logFile = logFile;
    }

    @Override
    public synchronized Started start() {
        Optional<WebGatewayRecord> running = WebGatewayRecord.readLive(recordFile);
        if (running.isEmpty()) {
            launch();
            running = awaitPublished();
        }
        return new Started(running.orElseThrow().url());
    }

    private void launch() {
        List<String> command = new ArrayList<>();
        // SIGHUP from a closing terminal would otherwise reach the child, which shares its process group.
        if (Files.isExecutable(NOHUP)) command.add(NOHUP.toString());
        command.addAll(CurrentCliCommand.resolve());
        command.add("web");
        try {
            Files.createDirectories(logFile.getParent());
            Process process = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                .redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                .start();
            process.getOutputStream().close();
        } catch (IOException failure) {
            throw new IllegalStateException("failed to launch claude web", failure);
        }
    }

    private Optional<WebGatewayRecord> awaitPublished() {
        long deadline = System.currentTimeMillis() + READY_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            Optional<WebGatewayRecord> published = WebGatewayRecord.readLive(recordFile);
            if (published.isPresent()) return published;
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for claude web", interrupted);
            }
        }
        throw new IllegalStateException("claude web did not publish its gateway; see " + logFile);
    }
}
