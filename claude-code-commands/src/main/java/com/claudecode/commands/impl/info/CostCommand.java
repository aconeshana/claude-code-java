package com.claudecode.commands.impl.info;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.core.engine.SessionCostState;
import com.claudecode.core.message.Usage;
import com.claudecode.core.model.ModelNames;
import com.claudecode.core.text.FormatUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * /cost — shows the total cost and duration of the current session.
 */
@SlashCommand(
    name = "cost",
    description = "Show the total cost and duration of the current session"
)
public class CostCommand implements AnnotatedCommand {

    @Override
    public boolean supportsNonInteractive() { return true; }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        return CommandResult.local(sessionSummary());
    }

    /** Current-session accounting shared by {@code /cost} and the released Usage tab. */
    public static String sessionSummary() {
        SessionCostState state = SessionCostState.get();


        // so multiple raw model IDs mapping to the same model fold into one line.
        Map<String, Usage> byDisplayName = new LinkedHashMap<>();
        Map<String, Double> costByDisplayName = new LinkedHashMap<>();
        Map<String, Double> recordedCosts = state.costByModel();
        for (Map.Entry<String, Usage> e : state.usageByModel().entrySet()) {
            String display = ModelNames.displayName(e.getKey());
            byDisplayName.merge(display, e.getValue(), Usage::add);
            double modelCost = recordedCosts.getOrDefault(e.getKey(), 0.0);
            costByDisplayName.merge(display, modelCost, Double::sum);
        }

        long added = state.totalLinesAdded();
        long removed = state.totalLinesRemoved();

        String sb = "Total cost:            " + FormatUtils.formatCost(state.totalCostUsd()) + '\n'
          + "Total duration (API):  " + FormatUtils.formatDuration(state.apiDurationMs()) + '\n'
          + "Total duration (wall): " + FormatUtils.formatDuration(state.wallDurationMs()) + '\n'
          + "Total code changes:    "
          + added + (added == 1 ? " line" : " lines") + " added, "
          + removed + (removed == 1 ? " line" : " lines") + " removed" + '\n'
          + formatModelUsage(byDisplayName, costByDisplayName);

        return sb;
    }


    private static String formatModelUsage(
            Map<String, Usage> byDisplayName, Map<String, Double> costByDisplayName) {
        if (byDisplayName.isEmpty()) {
            return "Usage:                 0 input, 0 output, 0 cache read, 0 cache write";
        }
        StringBuilder sb = new StringBuilder("Usage by model:");
        for (Map.Entry<String, Usage> e : byDisplayName.entrySet()) {
            Usage u = e.getValue();
            String usageString =
                "  " + FormatUtils.formatNumber(u.inputTokens()) + " input, "
                + FormatUtils.formatNumber(u.outputTokens()) + " output, "
                + FormatUtils.formatNumber(u.cacheReadInputTokens()) + " cache read, "
                + FormatUtils.formatNumber(u.cacheCreationInputTokens()) + " cache write"
                + (u.webSearchRequests() > 0
                    ? ", " + FormatUtils.formatNumber(u.webSearchRequests()) + " web search"
                    : "")
                + " (" + FormatUtils.formatCost(costByDisplayName.getOrDefault(e.getKey(), 0.0)) + ")";

            String label = padStart(e.getKey() + ":", 21);
            sb.append('\n').append(label).append(usageString);
        }
        return sb.toString();
    }


    private static String padStart(String s, int width) {
        if (s.length() >= width) return s;
        return " ".repeat(width - s.length()) + s;
    }

}
