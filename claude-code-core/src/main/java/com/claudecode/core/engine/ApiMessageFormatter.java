package com.claudecode.core.engine;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.AttachmentPayload;
import com.claudecode.core.message.AttachmentRenderer;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.DocumentBlock;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.RedactedThinkingBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.ServerToolResultBlock;
import com.claudecode.core.message.ServerToolUseBlock;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolReferenceBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.message.WebSearchToolResultBlock;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts core {@link Message} history into {@link StreamingClient.StreamRequest.RequestMessage}
 * turns ready for an Anthropic Messages API request.
 */
public final class ApiMessageFormatter {

    private ApiMessageFormatter() {}

    /**
     * Converts {@code messages} into API request turns without thinking-block
     * replay — for callers outside the main turn loop (e.g. {@code /compact}
     * summarization) where thinking is irrelevant.
     */
    public static List<StreamingClient.StreamRequest.RequestMessage> toRequestMessages(List<Message> messages) {
        return toRequestMessages(messages, false, true);
    }

    /**
     * Converts {@code messages} into API request turns: maps each {@link UserMessage}/{@link
     * AssistantMessage} to a role + content shape; every other message type (system/progress/etc.) is
     * skipped.
     */
    @SuppressWarnings("unchecked")
    public static List<StreamingClient.StreamRequest.RequestMessage> toRequestMessages(
            List<Message> messages, boolean includeThinking) {
        return toRequestMessages(messages, includeThinking, true);
    }


    @SuppressWarnings("unchecked")
    public static List<StreamingClient.StreamRequest.RequestMessage> toRequestMessages(
            List<Message> messages, boolean includeThinking,
            boolean midConversationSystemEnabled) {
        List<StreamingClient.StreamRequest.RequestMessage> requestMessages = new ArrayList<>();

        for (int messageIndex = 0; messageIndex < messages.size(); messageIndex++) {
            Message msg = messages.get(messageIndex);
            switch (msg) {
                case UserMessage um -> appendUserMessage(requestMessages, um);
                case AttachmentMessage am -> {
                    List<AttachmentPayload> contiguous = new ArrayList<>();
                    contiguous.add(am.payload());
                    while (messageIndex + 1 < messages.size()
                            && messages.get(messageIndex + 1) instanceof AttachmentMessage next) {
                        contiguous.add(next.payload());
                        messageIndex++;
                    }
                    if (midConversationSystemEnabled) {
                        String restored = AttachmentRenderer.renderSystemContent(contiguous);
                        if (!StringUtils.isBlank(restored)) {
                            requestMessages.add(new StreamingClient.StreamRequest.RequestMessage(
                                "system", restored));
                        }
                    } else {
                        for (AttachmentPayload payload : contiguous) {
                            for (UserMessage rendered : AttachmentRenderer.render(payload)) {
                                appendUserMessage(requestMessages, rendered);
                            }
                        }
                    }
                }
                case AssistantMessage am -> {
                    // Synthetic API error messages must never reach the API payload

                    if (am.isApiErrorMessage()) continue;
                    if (am.message() == null || am.message().content() == null) continue;

                    // the message contains only one text block. Collapsing that
// case to a string changes the established wire shape and the
                    // prompt-cache bytes after compaction/resume.
                    List<Map<String, Object>> contentArray = new ArrayList<>();
                    for (ContentBlock block : am.message().content()) {
                        if (block instanceof TextBlock tb && tb.text() != null) {
                            Map<String, Object> textMap = new LinkedHashMap<>();
                            textMap.put("type", "text");
                            textMap.put("text", tb.text());
                            contentArray.add(textMap);
                        } else if (block instanceof ThinkingBlock th && includeThinking) {
                            Map<String, Object> thMap = new LinkedHashMap<>();
                            thMap.put("type", "thinking");
                            thMap.put("thinking", th.thinking() != null ? th.thinking() : "");
                            if (StringUtils.isNotEmpty(th.signature())) {
                                thMap.put("signature", th.signature());
                            }
                            contentArray.add(thMap);
                        } else if (block instanceof RedactedThinkingBlock redacted && includeThinking) {
                            Map<String, Object> redactedMap = new LinkedHashMap<>();
                            redactedMap.put("type", "redacted_thinking");
                            redactedMap.put("data", redacted.data());
                            contentArray.add(redactedMap);
                        } else if (block instanceof ToolUseBlock tub) {
                            Map<String, Object> tuMap = new LinkedHashMap<>();
                            tuMap.put("type", "tool_use");
                            tuMap.put("id", tub.id());
                            tuMap.put("name", tub.name());
                            tuMap.put("input", tub.input());
                            if (tub.caller() != null) {
                                tuMap.put("caller", tub.caller());
                            }
                            contentArray.add(tuMap);
                        } else if (block instanceof ServerToolUseBlock serverTool) {
                            Map<String, Object> serverMap = new LinkedHashMap<>();
                            serverMap.put("type", "server_tool_use");
                            serverMap.put("id", serverTool.id());
                            serverMap.put("name", serverTool.name());
                            serverMap.put("input", serverTool.input());
                            contentArray.add(serverMap);
                        } else if (block instanceof WebSearchToolResultBlock searchResult) {
                            Map<String, Object> resultMap = new LinkedHashMap<>();
                            resultMap.put("type", "web_search_tool_result");
                            resultMap.put("tool_use_id", searchResult.toolUseId());
                            if (searchResult.errorCode() != null) {
                                resultMap.put("content", Map.of(
                                    "type", "web_search_tool_result_error",
                                    "error_code", searchResult.errorCode()));
                            } else {
                                List<Map<String, Object>> hits = new ArrayList<>();
                                for (WebSearchToolResultBlock.Hit hit : searchResult.content()) {
                                    Map<String, Object> hitMap = new LinkedHashMap<>();
                                    hitMap.put("type", "web_search_result");
                                    hitMap.put("title", hit.title());
                                    hitMap.put("url", hit.url());
                                    if (hit.encryptedContent() != null) {
                                        hitMap.put("encrypted_content", hit.encryptedContent());
                                    }
                                    if (hit.pageAge() != null) hitMap.put("page_age", hit.pageAge());
                                    hits.add(hitMap);
                                }
                                resultMap.put("content", hits);
                            }
                            contentArray.add(resultMap);
                        } else if (block instanceof ServerToolResultBlock serverResult) {
                            Map<String, Object> resultMap = new LinkedHashMap<>();
                            resultMap.put("type", serverResult.providerType() != null
                                ? serverResult.providerType() : "server_tool_result");
                            resultMap.put("tool_use_id", serverResult.toolUseId());
                            resultMap.put("content", JsonUtils.getMapper().convertValue(
                                serverResult.content(), Object.class));
                            contentArray.add(resultMap);
                        }
                    }
                    if (!contentArray.isEmpty()) {
                        requestMessages.add(new StreamingClient.StreamRequest.RequestMessage(
                            "assistant", contentArray));
                    }
                }
                case SystemMessage sm when MessageConstants.isSystemLocalCommandMessage(sm)
                        && sm.content() != null ->

// only system messages that survive — converted to a user turn so the model can
// reference previous command output in later turns.
                    requestMessages.add(new StreamingClient.StreamRequest.RequestMessage(
                        "user", sm.content()));
                default -> { /* Skip other system, progress, etc. */ }
            }
        }
        return requestMessages;
    }

    /**
     * Maps a {@link UserMessage} (or one rendered from an {@link AttachmentMessage})
     * to a wire {@code user} turn. This is faithful block→wire rendering only;
     * content-stripping normalization (too-large meta image/document strip,
     * tool_reference strip, merge) lives in {@link RequestMessageNormalizer}.
     */
    private static void appendUserMessage(
            List<StreamingClient.StreamRequest.RequestMessage> requestMessages,
            UserMessage um) {
        if (um.message() == null) return;
        if (um.message().isText() && um.message().text() != null) {
            requestMessages.add(new StreamingClient.StreamRequest.RequestMessage(
                "user", um.message().text()));
        } else if (um.message().blocks() != null) {
            boolean hasToolResult = um.message().blocks().stream()
                .anyMatch(ToolResultBlock.class::isInstance);

            if (hasToolResult) {
                List<Map<String, Object>> contentArray = new ArrayList<>();
                for (ContentBlock block : um.message().blocks()) {
                    if (block instanceof ToolResultBlock tr) {
                        Map<String, Object> trMap = new LinkedHashMap<>();
                        trMap.put("type", "tool_result");
                        trMap.put("tool_use_id", tr.toolUseId());
                        if (tr.content() != null && !tr.content().isEmpty()) {
                            // A tool_reference (or any future non-text block) inside the
                            // result must survive as a structured element — flattening to
                            // one joined string (the plain-text fast path below) would
                            // silently drop it. ToolSearchTool's tool_result is the first
// real producer of tool_reference blocks; matches the hasImage
                            // array-vs-string branching a few lines below for UserMessage
                            // content.
                            boolean hasNonText = tr.content().stream()
                                .anyMatch(inner -> !(inner instanceof TextBlock));
                            boolean preserveContentBlocks =
                                tr.preserveContentBlocks()
                                    || isContentBlockArrayToolUseResult(um.toolUseResult());
                            if (hasNonText || preserveContentBlocks) {
                                List<Map<String, Object>> innerArray = new ArrayList<>();
                                for (ContentBlock inner : tr.content()) {
                                    if (inner instanceof TextBlock itb && itb.text() != null) {
                                        Map<String, Object> textMap = new LinkedHashMap<>();
                                        textMap.put("type", "text");
                                        textMap.put("text", itb.text());
                                        innerArray.add(textMap);
                                    } else if (inner instanceof ToolReferenceBlock trb) {
                                        Map<String, Object> refMap = new LinkedHashMap<>();
                                        refMap.put("type", "tool_reference");
                                        refMap.put("tool_name", trb.toolName());
                                        innerArray.add(refMap);
                                    } else if (inner instanceof ImageBlock ib && ib.source() != null) {
// matches the plain-user-message image handling
                                        // (below): the model receives the actual image
                                        // base64, not a [Image #N] text placeholder.
                                        Map<String, Object> imgMap = new LinkedHashMap<>();
                                        imgMap.put("type", "image");
                                        imgMap.put("source", ib.source());
                                        innerArray.add(imgMap);
                                    } else if (inner instanceof DocumentBlock db && db.source() != null) {
                                        Map<String, Object> docMap = new LinkedHashMap<>();
                                        docMap.put("type", "document");
                                        docMap.put("source", db.source());
                                        innerArray.add(docMap);
                                    }
                                }
                                trMap.put("content", innerArray);
                            } else {
                                StringBuilder text = new StringBuilder();
                                for (ContentBlock inner : tr.content()) {
                                    if (inner instanceof TextBlock itb && itb.text() != null) {
                                        text.append(itb.text());
                                    }
                                }
                                trMap.put("content", text.toString());
                            }
                        }
                        if (tr.includeIsErrorField()) {
                            trMap.put("is_error", tr.isError());
                        }
                        contentArray.add(trMap);
                    } else if (block instanceof TextBlock tb && tb.text() != null) {
                        Map<String, Object> textMap = new LinkedHashMap<>();
                        textMap.put("type", "text");
                        textMap.put("text", tb.text());
                        contentArray.add(textMap);
                    }
                }
                if (!contentArray.isEmpty()) {
                    requestMessages.add(new StreamingClient.StreamRequest.RequestMessage(
                        "user", contentArray));
                }
            } else {
                boolean hasMedia = um.message().blocks().stream()
                    .anyMatch(b -> b instanceof ImageBlock || b instanceof DocumentBlock);
                boolean hasMultipleTextBlocks = um.message().blocks().size() > 1;
                if (hasMedia || hasMultipleTextBlocks) {
                    // Mixed text + image/document content must remain an array
// so the API receives the actual base64 media block.
                    List<Map<String, Object>> contentArray = new ArrayList<>();
                    for (ContentBlock block : um.message().blocks()) {
                        if (block instanceof TextBlock tb && tb.text() != null) {
                            Map<String, Object> textMap = new LinkedHashMap<>();
                            textMap.put("type", "text");
                            textMap.put("text", tb.text());
                            contentArray.add(textMap);
                        } else if (block instanceof ImageBlock ib && ib.source() != null) {
                            Map<String, Object> imgMap = new LinkedHashMap<>();
                            imgMap.put("type", "image");
                            imgMap.put("source", ib.source());
                            contentArray.add(imgMap);
                        } else if (block instanceof DocumentBlock db && db.source() != null) {
                            Map<String, Object> docMap = new LinkedHashMap<>();
                            docMap.put("type", "document");
                            docMap.put("source", db.source());
                            contentArray.add(docMap);
                        }
                    }
                    if (!contentArray.isEmpty()) {
                        requestMessages.add(new StreamingClient.StreamRequest.RequestMessage(
                            "user", contentArray));
                    }
                } else {
                    StringBuilder text = new StringBuilder();
                    for (ContentBlock block : um.message().blocks()) {
                        if (block instanceof TextBlock tb && tb.text() != null) {
                            text.append(tb.text());
                        }
                    }
                    if (!text.isEmpty()) {
                        requestMessages.add(new StreamingClient.StreamRequest.RequestMessage(
                            "user", text.toString()));
                    }
                }
            }
        }
    }

    private static boolean isContentBlockArrayToolUseResult(Object payload) {
        JsonNode node = payload instanceof JsonNode json
            ? json : JsonUtils.getMapper().valueToTree(payload);
        if (node == null || !node.isArray() || node.isEmpty()) return false;
        for (JsonNode item : node) {
            if (!item.isObject() || !item.hasNonNull("type")) return false;
        }
        return true;
    }
}
