package com.claudecode.mcp.oauth;

import org.apache.commons.lang3.Strings;

import com.claudecode.mcp.McpException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end coverage of {@link LoopbackCallbackServer} using an HttpClient
 * against the loopback port — no mocking of the HTTP layer.
 */
class LoopbackCallbackServerTest {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2)).build();

    @Test
    void awaitCode_returnsCode_onValidCallback() throws Exception {
        try (LoopbackCallbackServer srv = new LoopbackCallbackServer("state-xyz")) {
            var future = CompletableFuture.supplyAsync(() ->
                srv.awaitCode(Duration.ofSeconds(3)));

            HttpResponse<String> resp = get(srv.redirectUri() + "?code=abc123&state=state-xyz");
            assertEquals(200, resp.statusCode());
            assertTrue(Strings.CS.contains(resp.body(), "Authorization successful"));
            assertEquals("abc123", future.get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void awaitCode_rejects_onStateMismatch() throws Exception {
        try (LoopbackCallbackServer srv = new LoopbackCallbackServer("state-xyz")) {
            var future = CompletableFuture.supplyAsync(() -> {
                try {
                    return srv.awaitCode(Duration.ofSeconds(3));
                } catch (McpException e) {
                    return e.getMessage();
                }
            });

            HttpResponse<String> resp = get(srv.redirectUri() + "?code=abc&state=wrong-state");
            assertEquals(400, resp.statusCode());
            assertTrue(Strings.CS.contains(resp.body(), "State mismatch"));
            String result = future.get(3, TimeUnit.SECONDS);
            assertTrue(Strings.CS.contains(result, "state mismatch"), "got: " + result);
        }
    }

    @Test
    void awaitCode_propagatesProviderError() throws Exception {
        try (LoopbackCallbackServer srv = new LoopbackCallbackServer("s1")) {
            var future = CompletableFuture.supplyAsync(() -> {
                try {
                    return srv.awaitCode(Duration.ofSeconds(3));
                } catch (McpException e) {
                    return e.getMessage();
                }
            });

            HttpResponse<String> resp = get(srv.redirectUri()
                + "?error=access_denied&error_description=User+cancelled&state=s1");
            assertEquals(400, resp.statusCode());
            String result = future.get(3, TimeUnit.SECONDS);
            assertTrue(Strings.CS.contains(result, "access_denied"), "got: " + result);
        }
    }

    @Test
    void awaitCode_timesOut_ifNoCallback() {
        try (LoopbackCallbackServer srv = new LoopbackCallbackServer("s1")) {
            McpException ex = assertThrows(McpException.class, () ->
                srv.awaitCode(Duration.ofSeconds(1)));
            assertTrue(Strings.CS.contains(ex.getMessage(), "Timed out"));
        }
    }

    @Test
    void redirectUri_usesLoopbackAndDynamicPort() {
        try (LoopbackCallbackServer srv = new LoopbackCallbackServer("s1")) {
            assertTrue(Strings.CS.startsWith(srv.redirectUri(), "http://localhost:"));
            assertTrue(Strings.CS.endsWith(srv.redirectUri(), "/callback"));
            assertTrue(srv.port() > 0 && srv.port() < 65_536);
        }
    }

    @Test
    void secondCallbackAfterCompletion_returns410() throws Exception {
        try (LoopbackCallbackServer srv = new LoopbackCallbackServer("s1")) {
            get(srv.redirectUri() + "?code=first&state=s1");
            // Give the completion futures a beat to flip.
            String code = srv.awaitCode(Duration.ofSeconds(2));
            assertEquals("first", code);

            HttpResponse<String> second = get(srv.redirectUri() + "?code=second&state=s1");
            assertEquals(410, second.statusCode());
        }
    }

    private static HttpResponse<String> get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(2))
            .GET().build();
        return CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
