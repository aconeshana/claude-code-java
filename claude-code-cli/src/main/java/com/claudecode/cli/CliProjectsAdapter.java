package com.claudecode.cli;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.gateway.GatewayProjectsPort;
import com.claudecode.mcp.McpConfig;
import com.claudecode.mcp.McpConfigLoader;
import com.claudecode.mcp.McpServerConfig;
import com.claudecode.mcp.McpServerScope;
import com.claudecode.permissions.RuleSource;
import com.claudecode.services.claudemd.MemoryFileInfo;
import com.claudecode.services.claudemd.MemoryFileScanner;
import com.claudecode.services.config.SettingsEditor;
import com.claudecode.tools.skills.Skill;
import com.claudecode.tools.skills.SkillLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists Web UI multi-folder project metadata in {@code $CLAUDE_CONFIG_DIR/web-projects.json}
 * while writing bound additional directories strictly via {@link SettingsEditor} into standard
 * Claude Code {@code permissions.additionalDirectories} so official Claude Code remains 100%
 * compatible.
 */
@Explanation("Manages multi-folder web projects sidecar registry and project-scoped Rules/Skills/MCP inspection")
final class CliProjectsAdapter implements GatewayProjectsPort {

    private static final Logger LOG = LoggerFactory.getLogger(CliProjectsAdapter.class);
    private static final String REGISTRY_FILENAME = "web-projects.json";

    private record StoredProject(String path, String name, long updatedAtMs) {}

    private final Path storeFile;
    private final Path configHome;

    CliProjectsAdapter() {
        this(ClaudePaths.currentClaudeHome());
    }

    CliProjectsAdapter(Path configHome) {
        this.configHome = configHome;
        this.storeFile = configHome.resolve(REGISTRY_FILENAME);
    }

    @Override
    public synchronized List<ProjectEntry> listProjects() {
        Map<String, StoredProject> stored = readRegistry();
        List<ProjectEntry> result = new ArrayList<>(stored.size());
        for (StoredProject item : stored.values()) {
            result.add(toProjectEntry(item));
        }
        result.sort(Comparator.comparingLong(ProjectEntry::updatedAtMs).reversed());
        return List.copyOf(result);
    }

    @Override
    public synchronized ProjectEntry createOrUpdateProject(
            String path, String name, List<String> additionalDirectories, String tier) {
        String normalizedPath = requireExistingDirectory(path);
        String cleanName = name == null ? "" : name.trim();
        RuleSource ruleSource = Strings.CS.equals("project", tier)
            ? RuleSource.PROJECT_SETTINGS : RuleSource.LOCAL_SETTINGS;

        List<String> validAdditional = new ArrayList<>();
        if (additionalDirectories != null) {
            Set<String> seen = new LinkedHashSet<>();
            seen.add(normalizedPath);
            for (String rawDir : additionalDirectories) {
                if (StringUtils.isBlank(rawDir)) continue;
                String normalizedDir = requireExistingDirectory(rawDir);
                if (seen.add(normalizedDir)) {
                    validAdditional.add(normalizedDir);
                }
            }
        }
        if (!validAdditional.isEmpty()) {
            SettingsEditor.addAdditionalDirectoriesForProject(normalizedPath, validAdditional, ruleSource);
        }

        Map<String, StoredProject> registry = readRegistry();
        StoredProject updated = new StoredProject(normalizedPath, cleanName, System.currentTimeMillis());
        registry.put(normalizedPath, updated);
        writeRegistry(registry);
        return toProjectEntry(updated);
    }

    @Override
    public synchronized ProjectEntry renameProject(String path, String name) {
        String normalizedPath = normalizePath(path);
        if (normalizedPath == null) {
            throw new IllegalArgumentException("path is required");
        }
        String cleanName = name == null ? "" : name.trim();
        Map<String, StoredProject> registry = readRegistry();
        StoredProject updated = new StoredProject(normalizedPath, cleanName, System.currentTimeMillis());
        registry.put(normalizedPath, updated);
        writeRegistry(registry);
        return toProjectEntry(updated);
    }

    @Override
    public synchronized void removeProject(String path) {
        String normalizedPath = normalizePath(path);
        if (normalizedPath == null) return;
        Map<String, StoredProject> registry = readRegistry();
        if (registry.remove(normalizedPath) != null) {
            writeRegistry(registry);
        }
    }

    @Override
    public ObjectNode projectCustomizations(String path) {
        String normalizedPath = normalizePath(path);
        if (normalizedPath == null) {
            throw new IllegalArgumentException("path is required");
        }
        Path projectDir = Path.of(normalizedPath);
        if (!Files.isDirectory(projectDir)) {
            throw new IllegalArgumentException("project directory does not exist: " + normalizedPath);
        }

        ObjectNode root = JsonUtils.getMapper().createObjectNode();
        root.put("path", normalizedPath);

        // 1. Folders (primary + per-tier additional dirs directly from projectDir, never CwdState)
        List<String> localDirs = readTierAdditionalDirectories(projectDir.resolve(".claude/settings.local.json"));
        List<String> projectDirs = readTierAdditionalDirectories(projectDir.resolve(".claude/settings.json"));
        List<String> userDirs = readTierAdditionalDirectories(configHome.resolve("settings.json"));
        List<String> mergedAdditional = mergeAdditionalDirectories(userDirs, projectDirs, localDirs);

        ObjectNode folders = root.putObject("folders");
        folders.put("primary", normalizedPath);
        putStringArray(folders, "additional", mergedAdditional);
        putStringArray(folders, "local_directories", localDirs);
        putStringArray(folders, "project_directories", projectDirs);
        putStringArray(folders, "user_directories", userDirs);

        // 2. Rules (CLAUDE.md / .claude/rules across primary + additional dirs)
        List<Path> extraPaths = mergedAdditional.stream()
            .map(Path::of)
            .filter(Files::isDirectory)
            .toList();
        List<MemoryFileInfo> memoryFiles = List.of();
        try {
            MemoryFileScanner scanner = MemoryFileScanner.forConfigHome(configHome, List.of(), null);
            memoryFiles = scanner.scan(projectDir, extraPaths);
        } catch (RuntimeException failure) {
            LOG.debug("Failed to scan memory files for {}: {}", normalizedPath, failure.getMessage());
        }
        ObjectNode rulesNode = root.putObject("rules");
        ArrayNode ruleFiles = rulesNode.putArray("files");
        int totalRuleTokens = 0;
        for (MemoryFileInfo info : memoryFiles) {
            int tokens = estimateRoughTokens(info.content());
            totalRuleTokens += tokens;
            ObjectNode fileNode = ruleFiles.addObject();
            fileNode.put("path", info.path().toString());
            Path fileName = info.path().getFileName();
            fileNode.put("name", fileName == null ? info.path().toString() : fileName.toString());
            fileNode.put("scope", info.type().name());
            fileNode.put("tokens", tokens);
        }
        rulesNode.put("total_tokens", totalRuleTokens);

        // 3. Skills
        ArrayNode skillsArray = root.putArray("skills");
        try {
            SkillLoader loader = new SkillLoader();
            loader.addSource(Skill.SkillSource.USER, configHome.resolve("skills"));
            loader.addSource(Skill.SkillSource.PROJECT, projectDir.resolve(".claude/skills"));
            List<Skill> skills = loader.loadAll();
            for (Skill skill : skills) {
                ObjectNode skillNode = skillsArray.addObject();
                skillNode.put("name", StringUtils.defaultString(skill.name()));
                skillNode.put("description", StringUtils.defaultString(skill.description()));
                skillNode.put("source", skill.source() == null ? "unknown" : skill.source().name().toLowerCase(Locale.ROOT));
                if (skill.sourceFile() != null) {
                    skillNode.put("path", skill.sourceFile().toString());
                }
            }
        } catch (RuntimeException failure) {
            LOG.debug("Failed to load skills for {}: {}", normalizedPath, failure.getMessage());
        }

        // 4. MCP Servers
        ArrayNode mcpArray = root.putArray("mcp_servers");
        try {
            McpConfig mcpConfig = McpConfigLoader.loadConfig(projectDir);
            for (Map.Entry<String, McpServerConfig> entry : mcpConfig.servers().entrySet()) {
                McpServerConfig cfg = entry.getValue();
                McpServerScope scope = mcpConfig.scopes().get(entry.getKey());
                ObjectNode serverNode = mcpArray.addObject();
                serverNode.put("name", cfg.name());
                serverNode.put("type", StringUtils.defaultIfBlank(cfg.transportType(), "stdio"));
                serverNode.put("scope", scope == null ? "user" : scope.name().toLowerCase(Locale.ROOT));
                serverNode.put("disabled", cfg.disabled());
            }
        } catch (RuntimeException failure) {
            LOG.debug("Failed to load MCP config for {}: {}", normalizedPath, failure.getMessage());
        }

        return root;
    }

    private ProjectEntry toProjectEntry(StoredProject stored) {
        List<String> additional = readMergedAdditionalDirectories(stored.path());
        return new ProjectEntry(stored.path(), stored.name(), additional, stored.updatedAtMs());
    }

    private static int estimateRoughTokens(String text) {
        if (StringUtils.isBlank(text)) return 0;
        return Math.max(1, (text.length() + 3) / 4);
    }

    private List<String> readMergedAdditionalDirectories(String cwd) {
        Path projectDir = Path.of(cwd);
        return mergeAdditionalDirectories(
            readTierAdditionalDirectories(configHome.resolve("settings.json")),
            readTierAdditionalDirectories(projectDir.resolve(".claude/settings.json")),
            readTierAdditionalDirectories(projectDir.resolve(".claude/settings.local.json")));
    }

    private static List<String> mergeAdditionalDirectories(
            List<String> userDirs, List<String> projectDirs, List<String> localDirs) {
        Set<String> merged = new LinkedHashSet<>(userDirs);
        merged.addAll(projectDirs);
        merged.addAll(localDirs);
        return List.copyOf(merged);
    }

    private static List<String> readTierAdditionalDirectories(Path settingsPath) {
        if (!Files.isRegularFile(settingsPath)) return List.of();
        try {
            String raw = Files.readString(settingsPath, StandardCharsets.UTF_8);
            if (StringUtils.isBlank(raw)) return List.of();
            JsonNode parsed = JsonUtils.parseTree(raw);
            return readStringArray(parsed.path("permissions").path("additionalDirectories"));
        } catch (IOException | RuntimeException _) {
            return List.of();
        }
    }

    private static List<String> readStringArray(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode entry : node) {
            if (entry.isTextual() && StringUtils.isNotBlank(entry.asText())) {
                result.add(entry.asText().trim());
            }
        }
        return List.copyOf(result);
    }

    private static void putStringArray(ObjectNode parent, String field, List<String> values) {
        ArrayNode arr = parent.putArray(field);
        for (String value : values) {
            arr.add(value);
        }
    }

    private static String normalizePath(String raw) {
        if (StringUtils.isBlank(raw)) return null;
        return Path.of(raw.trim()).toAbsolutePath().normalize().toString();
    }

    private static String requireExistingDirectory(String raw) {
        String normalized = normalizePath(raw);
        if (normalized == null) {
            throw new IllegalArgumentException("directory path is required");
        }
        Path dir = Path.of(normalized);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("directory does not exist: " + normalized);
        }
        return normalized;
    }

    private Map<String, StoredProject> readRegistry() {
        Map<String, StoredProject> result = new LinkedHashMap<>();
        if (!Files.isRegularFile(storeFile)) return result;
        try {
            String raw = Files.readString(storeFile, StandardCharsets.UTF_8);
            if (StringUtils.isBlank(raw)) return result;
            JsonNode root = JsonUtils.parseTree(raw);
            JsonNode items = root.path("projects");
            if (!items.isArray()) return result;
            for (JsonNode item : items) {
                String path = normalizePath(item.path("path").asText(""));
                if (path == null) continue;
                String name = item.path("name").asText("").trim();
                long updated = item.path("updatedAtMs").asLong(0L);
                result.put(path, new StoredProject(path, name, updated));
            }
        } catch (IOException | RuntimeException failure) {
            LOG.warn("Failed to read {}: {}", storeFile, failure.getMessage());
        }
        return result;
    }

    private void writeRegistry(Map<String, StoredProject> registry) {
        ObjectNode root = JsonUtils.getMapper().createObjectNode();
        ArrayNode items = root.putArray("projects");
        for (StoredProject project : registry.values()) {
            ObjectNode node = items.addObject();
            node.put("path", project.path());
            node.put("name", project.name());
            node.put("updatedAtMs", project.updatedAtMs());
        }
        try {
            Path parent = storeFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = Files.createTempFile(parent != null ? parent : Path.of("."), "web-projects-", ".tmp");
            Files.writeString(tmp, JsonUtils.toPrettyJson(root) + "\n", StandardCharsets.UTF_8);
            try {
                Files.move(tmp, storeFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException _) {
                Files.move(tmp, storeFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("failed to persist " + storeFile + ": " + failure.getMessage(), failure);
        }
    }
}
