package com.claudecode.mcp;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.engine.SessionCapabilityFilter;
import com.claudecode.core.io.FileUtils;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Project-scoped persistent baseline store for progressive capability switches in
 * {@code ~/.claude.json} ({@code projects[<canonicalGitRoot>]}).
 *
 * <p>Reuses {@code disabledMcpServers} alongside {@code disabledMcpTools},
 * {@code disabledSkills}, and {@code disabledTools} so every newly opened session
 * inherits the user's saved default switches while active sessions can override them
 * in-memory without mutating disk defaults.
 */
@Explanation("Extends ~/.claude.json project config with default disablement sets for tools, skills, and MCP tools")
public final class CapabilityConfigStore {

    private static final Logger LOG = LoggerFactory.getLogger(CapabilityConfigStore.class);

    public static final String FIELD_DISABLED_MCP_SERVERS = "disabledMcpServers";
    public static final String FIELD_DISABLED_MCP_TOOLS = "disabledMcpTools";
    public static final String FIELD_DISABLED_SKILLS = "disabledSkills";
    public static final String FIELD_DISABLED_TOOLS = "disabledTools";

    private CapabilityConfigStore() {}

    public record ProjectDefaults(
            Set<String> disabledTools,
            Set<String> disabledSkills,
            Set<String> disabledMcpServers,
            Set<String> disabledMcpTools) {

        public ProjectDefaults {
            disabledTools = disabledTools == null ? Set.of() : Set.copyOf(disabledTools);
            disabledSkills = disabledSkills == null ? Set.of() : Set.copyOf(disabledSkills);
            disabledMcpServers = disabledMcpServers == null ? Set.of() : Set.copyOf(disabledMcpServers);
            disabledMcpTools = disabledMcpTools == null ? Set.of() : Set.copyOf(disabledMcpTools);
        }

        public static ProjectDefaults empty() {
            return new ProjectDefaults(Set.of(), Set.of(), Set.of(), Set.of());
        }
    }

    public static ProjectDefaults loadProjectDefaults(Path projectDir) {
        return loadProjectDefaults(projectDir, ClaudePaths.GLOBAL_JSON);
    }

    static ProjectDefaults loadProjectDefaults(Path projectDir, Path globalConfigPath) {
        if (projectDir == null || globalConfigPath == null || !Files.isRegularFile(globalConfigPath)) {
            return ProjectDefaults.empty();
        }
        try {
            JsonNode root = JsonUtils.readJson(globalConfigPath);
            if (root == null || !root.isObject()) return ProjectDefaults.empty();
            String key = McpConfigLoader.projectConfigKey(projectDir);
            JsonNode project = root.path("projects").path(key);
            if (!project.isObject()) return ProjectDefaults.empty();
            Set<String> tools = readStringSet(project.get(FIELD_DISABLED_TOOLS));
            tools.removeAll(SessionCapabilityFilter.KERNEL_TOOLS);
            return new ProjectDefaults(
                tools,
                readStringSet(project.get(FIELD_DISABLED_SKILLS)),
                readStringSet(project.get(FIELD_DISABLED_MCP_SERVERS)),
                readStringSet(project.get(FIELD_DISABLED_MCP_TOOLS)));
        } catch (IOException e) {
            LOG.debug("Failed to read project capability defaults from {}: {}",
                globalConfigPath, e.toString());
            return ProjectDefaults.empty();
        }
    }

    public static boolean setToolDisabled(Path projectDir, String toolName, boolean disabled)
            throws IOException {
        return setDisabledEntry(projectDir, FIELD_DISABLED_TOOLS, toolName, disabled,
            ClaudePaths.GLOBAL_JSON);
    }

    public static boolean setSkillDisabled(Path projectDir, String skillName, boolean disabled)
            throws IOException {
        return setDisabledEntry(projectDir, FIELD_DISABLED_SKILLS, skillName, disabled,
            ClaudePaths.GLOBAL_JSON);
    }

    public static boolean setMcpServerDisabled(Path projectDir, String serverName, boolean disabled)
            throws IOException {
        return setDisabledEntry(projectDir, FIELD_DISABLED_MCP_SERVERS, serverName, disabled,
            ClaudePaths.GLOBAL_JSON);
    }

    public static boolean setMcpToolDisabled(Path projectDir, String fullToolName, boolean disabled)
            throws IOException {
        return setDisabledEntry(projectDir, FIELD_DISABLED_MCP_TOOLS, fullToolName, disabled,
            ClaudePaths.GLOBAL_JSON);
    }

    static boolean setDisabledEntry(
            Path projectDir, String fieldName, String entryName, boolean disabled,
            Path globalConfigPath) throws IOException {
        Objects.requireNonNull(projectDir, "projectDir is required");
        if (StringUtils.isBlank(entryName)) {
            throw new IllegalArgumentException("Capability name must not be blank");
        }
        String trimmed = entryName.strip();
        if (FIELD_DISABLED_TOOLS.equals(fieldName)
                && SessionCapabilityFilter.KERNEL_TOOLS.contains(trimmed)) {
            throw new IllegalArgumentException(
                "Kernel capability '" + trimmed + "' cannot be disabled");
        }

        ObjectNode root = Files.isRegularFile(globalConfigPath)
            ? readObject(globalConfigPath)
            : JsonUtils.getMapper().createObjectNode();
        ObjectNode projects = ensureObject(root, "projects");
        ObjectNode project = ensureObject(projects, McpConfigLoader.projectConfigKey(projectDir));

        LinkedHashSet<String> names = readStringSet(project.get(fieldName));
        boolean changed = disabled ? names.add(trimmed) : names.remove(trimmed);
        if (!changed) return false;

        ArrayNode arr = project.putArray(fieldName);
        names.forEach(arr::add);
        FileUtils.atomicReplace(globalConfigPath, temp -> JsonUtils.writeJson(temp, root, true));
        LOG.info("Updated {} '{}' disabled={} in {}", fieldName, trimmed, disabled, globalConfigPath);
        return true;
    }

    private static LinkedHashSet<String> readStringSet(JsonNode node) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                if (item.isTextual() && StringUtils.isNotBlank(item.asText())) {
                    set.add(item.asText().strip());
                }
            }
        }
        return set;
    }

    private static ObjectNode readObject(Path path) throws IOException {
        JsonNode node = JsonUtils.readJson(path);
        if (node == null || !node.isObject()) {
            throw new IOException("Expected JSON object at " + path);
        }
        return (ObjectNode) node;
    }

    private static ObjectNode ensureObject(ObjectNode parent, String key) {
        JsonNode existing = parent.get(key);
        if (existing != null && existing.isObject()) {
            return (ObjectNode) existing;
        }
        return parent.putObject(key);
    }
}
