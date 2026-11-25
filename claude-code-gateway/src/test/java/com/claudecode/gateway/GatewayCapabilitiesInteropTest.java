package com.claudecode.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.capabilities.SessionCapabilityPort;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

class GatewayCapabilitiesInteropTest {

    private static final String TOKEN = "c".repeat(48);

    @Test
    void servesSnapshotAndAppliesSessionOrDefaultToggle() throws Exception {
        AtomicBoolean skillEnabled = new AtomicBoolean(true);
        AtomicBoolean defaultSkillEnabled = new AtomicBoolean(true);

        SessionCapabilityPort port = new SessionCapabilityPort() {
            @Override
            public boolean isBuiltInToolEnabled(String sessionId, String toolName) {
                return true;
            }

            @Override
            public boolean isMcpServerEnabled(String sessionId, String serverName) {
                return true;
            }

            @Override
            public boolean isMcpToolEnabled(String sessionId, String fullToolName) {
                return true;
            }

            @Override
            public boolean isSkillEnabled(String sessionId, String skillName) {
                return skillEnabled.get();
            }

            @Override
            public Set<String> frozenInitialEagerTools(
                    String sessionId, Supplier<Set<String>> computer) {
                return computer.get();
            }

            @Override
            public CapabilitiesSnapshot snapshot(String sessionId, String modelOverride) {
                return new CapabilitiesSnapshot(
                    sessionId == null ? "s1" : sessionId,
                    modelOverride == null ? "glm-5.3" : modelOverride,
                    "chat",
                    480,
                    List.of(new BuiltInToolItem(
                        "ToolInvoke", "Invoke deferred tool",
                        JsonUtils.getMapper().createObjectNode(),
                        80, true, true, true, "prefix_eager")),
                    List.of(new SkillItem(
                        "deploy", "project", "Deploy service", 25,
                        skillEnabled.get(), defaultSkillEnabled.get(), true)),
                    List.of(new McpServerItem(
                        "github", "project", "stdio", "connected", "Use GitHub tools", 375,
                        true, true, true,
                        List.of(new McpToolItem(
                            "github", "create_issue", "mcp__github__create_issue",
                            "Create an issue", JsonUtils.getMapper().createObjectNode(),
                            120, true, true)))));
            }

            @Override
            public ToggleOutcome toggle(ToggleRequest request) {
                if (request.kind() == Kind.TOOL && Strings.CS.equals("ToolInvoke", request.name())) {
                    throw new IllegalArgumentException("Kernel tool cannot be disabled");
                }
                skillEnabled.set(request.enabled());
                if (request.scope() == Scope.DEFAULT) {
                    defaultSkillEnabled.set(request.enabled());
                }
                return new ToggleOutcome(true, "Updated deploy", snapshot(request.sessionId(), null));
            }
        };

        SessionHostRegistry registry = new SessionHostRegistry(
            new SessionHostRegistry.Activator() {
                @Override
                public CompletionStage<SessionHostSession> activate(SessionOpenRequest request) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public List<SessionHostInfo> list() {
                    return List.of();
                }
            });
        try (GatewayServer server = GatewayServer.builder(
                new GatewayServer.Config("127.0.0.1", 0), TOKEN, registry)
                .capabilities(port)
                .build()) {
            server.start();
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();

            HttpResponse<String> getRes = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/session/capabilities?session_id=s1"))
                    .header("Authorization", "Bearer " + TOKEN)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, getRes.statusCode());
            JsonNode snap = JsonUtils.getMapper().readTree(getRes.body()).path("capabilities");
            assertEquals("chat", snap.path("protocol").asText());
            assertEquals(480, snap.path("saved_tokens").asInt());
            assertTrue(snap.path("skills").get(0).path("session_enabled").asBoolean());

            // Session-scoped disable leaves default_enabled=true
            HttpResponse<String> postRes = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/session/capabilities"))
                    .header("Authorization", "Bearer " + TOKEN)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                        {"session_id":"s1","kind":"skill","name":"deploy","enabled":false,"scope":"session"}
                        """))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, postRes.statusCode());
            JsonNode updated = JsonUtils.getMapper().readTree(postRes.body()).path("capabilities");
            assertFalse(updated.path("skills").get(0).path("session_enabled").asBoolean());
            assertTrue(updated.path("skills").get(0).path("default_enabled").asBoolean());

            // Rejects disabling kernel tool with 400
            HttpResponse<String> badRes = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/session/capabilities"))
                    .header("Authorization", "Bearer " + TOKEN)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                        {"session_id":"s1","kind":"tool","name":"ToolInvoke","enabled":false,"scope":"session"}
                        """))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(400, badRes.statusCode());
        }
    }
}
