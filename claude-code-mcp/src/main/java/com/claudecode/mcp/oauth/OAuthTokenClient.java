package com.claudecode.mcp.oauth;

import com.claudecode.mcp.McpException;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.text.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * OAuth 2.0 (RFC 6749) token endpoint client with PKCE (RFC 7636) support.
 */
public final class OAuthTokenClient {

    private static final MediaType FORM =
        MediaType.get("application/x-www-form-urlencoded; charset=utf-8");
    private final OkHttpClient http;

    public OAuthTokenClient() {
        this(OAuthHttpClient.shared());
    }

    OAuthTokenClient(OkHttpClient http) {
        this.http = Objects.requireNonNull(http, "http");
    }

    /**
     * authorization_code grant with PKCE. Returns the parsed token response
     * with {@code expiresAt} set from now + expires_in (or 0 if the AS
     * omitted expires_in — treat as "unknown, don't try to refresh").
     */
    public TokenResponse exchangeCode(
        String tokenEndpoint,
        String clientId,
        String clientSecret,
        String code,
        String codeVerifier,
        String redirectUri
    ) {
        return exchangeCode(tokenEndpoint, clientId, clientSecret, code, codeVerifier,
            redirectUri, null);
    }

    /** authorization_code grant with the RFC 8707 resource indicator. */
    public TokenResponse exchangeCode(
        String tokenEndpoint,
        String clientId,
        String clientSecret,
        String code,
        String codeVerifier,
        String redirectUri,
        String resource
    ) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "authorization_code");
        params.put("code", code);
        params.put("code_verifier", codeVerifier);
        params.put("redirect_uri", redirectUri);
        if (org.apache.commons.lang3.StringUtils.isNotBlank(resource)) {
            params.put("resource", resource);
        }
        return post(tokenEndpoint, clientId, clientSecret, params);
    }

    /**
     * refresh_token grant. Returns a fresh access_token — some ASes rotate
     * the refresh_token on each use ({@code TokenResponse#refreshToken}
     * will be non-null), others return the same one.
     */
    public TokenResponse refresh(
        String tokenEndpoint,
        String clientId,
        String clientSecret,
        String refreshToken
    ) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "refresh_token");
        params.put("refresh_token", refreshToken);
        return post(tokenEndpoint, clientId, clientSecret, params);
    }

    private TokenResponse post(
        String tokenEndpoint,
        String clientId,
        String clientSecret,
        Map<String, String> baseParams
    ) {
        Map<String, String> params = new LinkedHashMap<>(baseParams);
        // Public client: client_id goes in the body, no secret.
        // Confidential client: Basic auth on the Authorization header (see below).
        if (clientId != null && clientSecret == null) {
            params.put("client_id", clientId);
        }

        String body = encodeForm(params);
        try {
            LinkedHashMap<String, String> headers = new LinkedHashMap<>();
            headers.put("Accept", "application/json");
            headers.put("Content-Type", "application/x-www-form-urlencoded");
            if (clientId != null && clientSecret != null) {
                // RFC 6749 §2.3.1 default. Strict ASes reject credentials in the
                // body when they aren't explicitly allowed via
                // token_endpoint_auth_methods_supported: ["client_secret_post"].
                String basic = Base64.getEncoder().encodeToString(
                    (URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                        + ":"
                        + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8))
                        .getBytes(StandardCharsets.UTF_8));
                headers.put("Authorization", "Basic " + basic);
            }
            Request.Builder request = new Request.Builder()
                .url(tokenEndpoint)
                .header("User-Agent", "claude-code-java/mcp-oauth")
                .post(RequestBody.create(body, FORM));
            headers.forEach(request::header);
            try (Response response = http.newCall(request.build()).execute()) {
                String responseBody = response.body().string();
                int status = response.code();
                if (status >= 400) {
                    JsonNode error = JsonUtils.getMapper().readTree(responseBody);
                    String description = txt(error, "error_description");
                    if (description != null) throw new McpException(description);
                    throw new McpException("Token endpoint returned HTTP " + status
                        + ": " + StringUtils.truncateWithSuffix(responseBody, 200, "..."));
                }
                JsonNode json = JsonUtils.getMapper().readTree(responseBody);
                String accessToken = txt(json, "access_token");
                if (org.apache.commons.lang3.StringUtils.isBlank(accessToken)) {
                    throw new McpException("Token response missing access_token: "
                        + StringUtils.truncateWithSuffix(responseBody, 200, "..."));
                }
                long expiresIn = json.has("expires_in") ? json.get("expires_in").asLong(0) : 0L;
                long expiresAt = expiresIn > 0 ? System.currentTimeMillis() + expiresIn * 1000 : 0L;
                return new TokenResponse(
                    accessToken,
                    txt(json, "refresh_token"),
                    txt(json, "token_type"),
                    txt(json, "scope"),
                    expiresIn,
                    expiresAt);
            }
        } catch (McpException e) {
            throw e;
        } catch (Exception e) {
            throw new McpException("Token request failed: " + e.getMessage(), e);
        }
    }

    private static String encodeForm(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (var e : params.entrySet()) {
            if (!first) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
            sb.append('=');
            sb.append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(),
                StandardCharsets.UTF_8));
            first = false;
        }
        return sb.toString();
    }

    private static String txt(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return (v != null && v.isTextual() && !org.apache.commons.lang3.StringUtils.isBlank(v.asText())) ? v.asText() : null;
    }


    /**
     * Parsed access-token response.
     *
     * @param accessToken  bearer token to attach to MCP requests
     * @param refreshToken refresh token if the AS issued one (nullable)
     * @param tokenType    typically {@code "Bearer"}
     * @param scope        granted scope string (may differ from requested)
     * @param expiresIn    seconds until expiry from response
     * @param expiresAt    epoch millis when expiry occurs (0 = unknown/never)
     */
    public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        String scope,
        long   expiresIn,
        long   expiresAt
    ) {}
}
