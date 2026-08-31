package com.claudecode.services.plugins.runtime;

import com.claudecode.services.plugins.marketplace.UserConfigOption;
import com.claudecode.core.process.SubprocessEnvironment;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Variable substitution pipeline for plugin-provided content (command/agent markdown, hook
 * commands, MCP server configs).
 */
public final class PluginVariables {

    private static final Pattern PLUGIN_ROOT = Pattern.compile("\\$\\{CLAUDE_PLUGIN_ROOT}");
    private static final Pattern PLUGIN_DATA = Pattern.compile("\\$\\{CLAUDE_PLUGIN_DATA}");
    private static final Pattern SESSION_ID = Pattern.compile("\\$\\{CLAUDE_SESSION_ID}");
    private static final Pattern USER_CONFIG = Pattern.compile("\\$\\{user_config\\.([^}]+)}");
    private static final Pattern ENV_VAR = Pattern.compile("\\$\\{([^}]+)}");

    private PluginVariables() {}

    /**
     * Full content pipeline for command/agent prose: plugin paths → content-safe user_config → session
     * ID.
     */
    public static String substitute(String content, Path pluginRoot, Path dataDir,
                                    Map<String, String> userConfig,
                                    Map<String, UserConfigOption> schema,
                                    String sessionId) {
        String out = substitutePluginPaths(content, pluginRoot, dataDir);
        out = substituteUserConfigInContent(out, userConfig, schema);
        if (sessionId != null) {
            out = SESSION_ID.matcher(out).replaceAll(Matcher.quoteReplacement(sessionId));
        }
        return out;
    }

    /** {@code ${CLAUDE_PLUGIN_ROOT}} / {@code ${CLAUDE_PLUGIN_DATA}} → paths. */
    public static String substitutePluginPaths(String value, Path pluginRoot, Path dataDir) {
        String out = value;
        if (pluginRoot != null) {
            out = PLUGIN_ROOT.matcher(out).replaceAll(Matcher.quoteReplacement(pluginRoot.toString()));
        }
        if (dataDir != null) {
            out = PLUGIN_DATA.matcher(out).replaceAll(Matcher.quoteReplacement(dataDir.toString()));
        }
        return out;
    }


    public static String substituteUserConfig(String value, Map<String, String> userConfig) {
        Matcher m = USER_CONFIG.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String configValue = userConfig != null ? userConfig.get(key) : null;
            if (configValue == null) {
                throw new IllegalStateException(
                    "Missing required user configuration value: " + key + ". "
                        + "This should have been validated before variable substitution.");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(configValue));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Content-safe variant for skill/agent/command prose: sensitive keys become
     * a descriptive placeholder (their values must never enter the model
     * prompt), unknown keys stay literal.
     */
    public static String substituteUserConfigInContent(String content,
                                                       Map<String, String> userConfig,
                                                       Map<String, UserConfigOption> schema) {
        Matcher m = USER_CONFIG.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            UserConfigOption option = schema != null ? schema.get(key) : null;
            String replacement;
            if (option != null && Boolean.TRUE.equals(option.sensitive())) {
                replacement = "[sensitive option '" + key + "' not available in skill content]";
            } else {
                String value = userConfig != null ? userConfig.get(key) : null;
                replacement = value != null ? value : m.group(0);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Expands {@code ${VAR}} / {@code ${VAR:-default}} against the process
     * environment. Missing variables (no default) stay literal and are added
     * to {@code missingVars} for error reporting.
     */
    public static String expandEnvVars(String value, Collection<String> missingVars) {
        return expandEnvVars(value, missingVars, SubprocessEnvironment.snapshot());
    }

    /** Testable overload with an explicit environment map. */
    static String expandEnvVars(String value, Collection<String> missingVars, Map<String, String> env) {
        Matcher m = ENV_VAR.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String content = m.group(1);
            int sep = content.indexOf(":-");
            String varName = sep >= 0 ? content.substring(0, sep) : content;
            String defaultValue = sep >= 0 ? content.substring(sep + 2) : null;
            String envValue = env.get(varName);
            String replacement;
            if (envValue != null) {
                replacement = envValue;
            } else if (defaultValue != null) {
                replacement = defaultValue;
            } else {
                if (missingVars != null) {
                    missingVars.add(varName);
                }
                replacement = m.group(0);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
