package com.claudecode.mcp.oauth;

import com.claudecode.mcp.McpException;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.text.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RFC 7591 Dynamic Client Registration for MCP servers.
 */
public final class DynamicClientRegistration {

    private static final Logger LOG = LoggerFactory.getLogger(DynamicClientRegistration.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient http;

    public DynamicClientRegistration() {
        this(OAuthHttpClient.shared());
    }

    DynamicClientRegistration(OkHttpClient http) {
        this.http = Objects.requireNonNull(http, "http");
    }

    /**
     * Registers a client at the given endpoint.
     *
     * @param registrationEndpoint URL from {@link OAuthMetadata#registrationEndpoint};
     *                             callers must have verified it's non-null.
     * @param serverName           MCP server label (used in {@code client_name})
     * @param redirectUri          loopback URI the AS should redirect to
     * @param scopes               optional scopes; passed verbatim
     * @return a fresh DCR result ready to store in SecureStorage
     * @throws McpException if the endpoint returns non-2xx or the response can't be parsed
     */
    public DcrResult register(
        String registrationEndpoint,
        String serverName,
        String redirectUri,
        List<String> scopes
    ) {
        if (org.apache.commons.lang3.StringUtils.isBlank(registrationEndpoint)) {
            throw new McpException("Cannot register OAuth client — registration_endpoint is missing");
        }

        ObjectNode body = JsonUtils.getMapper().createObjectNode();
        body.put("client_name", "Claude Code (" + serverName + ")");
        body.putArray("redirect_uris").add(redirectUri);
        body.putArray("grant_types").add("authorization_code").add("refresh_token");
        body.putArray("response_types").add("code");
        body.put("token_endpoint_auth_method", "none");
        if (scopes != null && !scopes.isEmpty()) {
            body.put("scope", String.join(" ", scopes));
        }

        try {
            String payload = JsonUtils.getMapper().writeValueAsString(body);
            Request request = new Request.Builder()
                .url(registrationEndpoint)
                .header("Accept", "application/json")
                .header("User-Agent", "claude-code-java/mcp-oauth")
                .post(RequestBody.create(payload, JSON))
                .build();
            try (Response response = http.newCall(request).execute()) {
                String responseBody = response.body().string();
                if (!response.isSuccessful()) {
                    throw new McpException("DCR failed for " + serverName + ": HTTP "
                        + response.code() + ": "
                        + StringUtils.truncateWithSuffix(responseBody, 200, "..."));
                }
                JsonNode json = JsonUtils.getMapper().readTree(responseBody);
                String clientId = txt(json, "client_id");
                if (org.apache.commons.lang3.StringUtils.isBlank(clientId)) {
                    throw new McpException("DCR response missing client_id: "
                        + StringUtils.truncateWithSuffix(responseBody, 200, "..."));
                }
                String clientSecret = txt(json, "client_secret");
                LOG.info("[oauth] Registered dynamic client for {} (client_id={}, secret={})",
                    serverName, clientId, clientSecret != null ? "yes" : "no");
                return new DcrResult(clientId, clientSecret, registrationEndpoint);
            }
        } catch (McpException e) {
            throw e;
        } catch (Exception e) {
            throw new McpException("DCR request failed: " + e.getMessage(), e);
        }
    }


    public Optional<DcrResult> resolveClient(
        SecureStorage storage,
        String serverKey,
        String serverName,
        OAuthMetadata metadata,
        String redirectUri,
        List<String> scopes,
        String configClientId,
        String configClientSecret
    ) {
        // 1. Previously stored
        var stored = storage.read().flatMap(d ->
            Optional.ofNullable(d.mcpOAuthClientConfig().get(serverKey)));
        if (stored.isPresent()) {
            LOG.debug("[oauth] Reusing stored DCR client for {}", serverName);
            return stored.map(c -> new DcrResult(
                c.clientId(), c.clientSecret(), c.registrationEndpoint()));
        }
        // 2. Config-provided
        if (org.apache.commons.lang3.StringUtils.isNotBlank(configClientId)) {
            LOG.debug("[oauth] Using pre-configured client_id for {}", serverName);
            return Optional.of(new DcrResult(configClientId, configClientSecret, null));
        }
        // 3. Dynamic registration
        if (metadata.registrationEndpoint() == null) {
            LOG.warn("[oauth] {}: no stored client, no configured client_id, and AS advertises no registration_endpoint — cannot authenticate",
                serverName);
            return Optional.empty();
        }
        DcrResult fresh = register(metadata.registrationEndpoint(),
            serverName, redirectUri, scopes);
        // Persist so next authenticate skips this.
        persistClientConfig(storage, serverKey, fresh);
        return Optional.of(fresh);
    }

    private static void persistClientConfig(SecureStorage storage, String serverKey, DcrResult r) {
        SecureStorageData data = storage.read().orElseGet(SecureStorageData::empty);
        data.mcpOAuthClientConfig().put(serverKey,
            new SecureStorageData.McpOAuthClientConfigEntry(
                r.clientId(), r.clientSecret(), r.registrationEndpoint(),
                System.currentTimeMillis()));
        storage.update(data);
    }

    private static String txt(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return (v != null && v.isTextual() && !org.apache.commons.lang3.StringUtils.isBlank(v.asText())) ? v.asText() : null;
    }


    /**
     * DCR return value / cache entry.
     *
     * @param clientId            the {@code client_id} to use on subsequent authorize/token calls
     * @param clientSecret        when non-null, must be sent on token exchange
     *                            (server treats us as confidential); typically null
     *                            since we advertise {@code token_endpoint_auth_method: none}
     * @param registrationEndpoint the AS endpoint that minted these credentials
     */
    public record DcrResult(String clientId, String clientSecret, String registrationEndpoint) {}
}
