package com.claudecode.services.config;

import com.claudecode.permissions.RuleSource;
import java.nio.file.Path;

/**
 * Callback for {@link SettingsHotReloader} to notify a subscriber that one of the watched settings
 * files changed on disk.
 */
@FunctionalInterface
public interface SettingsChangeListener {

    /**
     * Invoked when a settings file changed. The listener typically re-reads
     * all settings files (including managed policy/drop-ins) from disk since a
     * change to any one may affect the merged view.
     *
     * @param source which file changed
     */
    void onChange(RuleSource source);

    /**
     * Path-aware callback used by the live reload orchestrator. The default
     * delegates to the source-only API so existing embedders and tests keep
     * their binary/source-compatible lambda shape.
     */
    default void onChange(RuleSource source, Path path) {
        onChange(source);
    }
}
