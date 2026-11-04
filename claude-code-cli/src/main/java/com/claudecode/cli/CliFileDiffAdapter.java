package com.claudecode.cli;

import com.claudecode.commands.diff.TurnDiffExtractor;
import com.claudecode.core.diff.StructuredPatchHunk;
import com.claudecode.core.message.Message;
import com.claudecode.gateway.GatewayFileDiffPort;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The changed-files viewer's data plane: resolves one file's before/after
 * content from a session's transcript.
 *
 * <p>The turn-aware diff source is {@link TurnDiffExtractor}, which lives in
 * {@code claude-code-commands} — a module the gateway does not depend on. This
 * adapter is therefore the composition root's half of
 * {@link GatewayFileDiffPort}: it takes the gateway's session-addressed read,
 * fetches that session's messages through the existing messages port, extracts
 * the turn diffs, and answers with the newest turn that touched the path.
 *
 * <p>Sessions are scanned most-recent-first so a file written in several turns
 * answers with its latest state — the file as it now stands on disk is what a
 * reader clicking a delivered-file chip expects to see.
 */
final class CliFileDiffAdapter {

    /** Content beyond this is reported as oversized rather than diffed. */
    private static final int OVERSIZED_BYTES = 1_000_000;

    private final Function<String, Optional<List<Message>>> messagesOf;

    /**
     * @param messagesOf resolves a session id to its messages (the gateway
     *                   messages port, already bound to the right roots); a
     *                   blank id must already resolve to the active session,
     *                   which is the messages port's own contract
     */
    CliFileDiffAdapter(Function<String, Optional<List<Message>>> messagesOf) {
        this.messagesOf = messagesOf;
    }

    /** The port this adapter implements. */
    GatewayFileDiffPort port() {
        return this::diff;
    }

    private Optional<GatewayFileDiffPort.FileDiff> diff(String sessionId, String path) {
        if (StringUtils.isBlank(path)) return Optional.empty();
        Optional<List<Message>> messages = messagesOf.apply(sessionId);
        if (messages.isEmpty()) return Optional.empty();
        // Most recent turn first: the extractor already returns newest-first,
        // so the first turn that names the path is the file's latest state.
        for (TurnDiffExtractor.TurnDiff turn : TurnDiffExtractor.extract(messages.get())) {
            for (TurnDiffExtractor.TurnFileDiff file : turn.files()) {
                if (!file.filePath().equals(path)) continue;
                return Optional.of(project(file));
            }
        }
        return Optional.empty();
    }

    /**
     * Projects one extracted file change onto the viewer's shape.
     *
     * <p>The sides come from the hunks rather than from disk: a hunk's removed
     * lines are the old side and its added and context lines the new side, both
     * already in the tool's own record. Reading the file instead would answer
     * the state <em>now</em>, which for a file edited again outside this
     * session would disagree with the turn the chip belongs to.
     */
    private GatewayFileDiffPort.FileDiff project(TurnDiffExtractor.TurnFileDiff file) {
        StringBuilder oldSide = new StringBuilder();
        StringBuilder newSide = new StringBuilder();
        for (StructuredPatchHunk hunk : file.hunks()) {
            for (String line : hunk.lines()) {
                if (line.isEmpty()) {
                    // A blank entry carries no marker; treat it as context.
                    oldSide.append('\n');
                    newSide.append('\n');
                    continue;
                }
                char marker = line.charAt(0);
                String body = line.substring(1);
                if (marker == '-') {
                    oldSide.append(body).append('\n');
                } else if (marker == '+') {
                    newSide.append(body).append('\n');
                } else {
                    // A context line belongs to both sides.
                    oldSide.append(body).append('\n');
                    newSide.append(body).append('\n');
                }
            }
        }
        // A created file has no old side: `DiffBlock` draws no removed lines for
        // a null side, which is the correct reading for a new file.
        String oldText = file.isNewFile() || oldSide.isEmpty() ? null : oldSide.toString();
        String newText = newSide.toString();
        if (oversized(oldText) || oversized(newText)) {
            return GatewayFileDiffPort.FileDiff.of(file.filePath(),
                GatewayFileDiffPort.FileDiff.Kind.OVERSIZED);
        }
        // A file whose content is not text has no line diff; the record carries
        // no flag for it, so an unreadable path is reported as binary rather
        // than as an empty diff.
        if (newText.indexOf('\0') >= 0) {
            return GatewayFileDiffPort.FileDiff.of(file.filePath(),
                GatewayFileDiffPort.FileDiff.Kind.BINARY);
        }
        return GatewayFileDiffPort.FileDiff.text(file.filePath(), oldText, newText);
    }

    private static boolean oversized(String text) {
        return text != null && text.length() > OVERSIZED_BYTES;
    }
}