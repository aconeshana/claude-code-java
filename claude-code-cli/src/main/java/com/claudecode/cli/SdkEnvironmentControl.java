package com.claudecode.cli;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Settings, hooks, dialog capability, plugin/skill reload, file read, and environment
 * operations exposed through the SDK control protocol.
 */
interface SdkEnvironmentControl {

    JsonNode settings();

    default void configureHooks(JsonNode hooks) {
        throw new UnsupportedOperationException("SDK hook callbacks are unavailable");
    }

    default void configureSupportedDialogKinds(JsonNode dialogKinds) {
        // Runtimes without an outbound dialog broker have nothing to configure.
    }

    default JsonNode reloadPlugins() {
        throw new UnsupportedOperationException("Plugin reload is unavailable");
    }

    default JsonNode reloadSkills() {
        throw new UnsupportedOperationException("Skill reload is unavailable");
    }

    default JsonNode readFile(String path, Long maxBytes, String encoding) {
        throw new UnsupportedOperationException("SDK file reads are unavailable");
    }

    default void applyFlagSettings(JsonNode settings) {
        throw new UnsupportedOperationException("Flag settings are unavailable");
    }

    default void updateEnvironmentVariables(JsonNode variables) {
        throw new UnsupportedOperationException("Environment updates are unavailable");
    }
}
