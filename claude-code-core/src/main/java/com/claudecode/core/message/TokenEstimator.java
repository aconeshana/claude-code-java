package com.claudecode.core.message;

import com.claudecode.core.annotation.Explanation;

import java.util.List;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Estimates token counts for messages.
 */
public final class TokenEstimator {

    /** Default bytes/chars per token for rough estimation. */
    private static final int CHARS_PER_TOKEN = 4;

    private TokenEstimator() {
    }

    private static final TokenEstimator INSTANCE = new TokenEstimator();

    public static TokenEstimator getInstance() {
        return INSTANCE;
    }


    public long estimateTokenCount(List<Message> messages) {
        return roughTokenCountEstimationForMessages(messages, CHARS_PER_TOKEN);
    }


    public long estimatePostCompactTokenCount(List<Message> messages) {
        long totalTokens = 0;
        for (Message message : messages) {
            if (message instanceof AttachmentMessage attachment
                    && attachment.payload() instanceof InvokedSkillsAttachment) {
                totalTokens += roughTokenCountEstimation(
                    MessageConstants.wrapInSystemReminder(
                        AttachmentRenderer.renderSystemContent(List.of(attachment.payload()))),
                    CHARS_PER_TOKEN);
            } else {
                totalTokens += roughTokenCountEstimationForMessage(
                    message, CHARS_PER_TOKEN);
            }
        }
        return totalTokens;
    }

    


    public long tokenCountWithEstimation(List<Message> messages, int charsPerToken) {
        return tokenCountWithEstimation(messages, null, charsPerToken);
    }

    /**
     * Model-aware context accounting.
     */
    @Explanation("Codex-style context accounting for GPT model ids")
    public long tokenCountWithEstimation(
            List<Message> messages, String model, int charsPerToken) {
        int safeCharsPerToken = charsPerToken > 0 ? charsPerToken : 4;
        boolean codexGpt = isGptModel(model);
        int index = messages.size() - 1;
        while (index >= 0) {
            Message message = messages.get(index);
            UsageSnapshot snapshot = usageSnapshot(message);
            if (snapshot != null) {
                Usage usage = snapshot.usage();
                String responseId = assistantMessageId(message);
                int anchorIndex = index;
                if (StringUtils.isNotBlank(responseId)) {
                    for (int priorIndex = index - 1; priorIndex >= 0; priorIndex--) {
                        String priorId = assistantMessageId(messages.get(priorIndex));
                        if (responseId.equals(priorId)) {
                            anchorIndex = priorIndex;
                        } else if (priorId != null) {
                            break;
                        }
                    }
                }
                String reportingModel = modelOrFallback(snapshot.model(), model);
                long exactContext = contextTokens(usage, reportingModel);
                List<Message> tail = messages.subList(anchorIndex + 1, messages.size());
                return exactContext + (codexGpt
                    ? codexTailTokenCount(tail, responseId)
                    : roughTokenCountEstimationForMessages(
                        tail, safeCharsPerToken));
            }
            index--;
        }
        return codexGpt
            ? codexTailTokenCount(messages, null)
            : roughTokenCountEstimationForMessages(messages, safeCharsPerToken);
    }

    /** True for built-in or custom model ids whose normalized name contains {@code gpt}. */
    @Explanation("Selects Codex token semantics by GPT model id")
    public static boolean isGptModel(String model) {
        return model != null && Strings.CS.contains(model.toLowerCase(Locale.ROOT), "gpt");
    }

    /**
     * Input tokens occupying the current context. All Java provider adapters
     * expose disjoint cache buckets; OpenAI's raw prompt total is recovered
     * from {@code total_tokens - output_tokens} when available.
     */
    @Explanation("Avoids double-counting OpenAI cached input tokens")
    public static long contextInputTokens(Usage usage, String model) {
        if (usage == null) return 0L;
        if (isGptModel(model)) {
            if (usage.reportedTotalTokens() != null) {
                return Math.max(0L, usage.reportedTotalTokens() - usage.outputTokens());
            }
            return saturatedAdd(usage.inputTokens(), usage.cacheReadInputTokens());
        }
        return saturatedAdd(usage.inputTokens(), saturatedAdd(
            usage.cacheCreationInputTokens(), usage.cacheReadInputTokens()));
    }

    /** Latest API response's full context snapshot, including model output. */
    @Explanation("Uses OpenAI input plus output as Codex's usage anchor")
    public static long contextTokens(Usage usage, String model) {
        if (usage == null) return 0L;
        if (isGptModel(model) && usage.reportedTotalTokens() != null) {
            return Math.max(0L, usage.reportedTotalTokens());
        }
        return saturatedAdd(contextInputTokens(usage, model), usage.outputTokens());
    }

    /**
     * A real API usage snapshot and the response model whose provider semantics define its cache
     * fields.
     */
    @Explanation("Preserves provider usage semantics across session model switches")
    public record UsageSnapshot(Usage usage, String model) {}

    /** Returns the latest real API usage snapshot, or {@code null}. */
    @Explanation("Shared status/context accounting for cross-provider model switches")
    public static UsageSnapshot latestUsageSnapshot(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            UsageSnapshot snapshot = usageSnapshot(messages.get(i));
            if (snapshot != null) return snapshot;
        }
        return null;
    }

    /**
     * Returns the latest API usage whose assistant envelope has received its
     * terminal message delta. During a fast tool loop the next Responses round
     * can append a provisional {@link Usage#EMPTY} assistant block before the
     * status-line debounce snapshots the conversation; that provisional block
     * must not hide the preceding round's finalized context usage.
     */
    @Explanation("Prevents provisional OpenAI Responses blocks from resetting HUD context usage")
    public static UsageSnapshot latestFinalizedUsageSnapshot(List<Message> messages) {
        UsageSnapshot latestCompatibleSnapshot = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            UsageSnapshot snapshot = usageSnapshot(message);
            if (snapshot == null) continue;
            if (latestCompatibleSnapshot == null) latestCompatibleSnapshot = snapshot;
            if (message instanceof AssistantMessage assistant
                    && assistant.message().stopReason() != null) {
                return snapshot;
            }
        }
        // Older transcripts and compatibility adapters may persist real usage
        // without a stop reason. Preserve that historical behavior when there
        // is no explicitly finalized envelope to prefer.
        return latestCompatibleSnapshot;
    }

    /**
     * Rough token count estimate for a single string.
     */
    public long estimateTokenCount(String text) {
        if (StringUtils.isEmpty(text)) {
            return 0;
        }
        return Math.round((double) text.length() / CHARS_PER_TOKEN);
    }

    /**
     * Extract exact input token count from an API {@link Usage} response.
     */
    public long getExactTokenCount(Usage usage) {
        if (usage == null) {
            return 0;
        }
        return usage.inputTokens();
    }

    /**
     * Estimate the character count for a single message by summing
     * the text content of its content blocks.
     */
    public long estimateMessageChars(Message msg) {
        if (msg instanceof AssistantMessage am) {
            return estimateAssistantChars(am);
        } else if (msg instanceof UserMessage um) {
            return estimateUserChars(um);
        }
        // Other message types (system, progress, etc.) contribute minimally
        return 0;
    }

    private long estimateAssistantChars(AssistantMessage am) {
        if (am.message() == null || am.message().content() == null) {
            return 0;
        }
        long chars = 0;
        for (ContentBlock block : am.message().content()) {
            chars += estimateBlockChars(block);
        }
        return chars;
    }

    private long estimateUserChars(UserMessage um) {
        if (um.message() == null) {
            return 0;
        }
        MessageContent mc = um.message();
        if (mc.isText() && mc.text() != null) {
            return mc.text().length();
        }
        if (mc.blocks() != null) {
            long chars = 0;
            for (ContentBlock block : mc.blocks()) {
                chars += estimateBlockChars(block);
            }
            return chars;
        }
        return 0;
    }

    private long estimateBlockChars(ContentBlock block) {
        return switch (block) {
            case TextBlock tb -> tb.text() != null ? tb.text().length() : 0;
            case ToolUseBlock tu -> {
                long nameLen = tu.name() != null ? tu.name().length() : 0;
                long inputLen = tu.input() != null ? tu.input().toString().length() : 0;
                yield nameLen + inputLen;
            }
            case ToolResultBlock tr -> estimateToolResultChars(tr);
            case ThinkingBlock th -> th.thinking() != null ? th.thinking().length() : 0;
            case RedactedThinkingBlock redacted -> redacted.data() != null ? redacted.data().length() : 0;
            case ImageBlock _ -> 0; // Images are opaque; skip for char estimation
            case DocumentBlock _ -> 0; // Opaque base64 doc (e.g. PDF); skip for char estimation
            case ServerToolUseBlock stu -> {
                long nameLen = stu.name() != null ? stu.name().length() : 0;
                long inputLen = stu.input() != null ? stu.input().toString().length() : 0;
                yield nameLen + inputLen;
            }
            case ServerToolResultBlock str -> str.content() != null ? str.content().toString().length() : 0;
            case WebSearchToolResultBlock wsr -> {
                if (wsr.content() == null) {
                    yield wsr.errorCode() != null ? wsr.errorCode().length() : 0;
                }
                long chars = 0;
                for (WebSearchToolResultBlock.Hit hit : wsr.content()) {
                    chars += (hit.title() != null ? hit.title().length() : 0)
                           + (hit.url() != null ? hit.url().length() : 0);
                }
                yield chars;
            }
            // tool_reference only ever appears inside a tool_result's content array
            // (ToolSearchTool's output) — a bare tool name, negligible size.
            case ToolReferenceBlock ref -> ref.toolName() != null ? ref.toolName().length() : 0;
        };
    }

    private long estimateToolResultChars(ToolResultBlock tr) {
        if (tr.content() == null) {
            return 0;
        }
        long chars = 0;
        for (ContentBlock inner : tr.content()) {
            if (inner instanceof TextBlock tb && tb.text() != null) {
                chars += tb.text().length();
            } else if (inner instanceof ToolReferenceBlock ref && ref.toolName() != null) {
                chars += ref.toolName().length();
            }
        }
        return chars;
    }

    private long roughTokenCountEstimationForMessages(
            List<Message> messages, int charsPerToken) {
        long totalTokens = 0;
        for (Message message : messages) {
            totalTokens += roughTokenCountEstimationForMessage(message, charsPerToken);
        }
        return totalTokens;
    }

    /**
     * Codex's usage anchor already covers every model-generated item from the
     * matching response, even when Java persisted that response as multiple
     * interleaved assistant records. Only genuinely local tail items are added.
     */
    private long codexTailTokenCount(List<Message> messages, String anchoredResponseId) {
        long totalTokens = 0;
        for (Message message : messages) {
            if (anchoredResponseId != null
                    && anchoredResponseId.equals(assistantMessageId(message))) {
                continue;
            }
            totalTokens = saturatedAdd(totalTokens,
                OpenAiResponsesTokenEstimator.estimateMessageTokens(message));
        }
        return totalTokens;
    }

    private long roughTokenCountEstimationForMessage(Message message, int charsPerToken) {
        if (message instanceof AssistantMessage assistant) {
            if (assistant.message() == null || assistant.message().content() == null) return 0;
            return assistant.message().content().stream()
                .mapToLong(block -> roughTokenCountEstimationForBlock(block, charsPerToken))
                .sum();
        }
        if (message instanceof UserMessage user) {
            return roughTokenCountEstimationForUser(user, charsPerToken);
        }
        if (message instanceof AttachmentMessage attachment && attachment.payload() != null) {
            return AttachmentRenderer.render(attachment.payload()).stream()
                .mapToLong(rendered -> roughTokenCountEstimationForUser(
                    rendered, charsPerToken))
                .sum();
        }
        return 0;
    }

    private long roughTokenCountEstimationForUser(
            UserMessage user, int charsPerToken) {
        if (user.message() == null) return 0;
        MessageContent content = user.message();
        if (content.isText()) {
            return roughTokenCountEstimation(content.text(), charsPerToken);
        }
        if (content.blocks() == null) return 0;
        return content.blocks().stream()
            .mapToLong(block -> roughTokenCountEstimationForBlock(block, charsPerToken))
            .sum();
    }

    private long roughTokenCountEstimationForBlock(
            ContentBlock block, int charsPerToken) {
        return switch (block) {
            case TextBlock text -> roughTokenCountEstimation(
                text.text(), charsPerToken);
            case ToolUseBlock toolUse -> roughTokenCountEstimation(
                (toolUse.name() != null ? toolUse.name() : "")
                    + (toolUse.input() != null ? toolUse.input().toString() : "{}"),
                charsPerToken);
            case ToolResultBlock toolResult -> toolResult.content() == null ? 0
                : toolResult.content().stream()
                    .mapToLong(inner -> roughTokenCountEstimationForBlock(
                        inner, charsPerToken))
                    .sum();
            case ThinkingBlock thinking -> roughTokenCountEstimation(
                thinking.thinking(), charsPerToken);
            case RedactedThinkingBlock redacted -> roughTokenCountEstimation(
                redacted.data(), charsPerToken);
            case ImageBlock _ -> 2_000;
            case DocumentBlock _ -> 2_000;
            case ServerToolUseBlock serverToolUse -> roughTokenCountEstimation(
                (serverToolUse.name() != null ? serverToolUse.name() : "")
                    + (serverToolUse.input() != null ? serverToolUse.input().toString() : "{}"),
                charsPerToken);
            case ServerToolResultBlock serverToolResult -> roughTokenCountEstimation(
                serverToolResult.content() != null ? serverToolResult.content().toString() : "",
                charsPerToken);
            case WebSearchToolResultBlock webSearch -> {
                if (webSearch.content() == null) {
                    yield roughTokenCountEstimation(
                        webSearch.errorCode(), charsPerToken);
                }
                long total = 0;
                for (WebSearchToolResultBlock.Hit hit : webSearch.content()) {
                    total += roughTokenCountEstimation(
                        (hit.title() != null ? hit.title() : "")
                            + (hit.url() != null ? hit.url() : ""),
                        charsPerToken);
                }
                yield total;
            }
            case ToolReferenceBlock reference -> roughTokenCountEstimation(
                reference.toolName(), charsPerToken);
        };
    }

    private long roughTokenCountEstimation(String text, int charsPerToken) {
        if (StringUtils.isEmpty(text)) return 0;
        return Math.round((double) text.length() / charsPerToken);
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private static UsageSnapshot usageSnapshot(Message message) {
        if (!(message instanceof AssistantMessage assistant)
                || assistant.message() == null
                || assistant.message().usage() == null
                || isSyntheticAssistant(assistant)) {
            return null;
        }
        return new UsageSnapshot(assistant.message().usage(), assistant.message().model());
    }

    private String assistantMessageId(Message message) {
        if (!(message instanceof AssistantMessage assistant)
                || assistant.message() == null) {
            return null;
        }
        return assistant.message().id();
    }

    private static boolean isSyntheticAssistant(AssistantMessage assistant) {
        if (assistant.isApiErrorMessage()
                || Strings.CS.equals(MessageConstants.SYNTHETIC_MODEL,
                    assistant.message().model())) {
            return true;
        }
        List<ContentBlock> content = assistant.message().content();
        if (content == null || content.isEmpty() || !(content.getFirst() instanceof TextBlock text)) {
            return false;
        }
        return MessageConstants.SYNTHETIC_MESSAGES.contains(text.text());
    }

    private static String modelOrFallback(String model, String fallback) {
        return StringUtils.isNotBlank(model) ? model : fallback;
    }
}
