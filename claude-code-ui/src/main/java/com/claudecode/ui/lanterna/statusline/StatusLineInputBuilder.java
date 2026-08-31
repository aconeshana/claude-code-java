package com.claudecode.ui.lanterna.statusline;

import com.claudecode.commands.context.ContextUsageAnalyzer;
import com.claudecode.core.engine.CostCalculator;
import com.claudecode.core.engine.SessionCostState;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.TokenEstimator;
import com.claudecode.core.message.Usage;
import com.claudecode.core.model.ModelNames;
import com.claudecode.core.metrics.SessionMetricsSnapshot;

import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

public final class StatusLineInputBuilder {

    private static final long EXCEEDS_200K_THRESHOLD = 200_000;

    private StatusLineInputBuilder() {}

    /** Immutable bundle of the non-derived ingredients the caller supplies. */
    public record Ingredients(
        String sessionId,
        String sessionName,     // nullable — custom title (/rename)
        String transcriptPath,
        String cwd,
        String projectDir,
        List<String> addedDirs,
        String modelId,
        String outputStyleName, // nullable → "default"
        String vimMode,         // nullable
        String version,
        Long contextWindow,
        SessionMetricsSnapshot sessionMetrics
    ) {
        public Ingredients(String sessionId, String sessionName, String transcriptPath,
                           String cwd, String projectDir, List<String> addedDirs,
                           String modelId, String outputStyleName, String vimMode,
                           String version) {
            this(sessionId, sessionName, transcriptPath, cwd, projectDir, addedDirs,
                modelId, outputStyleName, vimMode, version, null, null);
        }

        public Ingredients(String sessionId, String sessionName, String transcriptPath,
                           String cwd, String projectDir, List<String> addedDirs,
                           String modelId, String outputStyleName, String vimMode,
                           String version, Long contextWindow) {
            this(sessionId, sessionName, transcriptPath, cwd, projectDir, addedDirs,
                modelId, outputStyleName, vimMode, version, contextWindow, null);
        }
    }

    /**
     * Builds the payload. {@code messages} drives current-usage + the 200k
     * check; {@link SessionCostState#get} supplies the cumulative cost data.
     */
    public static StatusLineInput build(Ingredients in, List<Message> messages) {
        SessionCostState state = SessionCostState.get();

        // Cumulative totals + total cost (re-priced per model like /cost).
        long totalInput = 0, totalOutput = 0;
        double totalCost = 0.0;
        for (Map.Entry<String, Usage> e : state.usageByModel().entrySet()) {
            Usage u = e.getValue();
            totalInput += u.inputTokens();
            totalOutput += u.outputTokens();
            totalCost += CostCalculator.forModel(e.getKey()).calculateCost(u);
        }

        long contextWindow = in.contextWindow() != null && in.contextWindow() > 0
            ? in.contextWindow() : ContextUsageAnalyzer.contextWindowFor(in.modelId());
        TokenEstimator.UsageSnapshot currentSnapshot =
            TokenEstimator.latestFinalizedUsageSnapshot(messages);
        Usage current = currentSnapshot != null ? currentSnapshot.usage() : null;
        String usageModel = currentSnapshot != null
                && currentSnapshot.model() != null
                && !StringUtils.isBlank(currentSnapshot.model())
            ? currentSnapshot.model() : in.modelId();

        Integer usedPct = null, remainingPct = null;
        if (current != null && contextWindow > 0) {

            // OpenAI semantics where cached_tokens is a subset of input_tokens.
            long totalInputTokens = TokenEstimator.contextInputTokens(current, usageModel);
            int used = (int) Math.round(totalInputTokens * 100.0 / contextWindow);
            used = Math.min(100, Math.max(0, used));
            usedPct = used;
            remainingPct = 100 - used;
        }

        boolean exceeds200k = exceeds200k(current, usageModel);

        String outputStyle = (StringUtils.isNotBlank(in.outputStyleName()))
            ? in.outputStyleName() : "default";

        return StatusLineInput.builder(in.sessionId(), in.cwd())
            .sessionName(in.sessionName())
            .transcriptPath(in.transcriptPath())
            .model(in.modelId(), ModelNames.displayName(in.modelId()))
            .projectDir(in.projectDir())
            .addedDirs(in.addedDirs())
            .version(in.version())
            .outputStyleName(outputStyle)
            .totalCostUsd(totalCost)
            .durations(state.wallDurationMs(), state.apiDurationMs())
            .lineChanges(state.totalLinesAdded(), state.totalLinesRemoved())
            .tokenTotals(totalInput, totalOutput)
            .contextWindow(contextWindow, current, usedPct, remainingPct)
            .exceeds200kTokens(exceeds200k)
            .vimMode(in.vimMode())
            .sessionMetrics(in.sessionMetrics())
            .build();
    }


    private static boolean exceeds200k(Usage current, String model) {
        if (current == null) return false;
        return TokenEstimator.contextTokens(current, model) > EXCEEDS_200K_THRESHOLD;
    }
}
