package com.claudecode.gateway;

import com.claudecode.api.ApiMessage;
import com.claudecode.api.Delta;
import com.claudecode.api.MessageDeltaData;
import com.claudecode.api.StreamEvent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Synthesizes Anthropic {@link StreamEvent} sequences from block-level
 * {@link SDKMessage}s.
 *
 * <p>This is the Messages-protocol projection of the same semantic events
 * {@code SessionLinkEventSink} projects for the IM link: one in-order
 * {@link #translate} call per hub event, stateless across turns except for
 * tool-name correlation. The projection is provider-agnostic — it reads the
 * domain blocks, not any provider's raw stream — so the same translation
 * serves Anthropic, OpenAI-compatible, and custom endpoints.
 *
 * <p>Block-index assignment follows the protocol's rules: text and thinking
 * blocks emit start+delta+stop inline, tool_use blocks emit start at the
 * assistant message and stop after their tool_result arrives, keeping the
 * interleaved order the wire format expects.
 */
public final class SdkMessageToStreamEvent {

    private final Map<String, Integer> toolUseIndexes = new ConcurrentHashMap<>();
    private final Map<String, String> toolUseNames = new ConcurrentHashMap<>();
    private final AtomicInteger nextIndex = new AtomicInteger();
    private final String messageId;

    public SdkMessageToStreamEvent() {
        this("msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
    }

    public SdkMessageToStreamEvent(String messageId) {
        this.messageId = messageId;
    }

    /**
     * One message-start frame for the turn. The message shell carries no
     * content; blocks arrive as their own events, matching how a real
     * streamed response assembles.
     */
    public StreamEvent.MessageStart messageStart(String model, Usage usage) {
        return new StreamEvent.MessageStart(new ApiMessage(
            messageId, "message", "assistant", List.of(),
            model, null, null, usage == null ? Usage.EMPTY : usage, null));
    }

    /** Translates one assistant or tool-result message into ordered frames. */
    public List<StreamEvent> translate(SDKMessage message) {
        List<StreamEvent> events = new ArrayList<>();
        switch (message) {
            case SDKMessage.Assistant assistant -> translateAssistant(assistant, events);
            case SDKMessage.User user -> translateToolResults(user, events);
            default -> { /* Only assistant output and tool results project into one turn's wire stream. */ }
        }
        return events;
    }

    /** The terminal frames: usage/stop-reason delta then message stop. */
    public List<StreamEvent> turnComplete(String stopReason, Usage usage) {
        List<StreamEvent> events = new ArrayList<>();
        events.add(new StreamEvent.MessageDelta(
            new MessageDeltaData(stopReason == null ? "end_turn" : stopReason, null),
            usage == null ? Usage.EMPTY : usage));
        events.add(new StreamEvent.MessageStop());
        return events;
    }

    /** Resets block-index correlation; call between turns. */
    public void reset() {
        toolUseIndexes.clear();
        toolUseNames.clear();
        nextIndex.set(0);
    }

    private void translateAssistant(SDKMessage.Assistant assistant, List<StreamEvent> events) {
        AssistantMessage body = assistant.message();
        if (body == null || body.message() == null) return;
        List<ContentBlock> blocks = body.message().content();
        if (blocks == null) return;
        for (ContentBlock block : blocks) {
            switch (block) {
                case TextBlock text -> emitTextBlock(text.text(), events);
                case ThinkingBlock thinking -> emitThinkingBlock(thinking, events);
                case ToolUseBlock tool -> emitToolUseBlock(tool, events);
                default -> { /* Rich blocks have no Messages-protocol face in this projection. */ }
            }
        }
    }

    private void emitTextBlock(String text, List<StreamEvent> events) {
        int index = nextIndex.getAndIncrement();
        events.add(new StreamEvent.ContentBlockStart(index, new TextBlock("")));
        events.add(new StreamEvent.ContentBlockDelta(index, new Delta.TextDelta(text)));
        events.add(new StreamEvent.ContentBlockStop(index));
    }

    private void emitThinkingBlock(ThinkingBlock thinking, List<StreamEvent> events) {
        int index = nextIndex.getAndIncrement();
        events.add(new StreamEvent.ContentBlockStart(
            index, new ThinkingBlock("", thinking.signature())));
        events.add(new StreamEvent.ContentBlockDelta(index, new Delta.ThinkingDelta(thinking.thinking())));
        events.add(new StreamEvent.ContentBlockStop(index));
    }

    private void emitToolUseBlock(ToolUseBlock tool, List<StreamEvent> events) {
        int index = nextIndex.getAndIncrement();
        toolUseIndexes.put(tool.id(), index);
        toolUseNames.put(tool.id(), tool.name());
        // The block starts with the full input available: the gateway's
        // block-level projection emits it as one input_json_delta instead of
        // the byte-level partial stream a native provider sends.
        events.add(new StreamEvent.ContentBlockStart(
            index, new ToolUseBlock(tool.id(), tool.name(), null)));
        String inputJson = tool.input() == null ? "{}" : tool.input().toString();
        events.add(new StreamEvent.ContentBlockDelta(
            index, new Delta.InputJsonDelta(inputJson)));
        // No stop yet: the wire protocol closes a tool_use block only after
        // its tool_result is delivered.
    }

    private void translateToolResults(SDKMessage.User user, List<StreamEvent> events) {
        UserMessage body = user.message();
        if (body == null || body.message() == null) return;
        List<ContentBlock> blocks = body.message().blocks();
        if (blocks == null) return;
        for (ContentBlock block : blocks) {
            if (!(block instanceof ToolResultBlock result)) continue;
            Integer index = toolUseIndexes.remove(result.toolUseId());
            if (index == null) continue; // Not this turn's tool call.
            events.add(new StreamEvent.ContentBlockStop(index));
        }
    }

    /** The tool display name for a tool-use id seen in this turn. */
    public String toolName(String toolUseId) {
        return toolUseNames.get(toolUseId);
    }
}
