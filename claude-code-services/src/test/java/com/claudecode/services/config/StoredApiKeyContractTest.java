package com.claudecode.services.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The stored key is {@code primaryApiKey}, with the legacy {@code apiKey} field as a fallback. */
class StoredApiKeyContractTest {

    @TempDir
    Path tempDir;

    @Test
    void primaryFieldWinsOverTheLegacyField() throws Exception {
        Path file = write(Map.of("primaryApiKey", "primary", "apiKey", "legacy"));
        assertEquals("primary", GlobalConfigStore.readStoredApiKey(file).orElseThrow());
    }

    @Test
    void legacyFieldIsReadWhenThePrimaryFieldIsAbsent() throws Exception {
        Path file = write(Map.of("apiKey", "legacy"));
        assertEquals("legacy", GlobalConfigStore.readStoredApiKey(file).orElseThrow());
    }

    @Test
    void blankPrimaryFieldFallsThroughToTheLegacyField() throws Exception {
        Path file = write(Map.of("primaryApiKey", "  ", "apiKey", "legacy"));
        assertEquals("legacy", GlobalConfigStore.readStoredApiKey(file).orElseThrow());
    }

    @Test
    void missingFileHasNoStoredKey() {
        assertTrue(GlobalConfigStore.readStoredApiKey(tempDir.resolve("missing.json")).isEmpty());
    }

    private Path write(Map<String, String> fields) throws Exception {
        ObjectNode root = JsonUtils.getMapper().createObjectNode();
        fields.forEach(root::put);
        Path file = tempDir.resolve(".claude.json");
        Files.writeString(file, JsonUtils.getMapper().writeValueAsString(root));
        return file;
    }
}
