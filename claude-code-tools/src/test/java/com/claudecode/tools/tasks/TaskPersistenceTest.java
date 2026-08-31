package com.claudecode.tools.tasks;

import org.apache.commons.lang3.Strings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskPersistenceTest {

    @TempDir
    Path tmp;

    @Test
    void sanitizePathComponentMatchesTsAllowList() {
        assertEquals("default", TaskPersistence.sanitizePathComponent(null));
        assertEquals("team-a_1", TaskPersistence.sanitizePathComponent("team-a_1"));
        assertEquals("---etc-passwd", TaskPersistence.sanitizePathComponent("../etc/passwd"));
    }

    @Test
    void nextSequentialIdUsesFilesAndHighWaterMark() throws IOException {
        Files.createDirectories(tmp);
        Files.writeString(tmp.resolve("7.json"), "{}");
        Files.writeString(tmp.resolve("not-an-id.json"), "{}");
        Files.writeString(tmp.resolve(".highwatermark"), "9");

        assertEquals("10", TaskPersistence.nextSequentialId(tmp));
        assertEquals("10", Files.readString(tmp.resolve(".highwatermark")));
    }

    @Test
    void sequentialIdsUseJavascriptNumberRangeAndFormattingBeyondLong() throws IOException {
        Files.writeString(tmp.resolve("1000000000000000000000.json"), "{}");

        assertEquals("1e+21", TaskPersistence.nextSequentialId(tmp));
        assertEquals("1e+21", Files.readString(tmp.resolve(".highwatermark")));
    }

    @Test
    void sequentialIdsPreserveReleased197InfinityEdge() throws IOException {
        Files.writeString(tmp.resolve(".highwatermark"), "9".repeat(400));

        assertEquals("Infinity", TaskPersistence.nextSequentialId(tmp));
        assertEquals("Infinity", Files.readString(tmp.resolve(".highwatermark")));
    }

    @Test
    void listLockUsesReleased197ProperLockfileLayout() throws IOException {
        assertEquals("1", TaskPersistence.nextSequentialId(tmp));

        assertTrue(Files.isRegularFile(tmp.resolve(".lock")));
        assertFalse(Files.exists(tmp.resolve(".lock.lock")));
    }

    @Test
    void listLockMigratesUnlockedLegacyJavaArtifact() throws IOException {
        Files.writeString(tmp.resolve(".lock.lock"), "");

        assertEquals("1", TaskPersistence.nextSequentialId(tmp));

        assertFalse(Files.exists(tmp.resolve(".lock.lock")));
    }

    @Test
    void listLockWaitsForReleased197CompanionDirectory() throws Exception {
        Files.writeString(tmp.resolve(".lock"), "");
        Path companion = Files.createDirectory(tmp.resolve(".lock.lock"));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = executor.submit(() -> TaskPersistence.nextSequentialId(tmp));

            assertThrows(TimeoutException.class,
                () -> pending.get(100, TimeUnit.MILLISECONDS));
            Files.delete(companion);

            assertEquals("1", pending.get(3, TimeUnit.SECONDS));
        } finally {
            Files.deleteIfExists(companion);
        }
    }

    @Test
    void ensureHighWaterMarkAtLeastNeverMovesBackward() throws IOException {
        Files.writeString(tmp.resolve(".highwatermark"), "8");

        TaskPersistence.ensureHighWaterMarkAtLeast(tmp, 3);
        assertEquals("8", Files.readString(tmp.resolve(".highwatermark")));

        TaskPersistence.ensureHighWaterMarkAtLeast(tmp, 12);
        assertEquals("12", Files.readString(tmp.resolve(".highwatermark")));
    }

    @Test
    void taskListsUseReleased197JavaScriptNumberOrdering() {
        Task ten = task("10");
        Task two = task("2");
        Task hexSixteen = task("0x10");

        assertEquals(List.of("2", "10", "0x10"),
            TaskPersistence.sortLikeReleasedTaskList(List.of(ten, two, hexSixteen)).stream()
                .map(Task::id)
                .toList());
    }

    @Test
    void taskListNumberSortKeepsStableOrderAroundNaNLikeReleased197() {
        assertEquals(List.of("2stale", "10"),
            TaskPersistence.sortLikeReleasedTaskList(
                    List.of(task("2stale"), task("10"))).stream()
                .map(Task::id)
                .toList());
    }

    @Test
    void parseIntUsesTheExactEcmascriptWhitespaceSet() {
        assertEquals(12, TaskPersistence.parseIntOrZero("\u168012"));
        assertEquals(0, TaskPersistence.parseIntOrZero("\u001c12"));
        assertEquals(0, TaskPersistence.parseIntOrZero("\u008512"));
    }

    @Test
    void highWaterMarkTrimmingUsesTheExactEcmascriptWhitespaceSet() throws IOException {
        Files.writeString(tmp.resolve(".highwatermark"), "\u001c12");

        assertEquals(0, TaskPersistence.readHighWaterMark(tmp));
    }

    @Test
    void saveAndLoadJsonFilesShareMalformedFilePolicy() throws IOException {
        TaskPersistence.save(tmp, "1", new Sample("1", "ok"));
        Files.writeString(tmp.resolve("2.json"), "{bad");
        List<Path> malformed = new ArrayList<>();

        List<Sample> loaded = TaskPersistence.loadAll(
                tmp, Sample.class, (path, _) -> malformed.add(path));

        assertEquals(List.of(new Sample("1", "ok")), loaded);
        assertEquals(List.of(tmp.resolve("2.json")), malformed);
        assertTrue(Strings.CS.contains(Files.readString(tmp.resolve("1.json")), "\"value\":\"ok\""));
    }

    @Test
    void taskUpdateRemovesReleased197CompanionDirectory() throws IOException {
        TaskPersistence.save(tmp, "1", new Sample("1", "before"));

        var updated = TaskPersistence.update(
            tmp, "1", Sample.class, value -> new Sample(value.id(), "after"));

        assertEquals(new Sample("1", "after"), updated.orElseThrow());
        assertFalse(Files.exists(tmp.resolve("1.json.lock")));
    }

    private static Task task(String id) {
        return new Task(id, id, "", null, null, TodoStatus.PENDING,
            List.of(), List.of(), null);
    }

    private record Sample(String id, String value) {}
}
