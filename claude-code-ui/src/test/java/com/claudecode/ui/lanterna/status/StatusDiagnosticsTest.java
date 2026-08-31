package com.claudecode.ui.lanterna.status;

import org.apache.commons.lang3.Strings;
import com.claudecode.runtime.doctor.DoctorReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusDiagnosticsTest {

    @Test
    void extractsInvalidSettingsMemoryAndDoctorWarnings() {
        DoctorReport report = new DoctorReport(
            new DoctorReport.RuntimeInfo("1"),
            new DoctorReport.RipgrepStatus(false, DoctorReport.RipgrepMode.BUILTIN, null),
            List.of(new DoctorReport.DiagnosticRow("MCP server failed", DoctorReport.Style.ERROR)),
            List.of(new DoctorReport.EnvVarCheck("CLAUDE_CODE_MAX_OUTPUT_TOKENS", 0,
                "warning", "Invalid value")),
            List.of(),
            new DoctorReport.ContextUsage(
                new DoctorReport.ClaudeMdWarning(
                    List.of(new DoctorReport.FileSize("/repo/CLAUDE.md", 50_000)), 40_000),
                null, null),
            List.of(new DoctorReport.SettingsValidationError(
                "/repo/.claude/settings.json", "sandbox", "invalid")),
            List.of("Sandbox dependency missing"),
            List.of(new DoctorReport.AgentParseError("agent.md", "bad yaml")),
            List.of("Plugin manifest invalid"));

        List<String> diagnostics = StatusDiagnostics.from(report);

        assertTrue(diagnostics.stream().anyMatch(v -> Strings.CS.contains(v, "invalid settings files")));
        assertTrue(diagnostics.stream().anyMatch(v -> Strings.CS.contains(v, "Large /repo/CLAUDE.md")));
        assertTrue(diagnostics.contains("MCP server failed"));
        assertTrue(diagnostics.contains("Invalid value"));
        assertTrue(diagnostics.contains("Sandbox dependency missing"));
        assertTrue(diagnostics.stream().anyMatch(v -> Strings.CS.contains(v, "agent.md")));
        assertTrue(diagnostics.contains("Plugin manifest invalid"));
    }
}
