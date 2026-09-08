package com.claudecode.gateway;

import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.ProgressMessage;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.turn.SessionSink;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Fan-out hub for the session mirror stream.
 *
 * <p>One long-lived hub subscription follows the application's active session
 * (gated by {@code SessionHostRegistry.isCurrent}, the same shape
 * {@code SessionLinkServer.AttachedSession} uses), re-projects each semantic
 * event as a gateway mirror frame, journals a bounded ring of frames with
 * monotonically increasing ids, and fans frames out to connected clients.
 * A reconnecting client replays the ring from its {@code Last-Event-ID};
 * a client with no cursor starts live.
 *
 * <p>Every frame carries the originating {@code session_id} so a multi-session
 * client (active TUI session plus open headless sessions) can route each frame
 * to the right conversation view; open headless sessions are attached alongside
 * the active one so the web view follows them too.
 *
 * <p>Frame production never blocks on a client: this hub only offers to each
 * connection's bounded lane, so a lagging tab disconnects itself without
 * stalling the semantic event pipeline.
 *
 * <p>Tool-result projection: {@code tool.started} frames remember each tool
 * use's name (the {@code ToolUseBlock} is the only place it appears), and
 * {@code tool.completed} frames re-project the result content into the typed
 * {@code result} object of the alignment spec's {@code ToolResult} schema —
 * a type discriminator per tool family, the textual payload, and error
 * fields, bounded by {@link #MAX_RESULT_CHARS} so one huge tool result
 * cannot crowd the journal ring.
 */
public final class MirrorHub {

    /** One mirrored session event: a named SSE event with its JSON payload. */
    public record MirrorFrame(long id, String event, String data) {}

    private static final int RING_CAPACITY = 2_048;

    /**
     * Upper bound for one tool result's projected text payload. The mirror is
     * a rendering channel, not a data channel: the on-disk transcript stays
     * the source of truth for full tool output, so the frame carries a
     * bounded excerpt with a truncation marker instead of the whole payload.
     */
    private static final int MAX_RESULT_CHARS = 20_000;
    private static final String TRUNCATION_MARKER = "\n…[truncated]";

    /** Bounded recent tool-use names by id, for typing later result frames. */
    private static final int REMEMBERED_TOOLS = 256;

    private final SessionHostRegistry registry;
    private final ArrayDeque<MirrorFrame> ring = new ArrayDeque<>();
    private final AtomicLong nextId = new AtomicLong();
    private final CopyOnWriteArrayList<Consumer<MirrorFrame>> sinks =
        new CopyOnWriteArrayList<>();
    /** Per-session subscriptions: the active session plus open headless ones. */
    private final Map<String, AutoCloseable> subscriptions = new ConcurrentHashMap<>();
    /** The session each subscription follows, for frame attribution. */
    private final Map<String, String> sessionIds = new ConcurrentHashMap<>();
    /** Each followed session's project root, for transcript-path projection. */
    private final Map<String, String> sessionProjectDirs = new ConcurrentHashMap<>();
    /**
     * Recent tool-use names by tool use id, consumed when the matching result
     * frame needs a type discriminator. The {@code ToolResultBlock} carries
     * only the id — the name lives in the earlier {@code ToolUseBlock}.
     */
    private final Map<String, String> toolNamesByUseId =
        new ConcurrentHashMap<>();

    public MirrorHub(SessionHostRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Attaches to the currently active session, if any. Re-attaching replaces
     * the previous active-session subscription; the journal is kept because
     * headless-session frames and reconnecting clients still reference it —
     * a {@code session.activated} frame marks the switch point instead.
     */
    public synchronized void attachCurrent() {
        registry.currentActivation().map(SessionHostRegistry.ActivationResult::session)
            .ifPresent(this::attach);
    }

    /** Subscribes to one session's semantic hub under the active-session slot. */
    public synchronized void attach(SessionHostSession session) {
        Objects.requireNonNull(session, "session");
        String sessionId = session.info().id();
        closeSubscription(sessionId);
        subscriptions.put(sessionId, session.events().subscribe(
            attributedSink(sessionId, () -> registry.isCurrent(sessionId))));
        sessionProjectDirs.put(sessionId, session.info().workDir());
    }

    /**
     * Subscribes to one open headless session's hub. The subscription lives
     * until {@link #detachSession} or {@link #detach} — a headless session
     * never replaces the active-session slot.
     */
    public synchronized void attachSession(SessionHostSession session) {
        Objects.requireNonNull(session, "session");
        String sessionId = session.info().id();
        closeSubscription(sessionId);
        subscriptions.put(sessionId, session.events().subscribe(
            attributedSink(sessionId, () -> true)));
        sessionIds.put(sessionId, sessionId);
        sessionProjectDirs.put(sessionId, session.info().workDir());
    }

    /** Drops one headless session's mirror subscription (session closed). */
    public synchronized void detachSession(String sessionId) {
        closeSubscription(sessionId);
    }

    /** Registers a frame consumer; returns its subscription. */
    public AutoCloseable subscribe(Consumer<MirrorFrame> sink) {
        Objects.requireNonNull(sink, "sink");
        sinks.add(sink);
        return () -> sinks.remove(sink);
    }

    /** Frames after {@code lastEventId}, in id order; empty cursor replays nothing. */
    public synchronized List<MirrorFrame> replayAfter(long lastEventId) {
        List<MirrorFrame> missed = new ArrayList<>();
        for (MirrorFrame frame : ring) {
            if (frame.id() > lastEventId) missed.add(frame);
        }
        return missed;
    }

    /** The latest journal id, for clients that want to start live. */
    public long latestId() {
        MirrorFrame last = ring.peekLast();
        return last == null ? 0 : last.id();
    }

    /** Publishes one session-activation notice so mirrors can rebind. */
    public void publishActivated(SessionHostInfo info, String origin) {
        ObjectNode payload = object();
        payload.put("id", info.id());
        if (StringUtils.isNotBlank(info.summary())) payload.put("summary", info.summary());
        payload.put("message_count", info.messageCount());
        if (info.modifiedAt() != null) {
            payload.put("modified_at", info.modifiedAt().toString());
        }
        payload.put("origin", origin);
        publish(info.id(), "session.activated", payload);
    }

    /** Publishes one pending permission ask raised by a followed session. */
    public void publishPermissionAsked(String sessionId, ObjectNode payload) {
        publish(sessionId, "permission.asked", payload);
    }

    /** Publishes the resolution of one earlier permission ask. */
    public void publishPermissionResolved(String sessionId, ObjectNode payload) {
        publish(sessionId, "permission.resolved", payload);
    }

    private SessionSink attributedSink(String sessionId, BooleanSupplier live) {
        return new SessionSink() {
            @Override public void onTurnStart(UserInput input) {
                if (live.getAsBoolean() && isFollowed(sessionId)) {
                    MirrorHub.this.onTurnStart(sessionId, input);
                }
            }
            @Override public void onMessage(SDKMessage msg) {
                if (live.getAsBoolean() && isFollowed(sessionId)) {
                    MirrorHub.this.onMessage(sessionId, msg);
                }
            }
            @Override public void onError(Throwable error, boolean userCancel) {
                if (live.getAsBoolean() && isFollowed(sessionId)) {
                    MirrorHub.this.onError(sessionId, error, userCancel);
                }
            }
            @Override public void onTurnComplete(TurnOutcome outcome) {
                if (live.getAsBoolean() && isFollowed(sessionId)) {
                    MirrorHub.this.onTurnComplete(sessionId, outcome);
                }
            }
            @Override public void onIdle() {
                if (live.getAsBoolean() && isFollowed(sessionId)) {
                    MirrorHub.this.onIdle(sessionId);
                }
            }
        };
    }

    /** True while {@code sessionId} still owns a mirror subscription. */
    private boolean isFollowed(String sessionId) {
        return subscriptions.containsKey(sessionId);
    }

    private void onTurnStart(String sessionId, UserInput input) {
        ObjectNode payload = object();
        payload.put("display_text", input.displayText());
        payload.put("permission_mode", input.permissionMode());
        payload.put("origin", input.inputOrigin());
        publish(sessionId, "turn.started", payload);
    }

    private void onMessage(String sessionId, SDKMessage msg) {
        switch (msg) {
            case SDKMessage.Assistant assistant -> publishAssistant(sessionId, assistant);
            case SDKMessage.User user -> publishToolResults(sessionId, user);
            case SDKMessage.Progress progress -> publishProgress(sessionId, progress);
            case SDKMessage.System system -> {
                ObjectNode payload = object();
                payload.put("content", system.message().content());
                payload.put("synthetic", true);
                publish(sessionId, "output.text", payload);
            }
            default -> { /* Control-only messages are not chat output. */ }
        }
    }

    /**
     * Projects one tool/agent progress message — the sub-agent's live content
     * channel. A web client follows a running sub-agent through these frames
     * (its text output and progress line), keyed by {@code tool_use_id}; the
     * agent id doubles as the sidechain transcript key for a client that
     * wants the on-disk transcript instead.
     */
    private void publishProgress(String sessionId, SDKMessage.Progress progress) {
        ProgressMessage message = progress.message();
        if (message == null || message.data() == null) return;
        ProgressMessage.ProgressData data = message.data();
        ObjectNode payload = object();
        if (message.toolUseId() != null) payload.put("tool_use_id", message.toolUseId());
        payload.put("kind", data.type() == null ? "" : data.type());
        if (data.agentId() != null) payload.put("agent_id", data.agentId());
        if (data.prompt() != null) payload.put("prompt", data.prompt());
        if (data.progress() != null) payload.put("progress", data.progress());
        if (data.progressMessage() != null) {
            payload.put("message", data.progressMessage());
        }
        if (data.message() != null) {
            String text = agentText(data.message());
            if (text != null) payload.put("content", text);
        }
        if (data.output() != null && !payload.has("content")) {
            payload.put("content", data.output());
        }
        publish(sessionId, "tool.progress", payload);
    }

    /** The agent message's text blocks, or null when it carries none. */
    private static String agentText(Message message) {
        if (!(message instanceof UserMessage user)) return null;
        MessageContent content = user.message();
        if (content == null) return null;
        if (content.text() != null) return content.text();
        if (content.blocks() == null) return null;
        StringBuilder body = new StringBuilder();
        for (ContentBlock block : content.blocks()) {
            if (block instanceof TextBlock text) body.append(text.text());
        }
        return body.isEmpty() ? null : body.toString();
    }

    private void onError(String sessionId, Throwable error, boolean userCancel) {
        if (userCancel) {
            publish(sessionId, "turn.cancelled", object());
            return;
        }
        ObjectNode payload = object();
        payload.put("message", error == null ? "session turn failed" : error.getMessage());
        publish(sessionId, "session.error", payload);
    }

    private void onTurnComplete(String sessionId, TurnOutcome outcome) {
        ObjectNode payload = object();
        payload.put("done", true);
        payload.put("elapsed_ms", outcome.elapsedMs());
        payload.put("user_cancel", outcome.userCancel());
        publish(sessionId, "turn.completed", payload);
    }

    private void onIdle(String sessionId) {
        publish(sessionId, "session.idle", object());
    }

    private void publishAssistant(String sessionId, SDKMessage.Assistant assistant) {
        if (assistant.message() == null || assistant.message().message() == null) return;
        List<ContentBlock> blocks =
            assistant.message().message().content();
        if (blocks == null) return;
        for (ContentBlock block : blocks) {
            switch (block) {
                case TextBlock text -> {
                    ObjectNode payload = object();
                    payload.put("content", text.text());
                    publish(sessionId, "output.text", payload);
                }
                case ThinkingBlock thinking -> {
                    ObjectNode payload = object();
                    payload.put("content", thinking.thinking());
                    publish(sessionId, "output.thinking", payload);
                }
                case ToolUseBlock tool -> {
                    rememberToolName(tool.id(), tool.name());
                    ObjectNode payload = object();
                    payload.put("name", tool.name());
                    payload.put("tool_use_id", tool.id());
                    if (tool.input() != null) payload.set("input", tool.input());
                    publish(sessionId, "tool.started", payload);
                }
                default -> { /* Rich blocks stay in the local renderer. */ }
            }
        }
    }

    private void publishToolResults(String sessionId, SDKMessage.User user) {
        if (user.message() == null || user.message().message() == null) return;
        List<ContentBlock> blocks =
            user.message().message().blocks();
        if (blocks == null) return;
        for (ContentBlock block : blocks) {
            if (!(block instanceof ToolResultBlock result)) continue;
            ObjectNode payload = object();
            payload.put("status", result.isError() ? "failed" : "completed");
            payload.put("tool_use_id", result.toolUseId());
            // The remembered name is consumed here once: the projected type
            // and the transcript-path lookup share it.
            String toolName = toolNamesByUseId.remove(result.toolUseId());
            ObjectNode projected = projectToolResult(result, toolName);
            String transcriptPath = agentTranscriptPath(
                sessionId, toolName, user.message().toolUseResult());
            if (transcriptPath != null) projected.put("transcript_path", transcriptPath);
            payload.set("result", projected);
            publish(sessionId, "tool.completed", payload);
        }
    }

    /**
     * The sidechain transcript path for a completed sub-agent tool, or null.
     *
     * <p>Agent-family tools attach a structured {@code toolUseResult} payload
     * carrying the {@code agentId}; the transcript file lives under the
     * session's project directory in the {@code SessionManager} sidechain
     * shape ({@code <project>/<sessionId>/subagents/agent-<agentId>.jsonl}),
     * the same path the TUI's local-agent viewer polls.
     */
    private String agentTranscriptPath(String sessionId, String toolName, Object toolUseResult) {
        if (toolUseResult == null
                || !(Strings.CS.equals("Task", toolName) || Strings.CS.equals("Agent", toolName))) {
            return null;
        }
        JsonNode payload;
        try {
            payload = JsonUtils.getMapper().valueToTree(toolUseResult);
        } catch (RuntimeException _) {
            return null;
        }
        JsonNode agentId = payload.path("agentId");
        if (!agentId.isTextual() || StringUtils.isBlank(agentId.asText())) return null;
        String projectDir = sessionProjectDirs.get(sessionId);
        if (StringUtils.isBlank(projectDir)) return null;
        return projectDir + "/" + sessionId + "/subagents/agent-" + agentId.asText()
            + ".jsonl";
    }

    /**
     * Projects one tool result into the alignment spec's {@code ToolResult}
     * shape: a type discriminator per tool family, the concatenated textual
     * payload (bounded, with an explicit truncation marker), and the error
     * message on failed results. Non-text blocks (images) carry no mirror
     * text; the transcript stays the source of truth for full output.
     */
    private ObjectNode projectToolResult(ToolResultBlock result, String toolName) {
        ObjectNode node = object();
        node.put("type", resultType(toolName));
        String text = toolResultText(result);
        if (text == null) {
            node.putNull("data");
        } else if (text.length() > MAX_RESULT_CHARS) {
            node.put("data", text.substring(0, MAX_RESULT_CHARS) + TRUNCATION_MARKER);
        } else {
            node.put("data", text);
        }
        if (result.isError()) {
            node.put("errorMessage", text);
            node.put("errorCode", "tool_error");
        }
        return node;
    }

    /** Concatenates the result's textual content blocks, or null when none. */
    private static String toolResultText(ToolResultBlock result) {
        if (result.content() == null || result.content().isEmpty()) return null;
        StringBuilder body = new StringBuilder();
        for (ContentBlock block : result.content()) {
            if (block instanceof TextBlock text) body.append(text.text());
        }
        return body.isEmpty() ? null : body.toString();
    }

    /** The typed result discriminator for the tool family, when known. */
    private static String resultType(String toolName) {
        if (toolName == null) return "tool_result";
        return switch (toolName) {
            case "Bash" -> "execute_command_tool_result";
            case "Read" -> "read_file_tool_result";
            case "Write" -> "write_to_file_tool_result";
            case "Edit", "NotebookEdit" -> "replace_in_file_tool_result";
            case "Task", "Agent" -> "task_tool_result";
            default -> "tool_result";
        };
    }

    /** Remembers one tool use's name for the later result frame, bounded. */
    private void rememberToolName(String toolUseId, String toolName) {
        if (toolUseId == null || toolName == null) return;
        toolNamesByUseId.put(toolUseId, toolName);
        if (toolNamesByUseId.size() > REMEMBERED_TOOLS) {
            Iterator<String> eldest = toolNamesByUseId.keySet().iterator();
            if (eldest.hasNext()) eldest.next();
            eldest.remove();
        }
    }

    private void publish(String sessionId, String event, ObjectNode payload) {
        payload.put("session_id", sessionId);
        MirrorFrame frame = new MirrorFrame(
            nextId.incrementAndGet(), event, payload.toString());
        synchronized (this) {
            ring.addLast(frame);
            while (ring.size() > RING_CAPACITY) ring.removeFirst();
        }
        for (Consumer<MirrorFrame> sink : sinks) {
            sink.accept(frame);
        }
    }

    private void closeSubscription(String sessionId) {
        AutoCloseable subscription = subscriptions.remove(sessionId);
        sessionIds.remove(sessionId);
        sessionProjectDirs.remove(sessionId);
        if (subscription != null) {
            try { subscription.close(); } catch (Exception _) {
                // Journal and fan-out state remain authoritative if an old
                // subscription cannot detach cleanly.
            }
        }
    }

    /** Stops following every session and clears the journal. */
    public synchronized void detach() {
        for (String sessionId : List.copyOf(subscriptions.keySet())) {
            closeSubscription(sessionId);
        }
        ring.clear();
    }

    private static ObjectNode object() {
        return JsonUtils.getMapper().createObjectNode();
    }
}
