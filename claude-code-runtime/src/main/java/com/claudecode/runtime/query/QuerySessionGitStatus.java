package com.claudecode.runtime.query;

import com.claudecode.core.process.ProcessResult;
import com.claudecode.core.process.ProcessRunner;
import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Process-wide startup git-status snapshot memo shared by the main query loop and sub-agents
 * so the system prompt prefix stays byte-identical across turns.
 */
final class QuerySessionGitStatus {

    private static final int MAX_STATUS_CHARS = 2000;
    private static volatile String gitStatusCache;

    private QuerySessionGitStatus() {}

    static void primeSnapshot(String cwd) {
        initialSnapshot(cwd);
    }

    static String initialSnapshot(String cwd) {
        if (StringUtils.isBlank(cwd)) return null;
        String cached = gitStatusCache;
        if (cached != null) return cached.isEmpty() ? null : cached;
        synchronized (QuerySessionGitStatus.class) {
            if (gitStatusCache == null) {
                String computed = buildGitStatus(cwd);
                gitStatusCache = computed != null ? computed : "";
            }
            return gitStatusCache.isEmpty() ? null : gitStatusCache;
        }
    }

    static String computeSnapshot(String cwd) {
        return StringUtils.isBlank(cwd) ? null : buildGitStatus(cwd);
    }

    static void publishSnapshot(String snapshot) {
        synchronized (QuerySessionGitStatus.class) {
            gitStatusCache = snapshot != null ? snapshot : "";
        }
    }

    static void resetForTest() {
        synchronized (QuerySessionGitStatus.class) {
            gitStatusCache = null;
        }
    }

    private static String buildGitStatus(String cwd) {
        if (cwd == null) return null;
        File dir = new File(cwd);
        try {
            String insideWorkTree = runGit(dir, "rev-parse", "--is-inside-work-tree");
            if (!Strings.CS.equals("true", insideWorkTree)) return null;

            String branch = runGit(dir, "symbolic-ref", "--short", "HEAD");
            if (branch == null) {
                branch = runGit(dir, "rev-parse", "--abbrev-ref", "HEAD");
            }
            if (branch == null) branch = "HEAD";

            String mainBranch = runGit(dir, "rev-parse", "--abbrev-ref", "origin/HEAD");
            if (mainBranch != null && Strings.CS.startsWith(mainBranch, "origin/")) {
                mainBranch = mainBranch.substring("origin/".length());
            }
            if (Strings.CS.equals("HEAD", mainBranch)) mainBranch = null;
            if (mainBranch == null) mainBranch = "main";

            String status = runGit(dir, "--no-optional-locks", "status", "--short");
            if (status == null) status = "";
            if (status.length() > MAX_STATUS_CHARS) {
                status = status.substring(0, MAX_STATUS_CHARS)
                    + "\n... (truncated because it exceeds 2k characters. If you need more information, run \"git status\" using BashTool)";
            }

            String log = runGit(dir, "--no-optional-locks", "log", "--oneline", "-n", "5");
            if (log == null) log = "";

            String userName = runGit(dir, "config", "user.name");

            StringBuilder sb = new StringBuilder();
            sb.append("This is the git status at the start of the conversation. Note that this status is a snapshot in time, and will not update during the conversation.");
            sb.append("\n\nCurrent branch: ").append(branch);
            sb.append("\n\nMain branch (you will usually use this for PRs): ").append(mainBranch);
            if (StringUtils.isNotBlank(userName)) {
                sb.append("\n\nGit user: ").append(userName);
            }
            sb.append("\n\nStatus:\n").append(StringUtils.isBlank(status) ? "(clean)" : status);
            sb.append("\n\nRecent commits:\n").append(log);
            return sb.toString();
        } catch (Exception _) {
            return null;
        }
    }

    private static String runGit(File dir, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        Collections.addAll(command, args);
        ProcessResult result = ProcessRunner.run(command, dir.toPath(), Duration.ofSeconds(30));
        return result.succeeded() ? result.stdout().trim() : null;
    }
}
