package com.claudecode.runtime.sessionlink;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolResultBlock;
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
        putOptional(payload, "model_override", input.modelOverride());
        putOptional(payload, "effort_override", input.effortOverride());
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
            default -> { /* Progress/raw/control-only messages are not chat output. */ }
        }
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
        for (ContentBlock block : blocks) {
            switch (block) {
                case TextBlock text -> publishText(text.text(), false, Map.of());
                case ThinkingBlock thinking -> {
                    ObjectNode payload = object();
                    payload.put("content", thinking.thinking());
                    publish("output.thinking", payload);
                }
                case ToolUseBlock tool -> {
                    toolNames.put(tool.id(), tool.name());
                    ObjectNode payload = object();
                    payload.put("name", tool.name());
                    if (tool.input() != null) {
                        payload.put("input", tool.input().toString());
                        payload.set("input_raw", tool.input());
                    }
                    publish("tool.started", payload);
                }
                default -> { /* Unsupported rich blocks stay in the local renderer. */ }
            }
        }
    }

    private void publishToolResults(List<ContentBlock> blocks) {
        if (blocks == null) return;
        for (ContentBlock block : blocks) {
            if (!(block instanceof ToolResultBlock result)) continue;
            ObjectNode payload = object();
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

    private void publishText(String content, boolean synthetic, Map<String, String> metadata) {
        ObjectNode payload = object();
        payload.put("content", content == null ? "" : content);
        if (synthetic) payload.put("synthetic", true);
        if (!metadata.isEmpty()) payload.set("metadata", JsonUtils.getMapper().valueToTree(metadata));
        publish("output.text", payload);
    }

    private void publish(String name, JsonNode payload) {
        publisher.accept(SessionLinkFrame.event(name, sessionId, payload));
    }

    private static ObjectNode object() {
        return JsonUtils.getMapper().createObjectNode();
    }

    private static void putOptional(ObjectNode target, String field, String value) {
        if (StringUtils.isNotBlank(value)) target.put(field, value);
    }

    private static String textOf(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock text) result.append(text.text());
        }
        return result.toString();
    }
}
