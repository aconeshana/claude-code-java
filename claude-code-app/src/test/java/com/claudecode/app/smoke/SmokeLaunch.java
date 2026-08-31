package com.claudecode.app.smoke;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
















final class SmokeLaunch {

    /** Generous on purpose: a cold start on a loaded machine is slow, whereas a hang is not. */
    private static final Duration TIMEOUT = Duration.ofSeconds(90);

    private final Path emptyStdin;

    SmokeLaunch(Path scratch) {
        try {
            Files.createDirectories(scratch);
            this.emptyStdin = Files.write(scratch.resolve("empty-stdin"), new byte[0]);
        } catch (IOException cause) {
            throw new UncheckedIOException("cannot prepare an empty stdin for smoke launches", cause);
        }
    }

    /**
     * @param workingDirectory the launch cwd, which also keys the project directory a transcript
     *                         lands in — so session-dependent cases have to share one
     * @param home             both {@code HOME} and the parent of the isolated config directory
     */
    SmokeOutcome run(List<String> command, Path workingDirectory, Path home, String baseUrl) {
        ProcessBuilder builder = new ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .redirectInput(emptyStdin.toFile());
        configureEnvironment(builder.environment(), home, baseUrl);
        try {
            Process process = builder.start();
            // Both channels are drained concurrently: a full pipe buffer on either one deadlocks a
            // sequential read, and --debug produces far more than enough output to fill one.
            CompletableFuture<String> stdout = readAsync(process.getInputStream());
            CompletableFuture<String> stderr = readAsync(process.getErrorStream());
            boolean exited = process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly().waitFor();
            }
            return new SmokeOutcome(
                exited ? process.exitValue() : -1, stdout.join(), stderr.join(), !exited);
        } catch (IOException cause) {
            throw new UncheckedIOException("cannot launch " + command, cause);
        } catch (InterruptedException cause) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running " + command, cause);
        }
    }

    private static void configureEnvironment(
            Map<String, String> environment, Path home, String baseUrl) {
        Map<String, String> inherited = new HashMap<>(environment);
        environment.clear();
        // Subprocess lookup (git, ripgrep) needs PATH; TERM keeps terminal probing from guessing
        // capabilities off a variable that is absent rather than merely dumb.
        environment.put("PATH", inherited.getOrDefault("PATH", "/usr/bin:/bin:/usr/sbin:/sbin"));
        environment.put("TERM", "dumb");
        environment.put("LANG", "en_US.UTF-8");
        environment.put("HOME", home.toString());
        environment.put("CLAUDE_CONFIG_DIR", home.resolve(".claude").toString());
        // Telemetry and update checks would otherwise reach the fake server, which would make its
        // served-turn count say nothing about whether the launch actually completed a turn.
        environment.put("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1");
        environment.put("ANTHROPIC_BASE_URL", baseUrl);
        environment.put("ANTHROPIC_API_KEY", "sk-test-not-a-real-key");
    }

    private static CompletableFuture<String> readAsync(InputStream stream) {
        CompletableFuture<String> task = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            try (stream) {
                task.complete(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException cause) {
                task.complete("<unreadable: " + cause + '>');
            }
        });
        return task;
    }
}
