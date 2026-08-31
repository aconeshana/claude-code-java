package com.claudecode.tools.tasks;

import com.claudecode.tools.ToolTexts;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.core.state.AgentColorStore;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.tasks.teammate.AgentTeamsEnabled;
import com.claudecode.tools.tasks.teammate.TeammateMailbox;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.io.FileUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Optional;


@BuiltInTool(
    name = "TeamDelete",
    shouldDefer = true
)
public class TeamDeleteTool extends AnnotatedTool<JsonNode, String> {

    private static final JsonNode SCHEMA = buildSchema();

    @Override
    public String description() {
        return ToolTexts.description("TeamDelete");
    }

    @Override
    public JsonNode inputSchema() { return SCHEMA; }

    @Override
    public boolean isEnabled() {
        return AgentTeamsEnabled.isEnabled();
    }

    @Override
    public String call(JsonNode input, ToolExecutionContext context) {
        String sessionId = context != null ? context.sessionId() : null;

        // leader currently leads (appState.teamContext?.teamName). Java tracks
        // the lead session id in TeamRegistry.
        Optional<TeamRegistry.TeamState> led = (sessionId != null)
            ? TeamRegistry.instance().findByLeadSessionId(sessionId)
            : Optional.empty();


        // leader currently leads (appState.teamContext?.teamName), resolved via
        // TeamRegistry#findByLeadSessionId. Any team_name input is ignored and
        // rejected by the schema's additionalProperties:false.
        String teamName = led.map(TeamRegistry.TeamState::name).orElse(null);


        if (teamName == null) {
            return resultJson(true, "No team name found, nothing to clean up", null);
        }

// B.2 — active non-lead member guard.
        List<String> active = TeamRegistry.instance().activeNonLeadAgents(teamName);
        if (!active.isEmpty()) {
            String memberNames = String.join(", ", active);
            return resultJson(false,
                "Cannot cleanup team with " + active.size() + " active member(s): "
                    + memberNames
                    + ". Use requestShutdown to gracefully terminate teammates first.",
                teamName);
        }

        TeamRegistry.instance().remove(teamName);
        TeamTaskListRegistry.instance().removeAndDelete(teamName);
        TeamSessionCleanup.unregister(teamName);
// teamName was derived from led and the null case already returned above ("nothing to clean
// up"), so led is provably present here.
        TeammateMailbox.instance().clearTeam(led.orElseThrow().members());
        TeammateMailbox.instance().clear(TeammateMailbox.TEAM_LEAD);

        // team is explicitly disbanded.
        AgentColorStore.resetAll();

        // B.1 — clean up the persisted, sanitized team directory.
        deleteTeamDir(teamName);

        // TeamSessionCleanup/TeamTaskListRegistry clear the color allocator,
        // leader task-list binding, and shutdown-cleanup registration. Java's
        // in-process mailbox is cleared above. Pane/worktree teardown remains
        // an external exclusion because those backends do not exist here.
        return resultJson(true,
            "Cleaned up directories and worktrees for team \"" + teamName + "\"", teamName);
    }

    /** Builds the structured {@code {success, message, team_name?}} result. */
    private static String resultJson(boolean success, String message, String teamName) {
        ObjectNode result = mapper().createObjectNode();
        result.put("success", success);
        result.put("message", message);
        if (teamName != null) {
            result.put("team_name", teamName);
        }
        return result.toString();
    }

    /** Recursively deletes the sanitized team directory if it exists. */
    private static void deleteTeamDir(String teamName) {
        FileUtils.deleteRecursively(TeamPaths.teamDirectory(teamName));
    }

    @Override
    public PermissionDecision checkPermissions(JsonNode input, ToolPermissionContext permCtx) {
        return PermissionDecision.allow();
    }


    @Override public String searchHint() { return "disband a swarm team and clean up"; }


    @Override public String prompt(ToolExecutionContext context) {
        return ToolTexts.prompt("TeamDelete");
    }


    @Override
    public ToolResult mapResult(Object rawResult, JsonNode input, ToolExecutionContext context) {
        if (!(rawResult instanceof String output)) return null;
        try {
            JsonNode data = mapper().readTree(output);
            ToolResult result = data.path("success").asBoolean(true)
                ? ToolResult.success(output) : ToolResult.error(output);
            return result.withToolUseResult(data);
        } catch (Exception _) {
            return null;
        }
    }


    private static JsonNode buildSchema() {
        ObjectNode schema = mapper().createObjectNode();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("type", "object");

        // NO properties. The team to delete is inferred solely from the leader

        schema.put("additionalProperties", false);
        return schema;
    }
}
