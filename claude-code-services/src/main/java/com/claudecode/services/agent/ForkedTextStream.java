package com.claudecode.services.agent;

import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Reads a cache-sharing fork as a plain assistant-text stream.
 *
 * <p>Deliberately free of any caller-specific error vocabulary: it reports the
 * assembled text, the cumulative usage and the responding model, and nothing
 * else. The compaction reader ({@code LlmCompactSummarizer.consumeTextStream})
 * and the side-question reader ({@code CliInteractiveSessionLauncher}) stay
 * separate because they own contracts this one must not inherit —
 * prompt-too-long markers and {@code CompactException} for the former,
 * api-error surfacing and fallback-model retry accounting for the latter.
 *
 * <p>Consuming the fork as a text stream is also what makes 236's deny-all
 * {@code canUseTool} unnecessary here: with no execution loop, a tool call the
 * model emits anyway cannot run. It is recorded in {@link Result#requestedTools}
 * so the caller can tell "spent the turn on tools" apart from "returned
 * nothing".
 */
final class ForkedTextStream {

    private ForkedTextStream() {}

    /**
     * @param text           assistant text, or {@code null} when the turn produced none
     * @param usage          cumulative token usage reported by the stream
     * @param model          model that answered, or {@code null} when the stream never said
     * @param requestedTools names of tool calls the model emitted (never executed)
     */
    record Result(String text, Usage usage, String model, List<String> requestedTools) {}

    /**
     * Drains {@code stream} into a {@link Result}.
     *
     * @throws RuntimeException the stream's own error event, so the caller's
     *                          failure handling sees the provider's exception
     *                          rather than an empty result
     */
    static Result consume(Iterator<StreamingClient.StreamingEvent> stream) {
        StringBuilder text = new StringBuilder();
        Usage usage = Usage.EMPTY;
        String model = null;
        List<String> requestedTools = new ArrayList<>();
        while (stream.hasNext()) {
            StreamingClient.StreamingEvent event = stream.next();
            switch (event) {
                case StreamingClient.StreamingEvent.MessageStartEvent start -> {
                    // Anthropic stream usage fields are cumulative snapshots, not
                    // deltas — message_delta repeats the final output token count,
                    // so adding start + delta would double-count this request in
                    // billing.
                    if (start.usage() != null) usage = usage.updateCumulative(start.usage());
                    if (start.model() != null) model = start.model();
                    if (start.content() != null) {
                        for (ContentBlock block : start.content()) {
                            if (block instanceof TextBlock(String blockText) && blockText != null) {
                                text.append(blockText);
                            } else if (block instanceof ToolUseBlock toolUse) {
                                requestedTools.add(toolUse.name());
                            }
                        }
                    }
                }
                case StreamingClient.StreamingEvent.ContentBlockStartEvent start -> {
                    if (Strings.CS.endsWith(start.type(), "tool_use")) {
                        requestedTools.add(StringUtils.defaultIfBlank(start.name(), start.type()));
                    }
                }
                case StreamingClient.StreamingEvent.ContentBlockDeltaEvent delta -> {
                    if (Strings.CS.equals("text_delta", delta.deltaType())
                            && delta.deltaText() != null) {
                        text.append(delta.deltaText());
                    }
                }
                case StreamingClient.StreamingEvent.MessageDeltaEvent delta -> {
                    if (delta.usage() != null) usage = usage.updateCumulative(delta.usage());
                }
                case StreamingClient.StreamingEvent.ErrorEvent error -> {
                    throw error.exception() instanceof RuntimeException runtime
                        ? runtime : new IllegalStateException(error.exception());
                }
                default -> { /* block boundaries and stop events carry no text */ }
            }
        }
        return new Result(
            text.isEmpty() ? null : text.toString(), usage, model, List.copyOf(requestedTools));
    }
}
