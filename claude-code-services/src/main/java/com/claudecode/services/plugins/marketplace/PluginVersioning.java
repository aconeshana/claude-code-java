package com.claudecode.services.plugins.marketplace;

import org.apache.commons.lang3.StringUtils;
/**
 * Plugin version calculation for versioned cache paths and update detection.
 */
final class PluginVersioning {

    static final String UNKNOWN_VERSION = "unknown";

    private PluginVersioning() {}






    static String calculate(String manifestVersion, String entryVersion, String gitCommitSha) {
        if (StringUtils.isNotEmpty(manifestVersion)) {
            return manifestVersion;
        }
        if (StringUtils.isNotEmpty(entryVersion)) {
            return entryVersion;
        }
        if (StringUtils.isNotEmpty(gitCommitSha)) {
            return gitCommitSha.substring(0, Math.min(12, gitCommitSha.length()));
        }
        return UNKNOWN_VERSION;
    }
}
