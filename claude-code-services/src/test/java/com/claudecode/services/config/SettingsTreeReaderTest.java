package com.claudecode.services.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.claudecode.core.serialization.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization tests for the strict, BOM-tolerant settings tree boundary.
 */
class SettingsTreeReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesUtf8BomSettingsFilesWithoutTreatingThemAsJsonc() throws Exception {
        Path settings = tempDir.resolve("settings.json");
        Files.writeString(settings, "\uFEFF{\"model\":\"bom-model\"}");

        JsonNode parsed = SettingsTreeReader.readJson(settings);

        assertEquals("bom-model", parsed.path("model").asText());
    }

    @Test
    void treatsBlankAndBomOnlySettingsFilesAsEmptyObjectsLikeTsParser() throws Exception {
        Path settings = tempDir.resolve("settings.json");
        Files.writeString(settings, "\uFEFF  \n");

        JsonNode parsed = SettingsTreeReader.readJson(settings);

        assertTrue(parsed.isObject());
        assertTrue(parsed.isEmpty());
    }

    @Test
    void diagnosticsTreatBlankAndBomOnlySettingsFilesAsEmptyObjectsLikeTsParser() throws Exception {
        Path settings = tempDir.resolve("settings.json");
        Files.writeString(settings, "\uFEFF  \n");

        SettingsTreeReader.ParsedSettings parsed =
            SettingsTreeReader.parseForDiagnostics(settings, false);

        assertTrue(parsed.errors().isEmpty());
        assertTrue(parsed.settings().isEmpty());
    }

    @Test
    void malformedExistingSettingsRemainStrictlyInvalidForEditors() throws Exception {
        Path settings = tempDir.resolve("settings.json");
        Files.writeString(settings, "{not-json}");

        assertThrows(IOException.class, () -> SettingsTreeReader.readJson(settings));
    }

    @Test
    void cacheRevalidatesARewriteWhenTheFreshnessStampChanges() throws Exception {
        Path settings = tempDir.resolve("settings.json");
        Files.writeString(settings, "{\"language\":\"first\"}");
        assertEquals("first", SettingsTreeReader.readCached(settings, false)
            .path("language").asText());

        Files.writeString(settings, "{\"language\":\"other\"}");
        Files.setLastModifiedTime(settings, FileTime.fromMillis(System.currentTimeMillis() + 2_000));

        assertEquals("other", SettingsTreeReader.readCached(settings, false)
            .path("language").asText());
    }

    @Test
    void acceptedSettingsCoerceEnvironmentValuesButRejectAWholeInvalidSource() {
        JsonNode coercible = JsonUtils.parseTree("{\"env\":{\"PORT\":42}}");
        assertEquals("42", SettingsTreeReader.accepted(coercible).path("env").path("PORT").asText());

        JsonNode invalid = JsonUtils.parseTree("{\"model\":\"ok\",\"language\":42}");
        assertNull(SettingsTreeReader.accepted(invalid));
    }
}
