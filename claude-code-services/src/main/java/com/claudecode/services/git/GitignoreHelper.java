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
     * Ensures {@code relativePath} is covered by the global gitignore belonging
     * to {@code home}.
     *
     * <p>The home directory is a parameter rather than a hidden read of
     * {@code user.home} so the write target and the {@code HOME} handed to git
     * are provably the same value: the reader and writer of the global ignore
     * file cannot drift apart, and a caller with an authoritative home (for
     * example a session that resolved its configuration directory explicitly)
     * does not have to mutate a global system property to be honored.
     */
    public static void addFileGlobRuleToGitignore(String relativePath, String cwd, Path home) {
        try {
            if (home == null) return;
            if (!isInsideGitRepo(cwd, home)) return;
            if (isPathGitignored(relativePath, cwd, home)) return;

            String entry = "**/" + relativePath;
            Path globalGitignore = home.resolve(".config").resolve("git").resolve("ignore");
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

    private static boolean isInsideGitRepo(String cwd, Path home) {
        return runGitCheck(cwd, home, "rev-parse", "--is-inside-work-tree");
    }

    private static boolean isPathGitignored(String relativePath, String cwd, Path home) {
        return runGitCheck(cwd, home, "check-ignore", relativePath);
    }

    /**
     * Runs a git subcommand and returns whether it exited 0, degrading to false
     * on any error/timeout. {@code HOME} is pinned to {@code home} so
     * {@code git check-ignore} consults the same global gitignore this class
     * writes to; without the pin, git would resolve the global ignore from the
     * inherited {@code HOME}, and a caller running with a substituted home would
     * read a different file than it writes.
     */
    private static boolean runGitCheck(String cwd, Path home, String... args) {
        try {
            String[] cmd = new String[args.length + 1];
            cmd[0] = "git";
            System.arraycopy(args, 0, cmd, 1, args.length);
            ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(new File(cwd))
                .redirectErrorStream(true);
            pb.environment().put("HOME", home.toString());
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
