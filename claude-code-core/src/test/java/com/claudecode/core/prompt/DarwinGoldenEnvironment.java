package com.claudecode.core.prompt;

import com.claudecode.core.process.SubprocessEnvironment;

import java.util.Map;

/**
 * Stubs the platform identity the frozen TS goldens were captured with
 * ({@code Platform: darwin}, {@code Shell: zsh}, {@code OS Version: Darwin 23.5.0}).
 *
 * <p>The golden fixtures are verbatim TypeScript references — rewriting them per
 * platform would destroy their value — so the tests render under the captured
 * platform instead: {@code os.name}/{@code os.version} system properties plus a
 * {@code SHELL} runtime override (the same seam the process environment uses).
 * Restore between tests so production behavior on the real host is unaffected.
 */
final class DarwinGoldenEnvironment {

    private static String osName;
    private static String osVersion;

    private DarwinGoldenEnvironment() {}

    static void stub() {
        osName = System.setProperty("os.name", "Mac OS X");
        osVersion = System.setProperty("os.version", "23.5.0");
        SubprocessEnvironment.updateRuntime(Map.of("SHELL", "/bin/zsh"));
    }

    static void restore() {
        if (osName == null) System.clearProperty("os.name");
        else System.setProperty("os.name", osName);
        if (osVersion == null) System.clearProperty("os.version");
        else System.setProperty("os.version", osVersion);
        SubprocessEnvironment.clearRuntimeOverrides();
    }
}
