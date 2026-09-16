package com.claudecode.gateway;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.metrics.SessionMetricsSnapshot;
import com.claudecode.core.model.ModelContextWindows;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.turn.SessionSink;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * The per-session context-timeline ledgers behind the gateway's
 * {@code /api/session/context/{timeline,detail,content,overview}} routes:
 * one {@link ContextTimelineFold} per live session, kept current by
 * subscribing to the session's semantic hub the way {@link MirrorHub} does.
 *
 * <p>Covers dsh-context's host composition around its fold:
 * <ul>
 *   <li>{@code src/host/timeline.ts} — the projection unit lifecycle: one
 *       fold state per session, created on attach, advanced per event,
 *       revision-stamped for the client's latest-wins cursor.</li>
 *   <li>{@code src/host/detail.ts} — the on-demand detail payload
 *       ({@code rev + head + collections}) served off the live fold.</li>
 *   <li>{@code src/host/headers.ts} — the request-header epoch metadata
 *       (system tokens + per-tool prices); the content rides the
 *       {@code content?kind=system|tool} route on demand.</li>
 *   <li>{@code src/client/overview.ts} (host share) — the cross-session
 *       aggregate the Context Dashboard renders: every live session's head
 *       plus its activity ledger.</li>
 * </ul>
 *
 * <p>Only live sessions (the active TUI session and open headless sessions)
 * own a ledger. Each hub callback re-syncs the fold from the port's message
 * list, so compaction's in-place rewrite is observed before the replaced
 * rows are gone; a GET re-syncs too, so a quiet session never serves a
 * stale head. Transcript-only ids have no fold and answer {@code null}.
 */
@Explanation("Gateway-side stateful ledger; dsh keeps the fold in the harness's projection registry")
public final class ContextTimelineLedger {

    /** Refresh cadence for the header epoch (the port assembles the prompt on each call). */
    private static final long HEADERS_TTL_MS = 15_000;

    /** One live session's fold plus its header epoch cache and run state. */
    private static final class Entry {
        final ContextTimelineFold fold = new ContextTimelineFold();
        volatile boolean running;
        volatile long updatedAt = System.currentTimeMillis();
        GatewaySessionContextPort.HeaderContent headers;
        long headersAt;
        long headersSeq;
        int headersEpoch;
    }

    private final SessionHostRegistry registry;
    private final GatewaySessionContextPort port;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, AutoCloseable> subscriptions = new HashMap<>();

    public ContextTimelineLedger(SessionHostRegistry registry, GatewaySessionContextPort port) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.port = Objects.requireNonNull(port, "port");
    }

    /** Follows one session's hub; re-attaching replaces the previous subscription. */
    public synchronized void attach(SessionHostSession session) {
        Objects.requireNonNull(session, "session");
        String sessionId = session.info().id();
        detach(sessionId);
        Entry entry = entries.computeIfAbsent(sessionId, _ -> new Entry());
        subscriptions.put(sessionId, session.events().subscribe(sink(sessionId)));
        sync(sessionId, entry);
    }

    /** Drops one session's subscription and ledger (session closed). */
    public synchronized void detach(String sessionId) {
        AutoCloseable previous = subscriptions.remove(sessionId);
        if (previous != null) {
            try {
                previous.close();
            } catch (Exception _) {
                // A closing subscription has nothing left to deliver.
            }
        }
    }

    /** Forgets a closed session's ledger entirely. */
    public synchronized void forget(String sessionId) {
        detach(sessionId);
        entries.remove(sessionId);
    }

    /** Releases every subscription (gateway shutdown). */
    public synchronized void close() {
        for (String sessionId : List.copyOf(subscriptions.keySet())) detach(sessionId);
    }

    /** The slim head for {@code sessionId} (blank = active TUI session), or empty when cold. */
    public Optional<ObjectNode> timeline(String sessionId) {
        return resolve(sessionId).map(resolved -> {
            Entry entry = resolved.entry();
            sync(resolved.id(), entry);
            synchronized (entry) {
                return head(resolved.id(), entry);
            }
        });
    }

    /** The detail payload ({@code rev/head/collections/headers/agents}), or empty when cold. */
    public Optional<ObjectNode> detail(String sessionId) {
        return resolve(sessionId).map(resolved -> {
            Entry entry = resolved.entry();
            sync(resolved.id(), entry);
            refreshHeaders(resolved.id(), entry);
            synchronized (entry) {
                ObjectNode result = JsonUtils.getMapper().createObjectNode();
                result.put("rev", entry.fold.detailRev());
                result.set("head", head(resolved.id(), entry));
                entry.fold.detailInto(result);
                result.set("headers", headersBody(entry));
                return result;
            }
        });
    }

    /**
     * One node's stored content by {@code seq}, the system prompt
     * ({@code kind=system}), or one tool's definition ({@code kind=tool&name=}).
     * Empty when the session is cold or the target is unknown.
     */
    public Optional<ObjectNode> content(String sessionId, Long seq, String kind, String name) {
        return resolve(sessionId).flatMap(resolved -> {
            Entry entry = resolved.entry();
            ObjectNode result = JsonUtils.getMapper().createObjectNode();
            if (seq != null) {
                sync(resolved.id(), entry);
                synchronized (entry) {
                    ContextTimelineFold.Node node = entry.fold.nodeAt(seq);
                    if (node == null) return Optional.empty();
                    result.put("seq", node.seq);
                    result.put("cat", node.cat.wire());
                    if (node.tool != null) result.put("tool", node.tool);
                    if (node.name != null) result.put("name", node.name);
                    result.put("text", node.content == null ? "" : node.content);
                    return Optional.of(result);
                }
            }
            refreshHeaders(resolved.id(), entry);
            GatewaySessionContextPort.HeaderContent headers;
            synchronized (entry) {
                headers = entry.headers;
            }
            if (headers == null) return Optional.empty();
            if (Strings.CS.equals(kind, "system")) {
                result.put("kind", "system");
                result.put("text", String.join("\n\n", headers.systemPromptParts()));
                ArrayNode parts = result.putArray("parts");
                headers.systemPromptParts().forEach(parts::add);
                return Optional.of(result);
            }
            if (Strings.CS.equals(kind, "tools")) {
                // Every tool definition in one answer (the browser's header
                // epoch opens all schemas together; one GET beats N).
                result.put("kind", "tools");
                ArrayNode tools = result.putArray("tools");
                for (GatewaySessionContextPort.HeaderTool tool : headers.tools()) {
                    toolBody(tools.addObject(), tool);
                }
                return Optional.of(result);
            }
            if (Strings.CS.equals(kind, "tool") && StringUtils.isNotBlank(name)) {
                for (GatewaySessionContextPort.HeaderTool tool : headers.tools()) {
                    if (!Strings.CS.equals(tool.name(), name)) continue;
                    result.put("kind", "tool");
                    toolBody(result, tool);
                    return Optional.of(result);
                }
            }
            return Optional.empty();
        });
    }

    /** The dashboard aggregate: every live session's head, activity, and run state. */
    public ObjectNode overview() {
        ObjectNode result = JsonUtils.getMapper().createObjectNode();
        ArrayNode sessions = result.putArray("sessions");
        for (GatewaySessionContextPort.LiveSession live : port.liveSessions()) {
            Entry entry = entryFor(live.id());
            if (entry == null) continue;
            sync(live.id(), entry);
            ObjectNode row = sessions.addObject();
            row.put("id", live.id());
            row.put("title", StringUtils.defaultString(live.title()));
            row.put("cwd", StringUtils.defaultString(live.cwd()));
            row.put("running", entry.running);
            row.put("updatedAt", live.updatedAt() != null
                ? Math.max(live.updatedAt().toEpochMilli(), entry.updatedAt) : entry.updatedAt);
            synchronized (entry) {
                row.set("timeline", head(live.id(), entry));
                row.set("activity", entry.fold.activity());
            }
        }
        result.put("time", System.currentTimeMillis());
        return result;
    }

    private static void toolBody(ObjectNode row, GatewaySessionContextPort.HeaderTool tool) {
        row.put("name", tool.name());
        if (tool.description() != null) row.put("description", tool.description());
        if (tool.inputSchema() != null) {
            row.set("schema", JsonUtils.getMapper().valueToTree(tool.inputSchema()));
        }
        if (tool.source() != null) row.put("source", tool.source());
    }

    // ------------------------------------------------------------ internals

    private record Resolved(String id, Entry entry) {}

    /**
     * Resolves the addressed session to its ledger: a blank id is the active
     * TUI session; a known live id is its entry (created on first sight when
     * the port still serves live rows for it — a session attached before the
     * ledger existed). Cold ids resolve to nothing.
     */
    private Optional<Resolved> resolve(String sessionId) {
        String id = StringUtils.isBlank(sessionId) ? activeId() : sessionId;
        if (id == null) return Optional.empty();
        Entry entry = entryFor(id);
        return entry == null ? Optional.empty() : Optional.of(new Resolved(id, entry));
    }

    private Entry entryFor(String id) {
        Entry entry = entries.get(id);
        if (entry != null) return entry;
        boolean live = Strings.CS.equals(id, activeId())
            || port.liveSessions().stream().anyMatch(row -> Strings.CS.equals(row.id(), id));
        if (!live) return null;
        return entries.computeIfAbsent(id, _ -> new Entry());
    }

    private String activeId() {
        return registry.currentActivation()
            .map(activation -> activation.session().info().id())
            .filter(StringUtils::isNotBlank)
            .orElse(null);
    }

    /** Re-folds the session's live rows; the envelope and route ride along. */
    private void sync(String id, Entry entry) {
        List<Message> rows;
        try {
            rows = port.messages(id).orElse(null);
        } catch (RuntimeException _) {
            rows = null;
        }
        if (rows == null) return;
        synchronized (entry) {
            port.breakdown(id).ifPresent(breakdown ->
                entry.fold.envelope(breakdown.systemTokens(), breakdown.toolsTokens()));
            port.selection(id).map(GatewaySessionContextPort.ModelSelection::current)
                .ifPresent(entry.fold::model);
            try {
                if (entry.fold.sync(rows)) entry.updatedAt = System.currentTimeMillis();
            } catch (RuntimeException _) {
                // The live engine's list is an unsynchronized view: a GET that
                // races an append or a compaction rewrite can observe a
                // shifting size. The next sync (the hub's own callback runs on
                // the engine thread) re-folds from a settled list.
            }
        }
    }

    private void refreshHeaders(String id, Entry entry) {
        long now = System.currentTimeMillis();
        synchronized (entry) {
            if (entry.headers != null && now - entry.headersAt < HEADERS_TTL_MS) return;
        }
        GatewaySessionContextPort.HeaderContent fetched;
        try {
            fetched = port.headerContent(id).orElse(null);
        } catch (RuntimeException _) {
            fetched = null;
        }
        synchronized (entry) {
            entry.headersAt = now;
            if (fetched == null) return;
            boolean changed = entry.headers == null
                || !entry.headers.tools().stream().map(GatewaySessionContextPort.HeaderTool::name).toList()
                    .equals(fetched.tools().stream().map(GatewaySessionContextPort.HeaderTool::name).toList())
                || !entry.headers.systemPromptParts().equals(fetched.systemPromptParts());
            entry.headers = fetched;
            if (changed) entry.headersEpoch++;
        }
    }

    private ObjectNode head(String id, Entry entry) {
        Long contextWindow = null;
        String model = entry.fold.model();
        if (StringUtils.isNotBlank(model)) {
            long window = ModelContextWindows.defaultContextWindow(model);
            if (window > 0) contextWindow = window;
        }
        ObjectNode timing = null;
        Optional<SessionMetricsSnapshot> metrics = port.metrics(id)
            .filter(SessionMetricsSnapshot::complete);
        if (metrics.isPresent()) {
            SessionMetricsSnapshot fold = metrics.get();
            timing = entry.fold.timing(fold.llmMs(), fold.toolMs(), fold.ttftMs(), fold.steps());
        }
        ObjectNode head = entry.fold.head(contextWindow, timing);
        head.put("running", entry.running);
        return head;
    }

    /** The header epoch list (dsh {@code ContextHeaders}), priced by JSON length. */
    private ObjectNode headersBody(Entry entry) {
        ObjectNode result = JsonUtils.getMapper().createObjectNode();
        ArrayNode headers = result.putArray("headers");
        if (entry.headers == null) return result;
        ObjectNode epoch = headers.addObject();
        epoch.put("seq", 0);
        epoch.put("time", entry.headersAt);
        epoch.put("epoch", entry.headersEpoch);
        long systemChars = 0;
        for (String part : entry.headers.systemPromptParts()) systemChars += part.length();
        if (systemChars > 0) epoch.put("systemTokens", Math.ceilDiv(systemChars, 4) + 4);
        ArrayNode tools = epoch.putArray("tools");
        for (GatewaySessionContextPort.HeaderTool tool : entry.headers.tools()) {
            ObjectNode row = tools.addObject();
            row.put("name", tool.name());
            long chars = (tool.name() == null ? 0 : tool.name().length())
                + (tool.description() == null ? 0 : tool.description().length());
            if (tool.inputSchema() != null) {
                try {
                    chars += JsonUtils.getMapper().writeValueAsString(tool.inputSchema()).length();
                } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException _) {
                    // Unserializable schema: priced by name and description only.
                }
            }
            row.put("tokens", Math.ceilDiv(chars, 4));
            if (tool.source() != null) row.put("plugin", tool.source());
        }
        return result;
    }

    private SessionSink sink(String sessionId) {
        return new SessionSink() {
            @Override public void onTurnStart(UserInput input) {
                Entry entry = entries.get(sessionId);
                if (entry == null) return;
                entry.running = true;
                entry.updatedAt = System.currentTimeMillis();
                sync(sessionId, entry);
            }
            @Override public void onMessage(SDKMessage msg) {
                // Only rows that change the conversation list re-fold; stream
                // deltas, progress, and status frames would otherwise cost an
                // O(n) diff each on the engine thread under the hub lock.
                if (!(msg instanceof SDKMessage.Assistant || msg instanceof SDKMessage.User
                    || msg instanceof SDKMessage.System || msg instanceof SDKMessage.Attachment
                    || msg instanceof SDKMessage.CompactBoundary || msg instanceof SDKMessage.Tombstone
                    || msg instanceof SDKMessage.Result || msg instanceof SDKMessage.Error)) {
                    return;
                }
                Entry entry = entries.get(sessionId);
                if (entry != null) sync(sessionId, entry);
            }
            @Override public void onError(Throwable error, boolean userCancel) {
                Entry entry = entries.get(sessionId);
                if (entry != null) entry.running = false;
            }
            @Override public void onTurnComplete(TurnOutcome outcome) {
                Entry entry = entries.get(sessionId);
                if (entry == null) return;
                entry.running = false;
                sync(sessionId, entry);
            }
            @Override public void onIdle() {
                Entry entry = entries.get(sessionId);
                if (entry != null) entry.running = false;
            }
        };
    }
}
