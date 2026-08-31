package com.claudecode.core.pokemon;

import com.claudecode.core.annotation.Explanation;
import org.apache.commons.lang3.Strings;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resource-backed Pokémon evolution chains used by the welcome companion.
 */
@Explanation("Resource-backed Pokémon evolution chains for the Java welcome banner.")
public final class PokemonRoster {

    private static final String ENGLISH_RESOURCE = "/pokemon.txt";
    private static final String CHINESE_RESOURCE = "/pokemon_zh.txt";
    public static final List<Chain> CHAINS = loadChains();
    public static final List<String> STARTER_NAMES = CHAINS.stream().map(Chain::root).toList();
    public static final List<Entry> ENTRIES = flattenEntries();
    public static final List<String> NAMES = ENTRIES.stream().map(Entry::id).toList();
    private static final Map<String, Chain> CHAIN_BY_ROOT = indexRoots();
    private static final Map<String, Chain> CHAIN_BY_SPECIES = indexSpecies();
    private static final Map<String, Entry> ENTRY_BY_ID = indexEntries();

    private PokemonRoster() {}

    public static boolean contains(String id) {
        return id != null && ENTRY_BY_ID.containsKey(id);
    }

    public static String chineseName(String id) {
        Entry entry = id == null ? null : ENTRY_BY_ID.get(id);
        return entry == null ? "" : entry.chineseName();
    }

    public static Chain chainForRoot(String root) {
        return root == null ? null : CHAIN_BY_ROOT.get(root);
    }

    public static Chain chainContaining(String species) {
        return species == null ? null : CHAIN_BY_SPECIES.get(species);
    }

    private static List<Chain> loadChains() {
        List<ParsedLine> english = readLines(ENGLISH_RESOURCE);
        List<ParsedLine> chinese = readLines(CHINESE_RESOURCE);
        if (english.size() != chinese.size()) {
            throw new IllegalStateException("Pokémon chain counts differ: "
                + ENGLISH_RESOURCE + "=" + english.size() + ", "
                + CHINESE_RESOURCE + "=" + chinese.size());
        }
        if (english.isEmpty()) throw new IllegalStateException("Empty " + ENGLISH_RESOURCE);

        List<Chain> chains = new ArrayList<>(english.size());
        HashSet<String> allIds = new HashSet<>();
        HashSet<String> allChinese = new HashSet<>();
        for (int index = 0; index < english.size(); index++) {
            ParsedLine ids = english.get(index);
            ParsedLine names = chinese.get(index);
            if (ids.linear().size() != names.linear().size()
                    || ids.branches().size() != names.branches().size()) {
                throw new IllegalStateException("Pokémon chain shape differs at data line " + (index + 1));
            }
            for (String id : ids.allNodes()) {
                if (!id.matches("[a-z0-9-]+")) {
                    throw new IllegalStateException("Invalid Pokémon id: " + id);
                }
                if (!allIds.add(id)) throw new IllegalStateException("Duplicate Pokémon id: " + id);
            }
            for (String name : names.allNodes()) {
                if (!allChinese.add(name)) throw new IllegalStateException("Duplicate Pokémon name: " + name);
            }
            chains.add(new Chain(ids.linear(), ids.branches(), names.linear(), names.branches()));
        }
        return List.copyOf(chains);
    }

    private static List<ParsedLine> readLines(String resource) {
        InputStream stream = PokemonRoster.class.getResourceAsStream(resource);
        if (stream == null) throw new IllegalStateException("Missing " + resource);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines().map(String::trim)
                .filter(line -> !line.isEmpty() && !Strings.CS.startsWith(line, "#"))
                .map(line -> parseLine(resource, line))
                .toList();
        } catch (IOException error) {
            throw new IllegalStateException("Failed to read " + resource, error);
        }
    }

    private static ParsedLine parseLine(String resource, String line) {
        String[] parts = line.split("\\s*->\\s*");
        List<String> linear = new ArrayList<>();
        List<String> branches = List.of();
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index].trim();
            if (Strings.CS.contains(part, "|")) {
                if (index != parts.length - 1 || index == 0) {
                    throw new IllegalStateException("Branches must be terminal in " + resource + ": " + line);
                }
                branches = List.of(part.split("\\s*\\|\\s*"));
            } else {
                linear.add(part);
            }
        }
        if (linear.isEmpty() || linear.stream().anyMatch(String::isBlank)
                || branches.stream().anyMatch(String::isBlank)) {
            throw new IllegalStateException("Invalid chain in " + resource + ": " + line);
        }
        return new ParsedLine(List.copyOf(linear), List.copyOf(branches));
    }

    private static List<Entry> flattenEntries() {
        List<Entry> entries = new ArrayList<>();
        for (Chain chain : CHAINS) {
            for (int index = 0; index < chain.linear().size(); index++) {
                entries.add(new Entry(chain.linear().get(index), chain.chineseLinear().get(index)));
            }
            for (int index = 0; index < chain.branches().size(); index++) {
                entries.add(new Entry(chain.branches().get(index), chain.chineseBranches().get(index)));
            }
        }
        return List.copyOf(entries);
    }

    private static Map<String, Chain> indexRoots() {
        Map<String, Chain> result = new LinkedHashMap<>();
        for (Chain chain : CHAINS) result.put(chain.root(), chain);
        return Map.copyOf(result);
    }

    private static Map<String, Chain> indexSpecies() {
        Map<String, Chain> result = new HashMap<>();
        for (Chain chain : CHAINS) {
            for (String species : chain.allSpecies()) result.put(species, chain);
        }
        return Map.copyOf(result);
    }

    private static Map<String, Entry> indexEntries() {
        Map<String, Entry> result = new HashMap<>();
        for (Entry entry : ENTRIES) result.put(entry.id(), entry);
        return Map.copyOf(result);
    }

    private record ParsedLine(List<String> linear, List<String> branches) {
        List<String> allNodes() {
            List<String> nodes = new ArrayList<>(linear);
            nodes.addAll(branches);
            return nodes;
        }
    }

    public record Chain(List<String> linear, List<String> branches,
                        List<String> chineseLinear, List<String> chineseBranches) {
        public Chain {
            linear = List.copyOf(linear);
            branches = List.copyOf(branches);
            chineseLinear = List.copyOf(chineseLinear);
            chineseBranches = List.copyOf(chineseBranches);
        }

        public String root() { return linear.getFirst(); }
        public boolean branching() { return !branches.isEmpty(); }
        public boolean canEvolve() { return linear.size() > 1 || branching(); }

        public List<String> allSpecies() {
            List<String> species = new ArrayList<>(linear);
            species.addAll(branches);
            return List.copyOf(species);
        }
    }

    public record Entry(String id, String chineseName) {}
}
