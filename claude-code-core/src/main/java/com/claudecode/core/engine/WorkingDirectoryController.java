package com.claudecode.core.engine;

import java.nio.file.Path;
import java.util.List;

/**
 * Session-scoped working-directory mutation port used by foreground shell tools.
 *
 * <ul>
 *   <li>foreground commands publish the physical
 *       {@code pwd -P} result before the awaiting caller continues.</li>
 *   <li>exposes
 *       the original project directory and the current allowed-directory set.</li>
 * </ul>
 *
 * <p>The interface lives in core so a tool can report a cwd transition without
 * depending on the concrete query engine, permission gate, or hook service.
 */
public interface WorkingDirectoryController {

    WorkingDirectoryController NOOP = new WorkingDirectoryController() {
        @Override public boolean mutable() { return false; }
        @Override public Path originalDirectory() { return null; }
        @Override public List<Path> allowedDirectories() { return List.of(); }
        @Override public void update(Path previous, Path current) { }
    };

    /** Whether this execution context may mutate its owning session cwd. */
    boolean mutable();

    /** Project-identity cwd to restore when maintain/reset policy requires it. */
    Path originalDirectory();

    /** Original project cwd plus every active additional working directory. */
    List<Path> allowedDirectories();

    /** Publishes one physical cwd transition to the owning session. */
    void update(Path previous, Path current);
}
