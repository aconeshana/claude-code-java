package com.claudecode.ui.lanterna.status;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.runtime.doctor.DoctorReport;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Converts the shared doctor snapshot into the warning list shown by Status. */
public final class StatusDiagnostics {

    private StatusDiagnostics() {}


    public static List<String> from(DoctorReport report) {
        if (report == null) return List.of();
        List<String> out = new ArrayList<>();

        if (!report.invalidSettings().isEmpty()) {
            Set<String> files = new LinkedHashSet<>();
            report.invalidSettings().forEach(error -> files.add(error.file()));
            out.add("Found invalid settings files: " + String.join(", ", files)
                + ". They will be ignored.");
        }

        DoctorReport.ContextUsage context = report.contextUsage();
        if (context != null && context.claudeMd() != null) {
            long threshold = context.claudeMd().thresholdChars();
            for (DoctorReport.FileSize file : context.claudeMd().largeFiles()) {
                out.add("Large " + file.path() + " will impact performance ("
                    + file.chars() + " chars > " + threshold + ")");
            }
        }

        report.mcpRows().stream()
            .filter(row -> row.style() == DoctorReport.Style.WARN
                || row.style() == DoctorReport.Style.ERROR)
            .map(DoctorReport.DiagnosticRow::text)
            .filter(StringUtils::isNotBlank)
            .forEach(out::add);
        report.envVarChecks().stream()
            .filter(check -> check.status() != null
                && !Strings.CI.equals(check.status(), "ok")
                && !Strings.CI.equals(check.status(), "valid"))
            .map(DoctorReport.EnvVarCheck::message)
            .filter(StringUtils::isNotBlank)
            .forEach(out::add);
        out.addAll(report.sandboxDiagnostics());
        report.agentParseErrors().forEach(error ->
            out.add("Failed to parse agent " + error.path() + ": " + error.error()));
        out.addAll(report.pluginErrors());
        return List.copyOf(new LinkedHashSet<>(out));
    }
}
