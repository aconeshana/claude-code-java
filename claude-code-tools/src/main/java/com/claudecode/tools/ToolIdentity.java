package com.claudecode.tools;

import org.apache.commons.lang3.StringUtils;
import java.util.List;
import java.util.Objects;

/**
 * Immutable canonical name and input-only aliases for a tool.
 */
public record ToolIdentity(String name, List<String> aliases) {

    public ToolIdentity(String name) {
        this(name, List.of());
    }

    public ToolIdentity {
        Objects.requireNonNull(name, "name");
        aliases = List.copyOf(Objects.requireNonNull(aliases, "aliases"));
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        for (String alias : aliases) {
            if (StringUtils.isBlank(alias)) {
                throw new IllegalArgumentException("tool alias must not be blank");
            }
        }
    }
}
