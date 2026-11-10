package com.claudecode.gateway;

import com.claudecode.core.diff.StructuredPatchHunk;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Consumer-owned boundary for reading one session's last-write snapshot of a
 * changed file — the transport the webui changed-files viewer loads through.
 *
 * <p>The gateway cannot extract this itself: the only turn-aware diff source is
 * {@code TurnDiffExtractor}, which lives in {@code claude-code-commands}, a
 * module the gateway deliberately does not depend on. The CLI composition root
 * therefore resolves the addressed session's transcript (through
 * {@link GatewaySessionMessagesPort}) and answers here, in the same shape the
 * viewer renders.
 *
 * <p>Addressing mirrors {@link GatewayPermissionModePort} and
 * {@link GatewaySessionCancelPort}: a blank session id means the active TUI
 * session.
 */
public interface GatewayFileDiffPort {

    /**
     * The addressed file's comparison for the session's most recent turn that
     * touched it.
     *
     * @param sessionId the session to read, or {@code null} for the active one
     * @param path      the file path as a tool reported it
     * @return the diff, or empty when neither the session nor the file resolves
     */
    Optional<FileDiff> diff(String sessionId, String path);

    /**
     * One changed file's comparison, carried as the turn's own hunks.
     *
     * <p>The hunks are the tool record's own, passed through rather than
     * flattened into whole-file sides: the viewer draws line numbers, hunk
     * headers and a side-by-side pairing, all of which need the per-hunk
     * offsets ({@code oldStart}/{@code newStart}) that a pair of flattened
     * strings has already discarded.
     *
     * <p>{@link StructuredPatchHunk} is the same shape the wire format uses for
     * a hunk, and it lives in {@code claude-code-core}, so the gateway carries
     * it without taking a dependency on the extractor's own module.
     *
     * @param path    the path as reported
     * @param display the path to head the diff with
     * @param before  whether a prior version exists (false for a created file)
     * @param after   whether content remains (false for a deleted file)
     * @param hunks   the changed regions, in file order
     * @param coarse  whether {@code hunks} is a whole-file approximation
     *                rather than an exact patch, as a synthesized create is
     * @param kind    how the viewer should render this file
     */
    record FileDiff(
        String path,
        String display,
        boolean before,
        boolean after,
        List<StructuredPatchHunk> hunks,
        boolean coarse,
        Kind kind
    ) {
        public FileDiff {
            hunks = hunks != null ? List.copyOf(hunks) : List.of();
        }

        /** How the viewer renders one changed file. */
        public enum Kind {
            /** Text content on both sides. */
            TEXT,
            /** Not readable as UTF-8 text; no line diff exists. */
            BINARY,
            /** Beyond the size budget; not read. */
            OVERSIZED
        }

        /**
         * Convenience for a text diff.
         *
         * @param path    the path as reported
         * @param before  whether a prior version exists
         * @param after   whether content remains
         * @param hunks   the changed regions
         * @param coarse  whether the hunks are a whole-file approximation
         */
        public static FileDiff text(
            String path, boolean before, boolean after,
            List<StructuredPatchHunk> hunks, boolean coarse
        ) {
            return new FileDiff(path, path, before, after, hunks, coarse, Kind.TEXT);
        }

        /** Convenience for a file that cannot be diffed. */
        public static FileDiff of(String path, Kind kind) {
            return new FileDiff(path, path, false, false, List.of(), false, kind);
        }
    }

    /**
     * One turn's changed files, without their contents.
     *
     * <p>The viewer heads its changed-files card with this and then loads one
     * file's comparison at a time, so the card can state the totals and the
     * fold length before any diff has been read.
     *
     * @param turn    the 1-based turn number, as the transcript numbers it
     * @param files   the changed files, in the extractor's file order
     * @param total   the number of changed files
     * @param added   total added lines across {@code files}
     * @param deleted total removed lines across {@code files}
     */
    record ChangesSummary(
        int turn,
        List<ChangedFile> files,
        int total,
        int added,
        int deleted
    ) {
        public ChangesSummary {
            files = files != null ? List.copyOf(files) : List.of();
        }
    }

    /**
     * One changed file's identity and line counts, as the summary lists it.
     *
     * @param path     the path as reported
     * @param display  the path to head the row with
     * @param added    added lines in this file
     * @param deleted  removed lines in this file
     * @param binary   whether the file was reported as binary
     * @param oversized whether the file exceeded the size budget
     */
    record ChangedFile(
        String path,
        String display,
        int added,
        int deleted,
        boolean binary,
        boolean oversized
    ) {
    }

    /**
     * The addressed turn's changed files.
     *
     * @param sessionId the session to read, or {@code null} for the active one
     * @param turn      the 1-based turn number to summarize
     * @return the summary, or empty when the session or turn does not resolve
     */
    Optional<ChangesSummary> summary(String sessionId, int turn);

    /**
     * An implementation that resolves nothing. Used by the gateway's compact
     * constructors so tests and hosts without a wired composition root answer
     * an explicit "unknown file" rather than failing.
     */
    static GatewayFileDiffPort none() {
        return new GatewayFileDiffPort() {
            @Override
            public Optional<FileDiff> diff(String sessionId, String path) {
                return Optional.empty();
            }

            @Override
            public Optional<ChangesSummary> summary(String sessionId, int turn) {
                return Optional.empty();
            }
        };
    }

    /**
     * Convenience for a test or host that has a fixed answer per path.
     *
     * <p>The summary is derived from the same map so a fixture declares its
     * files once: every entry is summarized as turn 1, which is the only turn
     * a path-addressed stub can honestly claim to know.
     */
    static GatewayFileDiffPort of(Map<String, FileDiff> byPath) {
        return new GatewayFileDiffPort() {
            @Override
            public Optional<FileDiff> diff(String sessionId, String path) {
                return Optional.ofNullable(byPath.get(path));
            }

            @Override
            public Optional<ChangesSummary> summary(String sessionId, int turn) {
                List<ChangedFile> files = byPath.values().stream()
                    .map(GatewayFileDiffPort::fileOf)
                    .toList();
                if (files.isEmpty() || turn != 1) return Optional.empty();
                int added = files.stream().mapToInt(ChangedFile::added).sum();
                int deleted = files.stream().mapToInt(ChangedFile::deleted).sum();
                return Optional.of(new ChangesSummary(1, files, files.size(), added, deleted));
            }
        };
    }

    /** The summary row for one diff, counting its added and removed hunk lines. */
    private static ChangedFile fileOf(FileDiff diff) {
        int added = 0;
        int deleted = 0;
        for (StructuredPatchHunk hunk : diff.hunks()) {
            added += hunk.addedCount();
            deleted += hunk.removedCount();
        }
        return new ChangedFile(diff.path(), diff.display(), added, deleted,
            diff.kind() == FileDiff.Kind.BINARY, diff.kind() == FileDiff.Kind.OVERSIZED);
    }
}
