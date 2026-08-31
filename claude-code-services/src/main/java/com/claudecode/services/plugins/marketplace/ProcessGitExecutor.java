package com.claudecode.services.plugins.marketplace;

import java.io.InputStream;

import com.claudecode.core.process.SubprocessEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@link GitExecutor} backed by {@link ProcessBuilder}.
 */
public final class ProcessGitExecutor implements GitExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessGitExecutor.class);
    private static final long DEFAULT_TIMEOUT_MS = 60_000;

    private final long timeoutMs;

    public ProcessGitExecutor() {
        this(resolveTimeoutMs());
    }

    public ProcessGitExecutor(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public long timeoutMs() {
        return timeoutMs;
    }

    private static long resolveTimeoutMs() {
        String env = SubprocessEnvironment.get(
            "CLAUDE_CODE_PLUGIN_GIT_TIMEOUT_MS");
        if (env != null) {
            try {
                long parsed = Long.parseLong(env.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException _) {
                // fall through to default
            }
        }
        return DEFAULT_TIMEOUT_MS;
    }

    @Override
    public GitResult run(Path cwd, List<String> args) {
        List<String> command = new ArrayList<>(args.size() + 1);
        command.add("git");
        command.addAll(args);

        ProcessBuilder builder = new ProcessBuilder(command);
        if (cwd != null) {
            builder.directory(cwd.toFile());
        }
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("GIT_ASKPASS", "");

        try {
            Process process = builder.start();
            process.getOutputStream().close();
            return await(process);
        } catch (IOException e) {
            LOG.warn("Failed to start git {}: {}", args, e.getMessage());
            return new GitResult(-1, "", "Failed to start git: " + e.getMessage());
        }
    }

    private GitResult await(Process process) {
        // Drain both streams on virtual threads to avoid pipe-buffer deadlock
        // on chatty clones.
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread outReader = Thread.startVirtualThread(() -> readInto(process.getInputStream(), stdout));
        Thread errReader = Thread.startVirtualThread(() -> readInto(process.getErrorStream(), stderr));
        try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                long seconds = Math.round(timeoutMs / 1000.0);
                return new GitResult(-1, stdout.toString(),
                    "git operation timed out after " + seconds + "s");
            }
            outReader.join();
            errReader.join();
            return new GitResult(process.exitValue(), stdout.toString(), stderr.toString());
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new GitResult(-1, stdout.toString(), "git operation interrupted");
        }
    }

    private static void readInto(InputStream stream, StringBuilder target) {
        try (stream) {
            byte[] bytes = stream.readAllBytes();
            target.append(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException _) {
            // Stream closed by forcible destroy — partial output is fine.
        }
    }
}
