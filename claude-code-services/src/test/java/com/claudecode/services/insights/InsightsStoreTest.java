package com.claudecode.services.insights;


import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Session scanning (UUID filter, mtime sort) and cache round-trips. */
class InsightsStoreTest {

    private static final String UUID_1 = "11111111-1111-1111-1111-111111111111";
    private static final String UUID_2 = "22222222-2222-2222-2222-222222222222";

    @TempDir
    Path tmp;

    private InsightsStore store;

    @BeforeEach
    void setUp() {
        store = new InsightsStore(tmp.resolve("usage-data"), tmp.resolve("projects"));
    }

    // ── scanAllSessions ──────────────────────────────────────────────────────

    @Test
    void scanFiltersNonUuidFilesAndSortsByMtimeDescending() throws IOException {
        Path p1 = Files.createDirectories(tmp.resolve("projects/-Users-a-proj1"));
        Path p2 = Files.createDirectories(tmp.resolve("projects/-Users-a-proj2"));

        Path older = p1.resolve(UUID_1 + ".jsonl");
        Files.writeString(older, "{}\n{}\n");
        Files.setLastModifiedTime(older, FileTime.fromMillis(1_000_000L));

        Path newer = p2.resolve(UUID_2 + ".jsonl");
        Files.writeString(newer, "{}\n");
        Files.setLastModifiedTime(newer, FileTime.fromMillis(2_000_000L));

        // Filtered out: non-UUID name, wrong extension, directory
        Files.writeString(p1.resolve("not-a-uuid.jsonl"), "{}\n");
        Files.writeString(p1.resolve(UUID_2 + ".json"), "{}\n");
        Files.createDirectories(p1.resolve(UUID_2 + ".jsonl.d"));
        // Files directly under projects/ (not in a project dir) are ignored
        Files.writeString(tmp.resolve("projects").resolve(UUID_1 + ".jsonl"), "{}\n");

        List<InsightsStore.LiteSessionInfo> sessions = store.scanAllSessions();

        assertEquals(2, sessions.size());
        assertEquals(UUID_2, sessions.getFirst().sessionId());
        assertEquals(2_000_000L, sessions.getFirst().mtime());
        assertEquals(UUID_1, sessions.get(1).sessionId());
        assertEquals(newer, sessions.getFirst().path());
        assertEquals(Files.size(older), sessions.get(1).size());
    }

    @Test
    void scanReturnsEmptyWhenProjectsDirMissing() {
        assertTrue(store.scanAllSessions().isEmpty());
    }

    // ── session-meta cache ───────────────────────────────────────────────────

    private static SessionMeta sampleMeta(String sessionId) {
        return SessionMeta.builder(sessionId, "/Users/a/proj", "2026-01-05T10:00:00.000Z")
            .durationMinutes(30.0).userMessageCount(2).assistantMessageCount(5)
            .toolCounts(Map.of("Edit", 3L)).languages(Map.of("Java", 2L))
            .gitCommits(1).gitPushes(1).inputTokens(1000).outputTokens(500)
            .firstPrompt("fix the bug").userInterruptions(1)
            .userResponseTimes(List.of(10.5, 42.0)).toolErrors(2)
            .toolErrorCategories(Map.of("Command Failed", 2L)).usesTaskAgent(true)
            .usesWebSearch(true).linesAdded(12).linesRemoved(3).filesModified(4)
            .messageHours(List.of(10, 23))
            .userMessageTimestamps(List.of("2026-01-05T10:00:00.000Z"))
            .build();
    }

    @Test
    void sessionMetaRoundTripsThroughSnakeCaseJson() throws IOException {
        SessionMeta original = sampleMeta(UUID_1);

        store.saveSessionMeta(original);

        Path file = tmp.resolve("usage-data/session-meta/" + UUID_1 + ".json");
        assertTrue(Files.exists(file));
        String json = Files.readString(file);
        assertTrue(Strings.CS.contains(json, "session_id"), "cache must use TS snake_case keys");
        assertTrue(Strings.CS.contains(json, "user_message_count"));

        assertEquals(original, store.loadCachedSessionMeta(UUID_1));
    }

    @Test
    void loadCachedSessionMetaReturnsNullWhenMissingOrCorrupt() throws IOException {
        assertNull(store.loadCachedSessionMeta(UUID_1));

        Path dir = Files.createDirectories(tmp.resolve("usage-data/session-meta"));
        Files.writeString(dir.resolve(UUID_1 + ".json"), "{not json");
        assertNull(store.loadCachedSessionMeta(UUID_1));
    }

    // ── facets cache ─────────────────────────────────────────────────────────

    private static SessionFacets sampleFacets(String sessionId) {
        return new SessionFacets(sessionId, "ship the feature",
            Map.of("implement_feature", 1L), "fully_achieved",
            Map.of("satisfied", 1L), "very_helpful", "single_task",
            Map.of(), null, "correct_code_edits", "Shipped it.",
            List.of("use tabs"));
    }

    @Test
    void facetsRoundTripThroughJson() {
        SessionFacets original = sampleFacets(UUID_1);

        store.saveFacets(original);

        assertTrue(Files.exists(tmp.resolve("usage-data/facets/" + UUID_1 + ".json")));
        assertEquals(original, store.loadCachedFacets(UUID_1));
    }

    @Test
    void invalidCachedFacetsAreDeletedAndReturnNull() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("usage-data/facets"));
        Path file = dir.resolve(UUID_1 + ".json");
        // Parseable JSON but missing required facet fields → invalid
        Files.writeString(file, "{\"session_id\":\"" + UUID_1 + "\"}");

        assertNull(store.loadCachedFacets(UUID_1));
        assertFalse(Files.exists(file), "corrupted facet cache must be deleted for re-extraction");
    }

    @Test
    void loadCachedFacetsReturnsNullWhenMissing() {
        assertNull(store.loadCachedFacets(UUID_2));
    }
}
