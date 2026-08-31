package com.claudecode.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.api.ApiConfig;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.state.CwdState;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Guards client endpoint precedence at the CLI toolchain boundary. */
class CliToolchainAssemblerTest {

    private Path originalCwd;

    @BeforeEach
    void saveOriginalCwd() {
        originalCwd = CwdState.getOriginalCwd();
    }

    @AfterEach
    void restoreOriginalCwd() {
        if (originalCwd == null) CwdState.clearForTesting();
        else CwdState.setOriginalCwd(originalCwd);
    }

    @Test
    void environmentBaseUrlReachesClientsWhenCliOptionIsAbsent() {
        assertEquals("https://gateway.example.test",
            CliToolchainAssembler.selectBaseUrl(null, "https://gateway.example.test"));
        assertEquals("https://cli.example.test",
            CliToolchainAssembler.selectBaseUrl("https://cli.example.test", "https://gateway.example.test"));
    }

    @Test
    void guideFeedbackProviderClassificationMatchesReleasedFirstPartySemantics() {
        assertFalse(CliToolchainAssembler.isUsingThirdPartyServices(
            ApiConfig.ApiProvider.ANTHROPIC));
        assertTrue(CliToolchainAssembler.isUsingThirdPartyServices(
            ApiConfig.ApiProvider.BEDROCK));
        assertTrue(CliToolchainAssembler.isUsingThirdPartyServices(
            ApiConfig.ApiProvider.VERTEX));
    }

    @Test
    void subAgentSettingsUseSessionRootRatherThanAgentWorkingDirectory() {
        Path sessionRoot = Path.of("/tmp/session-root");
        Path subAgentCwd = sessionRoot.resolve("nested-agent");
        CwdState.setOriginalCwd(sessionRoot);

        assertEquals(sessionRoot.toString(),
            CliToolchainAssembler.settingsRootForSubAgent(subAgentCwd));
    }

    @Test
    void customMainModelCanStillSupplyAnthropicFallbackCredentials() {
        ConfigLoader loader = loaderWithEnv(Map.of(
            "ANTHROPIC_AUTH_TOKEN", "bearer-fallback"));

        ConfigLoader.Credentials credentials = CliToolchainAssembler.resolveFallbackCredentials(
            loader, ApiConfig.ApiProvider.ANTHROPIC, "sk-ant-fallback");

        assertEquals("sk-ant-fallback", credentials.apiKey());
        assertEquals("bearer-fallback", credentials.authToken());
    }

    @Test
    void nonAnthropicProviderDoesNotConsumeAnthropicCredentials() {
        ConfigLoader.Credentials credentials = CliToolchainAssembler.resolveFallbackCredentials(
            loaderWithEnv(Map.of("ANTHROPIC_API_KEY", "unused")),
            ApiConfig.ApiProvider.BEDROCK, null);

        assertNull(credentials.apiKey());
        assertNull(credentials.authToken());
    }

    @Test
    void mcpOAuthNeverMasqueradesAsClaudeAccountAuthSuccess() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/claudecode/cli/CliToolchainAssembler.java"));

        assertTrue(source.contains(
            "MCP OAuth is intentionally not mapped to Claude-account auth_success hooks"));
        assertFalse(source.contains("\"auth_success\""),
            "auth_success may only be emitted by a future Claude-account OAuth boundary");
    }

    @Test
    void elicitationResultHookCanOverrideValidatedResponse() {
        JsonNode original = JsonUtils.getMapper().createObjectNode()
            .put("action", "accept")
            .set("content", JsonUtils.getMapper().createObjectNode().put("answer", "original"));
        JsonNode fields = JsonUtils.getMapper().createObjectNode()
            .put("hookEventName", "ElicitationResult")
            .put("action", "decline");
        HookDispatcher.HookOutcome outcome = new HookDispatcher.HookOutcome(
            true, null, List.of(), false, null, null, List.of(),
            List.of(new HookDispatcher.HookSpecificOutput("ElicitationResult", fields)));

        JsonNode result = CliToolchainAssembler.applyElicitationResultOutcome(original, outcome);

        assertEquals("decline", result.path("action").asText());
        assertEquals("original", result.path("content").path("answer").asText());
    }

    @Test
    void elicitationResultHookBlockAlwaysDeclines() {
        JsonNode original = JsonUtils.getMapper().createObjectNode().put("action", "accept");

        JsonNode result = CliToolchainAssembler.applyElicitationResultOutcome(
            original, HookDispatcher.HookOutcome.BLOCK);

        assertEquals("decline", result.path("action").asText());
        assertFalse(result.has("content"));
    }

    @Test
    void elicitationResultHookRejectsUnknownActionOverride() {
        JsonNode original = JsonUtils.getMapper().createObjectNode().put("action", "cancel");
        JsonNode fields = JsonUtils.getMapper().createObjectNode()
            .put("hookEventName", "ElicitationResult")
            .put("action", "execute");
        HookDispatcher.HookOutcome outcome = new HookDispatcher.HookOutcome(
            true, null, List.of(), false, null, null, List.of(),
            List.of(new HookDispatcher.HookSpecificOutput("ElicitationResult", fields)));

        JsonNode result = CliToolchainAssembler.applyElicitationResultOutcome(original, outcome);

        assertEquals("cancel", result.path("action").asText());
    }

    private static ConfigLoader loaderWithEnv(Map<String, String> env) {
        return new ConfigLoader() {
            @Override
            String getEnvironmentVariable(String name) {
                return env.get(name);
            }

            @Override
            String getStoredApiKey() {
                return null;
            }
        };
    }

}
