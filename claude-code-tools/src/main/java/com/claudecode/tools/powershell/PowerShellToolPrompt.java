package com.claudecode.tools.powershell;

import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.tools.ToolTexts;
import com.claudecode.tools.bash.BashTimeouts;
import com.claudecode.tools.shell.OutputLimits;
import java.util.Map;
import java.util.function.Function;

/**
 * PowerShellTool description resource renderer.
 */
final class PowerShellToolPrompt {

    private PowerShellToolPrompt() {}

    static String getPrompt() {
        return getPrompt(SubprocessEnvironment::get);
    }

    static String getPrompt(Function<String, String> envLookup) {
        long defaultTimeoutMs = BashTimeouts.defaultTimeoutMs(envLookup);
        long maxTimeoutMs = BashTimeouts.maxTimeoutMs(envLookup);
        return ToolTexts.render(ToolTexts.prompt("PowerShell", "template"), Map.of(
                "MAX_TIMEOUT_MS", maxTimeoutMs,
                "MAX_TIMEOUT_MINUTES", maxTimeoutMs / 60_000,
                "DEFAULT_TIMEOUT_MS", defaultTimeoutMs,
                "DEFAULT_TIMEOUT_MINUTES", defaultTimeoutMs / 60_000,
                "MAX_OUTPUT_LENGTH", OutputLimits.getMaxOutputLength()));
    }
}
