package com.claudecode.cli;

import com.claudecode.core.state.CwdState;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.runtime.query.QuerySessionEnvironment;
import com.claudecode.services.config.SettingsReloadOrchestrator;
import com.claudecode.session.TranscriptRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Moves a running session from one project root to another so a session belonging to a
 * different directory can be resumed in place instead of being handed back to the user as a
 * {@code cd … && claude --resume …} command.
 *
 * <p>The JVM cannot change the OS working directory, and nothing here tries to: every
 * subprocess in this codebase is launched with an explicit {@code ProcessBuilder.directory},
 * so behaviour is decided entirely by three in-process values — {@code user.dir},
 * {@link QuerySession}'s configured working directory, and {@link CwdState}'s original cwd.
 * This class repoints all three, then rebuilds the startup singletons that captured the old
 * root: the transcript recorder, the permission root, the git-status snapshot, and the
 * settings watcher.
 *
 * <p>Two different notions of "current directory" are deliberately kept apart. The
 * <em>shell cwd</em> follows a Bash {@code cd}; the <em>project identity</em>
 * ({@link CwdState#getOriginalCwd()}) does not, and it is what selects the transcript
 * directory, the settings tiers, the project {@code CLAUDE.md} scope, and the permission
 * root. A project switch moves both.
 *
 * <p>The work is split to match the two-phase resume: {@link #prepare} runs on a virtual
 * thread and may block or fail, {@link #apply} runs on the UI event loop and only commits.
 * Anything computed in the first phase and not consumed by the second is discarded, so an
 * aborted resume leaves the outgoing project untouched.
 *
 * <p>Covers no original TypeScript counterpart: the released product refuses cross-directory
 * resumes outright, printing the {@code cd} hint instead, so this is a Java-side extension of
 * that flow rather than a port of it.
 *
 * <p>Known limitations, all of which need connection-level teardown and are deferred:
 * project-scoped MCP servers keep the outgoing project's connections, LSP roots are not
 * restarted, and plugin/skill discovery is not rescanned.
 */
final class CliProjectSwitch {

    private static final Logger log = LoggerFactory.getLogger(CliProjectSwitch.class);

    private final QuerySession engine;
    private final TranscriptRecorder transcriptRecorder;
    private final PermissionGate permissionGate;
    private final SettingsReloadOrchestrator settingsReload;

    /** Target-project git status computed by {@link #prepare}, awaiting commit or discard. */
    private final AtomicReference<PendingGitStatus> pendingGitStatus = new AtomicReference<>();

    private record PendingGitStatus(String cwd, String snapshot) {}

    /**
     * @param transcriptRecorder {@code null} when session persistence is off — nothing to move
     */
    CliProjectSwitch(QuerySession engine,
                     TranscriptRecorder transcriptRecorder,
                     PermissionGate permissionGate,
                     SettingsReloadOrchestrator settingsReload) {
        this.engine = engine;
        this.transcriptRecorder = transcriptRecorder;
        this.permissionGate = permissionGate;
        this.settingsReload = settingsReload;
    }

    /**
     * The project the session currently belongs to, read live rather than captured, so
     * assembly-time wiring keeps resolving correctly after a switch.
     */
    static String currentProjectRoot() {
        Path original = CwdState.getOriginalCwd();
        return original != null ? original.toString() : System.getProperty("user.dir");
    }

    /**
     * Blocking half: rejects an unusable target before anything is committed, and runs the git
     * subprocesses the target's status block needs. The result is staged, not installed.
     *
     * @throws IOException if the target is not an existing, readable directory
     */
    void prepare(String targetCwd) throws IOException {
        Path target = normalize(targetCwd);
        if (!Files.isDirectory(target)) {
            throw new IOException("Project directory no longer exists: " + targetCwd);
        }
        if (!Files.isReadable(target)) {
            throw new IOException("Project directory is not readable: " + targetCwd);
        }
        String resolved = target.toString();
        pendingGitStatus.set(new PendingGitStatus(
            resolved, QuerySessionEnvironment.computeGitStatusSnapshot(resolved)));
    }

    /**
     * Event-loop half: flips the three cwd values and repoints the singletons that hold a
     * root. The git subprocesses already ran in {@link #prepare}; what remains is field
     * assignments, object swaps, and the settings re-read.
     *
     * <p>That re-read stays synchronous on purpose. Until it completes the gate is still
     * enforcing the outgoing project's allow rules, and deferring it would leave a window in
     * which one project's grants apply to another's files. Local stats and a few small JSON
     * files are a cheaper price than that window.
     *
     * <p>Ordering matters. The transcript recorder is retargeted before the caller writes any
     * restored message, and the permission root is rebound before the settings reload replaces
     * the disk-sourced rules that resolve against it.
     */
    void apply(String targetCwd) {
        Path target = normalize(targetCwd);
        String resolved = target.toString();
        PendingGitStatus staged = pendingGitStatus.getAndSet(null);

        Path previous = Path.of(currentProjectRoot());
        if (previous.equals(target)) return;

        // Project identity first: everything below reads it back through CwdState.
        CwdState.setOriginalCwd(target);
        // Shell cwd, user.dir, and the CwdChanged hook — the same channel a Bash `cd` uses.
        engine.configuration().workingDirectoryController().update(previous, target);
        engine.configuration().getConfig().setGitStatusWorkingDirectory(resolved);
        if (staged != null && staged.cwd().equals(resolved)) {
            QuerySessionEnvironment.publishGitStatusSnapshot(staged.snapshot());
        }

        if (transcriptRecorder != null) transcriptRecorder.retargetProject(resolved);

        if (permissionGate != null) {
            permissionGate.retargetProject(target,
                CliToolchainAssembler.newPermissionPathContext(target, target));
        }

        if (settingsReload != null) {
            try {
                settingsReload.retargetProject(resolved);
            } catch (IOException e) {
                // The gate and hook engine already hold the target project's merged view;
                // only the file watcher failed to re-arm, so hot reload degrades to none.
                log.warn("Settings hot-reload not re-armed for {}: {}", resolved, e.getMessage());
            }
        }
    }

    private static Path normalize(String cwd) {
        return Path.of(cwd).toAbsolutePath().normalize();
    }
}
