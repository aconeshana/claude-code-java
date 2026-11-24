package com.claudecode.cli;

import com.claudecode.gateway.GatewayWorkspaceFilePort;
import com.claudecode.gateway.GatewayWorkspaceFiles;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

/**
 * Reads one workspace file for the webui file-preview tab, resolving the
 * session id to a workspace root this process owns.
 *
 * <p>The read itself — containment once symbolic links are followed, the size
 * budget, the binary/oversized classification — is {@link GatewayWorkspaceFiles},
 * shared with the gateway's own remote-session reader. This class contributes
 * only the addressing: which root a session id means in <em>this</em> process
 * (the active TUI session's working directory, or an open headless session's
 * project path).
 */
final class CliWorkspaceFileReader {

    /** The size budget for one previewed file. */
    static final long MAX_TEXT_BYTES = GatewayWorkspaceFiles.MAX_TEXT_BYTES;

    private final Function<String, Optional<Path>> rootOf;

    /**
     * @param rootOf resolves a session id to its workspace root; empty when the
     *               session has no root this reader can serve
     */
    CliWorkspaceFileReader(Function<String, Optional<Path>> rootOf) {
        this.rootOf = rootOf;
    }

    GatewayWorkspaceFilePort port() {
        return new GatewayWorkspaceFilePort() {
            @Override
            public Optional<TextFile> read(String sessionId, String path) {
                return rootOf.apply(sessionId)
                    .flatMap(root -> GatewayWorkspaceFiles.read(root, path));
            }

            @Override
            public Optional<DirectoryListing> list(String sessionId, String dir) {
                return rootOf.apply(sessionId)
                    .flatMap(root -> GatewayWorkspaceFiles.list(root, dir));
            }

            @Override
            public boolean openInHost(String sessionId, String path, boolean reveal) {
                return rootOf.apply(sessionId)
                    .map(root -> GatewayWorkspaceFiles.openInHost(root, path, reveal))
                    .orElse(false);
            }
        };
    }

    static Optional<GatewayWorkspaceFilePort.TextFile> read(Path root, String path) {
        return GatewayWorkspaceFiles.read(root, path);
    }
}
