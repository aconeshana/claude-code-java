package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.api.Delta;
import com.claudecode.api.StreamEvent;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Golden tests for the Messages-protocol projection: the exact StreamEvent
 * sequence a turn's blocks must produce, including the interleaved tool_use /
 * tool_result block-index discipline the wire format expects.
 */
class SdkMessageToStreamEventTest {

    @Test
    void textBlockEmitsStartDeltaStop() {
        SdkMessageToStreamEvent translator = new SdkMessageToStreamEvent();
        List<StreamEvent> events = translator.translate(assistant(List.of(new TextBlock("Hello"))));

        assertThat(events).hasSize(3);
        assertThat(events.getFirst()).isInstanceOf(StreamEvent.ContentBlockStart.class);
        assertThat(events.get(1)).isInstanceOf(StreamEvent.ContentBlockDelta.class);
        assertThat(events.get(2)).isInstanceOf(StreamEvent.ContentBlockStop.class);
        StreamEvent.ContentBlockDelta delta = (StreamEvent.ContentBlockDelta) events.get(1);
        assertThat(delta.delta()).isInstanceOf(Delta.TextDelta.class);
        assertThat(((Delta.TextDelta) delta.delta()).text()).isEqualTo("Hello");
    }

    @Test
    void thinkingBlockEmitsThinkingDelta() {
        SdkMessageToStreamEvent translator = new SdkMessageToStreamEvent();
        List<StreamEvent> events = translator.translate(assistant(List.of(
            new ThinkingBlock("let me think", "sig-1"))));

        assertThat(events).hasSize(3);
        StreamEvent.ContentBlockDelta delta = (StreamEvent.ContentBlockDelta) events.get(1);
        assertThat(delta.delta()).isInstanceOf(Delta.ThinkingDelta.class);
        assertThat(((Delta.ThinkingDelta) delta.delta()).thinking()).isEqualTo("let me think");
    }

    @Test
    void toolUseStartsAndClosesAfterItsResultArrives() {
        SdkMessageToStreamEvent translator = new SdkMessageToStreamEvent();
        // Assistant: [text, tool_use]
        List<StreamEvent> assistantEvents = translator.translate(assistant(List.of(
            new TextBlock("checking"),
            new ToolUseBlock("toolu_1", "Bash", null))));
        // User: tool_result for toolu_1
        List<StreamEvent> resultEvents = translator.translate(userWithToolResult("toolu_1"));

        // text → 3 frames; tool_use → start + input delta (stop withheld)
        assertThat(assistantEvents).hasSize(5);
        StreamEvent.ContentBlockStart toolStart =
            (StreamEvent.ContentBlockStart) assistantEvents.get(3);
        assertThat(toolStart.contentBlock()).isInstanceOf(ToolUseBlock.class);
        assertThat(((ToolUseBlock) toolStart.contentBlock()).name()).isEqualTo("Bash");

        // The result closes the tool_use block: exactly one stop frame.
        assertThat(resultEvents).hasSize(1);
        assertThat(resultEvents.getFirst()).isInstanceOf(StreamEvent.ContentBlockStop.class);
        assertThat(((StreamEvent.ContentBlockStop) resultEvents.getFirst()).index())
            .isEqualTo(toolStart.index());
    }

    @Test
    void blockIndexesIncrementInEmissionOrder() {
        SdkMessageToStreamEvent translator = new SdkMessageToStreamEvent();
        translator.translate(assistant(List.of(
            new TextBlock("a"), new ThinkingBlock("b", null), new TextBlock("c"))));

        List<StreamEvent> events = translator.translate(assistant(List.of(
            new TextBlock("d"))));
        StreamEvent.ContentBlockStart fourth = (StreamEvent.ContentBlockStart) events.getFirst();
        assertThat(fourth.index()).isEqualTo(3);
    }

    @Test
    void foreignToolResultIsIgnored() {
        SdkMessageToStreamEvent translator = new SdkMessageToStreamEvent();
        translator.translate(assistant(List.of(new ToolUseBlock("toolu_1", "Bash", null))));

        // A result for a tool call this turn never made must not emit a frame.
        assertThat(translator.translate(userWithToolResult("toolu_other"))).isEmpty();
    }

    @Test
    void turnCompleteEmitsUsageDeltaAndStop() {
        SdkMessageToStreamEvent translator = new SdkMessageToStreamEvent();
        List<StreamEvent> events = translator.turnComplete("end_turn", new Usage(10, 20, 0, 0));

        assertThat(events).hasSize(2);
        StreamEvent.MessageDelta delta = (StreamEvent.MessageDelta) events.getFirst();
        assertThat(delta.delta().stopReason()).isEqualTo("end_turn");
        assertThat(delta.usage().outputTokens()).isEqualTo(20);
        assertThat(events.get(1)).isInstanceOf(StreamEvent.MessageStop.class);
    }

    @Test
    void resetClearsToolCorrelation() {
        SdkMessageToStreamEvent translator = new SdkMessageToStreamEvent();
        translator.translate(assistant(List.of(new ToolUseBlock("toolu_1", "Bash", null))));
        translator.reset();

        // After reset the stale result no longer matches a tracked tool call.
        assertThat(translator.translate(userWithToolResult("toolu_1"))).isEmpty();
        // And block indexes start over.
        List<StreamEvent> events = translator.translate(assistant(List.of(new TextBlock("x"))));
        assertThat(((StreamEvent.ContentBlockStart) events.getFirst()).index()).isZero();
    }

    private static SDKMessage.Assistant assistant(List<ContentBlock> blocks) {
        AssistantMessage message = new AssistantMessage(
            UUID.randomUUID().toString(),
            new AssistantContent(null, blocks, null));
        return new SDKMessage.Assistant(message, Usage.EMPTY, "claude-sonnet-5");
    }

    private static SDKMessage.User userWithToolResult(String toolUseId) {
        UserMessage message = new UserMessage(
            UUID.randomUUID().toString(),
            new MessageContent(null, List.<ContentBlock>of(
                new ToolResultBlock(toolUseId, List.of(new TextBlock("done")), false, false, false))));
        return new SDKMessage.User(message);
    }
}
