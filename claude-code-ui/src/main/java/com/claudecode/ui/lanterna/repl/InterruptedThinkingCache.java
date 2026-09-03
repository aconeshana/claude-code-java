package com.claudecode.ui.lanterna.repl;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.Usage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Single-slot cache holding the most recent completed thinking block so that pressing
 * ESC mid-turn can salvage it into the transcript instead of losing it.
 *
 * <ul>
 *   <li>{@code src/screens/REPL.tsx} — the {@code StreamingThinking} slot
 *       ({@code {thinking, isStreaming, streamingEndedAt}}) and its 30 second expiry
 *       effect. Authoritative formula from the 2.1.197 bundle:
 *       {@code let r = 30000 - (Date.now() - s.streamingEndedAt); if (r > 0)
 *       setTimeout(() => set(null), r); else set(null)}.</li>
 *   <li>{@code src/screens/REPL.tsx} — the stream handler's assistant arm, which is the
 *       slot's only writer: {@code content.find(c => c.type === "thinking")} is stored
 *       whole. {@code thinking_delta} deliberately does not touch it.</li>
 *   <li>{@code src/screens/REPL.tsx} — the ESC cancel handler, the slot's only reader.
 *       Authoritative formula from the 2.1.197 bundle:
 *       {@code let t = slot?.thinking?.trim(); if (t && thinkingStartedAt !== null)
 *       setMessages(m => [...m, assistant({content: [{type: "thinking", thinking: t,
 *       signature: ""}], isVirtual: true})])}.</li>
 * </ul>
 *
 * <p><b>Lazy expiry instead of a timer.</b> 197 clears the slot from a ClockProvider
 * timeout; this class instead compares timestamps on read. The slot has exactly one
 * reader, so the two are observationally identical, and dropping the timer removes a
 * whole class of GUI-thread and cancellation ordering bugs while keeping the window
 * testable without sleeping.
 *
 * <p><b>The salvaged message is usually invisible, and that is correct.</b> When
 * {@code TurnEngine} decides to auto-restore an interrupted prompt it calls
 * {@code rewindBeforeLastRealUser()}, which truncates the transcript back past the
 * user turn and therefore also removes the message appended here. That is the common
 * ESC path (empty input box). The salvage only surfaces when the restore is blocked —
 * non-empty input box, queued input, or viewing a teammate transcript — and then only
 * under {@code --verbose} or ctrl+o, because thinking bodies are gated there. 197
 * behaves the same way (its rewind is a prefix truncation of the same message list),
 * so this is a faithful port, not a defect to be "fixed".
 *
 * <p>Never persisted: the virtual message goes through the UI transcript reducer only.
 * {@code MessageHistory} performs no IO, JSONL is written by a separate
 * {@code TranscriptSink}, and {@code RequestMessageNormalizer} filters {@code isVirtual}
 * out of API requests regardless.
 */
final class InterruptedThinkingCache {

    /** Matches 197's {@code 30000 - (now - streamingEndedAt) > 0} liveness window. */
    static final long TTL_MILLIS = 30_000L;

    private String thinking;
    private long endedAtMillis;

    /**
     * Replaces the slot with the given thinking body, restarting the expiry window.
     *
     * <p>A {@code null} or blank body overwrites just the same — freshness is decided on
     * read, not here. Callers pass the <em>first</em> thinking block of an assistant
     * message, and skip the call entirely when the message carries none, so a
     * thinking-free message leaves the previous body in place exactly as 197's
     * {@code if (found) set(...)} writer does.
     */
    synchronized void store(String thinking, long nowMillis) {
        this.thinking = thinking;
        this.endedAtMillis = nowMillis;
    }

    /**
     * Returns the trimmed cached thinking, or {@code null} when the slot is empty, blank,
     * or older than {@link #TTL_MILLIS}. Non-destructive — 197's slot likewise survives
     * being read, and is only cleared by the expiry timer or the next writer.
     */
    synchronized String peekFresh(long nowMillis) {
        if (thinking == null) return null;
        if (nowMillis - endedAtMillis >= TTL_MILLIS) return null;
        String trimmed = thinking.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Wraps salvaged thinking in the virtual assistant message 197 appends on cancel:
     * a lone thinking block with an empty signature, flagged {@code isVirtual}.
     */
    static SDKMessage.Assistant virtualThinkingMessage(String thinking) {
        List<ContentBlock> blocks = List.of(new ThinkingBlock(thinking, ""));
        AssistantMessage message = new AssistantMessage(
            UUID.randomUUID().toString(), AssistantContent.of(blocks), false, null,
            Instant.now(), null, null, null, null, null, null, Boolean.TRUE, null, null, null);
        return new SDKMessage.Assistant(message, Usage.EMPTY);
    }
}
