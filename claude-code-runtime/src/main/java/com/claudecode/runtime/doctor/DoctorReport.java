package com.claudecode.runtime.doctor;

import java.util.List;

/**
 * Presentation-neutral diagnostic snapshot for interactive runtime consumers.
 */
public record DoctorReport(
    RuntimeInfo runtime,
    RipgrepStatus ripgrepStatus,
    List<DiagnosticRow> mcpRows,
    List<EnvVarCheck> envVarChecks,
    List<UnreachablePermissionRule> unreachableRules,
    ContextUsage contextUsage,
    List<SettingsValidationError> invalidSettings,
    List<String> sandboxDiagnostics,
    List<AgentParseError> agentParseErrors,
    List<String> pluginErrors
) {
    public enum RipgrepMode { SYSTEM, BUILTIN }
    public record RipgrepStatus(boolean working, RipgrepMode mode, String systemPath) {}

    public record RuntimeInfo(String appVersion) {}
    public enum Style { HEADER, DIM, WARN, ERROR }
    public record DiagnosticRow(String text, Style style) {}
    public record SettingsValidationError(String file, String path, String message) {}
    public record EnvVarCheck(String name, long effective, String status, String message) {}
    public record AgentParseError(String path, String error) {}
    public record UnreachablePermissionRule(String ruleDisplay, String reason, String fix) {}
    public record ContextUsage(ClaudeMdWarning claudeMd,
                               AgentDescriptionsWarning agents,
                               McpToolsWarning mcpTools) {}
    public record ClaudeMdWarning(List<FileSize> largeFiles, long thresholdChars) {}
    public record FileSize(String path, long chars) {}
    public record AgentDescriptionsWarning(long totalTokens, long thresholdTokens,
                                           List<AgentTokens> topAgents, int moreCount) {}
    public record AgentTokens(String name, long tokens) {}
    public record McpToolsWarning(long totalTokens, long thresholdTokens,
                                  List<ServerTokens> byServer, int moreCount) {}
    public record ServerTokens(String serverName, int toolCount, long tokens) {}
}
