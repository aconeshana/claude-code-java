package com.claudecode.gateway;

import static com.claudecode.gateway.GatewayRequests.queryParam;
import static com.claudecode.gateway.GatewayRequests.readRequestJson;
import static com.claudecode.gateway.GatewayRequests.text;
import static com.claudecode.gateway.GatewayResponses.errorBody;
import static com.claudecode.gateway.GatewayResponses.respondJson;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * {@code /api/projects} and {@code /api/projects/customizations}: manages
 * multi-folder project records, unified atomic project deletion (sessions +
 * headless handles + project registry), and project-scoped Rules / Skills / MCP
 * overviews for the Web UI.
 */
@Explanation("HTTP handler for multi-folder project CRUD, unified project deletion, and per-project customizations overview")
final class GatewayProjectsHandler {

    private final GatewayProjectsPort projects;
    private final GatewaySessionCatalogPort catalog;
    private final GatewaySessionActionsPort sessionActions;
    private final GatewayHeadlessSessions headless;
    private final MirrorHub mirror;
    private final ContextTimelineLedger contextTimeline;

    GatewayProjectsHandler(GatewayProjectsPort projects) {
        this(projects, null, null, null, null, null);
    }

    GatewayProjectsHandler(
            GatewayProjectsPort projects,
            GatewaySessionCatalogPort catalog,
            GatewaySessionActionsPort sessionActions,
            GatewayHeadlessSessions headless,
            MirrorHub mirror,
            ContextTimelineLedger contextTimeline) {
        this.projects = projects;
        this.catalog = catalog;
        this.sessionActions = sessionActions;
        this.headless = headless;
        this.mirror = mirror;
        this.contextTimeline = contextTimeline;
    }

    void handleProjects(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        switch (method) {
            case "GET" -> handleList(exchange);
            case "POST" -> handleCreate(exchange);
            case "PATCH" -> handleRename(exchange);
            case "DELETE" -> handleDelete(exchange);
            default -> respondJson(exchange, 405,
                errorBody("method_not_allowed", "only GET, POST, PATCH, DELETE are supported"));
        }
    }

    void handleCustomizations(HttpExchange exchange) throws IOException {
        if (!Strings.CS.equals("GET", exchange.getRequestMethod())) {
            respondJson(exchange, 405, errorBody("method_not_allowed", "only GET is supported"));
            return;
        }
        String path = StringUtils.trimToNull(queryParam(exchange, "path"));
        if (path == null) {
            respondJson(exchange, 400, errorBody("invalid_request", "path query parameter is required"));
            return;
        }
        try {
            respondJson(exchange, 200, projects.projectCustomizations(path));
        } catch (IllegalArgumentException bad) {
            respondJson(exchange, 400, errorBody("invalid_request",
                StringUtils.defaultIfBlank(bad.getMessage(), "invalid project path")));
        } catch (RuntimeException failure) {
            respondJson(exchange, 500, errorBody("api_error",
                "failed to load project customizations: " + failure.getMessage()));
        }
    }

    private void handleList(HttpExchange exchange) throws IOException {
        ObjectNode out = JsonUtils.getMapper().createObjectNode();
        out.set("projects", toArray(projects.listProjects()));
        respondJson(exchange, 200, out);
    }

    private void handleCreate(HttpExchange exchange) throws IOException {
        JsonNode body = readRequestJson(exchange);
        if (body == null) return;
        String path = StringUtils.trimToNull(text(body, "path"));
        if (path == null) {
            respondJson(exchange, 400, errorBody("invalid_request", "path is required"));
            return;
        }
        String name = text(body, "name");
        String tier = StringUtils.defaultIfBlank(text(body, "tier"), "local");
        if (!Strings.CS.equals("local", tier) && !Strings.CS.equals("project", tier)) {
            respondJson(exchange, 400, errorBody("invalid_request", "tier must be 'local' or 'project'"));
            return;
        }
        List<String> additional = stringList(body, "additional_directories");
        try {
            GatewayProjectsPort.ProjectEntry entry =
                projects.createOrUpdateProject(path, name, additional, tier);
            ObjectNode out = JsonUtils.getMapper().createObjectNode();
            out.set("project", toJson(entry));
            out.set("projects", toArray(projects.listProjects()));
            respondJson(exchange, 200, out);
        } catch (IllegalArgumentException bad) {
            respondJson(exchange, 400, errorBody("invalid_request",
                StringUtils.defaultIfBlank(bad.getMessage(), "invalid project request")));
        } catch (RuntimeException failure) {
            respondJson(exchange, 500, errorBody("api_error",
                "failed to save project: " + failure.getMessage()));
        }
    }

    private void handleRename(HttpExchange exchange) throws IOException {
        JsonNode body = readRequestJson(exchange);
        if (body == null) return;
        String path = StringUtils.trimToNull(text(body, "path"));
        if (path == null) {
            respondJson(exchange, 400, errorBody("invalid_request", "path is required"));
            return;
        }
        String name = text(body, "name");
        try {
            GatewayProjectsPort.ProjectEntry entry = projects.renameProject(path, name);
            ObjectNode out = JsonUtils.getMapper().createObjectNode();
            out.set("project", toJson(entry));
            out.set("projects", toArray(projects.listProjects()));
            respondJson(exchange, 200, out);
        } catch (IllegalArgumentException bad) {
            respondJson(exchange, 400, errorBody("invalid_request",
                StringUtils.defaultIfBlank(bad.getMessage(), "invalid rename request")));
        } catch (RuntimeException failure) {
            respondJson(exchange, 500, errorBody("api_error",
                "failed to rename project: " + failure.getMessage()));
        }
    }

    private synchronized void handleDelete(HttpExchange exchange) throws IOException {
        String path = StringUtils.trimToNull(queryParam(exchange, "path"));
        if (path == null) {
            JsonNode body = readRequestJson(exchange);
            if (body == null) return;
            path = StringUtils.trimToNull(text(body, "path"));
        }
        if (path == null) {
            respondJson(exchange, 400, errorBody("invalid_request", "path is required"));
            return;
        }
        try {
            DeleteOutcome outcome = deleteProjectUnified(path);
            if (!outcome.failedSessionIds().isEmpty()) {
                ObjectNode err = errorBody("partial_delete_failed",
                    "failed to delete " + outcome.failedSessionIds().size()
                        + " session(s) under project " + path);
                err.set("deleted_session_ids", toStringArray(outcome.deletedSessionIds()));
                err.set("failed_session_ids", toStringArray(outcome.failedSessionIds()));
                respondJson(exchange, 500, err);
                return;
            }
            ObjectNode out = JsonUtils.getMapper().createObjectNode();
            out.put("path", outcome.canonicalPath());
            out.set("deleted_session_ids", toStringArray(outcome.deletedSessionIds()));
            out.put("deleted_count", outcome.deletedSessionIds().size());
            out.set("projects", toArray(projects.listProjects()));
            respondJson(exchange, 200, out);
        } catch (RuntimeException failure) {
            respondJson(exchange, 500, errorBody("api_error",
                "failed to remove project: " + failure.getMessage()));
        }
    }

    private record DeleteOutcome(
            String canonicalPath,
            List<String> deletedSessionIds,
            List<String> failedSessionIds) {}

    /**
     * Deletes every session belonging to {@code path} (across all pages, not just
     * the sidebar's visible page), closes any open headless handles for those
     * sessions, and removes the project from the web registry in one consistent
     * server-side operation.
     */
    private DeleteOutcome deleteProjectUnified(String path) {
        String canonicalTarget = canonicalize(path);
        Set<String> targetSessionIds = new LinkedHashSet<>();

        if (catalog != null) {
            // perProjectLimit = 0 fetches every session under every project (no pagination).
            for (GatewaySessionCatalogPort.ProjectEntry entry : catalog.listProjects(0)) {
                if (matchesProject(entry.projectPath(), path, canonicalTarget)) {
                    for (GatewaySessionCatalogPort.SessionEntry session : entry.sessions()) {
                        if (StringUtils.isNotBlank(session.id())) {
                            targetSessionIds.add(session.id());
                        }
                    }
                }
            }
        }
        if (headless != null) {
            for (GatewayHeadlessSessions.SessionListing listing : headless.list()) {
                String workDir = headless.find(listing.sessionId())
                    .map(SessionHostSession::info)
                    .map(SessionHostInfo::workDir)
                    .orElse(null);
                if (StringUtils.isNotBlank(workDir)
                        && matchesProject(workDir, path, canonicalTarget)) {
                    targetSessionIds.add(listing.sessionId());
                }
            }
        }

        List<String> deletedIds = new ArrayList<>();
        List<String> failedIds = new ArrayList<>();
        for (String sessionId : targetSessionIds) {
            boolean closedHeadless = false;
            if (headless != null && headless.isOpen(sessionId)) {
                closedHeadless = headless.close(sessionId);
                if (mirror != null) mirror.detachSession(sessionId);
                if (contextTimeline != null) contextTimeline.forget(sessionId);
            }
            if (sessionActions != null) {
                try {
                    boolean removed = sessionActions.delete(sessionId);
                    if (removed || closedHeadless) {
                        deletedIds.add(sessionId);
                    }
                } catch (GatewaySessionActionsPort.UnknownSessionException _) {
                    if (closedHeadless) {
                        deletedIds.add(sessionId);
                    }
                } catch (RuntimeException _) {
                    failedIds.add(sessionId);
                }
            } else if (closedHeadless) {
                deletedIds.add(sessionId);
            }
        }

        if (failedIds.isEmpty()) {
            projects.removeProject(path);
            if (!Strings.CS.equals(canonicalTarget, path)) {
                projects.removeProject(canonicalTarget);
            }
        }
        if (!deletedIds.isEmpty() && mirror != null) {
            mirror.publishSessionsChanged();
        }
        return new DeleteOutcome(canonicalTarget, List.copyOf(deletedIds), List.copyOf(failedIds));
    }

    private String canonicalize(String rawPath) {
        if (catalog == null) return rawPath;
        String canonical = StringUtils.trimToNull(catalog.canonicalizeProjectPath(rawPath));
        return canonical != null ? canonical : rawPath;
    }

    private boolean matchesProject(String candidatePath, String rawTarget, String canonicalTarget) {
        if (StringUtils.isBlank(candidatePath)) return false;
        if (Strings.CS.equals(candidatePath, rawTarget)
                || Strings.CS.equals(candidatePath, canonicalTarget)) {
            return true;
        }
        return Strings.CS.equals(canonicalize(candidatePath), canonicalTarget);
    }

    private static ArrayNode toStringArray(List<String> values) {
        ArrayNode array = JsonUtils.getMapper().createArrayNode();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static ArrayNode toArray(List<GatewayProjectsPort.ProjectEntry> entries) {
        ArrayNode array = JsonUtils.getMapper().createArrayNode();
        for (GatewayProjectsPort.ProjectEntry entry : entries) {
            array.add(toJson(entry));
        }
        return array;
    }

    private static ObjectNode toJson(GatewayProjectsPort.ProjectEntry entry) {
        ObjectNode node = JsonUtils.getMapper().createObjectNode();
        node.put("path", entry.path());
        node.put("name", StringUtils.defaultString(entry.name()));
        ArrayNode dirs = node.putArray("additional_directories");
        for (String dir : entry.additionalDirectories()) {
            dirs.add(dir);
        }
        node.put("updated_at_ms", entry.updatedAtMs());
        return node;
    }

    private static List<String> stringList(JsonNode request, String field) {
        JsonNode value = request.path(field);
        if (!value.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode entry : (ArrayNode) value) {
            if (entry.isTextual() && StringUtils.isNotBlank(entry.asText())) {
                result.add(entry.asText().trim());
            }
        }
        return result;
    }
}
