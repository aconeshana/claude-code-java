package com.claudecode.services.doctor;


import com.claudecode.mcp.McpConfigWarning;
import com.claudecode.permissions.UnreachableRule;

import java.util.List;

/**
 * Structured result of a {@code /doctor} diagnostic run, produced by {@link
 * DoctorDiagnosticsCollector} and consumed by both the interactive dialog ({@code DoctorDialog} in
 * {@code claude-code-ui}) and the headless text fallback ({@code DoctorCommand} in {@code
 * claude-code-commands}).
 */
public record DiagnosticReport(
    RuntimeInfo runtime,
    RipgrepStatus ripgrepStatus,
    List<McpConfigWarning> mcpWarnings,
    List<EnvVarCheck> envVarChecks,
    List<UnreachableRule> unreachableRules,
    ContextUsage contextUsage,
    List<SettingsValidationError> invalidSettings,
    List<String> sandboxDiagnostics,
    List<AgentParseError> agentParseErrors,
    List<String> pluginErrors
) {
    public enum RipgrepMode { SYSTEM, BUILTIN }

    public record RipgrepStatus(boolean working, RipgrepMode mode, String systemPath) {}

    public record RuntimeInfo(
        String javaVersion,
        String javaVendor,
        String osName,
        String osVersion,
        String osArch,
        String workingDirectory,
        String appVersion
    ) {}


    public record SettingsValidationError(String file, String path, String message) {}

    /** {@code status} is one of {@code "valid"}, {@code "capped"}, {@code "invalid"}. */
    public record EnvVarCheck(String name, long effective, String status, String message) {}


    public record AgentParseError(String path, String error) {}

    public record ContextUsage(
        ClaudeMdWarning claudeMd,
        AgentDescriptionsWarning agents,
        McpToolsWarning mcpTools
    ) {}

    public record ClaudeMdWarning(List<FileSize> largeFiles, long thresholdChars) {}

    public record FileSize(String path, long chars) {}


    public record AgentDescriptionsWarning(long totalTokens, long thresholdTokens,
                                           List<AgentTokens> topAgents, int moreCount) {}

    public record AgentTokens(String name, long tokens) {}


    public record McpToolsWarning(long totalTokens, long thresholdTokens,
                                  List<ServerTokens> byServer, int moreCount) {}

    public record ServerTokens(String serverName, int toolCount, long tokens) {}
}
