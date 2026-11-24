package com.claudecode.api;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.message.Usage;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.function.Consumer;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Cross-protocol reasoning, effort/budget, {@code <think>} tag (with Markdown code-fence
 * tracking), and vendor usage normalization helpers.
 *
 * <p>Replicated from {@code yetone/magpie} ({@code ir.go}, {@code chat.go}).
 */
@Explanation("Cross-protocol reasoning effort, <think> tag, and usage normalization")
final class ReasoningNormalizer {

    private static final String THINK_OPEN = "<think>";
    private static final String THINK_CLOSE = "</think>";

    private ReasoningNormalizer() {}

    record SplitThinkResult(String thinking, String content) {}

    static String effortOfBudget(long budgetTokens) {
        if (budgetTokens <= 0) return "";
        if (budgetTokens <= 4_096) return "low";
        if (budgetTokens <= 12_000) return "medium";
        if (budgetTokens <= 24_000) return "high";
        return "xhigh";
    }

    static int budgetOfEffort(String effort) {
        if (StringUtils.isBlank(effort)) return 0;
        return switch (normalizeEffort(effort)) {
            case "low" -> 4_096;
            case "medium" -> 10_000;
            case "high" -> 24_000;
            case "xhigh", "max" -> 32_000;
            default -> 10_000;
        };
    }

    static String normalizeEffort(String effort) {
        if (StringUtils.isBlank(effort)) return "";
        String norm = effort.trim().toLowerCase(Locale.ROOT);
        return switch (norm) {
            case "minimal", "low" -> "low";
            case "medium" -> "medium";
            case "high" -> "high";
            case "xhigh", "max", "ultra" -> "xhigh";
            case "none", "off", "disabled" -> "";
            default -> norm;
        };
    }

    static String resolveExplicitOrBudgetEffort(CreateMessageRequest request) {
        if (request == null) return "";
        if (request.thinking() != null && Strings.CS.equals("disabled", request.thinking().type())) {
            return "";
        }
        String explicit = request.outputConfig() != null && StringUtils.isNotBlank(request.outputConfig().effort())
            ? request.outputConfig().effort()
            : request.effort();
        if (StringUtils.isNotBlank(explicit)) {
            return explicit;
        }
        if (request.thinking() != null && Strings.CS.equals("enabled", request.thinking().type())
                && request.thinking().budgetTokens() != null
                && request.thinking().budgetTokens() > 0) {
            return effortOfBudget(request.thinking().budgetTokens());
        }
        return "";
    }

    static String resolveEffectiveEffort(CreateMessageRequest request) {
        String effort = resolveExplicitOrBudgetEffort(request);
        if (StringUtils.isNotBlank(effort)) return effort;
        if (request != null && request.thinking() != null
                && Strings.CS.equals("adaptive", request.thinking().type())) {
            return "medium";
        }
        return "";
    }

    /**
     * Extracts reasoning text from a Chat Completions message or streaming delta object,
     * supporting OpenAI/DeepSeek {@code reasoning_content}, OpenRouter {@code reasoning},
     * and OpenRouter structured {@code reasoning_details[]}.
     */
    static String extractReasoningField(JsonNode messageOrDelta) {
        if (messageOrDelta == null || messageOrDelta.isNull()) return "";
        if (messageOrDelta.hasNonNull("reasoning_content") && messageOrDelta.get("reasoning_content").isTextual()) {
            String rc = messageOrDelta.get("reasoning_content").asText();
            if (StringUtils.isNotEmpty(rc)) return rc;
        }
        if (messageOrDelta.hasNonNull("reasoning") && messageOrDelta.get("reasoning").isTextual()) {
            String r = messageOrDelta.get("reasoning").asText();
            if (StringUtils.isNotEmpty(r)) return r;
        }
        JsonNode details = messageOrDelta.get("reasoning_details");
        if (details != null && details.isArray() && !details.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode detail : details) {
                if (detail.hasNonNull("text") && detail.get("text").isTextual()) {
                    sb.append(detail.get("text").asText());
                } else if (detail.hasNonNull("summary") && detail.get("summary").isTextual()) {
                    sb.append(detail.get("summary").asText());
                }
            }
            return sb.toString();
        }
        return "";
    }

    /**
     * Extracts {@code <think>...</think>} blocks from unary assistant content while ignoring
     * tags inside fenced Markdown code blocks ({@code ```} or {@code ~~~}).
     */
    static SplitThinkResult splitThinkTags(String rawContent) {
        if (StringUtils.isEmpty(rawContent) || !Strings.CS.contains(rawContent, THINK_OPEN)) {
            return new SplitThinkResult("", rawContent == null ? "" : rawContent);
        }
        ThinkStreamFilter filter = new ThinkStreamFilter();
        StringBuilder thinking = new StringBuilder();
        StringBuilder content = new StringBuilder();
        filter.feed(rawContent, thinking::append, content::append);
        filter.finish(thinking::append, content::append);
        String visible = content.toString();
        if (!thinking.isEmpty()) {
            visible = stripLeadingBlankAfterThink(visible);
        }
        return new SplitThinkResult(thinking.toString(), visible);
    }

    private static String stripLeadingBlankAfterThink(String text) {
        int i = 0;
        while (i < text.length() && (text.charAt(i) == '\r' || text.charAt(i) == '\n')) {
            i++;
        }
        return i > 0 ? text.substring(i) : text;
    }

    static boolean requiresDeepSeekToolReasoningBackfill(String baseUrl, String model, boolean thinkingEnabled) {
        boolean deepSeekHost = StringUtils.isNotBlank(baseUrl)
            && Strings.CI.contains(baseUrl, "deepseek");
        boolean deepSeekReasoner = StringUtils.isNotBlank(model)
            && (Strings.CI.contains(model, "deepseek-reasoner")
                || Strings.CI.contains(model, "deepseek-r1")
                || (Strings.CI.contains(model, "deepseek") && thinkingEnabled));
        return deepSeekHost || deepSeekReasoner;
    }

    /**
     * Normalizes Chat Completions usage across OpenAI, DeepSeek ({@code prompt_cache_hit_tokens}),
     * Moonshot ({@code cached_tokens}), Mistral ({@code num_cached_tokens}), and Anthropic relays.
     */
    static Usage parseChatUsage(JsonNode usage) {
        if (usage == null || usage.isNull()) return Usage.EMPTY;
        long promptTokens = usage.path("prompt_tokens").asLong(0);
        long completionTokens = usage.path("completion_tokens").asLong(0);
        long cacheRead = usage.path("prompt_tokens_details").path("cached_tokens").asLong(0);
        long cacheWrite = usage.path("prompt_tokens_details").path("cache_write_tokens").asLong(0);
        if (cacheRead == 0) {
            cacheRead = firstPositive(
                usage.path("prompt_cache_hit_tokens").asLong(0),
                usage.path("cached_tokens").asLong(0),
                usage.path("num_cached_tokens").asLong(0));
        }
        boolean whole = true;
        if (cacheRead == 0 && cacheWrite == 0) {
            cacheRead = usage.path("cache_read_input_tokens").asLong(0);
            cacheWrite = usage.path("cache_creation_input_tokens").asLong(0);
            whole = promptTokens >= cacheRead + cacheWrite;
        }
        long input = whole ? Math.max(0L, promptTokens - cacheRead - cacheWrite) : promptTokens;
        long reasoning = firstPositive(
            usage.path("completion_tokens_details").path("reasoning_tokens").asLong(0),
            usage.path("output_tokens_details").path("reasoning_tokens").asLong(0),
            usage.path("reasoning_tokens").asLong(0));
        Long total = usage.hasNonNull("total_tokens") ? usage.path("total_tokens").asLong() : null;
        return new Usage(input, completionTokens, cacheWrite, cacheRead, total,
            reasoning > 0 ? reasoning : null);
    }

    private static long firstPositive(long... values) {
        for (long v : values) {
            if (v > 0) return v;
        }
        return 0L;
    }

    /**
     * Stateful streaming {@code <think>...</think>} splitter that tracks Markdown code fences
     * ({@code ```} and {@code ~~~}) so examples inside code blocks are never misparsed as thoughts.
     */
    static final class ThinkStreamFilter {
        private final StringBuilder pending = new StringBuilder();
        private boolean inThink;
        private boolean stripNewlinesAfterClose;
        private char fenceChar;
        private int fenceLen;
        private boolean atLineStart = true;
        private int leadingSpaces;
        private char runFenceChar;
        private int runFenceLen;

        void feed(String chunk, Consumer<String> onThinking,
                  Consumer<String> onContent) {
            if (StringUtils.isEmpty(chunk)) return;
            pending.append(chunk);
            drain(false, onThinking, onContent);
        }

        void finish(Consumer<String> onThinking,
                    Consumer<String> onContent) {
            drain(true, onThinking, onContent);
        }

        private void drain(boolean eof, Consumer<String> onThinking,
                           Consumer<String> onContent) {
            while (!pending.isEmpty()) {
                if (inThink) {
                    int closeIdx = pending.indexOf(THINK_CLOSE);
                    if (closeIdx >= 0) {
                        if (closeIdx > 0) {
                            onThinking.accept(pending.substring(0, closeIdx));
                        }
                        pending.delete(0, closeIdx + THINK_CLOSE.length());
                        inThink = false;
                        stripNewlinesAfterClose = true;
                        continue;
                    }
                    int hold = eof ? 0 : partialSuffixLength(pending, THINK_CLOSE);
                    int emitLen = pending.length() - hold;
                    if (emitLen > 0) {
                        onThinking.accept(pending.substring(0, emitLen));
                        pending.delete(0, emitLen);
                    }
                    if (!eof) return;
                } else {
                    if (stripNewlinesAfterClose) {
                        int k = 0;
                        while (k < pending.length()
                                && (pending.charAt(k) == '\r' || pending.charAt(k) == '\n')) {
                            k++;
                        }
                        if (k > 0) {
                            pending.delete(0, k);
                            atLineStart = true;
                            leadingSpaces = 0;
                        }
                        if (pending.isEmpty()) return;
                        stripNewlinesAfterClose = false;
                    }
                    int scanLimit = pending.length();
                    int emittedTo = 0;
                    boolean switchedToThink = false;
                    for (int i = 0; i < scanLimit; i++) {
                        char ch = pending.charAt(i);
                        if (fenceLen == 0 && ch == '<') {
                            String rem = pending.substring(i);
                            if (Strings.CS.startsWith(rem, THINK_OPEN)) {
                                if (i > emittedTo) {
                                    onContent.accept(pending.substring(emittedTo, i));
                                }
                                pending.delete(0, i + THINK_OPEN.length());
                                inThink = true;
                                switchedToThink = true;
                                break;
                            }
                            if (!eof && Strings.CS.startsWith(THINK_OPEN, rem)) {
                                if (i > emittedTo) {
                                    onContent.accept(pending.substring(emittedTo, i));
                                }
                                pending.delete(0, i);
                                return;
                            }
                        }
                        advanceFenceState(ch);
                    }
                    if (switchedToThink) {
                        continue;
                    }
                    onContent.accept(pending.toString());
                    pending.setLength(0);
                    return;
                }
            }
        }

        private void advanceFenceState(char ch) {
            if (ch == '\n') {
                finalizeLineFenceOpenOrClose(true);
                atLineStart = true;
                leadingSpaces = 0;
                runFenceChar = 0;
                runFenceLen = 0;
                return;
            }
            if (ch == '\r') return;
            if (atLineStart) {
                if (runFenceLen == 0 && ch == ' ' && leadingSpaces < 3) {
                    leadingSpaces++;
                    return;
                }
                if ((ch == '`' || ch == '~') && (runFenceLen == 0 || ch == runFenceChar)) {
                    runFenceChar = ch;
                    runFenceLen++;
                    return;
                }
                atLineStart = false;
                finalizeLineFenceOpenOrClose(false);
            }
        }

        private void finalizeLineFenceOpenOrClose(boolean endOfLine) {
            if (runFenceLen >= 3) {
                if (fenceLen == 0) {
                    fenceChar = runFenceChar;
                    fenceLen = runFenceLen;
                } else if (endOfLine && runFenceChar == fenceChar && runFenceLen >= fenceLen) {
                    fenceChar = 0;
                    fenceLen = 0;
                }
            }
            runFenceChar = 0;
            runFenceLen = 0;
        }

        private static int partialSuffixLength(StringBuilder buf, String tag) {
            int max = Math.min(buf.length(), tag.length() - 1);
            for (int n = max; n >= 1; n--) {
                String tail = buf.substring(buf.length() - n);
                if (Strings.CS.startsWith(tag, tail)) {
                    return n;
                }
            }
            return 0;
        }
    }
}
