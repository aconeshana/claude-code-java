package com.claudecode.core.process;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Generic, throw-safe external-process runner shared across modules.
 */
public final class ProcessRunner {

    private ProcessRunner() {}

    /**
     * Runs {@code command} (program first) in {@code cwd}, returning a
     * {@link ProcessResult} that is never null and never throws.
     *
     * <p>stdin is closed; stdout and stderr are each drained on a virtual thread,
     * so even a large or chatty response parks instead of occupying an OS thread
     * and cannot deadlock on a full pipe. A zero or negative {@code timeout} waits
     * indefinitely; otherwise the child is force-killed after the deadline and
     * reported via {@link ProcessResult#timedOut}. Spawn failure or an
     * interrupted wait yields {@link ProcessResult#failure}.
     *
     * @param command full argv, non-null
     * @param cwd working directory, or {@code null} for the current one
     * @param timeout max wall-clock time, or zero/negative for no limit
     */
    public static ProcessResult run(List<String> command, Path cwd, Duration timeout) {
        return run(command, cwd, timeout, Map.of());
    }


    public static ProcessResult run(
        List<String> command, Path cwd, Duration timeout, Map<String, String> env) {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (cwd != null) pb.directory(cwd.toAbsolutePath().toFile());
        SubprocessEnvironment.applyTo(pb.environment());
        if (env != null && !env.isEmpty()) pb.environment().putAll(env);
        Process process;
        try {
            process = pb.start();
        } catch (IOException _) {
            return ProcessResult.failure();
        }
        try {
            try { process.getOutputStream().close(); } catch (IOException _) {}
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<String> stdout = executor.submit(() ->
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                Future<String> stderr = executor.submit(() ->
                    new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
                boolean finished;
                try {
                    finished = waitFor(process, timeout);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                    return ProcessResult.failure();
                }
                if (!finished) {
                    process.destroyForcibly();
                    return ProcessResult.timeout();
                }
                try {
                    return new ProcessResult(stdout.get(), stderr.get(), process.exitValue(), false);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                    return ProcessResult.failure();
                } catch (ExecutionException _) {
                    process.destroyForcibly();
                    return ProcessResult.failure();
                }
            }
        } finally {
            process.destroyForcibly();
        }
    }

    private static boolean waitFor(Process process, Duration timeout) throws InterruptedException {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            process.waitFor();
            return true;
        }
        return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
}
