package com.claudecode.core.process;

import com.claudecode.core.config.EnvUtils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Builds the environment inherited by child processes.
 *
 * <ul>
 *   <li>{@code subprocessEnv} and
 *       {@code registerUpstreamProxyEnvFn}: merge lazily supplied proxy variables
 *       and optionally remove credentials that subprocesses do not need.</li>
 *   <li>SDK
 *       {@code update_environment_variables}: live process-environment overlay
 *       inherited by later child processes.</li>
 * </ul>
 */
public final class SubprocessEnvironment {
    public static final String SCRUB_FLAG = "CLAUDE_CODE_SUBPROCESS_ENV_SCRUB";

    private static final List<String> SENSITIVE_VARIABLES = List.of(
        "ANTHROPIC_API_KEY", "CLAUDE_CODE_OAUTH_TOKEN", "ANTHROPIC_AUTH_TOKEN",
        "ANTHROPIC_FOUNDRY_API_KEY", "ANTHROPIC_CUSTOM_HEADERS",
        "OTEL_EXPORTER_OTLP_HEADERS", "OTEL_EXPORTER_OTLP_LOGS_HEADERS",
        "OTEL_EXPORTER_OTLP_METRICS_HEADERS", "OTEL_EXPORTER_OTLP_TRACES_HEADERS",
        "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN", "AWS_BEARER_TOKEN_BEDROCK",
        "GOOGLE_APPLICATION_CREDENTIALS", "AZURE_CLIENT_SECRET",
        "AZURE_CLIENT_CERTIFICATE_PATH", "ACTIONS_ID_TOKEN_REQUEST_TOKEN",
        "ACTIONS_ID_TOKEN_REQUEST_URL", "ACTIONS_RUNTIME_TOKEN", "ACTIONS_RUNTIME_URL",
        "ALL_INPUTS", "OVERRIDE_GITHUB_TOKEN", "DEFAULT_WORKFLOW_TOKEN", "SSH_SIGNING_KEY"
    );

    private static volatile Supplier<Map<String, String>> upstreamProxyEnvironment = Map::of;
    private static final Map<String, String> runtimeOverrides = new ConcurrentHashMap<>();
    /** Settings.env overlay; explicit SDK runtime updates remain higher priority. */
    private static final Map<String, String> settingsOverrides = new ConcurrentHashMap<>();

    private SubprocessEnvironment() {}

    public static void registerUpstreamProxyEnvironment(Supplier<Map<String, String>> supplier) {
        upstreamProxyEnvironment = Objects.requireNonNull(supplier, "supplier");
    }

    public static void applyTo(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        environment.putAll(settingsOverrides);
        environment.putAll(runtimeOverrides);
        Map<String, String> proxy = upstreamProxyEnvironment.get();
        if (proxy != null && !proxy.isEmpty()) environment.putAll(proxy);
        if (!EnvUtils.isEnvTruthy(environment.get(SCRUB_FLAG))) return;
        for (String key : SENSITIVE_VARIABLES) {
            environment.remove(key);
            environment.remove("INPUT_" + key);
        }
    }

    /** Applies SDK-provided process-level environment updates for this JVM. */
    public static void updateRuntime(Map<String, String> variables) {
        if (variables != null) runtimeOverrides.putAll(variables);
    }

    /** Environment lookup that observes SDK runtime updates before the OS snapshot. */
    public static String get(String name) {
        String override = runtimeOverrides.get(name);
        if (override != null) return override;
        String settings = settingsOverrides.get(name);
        return settings != null ? settings : System.getenv(name);
    }

    /**
     * Returns the current child-process environment overlay without mutating
     * the JVM's immutable {@link System#getenv} snapshot. Runtime updates
     * take precedence over settings.env, matching {@link #applyTo(Map)}.
     */
    public static Map<String, String> snapshot() {
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.putAll(settingsOverrides);
        environment.putAll(runtimeOverrides);
        Map<String, String> proxy = upstreamProxyEnvironment.get();
        if (proxy != null && !proxy.isEmpty()) environment.putAll(proxy);
        return Map.copyOf(environment);
    }

    /** Replaces the settings.env overlay, removing values deleted by a reload. */
    public static void replaceSettings(Map<String, String> variables) {
        settingsOverrides.clear();
        if (variables != null) settingsOverrides.putAll(variables);
    }


    public static void updateSettings(Map<String, String> variables) {
        if (variables != null) settingsOverrides.putAll(variables);
    }

    /** Clears only settings.env values; SDK runtime overrides are preserved. */
    public static void clearSettings() {
        settingsOverrides.clear();
    }

    /** Clears SDK-provided runtime overrides when resetting process state. */
    public static void clearRuntimeOverrides() {
        runtimeOverrides.clear();
    }

}
