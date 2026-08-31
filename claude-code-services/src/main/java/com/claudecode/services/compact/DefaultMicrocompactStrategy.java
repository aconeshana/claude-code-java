package com.claudecode.services.compact;

import com.claudecode.core.engine.MessageCompactor;
import com.claudecode.core.message.*;
import com.claudecode.services.cache.PromptCacheBreakDetection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Default {@link MicrocompactStrategy}: the time-based microcompact trigger, Implements the
 * "Private helpers for microcompact" section of {@link CompactService}, plus the compactable-tool
 * allowlist used by the time-based trigger.
 */
final class DefaultMicrocompactStrategy implements MicrocompactStrategy {

    private static final Logger log = LoggerFactory.getLogger(DefaultMicrocompactStrategy.class);


    static final String TIME_BASED_MC_CLEARED_MESSAGE = "[Old tool result content cleared]";

    /** Must match {@code LlmClientAdapter.PROMPT_CACHE_SOURCE} — the tracking
     *  key the cache-break detector files main-loop state under. */
    private static final String PROMPT_CACHE_SOURCE = "repl_main_thread";

    @Override
    public MessageCompactor.MicrocompactResult apply(List<Message> messages) {
        return new MessageCompactor.MicrocompactResult(messages);
    }

    @Override
    public MessageCompactor.MicrocompactResult apply(List<Message> messages, boolean liveMainThread) {
// Time-based trigger runs first and short-circuits.
        if (liveMainThread) {
            MessageCompactor.MicrocompactResult timeBased =
                maybeTimeBasedMicrocompact(messages, TimeBasedMcConfig.load(), Instant.now());
            if (timeBased != null) {
                return timeBased;
            }
        }
        return apply(messages);
    }


    MessageCompactor.MicrocompactResult maybeTimeBasedMicrocompact(
            List<Message> messages, TimeBasedMcConfig config, Instant now) {
        Double gapMinutes = evaluateTimeBasedTrigger(messages, config, now);
        if (gapMinutes == null) {
            return null;
        }

        List<String> compactableIds = new ArrayList<>(collectCompactableToolIds(messages));


        int keepRecent = Math.max(1, config.keepRecent());
        Set<String> keepSet = new HashSet<>(
            compactableIds.subList(Math.max(0, compactableIds.size() - keepRecent), compactableIds.size()));
        Set<String> clearSet = new HashSet<>(compactableIds);
        clearSet.removeAll(keepSet);

        if (clearSet.isEmpty()) {
            return null;
        }

        long charsSaved = 0;
        List<Message> result = new ArrayList<>(messages.size());
        for (Message msg : messages) {
            if (!(msg instanceof UserMessage um) || um.message() == null || um.message().blocks() == null) {
                result.add(msg);
                continue;
            }
            boolean touched = false;
            List<ContentBlock> newBlocks = new ArrayList<>(um.message().blocks().size());
            for (ContentBlock block : um.message().blocks()) {
                if (block instanceof ToolResultBlock tr
                        && clearSet.contains(tr.toolUseId())
                        && !isAlreadyCleared(tr)) {
                    charsSaved += contentChars(tr.content());
                    newBlocks.add(new ToolResultBlock(tr.toolUseId(),
                        List.of(new TextBlock(TIME_BASED_MC_CLEARED_MESSAGE)), tr.isError()));
                    touched = true;
                } else {
                    newBlocks.add(block);
                }
            }
            if (touched) {
                result.add(new UserMessage(
                    um.uuid(), MessageContent.ofBlocks(newBlocks), um.isMeta(), um.isCompactSummary(),
                    um.toolUseResult(), um.origin(), um.parentUuidValue(),
                    um.timestampValue(), um.imagePasteIds(), um.permissionMode(),
                    um.sessionIdValue(), um.sourceToolAssistantUUID(), um.sourceToolUseID(),
                    um.isVirtual(), um.mcpMeta(), um.isVisibleInTranscriptOnly(),
                    um.planContent(), um.summarizeMetadata()));
            } else {
                result.add(msg);
            }
        }


        if (charsSaved == 0) {
            return null;
        }

        log.debug("[TIME-BASED MC] gap {}min > {}min, cleared {} tool results (~{} chars), kept last {}",
            Math.round(gapMinutes), config.gapThresholdMinutes(), clearSet.size(), charsSaved, keepSet.size());

// We just changed prompt content — the next response's cache read will be low, but that's
// us, not a break.
        PromptCacheBreakDetection.notifyCacheDeletion(PROMPT_CACHE_SOURCE, null);

        return new MessageCompactor.MicrocompactResult(result);
    }


    Double evaluateTimeBasedTrigger(List<Message> messages, TimeBasedMcConfig config, Instant now) {
        if (!config.enabled()) {
            return null;
        }
        AssistantMessage lastAssistant = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AssistantMessage am) {
                lastAssistant = am;
                break;
            }
        }
        if (lastAssistant == null || lastAssistant.timestamp().isEmpty()) {
            return null;
        }
        double gapMinutes = (now.toEpochMilli() - lastAssistant.timestamp().get().toEpochMilli()) / 60_000.0;
        if (!Double.isFinite(gapMinutes) || gapMinutes < config.gapThresholdMinutes()) {
            return null;
        }
        return gapMinutes;
    }

    private static boolean isAlreadyCleared(ToolResultBlock tr) {
        return tr.content() != null && tr.content().size() == 1
            && tr.content().getFirst() instanceof TextBlock tb
            && TIME_BASED_MC_CLEARED_MESSAGE.equals(tb.text());
    }

    private static long contentChars(List<ContentBlock> content) {
        if (content == null) return 0;
        long chars = 0;
        for (ContentBlock block : content) {
            if (block instanceof TextBlock tb && tb.text() != null) {
                chars += tb.text().length();
            }
        }
        return chars;
    }

    /**
     * Walk assistant messages and collect tool_use IDs whose tool name is in
     * {@link #COMPACTABLE_TOOLS}.
     */
    Set<String> collectCompactableToolIds(List<Message> messages) {
        Set<String> ids = new LinkedHashSet<>();
        for (Message msg : messages) {
            if (msg instanceof AssistantMessage am
                    && am.message() != null
                    && am.message().content() != null) {
                for (ContentBlock block : am.message().content()) {
                    if (block instanceof ToolUseBlock tu
                            && COMPACTABLE_TOOLS.contains(tu.name())) {
                        ids.add(tu.id());
                    }
                }
            }
        }
        return ids;
    }

}
