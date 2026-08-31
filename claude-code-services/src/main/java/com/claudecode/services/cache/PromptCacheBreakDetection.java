package com.claudecode.services.cache;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.serialization.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;


public final class PromptCacheBreakDetection {

    private static final Logger log = LoggerFactory.getLogger(PromptCacheBreakDetection.class);

    /** Cap tracked sources to avoid unbounded memory growth (subagents spawn unique keys). */
    private static final int MAX_TRACKED_SOURCES = 10;
    /** Minimum absolute token drop required to trigger a cache-break warning. */
    private static final long MIN_CACHE_MISS_TOKENS = 2_000;
    private static final long CACHE_TTL_5MIN_MS = 5L * 60 * 1000;
/**
     * Package-external visibility: also drives {@code LlmClientAdapter}'s thinking-clear latch.
     */
    public static final long CACHE_TTL_1HOUR_MS = 60L * 60 * 1000;


    private static final List<String> TRACKED_SOURCE_PREFIXES = List.of(
        "repl_main_thread", "sdk", "agent:custom", "agent:default", "agent:builtin");

    private static final Map<String, PreviousState> previousStateBySource = new ConcurrentHashMap<>();

    private PromptCacheBreakDetection() {}

    // ---- snapshot types ----

    /** cacheControlSignature contains the type plus cache-key-affecting TTL/scope fields. */
    public record SystemBlock(String text, String cacheControlSignature) {}
    public record ToolSchema(String name, String description, String inputSchemaJson) {}

    public record PromptStateSnapshot(
        List<SystemBlock> system,
        List<ToolSchema> toolSchemas,
        String querySource,
        String model,
        String agentId,
        Boolean fastMode,
        String globalCacheStrategy,
        List<String> betas,
        Boolean autoModeActive,
        Boolean isUsingOverage,
        Boolean cachedMCEnabled,
        String effortValue,
        Object extraBodyParams
    ) {}

    public record PendingChanges(
        boolean systemPromptChanged,
        boolean toolSchemasChanged,
        boolean modelChanged,
        boolean fastModeChanged,
        boolean cacheControlChanged,
        boolean globalCacheStrategyChanged,
        boolean betasChanged,
        boolean autoModeChanged,
        boolean overageChanged,
        boolean cachedMCChanged,
        boolean effortChanged,
        boolean extraBodyChanged,
        int addedToolCount,
        int removedToolCount,
        String systemCharDelta,
        List<String> addedTools,
        List<String> removedTools,
        List<String> changedToolSchemas,
        String previousModel,
        String newModel,
        String prevGlobalCacheStrategy,
        String newGlobalCacheStrategy,
        List<String> addedBetas,
        List<String> removedBetas,
        String prevEffortValue,
        String newEffortValue
    ) {}

    public record CacheBreakResult(
        boolean broke,
        String reason,
        long prevCacheRead,
        long cacheRead,
        long cacheCreation,
        int callNumber
    ) {}

    // ---- mutable per-source state ----

    private static final class PreviousState {
        int systemHash;
        int toolsHash;
        int cacheControlHash;
        List<String> toolNames;
        int systemCharCount;
        String model;
        boolean fastMode;
        String globalCacheStrategy;
        List<String> betas;
        boolean autoModeActive;
        boolean isUsingOverage;
        boolean cachedMCEnabled;
        String effortValue;
        int extraBodyHash;
        int callCount;
        PendingChanges pendingChanges;
        Long prevCacheReadTokens;
        boolean cacheDeletionsPending;
        Map<String, Integer> perToolHashes;
    }

    // ---- phase 1: record state before the call ----

    public static void recordPromptState(PromptStateSnapshot snapshot) {
        try {
            String key = getTrackingKey(snapshot.querySource(), snapshot.agentId());
            if (key == null) return;

            List<SystemBlock> system = snapshot.system() == null ? List.of() : snapshot.system();
            List<ToolSchema> tools = snapshot.toolSchemas() == null ? List.of() : snapshot.toolSchemas();
            boolean fastMode = Boolean.TRUE.equals(snapshot.fastMode());
            List<String> betas = snapshot.betas() == null ? new ArrayList<>() : new ArrayList<>(snapshot.betas());
            betas.sort(null);
            String effort = snapshot.effortValue() == null ? "" : snapshot.effortValue();

            int systemHash = computeHash(stripCacheControlFromSystem(system));
            int toolsHash = computeHash(stripCacheControlFromTools(tools));
            int cacheControlHash = computeHash(cacheControlTypes(system));
            List<String> toolNames = new ArrayList<>();
            for (ToolSchema t : tools) toolNames.add(t.name() == null ? "unknown" : t.name());
            int systemCharCount = system.stream().mapToInt(b -> b.text() == null ? 0 : b.text().length()).sum();
            Map<String, Integer> perToolHashes = computePerToolHashes(tools, toolNames);
            int extraBodyHash = snapshot.extraBodyParams() == null ? 0 : computeHash(snapshot.extraBodyParams());

            PreviousState prev = previousStateBySource.get(key);

            if (prev == null) {
                // Evict oldest entries if at capacity.
                while (previousStateBySource.size() >= MAX_TRACKED_SOURCES) {
                    String oldest = previousStateBySource.keySet().iterator().next();
                    if (oldest != null) previousStateBySource.remove(oldest);
                }
                PreviousState s = new PreviousState();
                s.systemHash = systemHash;
                s.toolsHash = toolsHash;
                s.cacheControlHash = cacheControlHash;
                s.toolNames = toolNames;
                s.systemCharCount = systemCharCount;
                s.model = snapshot.model();
                s.fastMode = fastMode;
                s.globalCacheStrategy = orEmpty(snapshot.globalCacheStrategy());
                s.betas = betas;
                s.autoModeActive = Boolean.TRUE.equals(snapshot.autoModeActive());
                s.isUsingOverage = Boolean.TRUE.equals(snapshot.isUsingOverage());
                s.cachedMCEnabled = Boolean.TRUE.equals(snapshot.cachedMCEnabled());
                s.effortValue = effort;
                s.extraBodyHash = extraBodyHash;
                s.callCount = 1;
                s.pendingChanges = null;
                s.prevCacheReadTokens = null;
                s.cacheDeletionsPending = false;
                s.perToolHashes = perToolHashes;
                previousStateBySource.put(key, s);
                return;
            }

            prev.callCount++;

            boolean systemPromptChanged = systemHash != prev.systemHash;
            boolean toolSchemasChanged = toolsHash != prev.toolsHash;
            boolean modelChanged = !Objects.equals(snapshot.model(), prev.model);
            boolean fastModeChanged = fastMode != prev.fastMode;
            boolean cacheControlChanged = cacheControlHash != prev.cacheControlHash;
            boolean globalCacheStrategyChanged = !orEmpty(snapshot.globalCacheStrategy()).equals(prev.globalCacheStrategy);
            boolean betasChanged = !betas.equals(prev.betas);
            boolean autoModeChanged = Boolean.TRUE.equals(snapshot.autoModeActive()) != prev.autoModeActive;
            boolean overageChanged = Boolean.TRUE.equals(snapshot.isUsingOverage()) != prev.isUsingOverage;
            boolean cachedMCChanged = Boolean.TRUE.equals(snapshot.cachedMCEnabled()) != prev.cachedMCEnabled;
            boolean effortChanged = !effort.equals(prev.effortValue);
            boolean extraBodyChanged = extraBodyHash != prev.extraBodyHash;

            if (systemPromptChanged || toolSchemasChanged || modelChanged || fastModeChanged
                    || cacheControlChanged || globalCacheStrategyChanged || betasChanged
                    || autoModeChanged || overageChanged || cachedMCChanged || effortChanged || extraBodyChanged) {
                List<String> prevToolSet = prev.toolNames;
                List<String> addedTools = new ArrayList<>(toolNames);
                addedTools.removeAll(prevToolSet);
                List<String> removedTools = new ArrayList<>(prevToolSet);
                removedTools.removeAll(toolNames);
                List<String> changedToolSchemas = new ArrayList<>();
                if (toolSchemasChanged) {
                    for (String name : toolNames) {
                        if (!prevToolSet.contains(name)) continue;
                        Integer prevH = prev.perToolHashes == null ? null : prev.perToolHashes.get(name);
                        Integer newH = perToolHashes.get(name);
                        if (!Objects.equals(newH, prevH)) changedToolSchemas.add(name);
                    }
                    prev.perToolHashes = perToolHashes;
                }
                List<String> addedBetas = new ArrayList<>(betas);
                addedBetas.removeAll(prev.betas);
                List<String> removedBetas = new ArrayList<>(prev.betas);
                removedBetas.removeAll(betas);
                prev.pendingChanges = new PendingChanges(
                    systemPromptChanged, toolSchemasChanged, modelChanged, fastModeChanged, cacheControlChanged,
                    globalCacheStrategyChanged, betasChanged, autoModeChanged, overageChanged, cachedMCChanged,
                    effortChanged, extraBodyChanged,
                    addedTools.size(), removedTools.size(),
                    diffChars(systemCharCount, prev.systemCharCount),
                    addedTools, removedTools, changedToolSchemas,
                    prev.model, snapshot.model(),
                    prev.globalCacheStrategy, orEmpty(snapshot.globalCacheStrategy),
                    addedBetas, removedBetas,
                    prev.effortValue, effort
                );
            } else {
                prev.pendingChanges = null;
            }

            prev.systemHash = systemHash;
            prev.toolsHash = toolsHash;
            prev.cacheControlHash = cacheControlHash;
            prev.toolNames = toolNames;
            prev.systemCharCount = systemCharCount;
            prev.model = snapshot.model();
            prev.fastMode = fastMode;
            prev.globalCacheStrategy = orEmpty(snapshot.globalCacheStrategy());
            prev.betas = betas;
            prev.autoModeActive = Boolean.TRUE.equals(snapshot.autoModeActive());
            prev.isUsingOverage = Boolean.TRUE.equals(snapshot.isUsingOverage());
            prev.cachedMCEnabled = Boolean.TRUE.equals(snapshot.cachedMCEnabled());
            prev.effortValue = effort;
            prev.extraBodyHash = extraBodyHash;
        } catch (Exception e) {
            log.debug("recordPromptState failed: {}", e.toString());
        }
    }

    // ---- phase 2: inspect the response for a cache break ----

    public static CacheBreakResult checkResponseForCacheBreak(
            String querySource, long cacheReadTokens, long cacheCreationTokens,
            Long timeSinceLastAssistantMs, String agentId) {
        try {
            String key = getTrackingKey(querySource, agentId);
            if (key == null) return new CacheBreakResult(false, null, 0, cacheReadTokens, cacheCreationTokens, 0);
            PreviousState state = previousStateBySource.get(key);
            if (state == null) return new CacheBreakResult(false, null, 0, cacheReadTokens, cacheCreationTokens, 0);
            if (isExcludedModel(state.model)) {
                return new CacheBreakResult(false, null, 0, cacheReadTokens, cacheCreationTokens, state.callCount);
            }

            Long prevCacheRead = state.prevCacheReadTokens;
            state.prevCacheReadTokens = cacheReadTokens;

            // First call with a previous value to compare against.
            if (prevCacheRead == null) {
                return new CacheBreakResult(false, null, 0, cacheReadTokens, cacheCreationTokens, state.callCount);
            }

            PendingChanges changes = state.pendingChanges;

            // Cached microcompact deletions intentionally reduce the cached prefix;
            // the drop is expected, not a break.
            if (state.cacheDeletionsPending) {
                state.cacheDeletionsPending = false;
                state.pendingChanges = null;
                return new CacheBreakResult(false, "cache deletion applied (expected drop)",
                    prevCacheRead, cacheReadTokens, cacheCreationTokens, state.callCount);
            }

            long tokenDrop = prevCacheRead - cacheReadTokens;
            if (cacheReadTokens >= prevCacheRead * 0.95 || tokenDrop < MIN_CACHE_MISS_TOKENS) {
                state.pendingChanges = null;
                return new CacheBreakResult(false, null, prevCacheRead, cacheReadTokens, cacheCreationTokens, state.callCount);
            }

            List<String> parts = new ArrayList<>();
            if (changes != null) {
                if (changes.modelChanged()) {
                    parts.add("model changed (" + changes.previousModel() + " → " + changes.newModel() + ")");
                }
                if (changes.systemPromptChanged()) {
                    parts.add("system prompt changed" + changes.systemCharDelta());
                }
                if (changes.toolSchemasChanged()) {
                    String toolDiff = (changes.addedToolCount() > 0 || changes.removedToolCount() > 0)
                        ? " (+" + changes.addedToolCount() + "/-" + changes.removedToolCount() + " tools)"
                        : " (tool prompt/schema changed, same tool set)";
                    parts.add("tools changed" + toolDiff);
                }
                if (changes.fastModeChanged()) parts.add("fast mode toggled");
                if (changes.globalCacheStrategyChanged()) {
                    parts.add("global cache strategy changed ("
                        + (changes.prevGlobalCacheStrategy().isEmpty() ? "none" : changes.prevGlobalCacheStrategy())
                        + " → "
                        + (changes.newGlobalCacheStrategy().isEmpty() ? "none" : changes.newGlobalCacheStrategy()) + ")");
                }
                if (changes.cacheControlChanged() && !changes.globalCacheStrategyChanged() && !changes.systemPromptChanged()) {
                    parts.add("cache_control changed (scope or TTL)");
                }
                if (changes.betasChanged()) {
                    String added = changes.addedBetas().isEmpty() ? "" : "+" + String.join(",", changes.addedBetas());
                    String removed = changes.removedBetas().isEmpty() ? "" : "-" + String.join(",", changes.removedBetas());
                    String diff = (added + " " + removed).trim();
                    parts.add("betas changed" + (diff.isEmpty() ? "" : " (" + diff + ")"));
                }
                if (changes.autoModeChanged()) parts.add("auto mode toggled");
                if (changes.overageChanged()) parts.add("overage state changed (TTL latched, no flip)");
                if (changes.cachedMCChanged()) parts.add("cached microcompact toggled");
                if (changes.effortChanged()) {
                    parts.add("effort changed ("
                        + (changes.prevEffortValue().isEmpty() ? "default" : changes.prevEffortValue()) + " → "
                        + (changes.newEffortValue().isEmpty() ? "default" : changes.newEffortValue()) + ")");
                }
                if (changes.extraBodyChanged()) parts.add("extra body params changed");
            }

            boolean over5min = timeSinceLastAssistantMs != null && timeSinceLastAssistantMs > CACHE_TTL_5MIN_MS;
            boolean over1h = timeSinceLastAssistantMs != null && timeSinceLastAssistantMs > CACHE_TTL_1HOUR_MS;

            String reason;
            if (!parts.isEmpty()) {
                reason = String.join(", ", parts);
            } else if (over1h) {
                reason = "possible 1h TTL expiry (prompt unchanged)";
            } else if (over5min) {
                reason = "possible 5min TTL expiry (prompt unchanged)";
            } else if (timeSinceLastAssistantMs != null) {
                reason = "likely server-side (prompt unchanged, <5min gap)";
            } else {
                reason = "unknown cause";
            }

            String summary = String.format(
                "[PROMPT CACHE BREAK] %s [source=%s, call #%d, cache read: %d → %d, creation: %d]",
                reason, querySource, state.callCount, prevCacheRead, cacheReadTokens, cacheCreationTokens);

            log.warn(summary);

            state.pendingChanges = null;
            return new CacheBreakResult(true, reason, prevCacheRead, cacheReadTokens, cacheCreationTokens, state.callCount);
        } catch (Exception e) {
            log.debug("checkResponseForCacheBreak failed: {}", e.toString());
            return new CacheBreakResult(false, null, 0, cacheReadTokens, cacheCreationTokens, 0);
        }
    }



    /** Call when cached microcompact sends cache_edits deletions — the next response's
     *  lower cache-read tokens are expected, not a break. */
    public static void notifyCacheDeletion(String querySource, String agentId) {
        String key = getTrackingKey(querySource, agentId);
        PreviousState s = key == null ? null : previousStateBySource.get(key);
        if (s != null) s.cacheDeletionsPending = true;
    }

    /** Call after compaction to reset the cache-read baseline. */
    public static void notifyCompaction(String querySource, String agentId) {
        String key = getTrackingKey(querySource, agentId);
        PreviousState s = key == null ? null : previousStateBySource.get(key);
        if (s != null) s.prevCacheReadTokens = null;
    }

    public static void cleanupAgentTracking(String agentId) {
        if (agentId != null) previousStateBySource.remove(agentId);
    }

    public static void resetPromptCacheBreakDetection() {
        previousStateBySource.clear();
    }

    // ---- helpers ----

    private static String getTrackingKey(String source, String agentId) {
        if (source == null) return null;
        if (Strings.CS.equals("compact", source)) return "repl_main_thread";
        for (String prefix : TRACKED_SOURCE_PREFIXES) {
            if (Strings.CS.startsWith(source, prefix)) return agentId != null ? agentId : source;
        }
        return null;
    }

    private static boolean isExcludedModel(String model) {
        return model != null && Strings.CS.contains(model, "haiku");
    }

    private static int computeHash(Object data) {
        try {
            return djb2(JsonUtils.getMapper().writeValueAsString(data));
        } catch (Exception _) {
            return djb2(String.valueOf(data));
        }
    }

    private static int djb2(String s) {
        int h = 5381;
        for (int i = 0; i < s.length(); i++) {
            h = (h * 33 + s.charAt(i));
        }
        return h;
    }

    private static List<String> stripCacheControlFromSystem(List<SystemBlock> system) {
        List<String> out = new ArrayList<>();
        for (SystemBlock b : system) out.add(b.text() == null ? "" : b.text());
        return out;
    }

    private static List<Object> stripCacheControlFromTools(List<ToolSchema> tools) {
        List<Object> out = new ArrayList<>();
        for (ToolSchema t : tools) {
            Map<String, Object> m = new HashMap<>();
            m.put("name", t.name());
            m.put("description", t.description());
            m.put("input_schema", t.inputSchemaJson());
            out.add(m);
        }
        return out;
    }

    private static List<String> cacheControlTypes(List<SystemBlock> system) {
        List<String> out = new ArrayList<>();
        for (SystemBlock b : system) {
            out.add(b.cacheControlSignature() == null ? "" : b.cacheControlSignature());
        }
        return out;
    }

    private static Map<String, Integer> computePerToolHashes(List<ToolSchema> tools, List<String> names) {
        Map<String, Integer> hashes = new HashMap<>();
        for (int i = 0; i < tools.size(); i++) {
            String name = i < names.size() ? names.get(i) : ("__idx_" + i);
            hashes.put(name, computeHash(tools.get(i)));
        }
        return hashes;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String diffChars(int now, int prev) {
        int d = now - prev;
        if (d == 0) return "";
        return d > 0 ? " (+" + d + " chars)" : " (" + d + " chars)";
    }
}
