package com.claudecode.tools.tasks;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.io.FileUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;


class TeamDeleteToolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    @AfterEach
    void reset() {
        TeamRegistry.instance().clearForTest();
        FileUtils.deleteRecursively(TeamPaths.teamDirectory("persisted-active"));
        FileUtils.deleteRecursively(TeamPaths.teamDirectory("persisted-idle"));
    }

    private ToolExecutionContext ctx(String sessionId) {
        return ToolExecutionContext.of(new AbortController(), sessionId);
    }

    @Test
    void deletesCurrentlyLedTeam() throws Exception {
        TeamRegistry.instance().create("myteam", "", "team-lead@myteam", "sess-del");
        TeamDeleteTool tool = new TeamDeleteTool();
        String result = tool.call(mapper.createObjectNode(), ctx("sess-del"));

        JsonNode json = mapper.readTree(result);
        assertTrue(json.get("success").asBoolean());
        assertEquals("myteam", json.get("team_name").asText());
        assertTrue(Strings.CS.contains(json.get("message").asText(), "Cleaned up directories"));
        assertFalse(TeamRegistry.instance().has("myteam"));
    }

    @Test
    void noLedTeam_isNoOpSuccess() throws Exception {
        TeamDeleteTool tool = new TeamDeleteTool();
        String result = tool.call(mapper.createObjectNode(), ctx("sess-nope"));
        JsonNode json = mapper.readTree(result);
        assertTrue(json.get("success").asBoolean());
        assertEquals("No team name found, nothing to clean up", json.get("message").asText());
        assertFalse(json.has("team_name"));
    }


    @Test
    void schemaHasNoTeamNameParam() {
        JsonNode schema = new TeamDeleteTool().inputSchema();
        assertFalse(schema.path("properties").has("team_name"),
            "TS TeamDeleteTool schema must not expose a team_name parameter");
    }

    @Test
    void persistedActiveMemberBlocksCleanup() throws Exception {
        String team = "persisted-active";
        TeamRegistry.instance().create(team, "", "team-lead@" + team, "sess-persisted");
        Files.createDirectories(TeamPaths.teamDirectory(team));
        ObjectNode config = mapper.createObjectNode();
        config.putArray("members").addObject()
            .put("agentId", "team-lead@" + team)
            .put("name", "team-lead");
        ArrayNode members = (ArrayNode) config.get("members");
        members.addObject()
            .put("agentId", "researcher@" + team)
            .put("name", "researcher")
            .put("isActive", true);
        mapper.writeValue(TeamPaths.teamConfigFile(team).toFile(), config);

        JsonNode result = mapper.readTree(new TeamDeleteTool().call(
            mapper.createObjectNode(), ctx("sess-persisted")));
        assertFalse(result.path("success").asBoolean());
        assertTrue(Strings.CS.contains(result.path("message").asText(), "active member"));
        assertTrue(TeamRegistry.instance().has(team));
    }

    @Test
    void persistedIdleMemberDoesNotBlockCleanup() throws Exception {
        String team = "persisted-idle";
        TeamRegistry.instance().create(team, "", "team-lead@" + team, "sess-idle");
        Files.createDirectories(TeamPaths.teamDirectory(team));
        ObjectNode config = mapper.createObjectNode();
        ArrayNode members = config.putArray("members");
        members.addObject().put("agentId", "team-lead@" + team).put("name", "team-lead");
        members.addObject().put("agentId", "researcher@" + team)
            .put("name", "researcher").put("isActive", false);
        mapper.writeValue(TeamPaths.teamConfigFile(team).toFile(), config);

        JsonNode result = mapper.readTree(new TeamDeleteTool().call(
            mapper.createObjectNode(), ctx("sess-idle")));
        assertTrue(result.path("success").asBoolean());
        assertFalse(TeamRegistry.instance().has(team));
    }
}
