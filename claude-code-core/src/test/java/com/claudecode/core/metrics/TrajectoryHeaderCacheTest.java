package com.claudecode.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrajectoryHeaderCacheTest {

    @TempDir
    Path tempDir;

    @Test
    void deduplicatesIdenticalHeadersAndEvictsAfterSevenDayTtl() {
        AtomicLong now = new AtomicLong(1_700_000_000_000L);
        TrajectoryHeaderCache cache = new TrajectoryHeaderCache(
            tempDir.resolve("trajectory-headers"),
            Duration.ofDays(7),
            now::get);

        ObjectNode readSchema = JsonUtils.getMapper().createObjectNode();
        readSchema.put("type", "object");
        List<TrajectoryHeaderCache.ToolSpec> tools = List.of(
            new TrajectoryHeaderCache.ToolSpec("Read", "Read file contents", readSchema));

        String sha1 = cache.store("You are a coding assistant.", tools);
        assertNotNull(sha1);
        assertEquals(64, sha1.length());

        // Advance 4 days and touch the same header — TTL window refreshes.
        now.addAndGet(Duration.ofDays(4).toMillis());
        String sha2 = cache.store("You are a coding assistant.", tools);
        assertEquals(sha1, sha2);

        // Advance another 5 days (9 days since creation, 5 days since refresh) -> still alive!
        now.addAndGet(Duration.ofDays(5).toMillis());
        var hit = cache.load(sha1);
        assertTrue(hit.isPresent());
        assertEquals("You are a coding assistant.", hit.get().systemPrompt());
        assertEquals(1, hit.get().tools().size());
        assertEquals("Read", hit.get().tools().getFirst().name());

        // Advance past 7 days since last touch -> evicted on load/sweep!
        now.addAndGet(Duration.ofDays(3).toMillis());
        assertFalse(cache.load(sha1).isPresent());
    }

    @Test
    void rejectsNonHexTraversalIdentifiers() {
        TrajectoryHeaderCache cache = new TrajectoryHeaderCache(
            tempDir.resolve("trajectory-headers"),
            Duration.ofDays(7),
            System::currentTimeMillis);
        assertFalse(cache.load("../secret").isPresent());
        assertFalse(cache.load("ZZZZ").isPresent());
    }
}
