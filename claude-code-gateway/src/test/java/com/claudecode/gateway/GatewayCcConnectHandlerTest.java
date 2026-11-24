package com.claudecode.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;

class GatewayCcConnectHandlerTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @Test
    void endpointsServeStatusAttachDetachLogsAndStreamingSetup() throws Exception {
        AtomicBoolean attached = new AtomicBoolean(false);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        GatewayCcConnectPort port = new GatewayCcConnectPort() {
            @Override
            public Snapshot snapshot() {
                return new Snapshot(
                    true, true, false, "/tmp/cc-connect.toml", List.of("feishu"), false,
                    List.of(new TargetStatus(
                        4242L, "/tmp/proj", "sess-1", "Demo Session",
                        attached.get(), attached.get() ? "feishu" : "",
                        attached.get() ? "connected" : "stopped",
                        attached.get() ? "2026-11-23T00:00:00Z" : "", "")));
            }

            @Override
            public Snapshot attach(AttachRequest request) {
                assertEquals(4242L, request.pid());
                assertEquals("feishu", request.channel());
                attached.set(true);
                return snapshot();
            }

            @Override
            public Snapshot detach(DetachRequest request) {
                assertEquals(4242L, request.pid());
                attached.set(false);
                return snapshot();
            }

            @Override
            public LogsResponse logs(long pid, int tail) {
                return new LogsResponse(pid, List.of("connected to feishu", "ready"));
            }

            @Override
            public SetupResult setup(SetupRequest request, Consumer<String> progress) {
                assertEquals("bind", request.mode());
                assertEquals("cli_test", request.appId());
                progress.accept("authenticating with feishu");
                progress.accept("discovering target chat");
                return new SetupResult(true, "feishu", "Feishu collaboration is ready");
            }

            @Override
            public boolean cancelSetup() {
                cancelled.set(true);
                return true;
            }
        };

        SessionHostRegistry registry = new SessionHostRegistry(new SessionHostRegistry.Activator() {
            @Override public CompletableFuture<SessionHostSession> activate(SessionOpenRequest req) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException());
            }
            @Override public List<SessionHostInfo> list() { return List.of(); }
        });

        try (GatewayServer server = GatewayServer.builder(
                new GatewayServer.Config("127.0.0.1", 0), TOKEN, registry)
                .ccConnect(port)
                .build()) {
            server.start();
            String base = "http://127.0.0.1:" + server.port();
            HttpClient http = HttpClient.newHttpClient();

            // Unauthenticated request must be rejected.
            HttpResponse<String> unauth = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/cc-connect")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauth.statusCode());

            // GET /api/cc-connect
            HttpResponse<String> statusResp = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/cc-connect"))
                    .header("Authorization", "Bearer " + TOKEN).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, statusResp.statusCode());
            JsonNode status = JsonUtils.getMapper().readTree(statusResp.body());
            assertTrue(status.path("configured").asBoolean());
            assertEquals(1, status.path("targets").size());
            assertFalse(status.path("targets").get(0).path("attached").asBoolean());

            // POST /api/cc-connect/attach
            HttpResponse<String> attachResp = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/cc-connect/attach"))
                    .header("Authorization", "Bearer " + TOKEN)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"pid\":4242,\"session_id\":\"sess-1\",\"channel\":\"feishu\",\"origin\":\"web\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, attachResp.statusCode());
            JsonNode attachedBody = JsonUtils.getMapper().readTree(attachResp.body());
            assertTrue(attachedBody.path("targets").get(0).path("attached").asBoolean());
            assertEquals("connected", attachedBody.path("targets").get(0).path("status").asText());

            // GET /api/cc-connect/logs
            HttpResponse<String> logsResp = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/cc-connect/logs?pid=4242&tail=50"))
                    .header("Authorization", "Bearer " + TOKEN).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, logsResp.statusCode());
            JsonNode logsBody = JsonUtils.getMapper().readTree(logsResp.body());
            assertEquals(2, logsBody.path("lines").size());

            // POST /api/cc-connect/detach
            HttpResponse<String> detachResp = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/cc-connect/detach"))
                    .header("Authorization", "Bearer " + TOKEN)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"pid\":4242,\"session_id\":\"sess-1\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, detachResp.statusCode());
            assertFalse(JsonUtils.getMapper().readTree(detachResp.body())
                .path("targets").get(0).path("attached").asBoolean());

            // POST /api/cc-connect/setup (NDJSON streaming)
            HttpResponse<String> setupResp = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/cc-connect/setup"))
                    .header("Authorization", "Bearer " + TOKEN)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"mode\":\"bind\",\"app_id\":\"cli_test\",\"app_secret\":\"sec\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, setupResp.statusCode());
            List<JsonNode> frames = new ArrayList<>();
            for (String line : setupResp.body().split("\n")) {
                if (StringUtils.isNotBlank(line)) frames.add(JsonUtils.getMapper().readTree(line));
            }
            assertEquals(3, frames.size());
            assertEquals("progress", frames.getFirst().path("type").asText());
            assertEquals("result", frames.get(2).path("type").asText());
            assertTrue(frames.get(2).path("ok").asBoolean());

            // POST /api/cc-connect/setup/cancel
            HttpResponse<String> cancelResp = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/cc-connect/setup/cancel"))
                    .header("Authorization", "Bearer " + TOKEN)
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, cancelResp.statusCode());
            assertTrue(cancelled.get());
        }
    }
}
