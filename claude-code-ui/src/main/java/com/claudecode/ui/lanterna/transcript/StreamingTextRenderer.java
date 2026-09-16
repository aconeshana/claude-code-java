package com.claudecode.ui.lanterna.transcript;

import com.claudecode.ui.MarkdownRenderer;
import com.googlecode.lanterna.TextColor;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the live-streaming text window of one assistant text block: the panel snapshot the
 * window can roll back to, the accumulated Markdown source, the stable/unstable prefix
 * split used to re-render only the still-changing tail, and the spinner-facing
 * "visible streaming text" transition callback.
 *
 * <p>The window opens on the first non-blank {@code content_block_delta}, stays open across
 * tool execution so an unstable-text rollback can never erase a committed tool row, and is
 * closed by the dispatcher whenever a row commits below it (final text block, tool
 * result, turn reset). 197 has no snapshot truncation at all — its streamed text is a
 * per-message override — so the rollback here only ever retracts a not-yet-stable suffix
 * of the current block.
 *
 * <ul>
 *   <li>{@code src/screens/REPL.tsx} — {@code streamingText} / {@code visibleStreamingText}:
 *       accumulated on {@code text_delta}, cleared on every {@code content_block_start},
 *       so a {@code tool_use} block start ends the visible-text phase before the tool
 *       result commits.</li>
 *   <li>{@code src/components/messages/AssistantTextMessage.tsx} — the committed
 *       Markdown projection the final Assistant message repaints; when it equals the
 *       streamed source nothing is redrawn.</li>
 * </ul>
 */
final class StreamingTextRenderer {

    private static final Logger log = LoggerFactory.getLogger(StreamingTextRenderer.class);
    private static final MarkdownRenderer MARKDOWN_RENDERER = MarkdownRenderer.shared();

    /**
     * Optional callback fired on transitions of the render-layer "visible streaming text"
     * state. Deliberately NOT the rollback window ({@link #isOpen()}): that window stays
     * open across tool execution for snapshot-rollback safety, while the spinner-facing
     * visibility must close at {@code tool_streaming_start}. Invoked with {@code true} when
     * visible streaming text starts (first {@code content_block_delta} of a text phase) and
     * {@code false} when it ends (a tool stream starts, the text commits, or the turn
     * resets). Fires only on actual transitions. Wired by {@code LanternaSessionSink} to
     * drive {@code SpinnerStateMachine#onStreamTextVisibility}. Null when no consumer is
     * registered (e.g. replay-only dispatch).
     */
    private Consumer<Boolean> onVisibility;

    /** True while a streaming text block is open and may still be rolled back. */
    private boolean open;
    /** Panel line count before streaming started this block. */
    private int startSnapshot = -1;
    /** First line of the replaceable (unstable) tail of the Markdown block. */
    private int tailSnapshot = -1;
    private final StringBuilder markdownText = new StringBuilder();
    private String strippedMarkdownText = "";
    private int stablePrefixLength;
    /** Diagnostic contract: characters handed to the stable-boundary parser per delta. */
    private final List<Integer> boundaryParseInputLengths = new ArrayList<>();
    private boolean hasStableContent;
    /** Spinner-facing visibility — see {@link #onVisibility}. */
    private boolean textVisible;

    /** Register a consumer notified when the visible-streaming-text window opens/closes. */
    void onVisibility(Consumer<Boolean> listener) {
        this.onVisibility = listener;
    }

    boolean isOpen() { return open; }

    /** Panel line the open window can roll back to, or -1. */
    int startSnapshot() { return startSnapshot; }

    /** True when the committed text equals the streamed source, so nothing needs repainting. */
    boolean matchesProjection(String text) {
        return open && Strings.CS.equals(text, markdownText.toString());
    }

    /** Opens the window at the panel's current line count. */
    void open(MessagePanel panel) {
        startSnapshot = panel.snapshotLineCount();
        log.debug("[DISPATCHER] content_block_delta first: took snapshot={} lines", startSnapshot);
        open = true;
    }

    /** Accumulates one text delta and repaints the unstable tail. */
    void appendDelta(String delta, MessagePanel panel) {
        markdownText.append(delta);
        renderStreamingMarkdown(panel);
    }

    /** Drops everything the open window painted so the committed text can replace it. */
    void rollbackToStart(MessagePanel panel) {
        if (!open || startSnapshot < 0) return;
        log.debug("[DISPATCHER] truncateLinesTo({}) from {} lines",
            startSnapshot, panel.snapshotLineCount());
        panel.truncateLinesTo(startSnapshot);
    }

    /** Transition the visible-streaming-text state, firing the listener only on change. */
    void setTextVisible(boolean visible) {
        if (textVisible == visible) return;
        textVisible = visible;
        if (onVisibility != null) onVisibility.accept(visible);
    }

    /**
     * Closes the window and restores the state the live stream owns, so the next
     * {@code content_block_delta} takes a fresh snapshot at the panel's CURRENT line count
     * — after any tool result — and a later unstable-text rollback never erases a tool
     * result / diff committed in between.
     */
    void close() {
        open = false;
        startSnapshot = -1;
        tailSnapshot = -1;
        strippedMarkdownText = "";
        stablePrefixLength = 0;
        boundaryParseInputLengths.clear();
        hasStableContent = false;
        markdownText.setLength(0);
        setTextVisible(false);
    }

    /** {@link #close()} only when a window is open (a committed row must not be rolled over). */
    void closeIfOpen() {
        if (open) close();
    }

    List<Integer> boundaryParseInputLengthsForTest() {
        return List.copyOf(boundaryParseInputLengths);
    }

    private void renderStreamingMarkdown(MessagePanel panel) {
        String stripped = MARKDOWN_RENDERER.stripPromptXmlTags(markdownText.toString());
        if (!Strings.CS.startsWith(stripped, strippedMarkdownText.substring(
                0, Math.min(stablePrefixLength, strippedMarkdownText.length())))) {
            panel.truncateLinesTo(startSnapshot);
            stablePrefixLength = 0;
            hasStableContent = false;
            tailSnapshot = -1;
        }
        strippedMarkdownText = stripped;
        String unstableCandidate = stripped.substring(stablePrefixLength);
        boundaryParseInputLengths.add(unstableCandidate.length());
        int stableAdvance = MARKDOWN_RENDERER.stablePrefixLength(unstableCandidate);
        if (tailSnapshot >= 0) {
            panel.truncateLinesTo(tailSnapshot);
        }

        if (stableAdvance > 0) {
            String newlyStable = unstableCandidate.substring(0, stableAdvance);
            if (hasStableContent) {
                panel.appendLine("", TextColor.ANSI.DEFAULT);
            }
            panel.appendMarkdown(newlyStable, MARKDOWN_RENDERER, !hasStableContent);
            stablePrefixLength += stableAdvance;
            hasStableContent = true;
        }
        tailSnapshot = panel.snapshotLineCount();

        String unstableSuffix = unstableCandidate.substring(stableAdvance);
        if (!StringUtils.isBlank(unstableSuffix)) {
            if (hasStableContent) {
                panel.appendLine("", TextColor.ANSI.DEFAULT);
            }
            panel.appendMarkdown(unstableSuffix, MARKDOWN_RENDERER, !hasStableContent);
        }
    }
}
