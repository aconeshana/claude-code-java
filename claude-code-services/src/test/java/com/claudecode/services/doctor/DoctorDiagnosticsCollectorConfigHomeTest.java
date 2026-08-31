package com.claudecode.services.doctor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoctorDiagnosticsCollectorConfigHomeTest {

    @Test
    void memoryDiagnosticsScanExplicitClaudeConfigHome(@TempDir Path temp) throws Exception {
        Path cwd = temp.resolve("project");
        Path home = temp.resolve("home");
        Path configHome = temp.resolve("custom-config");
        Files.createDirectories(cwd);
        Files.createDirectories(configHome);
        Files.writeString(configHome.resolve("CLAUDE.md"), "x".repeat(40_001));

        DoctorDiagnosticsCollector.Inputs inputs = new DoctorDiagnosticsCollector.Inputs(
            cwd, home, configHome, List.of(), List.of(), List.of());
        DiagnosticReport report = new DoctorDiagnosticsCollector().collect(inputs);

        assertNotNull(report.contextUsage().claudeMd());
        assertTrue(report.contextUsage().claudeMd().largeFiles().stream()
            .anyMatch(file -> file.path().equals(configHome.resolve("CLAUDE.md").toString())));
    }
}
