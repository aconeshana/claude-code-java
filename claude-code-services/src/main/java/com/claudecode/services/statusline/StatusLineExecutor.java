package com.claudecode.services.statusline;


import com.claudecode.services.process.PlatformShellCommand;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;


public final class StatusLineExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(StatusLineExecutor.class);


    static final int TIMEOUT_SECONDS = 5;

    private final String workingDirectory;
    private final Function<String, List<String>> commandResolver;

    public StatusLineExecutor(String workingDirectory) {
        this(workingDirectory, command -> PlatformShellCommand.resolve(null, command));
    }

    StatusLineExecutor(String workingDirectory,
                       Function<String, List<String>> commandResolver) {
        this.workingDirectory = workingDirectory;
        this.commandResolver = commandResolver;
    }

    /**
     * Runs {@code config.command} feeding {@code jsonInput} on stdin, and
     * returns the trimmed multi-line output when the command exits 0 with
     * non-empty output — otherwise {@link Optional#empty}.
     */
    public Optional<String> execute(StatusLineConfig config, String jsonInput) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(commandResolver.apply(config.command()));
            pb.directory(Path.of(workingDirectory).toFile());
            pb.redirectErrorStream(false);
            process = pb.start();

            // Write the status-line JSON to stdin, then close it so the command
            // sees EOF and can produce output.
            try (var stdin = process.getOutputStream()) {
                stdin.write((jsonInput + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException _) {
                // Command may exit without reading stdin (EPIPE) — not fatal.
            }

            // Drain BOTH streams on virtual threads. Reading stdout on this
// thread would block in readLine until the process closes its
            // pipe — which for a hung command never happens, so waitFor's
            // timeout below would never be reached. Off-thread drains let us
            // enforce the timeout and kill a stalled command.
            Process p = process;
            StringBuilder stdout = new StringBuilder();
            Thread stdoutDrain = Thread.ofVirtual().start(() -> drain(p.getInputStream(), stdout));
            Thread stderrDrain = Thread.ofVirtual().start(() -> drain(p.getErrorStream(), null));

            boolean completed = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                return Optional.empty();
            }
            // Process has exited; the drains will see EOF and finish promptly.
            stdoutDrain.join(1000);
            stderrDrain.join(1000);

            if (process.exitValue() != 0) {
                return Optional.empty();
            }
            return normalizeOutput(stdout.toString());
        } catch (IOException | RuntimeException e) {
            LOG.debug("Status line command failed to spawn: {}", e.getMessage());
            return Optional.empty();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            // InterruptedException only fires after pb.start() succeeded, so
            // process is guaranteed non-null here.
            process.destroyForcibly();
            return Optional.empty();
        }
    }

    /** Reads a stream fully into {@code sink} (or discards when {@code sink} is null). */
    private static void drain(InputStream in, StringBuilder sink) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (sink != null) {
                    sink.append(line).append('\n');
                }
            }
        } catch (IOException _) { /* best-effort capture */ }
    }


    static Optional<String> normalizeOutput(String rawStdout) {
        String[] lines = rawStdout.strip().split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;
            if (!out.isEmpty()) out.append('\n');
            out.append(trimmed);
        }
        return out.isEmpty() ? Optional.empty() : Optional.of(out.toString());
    }
}
