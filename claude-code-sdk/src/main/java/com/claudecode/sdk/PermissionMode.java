package com.claudecode.sdk;

/** Permission modes exposed by the official Agent SDK Query contract. */
public enum PermissionMode {
    DEFAULT("default"),
    ACCEPT_EDITS("acceptEdits"),
    BYPASS_PERMISSIONS("bypassPermissions"),
    PLAN("plan"),
    DONT_ASK("dontAsk");

    private final String wireValue;

    PermissionMode(String wireValue) { this.wireValue = wireValue; }
    public String wireValue() { return wireValue; }
}
