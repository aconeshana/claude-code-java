package com.claudecode.tools.sandbox;

import com.claudecode.core.engine.SandboxConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * macOS sandbox backend — wraps a command with {@code sandbox-exec} and a dynamically generated
 * seatbelt profile.
 */
public class SeatbeltSandboxBackend extends SandboxManager {

    @Override
    public boolean available() {
        return isExecutableOnPath("sandbox-exec");
    }

    @Override
    public String unavailableReason() {
        return "sandbox-exec is not available on this macOS host; cannot run sandboxed. "
            + "Set dangerouslyDisableSandbox: true to run unsandboxed.";
    }

    @Override
    public List<String> wrap(String command, Path cwd, SandboxConfig cfg) {
        String profile = buildProfile(cwd, cfg);
        List<String> argv = new ArrayList<>();
        argv.add("sandbox-exec");
        argv.add("-p");
        argv.add(profile);
        argv.add("bash");
        argv.add("-c");
        argv.add(command);
        return argv;
    }

    /** Build the seatbelt profile (last-match-wins: denies placed after allows override). */
    private String buildProfile(Path cwd, SandboxConfig cfg) {
        SandboxConfig.SandboxFilesystemConfig fs =
            cfg.filesystem() != null ? cfg.filesystem() : SandboxConfig.SandboxFilesystemConfig.DEFAULT;
        boolean netAllowed = networkAllowed(cfg);

        List<String> lines = new ArrayList<>();
        lines.add("(version 1)");
        lines.add("(deny default)");
        // Broad read access by default (read-only view of the filesystem).
        if (fs.allowManagedReadPathsOnly()) {
            // Restrict reads to cwd + explicit allowRead paths only.
            lines.add("(deny file-read*)");
            lines.add("(allow file-read* (subpath \"" + cwd + "\"))");
            for (String p : fs.allowRead()) {
                lines.add("(allow file-read* (subpath \"" + p + "\"))");
            }
        } else {
            lines.add("(allow file-read*)");
        }
        // Execution + signals the command needs to run and manage its children.
        lines.add("(allow process-exec*)");
        lines.add("(allow signal (target self))");
        // Writable locations: cwd, /tmp, the Claude temp dir, and any explicit
        // allowWrite paths.
        lines.add("(allow file-write* (subpath \"" + cwd + "\"))");
        lines.add("(allow file-write* (subpath \"/tmp\"))");
        for (String p : builtInAllowWrite()) {
            lines.add("(allow file-write* (subpath \"" + p + "\"))");
        }
        for (String p : fs.allowWrite()) {
            lines.add("(allow file-write* (subpath \"" + p + "\"))");
        }
        // Worktree main-repo path (F6): git needs write access to the main repo's
        // .git for index.lock etc. Resolved per-command from cwd.
        Path wt = detectWorktreeMainRepoPath(cwd);
        if (wt != null) {
            lines.add("(allow file-write* (subpath \"" + wt + "\"))");
        }
        // Explicit denyWrite / denyRead overrides (placed last → win).
        for (String p : fs.denyWrite()) {
            lines.add("(deny file-write* (subpath \"" + p + "\"))");
        }
        // Built-in denial of settings/.claude/skills/bare-repo files (sandbox escape
        // hardening) — also placed last so it overrides the allows above.
        for (String p : builtInDenyWrite(cwd)) {
            lines.add("(deny file-write* (subpath \"" + p + "\"))");
        }
        for (String p : fs.denyRead()) {
            lines.add("(deny file-read* (subpath \"" + p + "\"))");
        }
        // Network.
        if (usesDomainProxy(cfg)) {
            // Domain allowlist active: block all direct network, but permit the
            // loopback interface so the (unsandboxed) parent proxy — the only
            // allowed outbound path — is reachable. The proxy enforces domains.
            lines.add("(deny network*)");
            lines.add("(allow network* (to \"lo0\"))");
        } else if (netAllowed) {
            lines.add("(allow network*)");
        } else {
            lines.add("(deny network*)");
        }
        return String.join("\n", lines);
    }
}
