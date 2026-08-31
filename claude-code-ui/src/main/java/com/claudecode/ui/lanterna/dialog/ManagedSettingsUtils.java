package com.claudecode.ui.lanterna.dialog;

import java.util.Locale;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.config.SettingsPathResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Enterprise / MDM managed-settings helpers backing {@link ManagedSettingsSecurityDialog}.
 */
public final class ManagedSettingsUtils {

    /** Shell settings whose mere presence can execute arbitrary code / exfiltrate. */
    static final List<String> DANGEROUS_SHELL_SETTINGS = List.of(
        "apiKeyHelper",
        "awsAuthRefresh",
        "awsCredentialExport",
        "gcpAuthRefresh",
        "otelHeadersHelper",
        "statusLine");

    /**
     * Environment variables that are safe to apply before the trust dialog.
     */
    static final Set<String> SAFE_ENV_VARS = Set.of(
        "ANTHROPIC_CUSTOM_HEADERS",
        "ANTHROPIC_CUSTOM_MODEL_OPTION",
        "ANTHROPIC_CUSTOM_MODEL_OPTION_DESCRIPTION",
        "ANTHROPIC_CUSTOM_MODEL_OPTION_NAME",
        "ANTHROPIC_DEFAULT_HAIKU_MODEL",
        "ANTHROPIC_DEFAULT_HAIKU_MODEL_DESCRIPTION",
        "ANTHROPIC_DEFAULT_HAIKU_MODEL_NAME",
        "ANTHROPIC_DEFAULT_HAIKU_MODEL_SUPPORTED_CAPABILITIES",
        "ANTHROPIC_DEFAULT_OPUS_MODEL",
        "ANTHROPIC_DEFAULT_OPUS_MODEL_DESCRIPTION",
        "ANTHROPIC_DEFAULT_OPUS_MODEL_NAME",
        "ANTHROPIC_DEFAULT_OPUS_MODEL_SUPPORTED_CAPABILITIES",
        "ANTHROPIC_DEFAULT_SONNET_MODEL",
        "ANTHROPIC_DEFAULT_SONNET_MODEL_DESCRIPTION",
        "ANTHROPIC_DEFAULT_SONNET_MODEL_NAME",
        "ANTHROPIC_DEFAULT_SONNET_MODEL_SUPPORTED_CAPABILITIES",
        "ANTHROPIC_FOUNDRY_API_KEY",
        "ANTHROPIC_MODEL",
        "ANTHROPIC_SMALL_FAST_MODEL_AWS_REGION",
        "ANTHROPIC_SMALL_FAST_MODEL",
        "AWS_DEFAULT_REGION",
        "AWS_PROFILE",
        "AWS_REGION",
        "BASH_DEFAULT_TIMEOUT_MS",
        "BASH_MAX_OUTPUT_LENGTH",
        "BASH_MAX_TIMEOUT_MS",
        "CLAUDE_BASH_MAINTAIN_PROJECT_WORKING_DIR",
        "CLAUDE_CODE_API_KEY_HELPER_TTL_MS",
        "CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS",
        "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC",
        "CLAUDE_CODE_DISABLE_TERMINAL_TITLE",
        "CLAUDE_CODE_ENABLE_TELEMETRY",
        "CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS",
        "CLAUDE_CODE_IDE_SKIP_AUTO_INSTALL",
        "CLAUDE_CODE_MAX_OUTPUT_TOKENS",
        "CLAUDE_CODE_SKIP_BEDROCK_AUTH",
        "CLAUDE_CODE_SKIP_FOUNDRY_AUTH",
        "CLAUDE_CODE_SKIP_VERTEX_AUTH",
        "CLAUDE_CODE_SUBAGENT_MODEL",
        "CLAUDE_CODE_USE_BEDROCK",
        "CLAUDE_CODE_USE_FOUNDRY",
        "CLAUDE_CODE_USE_VERTEX",
        "DISABLE_AUTOUPDATER",
        "DISABLE_BUG_COMMAND",
        "DISABLE_COST_WARNINGS",
        "DISABLE_ERROR_REPORTING",
        "DISABLE_FEEDBACK_COMMAND",
        "DISABLE_TELEMETRY",
        "ENABLE_TOOL_SEARCH",
        "MAX_MCP_OUTPUT_TOKENS",
        "MAX_THINKING_TOKENS",
        "MCP_TIMEOUT",
        "MCP_TOOL_TIMEOUT",
        "OTEL_EXPORTER_OTLP_HEADERS",
        "OTEL_EXPORTER_OTLP_LOGS_HEADERS",
        "OTEL_EXPORTER_OTLP_LOGS_PROTOCOL",
        "OTEL_EXPORTER_OTLP_METRICS_CLIENT_CERTIFICATE",
        "OTEL_EXPORTER_OTLP_METRICS_CLIENT_KEY",
        "OTEL_EXPORTER_OTLP_METRICS_HEADERS",
        "OTEL_EXPORTER_OTLP_METRICS_PROTOCOL",
        "OTEL_EXPORTER_OTLP_PROTOCOL",
        "OTEL_EXPORTER_OTLP_TRACES_HEADERS",
        "OTEL_LOG_TOOL_DETAILS",
        "OTEL_LOG_USER_PROMPTS",
        "OTEL_LOGS_EXPORT_INTERVAL",
        "OTEL_LOGS_EXPORTER",
        "OTEL_METRIC_EXPORT_INTERVAL",
        "OTEL_METRICS_EXPORTER",
        "OTEL_METRICS_INCLUDE_ACCOUNT_UUID",
        "OTEL_METRICS_INCLUDE_SESSION_ID",
        "OTEL_METRICS_INCLUDE_VERSION",
        "OTEL_RESOURCE_ATTRIBUTES",
        "USE_BUILTIN_RIPGREP",
        "VERTEX_REGION_CLAUDE_3_5_HAIKU",
        "VERTEX_REGION_CLAUDE_3_5_SONNET",
        "VERTEX_REGION_CLAUDE_3_7_SONNET",
        "VERTEX_REGION_CLAUDE_4_0_OPUS",
        "VERTEX_REGION_CLAUDE_4_0_SONNET",
        "VERTEX_REGION_CLAUDE_4_1_OPUS",
        "VERTEX_REGION_CLAUDE_4_5_SONNET",
        "VERTEX_REGION_CLAUDE_4_6_SONNET",
        "VERTEX_REGION_CLAUDE_HAIKU_4_5");

    /** The dangerous subset of a settings object. Values are kept (not shown) for completeness. */
    public record DangerousSettings(
        Map<String, String> shellSettings,
        Map<String, String> envVars,
        boolean hasHooks) { }

    private ManagedSettingsUtils() { }

    /**
     * Loads file-based managed settings:  merged
     * under every  drop-in (drop-ins win,
     * sorted by name). Returns an empty object when no managed settings exist
     * or the file-backed store cannot be read.
     */
    public     static JsonNode loadManagedSettings() {
        return loadManagedSettingsFrom(getManagedFilePath());
    }

    /**
     * Loads managed settings from a specific directory (base
     * merged under  drop-ins). Package-private so tests
     * can exercise the real merge against a temp dir; the OS path is resolved by
     * {@link #loadManagedSettings}.
     */
    public     static JsonNode loadManagedSettingsFrom(Path dir) {
        ObjectMapper mapper = JsonUtils.getMapper();
        JsonNode base = readJson(mapper, dir.resolve("managed-settings.json"));
        ObjectNode merged = (base != null && base.isObject())
            ? (ObjectNode) base.deepCopy()
            : mapper.createObjectNode();
        Path dropinDir = dir.resolve("managed-settings.d");
        if (Files.isDirectory(dropinDir)) {
            try (Stream<Path> stream = Files.list(dropinDir)) {
                stream.filter(p -> p.getFileName() != null
                        && Strings.CS.endsWith(p.getFileName().toString(), ".json"))
                    .sorted()
                    .forEach(p -> {
                        JsonNode drop = readJson(mapper, p);
                        if (drop != null && drop.isObject()) {
                            mergeInto(merged, (ObjectNode) drop);
                        }
                    });
            } catch (IOException _) {
                // best-effort: a broken drop-in dir simply contributes nothing.
            }
        }
        return merged;
    }

    /** Recursively merges {@code src} into {@code dst} (object fields combined, scalars overwritten). */
    private static void mergeInto(ObjectNode dst, ObjectNode src) {
        Iterator<Map.Entry<String, JsonNode>> it = src.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String key = e.getKey();
            JsonNode sv = e.getValue();
            JsonNode dv = dst.get(key);
            if (sv.isObject() && dv != null && dv.isObject()) {
                mergeInto((ObjectNode) dv, (ObjectNode) sv);
            } else {
                dst.set(key, sv);
            }
        }
    }

    private static JsonNode readJson(ObjectMapper mapper, Path p) {
        if (!Files.isRegularFile(p)) {
            return null;
        }
        try {
            return mapper.readTree(p.toFile());
        } catch (IOException _) {
            return null;
        }
    }

    /**
     * Extracts the dangerous settings (dangerous shell settings, non-safe env vars, and any hooks) from
     * a merged settings object.
     */
    public static DangerousSettings extractDangerousSettings(JsonNode settings) {
        Map<String, String> shell = new LinkedHashMap<>();
        if (settings != null && settings.isObject()) {
            for (String key : DANGEROUS_SHELL_SETTINGS) {
                JsonNode v = settings.get(key);
                if (v != null && v.isTextual() && !v.asText().isEmpty()) {
                    shell.put(key, v.asText());
                }
            }
        }
        Map<String, String> env = new LinkedHashMap<>();
        if (settings != null && settings.isObject()) {
            JsonNode envNode = settings.get("env");
            if (envNode != null && envNode.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = envNode.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    JsonNode v = e.getValue();
                    if (v != null && v.isTextual() && !v.asText().isEmpty()
                            && !SAFE_ENV_VARS.contains(e.getKey().toUpperCase(Locale.ROOT))) {
                        env.put(e.getKey(), v.asText());
                    }
                }
            }
        }
        boolean hasHooks = false;
        if (settings != null && settings.isObject()) {
            JsonNode hooks = settings.get("hooks");
            hasHooks = hooks != null && hooks.isObject() && !hooks.isEmpty();
        }
        return new DangerousSettings(shell, env, hasHooks);
    }

/**
     * True when any dangerous setting is present.
     */
    public static boolean hasDangerousSettings(DangerousSettings d) {
        return d != null
            && (!d.shellSettings().isEmpty() || !d.envVars().isEmpty() || d.hasHooks());
    }


    public static List<String> formatDangerousSettingsList(DangerousSettings d) {
        List<String> items = new ArrayList<>();
        if (d == null) {
            return items;
        }
        // keep a stable order: shell settings (canonical order), then env vars, then hooks
        for (String key : DANGEROUS_SHELL_SETTINGS) {
            if (d.shellSettings().containsKey(key)) {
                items.add(key);
            }
        }
        // env vars sorted for determinism
        new TreeMap<>(d.envVars()).forEach((k, _) -> items.add(k));
        if (d.hasHooks()) {
            items.add("hooks");
        }
        return items;
    }

    /**
     * Returns the directory holding. The
     * Ant-only {@code CLAUDE_CODE_MANAGED_SETTINGS_PATH} override is
     * honored before the platform default, matching
     * this keeps the startup security gate
     * pointed at the same file source as the settings loader.
     */
    static Path getManagedFilePath() {
        return SettingsPathResolver.policySettingsDirectory();
    }
}
