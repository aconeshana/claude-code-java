package com.claudecode.services.config;

import com.claudecode.permissions.RuleSource;
import com.claudecode.http.SharedHttpClient;
import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.process.SubprocessEnvironment;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;


public final class ManagedEnvironmentApplier {
    private static final Set<String> PROVIDER_MANAGED = Set.of(
        "CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST", "CLAUDE_CODE_USE_BEDROCK",
        "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY", "ANTHROPIC_BASE_URL",
        "ANTHROPIC_BEDROCK_BASE_URL", "ANTHROPIC_VERTEX_BASE_URL",
        "ANTHROPIC_FOUNDRY_BASE_URL", "ANTHROPIC_FOUNDRY_RESOURCE",
        "ANTHROPIC_VERTEX_PROJECT_ID", "CLOUD_ML_REGION", "ANTHROPIC_API_KEY",
        "ANTHROPIC_AUTH_TOKEN", "CLAUDE_CODE_OAUTH_TOKEN", "AWS_BEARER_TOKEN_BEDROCK",
        "ANTHROPIC_FOUNDRY_API_KEY", "CLAUDE_CODE_SKIP_BEDROCK_AUTH",
        "CLAUDE_CODE_SKIP_VERTEX_AUTH", "CLAUDE_CODE_SKIP_FOUNDRY_AUTH", "ANTHROPIC_MODEL",
        "ANTHROPIC_DEFAULT_HAIKU_MODEL", "ANTHROPIC_DEFAULT_HAIKU_MODEL_DESCRIPTION",
        "ANTHROPIC_DEFAULT_HAIKU_MODEL_NAME", "ANTHROPIC_DEFAULT_HAIKU_MODEL_SUPPORTED_CAPABILITIES",
        "ANTHROPIC_DEFAULT_OPUS_MODEL", "ANTHROPIC_DEFAULT_OPUS_MODEL_DESCRIPTION",
        "ANTHROPIC_DEFAULT_OPUS_MODEL_NAME", "ANTHROPIC_DEFAULT_OPUS_MODEL_SUPPORTED_CAPABILITIES",
        "ANTHROPIC_DEFAULT_SONNET_MODEL", "ANTHROPIC_DEFAULT_SONNET_MODEL_DESCRIPTION",
        "ANTHROPIC_DEFAULT_SONNET_MODEL_NAME", "ANTHROPIC_DEFAULT_SONNET_MODEL_SUPPORTED_CAPABILITIES",
        "ANTHROPIC_SMALL_FAST_MODEL", "ANTHROPIC_SMALL_FAST_MODEL_AWS_REGION",
        "CLAUDE_CODE_SUBAGENT_MODEL");
    private static final Set<String> SAFE_ENV = Set.of(
        "ANTHROPIC_CUSTOM_HEADERS", "ANTHROPIC_CUSTOM_MODEL_OPTION",
        "ANTHROPIC_CUSTOM_MODEL_OPTION_DESCRIPTION", "ANTHROPIC_CUSTOM_MODEL_OPTION_NAME",
        "ANTHROPIC_DEFAULT_HAIKU_MODEL", "ANTHROPIC_DEFAULT_HAIKU_MODEL_DESCRIPTION",
        "ANTHROPIC_DEFAULT_HAIKU_MODEL_NAME", "ANTHROPIC_DEFAULT_HAIKU_MODEL_SUPPORTED_CAPABILITIES",
        "ANTHROPIC_DEFAULT_OPUS_MODEL", "ANTHROPIC_DEFAULT_OPUS_MODEL_DESCRIPTION",
        "ANTHROPIC_DEFAULT_OPUS_MODEL_NAME", "ANTHROPIC_DEFAULT_OPUS_MODEL_SUPPORTED_CAPABILITIES",
        "ANTHROPIC_DEFAULT_SONNET_MODEL", "ANTHROPIC_DEFAULT_SONNET_MODEL_DESCRIPTION",
        "ANTHROPIC_DEFAULT_SONNET_MODEL_NAME", "ANTHROPIC_DEFAULT_SONNET_MODEL_SUPPORTED_CAPABILITIES",
        "ANTHROPIC_FOUNDRY_API_KEY", "ANTHROPIC_MODEL", "ANTHROPIC_SMALL_FAST_MODEL_AWS_REGION",
        "ANTHROPIC_SMALL_FAST_MODEL", "AWS_DEFAULT_REGION", "AWS_PROFILE", "AWS_REGION",
        "BASH_DEFAULT_TIMEOUT_MS", "BASH_MAX_OUTPUT_LENGTH", "BASH_MAX_TIMEOUT_MS",
        "CLAUDE_BASH_MAINTAIN_PROJECT_WORKING_DIR", "CLAUDE_CODE_API_KEY_HELPER_TTL_MS",
        "CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS", "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC",
        "CLAUDE_CODE_DISABLE_TERMINAL_TITLE", "CLAUDE_CODE_ENABLE_TELEMETRY",
        "CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS", "CLAUDE_CODE_IDE_SKIP_AUTO_INSTALL",
        "CLAUDE_CODE_MAX_OUTPUT_TOKENS", "CLAUDE_CODE_SKIP_BEDROCK_AUTH",
        "CLAUDE_CODE_SKIP_FOUNDRY_AUTH", "CLAUDE_CODE_SKIP_VERTEX_AUTH", "CLAUDE_CODE_SUBAGENT_MODEL",
        "CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_FOUNDRY", "CLAUDE_CODE_USE_VERTEX",
        "DISABLE_AUTOUPDATER", "DISABLE_BUG_COMMAND", "DISABLE_COST_WARNINGS",
        "DISABLE_ERROR_REPORTING", "DISABLE_FEEDBACK_COMMAND", "DISABLE_TELEMETRY",
        "ENABLE_TOOL_SEARCH", "MAX_MCP_OUTPUT_TOKENS", "MAX_THINKING_TOKENS", "MCP_TIMEOUT",
        "MCP_TOOL_TIMEOUT", "OTEL_EXPORTER_OTLP_HEADERS", "OTEL_EXPORTER_OTLP_LOGS_HEADERS",
        "OTEL_EXPORTER_OTLP_LOGS_PROTOCOL", "OTEL_EXPORTER_OTLP_METRICS_CLIENT_CERTIFICATE",
        "OTEL_EXPORTER_OTLP_METRICS_CLIENT_KEY", "OTEL_EXPORTER_OTLP_METRICS_HEADERS",
        "OTEL_EXPORTER_OTLP_METRICS_PROTOCOL", "OTEL_EXPORTER_OTLP_PROTOCOL",
        "OTEL_EXPORTER_OTLP_TRACES_HEADERS", "OTEL_LOG_TOOL_DETAILS", "OTEL_LOG_USER_PROMPTS",
        "OTEL_LOGS_EXPORT_INTERVAL", "OTEL_LOGS_EXPORTER", "OTEL_METRIC_EXPORT_INTERVAL",
        "OTEL_METRICS_EXPORTER", "OTEL_METRICS_INCLUDE_ACCOUNT_UUID", "OTEL_METRICS_INCLUDE_SESSION_ID",
        "OTEL_METRICS_INCLUDE_VERSION", "OTEL_RESOURCE_ATTRIBUTES", "USE_BUILTIN_RIPGREP",
        "VERTEX_REGION_CLAUDE_3_5_HAIKU", "VERTEX_REGION_CLAUDE_3_5_SONNET",
        "VERTEX_REGION_CLAUDE_3_7_SONNET", "VERTEX_REGION_CLAUDE_4_0_OPUS",
        "VERTEX_REGION_CLAUDE_4_0_SONNET", "VERTEX_REGION_CLAUDE_4_1_OPUS",
        "VERTEX_REGION_CLAUDE_4_5_SONNET", "VERTEX_REGION_CLAUDE_4_6_SONNET",
        "VERTEX_REGION_CLAUDE_HAIKU_4_5");

    private static volatile Set<String> ccdSpawnEnvKeys;

    private ManagedEnvironmentApplier() {}

    /** Applies global config, trusted sources, then safe merged project/local values. */
    public static void applySafeConfigEnvironmentVariables(String cwd) {
        captureCcdEnvironmentKeys();
        Map<String, String> values = Map.of();
        values = new LinkedHashMap<>(
            filter(GlobalConfigStore.getEnvironment(), hostManaged(values), values));
        for (RuleSource source : new RuleSource[] {
            RuleSource.USER_SETTINGS, RuleSource.FLAG_SETTINGS}) {
            if (!SettingsSources.isEnabled(source)) continue;
            values.putAll(filter(env(SettingsSources.settingsForSource(source, cwd)),
                hostManaged(values), values));
        }

        // non-policy trusted sources and only then applies policy env. Java's
        // x-api-key build has no remote eligibility reader, but preserving the
        // phase boundary keeps precedence and host-managed filtering aligned.
        values.putAll(filter(env(SettingsSources.settingsForSource(
            RuleSource.POLICY_SETTINGS, cwd)), hostManaged(values), values));
        SettingsWithErrors merged = SettingsDiagnostics.getSettingsWithErrors();
        Map<String, String> safe = env(merged.settings());
        for (Map.Entry<String, String> entry : filter(safe, hostManaged(values), values).entrySet()) {
            if (SAFE_ENV.contains(entry.getKey().toUpperCase(Locale.ROOT))) {
                values.put(entry.getKey(), entry.getValue());
            }
        }
        SubprocessEnvironment.updateSettings(values);
        refreshHttpTransportProxy();
    }

    /** Applies all effective settings.env values after the trust boundary. */
    public static void applyConfigEnvironmentVariables() {
        captureCcdEnvironmentKeys();
        Map<String, String> values = Map.of();
        values = new LinkedHashMap<>(
            filter(GlobalConfigStore.getEnvironment(), hostManaged(values), values));
        values.putAll(filter(env(SettingsDiagnostics.getSettingsWithErrors().settings()),
            hostManaged(values), values));
        SubprocessEnvironment.updateSettings(values);
        refreshHttpTransportProxy();
    }

    private static void refreshHttpTransportProxy() {
        SharedHttpClient.refreshEnvironmentProxy(SubprocessEnvironment.snapshot());
    }

    private static Map<String, String> env(JsonNode settings) {
        if (settings == null || !settings.isObject()) return Map.of();
        JsonNode env = settings.get("env");
        if (env == null || !env.isObject()) return Map.of();
        Map<String, String> values = new LinkedHashMap<>();
        env.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText()));
        return values;
    }

    private static Map<String, String> filter(Map<String, String> input, boolean hostManaged,
                                              Map<String, String> currentEnvironment) {
        if (input == null || input.isEmpty()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();

        // `currentEnvironment` represents earlier staged sources in Java; the
        // subprocess overlay covers values installed by an earlier apply pass.
        String stagedSocket = currentEnvironment == null
            ? null : currentEnvironment.get("ANTHROPIC_UNIX_SOCKET");
        String liveSocket = SubprocessEnvironment.get("ANTHROPIC_UNIX_SOCKET");
        boolean ssh = (StringUtils.isNotEmpty(stagedSocket))
            || (StringUtils.isNotEmpty(liveSocket));
        for (Map.Entry<String, String> entry : input.entrySet()) {
            String key = entry.getKey();
            if (ssh && Set.of("ANTHROPIC_UNIX_SOCKET", "ANTHROPIC_BASE_URL", "ANTHROPIC_API_KEY",
                    "ANTHROPIC_AUTH_TOKEN", "CLAUDE_CODE_OAUTH_TOKEN").contains(key)) continue;
            if (hostManaged && isProviderManaged(key)) continue;
            if (ccdSpawnEnvKeys != null && ccdSpawnEnvKeys.contains(key)) continue;
            out.put(key, entry.getValue());
        }
        return out;
    }

    private static boolean hostManaged(Map<String, String> values) {
        String current = values.get("CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST");
        return EnvUtils.isEnvTruthy(current != null
            ? current : SubprocessEnvironment.get("CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST"));
    }

    private static boolean isProviderManaged(String key) {
        String upper = key.toUpperCase(Locale.ROOT);
        return PROVIDER_MANAGED.contains(upper) || Strings.CS.startsWith(upper, "VERTEX_REGION_CLAUDE_");
    }

    private static void captureCcdEnvironmentKeys() {
        if (ccdSpawnEnvKeys != null) return;
        ccdSpawnEnvKeys =Strings.CS.equals( "claude-desktop", System.getenv("CLAUDE_CODE_ENTRYPOINT"))
            ? new HashSet<>(System.getenv().keySet()) : Set.of();
    }
}
