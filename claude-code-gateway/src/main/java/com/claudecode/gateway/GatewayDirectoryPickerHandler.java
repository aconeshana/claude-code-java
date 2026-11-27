package com.claudecode.gateway;

import static com.claudecode.gateway.GatewayResponses.errorBody;
import static com.claudecode.gateway.GatewayResponses.respondJson;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Host filesystem directory browser endpoints backing the vendored upstream
 * {@code DirectoryBrowser} (Miller-column workspace directory picker &amp; new
 * folder creation):
 * <ul>
 *   <li>{@code GET /api/directories?path=<optional>} — lists subdirectories,
 *       breadcrumbs, and home root;</li>
 *   <li>{@code POST /api/directories} with {@code {"path","name"}} — creates a
 *       new child directory on disk and returns {@code {"path"}}.</li>
 * </ul>
 */
final class GatewayDirectoryPickerHandler {

    private static final int MAX_ENTRIES = 500;
    private static final int MAX_REQUEST_BYTES = 16_384;

    void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (Strings.CS.equals("GET", method)) {
            handleList(exchange);
            return;
        }
        if (Strings.CS.equals("POST", method)) {
            handleCreate(exchange);
            return;
        }
        respondJson(exchange, 405, errorBody("method_not_allowed",
            "only GET and POST are supported on /api/directories"));
    }

    private void handleList(HttpExchange exchange) throws IOException {
        String rawPath = queryParam(exchange, "path");
        Path home = resolveHome();
        Path target;
        if (StringUtils.isBlank(rawPath)) {
            target = home;
        } else {
            try {
                target = resolveInputPath(rawPath.trim(), home).toAbsolutePath().normalize();
            } catch (InvalidPathException _) {
                respondJson(exchange, 400, errorBody("invalid_path",
                    "Invalid directory path: " + rawPath));
                return;
            }
        }
        if (!Files.exists(target)) {
            respondJson(exchange, 404, errorBody("not_found",
                "Directory does not exist: " + target));
            return;
        }
        if (!Files.isDirectory(target)) {
            respondJson(exchange, 400, errorBody("not_a_directory",
                "Path is not a directory: " + target));
            return;
        }

        List<Path> children = new ArrayList<>();
        boolean truncated = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(target)) {
            for (Path entry : stream) {
                if (!Files.isDirectory(entry)) continue;
                children.add(entry);
            }
        } catch (IOException _) {
            respondJson(exchange, 403, errorBody("unreadable_directory",
                "Cannot read directory: " + target));
            return;
        }

        children.sort(Comparator.comparing(
            p -> p.getFileName() == null ? "" : p.getFileName().toString(),
            String.CASE_INSENSITIVE_ORDER));
        if (children.size() > MAX_ENTRIES) {
            children = children.subList(0, MAX_ENTRIES);
            truncated = true;
        }

        ObjectNode body = JsonUtils.getMapper().createObjectNode();
        body.put("path", target.toString());
        body.put("home", home.toString());
        body.put("truncated", truncated);

        ArrayNode crumbs = body.putArray("crumbs");
        for (Path crumb : buildCrumbs(target)) {
            ObjectNode c = crumbs.addObject();
            Path fileName = crumb.getFileName();
            c.put("name", fileName == null ? crumb.toString() : fileName.toString());
            c.put("path", crumb.toString());
            c.put("hidden", isHiddenSafe(crumb));
        }

        ArrayNode entries = body.putArray("entries");
        for (Path child : children) {
            ObjectNode e = entries.addObject();
            Path fileName = child.getFileName();
            String name = fileName == null ? child.toString() : fileName.toString();
            e.put("name", name);
            e.put("path", child.toString());
            e.put("hidden", Strings.CS.startsWith(name, ".") || isHiddenSafe(child));
        }

        respondJson(exchange, 200, body);
    }

    private void handleCreate(HttpExchange exchange) throws IOException {
        JsonNode req;
        try (InputStream body = exchange.getRequestBody()) {
            byte[] bytes = body.readNBytes(MAX_REQUEST_BYTES + 1);
            if (bytes.length > MAX_REQUEST_BYTES) {
                respondJson(exchange, 413, errorBody("payload_too_large", "request body too large"));
                return;
            }
            req = JsonUtils.getMapper().readTree(bytes);
        } catch (IOException _) {
            respondJson(exchange, 400, errorBody("invalid_json", "invalid JSON request body"));
            return;
        }
        if (req == null || !req.isObject()) {
            respondJson(exchange, 400, errorBody("invalid_json", "JSON object expected"));
            return;
        }

        String parentRaw = req.path("path").asText("");
        String name = req.path("name").asText("").trim();
        if (StringUtils.isBlank(parentRaw)) {
            respondJson(exchange, 400, errorBody("invalid_request", "path is required"));
            return;
        }
        if (StringUtils.isBlank(name)
                || Strings.CS.equals(".", name)
                || Strings.CS.equals("..", name)
                || Strings.CS.contains(name, "/")
                || Strings.CS.contains(name, "\\")) {
            respondJson(exchange, 400, errorBody("invalid_folder_name",
                "Invalid folder name: " + name));
            return;
        }

        Path home = resolveHome();
        Path parent;
        Path created;
        try {
            parent = resolveInputPath(parentRaw.trim(), home).toAbsolutePath().normalize();
            created = parent.resolve(name).normalize();
        } catch (InvalidPathException _) {
            respondJson(exchange, 400, errorBody("invalid_path", "Invalid path: " + parentRaw));
            return;
        }
        if (!Files.isDirectory(parent)) {
            respondJson(exchange, 404, errorBody("not_found",
                "Parent directory does not exist: " + parent));
            return;
        }
        if (created.getParent() == null || !created.getParent().equals(parent)) {
            respondJson(exchange, 400, errorBody("invalid_folder_name",
                "Folder name must be a direct child of parent"));
            return;
        }
        try {
            Files.createDirectory(created);
        } catch (FileAlreadyExistsException _) {
            respondJson(exchange, 409, errorBody("already_exists",
                "Folder already exists: " + name));
            return;
        } catch (IOException failure) {
            respondJson(exchange, 500, errorBody("create_failed",
                "Failed to create folder: " + failure.getMessage()));
            return;
        }

        ObjectNode body = JsonUtils.getMapper().createObjectNode();
        body.put("path", created.toString());
        respondJson(exchange, 201, body);
    }

    private static String queryParam(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI().getRawQuery();
        if (StringUtils.isBlank(query)) return "";
        for (String pair : query.split("&")) {
            int split = pair.indexOf('=');
            if (split <= 0) continue;
            if (!Strings.CS.equals(key, pair.substring(0, split))) continue;
            return URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8);
        }
        return "";
    }

    private static Path resolveHome() {
        String userHome = System.getProperty("user.home");
        if (StringUtils.isBlank(userHome)) {
            return Path.of(".").toAbsolutePath().normalize();
        }
        return Path.of(userHome).toAbsolutePath().normalize();
    }

    private static Path resolveInputPath(String raw, Path home) {
        if (Strings.CS.equals("~", raw)) return home;
        if (Strings.CS.startsWith(raw, "~/") || Strings.CS.startsWith(raw, "~\\")) {
            return home.resolve(raw.substring(2));
        }
        return Path.of(raw);
    }

    private static List<Path> buildCrumbs(Path target) {
        List<Path> chain = new ArrayList<>();
        Path cursor = target;
        while (cursor != null) {
            chain.add(cursor);
            cursor = cursor.getParent();
        }
        Collections.reverse(chain);
        return chain;
    }

    private static boolean isHiddenSafe(Path path) {
        try {
            return Files.isHidden(path);
        } catch (IOException _) {
            return false;
        }
    }
}
