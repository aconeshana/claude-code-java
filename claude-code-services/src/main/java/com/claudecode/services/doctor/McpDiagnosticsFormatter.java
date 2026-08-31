package com.claudecode.services.doctor;


import org.apache.commons.lang3.StringUtils;
import com.claudecode.mcp.McpConfigLoader;
import com.claudecode.mcp.McpConfigWarning;
import com.claudecode.mcp.McpServerScope;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a list of {@link McpConfigWarning} into scope-grouped display rows, shared by the
 * interactive {@code DoctorDialog} and the headless {@code DoctorCommand} text fallback so the two
 * never drift.
 */
public final class McpDiagnosticsFormatter {

    private static final String HELP_URL = "https://code.claude.com/docs/en/mcp";
    private static final List<McpServerScope> SCOPE_ORDER =
        List.of(McpServerScope.USER, McpServerScope.PROJECT, McpServerScope.LOCAL);

    private McpDiagnosticsFormatter() {}

    /** Visual emphasis for a row; renderers map this to their own colouring. */
    public enum Style { HEADER, DIM, WARN, ERROR }

    public record Row(String text, Style style) {}

    /**
     * @param diagnostics structured MCP warnings from {@link McpConfigLoader#loadConfig}
     * @param cwd         working directory (used to describe project/local config paths)
     * @return display rows, or an empty list when there are no diagnostics
     */
    public static List<Row> format(List<McpConfigWarning> diagnostics, Path cwd) {
        if (diagnostics == null || diagnostics.isEmpty()) return List.of();

        List<Row> rows = new ArrayList<>();
        rows.add(new Row("MCP Config Diagnostics", Style.HEADER));
        rows.add(new Row("For help configuring MCP servers, see: " + HELP_URL, Style.DIM));

        for (McpServerScope scope : SCOPE_ORDER) {
            List<McpConfigWarning> inScope = diagnostics.stream()
                .filter(d -> d.scope() == scope)
                .toList();
            if (inScope.isEmpty()) continue;


            // a blank line separates the help link and each scope group.
            rows.add(new Row("", Style.DIM));

            boolean hasFatal = inScope.stream()
                .anyMatch(d -> d.severity() == McpConfigWarning.Severity.FATAL);
            String tag = hasFatal ? "[Failed to parse]" : "[Contains warnings]";
            rows.add(new Row(tag + " " + scope.label(), hasFatal ? Style.ERROR : Style.WARN));
            rows.add(new Row("Location: " + McpConfigLoader.describeConfigPath(scope, cwd), Style.DIM));

            for (McpConfigWarning d : inScope) {
                boolean fatal = d.severity() == McpConfigWarning.Severity.FATAL;
                String sev = fatal ? "[Error]" : "[Warning]";
                String server = StringUtils.isNotBlank(d.serverName())
                    ? " [" + d.serverName() + "]" : "";
                String path = StringUtils.isNotEmpty(d.path())
                    ? " " + d.path() + ":" : "";
                rows.add(new Row("└ " + sev + server + path + " " + d.message(),
                    fatal ? Style.ERROR : Style.WARN));
            }
        }
        return rows;
    }
}
