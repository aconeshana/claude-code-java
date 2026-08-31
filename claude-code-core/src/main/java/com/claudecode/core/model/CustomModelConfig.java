package com.claudecode.core.model;

import com.claudecode.core.annotation.Explanation;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;




@Explanation("User-defined Anthropic, Chat Completions, and Responses endpoints")
public record CustomModelConfig(
        @JsonProperty("modelName") String modelName,
        @JsonProperty("protocol") ModelApiProtocol protocol,
        @JsonProperty("baseUrl") String baseUrl,
        @JsonProperty("apiKey") String apiKey,
        @JsonProperty("headers") Map<String, String> headers,
        @JsonProperty("contextWindow") Long contextWindow
) {
    public static final long DEFAULT_CONTEXT_WINDOW = ModelContextWindows.DEFAULT_CONTEXT_WINDOW;
    private static final long MIN_CONTEXT_WINDOW = 40_000L;
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    @JsonCreator
    public CustomModelConfig {
        modelName = requireText(modelName, "Model name");
        if (protocol == null) throw new IllegalArgumentException("Protocol is required");
        baseUrl = normalizeBaseUrl(baseUrl);
        apiKey = blankToNull(apiKey);
        headers = normalizeHeaders(headers);
        if (contextWindow != null && contextWindow < MIN_CONTEXT_WINDOW) {
            throw new IllegalArgumentException("Context window must be at least " + MIN_CONTEXT_WINDOW);
        }
    }

    public CustomModelConfig(String modelName, ModelApiProtocol protocol, String baseUrl,
                             String apiKey, Map<String, String> headers) {
        this(modelName, protocol, baseUrl, apiKey, headers, null);
    }

    public long effectiveContextWindow() {
        return contextWindow != null
            ? contextWindow : ModelContextWindows.defaultContextWindow(modelName);
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = requireText(value, "Base URL");
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Base URL must be a valid HTTP(S) URL", e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(Strings.CI.equals("http", scheme) || Strings.CI.equals("https", scheme))
                || uri.getHost() == null) {
            throw new IllegalArgumentException("Base URL must be a valid HTTP(S) URL");
        }
        while (Strings.CS.endsWith(normalized, "/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static Map<String, String> normalizeHeaders(Map<String, String> input) {
        if (input == null || input.isEmpty()) return Map.of();
        Map<String, String> copy = new LinkedHashMap<>();
        input.forEach((rawName, rawValue) -> {
            String name = requireText(rawName, "Header name");
            if (!HEADER_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("Invalid header name: " + name);
            }
            String value = rawValue == null ? "" : rawValue.trim();
            if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("Header values cannot contain line breaks");
            }
            copy.put(name, value);
        });
        return Map.copyOf(copy);
    }

    private static String requireText(String value, String field) {
        if (StringUtils.isBlank(value)) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String blankToNull(String value) {
        return StringUtils.isBlank(value) ? null : value.trim();
    }

    @Override
    public String toString() {
        return "CustomModelConfig[modelName=" + modelName + ", protocol=" + protocol
            + ", baseUrl=" + baseUrl + ", apiKey=<redacted>, headers=" + headers.keySet()
            + ", contextWindow=" + contextWindow + "]";
    }
}
