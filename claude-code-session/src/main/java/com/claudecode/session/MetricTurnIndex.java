package com.claudecode.session;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.Strings;

/**
 * The transcript turns that each owe one complete metrics turn: the main
 * submitted user rows, indexed by uuid as a transcript is read.
 *
 * <p>A promptSource row whose transcript parent is a tool_result row is a
 * mid-turn injection (historically the queue drain stamping the then-current
 * prompt provenance), not a turn opener — it participates in an
 * already-measured turn and must not enter the coverage obligation, or a
 * single queued prompt drain would permanently poison the restore
 * completeness check. The structural parent check also self-heals older
 * transcripts written before drain rows stopped claiming prompt provenance.
 *
 * <p>Rows are kept as the few facts that rule reads, not as parsed trees, so
 * a long transcript's index stays small. A later row with the same uuid
 * replaces an earlier one, as a whole-file uuid map does.
 */
final class MetricTurnIndex {

    private final Map<String, Row> rowsByUuid = new HashMap<>();

    /** Indexes one transcript row; a row without a uuid links nothing. */
    void accept(JsonNode node) {
        if (node.hasNonNull("uuid")) rowsByUuid.put(node.path("uuid").asText(), Row.of(node));
    }

    /** The turn-opening prompt uuids of every row accepted. */
    List<String> turnIds() {
        return turnIds(Map.of());
    }

    /**
     * The turn-opening prompt uuids with {@code later} rows laid over the
     * accepted ones, without accepting them.
     */
    List<String> turnIds(Map<String, Row> later) {
        Set<String> uuids = new HashSet<>(rowsByUuid.keySet());
        uuids.addAll(later.keySet());
        List<String> turnIds = new ArrayList<>();
        for (String uuid : uuids) {
            Row row = rowOf(uuid, later);
            if (row.user() && row.promptSource() && !injectedMidTurn(row, later)) {
                turnIds.add(uuid);
            }
        }
        return List.copyOf(turnIds);
    }

    /** Rows indexed the way {@link #accept} would, for {@link #turnIds(Map)}. */
    static Map<String, Row> rowsOf(List<JsonNode> nodes) {
        Map<String, Row> rows = new HashMap<>();
        for (JsonNode node : nodes) {
            if (node.hasNonNull("uuid")) rows.put(node.path("uuid").asText(), Row.of(node));
        }
        return rows;
    }

    private Row rowOf(String uuid, Map<String, Row> later) {
        Row row = later.get(uuid);
        return row != null ? row : rowsByUuid.get(uuid);
    }

    /** Whether a prompt row is chained onto a tool_result row. */
    private boolean injectedMidTurn(Row row, Map<String, Row> later) {
        if (row.parentUuid() == null) return false;
        Row parent = rowOf(row.parentUuid(), later);
        return parent != null && parent.user() && parent.carriesToolResult();
    }

    /**
     * The facts of one row the turn rule reads.
     *
     * @param user whether its type is {@code user}
     * @param promptSource whether it records prompt provenance
     * @param parentUuid its textual parent link, or {@code null}
     * @param carriesToolResult whether its message content holds a tool_result block
     */
    record Row(boolean user, boolean promptSource, String parentUuid,
            boolean carriesToolResult) {

        static Row of(JsonNode node) {
            JsonNode parent = node.path("parentUuid");
            return new Row(
                Strings.CS.equals("user", node.path("type").asText()),
                node.hasNonNull("promptSource"),
                parent.isTextual() ? parent.asText() : null,
                carriesToolResult(node.path("message").path("content")));
        }

        private static boolean carriesToolResult(JsonNode content) {
            if (!content.isArray()) return false;
            for (JsonNode block : content) {
                if (Strings.CS.equals("tool_result", block.path("type").asText())) return true;
            }
            return false;
        }
    }
}
