package com.claudecode.mcp.oauth;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.mcp.McpException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;

import static org.junit.jupiter.api.Assertions.*;

class OAuthTokenClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private HttpServer as;

    @AfterEach
    void stop() { if (as != null) as.stop(0); }

    @Test
    void exchangeCode_publicClient_sendsPkceAndClientId_inBody() throws Exception {
        AtomicReference<Map<String, String>> received = new AtomicReference<>();
        AtomicReference<String> authHeader = new AtomicReference<>();
        as = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        as.createContext("/token", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            received.set(parseFormBody(exchange));
            ObjectNode reply = MAPPER.createObjectNode();
            reply.put("access_token", "at-fresh");
            reply.put("token_type", "Bearer");
            reply.put("expires_in", 3600);
            reply.put("refresh_token", "rt-fresh");
            reply.put("scope", "read write");
            respond(exchange, 200, MAPPER.writeValueAsString(reply));
        });
        as.start();

        var client = new OAuthTokenClient(new OkHttpClient());
        var resp = client.exchangeCode(
            "http://127.0.0.1:" + as.getAddress().getPort() + "/token",
            "public-client-id", null, /* code */ "abc",
            /* verifier */ "verifier-123", "http://127.0.0.1:1/cb");

        Map<String, String> body = received.get();
        assertNull(authHeader.get(), "public client must NOT send Basic auth");
        assertEquals("authorization_code", body.get("grant_type"));
        assertEquals("abc",                body.get("code"));
        assertEquals("verifier-123",       body.get("code_verifier"));
        assertEquals("http://127.0.0.1:1/cb", body.get("redirect_uri"));
        assertEquals("public-client-id",   body.get("client_id"));
        assertFalse(body.containsKey("client_secret"));

        assertEquals("at-fresh", resp.accessToken());
        assertEquals("rt-fresh", resp.refreshToken());
        assertEquals(3600,       resp.expiresIn());
        assertTrue(resp.expiresAt() > System.currentTimeMillis());
    }

    @Test
    void exchangeCode_confidentialClient_usesBasicAuth() throws Exception {
        AtomicReference<String> authHeader = new AtomicReference<>();
        AtomicReference<Map<String, String>> received = new AtomicReference<>();
        as = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        as.createContext("/token", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            received.set(parseFormBody(exchange));
            ObjectNode reply = MAPPER.createObjectNode();
            reply.put("access_token", "at-basic");
            reply.put("expires_in", 3600);
            respond(exchange, 200, MAPPER.writeValueAsString(reply));
        });
        as.start();

        var client = new OAuthTokenClient(new OkHttpClient());
        client.exchangeCode(
            "http://127.0.0.1:" + as.getAddress().getPort() + "/token",
            "cid-x", "sec-y", "code123", "verifier", "http://127.0.0.1:1/cb");

        assertNotNull(authHeader.get());
        assertTrue(Strings.CS.startsWith(authHeader.get(), "Basic "),
            "with client_secret we must send Basic auth, got: " + authHeader.get());
        String decoded = new String(Base64.getDecoder().decode(
            authHeader.get().substring("Basic ".length())), StandardCharsets.UTF_8);
        assertTrue(Strings.CS.startsWith(decoded, "cid-x:"),
            "Basic payload must start with cid-x:, got: " + decoded);
        assertFalse(received.get().containsKey("client_id"),
            "when using Basic, don't repeat client_id in body");
    }

    @Test
    void refresh_sendsRefreshTokenGrant() throws Exception {
        AtomicReference<Map<String, String>> received = new AtomicReference<>();
        as = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        as.createContext("/token", exchange -> {
            received.set(parseFormBody(exchange));
            ObjectNode reply = MAPPER.createObjectNode();
            reply.put("access_token", "at-refreshed");
            reply.put("expires_in", 900);
            respond(exchange, 200, MAPPER.writeValueAsString(reply));
        });
        as.start();

        var client = new OAuthTokenClient(new OkHttpClient());
        var resp = client.refresh(
            "http://127.0.0.1:" + as.getAddress().getPort() + "/token",
            "cid", null, "rt-old");

        assertEquals("refresh_token", received.get().get("grant_type"));
        assertEquals("rt-old",        received.get().get("refresh_token"));
        assertEquals("at-refreshed",  resp.accessToken());
        assertEquals(900,             resp.expiresIn());
    }

    @Test
    void error_response_throwsMcpException() throws Exception {
        as = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        as.createContext("/token", exchange -> {
            respond(exchange, 400, "{\"error\":\"invalid_grant\"}");
        });
        as.start();

        var client = new OAuthTokenClient(new OkHttpClient());
        McpException ex = assertThrows(McpException.class, () ->
            client.exchangeCode(
                "http://127.0.0.1:" + as.getAddress().getPort() + "/token",
                "cid", null, "code", "verifier", "http://x/cb"));
        assertTrue(Strings.CS.contains(ex.getMessage(), "400"));
        assertTrue(Strings.CS.contains(ex.getMessage(), "invalid_grant"));
    }

    @Test
    void errorDescriptionIsSurfacedAsTheControlError() throws Exception {
        as = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        as.createContext("/token", exchange -> {
            respond(exchange, 400,
                "{\"error\":\"invalid_grant\",\"error_description\":\"WIRE_OAUTH_TOKEN_EXCHANGE_FAILED\"}");
        });
        as.start();

        var client = new OAuthTokenClient(new OkHttpClient());
        McpException ex = assertThrows(McpException.class, () ->
            client.exchangeCode(
                "http://127.0.0.1:" + as.getAddress().getPort() + "/token",
                "cid", null, "code", "verifier", "http://x/cb"));
        assertEquals("WIRE_OAUTH_TOKEN_EXCHANGE_FAILED", ex.getMessage());
    }

    @Test
    void emptyErrorResponseStillReportsHttpStatus() throws Exception {
        as = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        as.createContext("/token", exchange -> {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
        });
        as.start();

        var client = new OAuthTokenClient(new OkHttpClient());
        McpException ex = assertThrows(McpException.class, () ->
            client.refresh(
                "http://127.0.0.1:" + as.getAddress().getPort() + "/token",
                "cid", null, "rt"));

        assertTrue(Strings.CS.contains(ex.getMessage(), "HTTP 400"));
    }

    @Test
    void missingAccessToken_throwsMcpException() throws Exception {
        as = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        as.createContext("/token", exchange -> {
            respond(exchange, 200, "{\"expires_in\":3600}");
        });
        as.start();

        var client = new OAuthTokenClient(new OkHttpClient());
        McpException ex = assertThrows(McpException.class, () ->
            client.refresh(
                "http://127.0.0.1:" + as.getAddress().getPort() + "/token",
                "cid", null, "rt"));
        assertTrue(Strings.CS.contains(ex.getMessage(), "access_token"));
    }

    @Test
    void expiresAt_isZero_whenExpiresInMissing() throws Exception {
        as = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        as.createContext("/token", exchange ->
            respond(exchange, 200, "{\"access_token\":\"at\"}"));
        as.start();

        var client = new OAuthTokenClient(new OkHttpClient());
        var resp = client.refresh(
            "http://127.0.0.1:" + as.getAddress().getPort() + "/token",
            "cid", null, "rt");
        assertEquals(0, resp.expiresAt(), "no expires_in → expiresAt=0 (unknown, don't refresh preemptively)");
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static Map<String, String> parseFormBody(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> out = new HashMap<>();
        if (StringUtils.isBlank(body)) return out;
        for (String part : body.split("&")) {
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(k, v);
        }
        return out;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }
}
