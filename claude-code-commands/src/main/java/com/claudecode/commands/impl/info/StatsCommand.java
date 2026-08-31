package com.claudecode.commands.impl.info;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.core.model.ModelNames;
import com.claudecode.commands.session.SessionCommandPort;
import com.claudecode.core.text.FormatUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * /stats — cross-session usage statistics and activity.
 */
@SlashCommand(
    name = "stats",
    description = "Show your Claude Code usage statistics and activity"
)
public class StatsCommand implements AnnotatedCommand {

    public StatsCommand() { }

    @Override
    public CommandResult execute(CommandContext context, String args) {
// Interactive path: open the Stats panel.
        if (context.presentation().statsDialogLauncher() != null) {
            context.presentation().statsDialogLauncher().run();
            return CommandResult.skip();
        }
        // Headless fallback: text summary over the same aggregate.
        try {
            SessionCommandPort.StatsSnapshot stats = context.application().sessions().stats();
            if (stats.totalSessions() == 0) {
                return CommandResult.of("No stats available yet. Start using Claude Code!");
            }
            return CommandResult.of(renderText(stats));
        } catch (Exception e) {
            return CommandResult.of("Failed to load stats: "
                + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

/** Flat-text overview matching the dialog's Overview tab rows. */
    static String renderText(SessionCommandPort.StatsSnapshot stats) {
        List<Map.Entry<String, SessionCommandPort.ModelUsage>> models = stats.modelUsage().entrySet().stream()
            .sorted(Comparator.comparingLong((Map.Entry<String, SessionCommandPort.ModelUsage> e) ->
                e.getValue().inputTokens() + e.getValue().outputTokens()).reversed())
            .toList();
        long totalTokens = models.stream()
            .mapToLong(e -> e.getValue().inputTokens() + e.getValue().outputTokens()).sum();

        StringBuilder sb = new StringBuilder("Claude Code Stats (all time)\n\n");
        if (!models.isEmpty()) {
            sb.append("Favorite model:  ").append(ModelNames.displayName(models.getFirst().getKey())).append('\n');
        }
        sb.append("Total tokens:    ").append(FormatUtils.formatNumber(totalTokens)).append('\n');
        sb.append("Sessions:        ").append(FormatUtils.formatNumber(stats.totalSessions())).append('\n');
        if (stats.longestSession() != null) {
            sb.append("Longest session: ")
              .append(FormatUtils.formatDuration(stats.longestSession().duration())).append('\n');
        }
        sb.append("Active days:     ").append(stats.activeDays()).append('/').append(stats.totalDays()).append('\n');
        sb.append("Longest streak:  ").append(stats.streaks().longestStreak())
          .append(stats.streaks().longestStreak() == 1 ? " day" : " days").append('\n');
        sb.append("Current streak:  ").append(stats.streaks().currentStreak())
          .append(stats.streaks().currentStreak() == 1 ? " day" : " days").append('\n');
        if (stats.peakActivityDay() != null) {
            sb.append("Most active day: ").append(stats.peakActivityDay()).append('\n');
        }

        if (!models.isEmpty()) {
            sb.append("\nUsage by model:\n");
            for (Map.Entry<String, SessionCommandPort.ModelUsage> e : models) {
                SessionCommandPort.ModelUsage u = e.getValue();
                sb.append("  ").append(ModelNames.displayName(e.getKey()))
                  .append(": In: ").append(FormatUtils.formatNumber(u.inputTokens()))
                  .append(" · Out: ").append(FormatUtils.formatNumber(u.outputTokens()))
                  .append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }

}
