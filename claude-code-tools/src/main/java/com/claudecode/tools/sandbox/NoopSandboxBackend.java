package com.claudecode.tools.sandbox;

import com.claudecode.core.engine.SandboxConfig;
import com.claudecode.core.process.ExecutableFinder;

import java.nio.file.Path;
import java.util.List;
import com.claudecode.tools.bash.BashTool;

/**
 * Sandbox backend for platforms with no native sandbox support (Windows/other).
 * Always reports unavailable; the caller degrades per {@code failIfUnavailable}.
 * <p>
 * Kept so {@link PlatformSandboxManager} can return a non-null manager
 * everywhere and {@link BashTool} logic stays uniform.
 */
public class NoopSandboxBackend extends SandboxManager {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String unavailableReason() {
        return "sandboxing is not supported on this platform; cannot run sandboxed. "
            + "Set dangerouslyDisableSandbox: true to run unsandboxed.";
    }

    @Override
    public List<String> wrap(String command, Path cwd, SandboxConfig cfg) {
        // Should only be reached when a decision already resolved to unsandboxed;
        // fall back to a plain bash invocation so the caller still works.
        return List.of(ExecutableFinder.bashExecutable(), "-c", command);
    }
}
