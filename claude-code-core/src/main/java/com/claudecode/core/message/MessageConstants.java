package com.claudecode.core.message;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;


public final class MessageConstants {

    private MessageConstants() {}

    public static final String INTERRUPT_MESSAGE = "[Request interrupted by user]";

    public static final String INTERRUPT_MESSAGE_FOR_TOOL_USE =
            "[Request interrupted by user for tool use]";

    public static final String CANCEL_MESSAGE =
            "The user doesn't want to take this action right now. " +
            "STOP what you are doing and wait for the user to tell you how to proceed.";

    public static final String REJECT_MESSAGE =
            "The user doesn't want to proceed with this tool use. " +
            "The tool use was rejected (eg. if it was a file edit, the new_string was NOT written to the file). " +
            "STOP what you are doing and wait for the user to tell you how to proceed.";

    public static final String REJECT_MESSAGE_WITH_REASON_PREFIX =
            "The user doesn't want to proceed with this tool use. " +
            "The tool use was rejected (eg. if it was a file edit, the new_string was NOT written to the file). " +
            "To tell you how to proceed, the user said:\n";

    /**
     * Model-facing marker for an ExitPlanMode rejection whose body is the rejected plan.
     */
    public static final String PLAN_REJECTION_PREFIX =
            """
            The agent proposed a plan that was rejected by the user. \
            The user chose to stay in plan mode rather than proceed with implementation.
            Rejected plan:
            """;

    public static final String SUBAGENT_REJECT_MESSAGE =
            "Permission for this tool use was denied. " +
            "The tool use was rejected (eg. if it was a file edit, the new_string was NOT written to the file). " +
            "Try a different approach or report the limitation to complete your task.";

    /**
     * Returned as a tool_result for a concurrency-safe tool whose parallel batch was aborted because a
     * sibling (e.g.
     */
    public static String siblingErrorMessage(String toolName) {
        return "Cancelled: parallel tool call "
                + (toolName == null ? "" : toolName)
                + " errored";
    }











    public static final String SIBLING_ERROR_REASON = "sibling_error";


    public static String abortMessage(String reason) {
        return withMemoryCorrectionHint(REJECT_MESSAGE);
    }

    public static final String SUBAGENT_REJECT_MESSAGE_WITH_REASON_PREFIX =
            "Permission for this tool use was denied. " +
            "The tool use was rejected (eg. if it was a file edit, the new_string was NOT written to the file). " +
            "The user said:\n";

    public static final String DENIAL_WORKAROUND_GUIDANCE =
            "IMPORTANT: You *may* attempt to accomplish this action using other tools that might naturally be used to accomplish this goal, " +
            "e.g. using head instead of cat. But you *should not* attempt to work around this denial in malicious ways, " +
            "e.g. do not use your ability to run tests to execute non-test actions. " +
            "You should only try to work around this restriction in reasonable ways that do not attempt to bypass the intent behind this denial. " +
            "If you believe this capability is essential to complete the user's request, STOP and explain to the user " +
            "what you were trying to do and why you need this permission. Let the user decide how to proceed.";

    public static final String NO_RESPONSE_REQUESTED = "No response requested.";

    public static final String SYNTHETIC_TOOL_RESULT_PLACEHOLDER =
            "[Tool result missing due to internal error]";


    public static final String TOOL_USE_REMOVED_PLACEHOLDER = "[Tool use removed]";


    public static final String TOOL_USE_INTERRUPTED_PLACEHOLDER = "[Tool use interrupted]";


    public static final String ORPHANED_TOOL_RESULT_PLACEHOLDER =
            "[Orphaned tool result removed due to conversation resume]";

    public static final String SYNTHETIC_MODEL = "<synthetic>";

    public static final Set<String> SYNTHETIC_MESSAGES = Set.of(
            INTERRUPT_MESSAGE,
            INTERRUPT_MESSAGE_FOR_TOOL_USE,
            CANCEL_MESSAGE,
            REJECT_MESSAGE,
            NO_RESPONSE_REQUESTED
    );

    private static final String AUTO_MODE_REJECTION_PREFIX =
            "Permission for this action was denied by the Claude Code auto mode classifier. Reason: ";

    // ── Simple string builders ──────────────────────────────────────────────


    public static String autoRejectMessage(String toolName) {
        return "Permission to use " + toolName + " has been denied. " + DENIAL_WORKAROUND_GUIDANCE;
    }


    public static String dontAskRejectMessage(String toolName) {
        return "Permission to use " + toolName + " has been denied because Claude Code is running " +
               "in don't ask mode. " + DENIAL_WORKAROUND_GUIDANCE;
    }


    public static boolean isClassifierDenial(String content) {
        return Strings.CS.startsWith(content, AUTO_MODE_REJECTION_PREFIX);
    }


    public static String buildYoloRejectionMessage(String reason) {
        return AUTO_MODE_REJECTION_PREFIX + reason + ". " +
               "If you have other tasks that don't depend on this action, continue working on those. " +
               DENIAL_WORKAROUND_GUIDANCE + " " +
               "To allow this type of action in the future, the user can add a Bash permission rule to their settings.";
    }


    public static String buildClassifierUnavailableMessage(String toolName, String classifierModel) {
        return classifierModel + " is temporarily unavailable, so auto mode cannot determine the safety of " +
               toolName + " right now. " +
               "Wait briefly and then try this action again. " +
               "If it keeps failing, continue with other tasks that don't require this action and come back to it later. " +
               "Note: reading files, searching code, and other read-only operations do not require the classifier and can still be used.";
    }

    // ── Message predicates ──────────────────────────────────────────────────


    public static boolean isSyntheticMessage(Message message) {
        if (message instanceof UserMessage um) {
            MessageContent mc = um.message();
            if (mc == null) return false;
            if (mc.text() != null) return SYNTHETIC_MESSAGES.contains(mc.text());
            List<ContentBlock> blocks = mc.blocks();
            if (blocks != null && !blocks.isEmpty() && blocks.getFirst() instanceof TextBlock(
                String text
            )) {
                return SYNTHETIC_MESSAGES.contains(text);
            }
        } else if (message instanceof AssistantMessage am) {
            AssistantContent ac = am.message();
            if (ac == null || ac.content() == null || ac.content().isEmpty()) return false;
            if (ac.content().getFirst() instanceof TextBlock(String text)) {
                return SYNTHETIC_MESSAGES.contains(text);
            }
        }
        return false;
    }


    public static AssistantMessage getLastAssistantMessage(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AssistantMessage am) {
                return am;
            }
        }
        return null;
    }


    public static boolean hasToolCallsInLastAssistantTurn(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AssistantMessage am) {
                AssistantContent ac = am.message();
                if (ac != null && ac.content() != null) {
                    return ac.content().stream().anyMatch(ToolUseBlock.class::isInstance);
                }
            }
        }
        return false;
    }

    // ── Short ID derivation ─────────────────────────────────────────────────

    /**
     * Derives a short stable 6-char base36 ID from a UUID.
     */
    public static String deriveShortMessageId(String uuid) {
        String hex = uuid.replace("-", "");
        if (hex.length() > 10) hex = hex.substring(0, 10);
        long value = Long.parseUnsignedLong(hex, 16);
        String base36 = Long.toString(value, 36);
        return base36.length() > 6 ? base36.substring(0, 6) : base36;
    }


    public static String withMemoryCorrectionHint(String message) {
        return message;
    }

// ── From  ─────────────────────────────────────


    public static final String NO_CONTENT_MESSAGE = "(no content)";

    // ── Type guard predicates ───────────────────────────────────────────────


    public static boolean isToolUseRequestMessage(Message message) {
        if (!(message instanceof AssistantMessage am)) return false;
        AssistantContent ac = am.message();
        if (ac == null || ac.content() == null) return false;
        return ac.content().stream().anyMatch(ToolUseBlock.class::isInstance);
    }


    public static boolean isToolUseResultMessage(Message message) {
        if (!(message instanceof UserMessage um)) return false;
        if (um.toolUseResult() != null) return true;
        MessageContent mc = um.message();
        if (mc == null) return false;
        List<ContentBlock> blocks = mc.blocks();
        return blocks != null && !blocks.isEmpty() && blocks.getFirst() instanceof ToolResultBlock;
    }


    public static boolean isNotEmptyMessage(Message message) {
        String type = message.type();
        if (Strings.CS.equals("progress", type) || Strings.CS.equals("attachment", type) || Strings.CS.equals("system", type)) {
            return true;
        }
        // UserMessage
        if (message instanceof UserMessage um) {
            MessageContent mc = um.message();
            if (mc == null) return false;
            if (mc.text() != null) return !StringUtils.isBlank(mc.text());
            List<ContentBlock> blocks = mc.blocks();
            if (blocks == null || blocks.isEmpty()) return false;
            if (blocks.size() > 1) return true;
            if (!(blocks.getFirst() instanceof TextBlock(String text))) return true;
            return StringUtils.isNotBlank(text)
                    && !NO_CONTENT_MESSAGE.equals(text)
                    && !INTERRUPT_MESSAGE_FOR_TOOL_USE.equals(text);
        }
        // AssistantMessage
        if (message instanceof AssistantMessage am) {
            AssistantContent ac = am.message();
            if (ac == null || ac.content() == null) return false;
            List<ContentBlock> blocks = ac.content();
            if (blocks.isEmpty()) return false;
            if (blocks.size() > 1) return true;
            if (!(blocks.getFirst() instanceof TextBlock(String text))) return true;
            return StringUtils.isNotBlank(text)
                    && !NO_CONTENT_MESSAGE.equals(text)
                    && !INTERRUPT_MESSAGE_FOR_TOOL_USE.equals(text);
        }
        return true;
    }


    public static boolean isCompactBoundaryMessage(Message message) {
        return message instanceof SystemMessage sm && Strings.CS.equals("compact_boundary", sm.subtype());
    }


    public static int findLastCompactBoundaryIndex(List<? extends Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (isCompactBoundaryMessage(messages.get(i))) return i;
        }
        return -1;
    }


    public static <T extends Message> List<T> getMessagesAfterCompactBoundary(List<T> messages) {
        int boundaryIndex = findLastCompactBoundaryIndex(messages);
        return boundaryIndex == -1 ? messages : messages.subList(boundaryIndex, messages.size());
    }


    public static boolean shouldShowUserMessage(Message message, boolean isTranscriptMode) {
        if (!(message instanceof UserMessage um)) return true;
        if (um.isMeta()) return false;
        return !Boolean.TRUE.equals(um.isVisibleInTranscriptOnly()) || isTranscriptMode;
    }


    public static boolean isThinkingMessage(Message message) {
        if (!(message instanceof AssistantMessage am)) return false;
        AssistantContent ac = am.message();
        if (ac == null || ac.content() == null || ac.content().isEmpty()) return false;
        return ac.content().stream().allMatch(ThinkingBlock.class::isInstance);
    }


    public static int countToolCalls(List<Message> messages, String toolName, Integer maxCount) {
        int count = 0;
        for (Message msg : messages) {
            if (!(msg instanceof AssistantMessage am)) continue;
            AssistantContent ac = am.message();
            if (ac == null || ac.content() == null) continue;
            boolean hasUse = ac.content().stream()
                    .anyMatch(b -> b instanceof ToolUseBlock tu && toolName.equals(tu.name()));
            if (hasUse) {
                count++;
                if (maxCount != null && count >= maxCount) return count;
            }
        }
        return count;
    }


    public static boolean hasSuccessfulToolCall(List<Message> messages, String toolName) {
        String mostRecentToolUseId = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (!(msg instanceof AssistantMessage am)) continue;
            AssistantContent ac = am.message();
            if (ac == null || ac.content() == null) continue;
            for (ContentBlock b : ac.content()) {
                if (b instanceof ToolUseBlock tu && toolName.equals(tu.name())) {
                    mostRecentToolUseId = tu.id();
                    break;
                }
            }
            if (mostRecentToolUseId != null) break;
        }
        if (mostRecentToolUseId == null) return false;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (!(msg instanceof UserMessage um)) continue;
            MessageContent mc = um.message();
            if (mc == null) continue;
            List<ContentBlock> blocks = mc.blocks();
            if (blocks == null) continue;
            for (ContentBlock b : blocks) {
                if (b instanceof ToolResultBlock tr && mostRecentToolUseId.equals(tr.toolUseId())) {
                    return !tr.isError();
                }
            }
        }
        return false;
    }

    // ── Text utilities ──────────────────────────────────────────────────────


    public static String wrapInSystemReminder(String content) {
        return "<system-reminder>\n" + content + "\n</system-reminder>";
    }

    private static final Pattern STRIPPED_TAGS_RE = Pattern.compile(
            "<(commit_analysis|context|function_analysis|pr_analysis)>.*?</\\1>\\n?",
            Pattern.DOTALL);


    public static String stripPromptXMLTags(String content) {
        return STRIPPED_TAGS_RE.matcher(content).replaceAll("").trim();
    }


    public static boolean isEmptyMessageText(String text) {
        return StringUtils.isBlank(stripPromptXMLTags(text)) || NO_CONTENT_MESSAGE.equals(text.trim());
    }


    public static String extractTextContent(List<ContentBlock> blocks, String separator) {
        return blocks.stream()
                .filter(TextBlock.class::isInstance)
                .map(b -> ((TextBlock) b).text())
                .reduce((a, b) -> a + separator + b)
                .orElse("");
    }


    public static String getContentText(MessageContent content) {
        if (content == null) return null;
        if (content.text() != null) return content.text();
        List<ContentBlock> blocks = content.blocks();
        if (blocks != null) {
            String joined = extractTextContent(blocks, "\n").trim();
            return joined.isEmpty() ? null : joined;
        }
        return null;
    }


    public static String getAssistantMessageText(Message message) {
        if (!(message instanceof AssistantMessage am)) return null;
        AssistantContent ac = am.message();
        if (ac == null || ac.content() == null) return null;
        String joined = ac.content().stream()
                .filter(TextBlock.class::isInstance)
                .map(b -> ((TextBlock) b).text())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("")
                .trim();
        return joined.isEmpty() ? null : joined;
    }


    public static String getUserMessageText(Message message) {
        if (!(message instanceof UserMessage um)) return null;
        return getContentText(um.message());
    }

    // ── Identifier utilities ───────────────────────────────────────────────


    public static String deriveUUID(String parentUuid, int index) {
        String hex = String.format("%012x", index);
        if (parentUuid.length() < 24) return parentUuid;
        return parentUuid.substring(0, 24) + hex;
    }


    public static boolean isSystemLocalCommandMessage(Message message) {
        return message instanceof SystemMessage sm && Strings.CS.equals("local_command", sm.subtype());
    }

    // ── Message list utilities ─────────────────────────────────────────────


    public static List<UserMessage> wrapMessagesInSystemReminder(List<UserMessage> messages) {
        List<UserMessage> result = new ArrayList<>(messages.size());
        for (UserMessage msg : messages) {
            MessageContent mc = msg.message();
            if (mc == null) { result.add(msg); continue; }

            if (mc.isText() && mc.text() != null) {
                result.add(copyUserKeepingMeta(msg,
                        MessageContent.ofText(wrapInSystemReminder(mc.text()))));
            } else if (mc.blocks() != null) {
                List<ContentBlock> wrapped = new ArrayList<>(mc.blocks().size());
                for (ContentBlock block : mc.blocks()) {
                    if (block instanceof TextBlock(String text) && text != null) {
                        wrapped.add(new TextBlock(wrapInSystemReminder(text)));
                    } else {
                        wrapped.add(block);
                    }
                }
                result.add(copyUserKeepingMeta(msg, MessageContent.ofBlocks(wrapped)));
            } else {
                result.add(msg);
            }
        }
        return result;
    }


    public static UserMessage mergeUserMessages(UserMessage a, UserMessage b) {
        List<ContentBlock> aBlocks = toBlockList(a.message());
        List<ContentBlock> bBlocks = toBlockList(b.message());
        List<ContentBlock> merged = new ArrayList<>(aBlocks.size() + bBlocks.size());
        merged.addAll(aBlocks);
        merged.addAll(bBlocks);
        return copyUserKeepingMeta(a, MessageContent.ofBlocks(merged));
    }

    private static List<ContentBlock> toBlockList(MessageContent mc) {
        if (mc == null) return List.of();
        if (mc.isText() && mc.text() != null) return List.of(new TextBlock(mc.text()));
        if (mc.blocks() != null) return mc.blocks();
        return List.of();
    }


    public static List<Message> filterWhitespaceOnlyAssistantMessages(List<Message> messages) {
        boolean hasChanges = false;
        List<Message> filtered = new ArrayList<>(messages.size());
        for (Message message : messages) {
            if (message instanceof AssistantMessage am) {
                AssistantContent ac = am.message();
                if (ac != null && ac.content() != null && !ac.content().isEmpty()
                        && hasOnlyWhitespaceText(ac.content())) {
                    hasChanges = true;
                    continue; // drop whitespace-only assistant message
                }
            }
            filtered.add(message);
        }
        if (!hasChanges) return messages;

        // Merge adjacent UserMessages created by the gap (API requires alternating roles).
        List<Message> merged = new ArrayList<>(filtered.size());
        for (Message message : filtered) {
            if (message instanceof UserMessage um && !merged.isEmpty()
                    && merged.getLast() instanceof UserMessage prev) {
                merged.set(merged.size() - 1, mergeUserMessages(prev, um));
            } else {
                merged.add(message);
            }
        }
        return merged;
    }

    private static boolean hasOnlyWhitespaceText(List<ContentBlock> blocks) {
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock(String text)) {
                if (StringUtils.isNotBlank(text)) return false;
            } else {
                // Non-text block → not whitespace-only
                return false;
            }
        }
        return true;
    }


    public static List<Message> filterTrailingThinkingFromLastAssistant(List<Message> messages) {
        if (messages.isEmpty()) return messages;
        Message last = messages.getLast();
        if (!(last instanceof AssistantMessage assistant)
                || assistant.message() == null
                || assistant.message().content() == null
                || assistant.message().content().isEmpty()) {
            return messages;
        }

        List<ContentBlock> content = assistant.message().content();
        if (!(content.getLast() instanceof ThinkingBlock)) return messages;

        int lastValidIndex = content.size() - 1;
        while (lastValidIndex >= 0 && content.get(lastValidIndex) instanceof ThinkingBlock) {
            lastValidIndex--;
        }

        List<ContentBlock> filtered = lastValidIndex < 0
                ? List.of(new TextBlock(NO_CONTENT_MESSAGE))
                : List.copyOf(content.subList(0, lastValidIndex + 1));
        List<Message> result = new ArrayList<>(messages);
        result.set(result.size() - 1, copyAssistantWithContent(assistant, filtered));
        return result;
    }


    public static List<Message> ensureNonEmptyAssistantContent(List<Message> messages) {
        if (messages.isEmpty()) return messages;
        boolean changed = false;
        List<Message> result = new ArrayList<>(messages);
        for (int i = 0; i < result.size() - 1; i++) {
            Message message = result.get(i);
            if (!(message instanceof AssistantMessage assistant)
                    || assistant.message() == null
                    || assistant.message().content() == null
                    || !assistant.message().content().isEmpty()) {
                continue;
            }
            changed = true;
            result.set(i, copyAssistantWithContent(assistant,
                    List.of(new TextBlock(NO_CONTENT_MESSAGE))));
        }
        return changed ? result : messages;
    }

    private static AssistantMessage copyAssistantWithContent(
            AssistantMessage source, List<ContentBlock> content) {
        AssistantContent old = source.message();
        AssistantContent replacement = new AssistantContent(
                old.id(), content, old.usage(), old.model(), old.stopReason(), old.stopSequence());
        return copyAssistantKeepingMeta(source, replacement);
    }




    private static AssistantMessage copyAssistantKeepingMeta(
            AssistantMessage source, AssistantContent content) {
        return new AssistantMessage(source.uuid(), content, source.isApiErrorMessage(),
                source.parentUuidValue(), source.timestampValue(), source.attributionSkill(),
                source.attributionPlugin(), source.attributionMcpServer(), source.attributionMcpTool(),
                source.apiError(), source.error(),
                source.isVirtual(), source.requestId(), source.advisorModel(), source.isMeta());
    }




    private static UserMessage copyUserKeepingMeta(UserMessage source, MessageContent content) {
        return new UserMessage(source.uuid(), content,
                source.isMeta(), source.isCompactSummary(), source.toolUseResult(), source.origin(),
                source.parentUuidValue(), source.timestampValue(), source.imagePasteIds(),
                source.permissionMode(), source.sessionIdValue(),
                source.sourceToolAssistantUUID(), source.sourceToolUseID(),
                source.isVirtual(), source.mcpMeta(), source.isVisibleInTranscriptOnly(),
                source.planContent(), source.summarizeMetadata());
    }


    public static String getToolUseID(Message message) {
        return switch (message) {
            case AssistantMessage am when am.message() != null
                    && am.message().content() != null
                    && !am.message().content().isEmpty()
                    && am.message().content().getFirst() instanceof ToolUseBlock tb -> tb.id();
            case UserMessage um when um.message() != null
                    && um.message().blocks() != null
                    && !um.message().blocks().isEmpty()
                    && um.message().blocks().getFirst() instanceof ToolResultBlock tr -> tr.toolUseId();
            default -> null;
        };
    }


    public static List<Message> stripSignatureBlocks(List<Message> messages) {
        boolean changed = false;
        List<Message> result = new ArrayList<>(messages.size());
        for (Message msg : messages) {
            if (!(msg instanceof AssistantMessage am) || am.message() == null
                    || am.message().content() == null) {
                result.add(msg);
                continue;
            }
            List<ContentBlock> filtered = am.message().content().stream()
                    .filter(b -> !(b instanceof ThinkingBlock))
                    .toList();
            if (filtered.size() == am.message().content().size()) {
                result.add(msg);
            } else {
                changed = true;
                result.add(copyAssistantKeepingMeta(am,
                        AssistantContent.of(am.message().id(), filtered)));
            }
        }
        return changed ? result : messages;
    }


    public static List<Message> filterOrphanedThinkingOnlyMessages(List<Message> messages) {
        // Pass 1: collect message IDs that have non-thinking content
        Set<String> idsWithNonThinking = new HashSet<>();
        for (Message msg : messages) {
            if (!(msg instanceof AssistantMessage am) || am.message() == null
                    || am.message().content() == null) continue;
            boolean hasNonThinking = am.message().content().stream()
                    .anyMatch(b -> !(b instanceof ThinkingBlock));
            if (hasNonThinking && am.message().id() != null) {
                idsWithNonThinking.add(am.message().id());
            }
        }

        // Pass 2: drop thinking-only messages that have no non-thinking sibling.
        boolean changed = false;
        List<Message> result = new ArrayList<>(messages.size());
        for (Message msg : messages) {
            if (msg instanceof AssistantMessage am && am.message() != null
                    && am.message().content() != null && !am.message().content().isEmpty()
                    && (am.message().id() == null
                        || !idsWithNonThinking.contains(am.message().id()))) {
                boolean onlyThinking = am.message().content().stream()
                        .allMatch(ThinkingBlock.class::isInstance);
                if (onlyThinking) {
                    changed = true;
                    continue; // drop
                }
            }
            result.add(msg);
        }
        return changed ? result : messages;
    }

    /**
     * Strips leading {@code <system-reminder>…</system-reminder>} blocks from text.
     */
    public static String stripSystemReminders(String text) {
        if (text == null) return "";
        final String OPEN = "<system-reminder>";
        final String CLOSE = "</system-reminder>";
        String t = text.stripLeading();
        while (Strings.CS.startsWith(t, OPEN)) {
            int end = t.indexOf(CLOSE);
            if (end < 0) break;
            t = t.substring(end + CLOSE.length()).stripLeading();
        }
        return t;
    }
}
