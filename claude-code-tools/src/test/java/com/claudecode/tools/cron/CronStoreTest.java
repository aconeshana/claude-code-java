package com.claudecode.tools.cron;

import com.claudecode.core.serialization.JsonUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CronStoreTest {

    @AfterEach
    void resetStore() {
        CronStore.resetForTest();
    }

    @Test
    void durablePathIsProjectRelative(@TempDir Path projectRoot) {
        CronStore.configureProjectRootForTest(projectRoot);

        assertEquals(projectRoot.resolve(".claude").resolve("scheduled_tasks.json"),
            CronStore.durablePath());
    }

    @Test
    void startupProbeOnlyTreatsANonEmptyTasksEnvelopeAsScheduledWork(
            @TempDir Path projectRoot) throws Exception {
        CronStore.configureProjectRootForTest(projectRoot);
        Files.createDirectories(CronStore.durablePath().getParent());

        Files.writeString(CronStore.durablePath(), "{\"tasks\":[]}");
        assertFalse(CronStore.hasDurableTasksSync());

        Files.writeString(CronStore.durablePath(), """
            {"tasks":[{"id":"abc12345"}]}
            """);
        assertTrue(CronStore.hasDurableTasksSync());
    }

    @Test
    void validateNewJobAcceptsAFireableExpression() {
        assertNull(CronStore.validateNewJob("*/5 * * * *"));
    }

    @Test
    void validateNewJobRejectsMalformedAndNeverFiringExpressions() {
        assertEquals("Invalid cron expression 'not a cron'. Expected 5 fields: M H DoM Mon DoW.",
            CronStore.validateNewJob("not a cron"));
        // Feb 30 never occurs; with dow unconstrained no calendar date matches.
        assertEquals("Cron expression '0 0 30 2 *' "
                + "does not match any calendar date in the next year.",
            CronStore.validateNewJob("0 0 30 2 *"));
    }

    @Test
    void validateNewJobRejectsAtCapacityMatchingTheCronToolGate() {
        for (int i = 0; i < CronStore.MAX_JOBS; i++) {
            CronStore.add("*/5 * * * *", "job " + i, true, false);
        }

        assertEquals("Too many scheduled jobs (max " + CronStore.MAX_JOBS
            + "). Cancel one first.", CronStore.validateNewJob("0 9 * * *"));
    }

    @Test
    void durableFileUsesOfficialTasksEnvelopeAndOmitsRuntimeFields(@TempDir Path projectRoot)
        throws Exception {
        CronStore.configureProjectRootForTest(projectRoot);

        CronStore.add("5 * * * *", "check deploy", false, true);

        var root = JsonUtils.getMapper().readTree(CronStore.durablePath().toFile());
        assertTrue(root.isObject());
        assertTrue(root.path("tasks").isArray());
        var task = root.path("tasks").get(0);
        assertFalse(task.has("durable"));
        assertFalse(task.has("recurring"), "false optional flags are omitted by the TS writer");
    }

    @Test
    void loadDurableReadsOfficialEnvelopeAndIgnoresSessionOnlyState(@TempDir Path projectRoot)
        throws Exception {
        CronStore.configureProjectRootForTest(projectRoot);
        Files.createDirectories(CronStore.durablePath().getParent());
        Files.writeString(CronStore.durablePath(), """
            {
              "tasks": [
                {"id":"abc12345","cron":"*/5 * * * *","prompt":"ping","createdAt":123,"recurring":true}
              ]
            }
            """);

        CronStore.loadDurable();

        assertEquals(1, CronStore.list().size());
        assertEquals("abc12345", CronStore.list().getFirst().id());
        assertTrue(CronStore.list().getFirst().durable());
    }

    @Test
    void durableRoundTripPreservesLastFiredAtAndPermanent(@TempDir Path projectRoot)
        throws Exception {
        CronStore.configureProjectRootForTest(projectRoot);
        Files.createDirectories(CronStore.durablePath().getParent());
        Files.writeString(CronStore.durablePath(), """
            {
              "tasks": [
                {"id":"abc12345","cron":"*/5 * * * *","prompt":"ping",
                 "createdAt":123,"lastFiredAt":456,"recurring":true,"permanent":true}
              ]
            }
            """);

        CronStore.loadDurable();

        CronStore.CronJob task = CronStore.list().getFirst();
        assertEquals(456L, task.lastFiredAt());
        assertTrue(task.permanent());

        CronStore.markFired(List.of(task.id()), 789L);
        var persisted = JsonUtils.getMapper().readTree(CronStore.durablePath().toFile())
            .path("tasks").get(0);
        assertEquals(789L, persisted.path("lastFiredAt").asLong());
        assertTrue(persisted.path("permanent").asBoolean());
    }

    @Test
    void durableRoundTripPreservesReleasedCreatorOwnershipMetadata(@TempDir Path projectRoot)
        throws Exception {
        CronStore.configureProjectRootForTest(projectRoot);

        CronStore.add("5 * * * *", "owned", false, true, null, "session-a");

        var persisted = JsonUtils.getMapper().readTree(CronStore.durablePath().toFile())
            .path("tasks").get(0);
        assertEquals("session-a", persisted.path("createdBySessionId").asText());
        assertEquals(ProcessHandle.current().pid(), persisted.path("createdByPid").asLong());
        assertTrue(persisted.path("createdByProcStart").isTextual());

        CronStore.loadDurable();
        CronStore.CronJob restored = CronStore.list().getFirst();
        assertEquals("session-a", restored.createdBySessionId());
        assertEquals(ProcessHandle.current().pid(), restored.createdByPid());
        assertEquals(persisted.path("createdByProcStart").asText(),
            restored.createdByProcStart());
    }

    @Test
    void durableRoundTripPreservesThePerTaskModelOverride(@TempDir Path projectRoot)
        throws Exception {
        CronStore.configureProjectRootForTest(projectRoot);

        CronStore.addWithModel("5 * * * *", "report with opus", true, true, "opus");

        var persisted = JsonUtils.getMapper().readTree(CronStore.durablePath().toFile())
            .path("tasks").get(0);
        assertEquals("opus", persisted.path("model").asText());

        CronStore.loadDurable();
        assertEquals("opus", CronStore.list().getFirst().model());

        // A task without an override stays absent on the wire, not null-spelled.
        CronStore.add("7 * * * *", "session model", true, true);
        var rows = JsonUtils.getMapper().readTree(CronStore.durablePath().toFile())
            .path("tasks");
        assertFalse(rows.get(1).has("model"));
        assertNull(CronStore.list().get(1).model());
    }

    @Test
    void resumedCreatorSessionRefreshesPidBeforeOtherSessionsCanTakeOver(@TempDir Path projectRoot)
        throws Exception {
        CronStore.configureProjectRootForTest(projectRoot);
        Files.createDirectories(CronStore.durablePath().getParent());
        Files.writeString(CronStore.durablePath(), """
            {"tasks":[{"id":"abc12345","cron":"5 * * * *","prompt":"owned",
              "createdAt":123,"createdBySessionId":"session-a",
              "createdByPid":999999,"createdByProcStart":"old-token"}]}
            """);
        CronStore.loadDurable();

        assertTrue(CronStore.refreshCreatorProcess(
            "session-a", ProcessHandle.current().pid(), "new-token"));

        CronStore.CronJob refreshed = CronStore.list().getFirst();
        assertEquals(ProcessHandle.current().pid(), refreshed.createdByPid());
        assertEquals("new-token", refreshed.createdByProcStart());
        var persisted = JsonUtils.getMapper().readTree(CronStore.durablePath().toFile())
            .path("tasks").get(0);
        assertEquals(ProcessHandle.current().pid(), persisted.path("createdByPid").asLong());
        assertEquals("new-token", persisted.path("createdByProcStart").asText());
    }
}
