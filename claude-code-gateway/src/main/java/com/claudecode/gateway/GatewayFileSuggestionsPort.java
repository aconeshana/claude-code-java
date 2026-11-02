package com.claudecode.gateway;

import java.util.List;

/**
 * Consumer-owned boundary for the gateway's {@code @}-file completion surface.
 *
 * <p>The gateway must not depend on {@code claude-code-ui} (where the TUI's
 * {@code FileSuggestionService} lives) or on {@code claude-code-tools} (where
 * ripgrep is wrapped), so this port speaks plain strings: the CLI composition
 * root projects the same workspace enumeration the TUI {@code @}-completion
 * uses onto it, and the webui's {@code @} menu renders the rows.
 *
 * <p>Mirrors dsh's host {@code ctx.fileReferences} provider contract, which
 * the web client requires because it has no browser-side filesystem: the
 * candidates are <em>paths only</em>. A pick never reads or attaches file
 * contents — the submission-time attachment provider
 * ({@code AtMentionedFilesProvider}) owns that, exactly as dsh splits the
 * browser-safe reference grammar from the host's file reading.
 */
public interface GatewayFileSuggestionsPort {

    /**
     * One candidate row. {@code path} is workspace-relative for an ordinary
     * query (or absolute for a {@code ~/} / {@code /} rooted one);
     * {@code directory} is true when the row names a directory, so the client
     * can render a trailing slash and let the user descend another level.
     */
    record FileCandidate(String path, boolean directory) {}

    /** Candidates for one query, best-ranked first. */
    default List<FileCandidate> list(String query) { return List.of(); }
}