package com.claudecode.gateway;

import java.util.Optional;

/**
 * Consumer-owned boundary for reading one workspace file's content — the
 * transport the webui file-preview tab loads through.
 *
 * <p>The gateway holds no filesystem access of its own. The CLI composition
 * root resolves the path against the session's workspace and answers here.
 *
 * <p>Addressing mirrors {@link GatewayFileDiffPort}: a blank session id means
 * the active TUI session.
 */
public interface GatewayWorkspaceFilePort {

    /**
     * The addressed file's content, or empty when the session or the file does
     * not resolve inside its workspace.
     *
     * @param sessionId the session to read, or {@code null} for the active one
     * @param path      the file path, absolute or relative to the workspace
     * @return the file, or empty when it cannot be served
     */
    Optional<TextFile> read(String sessionId, String path);

    /**
     * One file's content as the preview renders it.
     *
     * @param path the path as requested
     * @param kind how the viewer should render this file
     * @param text the decoded UTF-8 content; empty unless {@code kind} is {@link Kind#TEXT}
     */
    record TextFile(String path, Kind kind, String text) {

        public TextFile {
            text = text != null ? text : "";
        }

        /** How the viewer renders one file. */
        public enum Kind {
            /** Readable UTF-8 text. */
            TEXT,
            /** Not readable as UTF-8 text. */
            BINARY,
            /** Beyond the size budget; not read. */
            OVERSIZED
        }
    }

    /**
     * A port that serves nothing.
     *
     * @return a port answering empty for every request
     */
    static GatewayWorkspaceFilePort none() {
        return (_, _) -> Optional.empty();
    }
}
