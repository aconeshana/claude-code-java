package com.claudecode.tools.tasks;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.tools.ValidationResult;
import com.claudecode.core.config.ClaudePaths;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused tests for {@link TeamCreateTool} — the "one team per leader" guard.
 */
class TeamCreateToolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    @AfterEach
    void reset() {
        TeamRegistry.instance().clearForTest();
    }

    private ToolExecutionContext ctx(String sessionId) {
        return ToolExecutionContext.of(new AbortController(), sessionId);
    }

    private JsonNode input(String teamName) {
        ObjectNode root = mapper.createObjectNode();
        root.put("team_name", teamName);
        return root;
    }

    @Test
    void validateInput_requiresTeamName() {
        TeamCreateTool tool = new TeamCreateTool();
        ValidationResult r = tool.validateInput(mapper.createObjectNode(), ctx("sess-1"));
        assertInstanceOf(ValidationResult.Invalid.class, r);
        assertEquals("team_name is required for TeamCreate",
            ((ValidationResult.Invalid) r).message());
    }

    @Test
    void validateInput_refusesSecondTeamForSameLeader() {
        TeamCreateTool tool = new TeamCreateTool();
// Simulate the leader already owning a team (no call FS side effect).
        TeamRegistry.instance().create("alpha", "", "team-lead@alpha", "sess-lead");
        ValidationResult r = tool.validateInput(input("beta"), ctx("sess-lead"));
        assertInstanceOf(ValidationResult.Invalid.class, r);
        assertTrue(Strings.CS.contains(((ValidationResult.Invalid) r).message(), "Already leading team"));
    }

    @Test
    void call_derivesLeadAgentIdAsTeamLeadAtTeamName() throws Exception {
        TeamCreateTool tool = new TeamCreateTool();
        String result = tool.call(input("gamma"), ctx("sess-lead-id"));
        JsonNode json = mapper.readTree(result);
        assertEquals("gamma", json.get("team_name").asText());
        assertEquals("team-lead@gamma", json.get("lead_agent_id").asText());
// clean it up.
        Path dir = ClaudePaths.TEAMS_DIR.resolve("gamma");
        if (Files.exists(dir)) {
            try (var stream = Files.walk(dir)) {
                stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception _) {}
                    });
            }
        }
    }

    @Test
    void collisionUsesOriginalWordSlugFallback() throws Exception {
        TeamRegistry.instance().create("collision-name", "", "team-lead@collision-name", "other");
        JsonNode json = mapper.readTree(new TeamCreateTool().call(
            input("collision-name"), ctx("new-leader")));
        String generated = json.get("team_name").asText();
        assertNotEquals("collision-name", generated);
        assertTrue(generated.matches("[^-]+-[^-]+-[^-]+"));
        Path dir = ClaudePaths.TEAMS_DIR.resolve(TeamPaths.sanitizeName(generated));
        if (Files.exists(dir)) {
            try (var stream = Files.walk(dir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception _) {}
                });
            }
        }
    }

    @Test
    void teamStoragePathsUseOriginalSanitizeNameProtocol() {
        assertEquals("---my-team", TeamPaths.sanitizeName("../My Team"));
        assertEquals(ClaudePaths.TEAMS_DIR.resolve("---my-team"),
            TeamPaths.teamDirectory("../My Team"));
        assertEquals(ClaudePaths.TASKS_DIR.resolve("My_Team"),
            TeamPaths.taskListDirectory("My_Team"));
        assertTrue(TeamPaths.teamDirectory("../My Team").normalize()
            .startsWith(ClaudePaths.TEAMS_DIR.normalize()));
    }

    @Test
    void migratesTheExistingSessionTaskListIntoTheTeamLikeReleased197(
            @TempDir Path tasksRoot) {
        TodoStore sessionTasks = new TodoStore(tasksRoot, "session-1");
        Task original = sessionTasks.create(
            "Keep this task", "before team creation", null, null);

        TeamCreateTool.migrateSessionTaskList(tasksRoot, "session-1", "My_Team");

        assertFalse(Files.exists(tasksRoot.resolve("session-1")));
        TodoStore teamTasks = new TodoStore(tasksRoot, "My_Team");
        assertEquals(original, teamTasks.get(original.id()).orElseThrow());
    }

    @Test
    void preparingTeamTasksDoesNotDeleteAnExistingTargetLikeReleased197(
            @TempDir Path tasksRoot) throws Exception {
        TodoStore sessionTasks = new TodoStore(tasksRoot, "session-1");
        Task sessionTask = sessionTasks.create(
            "Session task", "rename source", null, null);
        TodoStore existingTeamTasks = new TodoStore(tasksRoot, "team-a");
        Task existingTeamTask = existingTeamTasks.create(
            "Existing team task", "must survive failed rename", null, null);

        TeamCreateTool.prepareTeamTaskList(tasksRoot, "session-1", "team-a");

        assertTrue(Files.isDirectory(tasksRoot.resolve("session-1")),
            "rename failure leaves the source list untouched");
        assertEquals(sessionTask,
            new TodoStore(tasksRoot, "session-1").get(sessionTask.id()).orElseThrow());
        assertEquals(existingTeamTask,
            new TodoStore(tasksRoot, "team-a").get(existingTeamTask.id()).orElseThrow());
    }
}
