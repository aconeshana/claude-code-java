package com.claudecode.services.cost;

import com.claudecode.core.engine.SessionCostState;
import com.claudecode.core.message.Usage;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.services.config.TrustConfigStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * the
 * mechanism that keeps {@code /cost} intact after a resume. Uses a temp config
 * path so the real  is never touched.
 */
class CostStatePersistenceTest {

    @TempDir Path tmp;
    private Path config;

    @BeforeEach
    void setUp() {
        config = tmp.resolve(".claude.json");
        SessionCostState.get().reset();
    }

    @Test
    void saveThenRestore_matchingSessionId_restoresAllFields() throws Exception {
        SessionCostState s = SessionCostState.get();
        s.recordApiRequest("claude-opus-4-8", new Usage(1000, 200, 50, 25,
            new Usage.ServerToolUse(3, 0)), 4200, 2700);
        s.recordToolDuration(900);
        s.recordLinesChanged(40, 9);
        long wallBefore = s.wallDurationMs();
        double costBefore = s.totalCostUsd();

        CostStatePersistence.saveForSession("sess-A", tmp.resolve("project-a"), config);

        String projectKey = TrustConfigStore.getProjectPathForConfig(tmp.resolve("project-a"));
        var stored = JsonUtils.readJson(config).path("projects").path(projectKey);
        assertEquals(costBefore, stored.path("lastCost").asDouble());
        assertEquals(costBefore,
            stored.path("lastModelUsage").path("claude-opus-4-8").path("costUSD").asDouble());
        assertEquals(1000,
            stored.path("lastModelUsage").path("claude-opus-4-8").path("inputTokens").asLong());

        // Simulate a fresh process / switched-away session: wipe in-memory state.
        s.reset();
        assertTrue(s.isEmpty());

        assertTrue(CostStatePersistence.restoreForSession(
            "sess-A", tmp.resolve("project-a"), config));

        assertEquals(4200, s.apiDurationMs());
        assertEquals(2700, s.apiDurationWithoutRetriesMs());
        assertEquals(900, s.toolDurationMs());
        assertEquals(40, s.totalLinesAdded());
        assertEquals(9, s.totalLinesRemoved());
        Usage restored = s.usageByModel().get("claude-opus-4-8");
        assertNotNull(restored);
        assertEquals(1000, restored.inputTokens());
        assertEquals(3, restored.webSearchRequests());
        assertEquals(Double.doubleToLongBits(costBefore),
            Double.doubleToLongBits(s.costByModel().get("claude-opus-4-8")));
        assertEquals(Double.doubleToLongBits(costBefore),
            Double.doubleToLongBits(s.totalCostUsd()));

        assertTrue(s.wallDurationMs() >= wallBefore - 1000,
            "wall duration should resume near the saved value");
    }

    @Test
    void restore_differentSessionId_isNoOp() {
        SessionCostState s = SessionCostState.get();
        s.recordApiRequest("m", new Usage(1, 1, 0, 0), 100);
        CostStatePersistence.saveForSession("sess-A", tmp.resolve("project-a"), config);
        s.reset();


        assertFalse(CostStatePersistence.restoreForSession(
            "sess-B", tmp.resolve("project-a"), config));
        assertTrue(s.isEmpty());
    }

    @Test
    void restoreReadsReleasedPerModelAndTotalCostsWithoutRepricing() throws Exception {
        Path project = tmp.resolve("project-a");
        String projectKey = TrustConfigStore.getProjectPathForConfig(project);
        Files.writeString(config, """
            {
              "projects": {
                "%s": {
                  "lastSessionId": "sess-A",
                  "lastCost": 0.654321,
                  "lastAPIDuration": 100,
                  "lastDuration": 200,
                  "lastModelUsage": {
                    "custom-model": {
                      "inputTokens": 1,
                      "outputTokens": 0,
                      "cacheReadInputTokens": 0,
                      "cacheCreationInputTokens": 0,
                      "webSearchRequests": 0,
                      "costUSD": 0.123456
                    }
                  }
                }
              }
            }
            """.formatted(projectKey.replace("\\", "\\\\")));

        assertTrue(CostStatePersistence.restoreForSession("sess-A", project, config));

        SessionCostState state = SessionCostState.get();
        assertEquals(1, state.usageByModel().get("custom-model").inputTokens());
        assertEquals(0.123456, state.costByModel().get("custom-model"));
        assertEquals(0.654321, state.totalCostUsd());
    }

    @Test
    void restore_noStoredState_isNoOp() {
        assertFalse(CostStatePersistence.restoreForSession(
            "anything", tmp.resolve("project-a"), config));
    }

    @Test
    void save_blankSessionId_writesNothing() {
        SessionCostState.get().recordApiRequest("m", new Usage(1, 1, 0, 0), 100);
        CostStatePersistence.saveForSession("", tmp.resolve("project-a"), config);
        assertFalse(CostStatePersistence.restoreForSession(
            "", tmp.resolve("project-a"), config));
    }

    @Test
    void save_overwritesPreviousSession() {
        SessionCostState s = SessionCostState.get();
        s.recordApiRequest("m", new Usage(1, 0, 0, 0), 100);
        CostStatePersistence.saveForSession("sess-A", tmp.resolve("project-a"), config);
        s.reset();
        s.recordApiRequest("m", new Usage(9, 0, 0, 0), 500);
        CostStatePersistence.saveForSession("sess-B", tmp.resolve("project-a"), config);
        s.reset();

        // Only the last saved session (B) restores; A is gone.
        assertFalse(CostStatePersistence.restoreForSession(
            "sess-A", tmp.resolve("project-a"), config));
        assertTrue(CostStatePersistence.restoreForSession(
            "sess-B", tmp.resolve("project-a"), config));
        assertEquals(500, s.apiDurationMs());
    }

    @Test
    void capturedTargetSurvivesOutgoingSingleSlotOverwrite() {
        SessionCostState s = SessionCostState.get();
        s.recordApiRequest("target", new Usage(1, 0, 0, 0), 100);
        CostStatePersistence.saveForSession("sess-A", tmp.resolve("project-a"), config);
        SessionCostState.Snapshot target = CostStatePersistence.readForSession(
            "sess-A", tmp.resolve("project-a"), config);

        s.reset();
        s.recordApiRequest("outgoing", new Usage(9, 0, 0, 0), 500);
        CostStatePersistence.saveForSession("sess-B", tmp.resolve("project-a"), config);
        CostStatePersistence.restoreCaptured(target);

        assertEquals(100, s.apiDurationMs());
        assertTrue(s.usageByModel().containsKey("target"));
        assertFalse(s.usageByModel().containsKey("outgoing"));
    }

    @Test
    void differentProjectsKeepIndependentLastSessionCostState() {
        Path projectA = tmp.resolve("project-a");
        Path projectB = tmp.resolve("project-b");
        SessionCostState s = SessionCostState.get();
        s.recordApiRequest("a", new Usage(1, 0, 0, 0), 100);
        CostStatePersistence.saveForSession("sess-A", projectA, config);

        s.reset();
        s.recordApiRequest("b", new Usage(1, 0, 0, 0), 500);
        CostStatePersistence.saveForSession("sess-B", projectB, config);

        s.reset();
        assertTrue(CostStatePersistence.restoreForSession("sess-A", projectA, config));
        assertEquals(100, s.apiDurationMs());
        s.reset();
        assertTrue(CostStatePersistence.restoreForSession("sess-B", projectB, config));
        assertEquals(500, s.apiDurationMs());
    }

    @Test
    void saveUsesReleasedProjectFieldsAndPreservesAuthAndProjectSettings() throws Exception {
        Path project = tmp.resolve("project-a");
        String projectKey = TrustConfigStore.getProjectPathForConfig(project);
        Files.writeString(config, """
            {
              "primaryApiKey": "secret",
              "projects": {
                "%s": { "hasTrustDialogAccepted": true }
              }
            }
            """.formatted(projectKey.replace("\\", "\\\\")));
        SessionCostState.get().recordApiRequest("m", new Usage(1, 0, 0, 0), 123, 100);
        SessionCostState.get().recordToolDuration(9);

        CostStatePersistence.saveForSession("sess-A", project, config);

        var root = JsonUtils.readJson(config);
        assertEquals("secret", root.path("primaryApiKey").asText());
        var entry = root.path("projects").path(projectKey);
        assertTrue(entry.path("hasTrustDialogAccepted").asBoolean());
        assertEquals("sess-A", entry.path("lastSessionId").asText());
        assertEquals(123, entry.path("lastAPIDuration").asLong());
        assertEquals(100, entry.path("lastAPIDurationWithoutRetries").asLong());
        assertEquals(9, entry.path("lastToolDuration").asLong());
        assertTrue(entry.has("lastDuration"));
        assertTrue(entry.has("lastModelUsage"));
        assertFalse(root.has("lastSessionCosts"));
    }
}
