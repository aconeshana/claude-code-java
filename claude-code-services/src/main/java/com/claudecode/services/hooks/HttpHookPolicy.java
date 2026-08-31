package com.claudecode.services.hooks;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Effective process-wide policy for HTTP hooks.
 */
public record HttpHookPolicy(
    List<String> allowedUrls,
    List<String> allowedEnvVars
) {

    /** Policy used by programmatic/test hook engines when no settings are wired. */
    public static HttpHookPolicy unrestricted() {
        return new HttpHookPolicy(null, null);
    }

    /** Returns whether a URL is allowed by the effective global URL policy. */
    public boolean allowsUrl(String url) {
        return allowedUrls == null
            || allowedUrls.stream().anyMatch(pattern -> urlMatchesPattern(url, pattern));
    }


    static boolean urlMatchesPattern(String url, String pattern) {
        if (url == null || pattern == null) return false;
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                regex.append(".*");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        regex.append('$');
        return Pattern.compile(regex.toString(), Pattern.DOTALL).matcher(url).matches();
    }

    /** Effective env names for one hook; global absence means hook-only policy. */
    public List<String> effectiveEnvVars(List<String> hookAllowedEnvVars) {
        List<String> hookVars = hookAllowedEnvVars == null ? List.of() : hookAllowedEnvVars;
        if (allowedEnvVars == null) return List.copyOf(hookVars);
        return hookVars.stream().filter(allowedEnvVars::contains).toList();
    }
}
