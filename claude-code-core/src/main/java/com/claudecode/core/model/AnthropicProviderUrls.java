package com.claudecode.core.model;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.net.URI;
import java.util.Locale;

/**
 * Anthropic provider URL predicates shared by request and feature gates.
 */
public final class AnthropicProviderUrls {

    private static final String FIRST_PARTY_HOST = "api.anthropic.com";

    private AnthropicProviderUrls() {}




    public static boolean isFirstPartyBaseUrl(String baseUrl) {
        if (StringUtils.isEmpty(baseUrl)) return true;
        try {
            URI uri = URI.create(baseUrl.strip());
            String host = uri.getHost();
            if (host == null) return false;
            int port = normalizedPort(uri);
            return port == -1 && Strings.CI.equals(FIRST_PARTY_HOST, host);
        } catch (IllegalArgumentException _) {
            return false;
        }
    }

    /** WHATWG URL.host omits a scheme's default port but retains custom ports. */
    private static int normalizedPort(URI uri) {
        int port = uri.getPort();
        if (port == -1) return -1;
        String scheme = uri.getScheme();
        if (scheme == null) return port;
        return switch (scheme.toLowerCase(Locale.ROOT)) {
            case "http" -> port == 80 ? -1 : port;
            case "https" -> port == 443 ? -1 : port;
            default -> port;
        };
    }
}
