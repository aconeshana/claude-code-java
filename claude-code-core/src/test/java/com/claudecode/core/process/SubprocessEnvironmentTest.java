package com.claudecode.core.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SubprocessEnvironmentTest {
    @AfterEach void resetProxyProvider() {
        SubprocessEnvironment.registerUpstreamProxyEnvironment(Map::of);
        SubprocessEnvironment.clearRuntimeOverrides();
    }

    @Test void scrubsSecretsAndInputDuplicatesButKeepsGithubToken() {
        Map<String, String> env = new HashMap<>();
        env.put(SubprocessEnvironment.SCRUB_FLAG, "true");
        env.put("ANTHROPIC_API_KEY", "secret");
        env.put("INPUT_ANTHROPIC_API_KEY", "duplicate");
        env.put("GITHUB_TOKEN", "kept");
        SubprocessEnvironment.applyTo(env);
        assertFalse(env.containsKey("ANTHROPIC_API_KEY"));
        assertFalse(env.containsKey("INPUT_ANTHROPIC_API_KEY"));
        assertEquals("kept", env.get("GITHUB_TOKEN"));
    }

    @Test void mergesProxyEnvironmentEvenWhenScrubbingIsDisabled() {
        SubprocessEnvironment.registerUpstreamProxyEnvironment(
            () -> Map.of("HTTPS_PROXY", "http://127.0.0.1:1234"));
        Map<String, String> env = new HashMap<>();
        SubprocessEnvironment.applyTo(env);
        assertEquals("http://127.0.0.1:1234", env.get("HTTPS_PROXY"));
    }

    @Test void runtimeUpdatesAffectLookupAndFutureChildren() {
        SubprocessEnvironment.updateRuntime(Map.of("SDK_REFRESHED_TOKEN", "fresh"));
        Map<String, String> env = new HashMap<>();
        SubprocessEnvironment.applyTo(env);
        assertEquals("fresh", SubprocessEnvironment.get("SDK_REFRESHED_TOKEN"));
        assertEquals("fresh", env.get("SDK_REFRESHED_TOKEN"));
    }
}
