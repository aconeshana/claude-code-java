package com.claudecode.services.dream;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class DreamLockTest {

    @TempDir Path dir;

    @Test
    void readReturnsZeroWhenAbsent() {
        DreamLock lock = new DreamLock(dir);
        assertEquals(0L, lock.readLastConsolidatedAt());
    }

    @Test
    void acquireStampsNowAndReturnsZeroPrior() {
        DreamLock lock = new DreamLock(dir);
        long prior = lock.tryAcquire();
        assertEquals(0L, prior, "first acquire has no prior mtime");
        long mtime = lock.readLastConsolidatedAt();
        assertTrue(mtime > 0, "lock file now exists with an mtime");
        // mtime should be very recent (within the last minute)
        assertTrue(System.currentTimeMillis() - mtime < 60_000, "mtime is ~now");
    }

    @Test
    void secondAcquireByLiveProcessFails() {
        DreamLock lock = new DreamLock(dir);
        lock.tryAcquire(); // held by current (live) process
        assertEquals(-1L, lock.tryAcquire(), "live holder blocks re-acquire");
    }

    @Test
    void rollbackWithZeroPriorUnlinks() {
        DreamLock lock = new DreamLock(dir);
        lock.tryAcquire();
        assertTrue(Files.exists(dir.resolve(".consolidate-lock")));
        lock.rollback(0L);
        assertEquals(0L, lock.readLastConsolidatedAt(), "rollback to zero removes the file");
        assertFalse(Files.exists(dir.resolve(".consolidate-lock")));
    }

    @Test
    void rollbackWithPriorRewindsMtime() throws IOException {
        DreamLock lock = new DreamLock(dir);
        lock.tryAcquire();
        long fresh = lock.readLastConsolidatedAt();
        long prior = fresh - 3_600_000L; // one hour earlier
        lock.rollback(prior);
        // After rollback the mtime is rewound and the PID body is cleared.
        long rewound = lock.readLastConsolidatedAt();
        assertTrue(rewound <= prior + 2000, "mtime rewound to prior (allow clock slop)");
        assertEquals("", Files.readString(dir.resolve(".consolidate-lock")).trim());
    }

    @Test
    void reclaimsLockHeldByDeadPid() throws IOException {
        DreamLock lock = new DreamLock(dir);
        // Seed a lock body with a PID that cannot be alive, recent mtime.
        Path lockFile = dir.resolve(".consolidate-lock");
        Files.createDirectories(lockFile.getParent());
        Files.writeString(lockFile, "999999");
        long seededMtime = System.currentTimeMillis();
        Files.setLastModifiedTime(lockFile, FileTime.fromMillis(seededMtime));
        // Dead PID + recent mtime → reclaim (not -1).
        long prior = lock.tryAcquire();
        assertTrue(prior >= 0, "dead-PID lock is reclaimable");
        assertEquals(seededMtime, prior, "reclaim returns the seeded prior mtime");
    }

    @Test
    void recordConsolidationStampsFile() {
        DreamLock lock = new DreamLock(dir);
        lock.recordConsolidation();
        long mtime = lock.readLastConsolidatedAt();
        assertTrue(mtime > 0, "recordConsolidation stamps the lock file");
    }
}
