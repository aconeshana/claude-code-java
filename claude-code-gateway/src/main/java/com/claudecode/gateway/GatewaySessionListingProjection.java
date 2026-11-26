package com.claudecode.gateway;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;

/**
 * Projects the {@code GET /api/sessions} two-level project-to-session catalog
 * by merging on-disk catalog entries with live TUI, headless, and remote worker sessions.
 */
final class GatewaySessionListingProjection {

    private final GatewaySessionCatalogPort catalog;
    private final SessionHostRegistry registry;
    private final GatewayHeadlessSessions headless;
    private final MirrorHub mirror;
    private final TranscriptSessionActions sessionActions;
    private final RemoteWorkerPool remoteWorkers;

    GatewaySessionListingProjection(
            GatewaySessionCatalogPort catalog,
            SessionHostRegistry registry,
            GatewayHeadlessSessions headless,
            MirrorHub mirror,
            TranscriptSessionActions sessionActions,
            RemoteWorkerPool remoteWorkers) {
        this.catalog = catalog;
        this.registry = registry;
        this.headless = headless;
        this.mirror = mirror;
        this.sessionActions = sessionActions;
        this.remoteWorkers = remoteWorkers;
    }

    /**
     * Two-level project→session body when a catalog adapter is injected
     * (the shape of the TUI's {@code /resume} project picker); otherwise a
     * flat fallback over the registry's own listing. {@code perProjectLimit}
     * truncates each project's rows to its most recent sessions (the total
     * stays in {@code session_count}); {@code <= 0} serves every row.
     *
     * <p>The catalog only sees sessions that already have a transcript, so the
     * live sessions are folded in first ({@link LiveSessionMerge}); without
     * that a session created moments ago carries no row for {@code active} or
     * {@code headless_open} to land on.
     *
     * <p>{@code running} marks a session with a turn in flight right now,
     * which is a different fact from {@code active} (the TUI's current
     * session) and {@code headless_open} (a session the client can route to).
     * It is read from {@link MirrorHub#runningSince}, the same turn clock the
     * message snapshot serves as {@code running_since}. That clock is
     * populated only for sessions the hub follows — the active session and
     * every open headless session — so the flag is authoritative for exactly
     * the population that already receives {@code active}/{@code
     * headless_open}, and an on-disk-only history row legitimately reports
     * not-running. Omitted rather than false, matching the other two flags.
     */
    ObjectNode sessionsBody(int perProjectLimit) {
        ObjectNode body =
            JsonUtils.getMapper().createObjectNode();
        List<SessionHostInfo> remote = remoteSessions();
        Set<String> remoteIds = remote.stream()
            .map(SessionHostInfo::id)
            .collect(Collectors.toSet());
        List<SessionHostInfo> live = liveSessions(remote);
        List<GatewaySessionCatalogPort.ProjectEntry> projects = LiveSessionMerge.merge(
            catalog.listProjects(perProjectLimit), live, catalog::canonicalizeProjectPath,
            discarded(live));
        if (!projects.isEmpty()) {
            ArrayNode projectArray = body.putArray("projects");
            for (GatewaySessionCatalogPort.ProjectEntry project : projects) {
                ObjectNode projectNode = JsonUtils.getMapper().createObjectNode();
                projectNode.put("project_path", project.projectPath());
                projectNode.put("project_name", project.projectName());
                projectNode.put("session_count", project.sessionCount());
                ArrayNode sessionArray = projectNode.putArray("sessions");
                for (GatewaySessionCatalogPort.SessionEntry entry : project.sessions()) {
                    ObjectNode sessionNode = JsonUtils.getMapper().createObjectNode();
                    sessionNode.put("id", entry.id());
                    if (hasText(entry.summary())) sessionNode.put("summary", entry.summary());
                    // A followed session's own last request is fresher than any stored row.
                    sessionNode.put("message_count", mirror.liveMessageCount(entry.id())
                        .orElseGet(entry::messageCount));
                    sessionNode.put("modified_at", Instant.ofEpochMilli(
                        entry.lastModifiedMs()).toString());
                    if (hasText(entry.gitBranch())) sessionNode.put("git_branch", entry.gitBranch());
                    if (hasText(entry.cwd())) sessionNode.put("cwd", entry.cwd());
                    if (hasText(entry.customTitle())) {
                        sessionNode.put("custom_title", entry.customTitle());
                    }
                    if (hasText(entry.firstPrompt())) {
                        sessionNode.put("first_prompt", entry.firstPrompt());
                    }
                    if (remoteIds.contains(entry.id())) {
                        sessionNode.put("remote", true);
                        sessionNode.put("tui_attached", true);
                        if (remoteWorkers != null) {
                            remoteWorkers.pidOf(entry.id())
                                .ifPresent(pid -> sessionNode.put("tui_pid", pid));
                        }
                    }
                    if (registry.isCurrent(entry.id())) sessionNode.put("active", true);
                    // A session row the client can route to right now via
                    // metadata.session_id without a further open round-trip.
                    if (headless.isOpen(entry.id())) sessionNode.put("headless_open", true);
                    if (mirror.runningSince(entry.id()).isPresent()) {
                        sessionNode.put("running", true);
                    }
                    sessionNode.put("interactive", isInteractive(entry.id(), remoteIds));
                    sessionArray.add(sessionNode);
                }
                projectArray.add(projectNode);
            }
            return body;
        }
        // Fallback: flat listing over the registry when no catalog is wired.
        List<SessionHostInfo> sessions = new ArrayList<>(registry.list());
        sessions.addAll(remote);
        sessions.sort(Comparator.comparing(
            info -> info.modifiedAt() == null ? null : info.modifiedAt(),
            Comparator.nullsFirst(Comparator.reverseOrder())));
        ArrayNode array = body.putArray("sessions");
        for (SessionHostInfo info : sessions) {
            ObjectNode node = JsonUtils.getMapper().createObjectNode();
            node.put("id", info.id());
            if (!info.workDir().isEmpty()) node.put("work_dir", info.workDir());
            if (!info.summary().isEmpty()) node.put("summary", info.summary());
            node.put("message_count", mirror.liveMessageCount(info.id())
                .orElseGet(info::messageCount));
            if (info.modifiedAt() != null) node.put("modified_at", info.modifiedAt().toString());
            if (!info.gitBranch().isEmpty()) node.put("git_branch", info.gitBranch());
            if (remoteIds.contains(info.id())) {
                node.put("remote", true);
                node.put("tui_attached", true);
                if (remoteWorkers != null) {
                    remoteWorkers.pidOf(info.id())
                        .ifPresent(pid -> node.put("tui_pid", pid));
                }
            }
            if (registry.isCurrent(info.id())) node.put("active", true);
            if (headless.isOpen(info.id())) node.put("headless_open", true);
            if (mirror.runningSince(info.id()).isPresent()) node.put("running", true);
            node.put("interactive", isInteractive(info.id(), remoteIds));
            array.add(node);
        }
        return body;
    }

    /**
     * Whether some live process is hosting this session right now.
     *
     * <p>The listing mixes two very different things under one row shape: sessions a
     * running process owns, and historical transcripts read off disk. Only the former can
     * answer for in-process state — the permission mode and the model selection are fields
     * of the hosting engine, not of the transcript — so the client cannot offer controls
     * for them on the latter. Without this flag the client finds out only by asking, one
     * round-trip each, and gets {@code null} back.
     *
     * <p>The three sources are exactly the ones {@link #liveSessions} collects, so a
     * session is interactive when it is any of: this gateway's own TUI session, a headless
     * session it opened, or a session a remote worker registered. {@code running} is
     * deliberately excluded: a turn being in flight says nothing about whether a host can
     * still answer, and a session is interactive between turns too.
     */
    private boolean isInteractive(String sessionId, Set<String> remoteIds) {
        return registry.isCurrent(sessionId)
            || headless.isOpen(sessionId)
            || remoteIds.contains(sessionId);
    }

    /**
     * The sessions that exist right now regardless of what is on disk: the active TUI session and
     * every open headless session.
     *
     * <p>{@code registry.list()} is deliberately not used here — the CLI implements its activator
     * listing with the same transcript search the catalog runs, so it is blind to exactly the
     * sessions this method exists to surface.
     */
    private List<SessionHostInfo> liveSessions(List<SessionHostInfo> remote) {
        List<SessionHostInfo> live = new ArrayList<>();
        registry.currentActivation()
            .map(activation -> activation.session().info())
            .ifPresent(live::add);
        for (GatewayHeadlessSessions.SessionListing listing : headless.list()) {
            // A session that closed between listing and lookup yields nothing rather than a row
            // built from half-known metadata.
            headless.find(listing.sessionId())
                .map(SessionHostSession::info)
                .ifPresent(live::add);
        }
        live.addAll(remote);
        return live;
    }

    /**
     * The live sessions whose row the listing must not carry: their transcript
     * says archived, or this gateway deleted them.
     *
     * <p>A handle outlives both. Delete removes the file but the engine keeps
     * running, and archive appends a marker the {@code SessionHostInfo} record
     * has no field for — while the catalog filters archived rows out entirely.
     * Either way the session is live by every test the listing can apply to a
     * handle, and {@link LiveSessionMerge} would synthesize back the very row
     * the user just removed.
     *
     * <p>The two carry different evidence and must not be collapsed into "the
     * transcript is missing": a session created before its first write has no
     * transcript either, and reading that absence as a removal hid every
     * brand-new session from the listing. Archived is read off the marker the
     * archive left behind; deleted is the record {@link
     * com.claudecode.gateway.TranscriptSessionActions} kept when its own delete
     * succeeded.
     *
     * <p>A gateway the standalone wiring did not touch gets its listing back
     * untouched: it read no transcript to begin with, so a live row is the only
     * evidence it has that the session exists.
     */
    private Set<String> discarded(List<SessionHostInfo> live) {
        // Without the transcript reader nothing on disk contradicts a handle,
        // and a refreshed session with no transcript yet is an ordinary state.
        if (live.isEmpty() || sessionActions == null) return Set.of();
        Set<String> discarded = new HashSet<>();
        for (SessionHostInfo info : live) {
            if (StringUtils.isBlank(info.id())) continue;
            if (sessionActions.retired(info.id()) || sessionActions.wasDeleted(info.id())) {
                discarded.add(info.id());
            }
        }
        return discarded;
    }

    private List<SessionHostInfo> remoteSessions() {
        return remoteWorkers != null ? remoteWorkers.liveSessions() : List.of();
    }


    /** {@code SessionInfo}'s catalog-backed fields (summary, customTitle, ...) are nullable. */
    private static boolean hasText(String value) {
        return StringUtils.isNotEmpty(value);
    }
}
