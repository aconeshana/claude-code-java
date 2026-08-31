package com.claudecode.sdk;

import java.util.Arrays;


public enum ExitReason {
    CLEAR("clear"),
    RESUME("resume"),
    LOGOUT("logout"),
    PROMPT_INPUT_EXIT("prompt_input_exit"),
    OTHER("other"),
    BYPASS_PERMISSIONS_DISABLED("bypass_permissions_disabled");

    private final String wireValue;
    ExitReason(String wireValue) { this.wireValue = wireValue; }
    public String wireValue() { return wireValue; }
    public static ExitReason fromWire(String value) {
        return Arrays.stream(values()).filter(reason -> reason.wireValue.equals(value)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown exit reason: " + value));
    }
}
