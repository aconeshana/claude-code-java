package com.claudecode.runtime.sessionlink;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.ProgressMessage;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.turn.SessionSink;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Projects SDK/domain output into Session Link events without terminal rendering.
 */
@Explanation("Semantic SDKMessage projection for collaboration endpoints")
public final class SessionLinkEventSink implements SessionSink {

    /** Activity-line truncation width, kept equal to the gateway's own constant. */
    private static final int ACTIVITY_MAX_LEN = 120;

    private final String sessionId;
    private final Consumer<SessionLinkFrame> publisher;
    private final Map<String, String> toolNames = new ConcurrentHashMap<>();
    private final AtomicBoolean terminalEmitted = new AtomicBoolean();

    public SessionLinkEventSink(String sessionId, Consumer<SessionLinkFrame> publisher) {
        this.sessionId = sessionId;
        this.publisher = publisher;
    }

    @Override
    public void onTurnStart(UserInput input) {
        terminalEmitted.set(false);
        toolNames.clear();
        ObjectNode payload = object();
        payload.put("display_text", input.displayText());
        payload.put("permission_mode", input.permissionMode());
        payload.put("origin", input.inputOrigin());
        JsonUtils.putIfNotBlank(payload, "model_override", input.modelOverride());
        JsonUtils.putIfNotBlank(payload, "effort_override", input.effortOverride());
        publish("turn.started", payload);
    }

    @Override
    public void onMessage(SDKMessage msg) {
        switch (msg) {
            case SDKMessage.Assistant assistant -> publishAssistant(assistant);
            case SDKMessage.User user -> publishToolResults(
                user.message().message() == null ? List.of()
                    : user.message().message().blocks());
            case SDKMessage.System system -> {
                ObjectNode payload = object();
                payload.put("content", system.message().content());
                payload.put("synthetic", true);
                payload.putObject("metadata").put("subtype", system.message().subtype());
                publish("output.text", payload);
            }
            case SDKMessage.StreamRequestStart start -> {
                ObjectNode payload = object();
                payload.put("model", start.model());
                payload.put("message_count", start.messageCount());
                publish("turn.model", payload);
            }
            case SDKMessage.Result result -> publishResult(result);
            case SDKMessage.Error error -> onError(error.exception(), false);
            case SDKMessage.Progress progress -> publishProgress(progress);
            default -> { /* Raw/control-only messages are not chat output. */ }
        }
    }

    /**
     * Projects one sub-agent progress tick — the child's live activity line.
     *
     * <p>This mirrors the gateway's {@code tool.progress} frame field-for-field
     * ({@code tool_use_id}/{@code kind}/{@code agent_id}/{@code prompt}/
     * {@code progress}/{@code message}/{@code content}), because a supervisor
     * following a remote TUI session has to reduce both sources into the same
     * client-side shape. {@code content} falls back to {@code output} only when
     * no child message produced an activity line, so the two projections never
     * overwrite each other.
     */
    private void publishProgress(SDKMessage.Progress progress) {
        ProgressMessage message = progress.message();
        if (message == null || message.data() == null) return;
        ProgressMessage.ProgressData data = message.data();
        ObjectNode payload = object();
        if (message.toolUseId() != null) payload.put("tool_use_id", message.toolUseId());
        payload.put("kind", data.type() == null ? "" : data.type());
        if (data.agentId() != null) payload.put("agent_id", data.agentId());
        if (data.prompt() != null) payload.put("prompt", data.prompt());
        if (data.progress() != null) payload.put("progress", data.progress());
        if (data.progressMessage() != null) payload.put("message", data.progressMessage());
        if (data.message() != null) {
            String text = activityText(data.message());
            if (text != null) payload.put("content", text);
        }
        if (data.output() != null && !payload.has("content")) {
            payload.put("content", data.output());
        }
        publish("tool.progress", payload);
    }

    /**
     * One line of activity for a child message: its first tool call, else its prose.
     *
     * <p>This repeats {@code GatewayMirrorHub}'s projection deliberately rather
     * than sharing code across the module boundary: the runtime cannot depend
     * on the gateway, and a supervisor merges both sources into one view, so the
     * two must agree on tool-name fallbacks, argument selection, and truncation.
     */
    private static String activityText(Message message) {
        if (message instanceof AssistantMessage assistant) return assistantActivity(assistant);
        if (!(message instanceof UserMessage user) || user.message() == null) return null;
        if (user.message().text() != null) return user.message().text();
        if (user.message().blocks() == null) return null;
        StringBuilder body = new StringBuilder();
        for (ContentBlock block : user.message().blocks()) {
            if (block instanceof TextBlock(String value)) body.append(value);
        }
        return body.isEmpty() ? null : body.toString();
    }

    private static String assistantActivity(AssistantMessage assistant) {
        AssistantContent content = assistant.message();
        if (content == null || content.content() == null) return null;
        StringBuilder prose = new StringBuilder();
        for (ContentBlock block : content.content()) {
            if (block instanceof ToolUseBlock tool) {
                String name = tool.name() == null ? "Tool" : tool.name();
                String summary = argsSummary(tool.input());
                return summary.isEmpty() ? name : name + "(" + summary + ")";
            }
            if (block instanceof TextBlock(String value)) prose.append(value);
        }
        return prose.isEmpty() ? null : prose.toString();
    }

    /** The first textual argument of a tool call, truncated to one line. */
    private static String argsSummary(JsonNode input) {
        if (input == null || !input.isObject()) return "";
        for (var field : input.properties()) {
            JsonNode value = field.getValue();
            if (value != null && value.isTextual()) {
                String text = value.asText().replace('\n', ' ').strip();
                if (!text.isEmpty()) {
                    return text.length() <= ACTIVITY_MAX_LEN
                        ? text : text.substring(0, ACTIVITY_MAX_LEN) + "…";
                }
            }
        }
        return "";
    }

    @Override
    public void onError(Throwable error, boolean userCancel) {
        if (userCancel) return;
        ObjectNode payload = object();
        payload.put("message", error == null ? "session turn failed" : error.getMessage());
        publish("session.error", payload);
    }

    @Override
    public void onTurnComplete(TurnOutcome outcome) {
        if (!terminalEmitted.compareAndSet(false, true)) return;
        ObjectNode payload = object();
        payload.put("content", "");
        payload.put("done", true);
        payload.putObject("metadata")
            .put("fallback", true)
            .put("elapsed_ms", outcome.elapsedMs())
            .put("user_cancel", outcome.userCancel())
            .put("permission_rejected", outcome.permissionRejected());
        publish("turn.completed", payload);
    }

    @Override
    public void onIdle() {
        publish("session.idle", object());
    }

    private void publishAssistant(SDKMessage.Assistant assistant) {
        if (assistant.message() == null || assistant.message().message() == null) return;
        List<ContentBlock> blocks = assistant.message().message().content();
        if (blocks == null) return;
        String stepId = stepId(assistant.message());
        for (ContentBlock block : blocks) {
            switch (block) {
                case TextBlock text -> {
                    ObjectNode payload = object();
                    payload.put("content", text.text());
                    payload.put("synthetic", false);
                    if (stepId != null) payload.put("message_id", stepId);
                    publish("output.text", payload);
                }
                case ThinkingBlock thinking -> {
                    ObjectNode payload = object();
                    payload.put("content", thinking.thinking());
                    if (stepId != null) payload.put("message_id", stepId);
                    publish("output.thinking", payload);
                }
                case ToolUseBlock tool -> {
                    if (tool.id() != null) toolNames.put(tool.id(), tool.name());
                    ObjectNode payload = object();
                    payload.put("name", tool.name());
                    if (tool.id() != null) payload.put("tool_use_id", tool.id());
                    if (tool.input() != null) {
                        payload.put("input", tool.input().toString());
                        payload.set("input_raw", tool.input());
                    }
                    if (stepId != null) payload.put("message_id", stepId);
                    publish("tool.started", payload);
                }
                default -> { /* Unsupported rich blocks stay in the local renderer. */ }
            }
        }
    }

    private static String stepId(AssistantMessage message) {
        if (StringUtils.isNotBlank(message.uuid())) return message.uuid();
        AssistantContent envelope = message.message();
        return envelope == null || StringUtils.isBlank(envelope.id()) ? null : envelope.id();
    }

    private void publishToolResults(List<ContentBlock> blocks) {
        if (blocks == null) return;
        for (ContentBlock block : blocks) {
            if (!(block instanceof ToolResultBlock result)) continue;
            ObjectNode payload = object();
            if (result.toolUseId() != null) payload.put("tool_use_id", result.toolUseId());
            payload.put("name", toolNames.getOrDefault(result.toolUseId(), "Tool"));
            payload.put("result", textOf(result.content()));
            payload.put("status", result.isError() ? "failed" : "completed");
            payload.put("success", !result.isError());
            publish("tool.completed", payload);
        }
    }

    private void publishResult(SDKMessage.Result result) {
        if (!terminalEmitted.compareAndSet(false, true)) return;
        Usage usage = result.totalUsage() == null ? Usage.EMPTY : result.totalUsage();
        ObjectNode payload = object();
        payload.put("content", result.resultText());
        payload.put("done", true);
        payload.put("input_tokens", usage.inputTokens());
        payload.put("output_tokens", usage.outputTokens());
        payload.put("cache_creation_input_tokens", usage.cacheCreationInputTokens());
        payload.put("cache_read_input_tokens", usage.cacheReadInputTokens());
        payload.putObject("metadata")
            .put("result_type", result.resultType())
            .put("is_error", result.isError())
            .put("stop_reason", result.stopReason());
        publish("turn.completed", payload);
    }

    private void publish(String name, JsonNode payload) {
        publisher.accept(SessionLinkFrame.event(name, sessionId, payload));
    }

    private static ObjectNode object() {
        return JsonUtils.getMapper().createObjectNode();
    }

    private static String textOf(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock(var text)) result.append(text);
        }
        return result.toString();
    }
}
