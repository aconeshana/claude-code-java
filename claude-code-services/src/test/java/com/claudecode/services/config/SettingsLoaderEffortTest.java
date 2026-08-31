package com.claudecode.services.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsEffortTest {

    @Test
    void effortLevelAcceptsCurrentProtocolLevelsFromPersistedSettings(@TempDir Path tmp) throws Exception {
        Path user = tmp.resolve("user.json");
        Path project = tmp.resolve("project.json");
        Path local = tmp.resolve("local.json");
        Files.writeString(user, "{\"effortLevel\":\"high\"}");
        Files.writeString(project, "{\"effortLevel\":\"xhigh\"}");
        Files.writeString(local, "{}");

        assertEquals("xhigh", RuntimeSettings.loadEffortLevel(List.of(user, project, local)));

        Files.writeString(local, "{\"effortLevel\":\"minimal\"}");
        assertEquals("minimal", RuntimeSettings.loadEffortLevel(List.of(user, project, local)));
    }

    @Test
    void effortLevelRejectsUnknownValues(@TempDir Path tmp) throws Exception {
        Path settings = tmp.resolve("settings.json");
        Files.writeString(settings, "{\"effortLevel\":\"turbo\"}");
        assertNull(RuntimeSettings.loadEffortLevel(List.of(settings)));
    }

    @Test
    void effortLevelPreservesCaseSensitiveSchema(@TempDir Path tmp) throws Exception {
        Path settings = tmp.resolve("settings.json");
        Files.writeString(settings, "{\"effortLevel\":\"HIGH\"}");

        assertNull(RuntimeSettings.loadEffortLevel(List.of(settings)));
    }
}
