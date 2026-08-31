package com.claudecode.services.plugins.runtime;

import org.apache.commons.lang3.Strings;
import com.claudecode.services.plugins.marketplace.UserConfigOption;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginVariablesTest {

    private static UserConfigOption option(boolean sensitive) {
        return new UserConfigOption("string", null, null, null, null, null, sensitive, null, null);
    }

    @Test
    void substitutesPluginRootAndDataDir() {
        String out = PluginVariables.substitutePluginPaths(
            "run ${CLAUDE_PLUGIN_ROOT}/bin/x with ${CLAUDE_PLUGIN_DATA}/state.json",
            Path.of("/cache/p/1.0.0"), Path.of("/data/p-mkt"));
        assertEquals("run /cache/p/1.0.0/bin/x with /data/p-mkt/state.json", out);
    }

    @Test
    void nullPathsLeavePlaceholdersLiteral() {
        String out = PluginVariables.substitutePluginPaths(
            "${CLAUDE_PLUGIN_ROOT} ${CLAUDE_PLUGIN_DATA}", null, null);
        assertEquals("${CLAUDE_PLUGIN_ROOT} ${CLAUDE_PLUGIN_DATA}", out);
    }

    @Test
    void strictUserConfigSubstitutesKnownKeys() {
        String out = PluginVariables.substituteUserConfig(
            "url=${user_config.endpoint}", Map.of("endpoint", "https://api.example.com"));
        assertEquals("url=https://api.example.com", out);
    }

    @Test
    void strictUserConfigThrowsOnMissingKey() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> PluginVariables.substituteUserConfig("${user_config.missing}", Map.of()));
        assertTrue(Strings.CS.contains(e.getMessage(), "missing"), e.getMessage());
    }

    @Test
    void contentSafeUserConfigLeavesUnknownKeysLiteral() {
        String out = PluginVariables.substituteUserConfigInContent(
            "keep ${user_config.unknown} as-is", Map.of(), Map.of());
        assertEquals("keep ${user_config.unknown} as-is", out);
    }

    @Test
    void contentSafeUserConfigMasksSensitiveKeys() {
        String out = PluginVariables.substituteUserConfigInContent(
            "token: ${user_config.apiKey}",
            Map.of("apiKey", "secret-value"),
            Map.of("apiKey", option(true)));
        assertEquals("token: [sensitive option 'apiKey' not available in skill content]", out);
        assertFalse(Strings.CS.contains(out, "secret-value"));
    }

    @Test
    void contentSafeUserConfigSubstitutesNonSensitiveValues() {
        String out = PluginVariables.substituteUserConfigInContent(
            "user: ${user_config.username}",
            Map.of("username", "alice"),
            Map.of("username", option(false)));
        assertEquals("user: alice", out);
    }

    @Test
    void fullPipelineSubstitutesSessionId() {
        String out = PluginVariables.substitute(
            "session ${CLAUDE_SESSION_ID} at ${CLAUDE_PLUGIN_ROOT}",
            Path.of("/root"), Path.of("/data"), Map.of(), Map.of(), "abc-123");
        assertEquals("session abc-123 at /root", out);
    }

    @Test
    void nullSessionIdLeavesPlaceholderLiteral() {
        String out = PluginVariables.substitute(
            "${CLAUDE_SESSION_ID}", Path.of("/r"), Path.of("/d"), Map.of(), Map.of(), null);
        assertEquals("${CLAUDE_SESSION_ID}", out);
    }

    @Test
    void envExpansionUsesEnvironmentValue() {
        String out = PluginVariables.expandEnvVars(
            "home=${MY_HOME}", new ArrayList<>(), Map.of("MY_HOME", "/home/u"));
        assertEquals("home=/home/u", out);
    }

    @Test
    void envExpansionFallsBackToDefault() {
        List<String> missing = new ArrayList<>();
        String out = PluginVariables.expandEnvVars(
            "port=${PORT:-8080}", missing, Map.of());
        assertEquals("port=8080", out);
        assertTrue(missing.isEmpty());
    }

    @Test
    void envExpansionTracksMissingVarsAndKeepsLiteral() {
        List<String> missing = new ArrayList<>();
        String out = PluginVariables.expandEnvVars("x=${NOT_SET}", missing, Map.of());
        assertEquals("x=${NOT_SET}", out);
        assertEquals(List.of("NOT_SET"), missing);
    }

    @Test
    void defaultValuePreservesColonDashInDefault() {

        String out = PluginVariables.expandEnvVars(
            "v=${X:-a:-b}", new ArrayList<>(), Map.of());
        assertEquals("v=a:-b", out);
    }
}
