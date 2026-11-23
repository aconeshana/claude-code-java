package com.claudecode.api;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.DocumentBlock;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.RedactedThinkingBlock;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Self-healing tool-call and tool-result turn normalizer across Anthropic, OpenAI Chat,
 * OpenAI Responses, and Google Gemini conversation histories.
 *
 * <p>Replicated from {@code yetone/magpie} ({@code ir.go:joinSplitCalls},
 * {@code chat.go:pairToolMessages}, {@code anthropic.go:pairToolBlocks},
 * and {@code codeassist.go:pairFunctionTurns}).
 */
@Explanation("Self-healing tool call/result pairing, split-call joining, and orphan/interrupted repair")
final class ToolPairingHealer {

    static final String INTERRUPTED_TOOL_NOTE =
        "[The result of this tool call is unavailable: the turn was interrupted.]";

    private ToolPairingHealer() {}

    record HealedTurn(String role, List<ContentBlock> blocks) {}

    /**
     * Normalizes raw {@link CreateMessageRequest.RequestMessage} turns by:
     * <ol>
     *   <li>Joining consecutive {@code assistant} messages whose boundary splits parallel tool calls</li>
     *   <li>Coalescing consecutive user {@code tool_result} turns while preserving sibling user blocks</li>
     *   <li>Ordering {@code ToolResultBlock}s to match the preceding assistant's {@code ToolUseBlock} order</li>
     *   <li>Synthesizing interrupted {@code ToolResultBlock}s for unanswered assistant tool calls</li>
     *   <li>Demoting orphan {@code ToolResultBlock}s (no matching open call) to plain user text</li>
     *   <li>Deduplicating repeated {@code ToolResultBlock}s for the same {@code tool_use_id}</li>
     * </ol>
     */
    static List<HealedTurn> healRequestMessages(List<CreateMessageRequest.RequestMessage> messages) {
        if (messages == null || messages.isEmpty()) return List.of();

        List<HealedTurn> parsed = new ArrayList<>(messages.size());
        for (CreateMessageRequest.RequestMessage msg : messages) {
            if (msg == null) continue;
            String role = Strings.CS.equals("assistant", msg.role()) ? "assistant" : "user";
            parsed.add(new HealedTurn(role, toContentBlocks(msg.content())));
        }

        List<HealedTurn> joined = joinSplitAssistantCalls(parsed);
        List<HealedTurn> coalesced = coalesceConsecutiveUserToolTurns(joined);
        boolean hasAnyAssistantTurn = coalesced.stream()
            .anyMatch(t -> Strings.CS.equals("assistant", t.role()));

        List<HealedTurn> out = new ArrayList<>(coalesced.size() + 2);
        Map<String, ToolUseBlock> pendingCalls = new LinkedHashMap<>();

        for (HealedTurn turn : coalesced) {
            if (Strings.CS.equals("assistant", turn.role())) {
                flushUnansweredCalls(pendingCalls, out);
                List<ContentBlock> assistantBlocks = new ArrayList<>(turn.blocks().size());
                for (ContentBlock block : turn.blocks()) {
                    if (block instanceof ToolUseBlock toolUse) {
                        String id = StringUtils.isBlank(toolUse.id())
                            ? "call_" + UUID.randomUUID().toString().substring(0, 8)
                            : toolUse.id();
                        ToolUseBlock normalized = new ToolUseBlock(id, toolUse.name(),
                            toolUse.input() == null ? JsonUtils.getMapper().createObjectNode() : toolUse.input());
                        pendingCalls.put(id, normalized);
                        assistantBlocks.add(normalized);
                    } else {
                        assistantBlocks.add(block);
                    }
                }
                out.add(new HealedTurn("assistant", List.copyOf(assistantBlocks)));
                continue;
            }

            // User turn: separate tool_results from user's own blocks (text, images, documents).
            List<ToolResultBlock> results = new ArrayList<>();
            List<ContentBlock> siblingUserBlocks = new ArrayList<>();
            for (ContentBlock block : turn.blocks()) {
                if (block instanceof ToolResultBlock tr) {
                    results.add(tr);
                } else {
                    siblingUserBlocks.add(block);
                }
            }

            if (!hasAnyAssistantTurn && !results.isEmpty()) {
                Set<String> seenIds = new LinkedHashSet<>();
                List<ContentBlock> keptResults = new ArrayList<>(results.size());
                for (ToolResultBlock tr : results) {
                    if (StringUtils.isEmpty(tr.toolUseId()) || seenIds.add(tr.toolUseId())) {
                        keptResults.add(tr);
                    }
                }
                out.add(new HealedTurn("tool_results", List.copyOf(keptResults)));
                if (!siblingUserBlocks.isEmpty()) {
                    out.add(new HealedTurn("user", List.copyOf(siblingUserBlocks)));
                }
                continue;
            }

            if (pendingCalls.isEmpty() && results.isEmpty()) {
                if (!siblingUserBlocks.isEmpty()) {
                    out.add(new HealedTurn("user", List.copyOf(siblingUserBlocks)));
                }
                continue;
            }

            // Match results against pendingCalls: keep first answer per callId, demote orphans.
            Map<String, ToolResultBlock> matchedByCallId = new LinkedHashMap<>();
            Set<String> seenAnsweredIds = new LinkedHashSet<>();
            List<ContentBlock> demotedOrphans = new ArrayList<>();

            for (ToolResultBlock tr : results) {
                String callId = matchCallId(tr.toolUseId(), pendingCalls.keySet());
                if (callId != null && pendingCalls.containsKey(callId)) {
                    if (seenAnsweredIds.add(callId)) {
                        matchedByCallId.put(callId, new ToolResultBlock(
                            callId, tr.content(), tr.isError(),
                            tr.includeIsErrorField(), tr.preserveContentBlocks()));
                    }
                    // Duplicate answer for same callId is dropped (first wins).
                } else if (!seenAnsweredIds.contains(tr.toolUseId())) {
                    // Orphan tool result with no matching open assistant tool_use -> demote to user text.
                    String text = OpenAiWireSupport.textContent(tr.content());
                    if (StringUtils.isNotEmpty(text)) {
                        demotedOrphans.add(new TextBlock(text));
                    }
                    if (tr.content() != null) {
                        for (ContentBlock inner : tr.content()) {
                            if (inner instanceof ImageBlock || inner instanceof DocumentBlock) {
                                demotedOrphans.add(inner);
                            }
                        }
                    }
                }
            }

            if (!pendingCalls.isEmpty()) {
                List<ContentBlock> orderedToolResults = new ArrayList<>(pendingCalls.size());
                for (String callId : pendingCalls.keySet()) {
                    ToolResultBlock answer = matchedByCallId.get(callId);
                    if (answer != null) {
                        orderedToolResults.add(answer);
                    } else {
                        orderedToolResults.add(new ToolResultBlock(
                            callId, List.of(new TextBlock(INTERRUPTED_TOOL_NOTE)), true));
                    }
                }
                pendingCalls.clear();
                out.add(new HealedTurn("tool_results", List.copyOf(orderedToolResults)));
            }

            List<ContentBlock> trailingUser = new ArrayList<>(demotedOrphans.size() + siblingUserBlocks.size());
            trailingUser.addAll(demotedOrphans);
            trailingUser.addAll(siblingUserBlocks);
            if (!trailingUser.isEmpty()) {
                out.add(new HealedTurn("user", List.copyOf(trailingUser)));
            }
        }

        // Note: if the conversation ends right after an assistant tool_use turn with no user turn yet,
        // those calls are actively awaiting execution or continuation — only flush if followed by a turn,
        // except when caller explicitly asks to close trailing calls.
        return List.copyOf(out);
    }

    private static String matchCallId(String rawCallId, Set<String> pendingIds) {
        if (StringUtils.isEmpty(rawCallId)) return null;
        if (pendingIds.contains(rawCallId)) return rawCallId;
        GeminiThoughtSignature.UnsignedToolId unsignedResult = GeminiThoughtSignature.unsignToolId(rawCallId);
        for (String pendingId : pendingIds) {
            GeminiThoughtSignature.UnsignedToolId unsignedPending = GeminiThoughtSignature.unsignToolId(pendingId);
            if (Strings.CS.equals(unsignedPending.id(), unsignedResult.id())) {
                return pendingId;
            }
        }
        return null;
    }

    private static void flushUnansweredCalls(
            Map<String, ToolUseBlock> pendingCalls, List<HealedTurn> out) {
        if (pendingCalls.isEmpty()) return;
        List<ContentBlock> synthetic = new ArrayList<>(pendingCalls.size());
        for (String callId : pendingCalls.keySet()) {
            synthetic.add(new ToolResultBlock(
                callId, List.of(new TextBlock(INTERRUPTED_TOOL_NOTE)), true));
        }
        pendingCalls.clear();
        out.add(new HealedTurn("tool_results", List.copyOf(synthetic)));
    }

    /**
     * Joins consecutive assistant messages when the first contains at least one {@link ToolUseBlock}
     * and the next begins with (or consists of) thinking/tool_use blocks without an intervening user turn.
     */
    static List<HealedTurn> joinSplitAssistantCalls(List<HealedTurn> turns) {
        if (turns.size() < 2) return turns;
        List<HealedTurn> out = new ArrayList<>(turns.size());
        for (HealedTurn turn : turns) {
            if (!out.isEmpty()
                    && Strings.CS.equals("assistant", out.getLast().role())
                    && Strings.CS.equals("assistant", turn.role())
                    && hasToolUse(out.getLast().blocks())
                    && hasToolUse(turn.blocks())) {
                HealedTurn prev = out.removeLast();
                List<ContentBlock> merged = new ArrayList<>(prev.blocks().size() + turn.blocks().size());
                merged.addAll(prev.blocks());
                merged.addAll(turn.blocks());
                out.add(new HealedTurn("assistant", List.copyOf(merged)));
            } else {
                out.add(turn);
            }
        }
        return List.copyOf(out);
    }

    private static List<HealedTurn> coalesceConsecutiveUserToolTurns(List<HealedTurn> turns) {
        if (turns.size() < 2) return turns;
        List<HealedTurn> out = new ArrayList<>(turns.size());
        for (HealedTurn turn : turns) {
            if (!out.isEmpty()
                    && Strings.CS.equals("user", out.getLast().role())
                    && Strings.CS.equals("user", turn.role())
                    && (hasToolResult(out.getLast().blocks()) || hasToolResult(turn.blocks()))) {
                HealedTurn prev = out.removeLast();
                List<ContentBlock> merged = new ArrayList<>(prev.blocks().size() + turn.blocks().size());
                merged.addAll(prev.blocks());
                merged.addAll(turn.blocks());
                out.add(new HealedTurn("user", List.copyOf(merged)));
            } else {
                out.add(turn);
            }
        }
        return List.copyOf(out);
    }

    private static boolean hasToolUse(List<ContentBlock> blocks) {
        for (ContentBlock b : blocks) {
            if (b instanceof ToolUseBlock) return true;
        }
        return false;
    }

    private static boolean hasToolResult(List<ContentBlock> blocks) {
        for (ContentBlock b : blocks) {
            if (b instanceof ToolResultBlock) return true;
        }
        return false;
    }

    static List<ContentBlock> toContentBlocks(Object rawContent) {
        if (rawContent == null) return List.of();
        if (rawContent instanceof String s) {
            return List.of(new TextBlock(s));
        }
        if (!(rawContent instanceof List<?> items)) {
            return List.of(new TextBlock(String.valueOf(rawContent)));
        }
        List<ContentBlock> out = new ArrayList<>(items.size());
        for (Object item : items) {
            if (item instanceof ContentBlock cb) {
                out.add(cb);
                continue;
            }
            if (item instanceof String s) {
                out.add(new TextBlock(s));
                continue;
            }
            JsonNode node = JsonUtils.getMapper().valueToTree(item);
            ContentBlock parsed = jsonNodeToBlock(node);
            if (parsed != null) out.add(parsed);
        }
        return List.copyOf(out);
    }

    static ContentBlock jsonNodeToBlock(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) return new TextBlock(node.asText());
        String type = node.path("type").asText("");
        return switch (type) {
            case "text" -> new TextBlock(node.path("text").asText(""));
            case "thinking" -> new ThinkingBlock(
                node.path("thinking").asText(""),
                node.path("signature").isTextual() ? node.path("signature").asText() : null);
            case "redacted_thinking" -> new RedactedThinkingBlock(node.path("data").asText(""));
            case "image" -> new ImageBlock(node.get("source"));
            case "document" -> new DocumentBlock(node.get("source"));
            case "tool_use" -> new ToolUseBlock(
                node.path("id").asText(""),
                node.path("name").asText(""),
                node.has("input") && !node.get("input").isNull()
                    ? node.get("input") : JsonUtils.getMapper().createObjectNode());
            case "tool_result" -> {
                List<ContentBlock> inner = new ArrayList<>();
                JsonNode c = node.get("content");
                if (c != null && c.isTextual()) {
                    inner.add(new TextBlock(c.asText()));
                } else if (c != null && c.isArray()) {
                    for (JsonNode part : c) {
                        ContentBlock child = jsonNodeToBlock(part);
                        if (child != null) inner.add(child);
                    }
                }
                yield new ToolResultBlock(
                    node.path("tool_use_id").asText(""),
                    List.copyOf(inner),
                    node.path("is_error").asBoolean(false));
            }
            default -> null;
        };
    }

    static String sanitizeAnthropicToolId(String id) {
        if (StringUtils.isEmpty(id)) {
            return "toolu_" + UUID.randomUUID().toString().substring(0, 8);
        }
        StringBuilder sb = new StringBuilder(id.length());
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }
}
