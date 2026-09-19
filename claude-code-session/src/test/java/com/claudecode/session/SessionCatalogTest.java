package com.claudecode.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionCatalogTest {

    @Test
    void loadMoreSkipsInvisibleSessionsUntilItFillsTheRequestedPage(@TempDir Path base)
            throws Exception {
        SessionManager manager = new SessionManager(base, "/repo/main");
        long timestamp = 10_000;
        write(manager, "sidechain", true,
            "{\"type\":\"user\",\"isSidechain\":true,\"message\":{\"content\":\"hidden\"}}\n", timestamp--);
        write(manager, "team", true,
            "{\"type\":\"user\",\"teamName\":\"alpha\",\"message\":{\"content\":\"hidden\"}}\n", timestamp--);
        write(manager, "daemon", true,
            "{\"type\":\"user\",\"parentUuid\":null,\"sessionKind\":\"daemon\",\"message\":{\"content\":\"hidden\"}}\n", timestamp--);
        write(manager, "sdk", true,
            "{\"type\":\"user\",\"entrypoint\":\"sdk-cli\",\"message\":{\"content\":\"hidden\"}}\n", timestamp--);
        write(manager, "loop", true,
            "{\"type\":\"user\",\"message\":{\"content\":\"<command-name>/loop</command-name>\"}}\n", timestamp--);
        String first = write(manager, "first", false,
            "{\"type\":\"user\",\"message\":{\"content\":\"first visible\"}}\n", timestamp--);
        String second = write(manager, "second", false,
            "{\"type\":\"user\",\"message\":{\"content\":\"second visible\"}}\n", timestamp);

        SessionCatalog.Listing listing = SessionCatalog.forProject(manager, _ -> false);
        List<SessionCatalog.Entry> page = listing.loadMore(2);

        assertEquals(List.of(first, second), page.stream().map(e -> e.info().id()).toList());
        assertEquals(7, listing.nextIndex());
        assertFalse(listing.hasMore());
    }

    @Test
    void metadataFallbackRelocationAndPromptFormattingMatchPickerRules(@TempDir Path base)
            throws Exception {
        SessionManager manager = new SessionManager(base, "/repo/main");
        String fallback = write(manager, "fallback", false,
            "{\"type\":\"agent-name\",\"agentName\":\"solo\"}\n", 3_000);
        String bash = write(manager, "bash", false,
            """
            {"type":"user","timestamp":"2026-08-26T00:00:00Z","cwd":"/old",\
            "message":{"content":"<bash-input>git status</bash-input>"}}
            {"type":"relocated","relocatedCwd":"/new"}
            """, 2_000);
        String command = write(manager, "command", false,
            "{\"type\":\"user\",\"message\":{\"content\":\"<command-name>/model</command-name><command-args>opus</command-args>\"}}\n", 1_000);

        SessionCatalog.Listing listing = SessionCatalog.forProject(
            manager, name -> Strings.CS.equals("model", name));
        List<SessionCatalog.Entry> sessions = listing.loadMore(10);

        SessionInfo fallbackInfo = find(sessions, fallback);
        assertEquals("(session)", fallbackInfo.summary());
        assertEquals(-1, fallbackInfo.messageCount());
        SessionInfo bashInfo = find(sessions, bash);
        assertEquals("! git status", bashInfo.firstPrompt());
        assertEquals("/new", bashInfo.cwd());
        assertEquals(Instant.parse("2026-08-26T00:00:00Z"), bashInfo.createdAt());
        assertEquals("/model", find(sessions, command).firstPrompt());
    }

    @Test
    void statOnlyCursorReadsOnlyTheRequestedInitialBatch(@TempDir Path base) throws Exception {
        SessionManager manager = new SessionManager(base, "/repo/main");
        for (int i = 0; i < 1_000; i++) {
            write(manager, "session-" + i, false,
                "{\"type\":\"user\",\"message\":{\"content\":\"prompt " + i + "\"}}\n", i);
        }

        SessionCatalog.Listing listing = SessionCatalog.forProject(manager, _ -> false);
        assertEquals(50, listing.loadMore(50).size());
        assertEquals(64, listing.nextIndex());
        assertTrue(listing.hasMore());
        assertEquals(950, listing.loadMore(1_000).size());
        assertFalse(listing.hasMore());
    }

    @Test
    void concurrentBatchKeepsSurplusEntriesInReadyQueue(@TempDir Path base) throws Exception {
        SessionManager manager = new SessionManager(base, "/repo/main");
        for (int i = 0; i < 40; i++) {
            write(manager, "ready-" + i, false,
                "{\"type\":\"user\",\"message\":{\"content\":\"prompt " + i + "\"}}\n", i);
        }

        SessionCatalog.Listing listing = SessionCatalog.forProject(manager, _ -> false);
        List<String> first = listing.loadMore(5).stream().map(e -> e.info().id()).toList();
        assertEquals(32, listing.nextIndex());
        List<String> rest = listing.loadMore(35).stream().map(e -> e.info().id()).toList();

        assertEquals(5, first.size());
        assertEquals(35, rest.size());
        assertEquals(40, Stream.concat(first.stream(), rest.stream()).distinct().count());
        assertFalse(listing.hasMore());
        assertEquals(32, SessionCatalog.ioConcurrencyLimit());
    }

    @Test
    void catalogIoIsActuallyParallelAndNeverExceedsThirtyTwo(@TempDir Path base) throws Exception {
        SessionManager manager = new SessionManager(base, "/repo/main");
        for (int i = 0; i < 80; i++) write(manager, "parallel-" + i, false,
            "{\"type\":\"user\",\"message\":{\"content\":\"p" + i + "\"}}\n", i);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        CountDownLatch overlap = new CountDownLatch(2);
        SessionCatalog.IoObserver observer = new SessionCatalog.IoObserver() {
            @Override public void started() {
                int now = active.incrementAndGet();
                maximum.accumulateAndGet(now, Math::max);
                overlap.countDown();
                try { overlap.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException _) { Thread.currentThread().interrupt(); }
            }
            @Override public void finished() { active.decrementAndGet(); }
        };

        try (AutoCloseable ignored = SessionCatalog.observeIoForTest(observer)) {
            SessionCatalog.Listing listing = SessionCatalog.forProject(manager, _ -> false);
            assertEquals(80, listing.loadMore(80).size());
        }

        assertTrue(maximum.get() > 1);
        assertTrue(maximum.get() <= 32);
    }

    @Test
    void sameRepositoryCandidatesDeduplicateBySessionIdBeforeEnrichment(@TempDir Path base)
            throws Exception {
        SessionManager main = new SessionManager(base, "/repo/main");
        SessionManager worktree = new SessionManager(base, "/repo/worktree");
        String id = UUID.randomUUID().toString();
        Path older = main.getSessionFile(id);
        Files.createDirectories(older.getParent());
        Files.writeString(older,
            "{\"type\":\"user\",\"message\":{\"content\":\"older\"}}\n");
        Files.setLastModifiedTime(older, FileTime.fromMillis(1_000));
        Path newer = worktree.getSessionFile(id);
        Files.createDirectories(newer.getParent());
        Files.writeString(newer,
            "{\"type\":\"user\",\"message\":{\"content\":\"newer\"}}\n");
        Files.setLastModifiedTime(newer, FileTime.fromMillis(2_000));

        SessionCatalog.Listing listing = SessionCatalog.forManagers(
            List.of(main, worktree), _ -> false);
        List<SessionCatalog.Entry> page = listing.loadMore(10);

        assertEquals(1, page.size());
        assertEquals("newer", page.getFirst().info().firstPrompt());
        assertEquals(newer, page.getFirst().transcript());
    }

    /**
     * The archive flag is a latch, not a last-wins overwrite: archiving appends one
     * row at the end, but resuming the session afterwards pushes that row out of the
     * bounded tail window. Before the fix the flag silently reverted to false and the
     * session reappeared in every listing with no way to re-hide it.
     */
    @Test
    void archiveFlagSurvivesTranscriptGrowthPastTheTailWindow(@TempDir Path base)
            throws Exception {
        SessionManager manager = new SessionManager(base, "/repo/main");
        String id = writeArchivedThenGrown(manager, "grown",
            3L * LiteSessionReader.LITE_READ_BYTES);

        SessionCatalog.Listing listing = SessionCatalog.forProject(manager, _ -> false);
        SessionInfo info = find(listing.loadMore(10), id);

        assertTrue(info.archived(),
            "archived marker pushed out of the tail window must still be found");
    }

    @Test
    void archiveFlagIsFoundInTheOrdinaryTailWindowAndDefaultsToFalse(@TempDir Path base)
            throws Exception {
        SessionManager manager = new SessionManager(base, "/repo/main");
        String plain = write(manager, "plain", false,
            "{\"type\":\"user\",\"message\":{\"content\":\"hello\"}}\n", 2_000);
        String archived = write(manager, "archived", false,
            """
            {"type":"user","message":{"content":"hello"}}
            {"type":"archived","archived":true,"sessionId":"x"}
            """, 1_000);

        List<SessionCatalog.Entry> page =
            SessionCatalog.forProject(manager, _ -> false).loadMore(10);

        assertFalse(find(page, plain).archived(), "a session never archived stays visible");
        assertTrue(find(page, archived).archived());
    }

    /**
     * There is no unarchive UI today, but {@code lastTypedBoolean} already reads
     * {@code archived:false} as an explicit un-archive. Pin that both in the tail
     * window and through the backward scan, so re-archiving after a resume latches.
     */
    @Test
    void lastArchivedRowWinsAcrossResumesIncludingUnarchive(@TempDir Path base)
            throws Exception {
        SessionManager manager = new SessionManager(base, "/repo/main");
        long pad = 3L * LiteSessionReader.LITE_READ_BYTES;

        // archived -> grown -> unarchived (newest row wins, found in the tail).
        String unarchived = writeArchivedThenGrown(manager, "unarchived", pad);
        appendLine(manager, unarchived, "{\"type\":\"archived\",\"archived\":false,\"sessionId\":\"x\"}");

        // archived -> grown -> unarchived -> grown -> archived again: the newest
        // marker is out of the window, so the scan must pick the latest one.
        String rearchived = writeArchivedThenGrown(manager, "rearchived", pad);
        appendLine(manager, rearchived, "{\"type\":\"archived\",\"archived\":false}");
        growBy(manager, rearchived, pad);
        appendLine(manager, rearchived, "{\"type\":\"archived\",\"archived\":true}");
        growBy(manager, rearchived, pad);

        List<SessionCatalog.Entry> page =
            SessionCatalog.forProject(manager, _ -> false).loadMore(10);

        assertFalse(find(page, unarchived).archived(), "an explicit false un-archives");
        assertTrue(find(page, rearchived).archived(),
            "archive -> resume -> archive again must end up archived");
    }

    /**
     * The catalog enriches every transcript under {@code ~/.claude/projects}, so the
     * archive lookup must not degrade into a full-file read. Asserts the behaviour
     * that matters — a marker beyond the scan budget is abandoned rather than chased
     * to the start of the file.
     */
    @Test
    void archiveScanStaysBoundedAndDoesNotReadTheWholeFile(@TempDir Path base)
            throws Exception {
        SessionManager manager = new SessionManager(base, "/repo/main");
        // Marker sits far beyond LITE_READ_BYTES + MARKER_SCAN_BYTES.
        String id = writeArchivedThenGrown(manager, "beyond-budget",
            4L * LiteSessionReader.MARKER_SCAN_BYTES);

        SessionCatalog.Listing listing = SessionCatalog.forProject(manager, _ -> false);

        assertFalse(find(listing.loadMore(10), id).archived(),
            "the scan must give up at its budget instead of reading the whole file");
    }

    /** Archived row, then {@code padding} bytes of ordinary conversation after it. */
    private static String writeArchivedThenGrown(SessionManager manager, String seed,
                                                 long padding) throws Exception {
        String id = write(manager, seed, false,
            """
            {"type":"user","message":{"content":"hello"}}
            {"type":"archived","archived":true,"sessionId":"x"}
            """, 1_000);
        growBy(manager, id, padding);
        return id;
    }

    private static void growBy(SessionManager manager, String id, long padding)
            throws Exception {
        String row = "{\"type\":\"user\",\"message\":{\"content\":\"" + "x".repeat(512) + "\"}}\n";
        StringBuilder filler = new StringBuilder();
        while (filler.length() < padding) filler.append(row);
        Files.writeString(manager.getSessionFile(id), filler.toString(), StandardOpenOption.APPEND);
    }

    private static void appendLine(SessionManager manager, String id, String line)
            throws Exception {
        Files.writeString(manager.getSessionFile(id), line + "\n", StandardOpenOption.APPEND);
    }

    private static SessionInfo find(List<SessionCatalog.Entry> entries, String id) {
        return entries.stream().filter(entry -> entry.info().id().equals(id))
            .findFirst().orElseThrow().info();
    }

    private static String write(SessionManager manager, String seed, boolean ignored,
                                String content, long mtime) throws Exception {
        String id = UUID.nameUUIDFromBytes(seed.getBytes()).toString();
        Path transcript = manager.getSessionFile(id);
        Files.createDirectories(transcript.getParent());
        Files.writeString(transcript, content);
        Files.setLastModifiedTime(transcript, FileTime.fromMillis(mtime));
        return id;
    }
}
