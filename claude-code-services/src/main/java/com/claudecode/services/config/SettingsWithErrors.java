package com.claudecode.services.config;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Merged settings together with the diagnostics collected while loading them.
 */
public record SettingsWithErrors(ObjectNode settings, List<SettingsValidationError> errors) {
    public SettingsWithErrors {
        settings = settings == null
            ? JsonUtils.getMapper().createObjectNode() : settings.deepCopy();
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    /** Returns a detached settings tree so callers cannot mutate the retained diagnostic cache. */
    public ObjectNode settings() {
        return settings.deepCopy();
    }
}
