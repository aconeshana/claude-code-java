package com.claudecode.gateway;

import com.claudecode.core.annotation.Explanation;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Extends one process's own file-preview port with the sessions other
 * interactive processes host.
 *
 * <p>A standalone {@code claude web} gateway runs no engine, so every session
 * a webui client can address was opened by some TUI process, and the port the
 * composition root wired into that gateway resolves the workspace root from
 * its <em>own</em> project directory. Opening a delivered-file card there
 * therefore failed for a file that plainly exists: the gateway holds no
 * filesystem access of its own, and its own port has no session to resolve it
 * against.
 *
 * <p>A remote session is served from the work directory its owning process
 * reported over the Session Link registration, which is the same
 * {@code workDirOf} fallback the messages snapshot uses. That fallback is
 * inherently ownership-scoped — {@link RemoteWorkerPool#workDirOf} only
 * resolves a session some live worker listed — so an unowned id resolves
 * nothing here either.
 *
 * <p>The read order is local first, then remote:
 * <ul>
 *   <li>the wrapped port answers for the sessions this process hosts itself,
 *       and its answer wins whenever it has one (it may legitimately report a
 *       file as binary or oversized, which is a real answer, not a miss);</li>
 *   <li>only a miss falls through to the remote work directory, and only for a
 *       session a live worker claims.</li>
 * </ul>
 *
 * <p>No Session Link frame carries file content: the owner writes the file to
 * its own working directory, and the gateway reads that directory directly
 * through {@link GatewayWorkspaceFiles}. This is why the port can be answered
 * without a new protocol frame.
 */
@Explanation("The refactor moved the gateway out of each TUI process; this restores the "
    + "remote-session file previews the in-process gateway got for free")
class RemoteWorkspaceFilePort implements GatewayWorkspaceFilePort {

    private final GatewayWorkspaceFilePort local;
    private final RemoteWorkerPool remotes;

    /**
     * @param local   this process's own sessions (its engine and any workspace
     *                root it can resolve itself)
     * @param remotes the pool routing sessions to other processes
     */
    RemoteWorkspaceFilePort(GatewayWorkspaceFilePort local, RemoteWorkerPool remotes) {
        this.local = local;
        this.remotes = remotes;
    }

    /**
     * The addressed file, from this process when it can resolve the session and
     * from the owning process's working directory otherwise.
     *
     * <p>A remote read is anchored at the work directory the owner reported, so
     * the same containment rule applies to it as to a local one: the path must
     * resolve inside that root after symbolic links are followed.
     */
    @Override
    public Optional<TextFile> read(String sessionId, String path) {
        Optional<TextFile> own = local.read(sessionId, path);
        if (own.isPresent()) return own;
        return remotes.workDirOf(sessionId)
            .flatMap(workDir -> GatewayWorkspaceFiles.read(Path.of(workDir), path));
    }

    @Override
    public Optional<DirectoryListing> list(String sessionId, String dir) {
        Optional<DirectoryListing> own = local.list(sessionId, dir);
        if (own.isPresent()) return own;
        return remotes.workDirOf(sessionId)
            .flatMap(workDir -> GatewayWorkspaceFiles.list(Path.of(workDir), dir));
    }
}