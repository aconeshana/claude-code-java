package com.claudecode.services.plugins.marketplace;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Installation scope of a plugin (V2 ).
 */
public enum PluginScope {
    MANAGED("managed"),
    USER("user"),
    PROJECT("project"),
    LOCAL("local");

    private final String wire;

    PluginScope(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static PluginScope fromWire(String value) {
        for (PluginScope scope : values()) {
            if (scope.wire.equals(value)) {
                return scope;
            }
        }
        throw new IllegalArgumentException("Unknown plugin scope: " + value);
    }
}
