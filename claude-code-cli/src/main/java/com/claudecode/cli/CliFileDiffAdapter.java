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
 * The changed-files viewer's data plane: resolves a turn's changed files and
 * one file's hunk comparison from a session's transcript.
 *
 * <p>The turn-aware diff source is {@link TurnDiffExtractor}, which lives in
 * {@code claude-code-commands} — a module the gateway does not depend on. This
 * adapter is therefore the composition root's half of
 * {@link GatewayFileDiffPort}: it takes the gateway's session-addressed read,
 * fetches that session's messages through the existing messages port, extracts
 * the turn diffs, and answers either one turn's file summary or one path's
 * comparison.
 *
 * <p>Sessions are scanned most-recent-first so a file written in several turns
 * answers with its latest state — the file as it now stands on disk is what a
 * reader clicking a delivered-file card expects to see.
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
        return new GatewayFileDiffPort() {
            @Override
            public Optional<GatewayFileDiffPort.FileDiff> diff(String sessionId, String path) {
                return CliFileDiffAdapter.this.diff(sessionId, path);
            }

            @Override
            public Optional<GatewayFileDiffPort.ChangesSummary> summary(String sessionId, int turn) {
                return CliFileDiffAdapter.this.summary(sessionId, turn);
            }
        };
    }

    /**
     * The addressed turn's changed files, without contents.
     *
     * <p>Turns are counted the way the extractor numbers them, which is also
     * the 1-based {@code turn} the webui stamps on each transcript row, so the
     * number the card carries is the number this resolves.
     */
    private Optional<GatewayFileDiffPort.ChangesSummary> summary(String sessionId, int turn) {
        if (turn < 1) return Optional.empty();
        Optional<List<Message>> messages = messagesOf.apply(sessionId);
        if (messages.isEmpty()) return Optional.empty();
        for (TurnDiffExtractor.TurnDiff candidate : TurnDiffExtractor.extract(messages.get())) {
            if (candidate.turnIndex() != turn) continue;
            List<GatewayFileDiffPort.ChangedFile> files = candidate.files().stream()
                .map(CliFileDiffAdapter::summarize)
                .toList();
            return Optional.of(new GatewayFileDiffPort.ChangesSummary(
                turn, files, files.size(),
                candidate.stats().linesAdded(), candidate.stats().linesRemoved()));
        }
        return Optional.empty();
    }

    /** The summary row for one file: its line counts and whether it was refused. */
    private static GatewayFileDiffPort.ChangedFile summarize(TurnDiffExtractor.TurnFileDiff file) {
        GatewayFileDiffPort.FileDiff projected = project(file);
        return new GatewayFileDiffPort.ChangedFile(
            file.filePath(), file.filePath(),
            file.linesAdded(), file.linesRemoved(),
            projected.kind() == GatewayFileDiffPort.FileDiff.Kind.BINARY,
            projected.kind() == GatewayFileDiffPort.FileDiff.Kind.OVERSIZED);
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
     * <p>The hunks are passed through as the tool recorded them rather than
     * flattened into a pair of whole-file sides: the viewer draws line numbers,
     * hunk headers and a side-by-side pairing, and all three need the per-hunk
     * offsets a flattened pair has already discarded. Reading the file from
     * disk instead would answer the state <em>now</em>, which for a file edited
     * again outside this session would disagree with the turn the card belongs
     * to.
     *
     * <p>The whole-file budgets below still size the change from its own hunk
     * text, so a file whose diff is enormous is refused without ever
     * materializing the sides.
     */
    private static GatewayFileDiffPort.FileDiff project(TurnDiffExtractor.TurnFileDiff file) {
        List<StructuredPatchHunk> hunks = file.hunks();
        int totalChars = 0;
        boolean binary = false;
        for (StructuredPatchHunk hunk : hunks) {
            for (String line : hunk.lines()) {
                totalChars += line.length();
                // A NUL anywhere in the recorded lines means the tool diffed a
                // file that is not text; the record carries no flag for it, so
                // an unreadable path is reported as binary rather than as an
                // empty diff.
                if (line.indexOf('\0') >= 0) binary = true;
            }
        }
        if (binary) {
            return GatewayFileDiffPort.FileDiff.of(file.filePath(), GatewayFileDiffPort.FileDiff.Kind.BINARY);
        }
        if (totalChars > OVERSIZED_BYTES) {
            return GatewayFileDiffPort.FileDiff.of(file.filePath(), GatewayFileDiffPort.FileDiff.Kind.OVERSIZED);
        }
        // A created file has no prior version and a deletion leaves nothing
        // behind; the viewer names both cases from these two flags rather than
        // inferring them from an absent side.
        boolean before = !file.isNewFile();
        boolean deleted = !hunks.isEmpty() && hunks.stream().allMatch(hunk -> hunk.newLines() == 0);
        boolean after = !deleted;
        // `coarse` is the viewer's "line comparison timed out" notice. Nothing
        // here times out: a synthesized create hunk is a whole-file addition,
        // which is the exact change, so the flag stays false. Upstream sets it
        // when its own comparison budget expires, and this app serves the
        // recorded patch directly rather than computing a comparison.
        return GatewayFileDiffPort.FileDiff.text(file.filePath(), before, after, hunks, false);
    }
}