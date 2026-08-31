package com.claudecode.mcp;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.process.SubprocessEnvironment;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared MCP helpers that don't belong to a single transport/manager.
 */
final class McpUtils {

    /**
     * Maximum length (in chars) of a tool or server description that we forward to the model.
     */
    static final int MAX_MCP_DESCRIPTION_LENGTH = 2048;

    private static final String TRUNCATION_MARKER = "… [truncated]";

    private McpUtils() { }

    /**
     * Expands {@code ${VAR}} and {@code ${VAR:-default}} references inside a single string, reading
     * from the process environment.
     */
    static String expandEnvVarsInString(String value, List<String> missingVars) {
        if (value == null) return null;
        Pattern p = Pattern.compile("\\$\\{([^}]+)}");
        Matcher m = p.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String varContent = m.group(1);
            // Split on :- to support default values (limit to 2 parts so a
            // default value that itself contains ":-" is preserved verbatim).
            String[] parts = varContent.split(":-", 2);
            String varName = parts[0];
            String defaultValue = parts.length > 1 ? parts[1] : null;
            String envValue = SubprocessEnvironment.get(varName);
            if (envValue != null) {
                m.appendReplacement(sb, Matcher.quoteReplacement(envValue));
            } else if (defaultValue != null) {
                m.appendReplacement(sb, Matcher.quoteReplacement(defaultValue));
            } else {
                missingVars.add(varName);
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Truncates a tool or server description to {@link #MAX_MCP_DESCRIPTION_LENGTH} chars, appending
     * {@code "… [truncated]"} when the original exceeded it.
     */
    static String truncateDescription(String desc) {
        if (desc == null) return null;
        if (desc.length() <= MAX_MCP_DESCRIPTION_LENGTH) return desc;
        return desc.substring(0, MAX_MCP_DESCRIPTION_LENGTH) + TRUNCATION_MARKER;
    }

    /**
     * Returns a log-safe version of a server's base URL: scheme + authority + path with the query
     * string stripped and any trailing slash removed.
     */
    static String getLoggingSafeMcpBaseUrl(McpServerConfig config) {
        if (config == null) return null;
        String url = config.url();
        if (StringUtils.isBlank(url)) return null;
        try {
            URI uri = new URI(url);
            URI base = new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, null);
            String safe = base.toString();
            if (Strings.CS.endsWith(safe, "/")) safe = safe.substring(0, safe.length() - 1);
            return safe;
        } catch (URISyntaxException _) {
            return null;
        }
    }
}
