package com.claudecode.tools.mcp;

import org.apache.commons.lang3.Strings;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpAuthToolHttpTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void tokenExchangeUsesInjectedOkHttpClientAndPreservesResponse() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            respond(exchange, 200, "{\"access_token\":\"token-1\"}");
        });
        server.start();

        McpAuthTool tool = new McpAuthTool(new OkHttpClient());
        String result = tool.exchangeCodeForToken(
            endpoint(), "code-1", "client-1", "http://localhost/callback", "server-1");

        assertTrue(Strings.CS.contains(result, "OAuth token obtained for server 'server-1'"));
        assertTrue(Strings.CS.contains(result, "\"access_token\":\"token-1\""));
        assertEquals("application/x-www-form-urlencoded", contentType.get());
        assertEquals("grant_type=authorization_code&code=code-1&client_id=client-1"
            + "&redirect_uri=http://localhost/callback", requestBody.get());
    }

    private String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
