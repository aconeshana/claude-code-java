package com.claudecode.runtime.query;


public enum FastModeRuntimeState {
    OFF("off"),
    COOLDOWN("cooldown"),
    ON("on");

    private final String wireValue;

    FastModeRuntimeState(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
