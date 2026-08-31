package com.claudecode.mcp.oauth;

import org.apache.commons.lang3.Strings;

import com.claudecode.mcp.McpException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class McpOAuthDiscoveryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void extractResourceMetadataUrl_quotedValue() {
        Optional<String> r = McpOAuthDiscovery.extractResourceMetadataUrl(
            "Bearer realm=\"mcp\", resource_metadata=\"https://a.example/.well-known/oauth-protected-resource\"");
        assertTrue(r.isPresent());
        assertEquals("https://a.example/.well-known/oauth-protected-resource", r.get());
    }

    @Test
    void extractResourceMetadataUrl_unquotedValue() {
        Optional<String> r = McpOAuthDiscovery.extractResourceMetadataUrl(
            "Bearer resource_metadata=https://a.example/.well-known/oauth-protected-resource");
        assertTrue(r.isPresent());
        assertEquals("https://a.example/.well-known/oauth-protected-resource", r.get());
    }

    @Test
    void extractResourceMetadataUrl_missingReturnsEmpty() {
        assertTrue(McpOAuthDiscovery.extractResourceMetadataUrl("Bearer realm=\"mcp\"").isEmpty());
        assertTrue(McpOAuthDiscovery.extractResourceMetadataUrl("").isEmpty());
        assertTrue(McpOAuthDiscovery.extractResourceMetadataUrl(null).isEmpty());
    }

    @Test
    void wellKnownProtectedResource_rootUrl_omitsPathSuffix() throws Exception {
        URI r = McpOAuthDiscovery.wellKnownProtectedResource(URI.create("https://api.example.com/"));
        assertEquals("https://api.example.com/.well-known/oauth-protected-resource", r.toString());
    }

    @Test
    void wellKnownProtectedResource_pathUrl_appendsPathSuffix() throws Exception {
        URI r = McpOAuthDiscovery.wellKnownProtectedResource(URI.create("https://api.example.com/mcp"));
        assertEquals("https://api.example.com/.well-known/oauth-protected-resource/mcp", r.toString());
    }

    @Test
    void wellKnownAuthorizationServer_rootUrl() throws Exception {
        URI r = McpOAuthDiscovery.wellKnownAuthorizationServer(URI.create("https://as.example.com/"));
        assertEquals("https://as.example.com/.well-known/oauth-authorization-server", r.toString());
    }

    @Test
    void parseMetadata_roundtripsAllFields() throws Exception {
        var json = MAPPER.readTree("""
            {
              "issuer": "https://as.example.com",
              "authorization_endpoint": "https://as.example.com/authorize",
              "token_endpoint": "https://as.example.com/token",
              "registration_endpoint": "https://as.example.com/register",
              "code_challenge_methods_supported": ["S256"],
              "scopes_supported": ["read", "write"]
            }
            """);
        OAuthMetadata m = McpOAuthDiscovery.parseMetadata(json);
        assertNotNull(m);
        assertEquals("https://as.example.com",           m.issuer());
        assertEquals("https://as.example.com/authorize", m.authorizationEndpoint());
        assertEquals("https://as.example.com/token",     m.tokenEndpoint());
        assertEquals("https://as.example.com/register",  m.registrationEndpoint());
        assertTrue(m.supportsPkceS256());
        assertEquals(2, m.scopesSupported().size());
    }

    @Test
    void supportsPkceS256_defaultsTrueWhenFieldAbsent() {
        // RFC 8414 §2 says "plain" is the default if the field is omitted, but
        // practically every AS supports S256 — we try it first.
        OAuthMetadata m = new OAuthMetadata(null, null, null, null, null,
            null, null, null, null, null);
        assertTrue(m.supportsPkceS256());
    }

    @Test
    void supportsPkceS256_falseWhenOnlyPlainListed() {
        OAuthMetadata m = new OAuthMetadata(null, null, null, null, null,
            null, null, null, List.of("plain"), null);
        assertFalse(m.supportsPkceS256());
    }

    @Test
    void discoveryUsesInjectedOkHttpClientForBothMetadataRequests() {
        List<String> requestedUrls = new ArrayList<>();
        OkHttpClient http = new OkHttpClient.Builder()
            .addInterceptor(chain -> {
                String url = chain.request().url().toString();
                requestedUrls.add(url);
                String json = Strings.CS.contains(url, "oauth-protected-resource")
                    ? "{\"authorization_servers\":[\"https://auth.example\"]}"
                    : "{\"issuer\":\"https://auth.example\","
                        + "\"authorization_endpoint\":\"https://auth.example/authorize\","
                        + "\"token_endpoint\":\"https://auth.example/token\"}";
                return jsonResponse(chain.request(), json);
            })
            .build();

        OAuthMetadata metadata = new McpOAuthDiscovery(http)
            .discover("https://resource.example/mcp", null);

        assertEquals("https://auth.example", metadata.issuer());
        assertEquals(List.of(
            "https://resource.example/.well-known/oauth-protected-resource/mcp",
            "https://auth.example/.well-known/oauth-authorization-server"), requestedUrls);
    }

    @Test
    void discoveryRejectsPlaintextAuthorizationServerAdvertisedByResourceMetadata() {
        List<String> requestedUrls = new ArrayList<>();
        OkHttpClient http = new OkHttpClient.Builder()
            .addInterceptor(chain -> {
                requestedUrls.add(chain.request().url().toString());
                return jsonResponse(chain.request(),
                    "{\"authorization_servers\":[\"http://auth.example\"]}");
            })
            .build();

        assertThrows(McpException.class,
            () -> new McpOAuthDiscovery(http)
                .discover("https://resource.example/", null));
        assertEquals(List.of(
            "https://resource.example/.well-known/oauth-protected-resource"), requestedUrls);
    }

    private static Response jsonResponse(Request request, String json) throws IOException {
        return new Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", "application/json")
            .body(ResponseBody.create(json, MediaType.get("application/json")))
            .build();
    }
}
