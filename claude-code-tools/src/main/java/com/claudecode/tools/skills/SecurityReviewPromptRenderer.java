package com.claudecode.tools.skills;


import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.process.ExecutableFinder;
import com.claudecode.session.SessionManager;
import com.claudecode.core.text.FormatUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Renders the invocation-time prompt for the bundled {@code security-review} skill.
 */
final class SecurityReviewPromptRenderer {

    private static final long GIT_PROBE_TIMEOUT_SECONDS = 10;
    private static final long COMMAND_TIMEOUT_SECONDS = 120;
    private static final int PERSIST_THRESHOLD_CHARS = 30_000;
    private static final int PREVIEW_SIZE_CHARS = 2_000;
    private static final List<String> COMMANDS = List.of(
        "git status",
        "git diff --name-only origin/HEAD...",
        "git log --no-decorate origin/HEAD...",
        "git diff origin/HEAD..."
    );

    private SecurityReviewPromptRenderer() {}

    static String render(ToolExecutionContext context, String gitPromptFallback) {
        Path cwd = workingDirectory(context);
        if (!isGitRepository(cwd)) {
            return """
                Tell the user: /security-review needs to run inside a git repository, but the current working directory (`%s`) is not one.

                If the repository is in a subdirectory, `cd` into it first and then re-run /security-review.

                If this is a self-hosted runner session created without a `git_repository` source, either add one at session creation so the runner clones it and sets the working directory, or `cd` into the cloned repo before running the review.""".formatted(cwd);
        }
        return expandGitCommands(gitPromptFallback.stripTrailing(), cwd, context);
    }

    private static Path workingDirectory(ToolExecutionContext context) {
        String configured = context == null ? null : context.workingDirectory();
        String value = StringUtils.isBlank(configured)
            ? System.getProperty("user.dir") : configured;
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static boolean isGitRepository(Path cwd) {
        Process process = null;
        try {
            process = new ProcessBuilder(
                "git", "-C", cwd.toString(), "rev-parse", "--is-inside-work-tree")
                .redirectErrorStream(true)
                .start();
            boolean finished = process.waitFor(GIT_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.exitValue() == 0 && Strings.CS.equals("true", output.strip());
        } catch (IOException _) {
            return false;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private static String expandGitCommands(
            String prompt, Path cwd, ToolExecutionContext context) {
        Map<String, CompletableFuture<String>> outputs = new LinkedHashMap<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String command : COMMANDS) {
                outputs.put(command, CompletableFuture.supplyAsync(
                    () -> executeAndFormat(command, cwd, context), executor));
            }
            String resolved = prompt;
            for (Map.Entry<String, CompletableFuture<String>> entry : outputs.entrySet()) {
                String marker = "!`" + entry.getKey() + "`";
                resolved = resolved.replace(marker, entry.getValue().join());
            }
            return resolved;
        }
    }

    private static String executeAndFormat(
            String command, Path cwd, ToolExecutionContext context) {
        Process process = null;
        try {
            process = new ProcessBuilder(
                ExecutableFinder.bashExecutable(), "-c", command)
                .directory(cwd.toFile())
                .start();
            Process running = process;
            CompletableFuture<String> stdout = CompletableFuture.supplyAsync(
                () -> readStream(running.getInputStream()));
            CompletableFuture<String> stderr = CompletableFuture.supplyAsync(
                () -> readStream(running.getErrorStream()));
            boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException(
                    "Shell command interrupted for pattern \"!`" + command
                        + "`\": [Command interrupted]");
            }
            String out = stdout.join();
            String err = stderr.join();
            String formatted = formatBashOutput(out, err);
            if (process.exitValue() != 0) {
                throw new IllegalStateException(
                    "Shell command failed for pattern \"!`" + command + "`\": " + formatted);
            }
            if (StringUtils.isBlank(formatted)) formatted = "(Bash completed with no output)";
            return persistIfLarge(formatted, cwd, context);
        } catch (IOException e) {
            throw new IllegalStateException("[Error]\n" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                "Shell command interrupted for pattern \"!`" + command
                    + "`\": [Command interrupted]", e);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private static String readStream(InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String formatBashOutput(String stdout, String stderr) {
        String out = stdout == null ? "" : stdout.strip();
        String err = stderr == null ? "" : stderr.strip();
        if (out.isEmpty()) return err.isEmpty() ? "" : "[stderr]\n" + err;
        return err.isEmpty() ? out : out + "\n[stderr]\n" + err;
    }

    private static String persistIfLarge(
            String content, Path cwd, ToolExecutionContext context) {
        if (content.length() <= PERSIST_THRESHOLD_CHARS) return content;
        try {
            String sessionId = context == null || context.sessionId() == null
                ? "unknown" : context.sessionId();
            Path directory = new SessionManager(cwd.toString()).getToolResultsDir(sessionId);
            Files.createDirectories(directory);
            Path target = directory.resolve(
                UUID.randomUUID().toString().replace("-", "").substring(0, 9) + ".txt");
            Files.writeString(target, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
            String preview = preview(content);
            return "<persisted-output>\n"
                + "Output too large (" + FormatUtils.formatFileSize(content.length())
                + "). Full output saved to: " + target + "\n\n"
                + "Preview (first " + FormatUtils.formatFileSize(PREVIEW_SIZE_CHARS) + "):\n"
                + preview + "\n...\n</persisted-output>";
        } catch (IOException _) {
            return content;
        }
    }

    private static String preview(String content) {
        if (content.length() <= PREVIEW_SIZE_CHARS) return content;
        String truncated = content.substring(0, PREVIEW_SIZE_CHARS);
        int lastNewline = truncated.lastIndexOf('\n');
        int cut = lastNewline > PREVIEW_SIZE_CHARS / 2 ? lastNewline : PREVIEW_SIZE_CHARS;
        return content.substring(0, cut);
    }
}
