package com.claudecode.services.plugins.marketplace;

import com.claudecode.core.io.FileUtils;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent delisted-plugin notifications.
 */
final class FlaggedPluginStore {

    private static final Logger LOG = LoggerFactory.getLogger(FlaggedPluginStore.class);
    private static final Duration SEEN_EXPIRY = Duration.ofHours(48);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Entry(String flaggedAt, String seenAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FileData(Map<String, Entry> plugins) {}

    private final Path file;

    FlaggedPluginStore(Path file) {
        this.file = file;
    }

    synchronized Map<String, Entry> load() {
        Map<String, Entry> entries = read();
        Instant now = Instant.now();
        Map<String, Entry> retained = new LinkedHashMap<>();
        boolean changed = false;
        for (Map.Entry<String, Entry> item : entries.entrySet()) {
            Entry entry = item.getValue();
            if (entry == null || entry.flaggedAt() == null) {
                changed = true;
                continue;
            }
            if (entry.seenAt() != null && expired(entry.seenAt(), now)) {
                changed = true;
                continue;
            }
            retained.put(item.getKey(), entry);
        }
        if (changed) write(retained);
        return Map.copyOf(retained);
    }

    synchronized void add(String pluginId) {
        Map<String, Entry> entries = new LinkedHashMap<>(load());
        entries.put(pluginId, new Entry(Instant.now().toString(), null));
        write(entries);
    }

    synchronized void markSeen(List<String> pluginIds) {
        Map<String, Entry> entries = new LinkedHashMap<>(load());
        String now = Instant.now().toString();
        boolean changed = false;
        for (String pluginId : pluginIds) {
            Entry entry = entries.get(pluginId);
            if (entry != null && entry.seenAt() == null) {
                entries.put(pluginId, new Entry(entry.flaggedAt(), now));
                changed = true;
            }
        }
        if (changed) write(entries);
    }

    synchronized void remove(String pluginId) {
        Map<String, Entry> entries = new LinkedHashMap<>(load());
        if (entries.remove(pluginId) != null) write(entries);
    }

    private Map<String, Entry> read() {
        if (!Files.isRegularFile(file)) return new LinkedHashMap<>();
        try {
            FileData data = JsonUtils.getMapper().readValue(file.toFile(), FileData.class);
            return data == null || data.plugins() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(data.plugins());
        } catch (Exception e) {
            LOG.debug("Failed to read flagged plugins {}: {}", file, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private void write(Map<String, Entry> entries) {
        try {
            FileUtils.atomicReplace(file,
                temp -> JsonUtils.writeJson(temp, new FileData(entries), true));
            FileUtils.trySetOwnerOnlyPermissions(file);
        } catch (Exception e) {
            LOG.warn("Failed to write flagged plugins {}: {}", file, e.getMessage());
        }
    }

    private static boolean expired(String seenAt, Instant now) {
        try {
            return !Instant.parse(seenAt).plus(SEEN_EXPIRY).isAfter(now);
        } catch (Exception _) {
            return false;
        }
    }
}
