package com.claudecode.services.hooks;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.claudecode.core.process.SubprocessEnvironment;

import org.apache.commons.lang3.StringUtils;
/**
 * HTTP hook — POSTs hook input JSON to a URL.
 * Supports environment variable interpolation in headers.
 */
public record HttpHook(
    String url,
    Optional<String> ifCondition,
    Optional<Integer> timeoutSeconds,
    Map<String, String> headers,
    List<String> allowedEnvVars,
    Optional<String> statusMessage,
    boolean once
) implements HookCommand {

    private static final Pattern ENV_REFERENCE =
        Pattern.compile("\\$\\{([A-Z_][A-Z0-9_]*)\\}|\\$([A-Z_][A-Z0-9_]*)");

    public HttpHook(String url) {
        this(url, Optional.empty(), Optional.empty(), Map.of(), List.of(),
            Optional.empty(), false);
    }

    /**
     * Resolves this hook's own environment allowlist. Kept as a compatibility
     * seam for callers that do not have the global policy snapshot.
     */
    public Map<String, String> resolvedHeaders() {
        return resolvedHeaders(new HashSet<>(allowedEnvVars));
    }

    /**
     * Resolves and sanitizes header values using an already-intersected env
     * allowlist. Both {@code $VAR} and {@code ${VAR}} are accepted. Missing or
     * disallowed variables become the empty string, and CR/LF/NUL are removed
     * after interpolation so static and expanded values share the same guard.
     */
    public Map<String, String> resolvedHeaders(Set<String> effectiveAllowedEnvVars) {
        if (headers.isEmpty()) return Map.of();
        Set<String> allowed = effectiveAllowedEnvVars == null
            ? Set.of() : Set.copyOf(effectiveAllowedEnvVars);
        Map<String, String> resolved = new HashMap<>();
        for (var entry : headers.entrySet()) {
            resolved.put(entry.getKey(), interpolateAndSanitize(entry.getValue(), allowed));
        }
        return Map.copyOf(resolved);
    }

    static String interpolateAndSanitize(String value, Set<String> allowedEnvVars) {
        if (value == null) return "";
        Matcher matcher = ENV_REFERENCE.matcher(value);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            String replacement = allowedEnvVars.contains(name)
                ? Optional.ofNullable(SubprocessEnvironment.get(name)).orElse("")
                : "";
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return stripHeaderControls(out.toString());
    }

    static String stripHeaderControls(String value) {
        if (StringUtils.isEmpty(value)) return value == null ? "" : value;
        StringBuilder sanitized = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\r' && c != '\n' && c != '\0') sanitized.append(c);
        }
        return sanitized.toString();
    }
}
