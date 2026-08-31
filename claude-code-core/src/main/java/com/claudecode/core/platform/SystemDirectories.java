package com.claudecode.core.platform;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cross-platform user directory variables exposed to MCPB bundles.
 *
 * <ul>
 *   <li> —
 *       {@code getSystemDirectories}, including USERPROFILE and Linux/WSL XDG
 *       overrides.</li>
 * </ul>
 */
public final class SystemDirectories {
    private SystemDirectories() {}

    public static Map<String, String> resolve() {
        return resolve(System.getenv(), System.getProperty("user.home"), Platform.CURRENT, Platform.IS_WSL);
    }

    static Map<String, String> resolve(Map<String, String> env, String home,
                                       Platform platform, boolean wsl) {
        String profile = platform == Platform.WIN32 ? env.getOrDefault("USERPROFILE", home) : home;
        Map<String, String> result = new LinkedHashMap<>();
        result.put("HOME", home);
        if (platform == Platform.LINUX || wsl) {
            result.put("DESKTOP", env.getOrDefault("XDG_DESKTOP_DIR", Path.of(home, "Desktop").toString()));
            result.put("DOCUMENTS", env.getOrDefault("XDG_DOCUMENTS_DIR", Path.of(home, "Documents").toString()));
            result.put("DOWNLOADS", env.getOrDefault("XDG_DOWNLOAD_DIR", Path.of(home, "Downloads").toString()));
        } else {
            result.put("DESKTOP", Path.of(profile, "Desktop").toString());
            result.put("DOCUMENTS", Path.of(profile, "Documents").toString());
            result.put("DOWNLOADS", Path.of(profile, "Downloads").toString());
        }
        return result;
    }
}
