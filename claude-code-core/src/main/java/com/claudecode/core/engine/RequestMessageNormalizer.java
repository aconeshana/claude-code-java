package com.claudecode.core.engine;

import com.claudecode.core.message.MessageOrigin;
import java.time.Instant;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.message.ApiErrorMessages;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.DocumentBlock;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.SummarizeMetadata;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.tool.LegacyToolNames;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns every API-request normalization that sits *between* the raw {@link Message} history and the
 * wire-ready turns produced by {@link ApiMessageFormatter}.
 */
public final class RequestMessageNormalizer {

    private RequestMessageNormalizer() {}


    public static final int API_MAX_MEDIA_PER_REQUEST = 100;

    /**
     * Full normalization pipeline used by the main turn loop: strip meta-image/
     * document blocks for too-large errors, map to wire turns, strip
     * {@code tool_reference} when tool search is off, then merge consecutive user
     * turns.
     *
     * @param toolSearchEnabled when false, {@code tool_reference} blocks are
     *        stripped from the request (the tool-search beta is off and the
     *        server rejects the block). The legacy overload does not filter
     *        unavailable references when the caller does not provide a live
     *        tool-name set.
     */
    public static List<StreamingClient.StreamRequest.RequestMessage> normalizeForApi(
            List<Message> messages, boolean includeThinking, boolean toolSearchEnabled) {
        return normalizeForApi(messages, includeThinking, toolSearchEnabled, null);
    }

    /** Model-aware normalization used by production request assembly. */
    public static List<StreamingClient.StreamRequest.RequestMessage> normalizeForApi(
            List<Message> messages, boolean includeThinking, boolean toolSearchEnabled,
            String model) {
        return normalizeForApi(messages, includeThinking, toolSearchEnabled, model,
            null, Map.of());
    }

    /**
     * Production overload with the names currently present in the tool
     * catalogue. A non-null set is significant: an empty set means that no
     * tool references are currently resolvable and therefore all named
     * references are removed. A null set preserves the old pure-test API and
     * means that availability filtering was not requested.
     */
    public static List<StreamingClient.StreamRequest.RequestMessage> normalizeForApi(
            List<Message> messages, boolean includeThinking, boolean toolSearchEnabled,
            String model, Set<String> availableToolNames) {
        return normalizeForApi(messages, includeThinking, toolSearchEnabled, model,
            availableToolNames, Map.of());
    }

    /**
     * Full production entry point. {@code toolNameAliases} is supplied by the
     * live registry so historical assistant/tool-reference names can be mapped
     * to the canonical names that actually appear in the current request.
     */
    public static List<StreamingClient.StreamRequest.RequestMessage> normalizeForApi(
            List<Message> messages, boolean includeThinking, boolean toolSearchEnabled,
            String model, Set<String> availableToolNames,
            Map<String, String> toolNameAliases) {

// before any other processing ( These are
        // display-only envelopes that must never reach the model. Applied first
        // so later reorder/merge passes never observe them.
        messages = messages.stream()
            .filter(m -> !Boolean.TRUE.equals(m.isVirtual()))
            .toList();
        boolean midConversationSystem = model == null
            || MidConversationSystemSupport.isEnabled(model);
        List<Message> reordered = midConversationSystem
            ? reorderAttachmentsForApi(messages)
            : reorderReminderAttachmentsBeforeUsers(messages);
        List<Message> normalized = stripMetaBlocksForTooLargeErrors(reordered);

        // normalization and before reminder smoosh. ApiMessageFormatter is a pure
        // Java mapping boundary, so the retained Message objects are cleaned just
        // before mapping; the four pass order is unchanged.
        normalized = MessageConstants.filterOrphanedThinkingOnlyMessages(normalized);
        normalized = MessageConstants.filterTrailingThinkingFromLastAssistant(normalized);
        normalized = MessageConstants.filterWhitespaceOnlyAssistantMessages(normalized);
        normalized = MessageConstants.ensureNonEmptyAssistantContent(normalized);
        List<StreamingClient.StreamRequest.RequestMessage> wire =
            ApiMessageFormatter.toRequestMessages(normalized, includeThinking,
                midConversationSystem);
        wire = normalizeAssistantToolUses(wire, toolSearchEnabled, toolNameAliases);
        if (!toolSearchEnabled) {
            wire = stripToolReferences(wire);
        } else if (availableToolNames != null) {
            wire = stripUnavailableToolReferences(wire, availableToolNames,
                toolNameAliases);
        }
        wire = mergeNormalizedRequestMessages(
            wire, renderedAssistantMessageIds(normalized, includeThinking));

        // the observed external/API-key request shape. It is driven solely by
        // the real tool-search state; no synthetic tengu_* switch is introduced.
        if (toolSearchEnabled) {
            wire = relocateToolReferenceSiblings(wire);
        }
        wire = smooshSystemReminderSiblings(wire);
        wire = sanitizeErrorToolResultContent(wire);
        wire = ensureToolResultPairing(wire);

        // rewrite (tool_result pairing, error-result sanitation) and is a no-op
        // below the limit, so it never alters a well-formed request's wire bytes.
        wire = stripExcessMediaItems(wire, API_MAX_MEDIA_PER_REQUEST);
        return wire;
    }


    public static List<Message> reorderAttachmentsForApi(List<Message> messages) {
        List<Message> reordered = new ArrayList<>(messages.size());
        List<AttachmentMessage> pending = new ArrayList<>();
        for (Message message : messages) {
            if (message instanceof AttachmentMessage attachment) {
                pending.add(attachment);
                continue;
            }
            boolean stoppingPoint = message instanceof AssistantMessage
                || isToolResultFirstUser(message);
            if (stoppingPoint && !pending.isEmpty()) {
                reordered.addAll(pending);
                pending.clear();
            }
            reordered.add(message);
        }
        reordered.addAll(pending);
        return reordered;
    }

    /**
     * Claude families that reject interleaved raw {@code role:system} keep attachments as user {@code
     * system-reminder} blocks.
     */
    private static List<Message> reorderReminderAttachmentsBeforeUsers(List<Message> messages) {
        List<Message> reordered = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (!(message instanceof AttachmentMessage)) {
                reordered.add(message);
                continue;
            }
            List<Message> run = new ArrayList<>();
            while (i < messages.size() && messages.get(i) instanceof AttachmentMessage) {
                run.add(messages.get(i));
                i++;
            }
            i--;
            int insertAt = reordered.size();
            if (!reordered.isEmpty()) {
                Message previous = reordered.getLast();
                if (previous instanceof UserMessage && !isToolResultFirstUser(previous)) {
                    insertAt--;
                }
            }
            reordered.addAll(insertAt, run);
        }
        return reordered;
    }

    private static boolean isToolResultFirstUser(Message message) {
        if (!(message instanceof UserMessage user)
                || user.message() == null
                || user.message().blocks() == null
                || user.message().blocks().isEmpty()) {
            return false;
        }
        return user.message().blocks().getFirst()
            instanceof ToolResultBlock;
    }

    // ------------------------------------------------------------------------
    // Gap 2: strip image/document from the meta user message that preceded a
    // too-large synthetic error (message-level, before wire mapping).
    // ------------------------------------------------------------------------


    public static List<Message> stripMetaBlocksForTooLargeErrors(List<Message> messages) {
        Map<String, Set<String>> errorToTypes = ApiErrorMessages.errorToBlockTypes();
        Map<String, Set<String>> targets = new HashMap<>();
        for (int i = 0; i < messages.size(); i++) {
            if (!(messages.get(i) instanceof AssistantMessage am) || !am.isApiErrorMessage()) continue;
            String text = firstText(am);
            if (text == null) continue;
            Set<String> types = errorToTypes.get(text);
            if (types == null) continue;
            for (int j = i - 1; j >= 0; j--) {
                Message cand = messages.get(j);
                if (cand instanceof UserMessage um && um.isMeta()) {
                    targets.merge(um.uuid(), new HashSet<>(types), (a, b) -> {
                        a.addAll(b);
                        return a;
                    });
                    break;
                }
                if (cand instanceof AssistantMessage a2 && a2.isApiErrorMessage()) continue;
                break; // stop at any other assistant or non-meta user message
            }
        }

        List<Message> out = new ArrayList<>(messages.size());
        for (Message msg : messages) {
            Message toAdd = msg;
            if (msg instanceof UserMessage(
                String uuid, MessageContent message, boolean isMeta, boolean isCompactSummary,
                Object toolUseResult, MessageOrigin origin,
                String parentUuidValue, Instant timestampValue,
                List<Integer> imagePasteIds, String permissionMode, String sessionIdValue,
                String sourceToolAssistantUUID, String sourceToolUseID, Boolean isVirtual,
                Map<String, Object> mcpMeta, Boolean isVisibleInTranscriptOnly,
                String planContent, SummarizeMetadata summarizeMetadata
            )) {
                Set<String> typesToStrip = isMeta ? targets.get(uuid) : null;
                if (typesToStrip != null && message != null && message.blocks() != null) {
                    List<ContentBlock> filtered = message.blocks().stream()
                        .filter(b -> !shouldStrip(b, typesToStrip))
                        .toList();
                    if (filtered.isEmpty()) continue; // whole meta message dropped
                    toAdd = new UserMessage(uuid, MessageContent.ofBlocks(filtered),
                        isMeta, isCompactSummary, toolUseResult, origin,
                        parentUuidValue, timestampValue, imagePasteIds,
                        permissionMode, sessionIdValue,
                        sourceToolAssistantUUID, sourceToolUseID,
                        isVirtual, mcpMeta, isVisibleInTranscriptOnly, planContent,
                        summarizeMetadata);
                }
            }
            // `toAdd` is either the original message or the stripped replacement; the
            // instanceof pattern variable `um` is scoped to the `if`, so we thread the
            // (possibly replaced) message through `toAdd`.
            out.add(toAdd);
        }
        return out;
    }

    private static String firstText(AssistantMessage am) {
        if (am.message() == null || am.message().content() == null) return null;
        for (ContentBlock b : am.message().content()) {
            if (b instanceof TextBlock(String text) && text != null) {
                return text;
            }
        }
        return null;
    }

    private static boolean shouldStrip(ContentBlock b, Set<String> types) {
        return (b instanceof ImageBlock && types.contains("image"))
            || (b instanceof DocumentBlock && types.contains("document"));
    }

    // ------------------------------------------------------------------------
    // Gap 1: strip tool_reference blocks (wire-level, after mapping).
    // ------------------------------------------------------------------------


    public static List<StreamingClient.StreamRequest.RequestMessage> stripToolReferences(
            List<StreamingClient.StreamRequest.RequestMessage> messages) {
        List<StreamingClient.StreamRequest.RequestMessage> out = new ArrayList<>();
        for (StreamingClient.StreamRequest.RequestMessage msg : messages) {
            if (!Strings.CS.equals("user", msg.role()) || !(msg.content() instanceof List<?>)) {
                out.add(msg);
                continue;
            }
            List<Map<String, Object>> originalBlocks = asStringKeyedMapList(msg.content());
            if (originalBlocks == null) {
                out.add(msg);
                continue;
            }
            List<Map<String, Object>> blocks = new ArrayList<>();
            for (Map<String, Object> block : originalBlocks) {
                if (hasBlockType(block, "tool_reference")) continue;
                if (hasBlockType(block, "tool_result")) {
                    Object inner = block.get("content");
                    if (inner instanceof List<?>) {
                        List<Map<String, Object>> originalInner = asStringKeyedMapList(inner);
                        if (originalInner == null) {
                            blocks.add(block);
                            continue;
                        }
                        List<Map<String, Object>> filtered = new ArrayList<>();
                        for (Map<String, Object> ib : originalInner) {
                            if (!hasBlockType(ib, "tool_reference")) filtered.add(ib);
                        }

                        // when a tool_result held only tool_reference blocks, refill with a
                        // placeholder text so the wire request never carries an empty tool_result.
                        Map<String, Object> copy = new LinkedHashMap<>(block);
                        if (filtered.isEmpty()) {
                            Map<String, Object> placeholder = new LinkedHashMap<>();
                            placeholder.put("type", "text");
                            placeholder.put("text",
                                "[Tool references removed - tool search not enabled]");
                            copy.put("content", List.of(placeholder));
                        } else {
                            copy.put("content", filtered);
                        }
                        blocks.add(copy);
                        continue;
                    }
                }
                blocks.add(block);
            }
            out.add(new StreamingClient.StreamRequest.RequestMessage("user", blocks));
        }
        return out;
    }


    public static List<StreamingClient.StreamRequest.RequestMessage>
            stripUnavailableToolReferences(
                List<StreamingClient.StreamRequest.RequestMessage> messages,
                Set<String> availableToolNames,
                Map<String, String> toolNameAliases) {
        Set<String> available = availableToolNames == null ? Set.of() : availableToolNames;
        Map<String, String> aliases = toolNameAliases == null ? Map.of() : toolNameAliases;
        List<StreamingClient.StreamRequest.RequestMessage> out = new ArrayList<>(messages.size());
        for (StreamingClient.StreamRequest.RequestMessage message : messages) {
            if (!Strings.CS.equals("user", message.role())
                    || !(message.content() instanceof List<?>)) {
                out.add(message);
                continue;
            }
            List<Map<String, Object>> blocks = asStringKeyedMapList(message.content());
            if (blocks == null) {
                out.add(message);
                continue;
            }
            boolean changed = false;
            List<Map<String, Object>> rewritten = new ArrayList<>(blocks.size());
            for (Map<String, Object> block : blocks) {
                if (!hasBlockType(block, "tool_result")
                        || !(block.get("content") instanceof List<?>)) {
                    rewritten.add(block);
                    continue;
                }
                List<Map<String, Object>> inner = asStringKeyedMapList(block.get("content"));
                if (inner == null) {
                    rewritten.add(block);
                    continue;
                }
                boolean hasStaleReference = inner.stream()
                    .filter(innerBlock -> hasBlockType(innerBlock, "tool_reference"))
                    .map(innerBlock -> innerBlock.get("tool_name"))
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .map(name -> canonicalToolName(name, aliases))
                    .anyMatch(name -> !available.contains(name));
                if (!hasStaleReference) {
                    rewritten.add(block);
                    continue;
                }

                changed = true;
                List<Map<String, Object>> filtered = new ArrayList<>();
                for (Map<String, Object> innerBlock : inner) {
                    if (!hasBlockType(innerBlock, "tool_reference")) {
                        filtered.add(innerBlock);
                        continue;
                    }
                    Object rawName = innerBlock.get("tool_name");
                    if (!(rawName instanceof String name)
                            || available.contains(canonicalToolName(name, aliases))) {
                        filtered.add(innerBlock);
                    }
                }
                Map<String, Object> copy = new LinkedHashMap<>(block);
                if (filtered.isEmpty()) {
                    copy.put("content", List.of(textBlock(
                        "[Tool references removed - tools no longer available]")));
                } else {
                    copy.put("content", filtered);
                }
                rewritten.add(copy);
            }
            out.add(changed
                ? new StreamingClient.StreamRequest.RequestMessage("user", rewritten)
                : message);
        }
        return out;
    }


    private static List<StreamingClient.StreamRequest.RequestMessage>
            normalizeAssistantToolUses(
                List<StreamingClient.StreamRequest.RequestMessage> messages,
                boolean toolSearchEnabled,
                Map<String, String> toolNameAliases) {
        Map<String, String> aliases = toolNameAliases == null ? Map.of() : toolNameAliases;
        List<StreamingClient.StreamRequest.RequestMessage> out = new ArrayList<>(messages.size());
        for (StreamingClient.StreamRequest.RequestMessage message : messages) {
            if (!Strings.CS.equals("assistant", message.role())
                    || !(message.content() instanceof List<?>)) {
                out.add(message);
                continue;
            }
            List<Map<String, Object>> blocks = asStringKeyedMapList(message.content());
            if (blocks == null) {
                out.add(message);
                continue;
            }
            List<Map<String, Object>> rewritten = new ArrayList<>(blocks.size());
            for (Map<String, Object> block : blocks) {
                if (!hasBlockType(block, "tool_use")) {
                    rewritten.add(block);
                    continue;
                }
                String name = block.get("name") instanceof String rawName
                    ? canonicalToolName(rawName, aliases) : null;
                Object normalizedInput = normalizeToolInputForApi(
                    name, block.get("input"));
                Map<String, Object> copy = new LinkedHashMap<>();
                copy.put("type", "tool_use");
                copy.put("id", block.get("id"));
                copy.put("name", name);
                copy.put("input", normalizedInput);
                if (toolSearchEnabled && block.containsKey("caller")) {
                    copy.put("caller", block.get("caller"));
                }
                rewritten.add(copy);
            }
            out.add(new StreamingClient.StreamRequest.RequestMessage(
                "assistant", rewritten));
        }
        return out;
    }


    private static Object normalizeToolInputForApi(String toolName, Object input) {
        if (!(input instanceof JsonNode node) || !node.isObject()) return input;
        ObjectNode copy = node.deepCopy();
        if (Strings.CS.equals("ExitPlanMode", toolName)
                || Strings.CS.equals("ExitPlanModeV2", toolName)) {
            copy.remove(List.of("plan", "planFilePath"));
        } else if (Strings.CS.equals("Edit", toolName) && copy.has("edits")) {
            copy.remove(List.of("old_string", "new_string", "replace_all"));
        } else if (Strings.CS.equals("SendMessage", toolName)
                && copy.has("to") && copy.has("message")) {

            copy.remove(List.of("type", "recipient", "content"));
        }
        return copy;
    }

    /** Canonical name mapping used by both tool references and tool_use blocks. */
    private static String canonicalToolName(String rawName, Map<String, String> aliases) {
        if (rawName == null) return null;
        String mapped = aliases.getOrDefault(rawName, rawName);
        return LegacyToolNames.normalize(mapped);
    }

    private static Map<String, Object> textBlock(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        return block;
    }

    // ------------------------------------------------------------------------
    // Merge consecutive user turns (wire-level).
    // ------------------------------------------------------------------------


    public static List<StreamingClient.StreamRequest.RequestMessage> mergeConsecutiveRequestMessages(
            List<StreamingClient.StreamRequest.RequestMessage> messages) {
        return smooshSystemReminderSiblings(
            mergeNormalizedRequestMessages(messages, List.of()));
    }


    private static List<StreamingClient.StreamRequest.RequestMessage>
            mergeNormalizedRequestMessages(
                List<StreamingClient.StreamRequest.RequestMessage> messages,
                List<String> assistantMessageIds) {
        record IdentifiedRequestMessage(
            StreamingClient.StreamRequest.RequestMessage request, String assistantMessageId) {}

        List<IdentifiedRequestMessage> merged = new ArrayList<>();
        int assistantIndex = 0;
        for (StreamingClient.StreamRequest.RequestMessage msg : messages) {
            String assistantMessageId = null;
            if (Strings.CS.equals("assistant", msg.role())) {
                if (assistantIndex < assistantMessageIds.size()) {
                    assistantMessageId = assistantMessageIds.get(assistantIndex);
                }
                assistantIndex++;
            }

            IdentifiedRequestMessage prev = merged.isEmpty() ? null : merged.getLast();
            if (prev != null
                    && Strings.CS.equals("user", prev.request().role())
                    && Strings.CS.equals("user", msg.role())) {
                List<Map<String, Object>> previousBlocks = asBlockList(prev.request().content());
                List<Map<String, Object>> nextBlocks = asBlockList(msg.content());
                if (previousBlocks == null || nextBlocks == null) {
                    merged.add(new IdentifiedRequestMessage(msg, null));
                    continue;
                }
                merged.set(merged.size() - 1, new IdentifiedRequestMessage(
                    new StreamingClient.StreamRequest.RequestMessage(
                        "user", hoistToolResults(joinTextAtSeam(previousBlocks, nextBlocks))),
                    null));
                continue;
            }

            if (Strings.CS.equals("assistant", msg.role())
                    && !assistantMessageIds.isEmpty()) {
                boolean didMerge = false;
                for (int i = merged.size() - 1; i >= 0; i--) {
                    IdentifiedRequestMessage candidate = merged.get(i);
                    String role = candidate.request().role();
                    if (Strings.CS.equals("user", role)) {
                        if (containsToolResult(candidate.request().content())) {
                            continue;
                        }
                        break;
                    }
                    if (!Strings.CS.equals("assistant", role)) {
                        break;
                    }
                    if (!Objects.equals(candidate.assistantMessageId(), assistantMessageId)) {
                        continue;
                    }

                    List<Map<String, Object>> previousBlocks =
                        asStringKeyedMapList(candidate.request().content());
                    List<Map<String, Object>> nextBlocks = asStringKeyedMapList(msg.content());
                    if (previousBlocks == null || nextBlocks == null) {
                        break;
                    }
                    List<Map<String, Object>> combined = new ArrayList<>(previousBlocks);
                    combined.addAll(nextBlocks);
                    merged.set(i, new IdentifiedRequestMessage(
                        new StreamingClient.StreamRequest.RequestMessage("assistant", combined),
                        candidate.assistantMessageId()));
                    didMerge = true;
                    break;
                }
                if (didMerge) continue;
            }

            merged.add(new IdentifiedRequestMessage(msg, assistantMessageId));
        }

        return merged.stream().map(IdentifiedRequestMessage::request).toList();
    }

    /** Assistant API IDs in exactly the order formatter-emitted assistant turns. */
    private static List<String> renderedAssistantMessageIds(
            List<Message> messages, boolean includeThinking) {
        List<String> ids = new ArrayList<>();
        for (Message message : messages) {
            if (!(message instanceof AssistantMessage assistant)
                    || assistant.isApiErrorMessage()
                    || assistant.message() == null
                    || assistant.message().content() == null) {
                continue;
            }
            boolean renders = assistant.message().content().stream().anyMatch(block ->
                block instanceof TextBlock(String text1) && text1 != null
                    || block instanceof ToolUseBlock
                    || includeThinking && block instanceof ThinkingBlock);
            if (renders) ids.add(assistant.message().id());
        }
        return ids;
    }

    private static boolean containsToolResult(Object content) {
        List<Map<String, Object>> blocks = asStringKeyedMapList(content);
        return blocks != null && blocks.stream().anyMatch(block -> hasBlockType(block, "tool_result"));
    }


    private static List<StreamingClient.StreamRequest.RequestMessage>
            relocateToolReferenceSiblings(
                List<StreamingClient.StreamRequest.RequestMessage> messages) {
        List<StreamingClient.StreamRequest.RequestMessage> result = new ArrayList<>(messages);
        for (int i = 0; i < result.size(); i++) {
            StreamingClient.StreamRequest.RequestMessage source = result.get(i);
            if (!Strings.CS.equals("user", source.role())
                    || !(source.content() instanceof List<?>)) continue;
            List<Map<String, Object>> sourceBlocks = asStringKeyedMapList(source.content());
            if (sourceBlocks == null || !containsToolReference(sourceBlocks)) continue;

            List<Map<String, Object>> textSiblings = sourceBlocks.stream()
                .filter(block -> hasBlockType(block, "text"))
                .map(block -> (Map<String, Object>) new LinkedHashMap<>(block))
                .toList();
            if (textSiblings.isEmpty()) continue;

            int targetIndex = -1;
            for (int j = i + 1; j < result.size(); j++) {
                StreamingClient.StreamRequest.RequestMessage candidate = result.get(j);
                if (!Strings.CS.equals("user", candidate.role())
                        || !(candidate.content() instanceof List<?>)) continue;
                List<Map<String, Object>> candidateBlocks =
                    asStringKeyedMapList(candidate.content());
                if (candidateBlocks == null
                        || candidateBlocks.stream().noneMatch(
                            block -> hasBlockType(block, "tool_result"))
                        || containsToolReference(candidateBlocks)) continue;
                targetIndex = j;
                break;
            }
            if (targetIndex < 0) continue;

            List<Map<String, Object>> sourceKept = sourceBlocks.stream()
                .filter(block -> !hasBlockType(block, "text"))
                .map(block -> (Map<String, Object>) new LinkedHashMap<>(block))
                .toList();
            List<Map<String, Object>> targetBlocks = asStringKeyedMapList(
                result.get(targetIndex).content());
            if (targetBlocks == null) continue;
            List<Map<String, Object>> targetWithSiblings = new ArrayList<>(targetBlocks);
            targetWithSiblings.addAll(textSiblings);
            result.set(i, new StreamingClient.StreamRequest.RequestMessage("user", sourceKept));
            result.set(targetIndex, new StreamingClient.StreamRequest.RequestMessage(
                "user", targetWithSiblings));
        }
        return result;
    }

    private static boolean containsToolReference(List<Map<String, Object>> blocks) {
        return blocks.stream().anyMatch(block ->
            hasBlockType(block, "tool_result")
                && block.get("content") instanceof List<?> inner
                && asStringKeyedMapList(inner) != null
                && asStringKeyedMapList(inner).stream()
                    .anyMatch(innerBlock -> hasBlockType(innerBlock, "tool_reference")));
    }


    private static List<StreamingClient.StreamRequest.RequestMessage>
            sanitizeErrorToolResultContent(
                List<StreamingClient.StreamRequest.RequestMessage> messages) {
        List<StreamingClient.StreamRequest.RequestMessage> out = new ArrayList<>(messages.size());
        for (StreamingClient.StreamRequest.RequestMessage message : messages) {
            if (!Strings.CS.equals("user", message.role())
                    || !(message.content() instanceof List<?>)) {
                out.add(message);
                continue;
            }
            List<Map<String, Object>> blocks = asStringKeyedMapList(message.content());
            if (blocks == null) {
                out.add(message);
                continue;
            }
            boolean changed = false;
            List<Map<String, Object>> rewritten = new ArrayList<>(blocks.size());
            for (Map<String, Object> block : blocks) {
                if (!hasBlockType(block, "tool_result")
                        || !Boolean.TRUE.equals(block.get("is_error"))
                        || !(block.get("content") instanceof List<?>)) {
                    rewritten.add(block);
                    continue;
                }
                List<Map<String, Object>> inner = asStringKeyedMapList(block.get("content"));
                if (inner == null
                        || inner.stream().allMatch(innerBlock -> hasBlockType(innerBlock, "text"))) {
                    rewritten.add(block);
                    continue;
                }
                changed = true;
                List<String> texts = inner.stream()
                    .filter(innerBlock -> hasBlockType(innerBlock, "text"))
                    .map(innerBlock -> innerBlock.get("text"))
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
                Map<String, Object> copy = new LinkedHashMap<>(block);
                if (texts.isEmpty()) {
                    copy.put("content", List.of());
                } else {
                    copy.put("content", List.of(textBlock(String.join("\n\n", texts))));
                }
                rewritten.add(copy);
            }
            out.add(changed
                ? new StreamingClient.StreamRequest.RequestMessage("user", rewritten)
                : message);
        }
        return out;
    }

    private static final Logger LOG = LoggerFactory.getLogger(RequestMessageNormalizer.class);


    static List<StreamingClient.StreamRequest.RequestMessage> ensureToolResultPairing(
            List<StreamingClient.StreamRequest.RequestMessage> messages) {
        List<StreamingClient.StreamRequest.RequestMessage> result = new ArrayList<>();
        Set<String> allSeenToolUseIds = new LinkedHashSet<>();
        boolean[] repaired = {false};

        for (int i = 0; i < messages.size(); i++) {
            StreamingClient.StreamRequest.RequestMessage msg = messages.get(i);

            if (!Strings.CS.equals("assistant", msg.role())) {
                StreamingClient.StreamRequest.RequestMessage lastPushed =
                    result.isEmpty() ? null : result.getLast();
                boolean lastIsAssistant = lastPushed != null
                    && Strings.CS.equals("assistant", lastPushed.role());
                if (!lastIsAssistant) {
                    List<Map<String, Object>> blocks = asStringKeyedMapList(msg.content());
                    if (blocks != null) {
                        List<Map<String, Object>> stripped = blocks.stream()
                            .filter(b -> !hasBlockType(b, "tool_result"))
                            .toList();
                        if (stripped.size() != blocks.size()) {
                            repaired[0] = true;
                            if (!stripped.isEmpty()) {
                                result.add(new StreamingClient.StreamRequest.RequestMessage(
                                    msg.role(), stripped));
                            } else if (result.isEmpty()) {
                                result.add(new StreamingClient.StreamRequest.RequestMessage(
                                    msg.role(), List.of(textBlock(
                                        MessageConstants.ORPHANED_TOOL_RESULT_PLACEHOLDER))));
                            }
                            // else: stripped to empty and result is non-empty — drop

                            continue;
                        }
                    }
                }
                result.add(msg);
                continue;
            }

            List<Map<String, Object>> content = asStringKeyedMapList(msg.content());
            if (content == null) {
                result.add(msg);
                continue;
            }

            Set<String> serverResultIds = new HashSet<>();
            for (Map<String, Object> block : content) {
                if (block.get("tool_use_id") instanceof String id) serverResultIds.add(id);
            }

            Set<String> seenToolUseIds = new LinkedHashSet<>();
            boolean assistantChanged = false;
            List<Map<String, Object>> finalContent = new ArrayList<>();
            for (int idx = 0; idx < content.size(); idx++) {
                Map<String, Object> block = content.get(idx);
                boolean flagged;
                if (hasBlockType(block, "tool_use")) {
                    String id = block.get("id") instanceof String s ? s : null;
                    flagged = id != null && allSeenToolUseIds.contains(id);
                    if (!flagged && id != null) {
                        allSeenToolUseIds.add(id);
                        seenToolUseIds.add(id);
                    }
                } else if (hasBlockType(block, "server_tool_use")
                        || hasBlockType(block, "mcp_tool_use")) {
                    String id = block.get("id") instanceof String s ? s : null;
                    flagged = id == null || !serverResultIds.contains(id);
                } else {
                    flagged = false;
                }

                if (!flagged) {
                    finalContent.add(block);
                    continue;
                }
                assistantChanged = true;
                Map<String, Object> left = idx > 0 ? content.get(idx - 1) : null;
                Map<String, Object> right = idx < content.size() - 1 ? content.get(idx + 1) : null;
                if (isThinkingBlock(left) && isThinkingBlock(right)) {
                    Map<String, Object> replacement = new LinkedHashMap<>();
                    replacement.put("type", "text");
                    replacement.put("text", MessageConstants.TOOL_USE_REMOVED_PLACEHOLDER);
                    replacement.put("citations", List.of());
                    finalContent.add(replacement);
                }

            }

            if (finalContent.isEmpty()) {
                assistantChanged = true;
                finalContent = List.of(textBlock(MessageConstants.TOOL_USE_INTERRUPTED_PLACEHOLDER));
            }
            if (assistantChanged) {
                repaired[0] = true;
            }

            result.add(assistantChanged
                ? new StreamingClient.StreamRequest.RequestMessage(msg.role(), finalContent)
                : msg);

            StreamingClient.StreamRequest.RequestMessage nextMsg =
                i + 1 < messages.size() ? messages.get(i + 1) : null;
            boolean nextIsUser = nextMsg != null && Strings.CS.equals("user", nextMsg.role());
            List<Map<String, Object>> nextBlocks = nextIsUser ? asBlockList(nextMsg.content()) : null;
            nextIsUser = nextIsUser && nextBlocks != null;

            Set<String> existingToolResultIds = new LinkedHashSet<>();
            boolean hasDuplicateToolResults = false;
            if (nextIsUser) {
                for (Map<String, Object> block : nextBlocks) {
                    if (!hasBlockType(block, "tool_result")
                            || !(block.get("tool_use_id") instanceof String id)) {
                        continue;
                    }
                    if (!existingToolResultIds.add(id)) hasDuplicateToolResults = true;
                }
            }

            List<String> missingIds = seenToolUseIds.stream()
                .filter(id -> !existingToolResultIds.contains(id))
                .toList();
            List<String> orphanedIds = existingToolResultIds.stream()
                .filter(id -> !seenToolUseIds.contains(id))
                .toList();
            if (missingIds.isEmpty() && orphanedIds.isEmpty() && !hasDuplicateToolResults) {
                continue;
            }
            repaired[0] = true;

            List<Map<String, Object>> syntheticBlocks = new ArrayList<>();
            for (String id : missingIds) {
                Map<String, Object> sb = new LinkedHashMap<>();
                sb.put("type", "tool_result");
                sb.put("tool_use_id", id);
                sb.put("content", MessageConstants.SYNTHETIC_TOOL_RESULT_PLACEHOLDER);
                sb.put("is_error", true);
                syntheticBlocks.add(sb);
            }

            if (nextIsUser) {
                List<Map<String, Object>> filteredNext;
                if (orphanedIds.isEmpty() && !hasDuplicateToolResults) {
                    filteredNext = nextBlocks;
                } else {
                    Set<String> keptToolResultIds = new HashSet<>();
                    filteredNext = new ArrayList<>();
                    for (Map<String, Object> block : nextBlocks) {
                        if (hasBlockType(block, "tool_result")
                                && block.get("tool_use_id") instanceof String id) {
                            if (orphanedIds.contains(id) || !keptToolResultIds.add(id)) continue;
                        }
                        filteredNext.add(block);
                    }
                }
                List<Map<String, Object>> patchedContent = new ArrayList<>(syntheticBlocks);
                patchedContent.addAll(filteredNext);

                i++;
                if (!patchedContent.isEmpty()) {
                    StreamingClient.StreamRequest.RequestMessage patchedNext =
                        new StreamingClient.StreamRequest.RequestMessage("user", patchedContent);
                    result.add(smooshSystemReminderSiblings(List.of(patchedNext)).getFirst());
                } else {
                    result.add(new StreamingClient.StreamRequest.RequestMessage(
                        "user", List.of(textBlock(MessageConstants.NO_CONTENT_MESSAGE))));
                }
            } else if (!syntheticBlocks.isEmpty()) {
                result.add(new StreamingClient.StreamRequest.RequestMessage(
                    "user", syntheticBlocks));
            }
        }

        if (repaired[0]) {
            LOG.warn("ensureToolResultPairing: repaired tool_use/tool_result pairing "
                + "({} -> {} messages)", messages.size(), result.size());
        }
        return repaired[0] ? result : messages;
    }

    private static boolean isThinkingBlock(Map<String, Object> block) {
        return hasBlockType(block, "thinking") || hasBlockType(block, "redacted_thinking");
    }


    private static List<StreamingClient.StreamRequest.RequestMessage>
            smooshSystemReminderSiblings(
                List<StreamingClient.StreamRequest.RequestMessage> messages) {
        List<StreamingClient.StreamRequest.RequestMessage> out = new ArrayList<>(messages.size());
        for (StreamingClient.StreamRequest.RequestMessage message : messages) {
            if (!Strings.CS.equals("user", message.role()) || !(message.content() instanceof List<?>)) {
                out.add(message);
                continue;
            }

            List<Map<String, Object>> content = asStringKeyedMapList(message.content());
            if (content == null) {
                out.add(message);
                continue;
            }
            if (content.stream().noneMatch(b -> hasBlockType(b, "tool_result"))) {
                out.add(message);
                continue;
            }

            List<Map<String, Object>> reminders = new ArrayList<>();
            List<Map<String, Object>> kept = new ArrayList<>();
            for (Map<String, Object> block : content) {
                if (hasBlockType(block, "text")
                        && block.get("text") instanceof String text
                        && isSmooshablePlanReminder(text)) {
                    reminders.add(block);
                } else {
                    kept.add(block);
                }
            }
            if (reminders.isEmpty()) {
                out.add(message);
                continue;
            }

            int lastToolResult = -1;
            for (int i = kept.size() - 1; i >= 0; i--) {
                if (hasBlockType(kept.get(i), "tool_result")) {
                    lastToolResult = i;
                    break;
                }
            }
            Map<String, Object> smooshed =
                smooshIntoToolResult(kept.get(lastToolResult), reminders);
            if (smooshed == null) {
                out.add(message);
                continue;
            }
            kept.set(lastToolResult, smooshed);
            out.add(new StreamingClient.StreamRequest.RequestMessage("user", kept));
        }
        return out;
    }

    private static boolean isSmooshablePlanReminder(String text) {
        return Strings.CS.startsWith(text, "<system-reminder>\nPlan mode is active.")
            || Strings.CS.startsWith(text, "<system-reminder>\n## Exited Plan Mode");
    }

    /**
     * Returns {@code null} when the tool-reference API constraint or an
     * unsupported wire-content shape forbids folding.
     */
    private static Map<String, Object> smooshIntoToolResult(
            Map<String, Object> toolResult, List<Map<String, Object>> reminders) {
        if (reminders.isEmpty()) return new LinkedHashMap<>(toolResult);
        Object existing = toolResult.get("content");
        List<Map<String, Object>> existingBlocks = List.of();
        if (existing instanceof List<?>) {
            existingBlocks = asStringKeyedMapList(existing);
            if (existingBlocks == null) return null;
            if (existingBlocks.stream().anyMatch(b -> hasBlockType(b, "tool_reference"))) {
                return null;
            }
        } else if (existing != null && !(existing instanceof String)) {
            return null;
        }

        // The API accepts only text blocks in an is_error tool result. The
        // current caller supplies reminder text, but keep this invariant here
        // so the helper remains safe if another normalizer calls it later.
        if (Boolean.TRUE.equals(toolResult.get("is_error"))) {
            reminders = reminders.stream()
                .filter(block -> hasBlockType(block, "text"))
                .toList();
            if (reminders.isEmpty()) return new LinkedHashMap<>(toolResult);
        }

        List<String> reminderTexts = reminders.stream()
            .map(b -> b.get("text"))
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
        if (reminderTexts.isEmpty()) return new LinkedHashMap<>(toolResult);

        Map<String, Object> copy = new LinkedHashMap<>(toolResult);
        if (existing == null || existing instanceof String) {
            List<String> joined = new ArrayList<>();
            if (existing instanceof String text && !text.trim().isEmpty()) {
                joined.add(text.trim());
            }
            joined.addAll(reminderTexts);
            copy.put("content", String.join("\n\n", joined));
            return copy;
        }

        List<Map<String, Object>> merged = new ArrayList<>();
        for (Map<String, Object> block : existingBlocks) {
            appendToolResultContent(merged, block);
        }
        for (String text : reminderTexts) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "text");
            block.put("text", text);
            appendToolResultContent(merged, block);
        }
        copy.put("content", merged);
        return copy;
    }


    private static void appendToolResultContent(
            List<Map<String, Object>> merged, Map<String, Object> block) {
        if (!hasBlockType(block, "text") || !(block.get("text") instanceof String text)) {
            merged.add(new LinkedHashMap<>(block));
            return;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return;
        if (!merged.isEmpty() && hasBlockType(merged.getLast(), "text")) {
            Map<String, Object> previous = new LinkedHashMap<>(merged.getLast());
            previous.put("text", previous.get("text") + "\n\n" + trimmed);
            merged.set(merged.size() - 1, previous);
        } else {
            Map<String, Object> normalized = new LinkedHashMap<>(block);
            normalized.put("text", trimmed);
            merged.add(normalized);
        }
    }


    private static List<Map<String, Object>> asBlockList(Object content) {
        if (content instanceof String s) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "text");
            block.put("text", s);
            List<Map<String, Object>> list = new ArrayList<>();
            list.add(block);
            return list;
        }
        return asStringKeyedMapList(content);
    }


    public static List<StreamingClient.StreamRequest.RequestMessage> stripExcessMediaItems(
            List<StreamingClient.StreamRequest.RequestMessage> messages, int limit) {
        int toRemove = 0;
        for (StreamingClient.StreamRequest.RequestMessage msg : messages) {
            List<Map<String, Object>> blocks = asStringKeyedMapList(msg.content());
            if (blocks == null) continue;
            for (Map<String, Object> block : blocks) {
                if (isMedia(block)) toRemove++;
                if (isToolResult(block) && block.get("content") instanceof List<?>) {
                    List<Map<String, Object>> inner = asStringKeyedMapList(block.get("content"));
                    if (inner != null) {
                        for (Map<String, Object> nested : inner) {
                            if (isMedia(nested)) toRemove++;
                        }
                    }
                }
            }
        }
        toRemove -= limit;
        if (toRemove <= 0) return messages;

        List<StreamingClient.StreamRequest.RequestMessage> out = new ArrayList<>(messages.size());
        for (StreamingClient.StreamRequest.RequestMessage msg : messages) {
            if (toRemove <= 0) {
                out.add(msg);
                continue;
            }
            List<Map<String, Object>> blocks = asStringKeyedMapList(msg.content());
            if (blocks == null) {
                out.add(msg);
                continue;
            }

            final int before = toRemove;

            List<Map<String, Object>> mapped = new ArrayList<>(blocks.size());
            for (Map<String, Object> block : blocks) {
                if (toRemove <= 0 || !isToolResult(block)
                        || !(block.get("content") instanceof List<?>)) {
                    mapped.add(block);
                    continue;
                }
                List<Map<String, Object>> inner = asStringKeyedMapList(block.get("content"));
                if (inner == null) {
                    mapped.add(block);
                    continue;
                }
                List<Map<String, Object>> filtered = new ArrayList<>();
                for (Map<String, Object> nested : inner) {
                    if (toRemove > 0 && isMedia(nested)) {
                        toRemove--;
                        continue;
                    }
                    filtered.add(nested);
                }
                if (filtered.size() == inner.size()) {
                    mapped.add(block);
                } else {
                    Map<String, Object> copy = new LinkedHashMap<>(block);
                    copy.put("content", filtered);
                    mapped.add(copy);
                }
            }

            List<Map<String, Object>> stripped = new ArrayList<>(mapped.size());
            for (Map<String, Object> block : mapped) {
                if (toRemove > 0 && isMedia(block)) {
                    toRemove--;
                    continue;
                }
                stripped.add(block);
            }

            out.add(before == toRemove
                ? msg
                : new StreamingClient.StreamRequest.RequestMessage(msg.role(), stripped));
        }
        return out;
    }

    private static boolean isMedia(Map<String, Object> block) {
        return isType(block, "image") || isType(block, "document");
    }

    private static boolean isToolResult(Map<String, Object> block) {
        return isType(block, "tool_result");
    }

    private static boolean isType(Map<String, Object> block, String type) {
        // type is a compile-time constant, so literal-first equals is NPE-safe
        // even when block.get("type") is absent/non-string.
        return type.equals(block.get("type"));
    }

    private static List<Map<String, Object>> asStringKeyedMapList(Object value) {
        if (!(value instanceof List<?> list)) return null;
        List<Map<String, Object>> blocks = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) return null;
            Map<String, Object> block = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) return null;
                block.put(key, entry.getValue());
            }
            blocks.add(block);
        }
        return blocks;
    }


    private static List<Map<String, Object>> joinTextAtSeam(
            List<Map<String, Object>> a, List<Map<String, Object>> b) {
        List<Map<String, Object>> result = new ArrayList<>(a);
        Map<String, Object> lastA = result.isEmpty() ? null : result.getLast();
        Map<String, Object> firstB = b.isEmpty() ? null : b.getFirst();
        boolean reminderSeam = hasBlockType(lastA, "text")
            && hasBlockType(firstB, "text")
            && lastA.get("text") instanceof String leftText
            && firstB.get("text") instanceof String rightText
            && Strings.CS.startsWith(leftText, "<system-reminder>")
            && Strings.CS.startsWith(rightText, "<system-reminder>");
        boolean peerMessageSeam = reminderSeam
            && firstB.get("text") instanceof String rightText
            && Strings.CS.startsWith(rightText,
                "<system-reminder>\nAnother Claude session sent a message while you were working:");
        if (hasBlockType(lastA, "text") && hasBlockType(firstB, "text")
                && !peerMessageSeam) {
            Map<String, Object> extended = new LinkedHashMap<>(lastA);
            extended.put("text", lastA.get("text") + "\n");
            result.set(result.size() - 1, extended);
        }
        result.addAll(b);
        return result;
    }


    private static List<Map<String, Object>> hoistToolResults(List<Map<String, Object>> content) {
        List<Map<String, Object>> toolResults = new ArrayList<>();
        List<Map<String, Object>> others = new ArrayList<>();
        for (Map<String, Object> block : content) {
            if (hasBlockType(block, "tool_result")) {
                toolResults.add(block);
            } else {
                others.add(block);
            }
        }
        toolResults.addAll(others);
        return toolResults;
    }

    /** Preserve String.equals semantics across the generic JSON map boundary. */
    private static boolean hasBlockType(Map<String, Object> block, String expectedType) {
        return block != null
                && block.get("type") instanceof String actualType
                && Strings.CS.equals(expectedType, actualType);
    }
}
