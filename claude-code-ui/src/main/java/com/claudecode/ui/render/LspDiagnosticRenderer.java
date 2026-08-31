package com.claudecode.ui.render;

import com.claudecode.core.constants.AnsiStyle;
import com.claudecode.lsp.Diagnostic;
import com.claudecode.lsp.Diagnostic.Severity;
import com.claudecode.ui.Ansi;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.theme.Theme;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Renders LSP diagnostics in a user-friendly format.
 */
public final class LspDiagnosticRenderer {

    private static final String SEPARATOR = "─".repeat(60);

    /**
     * Render diagnostics for a single file.
     */
    public static String renderFileDiagnostics(Path filePath, List<Diagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        String filename = filePath.getFileName().toString();
        
        // Group by severity
        Map<Severity, List<Diagnostic>> bySeverity = diagnostics.stream()
            .collect(Collectors.groupingBy(Diagnostic::severity));

        // Count
        int errors = bySeverity.getOrDefault(Severity.ERROR, List.of()).size();
        int warnings = bySeverity.getOrDefault(Severity.WARNING, List.of()).size();
        int infos = bySeverity.getOrDefault(Severity.INFORMATION, List.of()).size();

        // Header
        sb.append("\n");
        sb.append(SEPARATOR).append("\n");
        sb.append(Ansi.styled(filename, AnsiStyle.BOLD)).append("\n");

        // Summary
        Theme theme = LanternaTheme.activeTheme();
        if (errors > 0) {
            sb.append(Ansi.colored("  ✖ " + errors + " error" + (errors > 1 ? "s" : ""), theme.error()));
        }
        if (warnings > 0) {
            if (errors > 0) sb.append("  ");
            sb.append(Ansi.colored("⚠ " + warnings + " warning" + (warnings > 1 ? "s" : ""), theme.warning()));
        }
        if (infos > 0) {
            if (errors > 0 || warnings > 0) sb.append("  ");
            sb.append(Ansi.colored("ℹ " + infos + " info", theme.permission()));
        }
        sb.append("\n");
        sb.append(SEPARATOR).append("\n");

        // List each diagnostic
        for (Diagnostic diag : diagnostics) {
            sb.append(renderDiagnostic(diag));
        }

        return sb.toString();
    }

    /**
     * Render a single diagnostic.
     */
    public static String renderDiagnostic(Diagnostic diag) {
        StringBuilder sb = new StringBuilder();
        Theme theme = LanternaTheme.activeTheme();

        // Location
        sb.append(Ansi.colored(String.format("  %d:%d", diag.startLine() + 1, diag.startCharacter() + 1), theme.subtle()));

        // Severity indicator
        switch (diag.severity()) {
            case ERROR -> {
                sb.append("  ");
                sb.append(Ansi.styled("error", theme.error(), AnsiStyle.BOLD));
            }
            case WARNING -> {
                sb.append("  ");
                sb.append(Ansi.colored("warning", theme.warning()));
            }
            case INFORMATION -> {
                sb.append("  ");
                sb.append(Ansi.colored("info", theme.permission()));
            }
            case HINT -> {
                sb.append("  ");
                sb.append(Ansi.colored("hint", theme.suggestion()));
            }
        }

        // Source and code
        if (diag.source() != null) {
            sb.append("  [").append(diag.source()).append("]");
        }
        if (diag.code() != null) {
            sb.append(" (").append(diag.code()).append(")");
        }

        sb.append("\n");
        
        // Message
        sb.append("    ").append(diag.message()).append("\n");

        return sb.toString();
    }

    private LspDiagnosticRenderer() {}
}
