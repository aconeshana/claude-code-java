package com.claudecode.services.config;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Internal result of validating a platform-managed settings payload.
 */
record ManagedValidation(ObjectNode settings, List<SettingsValidationError> errors) {
    ManagedValidation {
        settings = settings == null
            ? JsonUtils.getMapper().createObjectNode() : settings.deepCopy();
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public ObjectNode settings() {
        return settings.deepCopy();
    }
}
