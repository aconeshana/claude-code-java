package com.claudecode.core.pokemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.serialization.JsonUtils;
import org.apache.commons.lang3.StringUtils;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Persisted UI-only Pokémon shown in the Java welcome banner.
 */
@Explanation("UI-only Pokémon identity displayed in the Java welcome banner.")
public record PokemonProfile(
        String rootName,
        String name,
        Rarity rarity,
        boolean shiny,
        Map<Stat, Integer> stats,
        long hatchedAt,
        long experienceTokens,
        String evolutionChoice) {

    public enum Rarity { COMMON, UNCOMMON, RARE, EPIC, LEGENDARY }

    public enum Stat { DEBUGGING, PATIENCE, CHAOS, WISDOM, SNARK }

    public PokemonProfile {
        name = StringUtils.isBlank(name) ? "pikachu" : name;
        PokemonRoster.Chain chain = PokemonRoster.chainContaining(name);
        rootName = StringUtils.isBlank(rootName)
            ? (chain != null ? chain.root() : name) : rootName;
        rarity = rarity == null ? Rarity.COMMON : rarity;
        stats = Map.copyOf(stats == null ? Map.of() : stats);
        experienceTokens = Math.max(0L, experienceTokens);
    }

    /** Backward-compatible constructor for pre-evolution call sites. */
    public PokemonProfile(String name, Rarity rarity, boolean shiny,
                          Map<Stat, Integer> stats, long hatchedAt) {
        this(null, name, rarity, shiny, stats, hatchedAt, 0L, null);
    }

    public String displayName() {
        String[] parts = name.split("-");
        StringBuilder display = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (!display.isEmpty()) display.append(' ');
            display.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return display.toString();
    }

    public String stars() {
        return "★".repeat(rarity.ordinal() + 1);
    }

    /**
     * Projects this profile explicitly instead of relying on Jackson record
     * introspection, which is unavailable without per-record reflection metadata
     * in a GraalVM native image.
     */
    @Explanation("Explicit JSON projection keeps Pokémon persistence identical on the JVM and in native images.")
    public ObjectNode toJson() {
        ObjectNode node = JsonUtils.getMapper().createObjectNode();
        node.put("rootName", rootName);
        node.put("name", name);
        node.put("rarity", rarity.name());
        node.put("shiny", shiny);
        ObjectNode statsNode = node.putObject("stats");
        for (Stat stat : Stat.values()) {
            statsNode.put(stat.name(), stats.getOrDefault(stat, 0));
        }
        node.put("hatchedAt", hatchedAt);
        node.put("experienceTokens", experienceTokens);
        if (evolutionChoice == null) node.putNull("evolutionChoice");
        else node.put("evolutionChoice", evolutionChoice);
        return node;
    }

    public static PokemonProfile fromJson(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        try {
            String name = node.path("name").asText("");
            Rarity rarity = Rarity.valueOf(
                node.path("rarity").asText("COMMON").toUpperCase(Locale.ROOT));
            var stats = new EnumMap<Stat, Integer>(Stat.class);
            JsonNode statsNode = node.path("stats");
            for (Stat stat : Stat.values()) {
                stats.put(stat, statsNode.path(stat.name()).asInt(0));
            }
            return new PokemonProfile(node.path("rootName").asText(""), name, rarity,
                node.path("shiny").asBoolean(false), stats,
                node.path("hatchedAt").asLong(0L),
                node.path("experienceTokens").asLong(0L),
                node.path("evolutionChoice").asText(null));
        } catch (RuntimeException _) {
            return null;
        }
    }
}
