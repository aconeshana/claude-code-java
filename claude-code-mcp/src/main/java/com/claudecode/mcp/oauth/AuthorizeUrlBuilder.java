package com.claudecode.mcp.oauth;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds RFC 6749 §4.1 (authorization code grant) authorize URLs with the RFC 7636 (PKCE)
 * extension.
 */
public final class AuthorizeUrlBuilder {

    private static final SecureRandom RNG = new SecureRandom();

    private AuthorizeUrlBuilder() {}

    /**
     * Result of building an authorize URL. The {@code state} and
     * {@code pkce.verifier} must be retained for the callback + token
     * exchange steps.
     */
    public record BuiltUrl(String url, String state, PkcePair pkce) {}

    /**
     * Builds the authorize URL and generates fresh PKCE + state values.
     *
     * @param authorizationEndpoint from {@link OAuthMetadata#authorizationEndpoint}
     * @param clientId              client_id from DCR or config
     * @param redirectUri           loopback URL like {@code http://127.0.0.1:PORT/callback}
     * @param scopes                requested scopes; joined with a single space per RFC 6749 §3.3
     * @param resource              optional {@code resource} parameter (RFC 8707); pass
     *                              {@code null} to omit
     */
    public static BuiltUrl build(
        String authorizationEndpoint,
        String clientId,
        String redirectUri,
        List<String> scopes,
        String resource
    ) {
        if (StringUtils.isBlank(authorizationEndpoint)) {
            throw new IllegalArgumentException("authorization_endpoint must not be blank");
        }
        if (StringUtils.isBlank(clientId)) {
            throw new IllegalArgumentException("client_id must not be blank");
        }
        if (StringUtils.isBlank(redirectUri)) {
            throw new IllegalArgumentException("redirect_uri must not be blank");
        }

        PkcePair pkce = PkcePair.generate();
        String state = randomState();

        // LinkedHashMap so parameters render in a stable, human-scannable order —
        // useful when a user copies the URL from the terminal to a browser.
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", clientId);
        params.put("redirect_uri", redirectUri);
        params.put("code_challenge", pkce.challenge());
        params.put("code_challenge_method", "S256");
        params.put("state", state);
        if (scopes != null && !scopes.isEmpty()) {
            params.put("scope", String.join(" ", scopes));
        }
        if (StringUtils.isNotBlank(resource)) {
            params.put("resource", resource);
        }

        StringBuilder sb = new StringBuilder(authorizationEndpoint);
        sb.append(Strings.CS.contains(authorizationEndpoint, "?") ? '&' : '?');
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
            sb.append('=');
            sb.append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            first = false;
        }

        return new BuiltUrl(sb.toString(), state, pkce);
    }

    /**
     * Fresh CSRF state token — 24 raw bytes of entropy → 32 base64url characters.
     */
    private static String randomState() {
        byte[] raw = new byte[24];
        RNG.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
