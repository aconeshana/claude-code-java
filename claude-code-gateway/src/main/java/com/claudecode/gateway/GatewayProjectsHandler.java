package com.claudecode.gateway;

import static com.claudecode.gateway.GatewayRequests.queryParam;
import static com.claudecode.gateway.GatewayRequests.readJson;
import static com.claudecode.gateway.GatewayRequests.text;
import static com.claudecode.gateway.GatewayResponses.errorBody;
import static com.claudecode.gateway.GatewayResponses.respondJson;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * {@code /api/projects} and {@code /api/projects/customizations}: manages
 * multi-folder project records and returns project-scoped Rules / Skills / MCP
 * overviews for the Web UI.
 */
@Explanation("HTTP handler for multi-folder project CRUD and per-project customizations overview")
final class GatewayProjectsHandler {

    private final GatewayProjectsPort projects;

    GatewayProjectsHandler(GatewayProjectsPort projects) {
        this.projects = projects;
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
        JsonNode body = readJson(exchange);
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
        JsonNode body = readJson(exchange);
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

    private void handleDelete(HttpExchange exchange) throws IOException {
        String path = StringUtils.trimToNull(queryParam(exchange, "path"));
        if (path == null) {
            JsonNode body = readJson(exchange);
            if (body == null) return;
            path = StringUtils.trimToNull(text(body, "path"));
        }
        if (path == null) {
            respondJson(exchange, 400, errorBody("invalid_request", "path is required"));
            return;
        }
        try {
            projects.removeProject(path);
            ObjectNode out = JsonUtils.getMapper().createObjectNode();
            out.set("projects", toArray(projects.listProjects()));
            respondJson(exchange, 200, out);
        } catch (RuntimeException failure) {
            respondJson(exchange, 500, errorBody("api_error",
                "failed to remove project: " + failure.getMessage()));
        }
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
