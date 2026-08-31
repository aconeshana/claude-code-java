package com.claudecode.mcp.oauth;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.mcp.McpException;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RFC 9728 (Protected Resource Metadata) + RFC 8414 (Authorization Server Metadata) discovery for
 * MCP servers that require OAuth.
 */
public final class McpOAuthDiscovery {

    private static final Logger LOG = LoggerFactory.getLogger(McpOAuthDiscovery.class);

    // Bearer challenge parameter extractor. Matches k=v and k="v", tolerates
    // whitespace and multi-param challenges. NOT a general RFC 7235 parser —
    // it deliberately handles only the shape MCP servers actually emit.
    private static final Pattern BEARER_PARAM =
        Pattern.compile("(?i)\\b([a-z_]+)\\s*=\\s*(?:\"([^\"]+)\"|([^,\\s]+))");

    private final OkHttpClient http;

    public McpOAuthDiscovery() {
        this(OAuthHttpClient.shared());
    }

    McpOAuthDiscovery(OkHttpClient http) {
        this.http = Objects.requireNonNull(http, "http");
    }

    /**
     * Discovers the authorization server metadata for the given MCP server URL.
     *
     * @param mcpServerUrl        the MCP endpoint (e.g. <a href="https://api.githubcopilot.com/mcp/">...</a>)
     * @param resourceMetadataUrl explicit resource-metadata URL surfaced by a
     *                            prior 401 challenge, or {@code null} to probe
     * @return the resolved authorization server metadata; never {@code null}
     * @throws McpException if discovery fails at every stage
     */
    public OAuthMetadata discover(String mcpServerUrl, String resourceMetadataUrl) {
        if (StringUtils.isBlank(mcpServerUrl)) {
            throw new McpException("Cannot discover OAuth metadata: MCP server URL is blank");
        }
        URI mcpUri;
        try {
            mcpUri = new URI(mcpServerUrl);
        } catch (URISyntaxException e) {
            throw new McpException("Invalid MCP server URL: " + mcpServerUrl, e);
        }
        requireHttps(mcpUri, "MCP server URL");

        // Stage 1: RFC 9728 → RFC 8414.
        try {
            URI resourceMetaUri = resourceMetadataUrl != null
                ? new URI(resourceMetadataUrl)
                : wellKnownProtectedResource(mcpUri);
            requireHttps(resourceMetaUri, "resource metadata URL");
            String authServerUrl = fetchAuthorizationServerFromResource(resourceMetaUri);
            if (authServerUrl != null) {
                URI authServerUri = new URI(authServerUrl);
                // RFC 9728/8414 metadata must not downgrade to plaintext after
                // the protected-resource document selects an authorization
                // server.  Without this check a malicious or compromised MCP
                // metadata response could redirect OAuth discovery (and later
                // token exchange) to an HTTP endpoint.
                requireHttps(authServerUri, "authorization server URL");
                URI asMeta = wellKnownAuthorizationServer(authServerUri);
                requireHttps(asMeta, "authorization server metadata URL");
                OAuthMetadata metadata = fetchAuthServerMetadata(asMeta);
                if (metadata != null) return metadata;
            }
        } catch (McpException e) {
            LOG.debug("RFC 9728 discovery failed for {}: {}", mcpServerUrl, e.getMessage());
        } catch (URISyntaxException e) {
            LOG.debug("Malformed URL during discovery for {}: {}", mcpServerUrl, e.getMessage());
        }

        // Stage 2: legacy path-aware fallback (only meaningful when the MCP URL
        // has a path — root URLs already got probed as .well-known above).
        if (mcpUri.getPath() != null && !Strings.CS.equals(mcpUri.getPath(), "/") && !mcpUri.getPath().isEmpty()) {
            try {
                URI asMeta = wellKnownAuthorizationServer(mcpUri);
                OAuthMetadata metadata = fetchAuthServerMetadata(asMeta);
                if (metadata != null) return metadata;
            } catch (McpException | URISyntaxException e) {
                LOG.debug("Path-aware RFC 8414 fallback failed for {}: {}",
                    mcpServerUrl, e.getMessage());
            }
        }

        throw new McpException("Could not discover OAuth authorization server metadata for "
            + mcpServerUrl + " — neither RFC 9728 probe nor RFC 8414 fallback returned a valid response");
    }

    /**
     * Convenience: pass a WWW-Authenticate header verbatim, get back the
     * {@code resource_metadata} URL if the challenge included one.
     * <p>Format we handle: {@code Bearer realm="...", resource_metadata="https://..."}.
     */
    public static Optional<String> extractResourceMetadataUrl(String wwwAuthenticateHeader) {
        if (StringUtils.isBlank(wwwAuthenticateHeader)) {
            return Optional.empty();
        }
        Matcher m = BEARER_PARAM.matcher(wwwAuthenticateHeader);
        while (m.find()) {
            if (Strings.CI.equals("resource_metadata", m.group(1))) {
                String quoted = m.group(2);
                String unquoted = m.group(3);
                return Optional.of(quoted != null ? quoted : unquoted);
            }
        }
        return Optional.empty();
    }

    // ── HTTP helpers ────────────────────────────────────────────────────────

    private OAuthMetadata fetchAuthServerMetadata(URI url) {
        JsonNode json = fetchJson(url, "authorization server metadata");
        if (json == null) return null;
        return parseMetadata(json);
    }

    private String fetchAuthorizationServerFromResource(URI url) {
        JsonNode json = fetchJson(url, "protected resource metadata");
        if (json == null) return null;
        JsonNode servers = json.get("authorization_servers");
        if (servers == null || !servers.isArray() || servers.isEmpty()) {
            LOG.debug("Resource metadata at {} did not list authorization_servers", url);
            return null;
        }
        JsonNode first = servers.get(0);
        return first != null && first.isTextual() ? first.asText() : null;
    }

    private JsonNode fetchJson(URI url, String label) {
        Request request = new Request.Builder()
            .url(url.toString())
            .header("Accept", "application/json")
            .header("User-Agent", "claude-code-java/mcp-oauth")
            .get()
            .build();
        try (Response response = http.newCall(request).execute()) {
            if (response.code() != 200) {
                LOG.debug("{} at {} → HTTP {}", label, url, response.code());
                return null;
            }
            String responseBody = response.body().string();
            return JsonUtils.getMapper().readTree(responseBody);
        } catch (Exception e) {
            LOG.debug("Failed to fetch {} from {}: {}", label, url, e.getMessage());
            return null;
        }
    }

    // ── URL construction ────────────────────────────────────────────────────

    /**
     * Builds {@code {scheme}://{host}/.well-known/oauth-protected-resource[/{path}]}.
     * When the MCP URL has a non-root path we append it — that's the shape a
     * few real-world servers advertise.
     */
    static URI wellKnownProtectedResource(URI mcpUri) throws URISyntaxException {
        String base = mcpUri.getScheme() + "://" + mcpUri.getRawAuthority();
        String path = mcpUri.getRawPath();
        String suffix = (StringUtils.isEmpty(path) || Strings.CS.equals(path, "/")) ? "" : path;
        return new URI(base + "/.well-known/oauth-protected-resource" + suffix);
    }

    /**
     * Builds {@code {scheme}://{host}/.well-known/oauth-authorization-server[/{path}]}
     * matching RFC 8414 §3 (with the pre-RFC path-aware variant many
     * real-world servers still use).
     */
    static URI wellKnownAuthorizationServer(URI authServerUri) throws URISyntaxException {
        String base = authServerUri.getScheme() + "://" + authServerUri.getRawAuthority();
        String path = authServerUri.getRawPath();
        String suffix = (StringUtils.isEmpty(path) || Strings.CS.equals(path, "/")) ? "" : path;
        return new URI(base + "/.well-known/oauth-authorization-server" + suffix);
    }

    private static void requireHttps(URI uri, String label) {
        String scheme = uri.getScheme();
        if (scheme == null || !Strings.CI.equals(scheme, "https")) {
            throw new McpException(label + " must use https:// (got: " + uri + ")");
        }
    }

    // ── Parsing ─────────────────────────────────────────────────────────────

    static OAuthMetadata parseMetadata(JsonNode json) {
        if (json == null || !json.isObject()) return null;
        return new OAuthMetadata(
            optString(json, "issuer"),
            optString(json, "authorization_endpoint"),
            optString(json, "token_endpoint"),
            optString(json, "registration_endpoint"),
            optString(json, "revocation_endpoint"),
            stringList(json, "scopes_supported"),
            stringList(json, "response_types_supported"),
            stringList(json, "grant_types_supported"),
            stringList(json, "code_challenge_methods_supported"),
            stringList(json, "token_endpoint_auth_methods_supported")
        );
    }

    private static String optString(JsonNode node, String key) {
        JsonNode v = node.get(key);
        return (v != null && v.isTextual() && !StringUtils.isBlank(v.asText())) ? v.asText() : null;
    }

    private static List<String> stringList(JsonNode node, String key) {
        JsonNode v = node.get(key);
        if (v == null || !v.isArray()) return List.of();
        List<String> out = new ArrayList<>(v.size());
        for (JsonNode item : v) {
            if (item.isTextual()) out.add(item.asText());
        }
        return List.copyOf(out);
    }
}
