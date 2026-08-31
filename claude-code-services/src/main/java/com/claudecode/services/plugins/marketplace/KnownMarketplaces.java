package com.claudecode.services.plugins.marketplace;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * a map of marketplace name to source + cache metadata.
 */
public record KnownMarketplaces(@JsonValue Map<String, Entry> entries) {

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public KnownMarketplaces(Map<String, Entry> entries) {
        // Insertion-ordered unmodifiable copy (Map.copyOf would scramble key order,
        // producing noisy diffs in the JSON file on every rewrite).
        this.entries = entries == null
            ? Map.of()
            : Collections.unmodifiableMap(orderedCopy(entries));
    }

    /** One registered marketplace. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(
        MarketplaceSource source,
        String installLocation,
        String lastUpdated,
        Boolean autoUpdate) {}

    public static KnownMarketplaces empty() {
        return new KnownMarketplaces(Map.of());
    }

    public Entry get(String name) {
        return entries.get(name);
    }

    public boolean contains(String name) {
        return entries.containsKey(name);
    }

    /** Returns a copy with {@code name} added or replaced. */
    public KnownMarketplaces with(String name, Entry entry) {
        Map<String, Entry> next = orderedCopy(entries);
        next.put(name, entry);
        return new KnownMarketplaces(next);
    }

    /** Returns a copy without {@code name}. */
    public KnownMarketplaces without(String name) {
        Map<String, Entry> next = orderedCopy(entries);
        next.remove(name);
        return new KnownMarketplaces(next);
    }

    private static Map<String, Entry> orderedCopy(Map<String, Entry> source) {
        return new LinkedHashMap<>(source);
    }
}
