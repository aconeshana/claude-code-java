package com.claudecode.ui.lanterna.statusline;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.message.Usage;
import com.claudecode.core.metrics.SessionMetricsFormat;
import com.claudecode.core.metrics.SessionMetricsSnapshot;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
/**
 * The JSON payload piped to the user's status-line command on stdin — the Java match of established
 * behavior {@code StatusLineCommandInput} (the shape documented in the {@code statusline-setup}
 * agent prompt and built by {@code buildStatusLineCommandInput} in ).
 */
public record StatusLineInput(
    String sessionId,
    String sessionName,          // nullable — only when a custom title is set (/rename)
    String transcriptPath,
    String cwd,
    String modelId,
    String modelDisplayName,
    String projectDir,
    List<String> addedDirs,
    String version,
    String outputStyleName,
    double totalCostUsd,
    long totalDurationMs,
    long totalApiDurationMs,
    long totalLinesAdded,
    long totalLinesRemoved,
    long totalInputTokens,
    long totalOutputTokens,
    long contextWindowSize,
    Usage currentUsage,          // nullable — null before the first API response
    Integer usedPercentage,      // nullable — null when currentUsage is null
    Integer remainingPercentage, // nullable
    boolean exceeds200kTokens,
    String vimMode,              // nullable — only when vim mode is enabled
    @Explanation("Extends statusLine JSON with optional white-box session metrics")
    SessionMetricsSnapshot sessionMetrics // nullable/incomplete → omitted
) {

    public static Builder builder(String sessionId, String cwd) {
        return new Builder(sessionId, cwd);
    }

    /** Named construction for the optional status-line protocol sections. */
    public static final class Builder {
        private final String sessionId;
        private final String cwd;
        private String sessionName;
        private String transcriptPath = "";
        private String modelId = "";
        private String modelDisplayName = "";
        private String projectDir = "";
        private List<String> addedDirs = List.of();
        private String version = "";
        private String outputStyleName = "default";
        private double totalCostUsd;
        private long totalDurationMs;
        private long totalApiDurationMs;
        private long totalLinesAdded;
        private long totalLinesRemoved;
        private long totalInputTokens;
        private long totalOutputTokens;
        private long contextWindowSize;
        private Usage currentUsage;
        private Integer usedPercentage;
        private Integer remainingPercentage;
        private boolean exceeds200kTokens;
        private String vimMode;
        private SessionMetricsSnapshot sessionMetrics;

        private Builder(String sessionId, String cwd) {
            this.sessionId = sessionId;
            this.cwd = cwd;
        }

        public Builder sessionName(String value) { sessionName = value; return this; }
        public Builder transcriptPath(String value) { transcriptPath = value; return this; }
        public Builder model(String id, String displayName) { modelId = id; modelDisplayName = displayName; return this; }
        public Builder projectDir(String value) { projectDir = value; return this; }
        public Builder addedDirs(List<String> value) { addedDirs = value; return this; }
        public Builder version(String value) { version = value; return this; }
        public Builder outputStyleName(String value) { outputStyleName = value; return this; }
        public Builder totalCostUsd(double value) { totalCostUsd = value; return this; }
        public Builder durations(long total, long api) { totalDurationMs = total; totalApiDurationMs = api; return this; }
        public Builder lineChanges(long added, long removed) { totalLinesAdded = added; totalLinesRemoved = removed; return this; }
        public Builder tokenTotals(long input, long output) { totalInputTokens = input; totalOutputTokens = output; return this; }
        public Builder contextWindow(long size, Usage usage, Integer used, Integer remaining) {
            contextWindowSize = size;
            currentUsage = usage;
            usedPercentage = used;
            remainingPercentage = remaining;
            return this;
        }
        public Builder exceeds200kTokens(boolean value) { exceeds200kTokens = value; return this; }
        public Builder vimMode(String value) { vimMode = value; return this; }
        public Builder sessionMetrics(SessionMetricsSnapshot value) { sessionMetrics = value; return this; }

        public StatusLineInput build() {
            return new StatusLineInput(sessionId, sessionName, transcriptPath,
                cwd, modelId, modelDisplayName, projectDir, addedDirs, version,
                outputStyleName, totalCostUsd, totalDurationMs,
                totalApiDurationMs, totalLinesAdded, totalLinesRemoved,
                totalInputTokens, totalOutputTokens, contextWindowSize,
                currentUsage, usedPercentage, remainingPercentage,
                exceeds200kTokens, vimMode, sessionMetrics);
        }
    }


    public String toJson() {
        ObjectNode root = JsonUtils.getMapper().createObjectNode();

        // createBaseHookInput fields
        root.put("session_id", sessionId);
        root.put("transcript_path", transcriptPath);
        root.put("cwd", cwd);

        if (StringUtils.isNotBlank(sessionName)) {
            root.put("session_name", sessionName);
        }

        ObjectNode model = root.putObject("model");
        model.put("id", modelId);
        model.put("display_name", modelDisplayName);

        ObjectNode workspace = root.putObject("workspace");
        workspace.put("current_dir", cwd);
        workspace.put("project_dir", projectDir);
        var added = workspace.putArray("added_dirs");
        for (String d : addedDirs) added.add(d);

        root.put("version", version);

        root.putObject("output_style").put("name", outputStyleName);

        ObjectNode cost = root.putObject("cost");
        cost.put("total_cost_usd", totalCostUsd);
        cost.put("total_duration_ms", totalDurationMs);
        cost.put("total_api_duration_ms", totalApiDurationMs);
        cost.put("total_lines_added", totalLinesAdded);
        cost.put("total_lines_removed", totalLinesRemoved);

        ObjectNode ctx = root.putObject("context_window");
        ctx.put("total_input_tokens", totalInputTokens);
        ctx.put("total_output_tokens", totalOutputTokens);
        ctx.put("context_window_size", contextWindowSize);
        if (currentUsage != null) {
            ObjectNode cu = ctx.putObject("current_usage");
            cu.put("input_tokens", currentUsage.inputTokens());
            cu.put("output_tokens", currentUsage.outputTokens());
            cu.put("cache_creation_input_tokens", currentUsage.cacheCreationInputTokens());
            cu.put("cache_read_input_tokens", currentUsage.cacheReadInputTokens());
        } else {
            ctx.putNull("current_usage");
        }
        if (usedPercentage != null) ctx.put("used_percentage", usedPercentage);
        else ctx.putNull("used_percentage");
        if (remainingPercentage != null) ctx.put("remaining_percentage", remainingPercentage);
        else ctx.putNull("remaining_percentage");

        root.put("exceeds_200k_tokens", exceeds200kTokens);

        if (StringUtils.isNotBlank(vimMode)) {
            root.putObject("vim").put("mode", vimMode);
        }

        if (sessionMetrics != null && sessionMetrics.complete()) {
            ObjectNode metrics = root.putObject("session_metrics");
            metrics.put("coverage", "complete");
            metrics.put("turns", sessionMetrics.turns());
            metrics.put("steps", sessionMetrics.steps());
            metrics.put("llm_ms", sessionMetrics.llmMs());
            metrics.put("tool_ms", sessionMetrics.toolMs());
            metrics.put("ttft_ms", sessionMetrics.ttftMs());
            metrics.put("ttft_steps", sessionMetrics.ttftSteps());
            metrics.put("decode_ms", sessionMetrics.decodeMs());
            metrics.put("decode_tokens", sessionMetrics.decodeTokens());
            metrics.put("uncached_input_tokens", sessionMetrics.uncachedInputTokens());
            metrics.put("output_tokens", sessionMetrics.outputTokens());
            metrics.put("cache_write_tokens", sessionMetrics.cacheWriteTokens());
            metrics.put("cache_read_tokens", sessionMetrics.cacheReadTokens());
            metrics.put("billed_input_tokens", sessionMetrics.billedInputTokens());
            if (sessionMetrics.ttftAverageMs() != null) {
                metrics.put("ttft_average_ms", sessionMetrics.ttftAverageMs());
            } else metrics.putNull("ttft_average_ms");
            if (sessionMetrics.tokensPerSecond() != null) {
                metrics.put("tokens_per_second", sessionMetrics.tokensPerSecond());
            } else metrics.putNull("tokens_per_second");
            String cacheHit = SessionMetricsFormat.cacheHitPercent(sessionMetrics);
            if (cacheHit != null) metrics.put("cache_hit_percent", cacheHit);
            else metrics.putNull("cache_hit_percent");
        }

        try {
            return JsonUtils.getMapper().writeValueAsString(root);
        } catch (Exception _) {
            // ObjectNode → string never realistically fails; fall back to a
            // minimal payload so the command still receives valid JSON.
            return "{\"session_id\":\"" + sessionId + "\"}";
        }
    }
}
