package com.claudecode.tools.tasks;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.state.AgentColorStore;
import com.claudecode.tools.tasks.teammate.TeammateMailbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamSessionCleanupTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void reset() {
        TeamSessionCleanup.clearForTest();
        TeamRegistry.instance().clearForTest();
        TeamTaskListRegistry.instance().clearForTest();
        TeammateMailbox.instance().clearAll();
        AgentColorStore.resetAll();
    }

    @Test
    void registeredTeamIsRemovedWhenShutdownCleanupRuns() throws Exception {
        String name = "cleanup-team";
        TeamRegistry.instance().create(name, "", "team-lead@" + name, "session");
        TeamTaskListRegistry.instance().getOrCreate(name);
        TeammateMailbox.instance().registerName("researcher", "researcher@" + name);
        AgentColorStore.set("researcher", "blue");
        Files.createDirectories(TeamPaths.teamDirectory(name));
        Files.writeString(TeamPaths.teamConfigFile(name), "{}");
        TeamSessionCleanup.register(name);

        TeamSessionCleanup.cleanupRegisteredTeams();

        assertFalse(TeamRegistry.instance().has(name));
        assertTrue(TeamTaskListRegistry.instance().get(name).isEmpty());
        assertFalse(Files.exists(TeamPaths.teamDirectory(name)));
        assertNull(AgentColorStore.get("researcher"));
        assertFalse(TeammateMailbox.instance().hasInbox("researcher@" + name));
    }

    @Test
    void explicitDeleteUnregistersTeamFromCleanup() throws Exception {
        String name = "explicit-team";
        TeamRegistry.instance().create(name, "", "team-lead@" + name, "session");
        TeamSessionCleanup.register(name);
        ObjectNode input = mapper.createObjectNode();
        String result = new TeamDeleteTool().call(input,
            ToolExecutionContext.of(new AbortController(), "session"));
        assertTrue(mapper.readTree(result).path("success").asBoolean());
        TeamSessionCleanup.cleanupRegisteredTeams();
        assertFalse(TeamRegistry.instance().has(name));
    }
}
