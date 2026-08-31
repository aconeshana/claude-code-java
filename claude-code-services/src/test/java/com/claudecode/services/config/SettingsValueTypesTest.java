package com.claudecode.services.config;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the settings-validation value types' ownership boundaries.
 */
class SettingsValueTypesTest {

    @Test
    void settingsWithErrorsCopiesNestedSettingsAndFreezesDiagnostics() {
        ObjectNode input = JsonUtils.getMapper().createObjectNode();
        input.putObject("nested").put("value", "original");
        List<SettingsValidationError> inputErrors = new ArrayList<>(List.of(
            new SettingsValidationError("settings.json", "nested.value", "invalid value")));

        SettingsWithErrors result = new SettingsWithErrors(input, inputErrors);
        ((ObjectNode) input.get("nested")).put("value", "changed");
        inputErrors.add(new SettingsValidationError("other.json", "", "another error"));

        assertEquals("original", result.settings().path("nested").path("value").asText());
        assertEquals(1, result.errors().size());
        assertThrows(UnsupportedOperationException.class, () -> result.errors().add(
            new SettingsValidationError("later.json", "", "must not be added")));

        result.settings().put("callerMutation", true);
        assertFalse(result.settings().has("callerMutation"),
            "a caller must not obtain the cached settings tree by reference");
    }

    @Test
    void settingsWithErrorsNormalizesNullComponentsToEmptyValues() {
        SettingsWithErrors result = new SettingsWithErrors(null, null);

        assertTrue(result.settings().isEmpty());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void settingsValidationErrorRetainsItsDiagnosticFields() {
        SettingsValidationError error = new SettingsValidationError(
            "settings.json", "permissions.allow", "Invalid permission rule");

        assertEquals("settings.json", error.file());
        assertEquals("permissions.allow", error.path());
        assertEquals("Invalid permission rule", error.message());
    }

    @Test
    void managedValidationCarriesAcceptedSettingsAndDiagnostics() {
        ObjectNode settings = JsonUtils.getMapper().createObjectNode().put("model", "sonnet");
        List<SettingsValidationError> errors = List.of(
            new SettingsValidationError("managed settings", "", "warning"));

        ManagedValidation validation = new ManagedValidation(settings, errors);

        assertEquals("sonnet", validation.settings().path("model").asText());
        assertFalse(validation.errors().isEmpty());
        assertEquals(errors, validation.errors());
    }
}
