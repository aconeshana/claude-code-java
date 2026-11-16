package com.claudecode.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.gateway.GatewaySessionCatalogPort.ProjectEntry;
import com.claudecode.gateway.GatewaySessionCatalogPort.SessionEntry;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The catalog only sees sessions that already wrote a transcript, so a session created moments ago
 * has no row for {@code active}/{@code headless_open} to land on. These expectations pin the fold
 * that puts it back.
 */
class LiveSessionMergeTest {

    private static final UnaryOperator<String> IDENTITY = path -> path;

    private static SessionHostInfo live(String id, String workDir) {
        return new SessionHostInfo(id, workDir, "live " + id, 2, Instant.ofEpochMilli(9_000), "main");
    }

    private static SessionEntry row(String id, long modifiedMs) {
        return new SessionEntry(id, "stored " + id, 1, modifiedMs, "main", "/repo", null, null);
    }

    private static ProjectEntry project(String path, SessionEntry... rows) {
        return new ProjectEntry(path, path.substring(path.lastIndexOf('/') + 1),
            rows.length, 1_000L, List.of(rows));
    }

    private static List<String> idsOf(ProjectEntry project) {
        return project.sessions().stream().map(SessionEntry::id).toList();
    }

    @Test
    void foldsALiveSessionIntoTheProjectItBelongsTo() {
        List<ProjectEntry> merged = LiveSessionMerge.merge(
            List.of(project("/repo", row("stored-1", 1_000L))),
            List.of(live("fresh-1", "/repo")),
            IDENTITY);

        assertEquals(1, merged.size(), "an existing project absorbs the row, it does not duplicate");
        assertEquals(List.of("fresh-1", "stored-1"), idsOf(merged.getFirst()),
            "the live session is the most recently active, so it leads");
        assertEquals(2, merged.getFirst().sessionCount());
        assertEquals(9_000L, merged.getFirst().lastActivityMs());
    }

    @Test
    void synthesizesAProjectForADirectoryTheCatalogHasNeverSeen() {
        List<ProjectEntry> merged = LiveSessionMerge.merge(
            List.of(project("/repo", row("stored-1", 1_000L))),
            List.of(live("fresh-1", "/elsewhere/other")),
            IDENTITY);

        assertEquals(2, merged.size());
        ProjectEntry synthesized = merged.getFirst();
        assertEquals("/elsewhere/other", synthesized.projectPath(),
            "a synthesized project leads: its session is live");
        assertEquals("other", synthesized.projectName(), "labelled by directory name, like the catalog");
        assertEquals(List.of("fresh-1"), idsOf(synthesized));
        assertEquals(List.of("stored-1"), idsOf(merged.get(1)));
    }

    @Test
    void takesTheLiveCountsForASessionTheCatalogAlreadyLists() {
        // The catalog globbed the transcript earlier; its message_count is frozen at that moment.
        // The live row is the only source that keeps advancing, so it must win.
        List<ProjectEntry> merged =
            LiveSessionMerge.merge(
                List.of(project("/repo", row("sess-1", 1_000L))),
                List.of(live("sess-1", "/repo")),
                IDENTITY);

        assertEquals(1, merged.size());
        assertEquals(List.of("sess-1"), idsOf(merged.getFirst()), "no duplicate row");
        assertEquals(2, merged.getFirst().sessions().getFirst().messageCount(),
            "live count replaces the catalog's stale one");
        assertEquals(9_000L, merged.getFirst().sessions().getFirst().lastModifiedMs());
        assertEquals(1, merged.getFirst().sessionCount(), "overlaying a row adds no session");
    }

    @Test
    void keepsTheCatalogsTitleAndFirstPromptWhenOverlayingALiveRow() {
        SessionEntry titled = new SessionEntry(
            "sess-1", "stored summary", 1, 1_000L, "main", "/repo", "My session", "first words");

        List<ProjectEntry> merged = LiveSessionMerge.merge(
            List.of(project("/repo", titled)), List.of(live("sess-1", "/repo")), IDENTITY);

        SessionEntry overlaid = merged.getFirst().sessions().getFirst();
        assertEquals("My session", overlaid.customTitle(),
            "the live registry does not carry a custom title");
        assertEquals("first words", overlaid.firstPrompt());
        assertEquals(2, overlaid.messageCount());
    }

    @Test
    void leavesEveryRowAloneWhenNoCatalogSessionIsLive() {
        List<ProjectEntry> catalog = List.of(project("/repo", row("sess-1", 1_000L)));

        List<ProjectEntry> merged = LiveSessionMerge.merge(catalog, List.of(), IDENTITY);

        assertSame(catalog, merged, "nothing to fold in, so the listing is handed back untouched");
    }

    @Test
    void groupsSeveralLiveSessionsOfOneDirectoryUnderASingleProject() {
        List<ProjectEntry> merged = LiveSessionMerge.merge(
            List.of(),
            List.of(live("fresh-1", "/repo"), live("fresh-2", "/repo")),
            IDENTITY);

        assertEquals(1, merged.size());
        assertEquals(List.of("fresh-1", "fresh-2"), idsOf(merged.getFirst()));
        assertEquals(2, merged.getFirst().sessionCount());
    }

    @Test
    void matchesThroughTheCatalogsOwnCanonicalForm() {
        // The registry reports the directory the session was started from; the catalog keys by its
        // canonical form. Without the operator the two would become separate project rows.
        List<ProjectEntry> merged = LiveSessionMerge.merge(
            List.of(project("/repo", row("stored-1", 1_000L))),
            List.of(live("fresh-1", "/symlink/repo")),
            _ -> "/repo");

        assertEquals(1, merged.size());
        assertEquals(List.of("fresh-1", "stored-1"), idsOf(merged.getFirst()));
    }

    @Test
    void skipsASessionWithNoWorkingDirectory() {
        // Every blank directory would otherwise collapse into one project keyed by the empty
        // string, which also breaks the sidebar's per-project collapse state.
        List<ProjectEntry> merged = LiveSessionMerge.merge(
            List.of(project("/repo", row("stored-1", 1_000L))),
            List.of(new SessionHostInfo("fresh-1", null, "", 0, Instant.ofEpochMilli(9_000), "")),
            IDENTITY);

        assertEquals(1, merged.size());
        assertEquals(List.of("stored-1"), idsOf(merged.getFirst()));
    }

    @Test
    void dropsADuplicateAmongTheLiveSessionsThemselves() {
        List<ProjectEntry> merged = LiveSessionMerge.merge(
            List.of(),
            List.of(live("fresh-1", "/repo"), live("fresh-1", "/repo")),
            IDENTITY);

        assertEquals(List.of("fresh-1"), idsOf(merged.getFirst()));
    }

    @Test
    void returnsAnEmptyListingUnchangedWhenNothingIsLive() {
        assertTrue(LiveSessionMerge.merge(List.of(), List.of(), IDENTITY).isEmpty());
    }

    // -------------------------------------------------------------- BUG-15

    /**
     * Deleting a headless session must not bring it back.
     *
     * <p>Delete removes the transcript but not the open handle, so the next
     * listing still reports the session as live and this fold — whose whole job
     * is to synthesize a row for every live session the catalog does not know —
     * rebuilds the row the user just deleted. A transcript that is gone is the
     * one durable signal that separates "created a moment ago, never written"
     * from "written and then deleted", and it must win over the handle.
     */
    @Test
    void doesNotResurrectALiveSessionWhoseTranscriptIsGone() {
        List<ProjectEntry> merged = LiveSessionMerge.merge(
            List.of(project("/repo", row("stored-1", 1_000L))),
            List.of(live("deleted-1", "/repo")),
            IDENTITY,
            Set.of("deleted-1"));

        assertEquals(List.of("stored-1"), idsOf(merged.getFirst()),
            "the deleted session is not folded back in");
        assertEquals(1, merged.getFirst().sessionCount());
    }

    /**
     * The same fold against an empty catalog: a deleted session must not
     * synthesize a project of its own either.
     */
    @Test
    void doesNotSynthesizeAProjectForADeletedSession() {
        assertTrue(
            LiveSessionMerge.merge(List.of(), List.of(live("deleted-1", "/repo")), IDENTITY,
                Set.of("deleted-1")).isEmpty());
    }

    /**
     * Archiving is the second half of the same defect, but its evidence is the
     * opposite one: the transcript is still there and carries the marker, while
     * the live handle knows nothing about it. A live fold must therefore be
     * told which live sessions the transcripts call archived, and drop those
     * rows the catalog already dropped.
     */
    @Test
    void doesNotResurrectASessionWhoseTranscriptIsArchived() {
        List<ProjectEntry> merged = LiveSessionMerge.merge(
            List.of(),
            List.of(live("archived-1", "/repo"), live("open-1", "/repo")),
            IDENTITY,
            Set.of("archived-1"));

        assertEquals(1, merged.size());
        assertEquals(List.of("open-1"), idsOf(merged.getFirst()));
        assertEquals(1, merged.getFirst().sessionCount());
    }
}
