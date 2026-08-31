package com.claudecode.tools.sandbox;

import com.claudecode.core.platform.Platform;
import com.claudecode.tools.bash.BashTool;

/**
 * Selects the platform-appropriate {@link SandboxManager} backend.
 */
public final class PlatformSandboxManager {

    private PlatformSandboxManager() {}

    public static SandboxManager create() {
        if (Platform.IS_DARWIN) {
            return new SeatbeltSandboxBackend();
        }
        if (Platform.IS_LINUX) {
            return new BwrapSandboxBackend();
        }
        return new NoopSandboxBackend();
    }
}
