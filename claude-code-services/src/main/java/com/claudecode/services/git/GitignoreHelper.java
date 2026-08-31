package com.claudecode.services.git;

import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

/**
 * Best-effort helper that keeps a settings file pattern out of git by appending it to the user's
 * <b>global</b> gitignore ({@code ~/.config/git/ignore}) rather than the per-project
 * {@code.gitignore} — done once, it applies to every repo on the machine.
 */
public final class GitignoreHelper {

    private static final Logger LOG = LoggerFactory.getLogger(GitignoreHelper.class);
    private static final int GIT_TIMEOUT_SECONDS = 3;

    private GitignoreHelper() {}

    /**
     * Ensures {@code relativePath} (e.g.
     */
    public static void addFileGlobRuleToGitignore(String relativePath, String cwd) {
        try {
            if (!isInsideGitRepo(cwd)) return;
            if (isPathGitignored(relativePath, cwd)) return;

            String entry = "**/" + relativePath;
            Path globalGitignore = Path.of(System.getProperty("user.home"), ".config", "git", "ignore");
            Files.createDirectories(globalGitignore.getParent());
            if (Files.isReadable(globalGitignore)) {
                String content = Files.readString(globalGitignore);
                if (Strings.CS.contains(content, entry)) return;
                Files.writeString(globalGitignore, "\n" + entry + "\n", StandardOpenOption.APPEND);
            } else {
                Files.writeString(globalGitignore, entry + "\n");
            }
        } catch (Exception e) {
            LOG.warn("Failed to add {} to global gitignore: {}", relativePath, e.getMessage());
        }
    }

    private static boolean isInsideGitRepo(String cwd) {
        return runGitCheck(cwd, "rev-parse", "--is-inside-work-tree");
    }

    private static boolean isPathGitignored(String relativePath, String cwd) {
        return runGitCheck(cwd, "check-ignore", relativePath);
    }

    /**
     * Runs a git subcommand and returns whether it exited 0, degrading to false
     * on any error/timeout. {@code HOME} is pinned to {@code user.home} so
     * {@code git check-ignore} consults the same global gitignore this class
     * writes to (matters for test isolation; in real usage {@code user.home}
     * already equals {@code $HOME}).
     */
    private static boolean runGitCheck(String cwd, String... args) {
        try {
            String[] cmd = new String[args.length + 1];
            cmd[0] = "git";
            System.arraycopy(args, 0, cmd, 1, args.length);
            ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(new File(cwd))
                .redirectErrorStream(true);
            pb.environment().put("HOME", System.getProperty("user.home"));
            Process process = pb.start();
            process.getInputStream().readAllBytes(); // drain to avoid blocking on full pipe buffer
            boolean finished = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }
}
