package com.claudecode.services.cost;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.engine.SessionCostState;
import com.claudecode.core.message.Usage;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.services.config.GlobalConfigStore;
import com.claudecode.services.config.TrustConfigStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;


public final class CostStatePersistence {
    private static final Logger LOG = LoggerFactory.getLogger(CostStatePersistence.class);
    private static final String LEGACY_KEY = "lastSessionCosts";

    private CostStatePersistence() {}

    public static void saveForSession(String sessionId, Path workingDirectory) {
        saveForSession(sessionId, workingDirectory, ClaudePaths.GLOBAL_JSON);
    }

    static void saveForSession(String sessionId, Path workingDirectory, Path configPath) {
        if (StringUtils.isBlank(sessionId) || workingDirectory == null) return;
        try {
            SessionCostState.Snapshot snap = SessionCostState.get().snapshot();
            String projectKey = TrustConfigStore.getProjectPathForConfig(workingDirectory);
            GlobalConfigStore.updateProjectEntry(configPath, projectKey, entry -> {
                entry.put("lastSessionId", sessionId);
                entry.put("lastCost", snap.totalCostUsd());
                entry.put("lastAPIDuration", snap.apiDurationMs());
                entry.put("lastAPIDurationWithoutRetries", snap.apiDurationWithoutRetriesMs());
                entry.put("lastToolDuration", snap.toolDurationMs());
                entry.put("lastDuration", snap.wallDurationMs());
                entry.put("lastLinesAdded", snap.linesAdded());
                entry.put("lastLinesRemoved", snap.linesRemoved());
                ObjectNode models = JsonUtils.getMapper().createObjectNode();
                for (Map.Entry<String, Usage> usage : snap.usageByModel().entrySet()) {
                    Usage value = usage.getValue();
                    ObjectNode modelUsage = JsonUtils.getMapper().createObjectNode();
                    modelUsage.put("inputTokens", value.inputTokens());
                    modelUsage.put("outputTokens", value.outputTokens());
                    modelUsage.put("cacheReadInputTokens", value.cacheReadInputTokens());
                    modelUsage.put("cacheCreationInputTokens", value.cacheCreationInputTokens());
                    modelUsage.put("webSearchRequests", value.webSearchRequests());
                    modelUsage.put("costUSD",
                        snap.costByModel().getOrDefault(usage.getKey(), 0.0));
                    models.set(usage.getKey(), modelUsage);
                }
                entry.set("lastModelUsage", models);
                return entry;
            });
        } catch (Exception e) {
            LOG.warn("Failed to persist session cost state: {}", e.getMessage());
        }
    }

    public static void restoreForSession(String sessionId, Path workingDirectory) {
        restoreForSession(sessionId, workingDirectory, ClaudePaths.GLOBAL_JSON);
    }

    public static SessionCostState.Snapshot readForSession(
            String sessionId, Path workingDirectory) {
        return readForSession(sessionId, workingDirectory, ClaudePaths.GLOBAL_JSON);
    }

    public static void restoreCaptured(SessionCostState.Snapshot snapshot) {
        SessionCostState state = SessionCostState.get();
        state.reset();
        if (snapshot != null) state.restore(snapshot);
    }

    static boolean restoreForSession(
            String sessionId, Path workingDirectory, Path configPath) {
        SessionCostState.Snapshot snapshot = readForSession(sessionId, workingDirectory, configPath);
        if (snapshot == null) return false;
        SessionCostState.get().restore(snapshot);
        return true;
    }

    static SessionCostState.Snapshot readForSession(
            String sessionId, Path workingDirectory, Path configPath) {
        if (StringUtils.isBlank(sessionId) || workingDirectory == null) return null;
        try {
            ObjectNode root = GlobalConfigStore.snapshot(configPath);
            String projectKey = TrustConfigStore.getProjectPathForConfig(workingDirectory);
            JsonNode projects = root.get("projects");
            JsonNode stored = projects != null && projects.isObject() ? projects.get(projectKey) : null;
            if (stored != null && stored.isObject()) {
                if (!sessionId.equals(stored.path("lastSessionId").asText(null))) return null;
                return releasedSnapshot(stored);
            }
            JsonNode legacy = root.get(LEGACY_KEY);
            if (legacy == null || !legacy.isObject()
                    || !sessionId.equals(legacy.path("sessionId").asText(null))) return null;
            return legacySnapshot(legacy);
        } catch (Exception e) {
            LOG.warn("Failed to restore session cost state: {}", e.getMessage());
            return null;
        }
    }

    private static SessionCostState.Snapshot releasedSnapshot(JsonNode stored) throws Exception {
        Map<String, Usage> usage = usageMap(stored.get("lastModelUsage"));
        Map<String, Double> costs = costMap(stored.get("lastModelUsage"));
        if (!stored.has("lastCost")) {
            return new SessionCostState.Snapshot(
                stored.path("lastAPIDuration").asLong(0),
                stored.has("lastAPIDurationWithoutRetries")
                    ? stored.path("lastAPIDurationWithoutRetries").asLong(0)
                    : stored.path("lastAPIDuration").asLong(0),
                stored.path("lastToolDuration").asLong(0),
                stored.path("lastDuration").asLong(0),
                stored.path("lastLinesAdded").asLong(0),
                stored.path("lastLinesRemoved").asLong(0), usage);
        }
        return new SessionCostState.Snapshot(
            stored.path("lastAPIDuration").asLong(0),
            stored.has("lastAPIDurationWithoutRetries")
                ? stored.path("lastAPIDurationWithoutRetries").asLong(0)
                : stored.path("lastAPIDuration").asLong(0),
            stored.path("lastToolDuration").asLong(0),
            stored.path("lastDuration").asLong(0),
            stored.path("lastLinesAdded").asLong(0),
            stored.path("lastLinesRemoved").asLong(0),
            usage, costs, stored.path("lastCost").asDouble(0.0));
    }

    private static SessionCostState.Snapshot legacySnapshot(JsonNode stored) throws Exception {
        return new SessionCostState.Snapshot(
            stored.path("apiDurationMs").asLong(0),
            stored.has("apiDurationWithoutRetriesMs")
                ? stored.path("apiDurationWithoutRetriesMs").asLong(0)
                : stored.path("apiDurationMs").asLong(0),
            stored.path("toolDurationMs").asLong(0),
            stored.path("wallDurationMs").asLong(0),
            stored.path("linesAdded").asLong(0),
            stored.path("linesRemoved").asLong(0),
            usageMap(stored.get("usageByModel")));
    }

    private static Map<String, Usage> usageMap(JsonNode models) throws Exception {
        Map<String, Usage> result = new LinkedHashMap<>();
        if (models == null || !models.isObject()) return result;
        var fields = models.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            JsonNode value = field.getValue();
            if (value.has("inputTokens") || value.has("costUSD")) {
                result.put(field.getKey(), new Usage(
                    value.path("inputTokens").asLong(0),
                    value.path("outputTokens").asLong(0),
                    value.path("cacheCreationInputTokens").asLong(0),
                    value.path("cacheReadInputTokens").asLong(0),
                    new Usage.ServerToolUse(value.path("webSearchRequests").asLong(0), 0)));
            } else {

                result.put(field.getKey(),
                    JsonUtils.getMapper().treeToValue(value, Usage.class));
            }
        }
        return result;
    }

    private static Map<String, Double> costMap(JsonNode models) {
        Map<String, Double> result = new LinkedHashMap<>();
        if (models == null || !models.isObject()) return result;
        var fields = models.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (field.getValue().has("costUSD")) {
                result.put(field.getKey(), field.getValue().path("costUSD").asDouble(0.0));
            }
        }
        return result;
    }
}
