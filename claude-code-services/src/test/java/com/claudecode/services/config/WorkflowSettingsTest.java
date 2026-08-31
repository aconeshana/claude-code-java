package com.claudecode.services.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WorkflowSettingsTest {

    @TempDir Path temp;

    @Test
    void nullableLayeredBooleanKeepsUnsetDistinctFromFalse() throws IOException {
        Path user = temp.resolve("user.json");
        Path project = temp.resolve("project.json");
        assertNull(RuntimeSettings.loadOptionalBoolean("enableWorkflows", List.of(user, project)));

        Files.writeString(user, "{\"enableWorkflows\": true}");
        Files.writeString(project, "{\"enableWorkflows\": false}");
        assertEquals(Boolean.FALSE,
            RuntimeSettings.loadOptionalBoolean("enableWorkflows", List.of(user, project)));
    }
}
