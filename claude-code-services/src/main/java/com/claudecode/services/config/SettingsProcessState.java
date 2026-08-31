package com.claudecode.services.config;

import com.claudecode.permissions.RuleSource;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Package-private process state for dynamic settings sources.
 */
final class SettingsProcessState {

    static final Object LOCK = new Object();
    static final ObjectNode EMPTY = JsonUtils.getMapper().createObjectNode();

    static volatile ObjectNode flagSettingsFile = EMPTY.deepCopy();
    static volatile boolean flagSettingsFileLoaded = true;
    static volatile FileStamp flagSettingsFileStamp = FileStamp.MISSING;
    static volatile ObjectNode flagSettingsInline = EMPTY.deepCopy();
    static volatile ObjectNode pluginSettingsBase = EMPTY.deepCopy();
    static volatile Path flagSettingsPath;
    static final CopyOnWriteArrayList<Runnable> FLAG_SETTINGS_LISTENERS =
        new CopyOnWriteArrayList<>();

    static volatile List<RuleSource> enabledOrder = List.of(
        RuleSource.USER_SETTINGS,
        RuleSource.PROJECT_SETTINGS,
        RuleSource.LOCAL_SETTINGS,
        RuleSource.FLAG_SETTINGS,
        RuleSource.POLICY_SETTINGS);
    /**
     * Explicit settings root used when the process has not installed an original cwd yet.
     * Production startup sets {@link com.claudecode.core.state.CwdState}; retaining this fallback
     * keeps the package-level source-selection seam deterministic for embedders and tests.
     */
    static volatile Path configuredSettingsRoot;
    static volatile List<String> sessionAdditionalDirectories = List.of();

    record FileStamp(FileTime modified, long size) {
        static final FileStamp MISSING = new FileStamp(null, -1L);
    }

    private SettingsProcessState() {}
}
