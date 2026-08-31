package com.claudecode.services.config;

import com.claudecode.core.serialization.JsonUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsManagedKeysTest {

    @Test
    void loggingUsesOnlyExternalSchemaKeysAndExpandsKnownNestedKeys() {
        assertEquals(List.of(
            "disableAutoMode",
            "hooks.Stop",
            "model",
            "permissions.allow",
            "permissions.defaultMode",
            "sandbox.enabled"),
            SettingsDiagnostics.getManagedSettingsKeysForLogging(JsonUtils.parseTree("""
                {
                  "model": "sonnet",
                  "disableAutoMode": "disable",
                  "permissions": {
                    "allow": ["Read(*)"],
                    "defaultMode": "default",
                    "disableAutoMode": "disable",
                    "futurePermissionKey": true
                  },
                  "sandbox": {"enabled": true, "futureSandboxKey": true},
                  "hooks": {"Stop": []},
                  "autoMode": {"allow": ["Read(*)"]},
                  "enableWorkflows": true,
                  "futureTopLevelKey": "ignored"
                }
                """)));
    }

    @Test
    void managedValidationNormalizesCatchGuardedFieldsLikeTsSchema() {
        var accepted = SettingsTreeReader.validateManaged(JsonUtils.parseTree("""
            {
              "effortLevel": "turbo",
              "strictPluginOnlyCustomization": ["skills", "futureSurface", 7, "hooks"]
            }
            """), "managed settings").settings();

        assertFalse(accepted.has("effortLevel"));
        assertTrue(accepted.path("strictPluginOnlyCustomization").isArray());
        assertEquals(List.of("skills", "hooks"),
            JsonUtils.getMapper().convertValue(
                accepted.path("strictPluginOnlyCustomization"), List.class));
    }
}
