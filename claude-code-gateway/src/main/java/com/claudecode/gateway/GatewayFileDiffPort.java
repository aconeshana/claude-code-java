package com.claudecode.gateway;

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
@FunctionalInterface
public interface GatewayFileDiffPort {

    /**
     * The addressed file's before/after content for the session's most recent
     * turn that touched it.
     *
     * @param sessionId the session to read, or {@code null} for the active one
     * @param path      the file path as a tool reported it
     * @return the diff, or empty when neither the session nor the file resolves
     */
    Optional<FileDiff> diff(String sessionId, String path);

    /**
     * One changed file's before/after content.
     *
     * <p>Content is carried as whole-file text rather than as hunks: the webui
     * renders it through the vendored {@code DiffBlock}, which takes an old and
     * a new side and derives its own line accounting. A file that is not
     * readable as text answers with {@link Kind#BINARY}, and one beyond the
     * size budget with {@link Kind#OVERSIZED}, so the viewer can explain the
     * absence instead of drawing an empty diff.
     *
     * @param path      the path as reported
     * @param oldText   prior content, or {@code null} for a new file
     * @param newText   content after the change
     * @param kind      how the viewer should render this file
     * @param truncated whether {@code oldText}/{@code newText} were cut short
     */
    record FileDiff(
        String path,
        String oldText,
        String newText,
        Kind kind,
        boolean truncated
    ) {
        /** How the viewer renders one changed file. */
        public enum Kind {
            /** Text content on both sides. */
            TEXT,
            /** Not readable as UTF-8 text; no line diff exists. */
            BINARY,
            /** Beyond the size budget; not read. */
            OVERSIZED
        }

        /** Convenience for a text diff. */
        public static FileDiff text(String path, String oldText, String newText) {
            return new FileDiff(path, oldText, newText, Kind.TEXT, false);
        }

        /** Convenience for a file that cannot be diffed. */
        public static FileDiff of(String path, Kind kind) {
            return new FileDiff(path, null, "", kind, false);
        }
    }

    /**
     * An implementation that resolves nothing. Used by the gateway's compact
     * constructors so tests and hosts without a wired composition root answer
     * an explicit "unknown file" rather than failing.
     */
    static GatewayFileDiffPort none() {
        return (_, _) -> Optional.empty();
    }

    /** Convenience for a test or host that has a fixed answer per path. */
    static GatewayFileDiffPort of(Map<String, FileDiff> byPath) {
        return (_, path) -> Optional.ofNullable(byPath.get(path));
    }
}
