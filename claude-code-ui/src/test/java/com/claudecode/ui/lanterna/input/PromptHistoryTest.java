package com.claudecode.ui.lanterna.input;

import com.claudecode.core.message.PastedContent;
import com.claudecode.core.serialization.JsonUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.LinkedHashSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;


class PromptHistoryTest {

    private static final String SID = "session-1";
    private static final String PROJ = "/proj";
    private final List<PromptHistory> histories = new ArrayList<>();

    private PromptHistory history(Path tmp) {
        PromptHistory history = new PromptHistory(tmp.resolve("history.jsonl"));
        histories.add(history);
        return history;
    }

    private PromptHistory history(Path tmp, long flushDelayMs) {
        PromptHistory history = new PromptHistory(tmp.resolve("history.jsonl"), flushDelayMs);
        histories.add(history);
        return history;
    }

    private static List<String> displays(PromptHistory history, int limit) {
        return history.getEntriesWithPasted(limit, PROJ, SID, null).stream()
            .map(PromptHistory.Entry::display)
            .toList();
    }

    @Test
    void released197ChildSessionPersistenceGate() {
        assertTrue(PromptHistory.shouldSkipPromptHistory(
            Map.of("CLAUDE_CODE_SKIP_PROMPT_HISTORY", "true"), true));
        assertTrue(PromptHistory.shouldSkipPromptHistory(
            Map.of("CLAUDE_CODE_CHILD_SESSION", "true"), false));
        assertFalse(PromptHistory.shouldSkipPromptHistory(
            Map.of("CLAUDE_CODE_CHILD_SESSION", "true"), true));
        assertFalse(PromptHistory.shouldSkipPromptHistory(Map.of(), false));
        assertFalse(PromptHistory.shouldSkipPromptHistory(Map.of(
            "CLAUDE_CODE_CHILD_SESSION", "true",
            "CLAUDE_CODE_FORCE_SESSION_PERSISTENCE", "true"), false));
        assertFalse(PromptHistory.shouldSkipPromptHistory(
            Map.of("CLAUDE_CODE_CHILD_SESSION", "true"), false, true, true),
            "released z9e keeps prompt history for teammate contexts");
        assertFalse(PromptHistory.shouldSkipPromptHistory(
            Map.of("CLAUDE_CODE_CHILD_SESSION", "true"), false, false, false),
            "released z9e only applies the child-session gate in interactive mode");
    }

    @Test
    void legacySearchReaderIsGlobalUnboundedLazyAndDeduplicatesAtSearchTime(
            @TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("history.jsonl");
        StringBuilder jsonl = new StringBuilder();
        jsonl.append(jsonEntry("target beyond project window", "other", 1)
            .replace(PROJ, "/another-project"));
        for (int i = 2; i <= 130; i++) {
            jsonl.append(jsonEntry(i % 2 == 0 ? "duplicate" : "filler-" + i,
                "other", i));
        }
        Files.writeString(file, jsonl);
        PromptHistory h = history(tmp, 60_000);

        try (PromptHistory.HistoryReader reader = h.openGlobalHistoryReader()) {
            Set<String> seen = new LinkedHashSet<>();
            assertEquals("duplicate", reader.findNextAsync("duplicate", seen).join().display());
            assertNull(reader.findNextAsync("duplicate", seen).join(),
                "repeated displays are skipped rather than wrapped");
        }
        try (PromptHistory.HistoryReader reader = h.openGlobalHistoryReader()) {
            assertEquals("target beyond project window",
                reader.findNextAsync("target beyond", new LinkedHashSet<>()).join().display());
        }
    }

    @AfterEach
    void closeHistories() {
        histories.forEach(PromptHistory::close);
    }

    /** addEntry is async-flushed; wait for the entry to land on disk. */
    private static void awaitFlushed(Path tmp, String needle) throws Exception {
        Path f = tmp.resolve("history.jsonl");
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(f) && Strings.CS.contains(Files.readString(f), needle)) return;
            Thread.sleep(10);
        }
        throw new AssertionError("entry never flushed to disk: " + needle);
    }



    @Test
    void successfulFlushCyclesPreserveEveryEntry(@TempDir Path tmp) {
        PromptHistory h = history(tmp, 60_000);
        for (int i = 0; i < 20; i++) {
            h.addEntry("cmd-" + i, SID, PROJ);
        }
        h.flushPending();
        assertEquals(20, displays(h, 100).size());
    }

    @Test
    void flushPending_writesToDisk_andDrainsBuffer(@TempDir Path tmp) throws Exception {
        PromptHistory h = history(tmp);
        h.addEntry("hello world", SID, PROJ);
        awaitFlushed(tmp, "hello world");

        // Fresh instance reading the same file → entry came from disk.
        PromptHistory h2 = history(tmp);
        assertEquals(List.of("hello world"), displays(h2, 10));
    }

    @Test
    void addEntryDefersHistoryIoPastTheSubmissionFrame(@TempDir Path tmp) {
        PromptHistory h = history(tmp, 60_000);

        h.addEntry("render the echo first", SID, PROJ);

        assertFalse(Files.exists(tmp.resolve("history.jsonl")),
            "Enter should only enqueue history; JSON serialization and file locking run later");
        assertEquals(List.of("render the echo first"), displays(h, 10));
    }

    @Test
    void concurrentExplicitFlushes_neverDuplicateNorHideEntries(@TempDir Path tmp)
            throws Exception {
// The shutdown hook can call flushPending while an async flush is in flight; the flush lock

        PromptHistory h = history(tmp);
        for (int i = 0; i < 10; i++) {
            h.addEntry("dup-" + i, SID, PROJ);
        }
        Thread a = Thread.ofVirtual().start(h::flushPending);
        Thread b = Thread.ofVirtual().start(h::flushPending);
        a.join(5_000);
        b.join(5_000);

        assertEquals(10, displays(h, 100).size(),
            "all entries visible after concurrent flushes");
        long diskLines = Files.readAllLines(tmp.resolve("history.jsonl")).stream()
            .filter(l -> !StringUtils.isBlank(l)).count();
        assertEquals(10, diskLines, "entries written exactly once");
    }

    @Test
    void removeLastEntry_beforeFlush_fastPathDropsFromBuffer(@TempDir Path tmp) {
        PromptHistory h = history(tmp, 60_000);
        h.addEntry("keep me", SID, PROJ);
        h.addEntry("pop me", SID, PROJ);
        h.removeLastEntry();

        List<String> got = displays(h, 10);
        assertTrue(got.contains("keep me"));
        assertFalse(got.contains("pop me"));
    }

    @Test
    void close_waitsForAsyncFlush_andLeavesNoBackgroundWriter(@TempDir Path tmp)
            throws Exception {
        PromptHistory h = history(tmp);
        h.addEntry("written before close", SID, PROJ);

        h.close();

        assertTrue(Strings.CS.contains(Files.readString(tmp.resolve("history.jsonl")),
            "written before close"));
        Files.delete(tmp.resolve("history.jsonl"));
        Thread.sleep(20);
        assertFalse(Files.exists(tmp.resolve("history.jsonl")),
            "a closed history must not recreate its file from a late async flush");
    }

    @Test
    void contendedHistoryLock_isBounded_andKeepsEntryReadable(@TempDir Path tmp)
            throws Exception {
        Path file = tmp.resolve("history.jsonl");
        Files.createFile(file);
        try (FileChannel holder = FileChannel.open(file, StandardOpenOption.WRITE);
             FileLock ignored = holder.lock()) {
            PromptHistory h = history(tmp);
            h.addEntry("still pending", SID, PROJ);

            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread closer = Thread.ofVirtual()
                .uncaughtExceptionHandler((_, error) -> failure.set(error))
                .start(h::close);
            closer.join(1_000);

            assertFalse(closer.isAlive(),
                "history cleanup must not deadlock behind another process's file lock");
            assertNull(failure.get(), "lock contention must remain a recoverable I/O failure");
            assertEquals(List.of("still pending"),
                displays(h, 10));
        }
    }

    @Test
    void failureBeforeHistoryLockPreservesThePendingEntries(@TempDir Path tmp)
            throws Exception {
// Point the history "file" at a directory.
        Path asDir = tmp.resolve("history.jsonl");
        Files.createDirectories(asDir);
        PromptHistory h = new PromptHistory(asDir);
        histories.add(h);
        h.addEntry("survives io failure", SID, PROJ);
        h.flushPending();

        assertEquals(List.of("survives io failure"), displays(h, 10));
    }

    @Test
    void arrowHistoryPreservesDuplicateDisplaysAndCountsThemTowardTheLimit(
            @TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("history.jsonl");
        StringBuilder jsonl = new StringBuilder();
        jsonl.append(jsonEntry("older unique", "other", 1));
        for (int i = 2; i <= 11; i++) {
            jsonl.append(jsonEntry("repeat hatch", "other", i));
        }
        Files.writeString(file, jsonl);

        PromptHistory h = history(tmp, 60_000);

        assertEquals(Collections.nCopies(10, "repeat hatch"),
            h.getEntriesWithPasted(10, PROJ, SID, null).stream()
                .map(PromptHistory.Entry::display).toList());
    }

    @Test
    void arrowHistoryPrioritizesCurrentSessionWithinTheReleasedHundredEntryWindow(
            @TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("history.jsonl");
        StringBuilder jsonl = new StringBuilder();
        jsonl.append(jsonEntry("current session", SID, 1));
        for (int i = 2; i <= 11; i++) {
            jsonl.append(jsonEntry("other-" + i, "other", i));
        }
        Files.writeString(file, jsonl);

        PromptHistory h = history(tmp, 60_000);
        List<String> entries = h.getEntriesWithPasted(10, PROJ, SID, null).stream()
            .map(PromptHistory.Entry::display).toList();

        assertEquals("current session", entries.getFirst());
        assertEquals(10, entries.size());
    }

    @Test
    void bashHistoryFiltersOnlyInsideTheReleasedHundredEntryWindow(@TempDir Path tmp)
            throws Exception {
        Path file = tmp.resolve("history.jsonl");
        StringBuilder jsonl = new StringBuilder();
        jsonl.append(jsonEntry("!older-than-window", "other", 1));
        for (int i = 2; i <= 101; i++) {
            jsonl.append(jsonEntry("prompt-" + i, "other", i));
        }
        Files.writeString(file, jsonl);

        PromptHistory h = history(tmp, 60_000);

        assertTrue(h.getEntriesWithPasted(10, PROJ, SID, "!").isEmpty());
    }

    @Test
    void properLockfileDirectoryPreventsAConcurrentHistoryAppend(@TempDir Path tmp)
            throws Exception {
        Path file = tmp.resolve("history.jsonl");
        Files.createFile(file);
        Path lockDirectory = tmp.resolve("history.jsonl.lock");
        Files.createDirectory(lockDirectory);
        PromptHistory h = history(tmp, 60_000);
        h.addEntry("still pending", SID, PROJ);

        h.flushPending();

        assertEquals("", Files.readString(file));
        assertEquals(List.of("still pending"), displays(h, 10),
            "released Ozi retains kue when proper-lockfile acquisition fails");
    }

    @Test
    void entriesAddedWhileWaitingForTheHistoryLockJoinTheReleasedBatch(
            @TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("history.jsonl");
        Files.createFile(file);
        Path lockDirectory = tmp.resolve("history.jsonl.lock");
        Files.createDirectory(lockDirectory);
        PromptHistory h = history(tmp, 60_000);
        h.addEntry("before lock wait", SID, PROJ);

        Thread flush = Thread.ofVirtual().start(h::flushPending);
        Thread.sleep(75);
        h.addEntry("during lock wait", SID, PROJ);
        Files.delete(lockDirectory);
        flush.join(5_000);

        assertFalse(flush.isAlive());
        assertEquals(List.of("before lock wait", "during lock wait"),
            Files.readAllLines(file).stream()
                .filter(line -> !StringUtils.isBlank(line))
                .map(line -> {
                    try {
                        return JsonUtils.getMapper()
                            .readTree(line).path("display").asText();
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                })
                .toList());
    }

    @Test
    void timestampedMetadataDoesNotResolvePastedContentsUntilSelected() {
        AtomicInteger resolutions = new AtomicInteger();
        PromptHistory.Entry resolved =
            new PromptHistory.Entry("paste", SID, 1L, PROJ, PROJ, Map.of());
        PromptHistory.TimestampedEntry metadata = PromptHistory.TimestampedEntry.deferred(
            "paste", 1L, () -> {
                resolutions.incrementAndGet();
                return CompletableFuture.completedFuture(resolved);
            });

        assertEquals("paste", metadata.display());
        assertEquals(1L, metadata.timestamp());
        assertEquals(0, resolutions.get());
        assertEquals(resolved, metadata.resolveAsync().join());
        assertEquals(1, resolutions.get());
    }

    @Test
    void pickerDeduplicatesDisplaysWhileArrowHistoryDoesNot(@TempDir Path tmp)
            throws Exception {
        Path file = tmp.resolve("history.jsonl");
        Files.writeString(file,
            jsonEntry("repeat", "other", 1) + jsonEntry("repeat", "other", 2));
        PromptHistory h = history(tmp, 60_000);

        assertEquals(List.of("repeat", "repeat"), displays(h, 10));
        assertEquals(List.of("repeat"), h.getTimestampedEntries(
                PromptHistory.HistoryScope.PROJECT, PROJ, SID).stream()
            .map(PromptHistory.TimestampedEntry::display).toList());
    }

    @Test
    void staleProperLockfileDirectoryIsRecovered(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("history.jsonl");
        Files.createFile(file);
        Path lockDirectory = tmp.resolve("history.jsonl.lock");
        Files.createDirectory(lockDirectory);
        Files.setLastModifiedTime(lockDirectory,
            FileTime.fromMillis(System.currentTimeMillis() - 20_000));
        PromptHistory h = history(tmp, 60_000);
        h.addEntry("after stale lock", SID, PROJ);

        h.flushPending();

        assertTrue(Strings.CS.contains(Files.readString(file), "after stale lock"));
        assertFalse(Files.exists(lockDirectory));
    }

    @Test
    void consecutivePlainDuplicatesAreSuppressed(@TempDir Path tmp) {
        PromptHistory h = history(tmp, 60_000);

        h.addEntry("pokemon hatch", SID, PROJ);
        h.addEntry("pokemon hatch", SID, PROJ);

        assertEquals(List.of("pokemon hatch"), displays(h, 10));
    }

    @Test
    void removingASuppressedDuplicateDoesNotRemoveThePreviousRealEntry(@TempDir Path tmp) {
        PromptHistory h = history(tmp, 60_000);

        h.addEntry("pokemon hatch", SID, PROJ);
        h.addEntry("pokemon hatch", SID, PROJ);
        h.removeLastEntry();

        assertEquals(List.of("pokemon hatch"), displays(h, 10),
            "released 2.1.197 consumes the duplicate-suppression latch first");
    }

    @Test
    void historyCountUsesTheReleasedPhysicalHundredEntryProjectWindow(@TempDir Path tmp)
            throws Exception {
        Path file = tmp.resolve("history.jsonl");
        StringBuilder jsonl = new StringBuilder();
        jsonl.append(jsonEntry("!older bash", "other", 1));
        for (int i = 2; i <= 101; i++) {
            jsonl.append(jsonEntry(i % 2 == 0 ? "!bash-" + i : "prompt-" + i,
                "other", i));
        }
        Files.writeString(file, jsonl);
        PromptHistory h = history(tmp, 60_000);

        assertEquals(100, h.countEntries(PROJ, null));
        assertEquals(50, h.countEntries(PROJ, "!"));
    }

    @Test
    void duplicateWithPastedContentsIsNotSuppressed(@TempDir Path tmp) {
        PromptHistory h = history(tmp, 60_000);

        h.addEntry("inspect [Pasted text #1]", SID, PROJ,
            Map.of(1, PastedContent.text(1, "first")));
        h.addEntry("inspect [Pasted text #1]", SID, PROJ,
            Map.of(1, PastedContent.text(1, "second")));

        assertEquals(2, displays(h, 10).size());
    }

    @Test
    void storedDisplayPreservesLeadingAndTrailingWhitespace(@TempDir Path tmp) {
        PromptHistory h = history(tmp, 60_000);

        h.addEntry("  exact input  ", SID, PROJ);

        assertEquals(List.of("  exact input  "), displays(h, 10));
    }

    @Test
    void projectMatchingIsExactAndOldEntriesWithoutProjectAreSkipped(@TempDir Path tmp)
            throws Exception {
        Path file = tmp.resolve("history.jsonl");
        Files.writeString(file,
            """
            {"display":"trailing slash","sessionId":"other","timestamp":1,\
            "project":"/proj/","cwd":"/proj"}
            {"display":"missing project","sessionId":"other",\
            "timestamp":2,"cwd":"/proj"}
            """);
        PromptHistory h = history(tmp, 60_000);

        assertTrue(displays(h, 10).isEmpty());
    }

    @Test
    void timestampedPickerSupportsReleasedScopes(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("history.jsonl");
        Files.writeString(file,
            jsonEntry("other project", "other", 1, "/elsewhere")
                + jsonEntry("same project", "other", 2)
                + jsonEntry("same session", SID, 3));
        PromptHistory h = history(tmp, 60_000);

        assertEquals(List.of("same session"),
            h.getTimestampedEntries(PromptHistory.HistoryScope.SESSION, PROJ, SID).stream()
                .map(PromptHistory.TimestampedEntry::display).toList());
        assertEquals(List.of("same session", "same project"),
            h.getTimestampedEntries(PromptHistory.HistoryScope.PROJECT, PROJ, SID).stream()
                .map(PromptHistory.TimestampedEntry::display).toList());
        assertEquals(List.of("same session", "same project", "other project"),
            h.getTimestampedEntries(PromptHistory.HistoryScope.EVERYWHERE, PROJ, SID).stream()
                .map(PromptHistory.TimestampedEntry::display).toList());
        assertEquals(PromptHistory.HistoryScope.SESSION,
            PromptHistory.HistoryScope.EVERYWHERE.next());
    }

    @Test
    void removedEntryIdentityIncludesSessionId(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("history.jsonl");
        PromptHistory h = history(tmp, 60_000);
        h.addEntry("removed", SID, PROJ);
        long timestamp = h.getEntriesWithPasted(1, PROJ, SID, null).getFirst().timestamp();
        h.flushPending();
        Files.writeString(file, jsonEntry("same millisecond other session", "other", timestamp),
            StandardOpenOption.APPEND);

        h.removeLastEntry();

        assertEquals(List.of("same millisecond other session"), displays(h, 10));
    }

    @Test
    void unavailableStoredTextPasteRewritesItsReference(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("history.jsonl");
        Files.writeString(file,
            "{\"display\":\"inspect [Pasted text #7 +2 lines]\","
                + "\"sessionId\":\"other\",\"timestamp\":1,\"project\":\"/proj\","
                + "\"pastedContents\":{\"7\":{\"id\":7,\"type\":\"text\"}}}\n");
        PromptHistory h = history(tmp, 60_000);

        PromptHistory.Entry entry = h.getEntriesWithPasted(10, PROJ, SID, null).getFirst();

        assertEquals("inspect [Pasted text #7 — content no longer available]", entry.display());
        assertTrue(entry.pastedContents().isEmpty());
    }

    private static String jsonEntry(String display, String sessionId, long timestamp) {
        return jsonEntry(display, sessionId, timestamp, PROJ);
    }

    private static String jsonEntry(
            String display, String sessionId, long timestamp, String project) {
        return "{\"display\":\"" + display + "\",\"sessionId\":\"" + sessionId
            + "\",\"timestamp\":" + timestamp + ",\"project\":\"" + project
            + "\",\"cwd\":\"" + project + "\"}\n";
    }
}
