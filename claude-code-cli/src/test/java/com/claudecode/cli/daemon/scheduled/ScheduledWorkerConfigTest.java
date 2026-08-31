package com.claudecode.cli.daemon.scheduled;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduledWorkerConfigTest {

    @Test
    void parsesReleasedDefaults() {
        ScheduledWorkerConfig config = ScheduledWorkerConfig.parse("""
            {
              "tasks": [{
                "id": "daily-review",
                "cron": "0 9 * * *",
                "prompt": "Review the project",
                "directory": "/tmp/project"
              }]
            }
            """);

        assertEquals(1, config.maxConcurrent());
        ScheduledTaskConfig task = config.tasks().getFirst();
        assertEquals("daily-review", task.id());
        assertEquals(Path.of("/tmp/project").toAbsolutePath().normalize(), task.directory());
        assertTrue(task.enabled());
        assertEquals(ScheduledPermissionMode.DONT_ASK, task.permissionMode());
        assertEquals(30, task.runTimeoutMinutes());
        assertEquals(1, task.maxQueued());
    }

    @Test
    void acceptsEveryReleasedPermissionMode() {
        for (String mode : new String[]{
                "dontAsk", "auto", "default", "acceptEdits", "plan", "bypassPermissions"}) {
            ScheduledWorkerConfig config = ScheduledWorkerConfig.parse(
                configWith("\"permissionMode\":\"" + mode + "\""));
            assertEquals(mode, config.tasks().getFirst().permissionMode().wireValue());
        }
    }

    @Test
    void rejectsUnknownFieldsAtEveryLevel() {
        assertThrows(IllegalArgumentException.class, () -> ScheduledWorkerConfig.parse("""
            {"tasks":[],"unknown":true}
            """));
        assertThrows(IllegalArgumentException.class,
            () -> ScheduledWorkerConfig.parse(configWith("\"unknown\":true")));
    }

    @Test
    void rejectsDuplicateTaskIds() {
        assertThrows(IllegalArgumentException.class, () -> ScheduledWorkerConfig.parse("""
            {
              "tasks": [
                {"id":"same","cron":"* * * * *","prompt":"one","directory":"/tmp"},
                {"id":"same","cron":"* * * * *","prompt":"two","directory":"/tmp"}
              ]
            }
            """));
    }

    @Test
    void rejectsInvalidBoundsAndRequiredValues() {
        assertInvalid("\"id\":\"\"");
        assertInvalid("\"cron\":\"not cron\"");
        assertInvalid("\"prompt\":\"   \"");
        assertInvalid("\"directory\":\"\"");
        assertInvalid("\"runTimeoutMinutes\":0");
        assertInvalid("\"runTimeoutMinutes\":10081");
        assertInvalid("\"maxQueued\":0");
        assertThrows(IllegalArgumentException.class,
            () -> ScheduledWorkerConfig.parse("{\"tasks\":[],\"maxConcurrent\":0}"));
    }

    private static void assertInvalid(String override) {
        assertThrows(IllegalArgumentException.class,
            () -> ScheduledWorkerConfig.parse(configWith(override)));
    }

    private static String configWith(String override) {
        return """
            {
              "tasks": [{
                "id": "task",
                "cron": "* * * * *",
                "prompt": "prompt",
                "directory": "/tmp",
                %s
              }]
            }
            """.formatted(override);
    }
}
