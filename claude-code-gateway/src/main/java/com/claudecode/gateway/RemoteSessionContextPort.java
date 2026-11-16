package com.claudecode.gateway;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.message.Message;
import com.claudecode.core.metrics.SessionMetricsSnapshot;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;

/**
 * Extends one process's own context port with the sessions other interactive
 * processes host.
 *
 * <p>A standalone {@code claude web} gateway runs no engine, so every session
 * a webui client can address was opened by some TUI process. Answering from
 * the wrapped port alone makes those sessions read as <em>unknown</em> — a
 * blank model seat, a meter with no reading, no timeline entry, no overview
 * row — even though the session is running and its transcript is on disk.
 *
 * <p>Two sources back a remote session, chosen per capability:
 * <ul>
 *   <li>the owner over its Session Link, for state only the live engine
 *       knows ({@link #selection}, {@link #selectModel},
 *       {@link #selectEffort}), because a webui change and the TUI's own
 *       {@code /model} picker must edit one selection;</li>
 *   <li>the transcript the owner wrote in the working directory it reported,
 *       for usage and metrics. Those are durable per-session totals, not live
 *       engine state, so reading the same file yields the same numbers
 *       without a round trip.</li>
 * </ul>
 *
 * <p>What stays empty is what no other process can project: the
 * {@link #breakdown} needs the {@code /context} analyzer's toolchain, and
 * {@link #headerContent} needs the assembled request header — both live only
 * in the hosting process. Their consumers already degrade: the meter renders
 * its single-reading form and the System/Tools sections render empty.
 */
@Explanation("The refactor moved the gateway out of each TUI process; this restores the "
    + "remote-session answers the in-process gateway got for free")
final class RemoteSessionContextPort implements GatewaySessionContextPort {

    /** A remote selection the caller could not complete because the owner vanished. */
    private static final String GONE = "the session is no longer hosted by a running process";

    private final GatewaySessionContextPort local;
    private final RemoteWorkerPool remotes;
    private final GatewaySessionMessagesPort transcripts;
    private final MetricsFold metrics;

    /**
     * @param local       this process's own sessions (its engine and any
     *                    on-disk transcript it can resolve)
     * @param remotes     the pool routing sessions to other processes
     * @param transcripts the transcript reader, addressed by work directory
     * @param metrics     the durable-metrics fold over one session's rows
     */
    RemoteSessionContextPort(GatewaySessionContextPort local, RemoteWorkerPool remotes,
            GatewaySessionMessagesPort transcripts, MetricsFold metrics) {
        this.local = local;
        this.remotes = remotes;
        this.transcripts = transcripts;
        this.metrics = metrics;
    }

    /**
     * The durable session fold of a transcript this process cannot itself
     * open: the gateway has no session-module dependency, so the CLI
     * composition root replays the transcript the way the TUI's
     * {@code /resume} does.
     *
     * <p>The fold is addressed by the work directory the owning process
     * reported, not by this process's own project. A remote session's
     * transcript lives under its owner's project directory; resolving it
     * against the gateway's working directory finds nothing and reports every
     * remote session's metrics as absent.
     */
    @FunctionalInterface
    public interface MetricsFold {
        /**
         * The whole-session metrics of {@code sessionId} as recorded under
         * {@code workDir}, or empty when the fold is unavailable or
         * incomplete — a partial fold is never a total
         * ({@code docs/hud-metrics-specification.md} §6).
         */
        Optional<SessionMetricsSnapshot> of(String sessionId, String workDir);
    }

    @Override
    public Optional<ModelSelection> selection(String sessionId) {
        if (!isRemote(sessionId)) return local.selection(sessionId);
        return remotes.modelSelection(sessionId);
    }

    /**
     * The remote session's transcript, read through the working directory its
     * owner reported. The owner may stop routing here between the ownership
     * check and this read (it switched to another session, or exited); that
     * reads as no rows rather than as an error.
     */
    @Override
    public Optional<List<Message>> messages(String sessionId) {
        if (!isRemote(sessionId)) return local.messages(sessionId);
        return transcriptOf(sessionId);
    }

    @Override
    public Optional<ContextBreakdown> breakdown(String sessionId) {
        return isRemote(sessionId) ? Optional.empty() : local.breakdown(sessionId);
    }

    @Override
    public Optional<SessionMetricsSnapshot> metrics(String sessionId) {
        if (!isRemote(sessionId)) return local.metrics(sessionId);
        return remotes.workDirOf(sessionId).flatMap(workDir -> metrics.of(sessionId, workDir));
    }

    @Override
    public SelectionResult selectModel(String sessionId, String model) {
        if (!isRemote(sessionId)) return local.selectModel(sessionId, model);
        try {
            return remotes.selectModel(sessionId, model)
                .map(state -> SelectionResult.accepted(
                    RemoteSelections.modelSelection(state, effortOf(sessionId))))
                .orElseGet(() -> SelectionResult.rejected(GONE));
        } catch (IllegalStateException rejection) {
            return SelectionResult.rejected(StringUtils.defaultIfBlank(
                rejection.getMessage(), "the session's host rejected the model selection"));
        }
    }

    /**
     * The host applies the change; the refreshed selection is then read back
     * rather than echoed from the request, so the caller sees the state the
     * TUI {@code /model} picker would show after the same change.
     */
    @Override
    public SelectionResult selectEffort(String sessionId, String effort) {
        if (!isRemote(sessionId)) return local.selectEffort(sessionId, effort);
        try {
            if (remotes.selectEffort(sessionId, effort).isEmpty()) {
                return SelectionResult.rejected(GONE);
            }
            return remotes.model(sessionId)
                .map(state -> SelectionResult.accepted(
                    RemoteSelections.modelSelection(state, effortOf(sessionId))))
                .orElseGet(() -> SelectionResult.rejected(GONE));
        } catch (IllegalStateException rejection) {
            return SelectionResult.rejected(StringUtils.defaultIfBlank(
                rejection.getMessage(), "the session's host rejected the effort selection"));
        }
    }

    /**
     * This process's sessions plus every session another process reports as
     * its own — the set the context dashboard lists and the timeline ledger
     * builds an entry for. A remote row carries its work directory as the cwd
     * and the transcript's mtime as its update time, so both surfaces
     * describe it the way they describe a local one.
     */
    @Override
    public List<LiveSession> liveSessions() {
        return Stream.concat(
            local.liveSessions().stream(),
            remotes.liveSessions().stream().map(RemoteSessionContextPort::liveRow))
            .toList();
    }

    /**
     * The request header of a live engine; absent for a session hosted
     * elsewhere, whose assembler is not reachable from this process.
     */
    @Override
    public Optional<HeaderContent> headerContent(String sessionId) {
        return isRemote(sessionId) ? Optional.empty() : local.headerContent(sessionId);
    }

    // ------------------------------------------------------------ internals

    private boolean isRemote(String sessionId) {
        return sessionId != null && remotes.owns(sessionId);
    }

    private Optional<List<Message>> transcriptOf(String sessionId) {
        return remotes.workDirOf(sessionId)
            .flatMap(workDir -> transcripts.messagesInWorkDir(sessionId, workDir));
    }

    /**
     * The effort half that rides with a model change. A host that advertises
     * no effort levels answers without them, which is the same empty half a
     * local model change returns for a model without reasoning levels.
     */
    private GatewaySessionContextPort.EffortSelection effortOf(String sessionId) {
        return remotes.effort(sessionId).map(RemoteSelections::remoteEffort).orElse(null);
    }

    private static LiveSession liveRow(SessionHostInfo info) {
        return new LiveSession(info.id(), info.summary(), info.workDir(), info.modifiedAt());
    }
}
