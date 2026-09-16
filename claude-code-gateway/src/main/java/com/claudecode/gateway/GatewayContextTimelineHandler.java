package com.claudecode.gateway;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * {@code GET /api/session/context/{timeline,detail,content,overview}}: the
 * context-timeline routes the webui's vendored dsh-context surfaces read.
 *
 * <p>Covers dsh-context's host-side delivery:
 * <ul>
 *   <li>{@code src/host/detail.ts} — the {@code detail} endpoint contract
 *       ({@code rev + head + collections}, latest-wins by {@code rev}).</li>
 *   <li>{@code src/client/timelineSource.ts} (host share) — the head read
 *       the client's projection subscription used to receive as pushes.</li>
 *   <li>{@code src/client/historyPage.ts} (host share) — the seq-anchored
 *       content read ({@code content?seq=}) and the header epoch content
 *       ({@code content?kind=system|tool&name=}).</li>
 *   <li>{@code src/client/overview.ts} (host share) — the dashboard's
 *       cross-session read ({@code overview}).</li>
 * </ul>
 *
 * <p>Wire shape: these routes emit the camelCase structures of dsh-context's
 * {@code shared/types.ts} ({@code ContextTimeline}, {@code ContextTimelineDetail},
 * {@code ContextHeaders}, {@code ContextActivity}) so the vendored client
 * helpers ({@code assemble}, {@code categories}, {@code headline},
 * {@code DetailStore}) consume them unchanged — a deliberate exception to
 * the gateway's snake_case convention, recorded in {@code webui/UPSTREAM.md}.
 * A cold session (no live engine) answers {@code {"timeline": null}} /
 * {@code {"detail": null}} with 200, matching dsh's "key absent" semantics.
 */
@Explanation("camelCase dsh-context wire shapes on a snake_case gateway, so the vendored client reuses its helpers verbatim")
final class GatewayContextTimelineHandler {

    private final ContextTimelineLedger ledger;

    GatewayContextTimelineHandler(ContextTimelineLedger ledger) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    void handleTimeline(HttpExchange exchange) throws IOException {
        Map<String, String> query = query(exchange);
        ObjectNode body = JsonUtils.getMapper().createObjectNode();
        Optional<ObjectNode> head = ledger.timeline(query.get("session_id"));
        if (head.isPresent()) {
            body.set("timeline", head.get());
        } else {
            body.putNull("timeline");
        }
        respondJson(exchange, 200, body);
    }

    void handleDetail(HttpExchange exchange) throws IOException {
        Map<String, String> query = query(exchange);
        ObjectNode body = JsonUtils.getMapper().createObjectNode();
        Optional<ObjectNode> detail = ledger.detail(query.get("session_id"));
        if (detail.isPresent()) {
            body.set("detail", detail.get());
        } else {
            body.putNull("detail");
        }
        respondJson(exchange, 200, body);
    }

    void handleContent(HttpExchange exchange) throws IOException {
        Map<String, String> query = query(exchange);
        Long seq = null;
        String rawSeq = query.get("seq");
        if (StringUtils.isNotBlank(rawSeq)) {
            try {
                seq = Long.parseLong(rawSeq.strip());
            } catch (NumberFormatException _) {
                respondJson(exchange, 400, errorBody("invalid_request",
                    "seq must be an integer"));
                return;
            }
        }
        String kind = query.get("kind");
        if (seq == null && !Strings.CS.equalsAny(kind, "system", "tool", "tools")) {
            respondJson(exchange, 400, errorBody("invalid_request",
                "provide seq=<node seq>, kind=system, kind=tools, or kind=tool&name=<tool>"));
            return;
        }
        Optional<ObjectNode> content = ledger.content(
            query.get("session_id"), seq, kind, query.get("name"));
        if (content.isEmpty()) {
            respondJson(exchange, 404, errorBody("not_found", "no such context content"));
            return;
        }
        ObjectNode body = JsonUtils.getMapper().createObjectNode();
        body.set("content", content.get());
        respondJson(exchange, 200, body);
    }

    void handleOverview(HttpExchange exchange) throws IOException {
        respondJson(exchange, 200, ledger.overview());
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> result = new HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (StringUtils.isBlank(raw)) return result;
        for (String pair : raw.split("&")) {
            int split = pair.indexOf('=');
            if (split <= 0) continue;
            result.put(URLDecoder.decode(pair.substring(0, split), StandardCharsets.UTF_8),
                URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8));
        }
        return result;
    }

    private static ObjectNode errorBody(String type, String message) {
        ObjectNode body = JsonUtils.getMapper().createObjectNode();
        ObjectNode error = body.putObject("error");
        error.put("type", type);
        error.put("message", message);
        return body;
    }

    private static void respondJson(HttpExchange exchange, int status, ObjectNode body)
            throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
