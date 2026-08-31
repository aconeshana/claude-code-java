package com.claudecode.tools.tasks;

import com.claudecode.tools.ToolTexts;
import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.util.WordSlugGenerator;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.tools.ValidationResult;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.tasks.teammate.AgentTeamsEnabled;
import com.claudecode.tools.tasks.teammate.TeammateMailbox;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * TeamCreate — provisions a shared task list for a team so that in-process teammates spawned under
 * the same {@code team_id} can auto-claim work from it (see {@link
 * InProcessTeammateTask#tryClaimNextTask}).
 */
@BuiltInTool(
    name = "TeamCreate",
    shouldDefer = true
)
public class TeamCreateTool extends AnnotatedTool<JsonNode, String> {


    private static final String TEAM_LEAD_NAME = "team-lead";

    private static final JsonNode SCHEMA = buildSchema();

    @Override
    public String description() {
        return ToolTexts.description("TeamCreate");
    }

    @Override
    public JsonNode inputSchema() { return SCHEMA; }

    @Override
    public boolean isEnabled() {
        return AgentTeamsEnabled.isEnabled();
    }

    @Override
    public String call(JsonNode input, ToolExecutionContext context) {
        // team_name presence is guaranteed by validateInput; re-read defensively.
        String desiredName = input.hasNonNull("team_name") ? input.get("team_name").asText() : "";
        if (StringUtils.isBlank(desiredName)) {
            return "Error: team_name is required.";
        }
        // A.1 — do not silently overwrite an existing team: derive a unique name.
        String teamName = resolveUniqueTeamName(desiredName);
        String leadSessionId = context != null ? context.sessionId() : null;

        prepareTeamTaskList(ClaudePaths.TASKS_DIR, leadSessionId, teamName);
        TeamTaskListRegistry.instance().getOrCreate(teamName);


        // → "team-lead@<teamName>".
        String leadAgentId = TEAM_LEAD_NAME + "@" + teamName;
        String description = input.hasNonNull("description") ? input.get("description").asText() : "";
        String leadAgentType = input.hasNonNull("agent_type")
            ? input.get("agent_type").asText() : TEAM_LEAD_NAME;
        String leadModel = context != null ? context.currentModel() : null;
        String cwd = context != null && context.workingDirectory() != null
            ? context.workingDirectory() : System.getProperty("user.dir");

        // A.2 — getTeamFilePath sanitizes only the physical directory; the
        // user-visible team name and deterministic agent id retain finalTeamName.
        Path configPath = TeamPaths.teamConfigFile(teamName);

        // persisted. Do this before publishing in-memory team state so a disk
        // failure cannot return a successful-looking team that cannot be
        // discovered or cleaned up later.
        try {
            writeConfig(configPath, teamName, description, leadAgentId, leadSessionId,
                leadAgentType, leadModel, cwd);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist team configuration: "
                + e.getMessage(), e);
        }

// Register in-memory team state (AppState): lead is the first member and an active agent.
        TeamRegistry.instance().create(teamName, description, leadAgentId, leadSessionId);
        TeamTaskListRegistry.instance().bindSession(leadSessionId, teamName);
        TeammateMailbox.instance().registerName(TEAM_LEAD_NAME, leadAgentId);

        // initial team file write. Explicit TeamDelete removes this entry.
        TeamSessionCleanup.register(teamName);


        ObjectNode result = mapper().createObjectNode();
        result.put("team_name", teamName);
        result.put("lead_agent_id", leadAgentId);
        result.put("team_file_path", configPath.toString());
        return result.toString();
    }

    /**
     * Released 2.1.197 carries the leader's existing session task list into
     * the newly initialized team. Both the rename and directory creation are
     * best-effort in the original implementation.
     */
    static void migrateSessionTaskList(Path tasksRoot, String sessionId, String teamName) {
        Path target = tasksRoot.resolve(TaskPersistence.sanitizePathComponent(teamName));
        if (StringUtils.isNotEmpty(sessionId) && !sessionId.equals(teamName)) {
            Path source = tasksRoot.resolve(TaskPersistence.sanitizePathComponent(sessionId));
            try {
                Files.move(source, target);
            } catch (IOException _) {
                // Compatibility: a failed/missing source does not fail TeamCreate.
            }
        }
        try {
            Files.createDirectories(target);
        } catch (IOException _) {
            // Compatibility: directory initialization is also best-effort.
        }
    }

    static void prepareTeamTaskList(Path tasksRoot, String sessionId, String teamName) {
        // Released 2.1.197 switches the active list id, then attempts the rename.
        // It does not remove an existing destination; a collision simply leaves
        // both the session source and existing team list untouched.
        migrateSessionTaskList(tasksRoot, sessionId, teamName);
    }


    private static String resolveUniqueTeamName(String desired) {
        TeamTaskListRegistry listRegistry = TeamTaskListRegistry.instance();
        if (listRegistry.get(desired).isEmpty()
                && !TeamRegistry.instance().has(desired)
                && !Files.isRegularFile(TeamPaths.teamConfigFile(desired))) {
            return desired;
        }
        String candidate;
        do {
            candidate = WordSlugGenerator.generateWordSlug();
        } while (listRegistry.get(candidate).isPresent() || TeamRegistry.instance().has(candidate));
        return candidate;
    }


    private static void writeConfig(Path configPath, String teamName, String description,
                                    String leadAgentId, String leadSessionId,
                                    String leadAgentType, String leadModel, String cwd)
            throws IOException {
        Files.createDirectories(configPath.getParent());
        ObjectNode config = mapper().createObjectNode();
        config.put("name", teamName);
        config.put("description", description);
        config.put("createdAt", System.currentTimeMillis());
        config.put("leadAgentId", leadAgentId);
        config.put("leadSessionId", leadSessionId); // null → JSON null

        ArrayNode members = config.putArray("members");
        ObjectNode lead = members.addObject();
        lead.put("agentId", leadAgentId);
        lead.put("name", TEAM_LEAD_NAME);
        lead.put("agentType", leadAgentType);
        if (leadModel != null) lead.put("model", leadModel);
        lead.put("joinedAt", System.currentTimeMillis());
        lead.put("tmuxPaneId", "");
        lead.put("cwd", cwd);
        lead.putArray("subscriptions");
        mapper().writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), config);
    }

    @Override
    public PermissionDecision checkPermissions(JsonNode input, ToolPermissionContext permCtx) {
        return PermissionDecision.allow();
    }


    @Override
    public ValidationResult validateInput(JsonNode input, ToolExecutionContext context) {
        String desiredName = input.hasNonNull("team_name") ? input.get("team_name").asText() : "";
        if (StringUtils.isBlank(desiredName)) {
            return ValidationResult.invalid("team_name is required for TeamCreate");
        }
        String sessionId = context != null ? context.sessionId() : null;
        if (sessionId != null) {
            Optional<TeamRegistry.TeamState> existing =
                TeamRegistry.instance().findByLeadSessionId(sessionId);
            if (existing.isPresent()) {
                return ValidationResult.invalid(
                    "Already leading team \"" + existing.get().name()
                        + "\". A leader can only manage one team at a time. Use TeamDelete to "
                        + "end the current team before creating a new one.");
            }
        }
        return ValidationResult.valid();
    }


    @Override public String searchHint() { return "create a multi-agent swarm team"; }


    @Override public Object toAutoClassifierInput(JsonNode input) {
        return input == null ? "" : input.path("team_name").asText("");
    }


    @Override
    public ToolResult mapResult(Object rawResult, JsonNode input, ToolExecutionContext context) {
        if (!(rawResult instanceof String output)) return null;
        try {
            JsonNode data = mapper().readTree(output);
            return ToolResult.success(output).withToolUseResult(data);
        } catch (Exception _) {
            return null;
        }
    }


    @Override public String prompt(ToolExecutionContext context) {
        return ToolTexts.prompt("TeamCreate");
    }


    private static JsonNode buildSchema() {
        ObjectNode schema = mapper().createObjectNode();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("team_name")
            .put("description", "Name for the new team (also the shared task list id)")
            .put("type", "string");
        props.putObject("description")
            .put("description", "Team description/purpose")
            .put("type", "string");
        props.putObject("agent_type")
            .put("description", "Type/role of the team lead (e.g., \"researcher\")")
            .put("type", "string");
        ArrayNode required = schema.putArray("required");
        required.add("team_name");
        schema.put("additionalProperties", false);
        return schema;
    }
}
