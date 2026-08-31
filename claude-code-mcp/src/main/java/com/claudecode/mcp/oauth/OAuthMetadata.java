package com.claudecode.mcp.oauth;

import java.util.List;

/**
 * Subset of RFC 8414 OAuth 2.0 Authorization Server Metadata used by the MCP client.
 */
public record OAuthMetadata(
    String issuer,
    String authorizationEndpoint,
    String tokenEndpoint,
    String registrationEndpoint,
    String revocationEndpoint,
    List<String> scopesSupported,
    List<String> responseTypesSupported,
    List<String> grantTypesSupported,
    List<String> codeChallengeMethodsSupported,
    List<String> tokenEndpointAuthMethodsSupported
) {
    public OAuthMetadata {
        if (scopesSupported == null) scopesSupported = List.of();
        if (responseTypesSupported == null) responseTypesSupported = List.of();
        if (grantTypesSupported == null) grantTypesSupported = List.of();
        if (codeChallengeMethodsSupported == null) codeChallengeMethodsSupported = List.of();
        if (tokenEndpointAuthMethodsSupported == null) tokenEndpointAuthMethodsSupported = List.of();
    }

    /**
     * True iff the server accepts (or unambiguously requires) PKCE S256.
     * Per RFC 8414 §2, when the field is absent the server is understood to
     * default to {@code "plain"} — we still return true so callers get to
     * try S256 first (nearly every real-world AS supports it).
     */
    public boolean supportsPkceS256() {
        return codeChallengeMethodsSupported.isEmpty()
            || codeChallengeMethodsSupported.contains("S256");
    }
}
