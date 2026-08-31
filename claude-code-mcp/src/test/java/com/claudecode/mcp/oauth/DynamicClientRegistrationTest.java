package com.claudecode.mcp.oauth;

import org.apache.commons.lang3.Strings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.mcp.McpException;
import com.claudecode.mcp.McpServerConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DynamicClientRegistrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer fakeAs;

    @AfterEach
    void stopServer() {
        if (fakeAs != null) fakeAs.stop(0);
    }

    // ── ServerKey ────────────────────────────────────────────────────────

    @Test
    void serverKey_isStable_forSameConfig() {
        McpServerConfig a = remoteConfig("github", "https://x/mcp/",
            Map.of("Authorization", "Bearer x"));
        McpServerConfig b = remoteConfig("github", "https://x/mcp/",
            Map.of("Authorization", "Bearer x"));
        assertEquals(ServerKey.forConfig(a), ServerKey.forConfig(b));
    }

    @Test
    void serverKey_differs_whenUrlChanges() {
        McpServerConfig a = remoteConfig("github", "https://x/mcp/", Map.of());
        McpServerConfig b = remoteConfig("github", "https://y/mcp/", Map.of());
        assertNotEquals(ServerKey.forConfig(a), ServerKey.forConfig(b));
    }

    @Test
    void serverKey_differs_whenHeadersChange() {
        McpServerConfig a = remoteConfig("github", "https://x/mcp/",
            Map.of("Authorization", "Bearer OLD"));
        McpServerConfig b = remoteConfig("github", "https://x/mcp/",
            Map.of("Authorization", "Bearer NEW"));
        assertNotEquals(ServerKey.forConfig(a), ServerKey.forConfig(b));
    }

    @Test
    void serverKey_ignoresHeaderOrder() {
        // Header order shouldn't leak into the fingerprint — TreeMap in
        // ServerKey.forConfig canonicalises the order.
        McpServerConfig a = remoteConfig("s", "https://x/", Map.of(
            "A", "1", "B", "2"));
        McpServerConfig b = remoteConfig("s", "https://x/", Map.of(
            "B", "2", "A", "1"));
        assertEquals(ServerKey.forConfig(a), ServerKey.forConfig(b));
    }

    // ── DCR HTTP ─────────────────────────────────────────────────────────

    @Test
    void register_postsRfc7591CompliantBody_andParsesResponse() throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        fakeAs = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeAs.createContext("/register", exchange -> {
            callCount.incrementAndGet();
            // Body must be a valid DCR request.
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var parsed = MAPPER.readTree(body);
            assertEquals("Claude Code (github)", parsed.get("client_name").asText());
            assertEquals("http://127.0.0.1:0/callback", parsed.get("redirect_uris").get(0).asText());
            assertEquals("authorization_code", parsed.get("grant_types").get(0).asText());
            assertEquals("refresh_token",       parsed.get("grant_types").get(1).asText());
            assertEquals("code",                parsed.get("response_types").get(0).asText());
            assertEquals("none",                parsed.get("token_endpoint_auth_method").asText());

            // Respond with a DCR result.
            ObjectNode reply = MAPPER.createObjectNode();
            reply.put("client_id", "mint-abc");
            reply.put("client_secret", "sec-xyz");
            reply.putArray("redirect_uris").add("http://127.0.0.1:0/callback");
            respond(exchange, 201, MAPPER.writeValueAsString(reply));
        });
        fakeAs.start();

        String endpoint = "http://127.0.0.1:" + fakeAs.getAddress().getPort() + "/register";
        var dcr = new DynamicClientRegistration(new OkHttpClient());
        var result = dcr.register(endpoint, "github",
            "http://127.0.0.1:0/callback", List.of("mcp"));

        assertEquals("mint-abc", result.clientId());
        assertEquals("sec-xyz",  result.clientSecret());
        assertEquals(endpoint,   result.registrationEndpoint());
        assertEquals(1, callCount.get());
    }

    @Test
    void register_throwsMcpException_onNon2xx() throws Exception {
        fakeAs = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeAs.createContext("/register", exchange -> {
            respond(exchange, 401, "{\"error\":\"unauthorized\"}");
        });
        fakeAs.start();

        String endpoint = "http://127.0.0.1:" + fakeAs.getAddress().getPort() + "/register";
        var dcr = new DynamicClientRegistration(new OkHttpClient());
        McpException ex = assertThrows(McpException.class, () ->
            dcr.register(endpoint, "s", "http://x/cb", List.of()));
        assertTrue(Strings.CS.contains(ex.getMessage(), "401"));
    }

    // ── resolveClient fallback ladder ────────────────────────────────────

    @Test
    void resolveClient_returnsStoredClient_whenAvailable() {
        var stored = new SecureStorageData.McpOAuthClientConfigEntry(
            "old-cid", "old-sec", "https://old/reg", 0L);
        var data = new SecureStorageData(null,
            Map.of("srv|hash", stored), null);
        InMemoryStorage storage = new InMemoryStorage(data);

        OAuthMetadata metadata = new OAuthMetadata(
            null, "https://as/auth", "https://as/token", "https://as/register",
            null, null, null, null, null, null);
        var dcr = new DynamicClientRegistration(new OkHttpClient());
        Optional<DynamicClientRegistration.DcrResult> r = dcr.resolveClient(
            storage, "srv|hash", "srv", metadata,
            "http://127.0.0.1:1/cb", List.of(),
            null, null);
        assertTrue(r.isPresent());
        assertEquals("old-cid", r.get().clientId());
    }

    @Test
    void resolveClient_returnsConfigClient_whenNoStored() {
        InMemoryStorage storage = new InMemoryStorage(SecureStorageData.empty());
        OAuthMetadata metadata = new OAuthMetadata(
            null, "https://as/auth", "https://as/token", "https://as/register",
            null, null, null, null, null, null);
        var dcr = new DynamicClientRegistration(new OkHttpClient());
        Optional<DynamicClientRegistration.DcrResult> r = dcr.resolveClient(
            storage, "srv|hash", "srv", metadata,
            "http://127.0.0.1:1/cb", List.of(),
            "config-cid", "config-sec");
        assertTrue(r.isPresent());
        assertEquals("config-cid", r.get().clientId());
        assertEquals("config-sec", r.get().clientSecret());
        // Storage untouched — config path doesn't persist.
        assertTrue(storage.data.mcpOAuthClientConfig().isEmpty());
    }

    @Test
    void resolveClient_returnsEmpty_whenNoStoredNoConfigNoRegistrationEndpoint() {
        InMemoryStorage storage = new InMemoryStorage(SecureStorageData.empty());
        OAuthMetadata metadata = new OAuthMetadata(
            null, "https://as/auth", "https://as/token", null,     // <-- no registration
            null, null, null, null, null, null);
        var dcr = new DynamicClientRegistration(new OkHttpClient());
        assertTrue(dcr.resolveClient(
            storage, "srv|hash", "srv", metadata,
            "http://127.0.0.1:1/cb", List.of(), null, null).isEmpty());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static McpServerConfig remoteConfig(String name, String url, Map<String, String> headers) {
        return new McpServerConfig(
            name, "", List.of(), Map.of(),
            false, "http", url, headers);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    /** Bare-bones in-memory SecureStorage for the fallback ladder tests. */
    static final class InMemoryStorage implements SecureStorage {
        SecureStorageData data;
        InMemoryStorage(SecureStorageData d) { this.data = d; }
        @Override public String name() { return "memory"; }
        @Override public Optional<SecureStorageData> read() { return Optional.ofNullable(data); }
        @Override public Optional<String> update(SecureStorageData d) { this.data = d; return Optional.empty(); }
        @Override public boolean delete() { data = null; return true; }
    }
}
