package com.claudecode.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.claudecode.core.config.ApiKeyResolver;
import com.claudecode.core.process.SubprocessEnvironment;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * API key and bearer token resolve independently: {@code --api-key}, then
 * {@code ANTHROPIC_API_KEY}, then the stored key. The bearer token never fills
 * either role for the other.
 */
class CredentialResolutionContractTest {

    @AfterEach
    void clearOverrides() {
        SubprocessEnvironment.clearRuntimeOverrides();
    }

    @Test
    void explicitKeyBeatsEnvironmentAndStoredKey() {
        SubprocessEnvironment.updateRuntime(Map.of(ApiKeyResolver.ENV_API_KEY, "from-env"));
        assertEquals("from-flag",
            ApiKeyResolver.resolve("from-flag", () -> "from-store").orElseThrow());
    }

    @Test
    void environmentBeatsStoredKey() {
        SubprocessEnvironment.updateRuntime(Map.of(ApiKeyResolver.ENV_API_KEY, "from-env"));
        assertEquals("from-env",
            ApiKeyResolver.resolve(null, () -> "from-store").orElseThrow());
    }

    @Test
    void storedKeyIsTheLastSource() {
        assertEquals("from-store",
            ApiKeyResolver.resolve(null, () -> "from-store").orElseThrow());
    }

    @Test
    void bearerTokenIsNotAnApiKeyAndApiKeyIsNotABearerToken() {
        SubprocessEnvironment.updateRuntime(Map.of(
            "ANTHROPIC_AUTH_TOKEN", "bearer-only",
            ApiKeyResolver.ENV_API_KEY, "key-only"));
        ConfigLoader loader = new ConfigLoader();
        assertEquals("key-only", loader.resolveApiKey(null));
        assertEquals("bearer-only", loader.resolveAuthToken());

        SubprocessEnvironment.clearRuntimeOverrides();
        SubprocessEnvironment.updateRuntime(Map.of("ANTHROPIC_AUTH_TOKEN", "bearer-only"));
        assertNull(loader.resolveApiKey(null));

        SubprocessEnvironment.clearRuntimeOverrides();
        SubprocessEnvironment.updateRuntime(Map.of(ApiKeyResolver.ENV_API_KEY, "key-only"));
        assertNull(loader.resolveAuthToken());
    }
}
