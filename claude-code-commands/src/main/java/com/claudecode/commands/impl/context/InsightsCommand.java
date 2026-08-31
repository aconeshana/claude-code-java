package com.claudecode.commands.impl.context;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.prompt.PromptInvocation;
import com.claudecode.core.message.TextBlock;
import com.claudecode.commands.insights.InsightsPort;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.List;

/**
 * /insights — analyze all Claude Code sessions and generate a shareable usage report.
 */
@SlashCommand(
    name = "insights",
    description = "Generate a report analyzing your Claude Code sessions"
)
public class InsightsCommand implements AnnotatedCommand {

    @Override
    public boolean supportsNonInteractive() { return true; }

    /** The pipeline makes up to ~56 LLM calls — never run it on the GUI thread. */
    @Override
    public boolean isLongRunning() { return true; }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        if (context.application().insights() == null || context.application().insights().get() == null) {
            return CommandResult.of(
                "Insights is not available: no LLM pipeline wired (headless/bridge mode).");
        }
        InsightsPort.Report report;
        try {
            report = context.application().insights().get().generate();
        } catch (Exception e) {
            return CommandResult.of("Failed to generate insights: "
                + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }

        String reportUrl = "file://" + report.htmlPath();
        return promptResult(buildPrompt(report, reportUrl));
    }

    static CommandResult promptResult(String prompt) {
        return CommandResult.forPrompt(PromptInvocation.builder(List.of(new TextBlock(prompt)))
            .progressMessage("analyzing your sessions")
            .source("builtin")
            .userFacingName("insights")
            .contentLength(0)
            .build());
    }


    static String buildPrompt(InsightsPort.Report report, String reportUrl) {
        InsightsPort.Stats data = report.stats();
        Map<String, JsonNode> insights = report.insights();


        String sessionLabel = data.totalSessionsScanned() != null
                && data.totalSessionsScanned() > data.totalSessions()
            ? String.format("%,d sessions total · %d analyzed",
                data.totalSessionsScanned(), data.totalSessions())
            : data.totalSessions() + " sessions";
        String stats = String.join(" · ",
            sessionLabel,
            String.format("%,d messages", data.totalMessages()),
            Math.round(data.totalDurationHours()) + "h",
            data.gitCommits() + " commits");


        JsonNode atAGlance = insights.get("at_a_glance");
        String summaryText;
        if (atAGlance != null && atAGlance.isObject()) {
            summaryText = "## At a Glance\n\n"
                + glanceLine(atAGlance, "whats_working",
                    "**What's working:** ", " See _Impressive Things You Did_.") + "\n\n"
                + glanceLine(atAGlance, "whats_hindering",
                    "**What's hindering you:** ", " See _Where Things Go Wrong_.") + "\n\n"
                + glanceLine(atAGlance, "quick_wins",
                    "**Quick wins to try:** ", " See _Features to Try_.") + "\n\n"
                + glanceLine(atAGlance, "ambitious_workflows",
                    "**Ambitious workflows:** ", " See _On the Horizon_.");
        } else {
            summaryText = "_No insights generated_";
        }

        String header = "# Claude Code Insights\n\n"
            + stats + "\n"
            + data.startDate() + " to " + data.endDate() + "\n\n";

        String userSummary = header + summaryText
            + "\n\nYour full shareable insights report is ready: " + reportUrl;

        return "The user just ran /insights to generate a usage report analyzing their Claude Code sessions.\n\n"
            + "Here is the full insights data:\n"
            + report.insightsJson() + "\n\n"
            + "Report URL: " + reportUrl + "\n"
            + "HTML file: " + report.htmlPath() + "\n"
            + "Facets directory: " + report.htmlPath().getParent().resolve("facets") + "\n\n"
            + "Here is what the user sees:\n"
            + userSummary + "\n\n"
            + "Now output the following message exactly:\n\n"
            + "<message>\n"
            + "Your shareable insights report is ready:\n"
            + reportUrl + "\n\n"
            + "Want to dig into any section or try one of the suggestions?\n"
            + "</message>";
    }

    private static String glanceLine(JsonNode glance, String field, String prefix, String suffix) {
        String value = glance.path(field).asText("");
        return value.isEmpty() ? "" : prefix + value + suffix;
    }
}
