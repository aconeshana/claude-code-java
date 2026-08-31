package com.claudecode.sdk;

import java.util.Objects;

/** Available subagent metadata. */
public record AgentInfo(String name, String description, String model) {
    public AgentInfo {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
    }
}
