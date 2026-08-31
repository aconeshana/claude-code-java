package com.claudecode.core.model;

/**
 * Bare permission-mode identity for core's internal branches (e.g.
 */
public enum PermissionModeKind {
    DEFAULT, PLAN, ACCEPT_EDITS, BYPASS_PERMISSIONS, DONT_ASK, AUTO;


    public String wireValue() {
        return switch (this) {
            case DEFAULT -> "default";
            case PLAN -> "plan";
            case ACCEPT_EDITS -> "acceptEdits";
            case BYPASS_PERMISSIONS -> "bypassPermissions";
            case DONT_ASK -> "dontAsk";
            case AUTO -> "auto";
        };
    }
}
