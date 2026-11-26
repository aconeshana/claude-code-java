package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@code DELETE /api/projects} atomically deletes every session
 * under a project (including sessions beyond the default 5-row page size) and
 * removes the project from the web registry in a single server-side call.
 */
final class GatewayProjectsDeleteInteropTest {

    private static final String TOKEN =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void deleteProjectRemovesAllPaginatedSessionsAndRegistryInOneRequest() throws Exception {
        String targetProject = "/workspace/proj-many";
        String otherProject = "/workspace/proj-keep";

        Map<String, List<String>> sessionsByProject = new LinkedHashMap<>();
        List<String> targetIds = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            targetIds.add(String.format("00000000-0000-4000-8000-%012d", i));
        }
        sessionsByProject.put(targetProject, targetIds);
        sessionsByProject.put(otherProject, List.of(
            "11111111-1111-4111-8111-000000000001",
            "11111111-1111-4111-8111-000000000002"));

        Set<String> deletedIds = ConcurrentHashMap.newKeySet();
        Map<String, GatewayProjectsPort.ProjectEntry> webProjects = new ConcurrentHashMap<>();
        webProjects.put(targetProject,
            new GatewayProjectsPort.ProjectEntry(targetProject, "Many Sessions", List.of(), 100L));
        webProjects.put(otherProject,
            new GatewayProjectsPort.ProjectEntry(otherProject, "Keep Me", List.of(), 200L));

        GatewaySessionCatalogPort catalog = pagedCatalog(sessionsByProject, deletedIds);
        GatewaySessionActionsPort actions = new GatewaySessionActionsPort() {
            @Override public boolean delete(String sessionId) {
                return deletedIds.add(sessionId);
            }
        };
        GatewayProjectsPort projectsPort = new GatewayProjectsPort() {
            @Override public List<ProjectEntry> listProjects() {
                return List.copyOf(webProjects.values());
            }

            @Override public void removeProject(String path) {
                webProjects.remove(path);
            }
        };

        try (GatewayServer server = GatewayServer.builder(
                new GatewayServer.Config("127.0.0.1", 0), TOKEN, emptyRegistry())
                .catalog(catalog)
                .actions(actions)
                .projects(projectsPort)
                .build()) {
            server.start();
            String base = "http://127.0.0.1:" + server.port();
            HttpClient client = HttpClient.newHttpClient();

            // Default GET /api/sessions returns only 5 rows for proj-many, with session_count = 12.
            HttpResponse<String> before = client.send(HttpRequest.newBuilder(
                    URI.create(base + "/api/sessions"))
                .header("Authorization", "Bearer " + TOKEN)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(before.statusCode()).isEqualTo(200);
            JsonNode beforeProjects = JsonUtils.parseTree(before.body()).path("projects");
            JsonNode manyBefore = findProject(beforeProjects, targetProject);
            assertThat(manyBefore).isNotNull();
            assertThat(manyBefore.path("session_count").asInt()).isEqualTo(12);
            assertThat(manyBefore.path("sessions").size()).isEqualTo(5);

            // Single unified DELETE /api/projects removes all 12 sessions + registry entry.
            String encoded = URLEncoder.encode(targetProject, StandardCharsets.UTF_8);
            HttpResponse<String> del = client.send(HttpRequest.newBuilder(
                    URI.create(base + "/api/projects?path=" + encoded))
                .header("Authorization", "Bearer " + TOKEN)
                .DELETE().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(del.statusCode()).isEqualTo(200);
            JsonNode delBody = JsonUtils.parseTree(del.body());
            assertThat(delBody.path("deleted_count").asInt()).isEqualTo(12);
            assertThat(delBody.path("deleted_session_ids").size()).isEqualTo(12);
            assertThat(deletedIds).containsExactlyInAnyOrderElementsOf(targetIds);
            assertThat(webProjects).doesNotContainKey(targetProject);
            assertThat(webProjects).containsKey(otherProject);

            // Subsequent GET /api/sessions no longer includes proj-many; proj-keep is intact.
            HttpResponse<String> after = client.send(HttpRequest.newBuilder(
                    URI.create(base + "/api/sessions"))
                .header("Authorization", "Bearer " + TOKEN)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(after.statusCode()).isEqualTo(200);
            JsonNode afterProjects = JsonUtils.parseTree(after.body()).path("projects");
            assertThat(findProject(afterProjects, targetProject)).isNull();
            JsonNode keepAfter = findProject(afterProjects, otherProject);
            assertThat(keepAfter).isNotNull();
            assertThat(keepAfter.path("session_count").asInt()).isEqualTo(2);
        }
    }

    @Test
    void deleteProjectReportsPartialFailureAndKeepsRegistryEntry() throws Exception {
        String targetProject = "/workspace/proj-partial";
        String okId = "22222222-2222-4222-8222-000000000001";
        String failId = "22222222-2222-4222-8222-000000000002";

        Set<String> deletedIds = ConcurrentHashMap.newKeySet();
        Map<String, GatewayProjectsPort.ProjectEntry> webProjects = new ConcurrentHashMap<>();
        webProjects.put(targetProject,
            new GatewayProjectsPort.ProjectEntry(targetProject, "Partial", List.of(), 100L));

        GatewaySessionCatalogPort catalog = pagedCatalog(
            Map.of(targetProject, List.of(okId, failId)), deletedIds);
        GatewaySessionActionsPort actions = new GatewaySessionActionsPort() {
            @Override public boolean delete(String sessionId) {
                if (failId.equals(sessionId)) {
                    throw new IllegalStateException("disk permission denied");
                }
                return deletedIds.add(sessionId);
            }
        };
        GatewayProjectsPort projectsPort = new GatewayProjectsPort() {
            @Override public List<ProjectEntry> listProjects() {
                return List.copyOf(webProjects.values());
            }

            @Override public void removeProject(String path) {
                webProjects.remove(path);
            }
        };

        try (GatewayServer server = GatewayServer.builder(
                new GatewayServer.Config("127.0.0.1", 0), TOKEN, emptyRegistry())
                .catalog(catalog)
                .actions(actions)
                .projects(projectsPort)
                .build()) {
            server.start();
            String base = "http://127.0.0.1:" + server.port();
            HttpClient client = HttpClient.newHttpClient();

            String encoded = URLEncoder.encode(targetProject, StandardCharsets.UTF_8);
            HttpResponse<String> del = client.send(HttpRequest.newBuilder(
                    URI.create(base + "/api/projects?path=" + encoded))
                .header("Authorization", "Bearer " + TOKEN)
                .DELETE().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(del.statusCode()).isEqualTo(500);
            JsonNode body = JsonUtils.parseTree(del.body());
            assertThat(body.path("deleted_session_ids").size()).isEqualTo(1);
            assertThat(body.path("failed_session_ids").size()).isEqualTo(1);
            assertThat(body.path("failed_session_ids").get(0).asText()).isEqualTo(failId);
            assertThat(webProjects).containsKey(targetProject);
        }
    }

    private static GatewaySessionCatalogPort pagedCatalog(
            Map<String, List<String>> sessionsByProject, Set<String> deletedIds) {
        return new GatewaySessionCatalogPort() {
            @Override public List<ProjectEntry> listProjects() {
                return listProjects(Integer.MAX_VALUE);
            }

            @Override public List<ProjectEntry> listProjects(int perProjectLimit) {
                int limit = perProjectLimit <= 0 ? Integer.MAX_VALUE : perProjectLimit;
                List<ProjectEntry> result = new ArrayList<>();
                for (Map.Entry<String, List<String>> entry : sessionsByProject.entrySet()) {
                    List<SessionEntry> remaining = entry.getValue().stream()
                        .filter(id -> !deletedIds.contains(id))
                        .map(id -> new SessionEntry(id, "summary-" + id, 2, 1_000L, "main",
                            entry.getKey(), null, "hello"))
                        .toList();
                    if (remaining.isEmpty()) continue;
                    List<SessionEntry> page = remaining.size() > limit
                        ? remaining.subList(0, limit) : remaining;
                    result.add(new ProjectEntry(entry.getKey(), entry.getKey(),
                        remaining.size(), 1_000L, page));
                }
                return result;
            }
        };
    }

    private static JsonNode findProject(JsonNode projects, String path) {
        for (JsonNode project : projects) {
            if (path.equals(project.path("project_path").asText())) return project;
        }
        return null;
    }

    private static SessionHostRegistry emptyRegistry() {
        return new SessionHostRegistry(new SessionHostRegistry.Activator() {
            @Override public CompletableFuture<SessionHostSession> activate(SessionOpenRequest request) {
                throw new UnsupportedOperationException("not used");
            }

            @Override public List<SessionHostInfo> list() {
                return List.of();
            }
        });
    }
}
