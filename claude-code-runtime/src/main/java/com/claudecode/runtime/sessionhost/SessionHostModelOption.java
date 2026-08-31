package com.claudecode.runtime.sessionhost;

import com.claudecode.core.annotation.Explanation;
import java.util.Objects;

/**
 * One model choice exposed by an application-owned semantic session.
 */
@Explanation("Session-scoped model metadata for semantic remote endpoints")
public record SessionHostModelOption(
        String name,
        String label,
        String description,
        String alias,
        boolean defaultOption) {

    public SessionHostModelOption {
        name = Objects.requireNonNull(name, "name").trim();
        label = label == null ? "" : label.trim();
        description = description == null ? "" : description.trim();
        alias = alias == null ? "" : alias.trim();
        if (name.isEmpty() || name.length() > 1024) {
            throw new IllegalArgumentException("model option name must contain 1-1024 characters");
        }
    }

    public SessionHostModelOption(
            String name, String label, String description, boolean defaultOption) {
        this(name, label, description, "", defaultOption);
    }
}
