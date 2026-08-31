package com.claudecode.sdk;

import com.claudecode.core.serialization.JsonUtils;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessCommandTest {
    @Test
    void mapsOfficialProcessTransportArgumentsAndEnvironment() {
        QueryOptions options = QueryOptions.builder()
            .cwd(Path.of("/tmp/project"))
            .model("claude-sonnet-4-6")
            .fallbackModel("claude-haiku-4-5")
            .maxTurns(3)
            .maxBudgetUsd(2.5)
            .permissionMode("plan")
            .allowDangerouslySkipPermissions(true)
            .allowedTools(List.of("Read", "Bash(git *)"))
            .disallowedTools(List.of("Write"))
            .includePartialMessages(true)
            .includeHookEvents(true)
            .sandbox(JsonUtils.getMapper().createObjectNode().put("enabled", true))
            .settingSources(List.of("project", "local"))
            .sessionId("11111111-1111-4111-8111-111111111111")
            .persistSession(false)
            .env(Map.of("CUSTOM", "value"))
            .build();

        ProcessCommand command = ProcessCommand.create(options);

        assertEquals("sdk-ts", command.environment().get("CLAUDE_CODE_ENTRYPOINT"));
        assertEquals("value", command.environment().get("CUSTOM"));
        assertTrue(command.arguments().containsAll(List.of(
            "--output-format", "stream-json", "--verbose", "--input-format",
            "--model", "claude-sonnet-4-6", "--fallback-model", "claude-haiku-4-5",
            "--max-turns", "3", "--max-budget-usd", "2.5", "--permission-mode", "plan",
            "--allow-dangerously-skip-permissions", "--include-partial-messages",
            "--include-hook-events", "--no-session-persistence", "--setting-sources",
            "project,local")));
        assertTrue(command.arguments().stream().anyMatch(value ->
            Strings.CS.contains(value, "\"sandbox\":{\"enabled\":true}")));
    }

    @Test
    void rejectsPermissionCallbackAndPromptToolTogether() {
        QueryOptions options = QueryOptions.builder()
            .canUseTool((_, _, _) -> JsonUtils.getMapper().createObjectNode())
            .permissionPromptToolName("permission")
            .build();
        assertThrows(IllegalArgumentException.class, () -> ProcessCommand.create(options));
    }

    @Test
    void rejectsNonPositiveTaskBudget() {
        QueryOptions options = QueryOptions.builder().taskBudget(0).build();
        assertThrows(IllegalArgumentException.class, () -> ProcessCommand.create(options));
    }
}
