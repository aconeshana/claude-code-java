package com.claudecode.gateway;

import java.util.List;
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
     * One workspace directory's immediate children, or empty when the session or
     * directory does not resolve inside its workspace.
     *
     * @param sessionId the session to inspect, or {@code null} for the active one
     * @param dir       relative directory path, or blank for the workspace root
     * @return the directory listing, or empty when it cannot be served
     */
    default Optional<DirectoryListing> list(String sessionId, String dir) {
        return Optional.empty();
    }

    /**
     * One entry inside a workspace directory listing.
     *
     * @param name      base entry name
     * @param path      workspace-relative POSIX path
     * @param directory whether this entry is a directory
     * @param size      file size in bytes, or 0 for directories
     */
    record DirectoryEntry(String name, String path, boolean directory, long size) {}

    /**
     * One workspace directory listing.
     *
     * @param path    workspace-relative POSIX path of the listed directory (empty for root)
     * @param entries immediate children, directories first
     */
    record DirectoryListing(String path, List<DirectoryEntry> entries) {
        public DirectoryListing {
            path = path != null ? path : "";
            entries = entries != null ? List.copyOf(entries) : List.of();
        }
    }

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
