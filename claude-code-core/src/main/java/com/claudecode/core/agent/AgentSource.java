package com.claudecode.core.agent;

/**
 * Where an agent definition came from.
 */
public enum AgentSource {
    BUILT_IN, MANAGED, USER, PROJECT, FLAG_SETTINGS, PLUGIN;

    public String displayName() {
        return switch (this) {
            case BUILT_IN -> "Built-in";
            case MANAGED -> "Managed";
            case USER -> "User";
            case PROJECT -> "Project";
            case FLAG_SETTINGS -> "CLI";
            case PLUGIN -> "Plugin";
        };
    }
}
