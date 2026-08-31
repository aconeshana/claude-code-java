package com.claudecode.tools.powershell;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import com.claudecode.tools.monitor.MonitorFeatureGate;
import com.claudecode.tools.sandbox.NoopSandboxBackend;

class PowerShellToolTest {

    private final PowerShellTool tool = new PowerShellTool(new NoopSandboxBackend());
    private final ObjectMapper mapper = new ObjectMapper();

    private ToolExecutionContext ctx() {
        return ToolExecutionContext.of(new AbortController(), "test-session");
    }

    private ObjectNode input(String command) {
        return mapper.createObjectNode().put("command", command);
    }

    @Test
    void maxResultSizeChars_matchesReleased197() {
        assertEquals(30_000, tool.maxResultSizeChars());
    }

    // ── F3: schema carries description / run_in_background / dangerouslyDisableSandbox ──

    @Test
    void schemaIncludesBackgroundAndSandboxParams() {
        var props = tool.inputSchema().get("properties");
        assertTrue(props.has("description"), "schema must carry description");
        assertTrue(props.has("run_in_background"), "schema must carry run_in_background");
        assertTrue(props.has("dangerouslyDisableSandbox"), "schema must carry dangerouslyDisableSandbox");
        assertEquals("boolean", props.get("run_in_background").get("type").asText());
        assertEquals("boolean", props.get("dangerouslyDisableSandbox").get("type").asText());
    }

    @Test
    void schemaUsesConfiguredMaximumTimeout() {
        PowerShellTool configured = new PowerShellTool(name -> switch (name) {
            case "BASH_DEFAULT_TIMEOUT_MS" -> "700000";
            case "BASH_MAX_TIMEOUT_MS" -> "300000";
            default -> null;
        }, new NoopSandboxBackend());

        assertEquals("Optional timeout in milliseconds (max 700000)",
            configured.inputSchema().path("properties").path("timeout")
                .path("description").asText());
    }

    // ── F9: isConcurrencySafe via read-only cmdlet classification ──

    @Test
    void concurrencySafe_allowsReadOnlyCmdlets() {
        assertTrue(tool.isConcurrencySafe(input("Get-ChildItem")));
        assertTrue(tool.isConcurrencySafe(input("Get-Content file.txt")));
        assertTrue(tool.isConcurrencySafe(input("Get-Process | Select-Object Name")));
    }

    @Test
    void concurrencySafe_rejectsMutatingCmdlets() {
        // Remove-Item / New-Item are write cmdlets, absent from READ_CMDLETS.
        assertFalse(tool.isConcurrencySafe(input("Remove-Item foo")));
        assertFalse(tool.isConcurrencySafe(input("New-Item bar")));
        assertFalse(tool.isConcurrencySafe(input("Get-Process | Remove-Item foo")));
    }

    @Test
    void concurrencySafe_rejectsScriptblockAndSplatting() {
        // hasSyncSecurityConcerns: scriptblocks / splatting / iex are unsafe.
        assertFalse(tool.isConcurrencySafe(input("Get-Process | ForEach-Object { $_.Name }")));
        assertFalse(tool.isConcurrencySafe(input("Invoke-Expression $x")));
    }

    @Test
    void concurrencySafe_rejectsBackground() {
        ObjectNode in = input("Get-ChildItem");
        in.put("run_in_background", true);
        assertFalse(tool.isConcurrencySafe(in));
    }

    @Test
    void taskActivityClassificationMatchesReleased197CmdletsAndAliases() {
        var mixed = PowerShellTool.classifySearchOrReadCommand(
            "Select-String Task files.txt | Get-Content files.txt");
        assertTrue(mixed.isSearch());
        assertTrue(mixed.isRead());

        var both = PowerShellTool.classifySearchOrReadCommand("gci .");
        assertTrue(both.isSearch());
        assertTrue(both.isRead());

        var neutralPrefix = PowerShellTool.classifySearchOrReadCommand(
            "Write-Output ready | gc files.txt");
        assertFalse(neutralPrefix.isSearch());
        assertTrue(neutralPrefix.isRead());

        var unsupported = PowerShellTool.classifySearchOrReadCommand(
            "Get-Content files.txt | Sort-Object");
        assertFalse(unsupported.isSearch());
        assertFalse(unsupported.isRead());
    }

    // ── F6: leading sleep guard ──

    @Test
    void sleepGuard_blocksLeadingSleep() {
        String result = MonitorFeatureGate.withSystemEnabled(true,
            () -> (String) tool.call(input("sleep 5"), ctx()));
        assertTrue(Strings.CS.startsWith(result, "Blocked:"), "expected sleep block, got: " + result);
        assertTrue(Strings.CS.contains(result, "run_in_background"), result);
    }

    @Test
    void sleepGuard_blocksStartSleep() {
        String result = MonitorFeatureGate.withSystemEnabled(true,
            () -> (String) tool.call(input("start-sleep 10"), ctx()));
        assertTrue(Strings.CS.startsWith(result, "Blocked:"), "expected sleep block, got: " + result);
    }

    @Test
    void sleepGuard_under2sNotBlocked() {
        // Sub-2s sleep is legitimate pacing; guard must pass it through.
        String result = (String) tool.call(input("sleep 1"), ctx());
        assertFalse(Strings.CS.startsWith(result, "Blocked:"), "sub-2s sleep must not be blocked");
    }

    @Test
    void sleepGuard_skippedWhenBackgrounded() {
        ObjectNode in = input("sleep 30");
        in.put("run_in_background", true);
        String result = (String) tool.call(in, ctx());
        assertFalse(Strings.CS.startsWith(result, "Blocked:"), "backgrounded sleep must not be blocked");
    }

    @Test
    void emptyCommandReturnsError() {
        String result = (String) tool.call(input(""), ctx());
        assertTrue(Strings.CS.contains(result, "Error"));
    }

    // ── F8: cached PowerShell discovery + clear seam ──

    @Test
    void cachedPathIsStableAndClearAllowsReProbe() {
        // Reset any JVM-wide cached probe so the test starts from a known state.
        PowerShellTool.clearCachedPowerShellPath();

        // First read probes the environment and caches the result.
        String first = PowerShellTool.resolvedPowerShellPathForTest();
        // Second read must return the identical cached value (no re-probe).
        String second = PowerShellTool.resolvedPowerShellPathForTest();
        assertEquals(first, second, "cached path must be stable within the JVM");

        // Clearing the seam must force a fresh probe that resolves to the
        // same environment-determined binary (null when none is installed).
        PowerShellTool.clearCachedPowerShellPath();
        String third = PowerShellTool.resolvedPowerShellPathForTest();
        assertEquals(first, third, "re-probe after clear must match the environment result");
    }

    @Test
    void executableProbeUsesCommandModeSupportedByWindowsPowerShell() {
        assertEquals(List.of(
            "powershell", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command",
            "$PSVersionTable.PSVersion.ToString()"),
            PowerShellTool.probeCommand("powershell"));
    }
}
