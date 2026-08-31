package com.claudecode.cli.daemon.scheduled;

import com.claudecode.core.serialization.JsonUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduledWorkerStatusWriterTest {

    @Test
    void atomicallyWritesReleasedStatusShape(@TempDir Path directory) throws Exception {
        Path status = directory.resolve("daemon.scheduled.status.json");
        ScheduledWorkerStatusWriter writer = new ScheduledWorkerStatusWriter(
            status, () -> 42L, () -> "proc-start", () -> 1_000L);

        writer.write(new ScheduledWorkerSnapshot(true, 2, 3, Map.of("task", 900L)));

        var json = JsonUtils.getMapper().readTree(status.toFile());
        assertEquals(42L, json.path("pid").asLong());
        assertEquals("proc-start", json.path("procStart").asText());
        assertEquals(1_000L, json.path("timestamp").asLong());
        assertEquals(2, json.path("running").asInt());
        assertEquals(3, json.path("queued").asInt());
        assertEquals(900L, json.path("lastFiredAt").path("task").asLong());
        assertTrue(Files.list(directory).noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
    }
}
