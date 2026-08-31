package com.claudecode.services.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SettingsPolicyDropInTest {

    @TempDir
    Path tempDir;

    @Test
    void baseThenAlphabeticalDropInsLaterValuesWin() throws Exception {
        Path base = tempDir.resolve("managed-settings.json");
        Path ten = tempDir.resolve("10-defaults.json");
        Path twenty = tempDir.resolve("20-security.json");
        Files.writeString(base, "{\"model\":\"base\",\"values\":[\"base\"]}");
        Files.writeString(twenty, "{\"model\":\"security\",\"values\":[\"security\"]}");
        Files.writeString(ten, "{\"language\":\"English\",\"values\":[\"defaults\"]}");

        ObjectNode merged = SettingsSnapshots.loadPolicySettingsSnapshot(List.of(base, ten, twenty));

        assertEquals("security", merged.path("model").asText());
        assertEquals(List.of("base", "defaults", "security"),
            StreamSupport.stream(
                    merged.path("values").spliterator(), false)
                .map(JsonNode::asText).toList());
    }

    @Test
    void malformedFragmentDoesNotDiscardValidPolicyFiles() throws Exception {
        Path base = tempDir.resolve("managed-settings.json");
        Path malformed = tempDir.resolve("10-bad.json");
        Path valid = tempDir.resolve("20-good.json");
        Files.writeString(base, "{\"model\":\"base\"}");
        Files.writeString(malformed, "{not-json");
        Files.writeString(valid, "{\"language\":\"English\"}");

        ObjectNode merged = SettingsSnapshots.loadPolicySettingsSnapshot(
            List.of(base, malformed, valid));

        assertEquals("base", merged.path("model").asText());
        assertEquals("English", merged.path("language").asText());
    }

    @Test
    void emptyOrMissingPolicyFilesProduceNoSnapshot() {
        assertNull(SettingsSnapshots.loadPolicySettingsSnapshot(List.of(
            tempDir.resolve("missing.json"))));
    }
}
