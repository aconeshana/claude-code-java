package com.claudecode.cli.daemon.scheduled;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduledAgentQueryRunnerTest {

    @Test
    void projectsReleasedQueryOptionsToChildCli() {
        ScheduledTaskConfig task = new ScheduledTaskConfig(
            "task", "* * * * *", "inspect the project", Path.of("/tmp/project"),
            true, ScheduledPermissionMode.PLAN, "claude-opus-5", 30, 1);

        List<String> command = ScheduledAgentQueryRunner.commandFor(
            Path.of("/opt/claude-code-java"), task);

        assertEquals("/opt/claude-code-java", command.getFirst());
        assertTrue(command.containsAll(List.of(
            "--print", "--output-format", "json", "--permission-mode", "plan",
            "--model", "claude-opus-5", "--setting-sources", "user,project,local",
            "inspect the project")));
    }

    @Test
    void omitsOptionalModel() {
        ScheduledTaskConfig task = new ScheduledTaskConfig(
            "task", "* * * * *", "prompt", Path.of("/tmp"), true,
            ScheduledPermissionMode.DONT_ASK, null, 30, 1);

        List<String> command = ScheduledAgentQueryRunner.commandFor(Path.of("claude"), task);

        assertTrue(command.stream().noneMatch("--model"::equals));
    }
}
