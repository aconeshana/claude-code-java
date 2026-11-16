package com.claudecode.gateway;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.session.SessionOperationsService;
import com.claudecode.runtime.webworker.WebWorkerDirectory;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Extends one process's own session-actions port with the sessions it does not
 * host, answered from the transcript those sessions wrote to disk.
 *
 * <p>A standalone {@code claude web} gateway runs no engine, so every session a
 * webui client can address was opened by some TUI process. The composition root
 * that starts that gateway wires no session storage of its own and passes the
 * unmapped {@link GatewaySessionActionsPort}, whose four defaults answer by
 * throwing, so {@code /rename}, {@code /fork}, {@code /archive} and
 * {@code DELETE} each returned 500 <em>"… is not configured"</em> for a row the
 * same webui had just listed.
 *
 * <p>None of the four needs a live engine. Rename and archive append one JSONL
 * record, delete removes the transcript and its sidecar directory, and fork
 * derives a child transcript from the parent's rows. All four are addressable
 * by bare session id, because {@link SessionOperationsService} resolves an id
 * against every project directory under the configuration home rather than
 * against one process's project. So the gateway answers them itself instead of
 * forwarding them to the owner: no Session Link frame carries them, and a
 * session whose owner has already exited — the common case for a row a user is
 * tidying up — stays actionable.
 *
 * <p>The order is local first, then the transcript:
 * <ul>
 *   <li>the wrapped port answers for a session this process hosts, and its
 *       answer wins — including its own 404 and its own 500, which describe a
 *       live operation rather than a missing one;</li>
 *   <li>only a port that cannot serve the action at all — the unmapped default,
 *       which signals that by throwing
 *       {@link UnsupportedOperationException} — falls through to the transcript
 *       this process reads from the same configuration home.</li>
 * </ul>
 *
 * <p>That falling-through port is why the local-first rule here cannot be an
 * ownership test of the kind {@link RemoteSessionContextPort} uses: the
 * transcript is the same file for a session hosted in another process and for
 * one nobody hosts any more, and both must stay editable.
 *
 * <p>Unknown-id and failed-on-existing stay distinguishable across the
 * translation: {@link SessionOperationsService.SessionNotFoundException} — the
 * only signal of a missing transcript — becomes
 * {@link UnknownSessionException} (404), while a fork that resolves a session
 * but finds nothing forkable, or a write that fails, keeps its own
 * {@code RuntimeException} (500). See
 * {@link GatewaySessionActionsPort} for why that distinction cannot be inferred
 * from the exception types at the handler.
 */
@Explanation("The refactor moved the gateway out of each TUI process; this restores the "
    + "session row menu actions the in-process gateway got for free")
final class TranscriptSessionActions implements GatewaySessionActionsPort {

    private final GatewaySessionActionsPort local;
    private final SessionOperationsService transcripts;

    /**
     * @param local      this process's own sessions; its answer wins whenever
     *                   it can give one
     * @param configHome the configuration home whose {@code projects/} tree
     *                   holds every session's transcript, including the
     *                   transcripts of sessions this process does not host
     */
    TranscriptSessionActions(GatewaySessionActionsPort local, Path configHome) {
        this.local = local;
        this.transcripts = new SessionOperationsService(configHome);
    }

    /**
     * The configuration home a worker directory implies: it is by definition
     * {@code <configuration home>/web-workers} (see {@link WebWorkerDirectory}),
     * so its parent holds the {@code projects/} tree the transcripts live under.
     *
     * <p>That is the only configuration path this server is handed, and deriving
     * from it — rather than adding a second, separately-set builder field — keeps
     * the standalone {@code claude web} gateway and a test that relocates its
     * state through {@code useWebStateDirectory} reading the same home as the
     * composition root's other readers.
     *
     * <p>A directory with no parent (a filesystem root, never a real layout) is
     * passed through unchanged: no {@code projects/} tree is found under it, so
     * every session reads as unknown — the same answer as for any other home
     * holding no transcript.
     */
    static Path configurationHomeOf(WebWorkerDirectory workers) {
        Path directory = workers.directory();
        return Objects.requireNonNullElse(directory.getParent(), directory);
    }

    @Override
    public void rename(String sessionId, String title) {
        try {
            local.rename(sessionId, title);
        } catch (UnsupportedOperationException _) {
            translate(() -> {
                transcripts.renameSession(sessionId, title, null);
                return null;
            });
        }
    }

    @Override
    public ForkResult fork(String sessionId, String title) {
        try {
            return local.fork(sessionId, title);
        } catch (UnsupportedOperationException _) {
            return translate(() -> new ForkResult(
                transcripts.forkSession(sessionId, (String) null, null, title).sessionId()));
        }
    }

    @Override
    public void archive(String sessionId) {
        try {
            local.archive(sessionId);
        } catch (UnsupportedOperationException _) {
            translate(() -> {
                transcripts.archiveSession(sessionId, null);
                return null;
            });
        }
    }

    @Override
    public boolean delete(String sessionId) {
        try {
            return local.delete(sessionId);
        } catch (UnsupportedOperationException _) {
            // False for an id no transcript answers to: deletion is idempotent
            // and the handler renders it as 404, not as a failed operation.
            return translate(() -> transcripts.deleteSession(sessionId, null));
        }
    }

    /** A missing transcript is the one failure the handler must render as 404. */
    private static <T> T translate(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (SessionOperationsService.SessionNotFoundException missing) {
            throw new UnknownSessionException(missing.getMessage());
        }
    }
}