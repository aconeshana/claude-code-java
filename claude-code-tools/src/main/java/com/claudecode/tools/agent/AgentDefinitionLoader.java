package com.claudecode.tools.agent;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.claudecode.core.agent.BuiltInAgentDefinitions;
import com.claudecode.core.agent.AgentSource;
import com.claudecode.core.state.AgentColorStore;
import com.claudecode.core.agent.BuiltInAgentDefinitions.AgentDefinition;
import com.claudecode.core.util.FrontmatterParser;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.config.ClaudeConfigDirectories;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads custom agent definitions from managed, user, and project ancestor {@code.claude/agents/}
 * directories, merges them with built-in agents, and initialises colors via {@link
 * AgentColorStore}.
 */
public final class AgentDefinitionLoader {

    private static final Logger log = LoggerFactory.getLogger(AgentDefinitionLoader.class);


    public record ParseError(String path, String error) {}


    private record Loaded(List<BuiltInAgentDefinitions.AgentDefinition> agents,
                          List<ParseError> parseErrors) {}


    private static final Map<String, Loaded> CACHE = new ConcurrentHashMap<>();


    private static volatile Supplier<List<AgentDefinition>> pluginAgentsProvider;
    private static volatile Supplier<List<AgentDefinition>> cliAgentsProvider;

    private AgentDefinitionLoader() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns all agent definitions (built-in + custom) for {@code cwd}.
     */
    public static List<BuiltInAgentDefinitions.AgentDefinition> getAll(String cwd) {
        String key = (cwd != null) ? cwd : "";
        return CACHE.computeIfAbsent(key, AgentDefinitionLoader::buildAll).agents();
    }


    public static List<ParseError> getParseErrors(String cwd) {
        String key = (cwd != null) ? cwd : "";
        return CACHE.computeIfAbsent(key, AgentDefinitionLoader::buildAll).parseErrors();
    }

/**
     * Clears the per-cwd cache.
     */
    public static void clearCache() {
        CACHE.clear();
    }

    /**
     * Registers (or clears, with {@code null}) the plugin agent supplier and invalidates the cache so
     * the next {@link #getAll} pass picks the plugin agents up.
     */
    public static void setPluginAgentsProvider(
            Supplier<List<BuiltInAgentDefinitions.AgentDefinition>> provider) {
        pluginAgentsProvider = provider;
        clearCache();
    }

    /** Installs the CLI {@code --agents} definitions and invalidates the cache. */
    public static void setCliAgentsProvider(
            Supplier<List<BuiltInAgentDefinitions.AgentDefinition>> provider) {
        cliAgentsProvider = provider;
        clearCache();
    }


    public static List<AgentDefinition> parseCliAgents(String rawJson) {
        if (StringUtils.isBlank(rawJson)) return List.of();
        try {
            JsonNode root = JsonUtils.getMapper().readTree(rawJson);
            if (root == null || !root.isObject()) return List.of();
            List<AgentDefinition> result = new ArrayList<>();
            root.fields().forEachRemaining(entry -> {
                String name = entry.getKey();
                JsonNode node = entry.getValue();
                if (!node.isObject()) return;
                String description = text(node, "description");
                String prompt = text(node, "prompt");
                if (StringUtils.isBlank(name) || StringUtils.isBlank(description) || StringUtils.isBlank(prompt)) return;
                String model = textOrNull(node, "model");
                if (model != null && Strings.CI.equals("inherit", model)) model = "inherit";
                String memory = textOrNull(node, "memory");
                if (memory != null && !List.of("user", "project", "local").contains(memory)) memory = null;
                Integer maxTurns = node.has("maxTurns") && node.get("maxTurns").canConvertToInt()
                    ? node.get("maxTurns").intValue() : null;
                if (maxTurns != null && maxTurns <= 0) maxTurns = null;
                String effort = node.has("effort") ? node.get("effort").asText() : null;
                String permissionMode = textOrNull(node, "permissionMode");
                JsonNode hooks = node.has("hooks") ? node.get("hooks").deepCopy() : null;
                String initialPrompt = textOrNull(node, "initialPrompt");
                String isolation = textOrNull(node, "isolation");
                result.add(AgentDefinition.builder(name, description)
                    .tools(stringList(node.get("tools")))
                    .disallowedTools(stringList(node.get("disallowedTools")))
                    .mcpServers(stringList(node.get("mcpServers")))
                    .memory(memory)
                    .model(model)
                    .systemPrompt(prompt)
                    .background(node.path("background").asBoolean(false))
                    .source(AgentSource.FLAG_SETTINGS)
                    .maxTurns(maxTurns)
                    .effort(effort)
                    .permissionMode(permissionMode)
                    .hooks(hooks)
                    .skills(stringList(node.get("skills")))
                    .initialPrompt(initialPrompt)
                    .isolation(isolation)
                    .build());
            });
            return List.copyOf(result);
        } catch (Exception e) {
            log.warn("Failed to parse --agents JSON: {}", e.getMessage());
            return List.of();
        }
    }

    private static String text(JsonNode node, String field) {
        return node.has(field) && node.get(field).isTextual() ? node.get(field).asText() : "";
    }

    private static String textOrNull(JsonNode node, String field) {
        String value = text(node, field);
        return StringUtils.isBlank(value) ? null : value.trim();
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) if (item.isTextual()) result.add(item.asText());
        return List.copyOf(result);
    }

    /**
     * Returns the currently-<em>active</em> agent per name — i.e.
     */
    public static List<BuiltInAgentDefinitions.AgentDefinition> getActive(String cwd) {
        return activeFrom(getAll(cwd));
    }

    static List<BuiltInAgentDefinitions.AgentDefinition> activeFrom(
            List<BuiltInAgentDefinitions.AgentDefinition> allAgents) {
        Map<String, BuiltInAgentDefinitions.AgentDefinition> byType = new LinkedHashMap<>();

        // built-in < plugin < user < project < managed/policy.
        for (AgentSource source : List.of(
                AgentSource.BUILT_IN,
                AgentSource.PLUGIN,
                AgentSource.USER,
                AgentSource.PROJECT,
                AgentSource.FLAG_SETTINGS,
                AgentSource.MANAGED)) {
            for (BuiltInAgentDefinitions.AgentDefinition agent : allAgents) {
                if (agent.source() == source) {
                    byType.put(agent.agentType(), agent);
                }
            }
        }

        List<AgentDefinition> cli = byType.values().stream()
            .filter(a -> a.source() == AgentSource.FLAG_SETTINGS).toList();
        if (cli.isEmpty()) return List.copyOf(byType.values());
        List<AgentDefinition> ordered = new ArrayList<>();
        boolean inserted = false;
        for (AgentDefinition agent : byType.values()) {
            if (agent.source() == AgentSource.FLAG_SETTINGS) continue;
            if (!inserted && Strings.CS.equals("statusline-setup", agent.agentType())) {
                ordered.addAll(cli);
                inserted = true;
            }
            ordered.add(agent);
        }
        if (!inserted) ordered.addAll(cli);
        return List.copyOf(ordered);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private static Loaded buildAll(String cwd) {
        List<BuiltInAgentDefinitions.AgentDefinition> builtIns = BuiltInAgentDefinitions.getBuiltInAgents();
        List<BuiltInAgentDefinitions.AgentDefinition> plugins  = loadPluginAgents();
        List<BuiltInAgentDefinitions.AgentDefinition> cli = loadCliAgents();
        List<ParseError> parseErrors = new ArrayList<>();
        List<BuiltInAgentDefinitions.AgentDefinition> custom;
        try {
            custom = loadCustomAgents(cwd, parseErrors);
        } catch (RuntimeException e) {

            String message = e.getMessage() != null ? e.getMessage() : e.toString();
            log.warn("Error loading agent definitions: {}", message);
            parseErrors.add(new ParseError("unknown", message));
            custom = List.of();
        }


        for (BuiltInAgentDefinitions.AgentDefinition a : builtIns) {
            if (a.color() != null) AgentColorStore.set(a.agentType(), a.color());
        }
        for (BuiltInAgentDefinitions.AgentDefinition a : plugins) {
            if (a.color() != null) AgentColorStore.set(a.agentType(), a.color());
        }
        for (BuiltInAgentDefinitions.AgentDefinition a : custom) {
            if (a.color() != null) AgentColorStore.set(a.agentType(), a.color());
        }


        List<BuiltInAgentDefinitions.AgentDefinition> all =
            new ArrayList<>(builtIns.size() + plugins.size() + custom.size() + cli.size());
        all.addAll(builtIns);
        all.addAll(plugins);
        all.addAll(custom);
        all.addAll(cli);
        return new Loaded(Collections.unmodifiableList(all),
            Collections.unmodifiableList(parseErrors));
    }

    /** Agents from the plugin injection channel; empty when no provider wired. */
    private static List<BuiltInAgentDefinitions.AgentDefinition> loadPluginAgents() {
        var provider = pluginAgentsProvider;
        if (provider == null) return List.of();
        try {
            List<BuiltInAgentDefinitions.AgentDefinition> agents = provider.get();
            return agents != null ? agents : List.of();
        } catch (Exception e) {
            log.warn("Plugin agents provider failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static List<BuiltInAgentDefinitions.AgentDefinition> loadCliAgents() {
        var provider = cliAgentsProvider;
        if (provider == null) return List.of();
        try {
            List<AgentDefinition> agents = provider.get();
            return agents != null ? agents : List.of();
        } catch (Exception e) {
            log.warn("CLI agents provider failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Scans {@code ~/.claude/agents/} (user-level) and {@code <cwd>/.claude/agents/} (project-level)
     * for {@code.md} agent files, collecting parse failures into {@code parseErrors}.
     */
    private static List<BuiltInAgentDefinitions.AgentDefinition> loadCustomAgents(
            String cwd, List<ParseError> parseErrors) {
        List<Path> scanDirs = new ArrayList<>();

        // Policy-managed: <managed-root>/.claude/agents.
        scanDirs.add(ClaudePaths.managedRoot().resolve(".claude").resolve("agents"));


        scanDirs.add(ClaudePaths.AGENTS_DIR);

        // Project-level: every existing .claude/agents from cwd to git root.
        if (StringUtils.isNotBlank(cwd)) {
            scanDirs.addAll(ClaudeConfigDirectories.projectDirs(Path.of(cwd), "agents"));
        }

        List<BuiltInAgentDefinitions.AgentDefinition> agents = new ArrayList<>();
        Set<String> seenFileIds = new HashSet<>();
        for (Path dir : scanDirs) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> Strings.CS.endsWith(p.getFileName().toString(), ".md"))
                     .sorted()
                     .forEach(mdFile -> {
                         String fileId = fileIdentity(mdFile);
                         if (fileId != null && !seenFileIds.add(fileId)) {
                             log.debug("Skipping duplicate agent file: {}", mdFile);
                             return;
                         }
                         ParseOutcome outcome = parseAgentMarkdownInternal(mdFile);
                         if (outcome.definition() != null) {
                             agents.add(outcome.definition());
                         } else if (outcome.error() != null) {
                             parseErrors.add(outcome.error());
                             log.debug("Failed to parse agent from {}: {}",
                                 mdFile, outcome.error().error());
                         }
                     });
            } catch (IOException e) {
                log.debug("Cannot scan agents dir {}: {}", dir, e.getMessage());
            }
        }
        return agents;
    }

    private static String fileIdentity(Path path) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Object fileKey = attrs.fileKey();
            return fileKey != null ? fileKey.toString() : path.toRealPath().toString();
        } catch (IOException | SecurityException _) {

            return null;
        }
    }

    /**
     * Parses a single agent {@code.md} file and returns an {@link
     * BuiltInAgentDefinitions.AgentDefinition}, or {@code null} if the file lacks the required {@code
     * name} / {@code description} fields.
     */
    static BuiltInAgentDefinitions.AgentDefinition parseAgentMarkdown(Path filePath) {
        return parseAgentMarkdownInternal(filePath).definition();
    }

    /**
     * Parse outcome: exactly one of {@code definition} (success) or {@code error} (reportable failure)
     * is non-null, or both are null (silent skip — file is not an agent attempt).
     */
    private record ParseOutcome(BuiltInAgentDefinitions.AgentDefinition definition,
                                ParseError error) {
        static final ParseOutcome SKIP = new ParseOutcome(null, null);
    }

    private static ParseOutcome parseAgentMarkdownInternal(Path filePath) {
        String content;
        try {
            content = Files.readString(filePath);
        } catch (IOException e) {

            log.debug("Cannot read agent file {}: {}", filePath, e.getMessage());
            return ParseOutcome.SKIP;
        }

        Map<String, Object> fm = extractFrontmatter(content);

        if (fm == null) return ParseOutcome.SKIP;

        String name = asString(fm.get("name"));
        String description = asString(fm.get("description"));


        if (StringUtils.isBlank(name)) return ParseOutcome.SKIP;
        if (StringUtils.isBlank(description)) {

            log.debug("Agent file {} missing required 'description' in frontmatter", filePath);
            return new ParseOutcome(null, new ParseError(filePath.toString(),
                "Missing required \"description\" field in frontmatter"));
        }

        try {

            String whenToUse = description.replace("\\n", "\n");

            String color     = asString(fm.get("color"));
            List<String> tools = parseToolList(fm.get("tools"));
            List<String> disallowedTools = parseToolList(fm.get("disallowedTools"));
            List<String> mcpServers = parseMcpServersList(fm.get("mcpServers"));
            // memory: user|project|local — declares the agent's persistent memory
            // dir scope. Anything else (blank / unknown) → null = no memory dir.
            String memory = normaliseMemoryScope(asString(fm.get("memory")));
            String model  = asString(fm.get("model"));
            String effort = asString(fm.get("effort"));
            String permissionMode = asString(fm.get("permissionMode"));
            List<String> skills = parseToolList(fm.get("skills"));
            String initialPrompt = asString(fm.get("initialPrompt"));
            String isolation = asString(fm.get("isolation"));
            // background: true → the agent always runs in the background

            boolean background = asBoolean(fm.get("background"));
            Object maxTurnsRaw = fm.get("maxTurns");
            Integer maxTurns = FrontmatterParser.parsePositiveIntFromFrontmatter(maxTurnsRaw);
            if (fm.containsKey("maxTurns") && maxTurns == null) {
                log.debug("Agent file {} has invalid maxTurns '{}'. Must be a positive integer.",
                    filePath, maxTurnsRaw);
            }

// Markdown body (after the closing "---") is the agent's authored system prompt.
            String systemPrompt = extractBody(content);

            AgentSource source = deriveSource(filePath);

            return new ParseOutcome(BuiltInAgentDefinitions.AgentDefinition
                .builder(name, whenToUse)
                .tools(tools).disallowedTools(disallowedTools).color(color)
                .mcpServers(mcpServers).memory(memory).model(model)
                .systemPrompt(systemPrompt).background(background).source(source)
                .filePath(filePath).maxTurns(maxTurns).effort(effort)
                .permissionMode(permissionMode).skills(skills)
                .initialPrompt(initialPrompt).isolation(isolation).build(), null);
        } catch (RuntimeException e) {

            // name/description checks to 'Unknown parsing error'.
            log.debug("Error parsing agent from {}: {}", filePath, e.getMessage());
            return new ParseOutcome(null,
                new ParseError(filePath.toString(), "Unknown parsing error"));
        }
    }

    /**
     * Everything after the frontmatter's closing {@code ---} delimiter,
     * leading blank lines stripped. {@code null} if there's no frontmatter
     * block or the body is blank (matches "no meaningful body" the same way
     * a built-in agent has no authored prompt).
     */
    static String extractBody(String content) {
        if (!Strings.CS.startsWith(content, "---")) return null;
        int end = content.indexOf("\n---", 3);
        if (end < 0) return null;
        String body = content.substring(end + 4).replaceFirst("^\\n+", "").stripTrailing();
        return StringUtils.isBlank(body) ? null : body;
    }

    /**
     * Resolves the managed/user/project source from the directory roots used by
     * {@link #loadCustomAgents}.
     */
    static AgentSource deriveSource(Path filePath) {
        Path normalized = filePath.toAbsolutePath().normalize();
        Path managed = ClaudePaths.managedRoot().resolve(".claude").resolve("agents")
            .toAbsolutePath().normalize();
        if (normalized.startsWith(managed)) return AgentSource.MANAGED;
        return normalized.startsWith(ClaudePaths.AGENTS_DIR.toAbsolutePath().normalize())
            ? AgentSource.USER : AgentSource.PROJECT;
    }

    private static String normaliseMemoryScope(String raw) {
        if (raw == null) return null;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "user", "project", "local" -> v;
            default -> null;
        };
    }

    // ── Frontmatter parsing ───────────────────────────────────────────────────

    /**
     * Extracts YAML frontmatter key→value pairs from markdown content.
     * Handles scalar strings, inline arrays {@code [a, b, c]}, and YAML
     * sequence blocks ({@code - item}).
     *
     * @return map of parsed fields, or {@code null} if no valid frontmatter block
     */
    static Map<String, Object> extractFrontmatter(String content) {
        if (!Strings.CS.startsWith(content, "---")) return null;
        int end = content.indexOf("\n---", 3);
        if (end < 0) return null;

        String block = content.substring(3, end);
        String[] lines = block.split("\n", -1);
        Map<String, Object> result = new LinkedHashMap<>();
        String currentKey = null;
        List<String> currentList = null;

        for (String line : lines) {
            // YAML sequence item (continuation of list-valued key)
            if ((Strings.CS.startsWith(line, "  ") || Strings.CS.startsWith(line, "\t")) && currentKey != null) {
                String item = line.strip();
                if (Strings.CS.startsWith(item, "- ")) {
                    if (currentList == null) {
                        currentList = new ArrayList<>();
                    }
                    currentList.add(item.substring(2).strip());
                }
                continue;
            }

            // Flush pending list before starting a new key
            if (currentKey != null && currentList != null) {
                result.put(currentKey, Collections.unmodifiableList(currentList));
                currentKey = null;
                currentList = null;
            }

            // Skip blank lines and YAML comments
            String stripped = line.strip();
            if (stripped.isEmpty() || Strings.CS.startsWith(stripped, "#")) continue;

            int colon = line.indexOf(':');
            if (colon <= 0) continue;

            String key   = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();

            if (value.isEmpty()) {
                // Value will come from following sequence lines
                currentKey = key;
            } else {
                result.put(key, parseYamlScalar(value));
            }
        }

        // Flush final pending list
        if (currentKey != null && currentList != null) {
            result.put(currentKey, Collections.unmodifiableList(currentList));
        }

        return result;
    }

    /**
     * Parses a single YAML scalar value: strips quotes, handles inline arrays.
     */
    private static Object parseYamlScalar(String value) {
        // Inline YAML array: [Read, Write, Bash]
        if (Strings.CS.startsWith(value, "[") && Strings.CS.endsWith(value, "]")) {
            String inner = value.substring(1, value.length() - 1);
            return Arrays.stream(inner.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
        }
        // Single-quoted scalar: YAML's only escape is doubled '' -> ' (not produced
        // by AgentFileWriter, which always double-quotes; handled for hand-edited files).
        if (Strings.CS.startsWith(value, "'") && Strings.CS.endsWith(value, "'")) {
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        // Double-quoted scalar
        if (Strings.CS.startsWith(value, "\"") && Strings.CS.endsWith(value, "\"")) {
            return unescapeDoubleQuoted(value.substring(1, value.length() - 1));
        }
        return value;
    }

    /**
     * Reverses YAML double-quoted-scalar escaping for the two sequences {@link
     * AgentFileWriter#formatAsMarkdown} actually produces: {@code \\}→{@code \} and {@code \"}→{@code
     * "}.
     */
    private static String unescapeDoubleQuoted(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                if (next == '\\' || next == '"') {
                    out.append(next);
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    /** Parses tools field — list, inline array, or comma-separated string. */
    @SuppressWarnings("unchecked")
    private static List<String> parseToolList(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof List) return (List<String>) raw;
        String s = raw.toString().strip();
        if (s.isEmpty()) return List.of();
        if (Strings.CS.startsWith(s, "[") && Strings.CS.endsWith(s, "]")) {
            s = s.substring(1, s.length() - 1);
        }
        return Arrays.stream(s.split(","))
            .map(String::strip)
            .filter(t -> !t.isEmpty())
            .toList();
    }

    /**
     * Parses the frontmatter {@code mcpServers} field — supports YAML sequence blocks
     * ({@code - name}), inline arrays ({@code [a, b]}), or a single string.
     * Ignores inline server objects (the compatibility contract {@code AgentMcpServerSpec} allows both
     * a name-reference and an inline definition; Java currently only tracks
     * name references, matching what {@link AgentMcpServerIndex} consumes for
     * the {@code /mcp} "used by" reverse index).
     */
    @SuppressWarnings("unchecked")
    private static List<String> parseMcpServersList(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof List<?> list) {
            List<String> names = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item instanceof String s && !StringUtils.isBlank(s)) names.add(s.strip());
            }
            return List.copyOf(names);
        }
        String s = raw.toString().strip();
        if (s.isEmpty()) return List.of();
        if (Strings.CS.startsWith(s, "[") && Strings.CS.endsWith(s, "]")) {
            s = s.substring(1, s.length() - 1);
        }
        return Arrays.stream(s.split(","))
            .map(String::strip)
            .filter(t -> !t.isEmpty())
            .toList();
    }

    private static String asString(Object o) {
        if (o == null) return null;
        return o.toString();
    }

    /** Preserves compatibility with frontmatter boolean coercion: null / blank / non-"true" → false. */
    private static boolean asBoolean(Object o) {
        if (o == null) return false;
        return Strings.CI.equals("true", o.toString().trim());
    }
}
