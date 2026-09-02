package com.claudecode.commands.impl.info;

import static com.claudecode.core.config.EnvUtils.isEnvTruthy;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.runtime.doctor.DoctorReport;
import org.apache.commons.lang3.StringUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * {@code /doctor} — diagnose and verify the Claude Code installation and settings.
 */
@SlashCommand(
    name = "doctor",
    description = "Diagnose and verify your Claude Code installation and settings"
)
public class DoctorCommand implements AnnotatedCommand {

    private static final String DISABLE_ENV = "DISABLE_DOCTOR_COMMAND";

    @Override
    public boolean isAvailable(CommandContext context) {
        return !isEnvTruthy(SubprocessEnvironment.get(DISABLE_ENV));
    }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        if (context.presentation().doctorDialogLauncher() != null) {
            context.presentation().doctorDialogLauncher().run();
            return CommandResult.skip();
        }
        return CommandResult.of(renderTextReport(context));
    }

    private String renderTextReport(CommandContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("Diagnostics\n");
        sb.append("===========\n\n");

        sb.append("Java: ").append(System.getProperty("java.version")).append("\n");
        sb.append("Java Vendor: ").append(System.getProperty("java.vendor")).append("\n");
        sb.append("Java Home: ").append(System.getProperty("java.home")).append("\n\n");

        sb.append("OS: ").append(System.getProperty("os.name")).append(" ");
        sb.append(System.getProperty("os.version")).append(" ");
        sb.append(System.getProperty("os.arch")).append("\n\n");

        sb.append("Model: ").append(context.session().model()).append("\n");
        sb.append("Working directory: ").append(context.session().workingDirectory()).append("\n\n");

        checkGit(sb, context.session().workingDirectory());
        appendCollectedDiagnostics(sb, context);

        return sb.toString();
    }

    private void appendCollectedDiagnostics(StringBuilder sb, CommandContext context) {
        if (context.application().doctor() == null) {
            sb.append("Runtime diagnostics are unavailable in this context.\n");
            return;
        }
        DoctorReport report = context.application().doctor().collect();

        sb.append("Version: ").append(report.runtime().appVersion()).append("\n\n");

        sb.append("Search:\n");
        sb.append("  ripgrep: ")
          .append(formatRipgrepStatus(report.ripgrepStatus()))
          .append("\n\n");

        if (!report.invalidSettings().isEmpty()) {
            sb.append("Invalid Settings:\n");
            LinkedHashMap<String, List<DoctorReport.SettingsValidationError>> byFile =
                new LinkedHashMap<>();
            for (DoctorReport.SettingsValidationError e : report.invalidSettings()) {
                byFile.computeIfAbsent(e.file(), _ -> new ArrayList<>()).add(e);
            }
            for (var entry : byFile.entrySet()) {
                sb.append("  ").append(entry.getKey()).append("\n");
                for (DoctorReport.SettingsValidationError e : entry.getValue()) {
                    String p = StringUtils.isNotEmpty(e.path()) ? e.path() + ": " : "";
                    sb.append("    ").append(p).append(e.message()).append("\n");
                }
            }
            sb.append("\n");
        }

        // SandboxDoctorSection — present only when enabled diagnostics contain issues.
        if (!report.sandboxDiagnostics().isEmpty()) {
            sb.append("Sandbox:\n");
            for (String s : report.sandboxDiagnostics()) sb.append("  ").append(s).append("\n");
            sb.append("\n");
        }

        List<DoctorReport.DiagnosticRow> mcpRows = report.mcpRows();
        if (!mcpRows.isEmpty()) {
            for (DoctorReport.DiagnosticRow row : mcpRows) {
                String indent = row.style() == DoctorReport.Style.HEADER ? "" : "  ";
                sb.append(indent).append(row.text()).append("\n");
            }
            sb.append("\n");
        }

        if (!report.envVarChecks().isEmpty()) {
            sb.append("Environment Variables:\n");
            for (DoctorReport.EnvVarCheck c : report.envVarChecks()) {
                sb.append("  ").append(c.name()).append(": ").append(c.message()).append("\n");
            }
            sb.append("\n");
        }


        if (!report.agentParseErrors().isEmpty()) {
            sb.append("Agent Parse Errors:\n");
            sb.append("  Failed to parse ").append(report.agentParseErrors().size())
              .append(" agent file(s):\n");
            for (DoctorReport.AgentParseError f : report.agentParseErrors()) {
                sb.append("    ").append(f.path()).append(": ").append(f.error()).append("\n");
            }
            sb.append("\n");
        }


        // ("N plugin error(s) detected:" + per-error "source [plugin]: message" rows).
        if (!report.pluginErrors().isEmpty()) {
            sb.append("Plugin Errors:\n");
            sb.append("  ").append(report.pluginErrors().size())
              .append(" plugin error(s) detected:\n");
            for (String e : report.pluginErrors()) sb.append("    ").append(e).append("\n");
            sb.append("\n");
        }

        if (!report.unreachableRules().isEmpty()) {
            sb.append("Unreachable Permission Rules:\n");
            int n = report.unreachableRules().size();
            sb.append("  ").append(n).append(" unreachable permission rule")
              .append(n == 1 ? "" : "s").append(" detected\n");
            for (DoctorReport.UnreachablePermissionRule r : report.unreachableRules()) {
                sb.append("    ").append(r.ruleDisplay()).append(": ").append(r.reason()).append("\n");
                sb.append("      Fix: ").append(r.fix()).append("\n");
            }
            sb.append("\n");
        }

        appendContextUsage(sb, report.contextUsage());
    }

    static String formatRipgrepStatus(DoctorReport.RipgrepStatus status) {
        if (!status.working()) return "not working (Java regex fallback)";
        return switch (status.mode()) {
            case BUILTIN -> "available (vendor)";
            case SYSTEM -> "available ("
                + (status.systemPath() != null ? status.systemPath() : "system") + ")";
        };
    }

    private void appendContextUsage(StringBuilder sb, DoctorReport.ContextUsage ctx) {
        if (ctx.claudeMd() == null && ctx.agents() == null && ctx.mcpTools() == null) return;

        sb.append("Context Usage Warnings:\n");
        if (ctx.claudeMd() != null) {
            List<DoctorReport.FileSize> files = ctx.claudeMd().largeFiles();
            long threshold = ctx.claudeMd().thresholdChars();
            String header = files.size() == 1
                ? "Large CLAUDE.md file detected (" + fmt(files.getFirst().chars())
                    + " chars > " + fmt(threshold) + ")"
                : files.size() + " large CLAUDE.md files detected (each > " + fmt(threshold) + " chars)";
            sb.append("  ").append(header).append(":\n");
            for (DoctorReport.FileSize f : files) {
                sb.append("    ").append(f.path()).append(": ").append(fmt(f.chars())).append(" chars\n");
            }
        }
        if (ctx.agents() != null) {
            sb.append("  Large agent descriptions (~").append(fmt(ctx.agents().totalTokens()))
              .append(" tokens > ").append(fmt(ctx.agents().thresholdTokens())).append("):\n");
            for (DoctorReport.AgentTokens a : ctx.agents().topAgents()) {
                sb.append("    ").append(a.name()).append(": ~").append(fmt(a.tokens())).append(" tokens\n");
            }
            if (ctx.agents().moreCount() > 0) {
                sb.append("    (").append(ctx.agents().moreCount()).append(" more custom agents)\n");
            }
        }
        if (ctx.mcpTools() != null) {
            sb.append("  Large MCP tools context (~").append(fmt(ctx.mcpTools().totalTokens()))
              .append(" tokens estimated > ").append(fmt(ctx.mcpTools().thresholdTokens())).append("):\n");
            for (DoctorReport.ServerTokens s : ctx.mcpTools().byServer()) {
                sb.append("    ").append(s.serverName()).append(": ").append(s.toolCount())
                  .append(" tools (~").append(fmt(s.tokens())).append(" tokens)\n");
            }
            if (ctx.mcpTools().moreCount() > 0) {
                sb.append("    (").append(ctx.mcpTools().moreCount()).append(" more servers)\n");
            }
        }
        sb.append("\n");
    }


    private static String fmt(long n) {
        return String.format(Locale.US, "%,d", n);
    }

    private void checkGit(StringBuilder sb, String workingDir) {
        sb.append("Git:\n");
        try {
            if (StringUtils.isBlank(workingDir)) {
                sb.append("  Error: working directory is unavailable\n\n");
                return;
            }
            Path gitCwd = Path.of(workingDir).toAbsolutePath().normalize();
            if (!Files.isDirectory(gitCwd)) {
                sb.append("  Error: working directory does not exist: ")
                    .append(gitCwd).append("\n\n");
                return;
            }

            String version = runCommand(gitCwd, "git", "--version");
            sb.append("  Version: ").append(version).append("\n");

            String branch = runCommand(gitCwd, "git", "branch", "--show-current");
            sb.append("  Branch: ").append(StringUtils.isBlank(branch) ? "(detached)" : branch).append("\n");

            String status = runCommand(gitCwd, "git", "status", "--porcelain");
            if (StringUtils.isBlank(status)) {
                sb.append("  Status: clean\n");
            } else {
                int lines = status.split("\n").length;
                sb.append("  Status: ").append(lines).append(" file(s) changed\n");
            }
        } catch (Exception e) {
            sb.append("  Error: ").append(e.getMessage()).append("\n");
        }
        sb.append("\n");
    }

    private String runCommand(Path workingDirectory, String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDirectory.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try { p.getOutputStream().close(); } catch (IOException _) {}
        String output = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(10, TimeUnit.SECONDS)) {
            p.destroyForcibly();
        }
        return output.trim();
    }

}
