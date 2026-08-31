package com.claudecode.services.plugins.marketplace;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Plugin manifest file.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PluginManifest(
    String name,
    String version,
    String description,
    PluginAuthor author,
    String homepage,
    String repository,
    String license,
    List<String> keywords,
    JsonNode dependencies,
    JsonNode commands,
    JsonNode agents,
    JsonNode skills,
    JsonNode workflows,
    JsonNode outputStyles,
    JsonNode hooks,
    List<PluginChannel> channels,
    JsonNode mcpServers,
    JsonNode lspServers,
    JsonNode settings,
    Map<String, UserConfigOption> userConfig) {

    public PluginManifest {
        channels = channels == null ? List.of() : List.copyOf(channels);
        userConfig = userConfig == null ? Map.of() : Map.copyOf(userConfig);
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static final class Builder {
        private String name;
        private String version;
        private String description;
        private PluginAuthor author;
        private String homepage;
        private String repository;
        private String license;
        private List<String> keywords;
        private JsonNode dependencies;
        private JsonNode commands;
        private JsonNode agents;
        private JsonNode skills;
        private JsonNode workflows;
        private JsonNode outputStyles;
        private JsonNode hooks;
        private List<PluginChannel> channels = List.of();
        private JsonNode mcpServers;
        private JsonNode lspServers;
        private JsonNode settings;
        private Map<String, UserConfigOption> userConfig = Map.of();

        private Builder(String name) { this.name = name; }

        private Builder(PluginManifest source) {
            name = source.name;
            version = source.version;
            description = source.description;
            author = source.author;
            homepage = source.homepage;
            repository = source.repository;
            license = source.license;
            keywords = source.keywords;
            dependencies = source.dependencies;
            commands = source.commands;
            agents = source.agents;
            skills = source.skills;
            workflows = source.workflows;
            outputStyles = source.outputStyles;
            hooks = source.hooks;
            channels = source.channels;
            mcpServers = source.mcpServers;
            lspServers = source.lspServers;
            settings = source.settings;
            userConfig = source.userConfig;
        }

        public Builder name(String value) { name = value; return this; }
        public Builder version(String value) { version = value; return this; }
        public Builder description(String value) { description = value; return this; }
        public Builder author(PluginAuthor value) { author = value; return this; }
        public Builder homepage(String value) { homepage = value; return this; }
        public Builder repository(String value) { repository = value; return this; }
        public Builder license(String value) { license = value; return this; }
        public Builder keywords(List<String> value) { keywords = value; return this; }
        public Builder dependencies(JsonNode value) { dependencies = value; return this; }
        public Builder commands(JsonNode value) { commands = value; return this; }
        public Builder agents(JsonNode value) { agents = value; return this; }
        public Builder skills(JsonNode value) { skills = value; return this; }
        public Builder workflows(JsonNode value) { workflows = value; return this; }
        public Builder outputStyles(JsonNode value) { outputStyles = value; return this; }
        public Builder hooks(JsonNode value) { hooks = value; return this; }
        public Builder channels(List<PluginChannel> value) { channels = value; return this; }
        public Builder mcpServers(JsonNode value) { mcpServers = value; return this; }
        public Builder lspServers(JsonNode value) { lspServers = value; return this; }
        public Builder settings(JsonNode value) { settings = value; return this; }
        public Builder userConfig(Map<String, UserConfigOption> value) { userConfig = value; return this; }

        public PluginManifest build() {
            return new PluginManifest(name, version, description, author, homepage,
                repository, license, keywords, dependencies, commands, agents,
                skills, workflows, outputStyles, hooks, channels, mcpServers,
                lspServers, settings, userConfig);
        }
    }

    /**
     * Extra command sources declared in the manifest, as path strings.
     */
    public List<String> commandPaths() {
        List<String> paths = stringOrArray(commands);
        if (commands != null && commands.isObject()) {
            for (JsonNode meta : commands) {
                JsonNode source = meta.get("source");
                if (source != null && source.isTextual()) {
                    paths.add(source.asText());
                }
            }
        }
        return List.copyOf(paths);
    }

    /** Extra agent file paths declared in the manifest (string | array of strings). */
    public List<String> agentPaths() {
        return List.copyOf(stringOrArray(agents));
    }

    /** Extra skill directory paths declared in the manifest (string | array of strings). */
    public List<String> skillPaths() {
        return List.copyOf(stringOrArray(skills));
    }

    /** Extra workflow file/directory paths (string | array of strings). */
    public List<String> workflowPaths() {
        return List.copyOf(stringOrArray(workflows));
    }

    /** Extra output-style paths declared in the manifest (string | array of strings). */
    public List<String> outputStylePaths() {
        return List.copyOf(stringOrArray(outputStyles));
    }

    /**
     * Hook file paths declared in the manifest.
     */
    public List<String> hookPaths() {
        List<String> paths = new ArrayList<>();
        if (hooks == null || hooks.isNull()) {
            return List.of();
        }
        if (hooks.isTextual()) {
            paths.add(hooks.asText());
        } else if (hooks.isArray()) {
            for (JsonNode item : hooks) {
                if (item.isTextual()) {
                    paths.add(item.asText());
                }
            }
        }
        return List.copyOf(paths);
    }

    /**
     * Normalised dependency references: bare {@code name} or {@code name@marketplace}.
     * Ports {@code DependencyRefSchema}'s transform — a trailing {@code @^version}
     * constraint is silently stripped and object form is flattened, so downstream
     * code never sees versions or objects.
     */
    public List<String> dependencyRefs() {
        if (dependencies == null || !dependencies.isArray()) {
            return List.of();
        }
        List<String> refs = new ArrayList<>();
        for (JsonNode dep : dependencies) {
            if (dep.isTextual()) {
                refs.add(dep.asText().replaceFirst("@\\^[^@]*$", ""));
            } else if (dep.isObject() && dep.path("name").isTextual()) {
                String depName = dep.get("name").asText();
                JsonNode marketplace = dep.get("marketplace");
                refs.add(marketplace != null && marketplace.isTextual()
                    ? depName + "@" + marketplace.asText()
                    : depName);
            }
        }
        return List.copyOf(refs);
    }

    private static List<String> stringOrArray(JsonNode node) {
        List<String> paths = new ArrayList<>();
        if (node == null || node.isNull()) {
            return paths;
        }
        if (node.isTextual()) {
            paths.add(node.asText());
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                if (item.isTextual()) {
                    paths.add(item.asText());
                }
            }
        }
        return paths;
    }
}
