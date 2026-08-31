package com.claudecode.tools.sandbox;

import com.claudecode.core.engine.SandboxConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Linux sandbox backend — wraps a command with {@code bwrap} (bubblewrap).
 */
public class BwrapSandboxBackend extends SandboxManager {

    @Override
    public boolean available() {
        return isExecutableOnPath("bwrap");
    }

    @Override
    public String unavailableReason() {
        return "bwrap (bubblewrap) is not installed on this Linux host; cannot run sandboxed. "
            + "Install bubblewrap or set dangerouslyDisableSandbox: true to run unsandboxed.";
    }

    @Override
    public List<String> wrap(String command, Path cwd, SandboxConfig cfg) {
        SandboxConfig.SandboxFilesystemConfig fs =
            cfg.filesystem() != null ? cfg.filesystem() : SandboxConfig.SandboxFilesystemConfig.DEFAULT;
        boolean netAllowed = networkAllowed(cfg);

        List<String> argv = new ArrayList<>();
        argv.add("bwrap");

        boolean domainProxy = usesDomainProxy(cfg);
        if (!netAllowed && !domainProxy) {
            argv.add("--unshare-net");
        }
        // Read-only root, then re-mount cwd (and allowWrite paths) writable.
        argv.add("--ro-bind");
        argv.add("/");
        argv.add("/");
        argv.add("--bind");
        argv.add(cwd.toString());
        argv.add(cwd.toString());
        for (String p : fs.allowWrite()) {
            argv.add("--bind");
            argv.add(p);
            argv.add(p);
        }
        // Worktree main-repo path (F6): git needs write access to the main repo's
        // .git for index.lock etc. Resolved per-command from cwd.
        Path wt = detectWorktreeMainRepoPath(cwd);
        if (wt != null) {
            argv.add("--bind");
            argv.add(wt.toString());
            argv.add(wt.toString());
        }
        // Explicit allowRead paths (F5): re-assert read access under the ro-bind
        // root so managed-read-only scoping (if enabled) still permits them.
        for (String p : fs.allowRead()) {
            argv.add("--ro-bind");
            argv.add(p);
            argv.add(p);
        }
        // Claude temp dir is always writable (cwd tracking files etc.).
        for (String p : builtInAllowWrite()) {
            argv.add("--bind");
            argv.add(p);
            argv.add(p);
        }
        // Built-in denial of settings/.claude/skills/bare-repo files. With
        // --ro-bind / / the tree is read-only by default, but cwd was re-mounted
        // writable above, so re-assert these as read-only (later mount wins).
        // Paths that don't exist can't be bound — planted bare-repo files are
        // scrubbed post-command by the caller instead.
        for (String p : builtInDenyWrite(cwd)) {
            if (Files.exists(Path.of(p))) {
                argv.add("--ro-bind");
                argv.add(p);
                argv.add(p);
            }
        }
        argv.add("--dev");
        argv.add("/dev");
        argv.add("--proc");
        argv.add("/proc");
        argv.add("--tmpfs");
        argv.add("/tmp");
        argv.add("bash");
        argv.add("-c");
        argv.add(command);
        return argv;
    }
}
