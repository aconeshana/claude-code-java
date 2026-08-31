package com.claudecode.services.plugins;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.process.ExecutableFinder;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Checks whether an LSP server launch command resolves to an installed binary.
 */
final class BinaryCheck {

    private static final ConcurrentHashMap<String, Boolean> CACHE = new ConcurrentHashMap<>();

    private BinaryCheck() {}

    static boolean isBinaryInstalled(String command) {
        if (StringUtils.isBlank(command)) {
            return false;
        }
        String trimmed = command.trim();
        return CACHE.computeIfAbsent(trimmed,
            key -> ExecutableFinder.find(key).isPresent());
    }

    static void clearCache() { CACHE.clear(); }
}
