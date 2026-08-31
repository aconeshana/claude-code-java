package com.claudecode.cli.daemon.scheduled;

import org.apache.commons.lang3.Strings;

enum ScheduledPermissionMode {
    DONT_ASK("dontAsk"),
    AUTO("auto"),
    DEFAULT("default"),
    ACCEPT_EDITS("acceptEdits"),
    PLAN("plan"),
    BYPASS_PERMISSIONS("bypassPermissions");

    private final String wireValue;

    ScheduledPermissionMode(String wireValue) {
        this.wireValue = wireValue;
    }

    String wireValue() {
        return wireValue;
    }

    static ScheduledPermissionMode parse(String value) {
        for (ScheduledPermissionMode mode : values()) {
            if (Strings.CS.equals(mode.wireValue, value)) return mode;
        }
        throw new IllegalArgumentException("Unsupported permissionMode: " + value);
    }
}
