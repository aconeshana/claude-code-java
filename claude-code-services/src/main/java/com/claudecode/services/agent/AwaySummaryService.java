package com.claudecode.services.agent;

import com.claudecode.core.annotation.CacheTier;
import com.claudecode.core.engine.SessionCostState;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.HumanTurns;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.services.config.RuntimeSettings;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Shared recap generation for the away summary and {@code /recap}.
 *
 * <p>Authority: the 2.1.236 bundle's shared away-summary execution path —
 * {@code JXn} (a single forked turn over the main loop's own cache-safe
 * parameters with the fixed recap prompt appended), {@code Nbm} (assistant text
 * join + trim), {@code edu} (word-boundary cap at 400 chars) — plus the
 * conversation gates {@code et0} (minimum user turns and turns since the last
 * recap) and {@code hQg} (last message already an away summary).
 *
 * <p>236 takes its fork parameters in two steps, and so does this port: the
 * saved main-loop request ({@code H5e} ≙
 * {@link QuerySession.Forks#getLastCacheSafeForkRequest()}) is preferred,
 * because reusing it verbatim keeps the prompt-cache prefix intact; only when it
 * is absent — after compaction/clear/resume, or on a sub-agent engine — are the
 * system prompt and tool catalog rebuilt ({@code $lT} ≙
 * {@link QuerySession.Forks#buildCacheSharingRequest(List, String, String)}).
 * Both carry the full tool catalog, which is what makes a conversation
 * containing {@code tool_use} blocks a valid request in the first place.
 *
 * <p>236 installs a deny-all {@code canUseTool} ("Away summary cannot use
 * tools") because it runs the recap as a real forked query. This port reads the
 * fork as a plain text stream with no execution loop, so a tool call cannot run
 * and the deny-all has nothing to intercept — the same reasoning recorded on
 * {@code LlmCompactSummarizer}.
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
 *   <li>{@code src/commands/recap.ts} — the rebuild gate and the
 *       ok/no-turn/failed outcome mapping consumed by {@code /recap}
 *       (236 {@code BlT}/{@code $lT})</li>
 *   <li>{@code src/utils/cacheSafeParams.ts} — preferring the saved main-loop
 *       request over a rebuild (236 {@code H5e})</li>
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

    /** 236 {@code querySource}/{@code forkLabel} for the recap fork. */
    static final String QUERY_SOURCE = "away_summary";

    private final StreamingClient streamingClient;
    private final Supplier<QuerySession> engineSupplier;

    /**
     * @param streamingClient transport used to run the recap fork
     * @param engineSupplier  late-bound because the away-summary service is
     *                        constructed alongside, not after, its engine
     */
    public AwaySummaryService(StreamingClient streamingClient,
                              Supplier<QuerySession> engineSupplier) {
        this.streamingClient = Objects.requireNonNull(streamingClient, "streamingClient");
        this.engineSupplier = Objects.requireNonNull(engineSupplier, "engineSupplier");
    }

    /** The terminal state of a recap attempt — 236 {@code JXn}'s result kinds. */
    public enum Kind {
        /** A recap was produced. */
        OK,
        /** No fork parameters and nothing worth recapping (236 {@code no-turn}). */
        NO_TURN,
        /** Generation could not complete (API error or internal failure). */
        FAILED
    }

    /**
     * @param kind terminal state
     * @param text recap text when {@link Kind#OK}, otherwise {@code null}
     */
    public record RecapResult(Kind kind, String text) {

        static RecapResult ok(String text) {
            return new RecapResult(Kind.OK, text);
        }

        static RecapResult noTurn() {
            return new RecapResult(Kind.NO_TURN, null);
        }

        static RecapResult failed() {
            return new RecapResult(Kind.FAILED, null);
        }
    }

    /** Whether the feature is enabled via {@code settings.awaySummaryEnabled}. */
    public boolean isEnabled() {
        return RuntimeSettings.loadAwaySummaryEnabled();
    }

    /**
     * Generates a recap, mirroring 236 {@code JXn}: fork the main loop's
     * cache-safe parameters with {@link #RECAP_PROMPT} appended as a final user
     * turn, then read that turn as assistant text.
     *
     * <p>{@code messages} is the rebuild input and the {@code no-turn} gate
     * input. It is deliberately <em>not</em> the request body when a saved
     * main-loop request exists: re-deriving the prefix from the live
     * conversation would move the prompt-cache breakpoint and miss the entire
     * prefix on every recap. Anything appended after the last completed
     * assistant turn belongs to an unfinished turn, which 236 strips as well.
     */
    @CacheTier(CacheTier.Tier.FORKED_PREFIX)
    public RecapResult synthesizeRecap(List<Message> messages) {
        QuerySession engine = engineSupplier.get();
        if (engine == null) return RecapResult.noTurn();

        StreamingClient.StreamRequest request;
        try {
            request = buildRecapRequest(engine, messages);
        } catch (RuntimeException failure) {
            log.debug("[awaySummary] fork params rebuild failed: {}", failure.toString());
            return RecapResult.failed();
        }
        if (request == null) return RecapResult.noTurn();

        try {
            long startedAt = System.currentTimeMillis();
            Iterator<StreamingClient.StreamingEvent> stream =
                streamingClient.createStream(request);
            ForkedTextStream.Result result = ForkedTextStream.consume(stream);
            recordCost(request, result, stream, startedAt);
            if (StringUtils.isBlank(result.text())) {
                if (!result.requestedTools().isEmpty()) {
                    log.debug("[awaySummary] recap turn was spent calling tools: {}",
                        String.join(", ", result.requestedTools()));
                }
                return RecapResult.failed();
            }
            return RecapResult.ok(capRecapText(result.text().strip()));
        } catch (Exception failure) {
            log.debug("[awaySummary] generation failed: {}", failure.toString());
            return RecapResult.failed();
        }
    }

    /**
     * Recap text for the focus-driven watcher, or {@code null} when there is
     * nothing to show. The watcher draws no distinction between the
     * {@code no-turn} and {@code failed} kinds.
     */
    public String generateAwaySummary(List<Message> messages) {
        RecapResult result = synthesizeRecap(messages);
        return result.kind() == Kind.OK ? result.text() : null;
    }

    /**
     * 236 {@code JXn}'s two-step parameter lookup. Returns {@code null} when
     * neither step yields a request, which the caller reports as
     * {@code no-turn}.
     */
    private StreamingClient.StreamRequest buildRecapRequest(
            QuerySession engine, List<Message> messages) {
        StreamingClient.StreamRequest saved = engine.forks().getLastCacheSafeForkRequest();
        if (saved != null) return appendRecapPrompt(saved);
        if (!hasRecappableContent(messages)) return null;
        return engine.forks().buildCacheSharingRequest(
            stripUnfinishedTurn(messages), RECAP_PROMPT, QUERY_SOURCE);
    }

    /**
     * Derives the recap fork from the saved main-loop request: prefix verbatim
     * (so the prompt cache still hits), recap prompt as a trailing user turn.
     * The snapshot is a main-loop request — it carries
     * {@code skipCacheWrite=false} and {@code querySource="user"} — so this
     * override of both is load-bearing, not cosmetic.
     */
    private static StreamingClient.StreamRequest appendRecapPrompt(
            StreamingClient.StreamRequest parent) {
        List<StreamingClient.StreamRequest.RequestMessage> messages =
            new ArrayList<>(parent.messages());
        messages.add(new StreamingClient.StreamRequest.RequestMessage("user", RECAP_PROMPT));
        return new StreamingClient.StreamRequest(
            parent.model(), parent.maxTokens(), parent.systemPrompt(), List.copyOf(messages),
            true, parent.tools(), null, parent.effort(), parent.fallbackModel(),
            parent.maxOutputTokensOverride(), parent.taskBudget(), parent.toolChoice(),
            parent.onStreamingFallback(), parent.thinkingEnabled(), parent.sessionId(),
            null, true, QUERY_SOURCE, parent.abortController(),
            parent.thinkingBudgetTokens());
    }

    /**
     * 236 {@code $lT}'s gate: a rebuild is only worth issuing when the
     * conversation holds something summarizable — a real assistant reply, a
     * compact summary, or a turn the user actually typed. Expressed with the
     * shared predicates rather than a second copy of the synthetic/tag lists:
     * {@link MessageConstants#isSyntheticMessage} ≙ 236 {@code QAe}, and
     * {@link HumanTurns#isTypedTurn} ≙ 236 {@code rrt} combined with
     * {@code !kHe(...)}.
     */
    public static boolean hasRecappableContent(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return false;
        for (Message message : messages) {
            switch (message) {
                case AssistantMessage assistant -> {
                    if (!assistant.isApiErrorMessage()
                            && !MessageConstants.isSyntheticMessage(assistant)) {
                        return true;
                    }
                }
                case UserMessage user -> {
                    if (user.isCompactSummary() || HumanTurns.isTypedTurn(user)) return true;
                }
                default -> { /* progress/attachment/system rows carry no recap material */ }
            }
        }
        return false;
    }

    /**
     * 236 slices off a trailing assistant row whose {@code stop_reason} is still
     * {@code null} — that turn never completed, and forking it would replay a
     * half-written assistant message.
     */
    private static List<Message> stripUnfinishedTurn(List<Message> messages) {
        if (messages.isEmpty()) return messages;
        return messages.getLast() instanceof AssistantMessage assistant
            && assistant.message() != null
            && assistant.message().stopReason() == null
            ? messages.subList(0, messages.size() - 1)
            : messages;
    }

    private static void recordCost(
            StreamingClient.StreamRequest request, ForkedTextStream.Result result,
            Iterator<StreamingClient.StreamingEvent> stream, long startedAt) {
        long completedAt = System.currentTimeMillis();
        long finalAttemptStartMs =
            stream instanceof StreamingClient.TimedStreamingIterator timed
                && timed.lastAttemptStartMs() > 0L
                    ? timed.lastAttemptStartMs() : startedAt;
        SessionCostState.get().recordApiRequest(
            StringUtils.defaultIfBlank(result.model(), request.model()),
            result.usage(),
            Math.max(0L, completedAt - startedAt),
            Math.max(0L, completedAt - finalAttemptStartMs));
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
