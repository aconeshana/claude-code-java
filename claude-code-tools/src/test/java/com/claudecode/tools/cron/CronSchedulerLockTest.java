package com.claudecode.tools.cron;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import com.claudecode.core.serialization.JsonUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CronSchedulerLockTest {

    @Test
    void onlyOneLiveOwnerFiresAndStaleLeaseCanBeTakenOver(@TempDir Path projectRoot)
        throws Exception {
        CronSchedulerLock first = new CronSchedulerLock(projectRoot, "first");
        CronSchedulerLock second = new CronSchedulerLock(projectRoot, "second");

        assertTrue(first.tryAcquire());
        assertFalse(second.tryAcquire());
        first.release();
        assertTrue(second.tryAcquire());
        second.release();

        Files.createDirectories(projectRoot.resolve(".claude"));
        Files.writeString(projectRoot.resolve(".claude/scheduled_tasks.lock"),
            "{\"sessionId\":\"dead\",\"pid\":9223372036854775807,\"acquiredAt\":1}");
        assertTrue(first.tryAcquire());
        first.release();
    }

    @Test
    void reusedLivePidWithDifferentStartTokenIsAStaleLease(@TempDir Path projectRoot)
        throws Exception {
        CronSchedulerLock lock = new CronSchedulerLock(projectRoot, "new-owner");
        Files.createDirectories(projectRoot.resolve(".claude"));
        Files.writeString(projectRoot.resolve(".claude/scheduled_tasks.lock"), """
            {"sessionId":"old-owner","pid":%d,"procStart":"not-this-process","acquiredAt":1}
            """.formatted(ProcessHandle.current().pid()));

        assertTrue(lock.tryAcquire());

        var lease = JsonUtils.getMapper().readTree(lock.path().toFile());
        assertEquals("new-owner", lease.path("sessionId").asText());
        assertTrue(lease.path("procStart").isTextual());
        lock.release();
    }
}
