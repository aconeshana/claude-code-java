package com.claudecode.commands.metadata;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.util.List;
import java.util.Objects;

/**
 * Immutable identity metadata shared by static and definition-driven commands.
 */
public record CommandMetadata(String name, String description, List<String> aliases) {

    public CommandMetadata(String name, String description) {
        this(name, description, List.of());
    }

    public CommandMetadata {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        aliases = List.copyOf(Objects.requireNonNull(aliases, "aliases"));
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("command name must not be blank");
        }
        if (Strings.CS.startsWith(name, "/")) {
            throw new IllegalArgumentException("command name must not start with '/': " + name);
        }
        for (String alias : aliases) {
            if (StringUtils.isBlank(alias)) {
                throw new IllegalArgumentException("command alias must not be blank");
            }
            if (Strings.CS.startsWith(alias, "/")) {
                throw new IllegalArgumentException("command alias must not start with '/': " + alias);
            }
        }
    }
}
