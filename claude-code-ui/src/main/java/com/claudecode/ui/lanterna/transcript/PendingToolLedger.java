package com.claudecode.ui.lanterna.transcript;

import com.claudecode.core.message.ProgressMessage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.apache.commons.lang3.Strings;

/**
 * Per-turn bookkeeping for tool calls that have been announced but not yet resolved,
 * plus the per-{@code tool_use_id} side tables (input JSON, progress events, structured
 * results) that header tags and result renderers consult after the fact.
 *
 * <p>Tools execute serially, so the pending queue is FIFO: {@code tool_streaming_start}
 * pushes, {@code tool_result_*} pops. Lookups by id fall back to the head of the queue
 * for providers that omit ids. Rows above a pending tool can still be inserted or removed
 * (agent progress blocks, groups), so the recorded line indexes are shifted through
 * {@link #shiftLines} rather than recomputed.
 *
 * <ul>
 *   <li>{@code src/utils/messages.ts} — {@code getInProgressToolUseIDs} /
 *       {@code getUnresolvedToolUseIDs}: the set of tool uses awaiting a result.</li>
 *   <li>{@code src/components/Messages.tsx} — matching each {@code tool_result} back to its
 *       {@code tool_use} block by id, with positional fallback for legacy streams.</li>
 * </ul>
 */
final class PendingToolLedger {

    /** One in-flight tool call awaiting its result. */
    record PendingTool(int lineIdx, boolean transparent, String inputJson,
                       String toolName, int statusLineIdx, String logicalMessageId,
                       String toolUseId, String groupMessageId) {
        PendingTool withInputJson(String json) {
            return new PendingTool(lineIdx, transparent, json, toolName, statusLineIdx,
                logicalMessageId, toolUseId, groupMessageId);
        }
        PendingTool withStatusLineIdx(int index) {
            return new PendingTool(lineIdx, transparent, inputJson, toolName, index,
                logicalMessageId, toolUseId, groupMessageId);
        }
        PendingTool withLineIdx(int index) {
            return new PendingTool(index, transparent, inputJson, toolName, statusLineIdx,
                logicalMessageId, toolUseId, groupMessageId);
        }
        PendingTool shiftedAfter(int start, int delta) {
            int shiftedLine = lineIdx >= start ? lineIdx + delta : lineIdx;
            int shiftedStatus = statusLineIdx >= start ? statusLineIdx + delta : statusLineIdx;
            return new PendingTool(shiftedLine, transparent, inputJson, toolName,
                shiftedStatus, logicalMessageId, toolUseId, groupMessageId);
        }
    }

    /** The tool name and full input JSON announced for a {@code tool_use_id}. */
    record ToolInvocation(String toolName, String inputJson) {}

    private final Deque<PendingTool> pending = new ArrayDeque<>();
    private final Map<String, ToolInvocation> invocations = new HashMap<>();
    private final Map<String, List<ProgressMessage>> progressByToolUseId = new HashMap<>();
    private final Map<String, Object> resultsByToolUseId = new HashMap<>();
    private long sequence;

    /** Logical-message id for a tool card that has no {@code tool_use_id} of its own. */
    String nextLogicalId() {
        return "tool:" + (++sequence);
    }

    void clear() {
        pending.clear();
        invocations.clear();
        progressByToolUseId.clear();
        resultsByToolUseId.clear();
    }

    // ── pending queue ─────────────────────────────────────────────────────────

    boolean isEmpty() { return pending.isEmpty(); }

    int size() { return pending.size(); }

    void addLast(PendingTool tool) { pending.addLast(tool); }

    void addFirst(PendingTool tool) { pending.addFirst(tool); }

    PendingTool peekFirst() { return pending.peekFirst(); }

    PendingTool pollFirst() { return pending.pollFirst(); }

    void removeFirst() { pending.removeFirst(); }

    /** Immutable copy in queue order. */
    List<PendingTool> snapshot() { return new ArrayList<>(pending); }

    /** By id, else the queue head (for providers that omit ids). */
    PendingTool find(String toolUseId) {
        if (toolUseId == null) return pending.peekFirst();
        return pending.stream()
            .filter(tool -> Strings.CS.equals(toolUseId, tool.toolUseId()))
            .findFirst().orElse(pending.peekFirst());
    }

    /** By id, else the single legacy id-less entry, else null. */
    PendingTool findExact(String toolUseId) {
        if (toolUseId == null) return pending.peekLast();
        PendingTool exact = pending.stream()
            .filter(tool -> Strings.CS.equals(toolUseId, tool.toolUseId()))
            .findFirst().orElse(null);
        if (exact != null) return exact;
        List<PendingTool> legacyWithoutIds = pending.stream()
            .filter(tool -> tool.toolUseId() == null)
            .toList();
        return legacyWithoutIds.size() == 1 ? legacyWithoutIds.getFirst() : null;
    }

    /** Removes and returns the entry for {@code toolUseId}; head when null; legacy fallback. */
    PendingTool remove(String toolUseId) {
        if (toolUseId == null) return pending.pollFirst();
        for (var iterator = pending.iterator(); iterator.hasNext();) {
            PendingTool tool = iterator.next();
            if (Strings.CS.equals(toolUseId, tool.toolUseId())) {
                iterator.remove();
                return tool;
            }
        }
        List<PendingTool> legacy = pending.stream()
            .filter(tool -> tool.toolUseId() == null)
            .toList();
        if (legacy.size() != 1) return null;
        PendingTool tool = legacy.getFirst();
        pending.remove(tool);
        return tool;
    }

    void removeById(String toolUseId) {
        pending.removeIf(tool -> Strings.CS.equals(toolUseId, tool.toolUseId()));
    }

    void replace(PendingTool expected, PendingTool replacement) {
        reanchor(tool -> tool == expected ? replacement : tool);
    }

    /** Shifts every recorded line at or after {@code start} by {@code delta}. */
    void shiftLines(int start, int delta) {
        if (delta == 0 || pending.isEmpty()) return;
        reanchor(tool -> tool.shiftedAfter(start, delta));
    }

    /** Rewrites every entry in place, preserving queue order. */
    void reanchor(UnaryOperator<PendingTool> mapper) {
        List<PendingTool> mapped = pending.stream().map(mapper).toList();
        pending.clear();
        pending.addAll(mapped);
    }

    // ── side tables ───────────────────────────────────────────────────────────

    void recordInvocation(String toolUseId, ToolInvocation invocation) {
        invocations.put(toolUseId, invocation);
    }

    ToolInvocation invocation(String toolUseId) {
        return toolUseId == null ? null : invocations.get(toolUseId);
    }

    ToolInvocation forgetInvocation(String toolUseId) {
        return toolUseId == null ? null : invocations.remove(toolUseId);
    }

    /** Recorded invocation name, else the pending entry's name, else empty. */
    String toolName(String toolUseId) {
        ToolInvocation invocation = invocation(toolUseId);
        if (invocation != null) return invocation.toolName();
        PendingTool tool = find(toolUseId);
        return tool != null ? tool.toolName() : "";
    }

    void recordProgress(String toolUseId, ProgressMessage progress) {
        progressByToolUseId.computeIfAbsent(toolUseId, _ -> new ArrayList<>()).add(progress);
    }

    List<ProgressMessage> progress(String toolUseId) {
        return progressByToolUseId.get(toolUseId);
    }

    void recordResult(String toolUseId, Object result) {
        resultsByToolUseId.put(toolUseId, result);
    }

    Object result(String toolUseId) {
        return toolUseId == null ? null : resultsByToolUseId.get(toolUseId);
    }
}
