package com.claudecode.ui.lanterna.input;

/**
 * Pure permission-mode transition used by the prompt's Shift+Tab gesture.
 */
final class PermissionModeCycle {

    private PermissionModeCycle() {}

    static String next(String currentMode, boolean bypassPermissionsAvailable) {
        return switch (currentMode) {
            case "default" -> "acceptEdits";
            case "acceptEdits" -> "plan";
            case "plan" -> bypassPermissionsAvailable
                ? "bypassPermissions" : "default";
            default -> "default";
        };
    }
}
