package com.claudecode.gateway;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.lang3.Strings;

/**
 * Classpath static file serving for the bundled webui.
 *
 * <p>Serves {@code GET /webui/**} (and {@code GET /} as the index alias) from
 * the {@code /webui/} classpath root — the Vite build output copied into the
 * application JAR and the native image by the Gradle {@code buildWebui} chain.
 * Unauthenticated by design: the gateway binds loopback only and the launch
 * token guards the API routes, while the static shell itself carries no
 * secrets (the token enters only through the browsable landing URL and API
 * calls). Path traversal is rejected before any resource lookup.
 */
final class GatewayStaticFiles {

    /** The index document served at both {@code /} and {@code /webui/}. */
    private static final String INDEX = "/webui/index.html";

    /**
     * Content types by file extension; anything else serves as octet-stream.
     * Keys are lowercase without the leading dot.
     */
    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
        Map.entry("html", "text/html; charset=utf-8"),
        Map.entry("css", "text/css; charset=utf-8"),
        Map.entry("js", "text/javascript; charset=utf-8"),
        Map.entry("mjs", "text/javascript; charset=utf-8"),
        Map.entry("json", "application/json; charset=utf-8"),
        Map.entry("map", "application/json; charset=utf-8"),
        Map.entry("svg", "image/svg+xml"),
        Map.entry("png", "image/png"),
        Map.entry("webp", "image/webp"),
        Map.entry("ico", "image/x-icon"),
        Map.entry("woff", "font/woff"),
        Map.entry("woff2", "font/woff2"),
        Map.entry("txt", "text/plain; charset=utf-8"),
        Map.entry("webmanifest", "application/manifest+json"));

    /** Placeholder served when the bundle was not packaged into this build. */
    private static final String MISSING_ASSETS_PAGE = """
        <!doctype html>
        <html><head><meta charset="utf-8"><title>Claude Code</title></head>
        <body style="font-family: system-ui; padding: 32px;">
        <h1>Claude Code webui</h1>
        <p>The webui bundle is not packaged into this build. Build it with
        <code>cd webui &amp;&amp; pnpm build</code> and rebuild the application
        JAR, then restart.</p>
        </body></html>
        """;

    private GatewayStaticFiles() {}

    /**
     * Serves one static request, if it maps to a packaged webui asset.
     *
     * @param exchange the inbound exchange; never closed by this method
     * @return true when the request was a static route and a response was
     *     written; false when the path is not a static route
     * @throws IOException when writing the response fails
     */
    static boolean serveIfStatic(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!Strings.CS.equals("GET", method)) return false;
        String path = exchange.getRequestURI().getPath();
        String resource;
        if (Strings.CS.equals("/", path) || Strings.CS.equals("/webui", path)
                || Strings.CS.equals("/webui/", path)) {
            resource = INDEX;
        } else if (Strings.CS.startsWith(path, "/webui/")) {
            resource = normalize(path);
            if (resource == null) {
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                try (exchange) {
                    byte[] body = "bad request".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(400, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                }
                return true;
            }
        } else {
            return false;
        }
        byte[] body = read(resource);
        if (body == null) {
            if (Strings.CS.equals(resource, INDEX)) {
                // The bundle is absent: keep / usable with an explanation
                // instead of a bare 404 (dev JARs without the webui build).
                byte[] page = MISSING_ASSETS_PAGE.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                try (exchange) {
                    exchange.sendResponseHeaders(200, page.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(page);
                    }
                }
                return true;
            }
            byte[] notFound = "not found".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            try (exchange) {
                exchange.sendResponseHeaders(404, notFound.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(notFound);
                }
            }
            return true;
        }
        exchange.getResponseHeaders().set("Content-Type", contentType(resource));
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        try (exchange) {
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        return true;
    }

    /** Maps a request path to a classpath resource path, or null on traversal. */
    private static String normalize(String path) {
        String relative = path.substring("/webui/".length());
        if (relative.isEmpty() || relative.indexOf('\0') >= 0) return null;
        if (Strings.CS.startsWith(relative, "/") || Strings.CS.contains(relative, "../")
                || Strings.CS.contains(relative, "..\\")
                || Strings.CS.equals(relative, "..")) {
            return null;
        }
        return "/webui/" + relative;
    }

    private static byte[] read(String resource) {
        try (InputStream in = GatewayStaticFiles.class.getResourceAsStream(resource)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException _) {
            return null;
        }
    }

    private static String contentType(String resource) {
        int dot = resource.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        return CONTENT_TYPES.getOrDefault(
            resource.substring(dot + 1).toLowerCase(Locale.ROOT),
            "application/octet-stream");
    }
}
