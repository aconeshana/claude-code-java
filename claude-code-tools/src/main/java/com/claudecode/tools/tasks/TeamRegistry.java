package com.claudecode.tools.tasks;

import java.util.ArrayList;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;


public final class TeamRegistry {

    private static final TeamRegistry INSTANCE = new TeamRegistry();

    private final ConcurrentHashMap<String, TeamState> teams = new ConcurrentHashMap<>();

    private TeamRegistry() {}

    public static TeamRegistry instance() {
        return INSTANCE;
    }

    /**
     * Per-team state. {@code members} and {@code activeAgents} are mutable,
     * thread-safe sets (the record fields hold the live references).
     */
    public record TeamState(
        String name,
        String description,
        String leadAgentId,
        String leadSessionId,
        long createdAt,
        Set<String> members,
        Set<String> activeAgents
    ) {}

    /** Registers a new team; the lead is the first member and an active agent. */
    public TeamState create(String name, String description, String leadAgentId, String leadSessionId) {
        Set<String> members = ConcurrentHashMap.newKeySet();
        Set<String> activeAgents = ConcurrentHashMap.newKeySet();
        if (leadAgentId != null) {
            members.add(leadAgentId);
            activeAgents.add(leadAgentId);
        }
        TeamState state = new TeamState(name, description, leadAgentId, leadSessionId,
            System.currentTimeMillis(), members, activeAgents);
        teams.put(name, state);
        return state;
    }

    public Optional<TeamState> get(String name) {
        return Optional.ofNullable(teams.get(name));
    }

    public boolean has(String name) {
        return teams.containsKey(name);
    }

    /**
     * Finds the team whose {@code leadSessionId} matches {@code sessionId}, if any.
     */
    public Optional<TeamState> findByLeadSessionId(String sessionId) {
        if (sessionId == null) return Optional.empty();
        return teams.values().stream()
            .filter(s -> sessionId.equals(s.leadSessionId()))
            .findFirst();
    }

    public void remove(String name) {
        teams.remove(name);
    }

    /**
     * Registers a spawned teammate in both the in-memory state and the persisted team file.
     */
    public void addAgent(String name, String agentId, String displayName,
                         String agentType, String model, String cwd) {
        TeamState s = teams.get(name);
        if (s != null) {
            s.members().add(agentId);
            s.activeAgents().add(agentId);
            updatePersistedMember(name, agentId, displayName, agentType, model, cwd, true);
        }
    }


    void setAgentActive(String name, String agentId, boolean active) {
        TeamState s = teams.get(name);
        if (s != null) {
            if (active) {
                s.activeAgents().add(agentId);
            } else {
                s.activeAgents().remove(agentId);
            }
            updatePersistedMember(name, agentId, agentId, null, null,
                System.getProperty("user.dir"), active);
        }
    }

    /**
     * Active agents that are NOT the lead.
     */
    public List<String> activeNonLeadAgents(String name) {
        TeamState s = teams.get(name);
        if (s == null) return List.of();
        List<String> persisted = readPersistedActiveNonLeadAgents(name, s.leadAgentId());
        if (persisted != null) {
            return persisted;
        }
        List<String> out = new ArrayList<>();
        for (String a : s.activeAgents()) {
            if (s.leadAgentId() == null || !s.leadAgentId().equals(a)) out.add(a);
        }
        return out;
    }


    private static List<String> readPersistedActiveNonLeadAgents(String name, String leadAgentId) {
        if (name == null) return null;
        var path = TeamPaths.teamConfigFile(name);
        if (!Files.isRegularFile(path)) return null;
        try {
            JsonNode root = JsonUtils.getMapper().readTree(path.toFile());
            JsonNode members = root == null ? null : root.get("members");
            if (members == null || !members.isArray()) return null;
            List<String> active = new ArrayList<>();
            for (JsonNode member : members) {
                String agentId = text(member, "agentId");
                String memberName = text(member, "name");
                if ((leadAgentId != null &&Strings.CS.equals( leadAgentId, agentId))
                    ||Strings.CS.equals( "team-lead", memberName)) {
                    continue;
                }
                if (!member.has("isActive") || member.path("isActive").asBoolean(true)) {
                    String label = StringUtils.isNotBlank(memberName) ? memberName : agentId;
                    if (StringUtils.isNotBlank(label)) active.add(label);
                }
            }
            return active;
        } catch (IOException | RuntimeException _) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) return null;
        String value = node.get(field).asText();
        return StringUtils.isBlank(value) ? null : value;
    }


    private static synchronized void updatePersistedMember(String teamName, String agentId,
                                                            String displayName, String agentType,
                                                            String model, String cwd,
                                                            boolean active) {
        if (teamName == null || agentId == null) return;
        var path = TeamPaths.teamConfigFile(teamName);
        if (!Files.isRegularFile(path)) return;
        try {
            JsonNode rootNode = JsonUtils.getMapper().readTree(path.toFile());
            if (!(rootNode instanceof ObjectNode root)) return;
            ArrayNode members;
            if (root.get("members") instanceof ArrayNode existing) {
                members = existing;
            } else {
                members = root.putArray("members");
            }
            ObjectNode member = null;
            for (JsonNode node : members) {
                if (node instanceof ObjectNode object
                    && agentId.equals(text(object, "agentId"))) {
                    member = object;
                    break;
                }
            }
            if (member == null) {
                member = members.addObject();
                member.put("agentId", agentId);
                member.put("name", StringUtils.isBlank(displayName) ? agentId : displayName);
                member.put("joinedAt", System.currentTimeMillis());
                member.put("tmuxPaneId", "");
                member.put("cwd", cwd == null ? System.getProperty("user.dir") : cwd);
                member.putArray("subscriptions");
            }
            if (StringUtils.isNotBlank(agentType)) member.put("agentType", agentType);
            if (StringUtils.isNotBlank(model)) member.put("model", model);
            member.put("isActive", active);
            Files.createDirectories(path.getParent());
            JsonUtils.getMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(), root);
        } catch (IOException | RuntimeException _) {
            // Team state remains usable in memory if persistence is unavailable.
        }
    }

    /** Test seam: clears all registered teams. */
    void clearForTest() {
        teams.clear();
    }
}
