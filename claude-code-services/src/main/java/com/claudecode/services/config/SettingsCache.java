package com.claudecode.services.config;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Package-private parsed-tree cache for settings readers.
 */
final class SettingsCache {

    record CachedTree(FileTime modified, long size, JsonNode tree) {}

    static final ConcurrentHashMap<Path, CachedTree> TREES = new ConcurrentHashMap<>();

    private SettingsCache() {}

    static void clearTrees() {
        TREES.clear();
    }
}
