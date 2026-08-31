package com.claudecode.tools.skills;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.util.FrontmatterParser;
import com.claudecode.mcp.McpConnectionView;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovers direct skills exposed through the MCP skills extension.
 */
public final class McpSkillDiscovery {

    static final String EXTENSION = "io.modelcontextprotocol/skills";
    static final String INDEX_URI = "skill://index.json";
    private static final int MAX_INDEX_CHARS = 1_000_000;
    private static final int MAX_SKILLS = 100;
    private static final Logger LOG = LoggerFactory.getLogger(McpSkillDiscovery.class);

    private final Path cacheRoot;
    private final FrontmatterParser frontmatterParser = FrontmatterParser.shared();

    public McpSkillDiscovery(Path claudeHome) {
        this.cacheRoot = claudeHome.resolve("mcp-skill-archives");
    }

    /** Fetches the current direct-skill snapshot for one connected server. */
    public List<Skill> fetch(McpConnectionView connection) {
        return fetch(connection, () -> { });
    }

    /**
     * Fetches skills while allowing the MCP lifecycle owner to interleave its startup resource-catalog
     * prefetch after the index probe.
     */
    public List<Skill> fetch(McpConnectionView connection, Runnable afterIndexProbe) {
        Objects.requireNonNull(afterIndexProbe, "afterIndexProbe");
        if (connection == null) {
            return List.of();
        }
        if (!supportsSkills(connection.getServerCapabilities())) {
            afterIndexProbe.run();
            return List.of();
        }
        String indexText = readText(connection, INDEX_URI);
        afterIndexProbe.run();
        if (indexText == null || indexText.length() > MAX_INDEX_CHARS) return List.of();

        JsonNode root;
        try {
            root = JsonUtils.getMapper().readTree(indexText);
        } catch (Exception _) {
            LOG.warn("MCP skill index from '{}' is invalid JSON", connection.getServerId());
            return List.of();
        }
        JsonNode entries = root == null ? null : root.get("skills");
        if (entries == null || !entries.isArray()) return List.of();

        List<Skill> skills = new ArrayList<>();
        for (JsonNode entry : entries) {
            if (skills.size() >= MAX_SKILLS) break;
            String name = text(entry.path("frontmatter").get("name"));
            String url = text(entry.get("url"));
            if (name == null || url == null) continue;
            Skill skill = fetchDirect(connection, name, url, text(entry.get("digest")));
            if (skill != null) skills.add(skill);
        }
        return List.copyOf(skills);
    }

    private Skill fetchDirect(
            McpConnectionView connection, String indexName, String url, String declaredDigest) {
        String markdown = readText(connection, url);
        if (markdown == null || markdown.length() > MAX_INDEX_CHARS) return null;
        String actualDigest = sha256(markdown.getBytes(StandardCharsets.UTF_8));
        String expected = normalizeDigest(declaredDigest);
        if (expected != null && !expected.equals(actualDigest)) {
            LOG.warn("MCP skill '{}' digest mismatch from '{}'", indexName,
                connection.getServerId());
            return null;
        }

        Path skillFile;
        try {
            skillFile = cache(connection.getServerId(), indexName, url,
                markdown, actualDigest, expected);
        } catch (IOException e) {
            LOG.warn("Failed to cache MCP skill '{}:{}'", connection.getServerId(), indexName, e);
            return null;
        }

        FrontmatterParser.ParseResult parsed = frontmatterParser.parse(markdown);
        String description = parsed.description();
        if (StringUtils.isBlank(description)) {
            description = parsed.body().lines().map(String::trim)
                .filter(line -> !line.isEmpty())
                .map(line -> line.replaceFirst("^#+\\s*", ""))
                .findFirst().orElse("");
        }
        String server = normalizeSegment(connection.getServerId());
        String skillName = normalizeSegment(indexName);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", server + ":" + skillName);
        metadata.put("description", description);
        metadata.put("source", "mcp");
        metadata.put("loadedFrom", "mcp");
        metadata.put("isMcp", true);
        // Attribution belongs to every MCP skill, including direct resource
        // URLs that do not use the optional .../SKILL.md directory convention.
        metadata.put("mcpServer", connection.getServerId());
        String resourceRoot = resourceRoot(url);
        if (resourceRoot != null) {
            metadata.put("mcpResourceRoot", resourceRoot);
            metadata.put("mcpDirectoryRead", supportsDirectoryRead(
                connection.getServerCapabilities()));
        }
        return new Skill(
            server + ":" + skillName,
            description,
            List.of(),
            parsed.body(),
            skillFile,
            Skill.SkillSource.MCP,
            null,
            null,
            null,
            Map.copyOf(metadata));
    }

    private Path cache(
            String server, String name, String url, String markdown,
            String cacheKey, String declaredDigest) throws IOException {
        String slug = normalizeSegment(server) + "--" + sanitizeFileSegment(name)
            + "--" + sha256((server + "\0" + url).getBytes(StandardCharsets.UTF_8))
                .substring(0, 8);
        Path slugDir = cacheRoot.resolve(slug);
        Path keyDir = slugDir.resolve(cacheKey);
        Path skillFile = keyDir.resolve("SKILL.md");
        Files.createDirectories(keyDir);
        if (!Files.isRegularFile(skillFile)) Files.writeString(skillFile, markdown);

        ObjectNode meta = JsonUtils.getMapper().createObjectNode();
        meta.put("url", url);
        meta.put("cacheKey", cacheKey);
        if (declaredDigest != null) meta.put("declaredDigest", declaredDigest);
        meta.put("fetchedAt", Instant.now().toEpochMilli());
        Files.createDirectories(slugDir);
        Files.writeString(slugDir.resolve("meta.json"),
            JsonUtils.getMapper().writeValueAsString(meta));
        return skillFile;
    }

    private static String readText(McpConnectionView connection, String uri) {
        try {
            ObjectNode params = JsonUtils.getMapper().createObjectNode();
            params.put("uri", uri);
            JsonNode result = connection.sendRequest("resources/read", params);
            JsonNode contents = result == null ? null : result.get("contents");
            if (contents == null || !contents.isArray()) return null;
            for (JsonNode content : contents) {
                JsonNode text = content.get("text");
                if (text != null && text.isTextual()) return text.asText();
            }
            return null;
        } catch (RuntimeException e) {
            LOG.warn("Failed to read MCP skill resource '{}' from '{}'", uri,
                connection.getServerId(), e);
            return null;
        }
    }

    static boolean supportsSkills(JsonNode capabilities) {
        return extension(capabilities) != null;
    }

    public static boolean supportsDirectoryRead(JsonNode capabilities) {
        JsonNode extension = extension(capabilities);
        return extension != null && extension.path("directoryRead").asBoolean(false);
    }

    private static JsonNode extension(JsonNode capabilities) {
        if (capabilities == null || !capabilities.isObject()) return null;
        JsonNode extensions = capabilities.get("extensions");
        if (extensions == null || !extensions.isObject()) return null;
        return extensions.get(EXTENSION);
    }

    private static String resourceRoot(String url) {
        if (url == null || !Strings.CS.endsWith(url, "/SKILL.md")) return null;
        String root = url.substring(0, url.length() - "/SKILL.md".length());
        return root.matches("(?i)^[a-z][a-z0-9+.-]*://.+") ? root : null;
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() && !node.asText().isEmpty()
            ? node.asText() : null;
    }

    private static String normalizeSegment(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9._-]+", "-")
            .replaceAll("^-+|-+$", "");
    }

    private static String sanitizeFileSegment(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    private static String normalizeDigest(String digest) {
        if (digest == null) return null;
        String normalized = digest.trim().toLowerCase(Locale.ROOT);
        if (Strings.CS.startsWith(normalized, "sha256:")) normalized = normalized.substring(7);
        return normalized.matches("[0-9a-f]{64}") ? normalized : null;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
