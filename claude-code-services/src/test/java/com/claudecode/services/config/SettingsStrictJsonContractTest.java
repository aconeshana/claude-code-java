package com.claudecode.services.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** File-backed settings stay strict JSON after an optional UTF-8 BOM. */
class SettingsStrictJsonContractTest {

    @TempDir
    Path tempDir;

    @Test
    void utf8BomIsAccepted() throws Exception {
        Path settings = write("\uFEFF{\"theme\":\"dark\"}");
        JsonNode parsed = SettingsTreeReader.readJson(settings);
        assertEquals("dark", parsed.path("theme").asText());
    }

    @Test
    void commentsAreRejected() throws Exception {
        Path settings = write("{\"theme\":\"dark\" /* inline */}");
        assertThrows(IOException.class, () -> SettingsTreeReader.readJson(settings));
    }

    @Test
    void trailingCommasAreRejected() throws Exception {
        Path settings = write("{\"theme\":\"dark\",}");
        assertThrows(IOException.class, () -> SettingsTreeReader.readJson(settings));
    }

    private Path write(String content) throws IOException {
        Path settings = tempDir.resolve("settings.json");
        Files.writeString(settings, content);
        return settings;
    }
}
