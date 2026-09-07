package com.claudecode.services.agent;

import com.claudecode.core.annotation.CacheTier;
import com.claudecode.api.CreateMessageRequest;
import com.claudecode.api.LlmClient;
import com.claudecode.core.engine.ApiMessageFormatter;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.services.config.RuntimeSettings;
import com.claudecode.services.model.SideQuery;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Shared recap generation for the away summary and {@code /recap}.
 *
 * <p>Authority: the 2.1.236 bundle's shared away-summary execution path —
 * {@code JXn} (single non-streaming side query over the recent conversation with
 * the fixed recap prompt), {@code Nbm} (assistant text join + trim), {@code edu}
 * (word-boundary cap at 400 chars) — plus the conversation gates {@code et0}
 * (minimum user turns and turns since the last recap) and {@code hQg} (last
 * message already an away summary).
 *
 * <p>236 gates that are server-side analytics or experiment infrastructure
 * (GrowthBook flags, rate-limit probes, cache-age probes, remote recap) are
 * intentionally not reproduced; the turn-count and last-message gates are,
 * because they are observable user-facing behavior.
 *
 * <p>TS coverage:
 * <ul>
 *   <li>{@code src/services/awaySummary.ts} — recap generation over the recent
 *       conversation (236 binary {@code JXn}/{@code FlT}/{@code Hbm}; the
 *       weflow tree predates the 236 shared-execution rework, the binary is
 *       authoritative)</li>
 *   <li>{@code src/hooks/useAwaySummary.ts} — conversation gates
 *       {@code et0}/{@code hQg}/{@code qSs} and the disable-hint suffix</li>
 * </ul>
 */
public class AwaySummaryService {

    private static final Logger log = LoggerFactory.getLogger(AwaySummaryService.class);

    /** 236 {@code FlT}: the fixed user prompt appended after the conversation. */
    static final String RECAP_PROMPT =
        "The user stepped away and is coming back. Recap in under 40 words, "
        + "1-2 plain sentences, no markdown. Lead with the overall goal and "
        + "current task, then the one next action. Skip root-cause narrative, "
        + "fix internals, secondary to-dos, and em-dash tangents.";

    /** 236 {@code Hbm}: recap text is capped to 400 characters. */
    static final int RECAP_CHAR_CAP = 400;

    /** 236 {@code Ze0}: minimum real user messages before a recap is useful. */
    static final int MIN_USER_MESSAGES = 3;

    /** 236 {@code Qe0}: minimum new user messages since the last recap. */
    static final int MIN_NEW_USER_MESSAGES = 2;

    /** Suffix appended to the first few recaps (236 {@code c.current<3}). */
    static final String DISABLE_HINT = " (disable recaps in /config)";

    /** How many recaps carry the disable hint (236 counter limit). */
    static final int DISABLE_HINT_COUNT = 3;

    /** Token budget for the one-shot query; the 400-char cap is post-hoc. */
    static final int RECAP_MAX_TOKENS = 1024;

    private final LlmClient llmClient;

    public AwaySummaryService(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /** Whether the feature is enabled via {@code settings.awaySummaryEnabled}. */
    public boolean isEnabled() {
        return RuntimeSettings.loadAwaySummaryEnabled();
    }

    /**
     * Generates a recap over the given conversation, mirroring 236 {@code JXn}:
     * the conversation is sent as-is with {@link #RECAP_PROMPT} appended as a
     * final user turn; the assistant text is trimmed and capped.
     *
     * @return the recap text, or {@code null} when generation fails or yields
     *         nothing (236 kinds {@code failed} / {@code api-error})
     */
    @CacheTier(CacheTier.Tier.FORKED_PREFIX)
    public String generateAwaySummary(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return null;
        try {
            SideQuery sideQuery = new SideQuery(llmClient);
            List<CreateMessageRequest.RequestMessage> request = new ArrayList<>();
            for (var m : ApiMessageFormatter.toRequestMessages(messages)) {
                request.add(new CreateMessageRequest.RequestMessage(m.role(), m.content()));
            }
            request.add(new CreateMessageRequest.RequestMessage("user", RECAP_PROMPT));
            String text = sideQuery.queryTextOrThrow(new SideQuery.Request()
                .model(SideQuery.resolveSmallFastModel())
                .messages(request)
                .maxTokens(RECAP_MAX_TOKENS)
                .tools(List.of())
                // 236 skipCacheWrite:true — the conversation prefix keeps its
                // cache markers (shared with the main loop), only the appended
                // recap prompt is left uncached.
                .skipCacheWrite(true)
                .querySource("away_summary"));
            if (StringUtils.isBlank(text)) return null;
            return capRecapText(text.strip());
        } catch (Exception e) {
            log.debug("[awaySummary] generation failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Generates (if enabled and conversation gates pass) and publishes the recap
     * via {@code sink}. Mirrors 236 {@code P} minus the experiment-only gates.
     */
    public void maybePublishAwaySummary(List<Message> messages, Consumer<String> sink) {
        if (!isEnabled()) return;
        if (!shouldRecap(messages)) return;
        if (lastMessageIsAwaySummary(messages)) return;
        String text = generateAwaySummary(messages);
        if (text != null) sink.accept(text);
    }

    /**
     * 236 {@code et0}: a recap is useful only once the conversation has at least
     * {@link #MIN_USER_MESSAGES} real user messages, and, after a previous
     * recap, at least {@link #MIN_NEW_USER_MESSAGES} more.
     */
    public boolean shouldRecap(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return false;
        int userCount = 0;
        int lastRecapIndex = -1;
        for (int i = 0; i < messages.size(); i++) {
            Message m = messages.get(i);
            if (isRealUserMessage(m)) userCount++;
            if (isAwaySummary(m)) lastRecapIndex = i;
        }
        if (userCount < MIN_USER_MESSAGES) return false;
        if (lastRecapIndex == -1) return true;
        int newUserCount = 0;
        for (int i = lastRecapIndex + 1; i < messages.size(); i++) {
            if (isRealUserMessage(messages.get(i))) newUserCount++;
        }
        return newUserCount >= MIN_NEW_USER_MESSAGES;
    }

    /**
     * 236 {@code hQg}: the last message is already an away summary — a second
     * recap would render back-to-back.
     */
    public boolean lastMessageIsAwaySummary(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return false;
        return isAwaySummary(messages.getLast());
    }

    private static boolean isAwaySummary(Message m) {
        return m instanceof SystemMessage sys && Strings.CS.equals("away_summary", sys.subtype());
    }

    /** 236 {@code qSs}: a real user message (not meta, not a compact summary). */
    static boolean isRealUserMessage(Message m) {
        return m instanceof UserMessage um && !um.isMeta() && !um.isCompactSummary();
    }

    /**
     * 236 {@code edu}: word-boundary truncation at {@link #RECAP_CHAR_CAP}
     * characters with an ellipsis — prefers cutting at the last word boundary
     * inside the cap when that leaves at least half the budget.
     */
    static String capRecapText(String text) {
        if (text.length() <= RECAP_CHAR_CAP) return text;
        String head = text.substring(0, RECAP_CHAR_CAP - 1);
        int boundary = lastWordBoundary(head);
        String candidate = boundary == -1 ? "" : head.substring(0, boundary).stripTrailing();
        String trimmed = head.stripTrailing();
        return (candidate.length() > RECAP_CHAR_CAP / 2 ? candidate : trimmed) + "…";
    }

    private static int lastWordBoundary(String head) {
        for (int i = head.length() - 1; i > 0; i--) {
            if (Character.isWhitespace(head.charAt(i))) return i;
        }
        return -1;
    }

    /** 236 appends the disable hint to the first {@link #DISABLE_HINT_COUNT} recaps. */
    public static String withDisableHint(String text, int publishedCount) {
        return publishedCount < DISABLE_HINT_COUNT ? text + DISABLE_HINT : text;
    }
}