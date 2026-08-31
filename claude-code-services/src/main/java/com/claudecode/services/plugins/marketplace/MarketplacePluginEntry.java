package com.claudecode.services.plugins.marketplace;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * One plugin entry inside a marketplace manifest ('s {@code plugins[]}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MarketplacePluginEntry(
    String name,
    PluginSource source,
    String description,
    String version,
    PluginAuthor author,
    String category,
    List<String> tags,
    Boolean strict,
    JsonNode commands,
    JsonNode agents,
    JsonNode skills,
    JsonNode hooks,
    List<PluginChannel> channels,
    JsonNode mcpServers,
    JsonNode lspServers,
    String homepage,
    String repository) {

    public static Builder builder(String name, PluginSource source) {
        return new Builder(name, source);
    }

    public static final class Builder {
        private final String name;
        private final PluginSource source;
        private String description;
        private String version;
        private PluginAuthor author;
        private String category;
        private List<String> tags = List.of();
        private Boolean strict;
        private JsonNode commands;
        private JsonNode agents;
        private JsonNode skills;
        private JsonNode hooks;
        private List<PluginChannel> channels = List.of();
        private JsonNode mcpServers;
        private JsonNode lspServers;
        private String homepage;
        private String repository;

        private Builder(String name, PluginSource source) {
            this.name = name;
            this.source = source;
        }

        public Builder description(String value) { description = value; return this; }
        public Builder version(String value) { version = value; return this; }
        public Builder author(PluginAuthor value) { author = value; return this; }
        public Builder category(String value) { category = value; return this; }
        public Builder tags(List<String> value) { tags = value; return this; }
        public Builder strict(Boolean value) { strict = value; return this; }
        public Builder commands(JsonNode value) { commands = value; return this; }
        public Builder agents(JsonNode value) { agents = value; return this; }
        public Builder skills(JsonNode value) { skills = value; return this; }
        public Builder hooks(JsonNode value) { hooks = value; return this; }
        public Builder channels(List<PluginChannel> value) { channels = value; return this; }
        public Builder mcpServers(JsonNode value) { mcpServers = value; return this; }
        public Builder lspServers(JsonNode value) { lspServers = value; return this; }
        public Builder homepage(String value) { homepage = value; return this; }
        public Builder repository(String value) { repository = value; return this; }

        public MarketplacePluginEntry build() {
            return new MarketplacePluginEntry(name, source, description, version,
                author, category, tags, strict, commands, agents, skills, hooks,
                channels, mcpServers, lspServers, homepage, repository);
        }
    }


    public boolean strictOrDefault() {
        return strict == null || strict;
    }

    /**
     * Fallback manifest synthesized from the marketplace entry, used when the plugin directory has no.
     */
    public PluginManifest toFallbackManifest() {
        return PluginManifest.builder(name)
            .version(version)
            .description(description)
            .author(author)
            .homepage(homepage)
            .repository(repository)
            .commands(commands)
            .agents(agents)
            .skills(skills)
            .hooks(hooks)
            .channels(channels)
            .mcpServers(mcpServers)
            .lspServers(lspServers)
            .build();
    }
}
