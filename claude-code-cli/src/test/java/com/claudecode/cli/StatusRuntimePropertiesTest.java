package com.claudecode.cli;

import org.apache.commons.lang3.Strings;

import com.claudecode.api.ApiConfig;
import com.claudecode.commands.StatusProperty;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusRuntimePropertiesTest {

    @Test
    void anthropicReportsSeparateCredentialSourcesBaseUrlProxyAndTls() {
        Map<String, String> env = Map.of(
            "ANTHROPIC_AUTH_TOKEN", "secret",
            "ANTHROPIC_API_KEY", "key",
            "https_proxy", "http://proxy",
            "NODE_EXTRA_CA_CERTS", "/certs/ca.pem",
            "CLAUDE_CODE_CLIENT_CERT", "/certs/client.pem",
            "CLAUDE_CODE_CLIENT_KEY", "/certs/client.key");

        List<StatusProperty> rows = CliRuntimeAdapters.statusRuntimeProperties(
            ApiConfig.ApiProvider.ANTHROPIC, "https://gateway", null,
            env, false, Path.of("/config.json"));

        assertEquals("ANTHROPIC_AUTH_TOKEN", value(rows, "Auth token"));
        assertEquals("ANTHROPIC_API_KEY", value(rows, "API key"));
        assertEquals("https://gateway", value(rows, "Anthropic base URL"));
        assertEquals("http://proxy", value(rows, "Proxy"));
        assertEquals("/certs/ca.pem", value(rows, "Additional CA cert(s)"));
        assertEquals("/certs/client.pem", value(rows, "mTLS client cert"));
        assertEquals("/certs/client.key", value(rows, "mTLS client key"));
    }

    @Test
    void bedrockReportsProviderRegionAndUnlabelledAuthSkip() {
        List<StatusProperty> rows = CliRuntimeAdapters.statusRuntimeProperties(
            ApiConfig.ApiProvider.BEDROCK, null, null,
            Map.of(
                "AWS_DEFAULT_REGION", "eu-west-1",
                "BEDROCK_BASE_URL", "https://bedrock",
                "CLAUDE_CODE_SKIP_BEDROCK_AUTH", "true"),
            false, Path.of("/config.json"));

        assertEquals("AWS Bedrock", value(rows, "API provider"));
        assertEquals("https://bedrock", value(rows, "Bedrock base URL"));
        assertEquals("eu-west-1", value(rows, "AWS region"));
        assertTrue(rows.stream().anyMatch(row -> row.label() == null
            && Strings.CS.equals(row.value(), "AWS auth skipped")));
    }

    @Test
    void vertexReportsProjectAndDefaultRegion() {
        List<StatusProperty> rows = CliRuntimeAdapters.statusRuntimeProperties(
            ApiConfig.ApiProvider.VERTEX, null, null,
            Map.of("ANTHROPIC_VERTEX_PROJECT_ID", "project-1"),
            false, Path.of("/config.json"));

        assertEquals("Google Vertex AI", value(rows, "API provider"));
        assertEquals("project-1", value(rows, "GCP project"));
        assertEquals("us-east5", value(rows, "Default region"));
    }

    private static String value(List<StatusProperty> rows, String label) {
        return rows.stream().filter(row -> label.equals(row.label()))
            .findFirst().orElseThrow().value();
    }
}
