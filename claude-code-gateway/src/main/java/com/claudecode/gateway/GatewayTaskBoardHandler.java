package com.claudecode.gateway;

import static com.claudecode.gateway.GatewayRequests.sessionIdOf;
import static com.claudecode.gateway.GatewayResponses.respondJson;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.tasks.TaskBoardPort;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import com.sun.net.httpserver.HttpExchange;

/**
 * {@code GET /api/session/tasks}: one session's current to-do list, feeding the
 * webui to-do dock above the composer.
 *
 * <p>This is the cold-load half of the surface. The live half is the
 * {@code task_board} mirror frame ({@link MirrorHub#publishTaskBoard}), which
 * only carries changes; a page that loads after the last change would otherwise
 * show nothing until the model next touches the list. Both halves emit the same
 * {@code tasks} array through {@link #writeSnapshot}, so the polled body and the
 * pushed frame cannot drift.
 *
 * <p>The addressed session arrives as {@code ?session_id=}; a blank id keeps the
 * legacy target — the active session.
 */
final class GatewayTaskBoardHandler {

    /** Marks a payload the mirror pushed; the live counterpart of {@link #SOURCE_SNAPSHOT}. */
    static final String SOURCE_EVENT = "event";

    /** Marks a payload this endpoint read on demand. */
    static final String SOURCE_SNAPSHOT = "snapshot";

    private final GatewayTaskBoardPort taskBoard;

    GatewayTaskBoardHandler(GatewayTaskBoardPort taskBoard) {
        this.taskBoard = taskBoard;
    }

    /** Handles one {@code GET} exchange: the addressed session's current snapshot. */
    void handleGet(HttpExchange exchange) throws IOException {
        String sessionId = sessionIdOf(exchange);
        ObjectNode response = JsonUtils.getMapper().createObjectNode();
        Optional<TaskBoardPort.Snapshot> snapshot = taskBoard.snapshot(sessionId);
        if (snapshot.isPresent()) {
            writeSnapshot(response, snapshot.get(), SOURCE_SNAPSHOT);
        } else {
            // The port has no board for this session (an unknown or headless id).
            // Answer the empty projection rather than an error: "no to-dos yet"
            // is a normal state the panel renders as absent, not a failure.
            writeSnapshot(response, TaskBoardPort.Snapshot.EMPTY, SOURCE_SNAPSHOT);
        }
        respondJson(exchange, 200, response);
    }

    /**
     * Writes the wire projection of a task-board snapshot. Shared with the
     * mirror broadcast so the SSE frame and this endpoint's body cannot drift.
     *
     * @param node     the object to fill
     * @param snapshot the board state
     * @param source   {@link #SOURCE_EVENT} for a pushed frame,
     *                 {@link #SOURCE_SNAPSHOT} for an on-demand read
     */
    static void writeSnapshot(ObjectNode node, TaskBoardPort.Snapshot snapshot, String source) {
        node.put("list_id", snapshot.listId());
        node.put("revision", snapshot.revision());
        // A hidden board (no visible task) is the panel's "render nothing" case.
        node.put("hidden", snapshot.hidden());
        node.put("source", source);
        List<TaskBoardPort.TaskItem> tasks = snapshot.tasks();
        ArrayNode rows = node.putArray("tasks");
        for (TaskBoardPort.TaskItem item : tasks) {
            ObjectNode row = rows.addObject();
            row.put("id", item.id());
            row.put("subject", item.subject());
            row.put("description", item.description());
            putNullable(row, "active_form", item.activeForm());
            putNullable(row, "owner", item.owner());
            // Lower-case wire values; the webui's discriminated union keys on
            // these. TaskBoardPort.Status is the runtime enum, never the tools
            // module's TodoStatus — the gateway cannot see claude-code-tools.
            row.put("status", statusWire(item.status()));
            putStrings(row.putArray("blocks"), item.blocks());
            putStrings(row.putArray("blocked_by"), item.blockedBy());
        }
    }

    private static String statusWire(TaskBoardPort.Status status) {
        return switch (status) {
            case PENDING -> "pending";
            case IN_PROGRESS -> "in_progress";
            case COMPLETED -> "completed";
        };
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null) node.putNull(field);
        else node.put(field, value);
    }

    private static void putStrings(ArrayNode array, List<String> values) {
        for (String value : values) {
            array.add(value);
        }
    }
}