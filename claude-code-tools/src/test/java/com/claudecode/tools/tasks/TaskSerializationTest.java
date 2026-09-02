package com.claudecode.tools.tasks;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Disk-path serialization regression coverage for {@link Task}. Historically every store test
 * used {@link TodoStore#inMemory()}, so the Jackson write path (valueFilter instantiation)
 * only failed under GraalVM native image at runtime.
 */
class TaskSerializationTest {

    @Test
    void absentMetadataIsOmittedFromWireForm(@TempDir Path dir) throws Exception {
        Task task = new Task("1", "subject", "desc", Optional.empty(), Optional.empty(),
            TodoStatus.PENDING, List.of(), List.of(), Optional.empty());
        Path target = dir.resolve("1.json");

        JsonUtils.writeJson(target, task, true);

        JsonNode wire = JsonUtils.parseTree(Files.readString(target));
        assertFalse(wire.has("metadata"), wire.toPrettyString());
        assertFalse(wire.has("activeForm"));
        assertFalse(wire.has("owner"));
    }

    @Test
    void explicitEmptyMetadataSerializesAsEmptyObject(@TempDir Path dir) throws Exception {
        Task task = new Task("1", "subject", "desc", Optional.empty(), Optional.empty(),
            TodoStatus.PENDING, List.of(), List.of(),
            Optional.of(Map.of()));
        Path target = dir.resolve("1.json");

        JsonUtils.writeJson(target, task, true);

        JsonNode wire = JsonUtils.parseTree(Files.readString(target));
        assertTrue(wire.has("metadata"), wire.toPrettyString());
        assertTrue(wire.get("metadata").isObject());
        assertTrue(wire.get("metadata").isEmpty());
    }

    @Test
    void metadataValuesRoundTripThroughDiskStore(@TempDir Path dir) throws Exception {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("nullable", null);
        metadata.put("nested", Map.of("k", "v"));
        TodoStore store = new TodoStore(dir, "session");

        Task created = store.create("round trip", "wire form must survive", null, metadata);
        store.reload();
        Task reloaded = store.get(created.id()).orElseThrow();

        assertEquals(created, reloaded);
        assertEquals(metadata, reloaded.metadata().orElseThrow());
        assertTrue(reloaded.metadata().orElseThrow().containsKey("nullable"));
    }
}
