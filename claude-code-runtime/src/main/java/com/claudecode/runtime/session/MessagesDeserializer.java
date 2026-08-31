package com.claudecode.runtime.session;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.PreservedSegment;
import com.claudecode.core.message.ProgressMessage;
import com.claudecode.core.message.RetractedMessages;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.message.Usage;
import com.claudecode.permissions.PermissionMode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Post-load message pipeline that repairs a persisted session log into an API-valid conversation
 * before it is fed to the model on {@code /resume}.
 */
public final class MessagesDeserializer {


    public static final String NO_RESPONSE_REQUESTED = "No response requested.";


    public static final String CONTINUE_FROM_WHERE_YOU_LEFT_OFF =
        "Continue from where you left off.";

    private MessagesDeserializer() {}

    // ── Public API ─────────────────────────────────────────────────────────

    /** Convenience wrapper — drop the interruption state. */
    public static List<Message> deserialize(List<Message> serialized) {
        return deserializeWithInterrupt(serialized).messages();
    }

    /**
     * Full pipeline. Returns the repaired messages AND a classification of
     * the turn boundary so the caller can auto-resume interrupted turns.
     */
    public static DeserializeResult deserializeWithInterrupt(List<Message> serialized) {
        if (serialized == null || serialized.isEmpty()) {
            return new DeserializeResult(List.of(), new None());
        }
        List<Message> retained = RetractedMessages.filter(serialized);
        List<Message> stripped = stripInvalidPermissionMode(retained);
        List<Message> pruned = prunePreBoundary(stripped);
        List<Message> afterToolUse = filterUnresolvedToolUses(pruned);
        List<Message> afterThinking = filterOrphanedThinkingOnly(afterToolUse);
        List<Message> afterWhitespace = filterWhitespaceOnlyAssistant(afterThinking);

        InternalState internal = detectTurnInterruption(afterWhitespace);
        List<Message> withContinuation;
        TurnInterruptionState state;
        if (internal instanceof InterruptedTurn) {
            UserMessage cont = newUserMessage(CONTINUE_FROM_WHERE_YOU_LEFT_OFF, /* isMeta */ true);
            withContinuation = new ArrayList<>(afterWhitespace.size() + 1);
            withContinuation.addAll(afterWhitespace);
            withContinuation.add(cont);
            state = new InterruptedPrompt(cont);
        } else if (internal instanceof InterruptedPromptInternal(UserMessage message)) {
            withContinuation = new ArrayList<>(afterWhitespace);
            state = new InterruptedPrompt(message);
        } else {
            withContinuation = new ArrayList<>(afterWhitespace);
            state = new None();
        }

        List<Message> withSentinel = injectSentinelAfterTrailingUser(withContinuation);
        return new DeserializeResult(List.copyOf(withSentinel), state);
    }

    // ── Result types ───────────────────────────────────────────────────────


    public record DeserializeResult(List<Message> messages, TurnInterruptionState state) {}


    public sealed interface TurnInterruptionState permits None, InterruptedPrompt {}

    public record None() implements TurnInterruptionState {}

    public record InterruptedPrompt(UserMessage message) implements TurnInterruptionState {}

    // ── Stage: strip invalid permissionMode ────────────────────────────────

    private static List<Message> stripInvalidPermissionMode(List<Message> messages) {
        Set<String> valid = new HashSet<>();
        for (PermissionMode m : PermissionMode.values()) {
            valid.add(m.external());
            valid.add(m.kind().wireValue());
        }
        boolean anyChanged = false;
        List<Message> out = new ArrayList<>(messages.size());
        for (Message m : messages) {
            if (m instanceof UserMessage um && um.permissionMode() != null
                    && !valid.contains(um.permissionMode())) {
                out.add(withPermissionMode(um, null));
                anyChanged = true;
            } else {
                out.add(m);
            }
        }
        return anyChanged ? out : messages;
    }

    // ── Stage: relink preserved segment and prune compacted history ─────────


    private static List<Message> prunePreBoundary(List<Message> messages) {
        int boundaryIdx = -1;
        SystemMessage boundary = null;
        for (int i = 0; i < messages.size(); i++) {
            Message m = messages.get(i);
            if (m instanceof SystemMessage sm && Strings.CS.equals("compact_boundary", sm.subtype())) {
                boundaryIdx = i;
                boundary = sm;
            }
        }
        if (boundaryIdx < 0) {
            return messages;
        }

        PreservedSegment segment = boundary.compactMetadata() == null
            ? null : boundary.compactMetadata().preservedSegment();
        if (segment == null) {
            return new ArrayList<>(messages.subList(boundaryIdx, messages.size()));
        }

        Map<String, Message> byUuid = new LinkedHashMap<>();
        for (Message message : messages) {
            if (message.uuid() != null) byUuid.put(message.uuid(), message);
        }

        List<Message> preservedReverse = new ArrayList<>();
        Set<String> walkSeen = new HashSet<>();
        Message current = byUuid.get(segment.tailUuid());
        boolean reachedHead = false;
        while (current != null && current.uuid() != null && walkSeen.add(current.uuid())) {
            preservedReverse.add(current);
            if (current.uuid().equals(segment.headUuid())) {
                reachedHead = true;
                break;
            }
            current = current.parentUuid().map(byUuid::get).orElse(null);
        }
        if (!reachedHead) {
            return messages;
        }

        Collections.reverse(preservedReverse);
        Set<String> preservedUuids = new HashSet<>();
        for (Message message : preservedReverse) preservedUuids.add(message.uuid());

        List<Message> out = new ArrayList<>(messages.size() - boundaryIdx + preservedReverse.size());
        for (int i = boundaryIdx; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (!preservedUuids.contains(message.uuid())) out.add(message);
        }

        int anchorIdx = -1;
        for (int i = 0; i < out.size(); i++) {
            if (segment.anchorUuid().equals(out.get(i).uuid())) {
                anchorIdx = i;
                break;
            }
        }
        if (anchorIdx < 0) {
            return messages;
        }
        out.addAll(anchorIdx + 1, preservedReverse.stream()
            .map(MessagesDeserializer::stripStaleUsage)
            .toList());
        return out;
    }


    private static Message stripStaleUsage(Message message) {
        if (!(message instanceof AssistantMessage(
            String uuid, AssistantContent content, boolean isApiErrorMessage,
            String parentUuidValue, Instant timestampValue, String attributionSkill,
            String attributionPlugin, String attributionMcpServer, String attributionMcpTool,
            String apiError, String error, Boolean isVirtual, String requestId, String advisorModel,
            Boolean isMeta
        )) || content == null) {
            return message;
        }
      AssistantContent withoutUsage = new AssistantContent(
            content.id(), content.content(), Usage.EMPTY, content.model(),
            content.stopReason(), content.stopSequence());
        return new AssistantMessage(
            uuid, withoutUsage, isApiErrorMessage,
            parentUuidValue, timestampValue,
            attributionSkill, attributionPlugin,
            attributionMcpServer, attributionMcpTool,
            apiError, error, isVirtual,
            requestId, advisorModel, isMeta);
    }

    // ── Stage: filter unresolved tool_use ──────────────────────────────────

    /**
     * Drops assistant messages where <em>every</em> {@code tool_use} block
     * lacks a matching {@code tool_result} in the whole conversation. Messages
     * that mix resolved and unresolved uses are kept — trimming them would
     * break the paired UI dot indicator.
     */
    private static List<Message> filterUnresolvedToolUses(List<Message> messages) {
        Set<String> toolUseIds = new HashSet<>();
        Set<String> toolResultIds = new HashSet<>();
        for (Message m : messages) {
            forEachBlock(m, block -> {
                if (block instanceof ToolUseBlock tub && tub.id() != null) {
                    toolUseIds.add(tub.id());
                } else if (block instanceof ToolResultBlock trb && trb.toolUseId() != null) {
                    toolResultIds.add(trb.toolUseId());
                }
            });
        }
        Set<String> unresolved = new HashSet<>(toolUseIds);
        unresolved.removeAll(toolResultIds);
        if (unresolved.isEmpty()) return messages;

        List<Message> out = new ArrayList<>(messages.size());
        for (Message m : messages) {
            if (!(m instanceof AssistantMessage am)) { out.add(m); continue; }
            List<ContentBlock> content = assistantBlocks(am);
            if (content == null || content.isEmpty()) { out.add(m); continue; }
            List<String> tuIds = new ArrayList<>();
            for (ContentBlock b : content) {
                if (b instanceof ToolUseBlock tub && tub.id() != null) tuIds.add(tub.id());
            }
            if (tuIds.isEmpty()) { out.add(m); continue; }
            boolean allUnresolved = true;
            for (String id : tuIds) if (!unresolved.contains(id)) { allUnresolved = false; break; }
            if (!allUnresolved) out.add(m);
        }
        return out;
    }

    // ── Stage: drop thinking-only messages that have no id sibling ─────────


    private static List<Message> filterOrphanedThinkingOnly(List<Message> messages) {
        Set<String> idsWithNonThinkingContent = new HashSet<>();
        for (Message m : messages) {
            if (!(m instanceof AssistantMessage am)) continue;
            List<ContentBlock> content = assistantBlocks(am);
            if (content == null || content.isEmpty()) continue;
            String id = am.message() == null ? null : am.message().id();
            if (id == null) continue;
            for (ContentBlock b : content) {
                if (!(b instanceof ThinkingBlock)) {
                    idsWithNonThinkingContent.add(id);
                    break;
                }
            }
        }
        List<Message> out = new ArrayList<>(messages.size());
        for (Message m : messages) {
            if (!(m instanceof AssistantMessage am)) { out.add(m); continue; }
            List<ContentBlock> content = assistantBlocks(am);
            if (content == null || content.isEmpty()) { out.add(m); continue; }
            boolean allThinking = true;
            for (ContentBlock b : content) {
                if (!(b instanceof ThinkingBlock)) { allThinking = false; break; }
            }
            if (!allThinking) { out.add(m); continue; }
            String id = am.message() == null ? null : am.message().id();
            if (id != null && idsWithNonThinkingContent.contains(id)) { out.add(m); continue; }
            // Truly orphaned — drop.
        }
        return out;
    }

    // ── Stage: whitespace-only assistant + adjacent-user merge ─────────────

    private static List<Message> filterWhitespaceOnlyAssistant(List<Message> messages) {
        boolean anyDropped = false;
        List<Message> filtered = new ArrayList<>(messages.size());
        for (Message m : messages) {
            if (!(m instanceof AssistantMessage am)) { filtered.add(m); continue; }
            List<ContentBlock> content = assistantBlocks(am);
            if (content == null || content.isEmpty()) { filtered.add(m); continue; }
            if (hasOnlyWhitespaceTextContent(content)) { anyDropped = true; continue; }
            filtered.add(m);
        }
        if (!anyDropped) return messages;

        // Drop assistant leaves adjacent users — merge them (API requires alternating roles).
        List<Message> merged = new ArrayList<>(filtered.size());
        for (Message m : filtered) {
            Message prev = merged.isEmpty() ? null : merged.getLast();
            if (m instanceof UserMessage um && prev instanceof UserMessage pum) {
                merged.set(merged.size() - 1, mergeUserMessages(pum, um));
            } else {
                merged.add(m);
            }
        }
        return merged;
    }

    private static boolean hasOnlyWhitespaceTextContent(List<ContentBlock> content) {
        if (content.isEmpty()) return false;
        for (ContentBlock b : content) {
            if (!(b instanceof TextBlock(String text))) return false;
            if (StringUtils.isNotBlank(text)) return false;
        }
        return true;
    }


    private static UserMessage mergeUserMessages(UserMessage a, UserMessage b) {
        List<ContentBlock> merged = hoistToolResults(
            joinTextAtSeam(normalizeToBlocks(a.message()), normalizeToBlocks(b.message())));
        MessageContent content = MessageContent.ofBlocks(merged);
        return new UserMessage(
            a.uuid(), content,
            a.isMeta() && b.isMeta(),
            a.isCompactSummary() || b.isCompactSummary(),
            a.toolUseResult(), a.origin(),
            a.parentUuidValue(), a.timestampValue(),
            a.imagePasteIds(), a.permissionMode(), a.sessionIdValue(),
            a.sourceToolAssistantUUID(), a.sourceToolUseID(),
            a.isVirtual(), a.mcpMeta(), a.isVisibleInTranscriptOnly());
    }


    private static List<ContentBlock> normalizeToBlocks(MessageContent c) {
        if (c == null) return List.of();
        if (c.text() != null) return List.of(new TextBlock(c.text()));
        if (c.blocks() != null) return c.blocks();
        return List.of();
    }


    private static List<ContentBlock> joinTextAtSeam(List<ContentBlock> a, List<ContentBlock> b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        ContentBlock lastA = a.getLast();
        ContentBlock firstB = b.getFirst();
        List<ContentBlock> out = new ArrayList<>(a.size() + b.size());
        if (lastA instanceof TextBlock(String lastText) && firstB instanceof TextBlock) {
            out.addAll(a.subList(0, a.size() - 1));
            out.add(new TextBlock((lastText != null ? lastText : "") + "\n"));
        } else {
            out.addAll(a);
        }
        out.addAll(b);
        return out;
    }


    private static List<ContentBlock> hoistToolResults(List<ContentBlock> content) {
        List<ContentBlock> toolResults = new ArrayList<>();
        List<ContentBlock> others = new ArrayList<>();
        for (ContentBlock b : content) {
            if (b instanceof ToolResultBlock) toolResults.add(b);
            else others.add(b);
        }
        if (toolResults.isEmpty()) return content;
        List<ContentBlock> out = new ArrayList<>(content.size());
        out.addAll(toolResults);
        out.addAll(others);
        return out;
    }

    // ── Turn-interruption detection ────────────────────────────────────────

    private sealed interface InternalState
        permits InternalNone, InterruptedPromptInternal, InterruptedTurn {}

    private record InternalNone() implements InternalState {}

    private record InterruptedPromptInternal(UserMessage message) implements InternalState {}

    private record InterruptedTurn() implements InternalState {}

    private static InternalState detectTurnInterruption(List<Message> messages) {
        if (messages.isEmpty()) return new InternalNone();
        int lastIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m instanceof SystemMessage) continue;
            if (m instanceof ProgressMessage) continue;
            if (m instanceof AssistantMessage am && am.isApiErrorMessage()) continue;
            lastIdx = i;
            break;
        }
        if (lastIdx < 0) return new InternalNone();
        Message last = messages.get(lastIdx);
        if (last instanceof AssistantMessage) return new InternalNone();
        if (last instanceof UserMessage um) {
            if (um.isMeta() || um.isCompactSummary()) return new InternalNone();
            if (MessageConstants.isToolUseResultMessage(um)) {
                if (isTerminalToolResult(um, messages, lastIdx)) return new InternalNone();
                return new InterruptedTurn();
            }
            return new InterruptedPromptInternal(um);
        }
        if (last instanceof AttachmentMessage) return new InterruptedTurn();
        return new InternalNone();
    }

    private static final Set<String> BRIEF_MODE_TERMINAL_TOOL_NAMES = Set.of(


        // so a tool_result on one of these tools is a legitimate turn end, not an interruption.
        "Send", "SendUserMessage", "SendUserFile"
    );

    private static boolean isTerminalToolResult(UserMessage result, List<Message> messages, int resultIdx) {
        MessageContent c = result.message();
        if (c == null || c.blocks() == null || c.blocks().isEmpty()) return false;
        if (!(c.blocks().getFirst() instanceof ToolResultBlock trb)) return false;
        String toolUseId = trb.toolUseId();
        if (toolUseId == null) return false;
        for (int i = resultIdx - 1; i >= 0; i--) {
            if (!(messages.get(i) instanceof AssistantMessage am)) continue;
            List<ContentBlock> content = assistantBlocks(am);
            if (content == null) continue;
            for (ContentBlock b : content) {
                if (b instanceof ToolUseBlock tub && toolUseId.equals(tub.id())) {
                    return BRIEF_MODE_TERMINAL_TOOL_NAMES.contains(tub.name());
                }
            }
        }
        return false;
    }

    // ── Sentinel injection ────────────────────────────────────────────────

    private static List<Message> injectSentinelAfterTrailingUser(List<Message> messages) {
        int lastRelevantIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m instanceof SystemMessage) continue;
            if (m instanceof ProgressMessage) continue;
            lastRelevantIdx = i;
            break;
        }
        if (lastRelevantIdx < 0) return messages;
        if (!(messages.get(lastRelevantIdx) instanceof UserMessage)) return messages;
        List<Message> out = new ArrayList<>(messages.size() + 1);
        out.addAll(messages);
        out.add(lastRelevantIdx + 1, newAssistantMessage(NO_RESPONSE_REQUESTED));
        return out;
    }

    // ── Shared helpers ────────────────────────────────────────────────────

    private static List<ContentBlock> assistantBlocks(AssistantMessage am) {
        if (am.message() == null) return null;
        return am.message().content();
    }


    private static void forEachBlock(Message m, Consumer<ContentBlock> fn) {
        List<ContentBlock> blocks = null;
        if (m instanceof UserMessage um && um.message() != null) blocks = um.message().blocks();
        else if (m instanceof AssistantMessage am) blocks = assistantBlocks(am);
        if (blocks == null) return;
        for (ContentBlock b : blocks) fn.accept(b);
    }

    private static UserMessage newUserMessage(String text, boolean isMeta) {
        return new UserMessage(
            UUID.randomUUID().toString(),
            MessageContent.ofBlocks(List.of(new TextBlock(text))),
            isMeta,
            /* isCompactSummary */ false,
            /* toolUseResult */ null,
            MessageOrigin.USER,
            /* parentUuid */ null,
            Instant.now(),
            /* imagePasteIds */ null,
            /* permissionMode */ null,
            /* sessionId */ null,
            /* sourceToolAssistantUUID */ null);
    }

    private static AssistantMessage newAssistantMessage(String text) {
        Usage usage = new Usage(
            0, 0, 0, 0,
            Usage.ServerToolUse.ZERO,
            /* serviceTier */ null,
            Usage.CacheCreation.ZERO,
            /* inferenceGeo */ null,
            /* iterations */ null,
            /* speed */ null);
        AssistantContent content = AssistantContent.apiResponse(
            UUID.randomUUID().toString(),
            List.of(new TextBlock(text)),
            usage,
            "<synthetic>",
            "stop_sequence",
            "");
        return new AssistantMessage(
            UUID.randomUUID().toString(),
            content,
            /* isApiErrorMessage */ false,
            /* parentUuid */ null,
            Instant.now());
    }

    private static UserMessage withPermissionMode(UserMessage um, String mode) {
        return new UserMessage(
            um.uuid(), um.message(),
            um.isMeta(), um.isCompactSummary(),
            um.toolUseResult(), um.origin(),
            um.parentUuidValue(), um.timestampValue(),
            um.imagePasteIds(), mode, um.sessionIdValue(),
            um.sourceToolAssistantUUID(), um.sourceToolUseID(),
            um.isVirtual(), um.mcpMeta(), um.isVisibleInTranscriptOnly(),
            um.planContent(), um.summarizeMetadata());
    }
}
