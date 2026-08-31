package com.claudecode.tools.tasks;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoStorePathTest {

    private static final class CleanupFailingTodoStore extends TodoStore {
        private boolean failUpdates;

        private CleanupFailingTodoStore(Path tasksBase) {
            super(tasksBase, "session");
        }

        @Override
        synchronized Optional<Task> updateAtomically(
                String taskId, UnaryOperator<Task> updater) {
            if (failUpdates) {
                throw new IllegalStateException("simulated relationship cleanup failure");
            }
            return super.updateAtomically(taskId, updater);
        }
    }

    @Test
    void loadsReleased197TaskJsonWithLowercaseStatusAndOmittedOptionalFields(
            @TempDir Path configHome) throws IOException {
        Path tasksBase = configHome.resolve("tasks");
        Path taskDir = tasksBase.resolve("session");
        Files.createDirectories(taskDir);
        Files.writeString(taskDir.resolve("1.json"), """
            {
              "id": "1",
              "subject": "Official task",
              "description": "Written by Claude Code 2.1.197",
              "status": "pending",
              "blocks": [],
              "blockedBy": []
            }
            """);

        TodoStore store = new TodoStore(tasksBase, "session");

        Task task = store.get("1").orElseThrow();
        assertEquals(TodoStatus.PENDING, task.status());
        assertTrue(task.activeForm().isEmpty());
        assertTrue(task.owner().isEmpty());
        assertEquals(Map.of(), task.metadata());
    }

    @Test
    void addressesTasksByFileNameButPreservesEmbeddedIdUntilUpdateLikeReleased197(
            @TempDir Path configHome) throws IOException {
        Path tasksBase = configHome.resolve("tasks");
        Path taskDir = tasksBase.resolve("session");
        Files.createDirectories(taskDir);
        Files.writeString(taskDir.resolve("1.json"), """
            {
              "id": "99",
              "subject": "Mismatched id",
              "description": "Addressed through 1.json",
              "status": "pending",
              "blocks": [],
              "blockedBy": []
            }
            """);

        TodoStore store = new TodoStore(tasksBase, "session");

        Task loaded = store.get("1").orElseThrow();
        assertEquals("99", loaded.id());
        assertTrue(store.get("99").isEmpty());
        assertEquals(List.of("99"), store.list().stream().map(Task::id).toList());

        Task updated = store.update("1", loaded.withSubject("Rewritten")).orElseThrow();

        assertEquals("1", updated.id());
        assertEquals("1", store.get("1").orElseThrow().id());
        assertEquals("1", JsonUtils.parseTree(
            Files.readString(taskDir.resolve("1.json"))).path("id").asText());
    }

    @Test
    void writesTaskJsonUsingReleased197WireFormat(@TempDir Path configHome) throws IOException {
        Path tasksBase = configHome.resolve("tasks");
        TodoStore store = new TodoStore(tasksBase, "session");

        Task created = store.create("Java task", "Readable by 2.1.197", null, null);

        String raw = Files.readString(
            tasksBase.resolve("session").resolve(created.id() + ".json"));
        JsonNode persisted = JsonUtils.parseTree(raw);
        assertTrue(raw.contains("\n  \"id\""));
        assertEquals("pending", persisted.path("status").asText());
        assertFalse(persisted.has("activeForm"));
        assertFalse(persisted.has("owner"));
        assertFalse(persisted.has("metadata"));
        assertTrue(persisted.path("blocks").isArray());
        assertTrue(persisted.path("blockedBy").isArray());
    }

    @Test
    void skipsTaskFilesThatFailTheReleased197Schema(@TempDir Path configHome) throws IOException {
        Path taskDir = configHome.resolve("tasks").resolve("session");
        Files.createDirectories(taskDir);
        Files.writeString(taskDir.resolve("1.json"), """
            {
              "id": "1",
              "subject": "valid",
              "description": "kept",
              "status": "pending",
              "blocks": [],
              "blockedBy": []
            }
            """);
        Files.writeString(taskDir.resolve("2.json"), """
            {
              "id": "2",
              "subject": "missing blockedBy",
              "description": "invalid",
              "status": "pending",
              "blocks": []
            }
            """);
        Files.writeString(taskDir.resolve("3.json"), """
            {
              "id": "3",
              "subject": "null optional",
              "description": "invalid",
              "owner": null,
              "status": "pending",
              "blocks": [],
              "blockedBy": []
            }
            """);
        Files.writeString(taskDir.resolve("4.json"), """
            {
              "id": "4",
              "subject": "coerced array item",
              "description": "invalid",
              "status": "pending",
              "blocks": [2],
              "blockedBy": []
            }
            """);

        TodoStore store = new TodoStore(configHome.resolve("tasks"), "session");

        assertEquals(List.of("valid"), store.list().stream().map(Task::subject).toList());
    }

    @Test
    void createUsesHighWaterMarkWithoutAdvancingItLikeReleased197(
            @TempDir Path configHome) throws IOException {
        Path tasksBase = configHome.resolve("tasks");
        Path taskDir = tasksBase.resolve("session");
        Files.createDirectories(taskDir);
        Files.writeString(taskDir.resolve(".highwatermark"), "9");
        TodoStore store = new TodoStore(tasksBase, "session");

        Task created = store.create("next", "created under the list lock", null, Map.of());

        assertEquals("10", created.id());
        assertTrue(Files.isRegularFile(taskDir.resolve("10.json")));
        assertEquals("9", Files.readString(taskDir.resolve(".highwatermark")));
    }

    @Test
    void createUsesJavascriptParseIntPrefixesFromTaskFileNames(
            @TempDir Path configHome) throws IOException {
        Path tasksBase = configHome.resolve("tasks");
        Path taskDir = tasksBase.resolve("session");
        Files.createDirectories(taskDir);
        Files.writeString(taskDir.resolve("12stale.json"), """
            {
              "id": "12stale",
              "subject": "Legacy malformed id",
              "description": "Still advances the numeric sequence",
              "status": "pending",
              "blocks": [],
              "blockedBy": []
            }
            """);

        TodoStore store = new TodoStore(tasksBase, "session");

        assertEquals("13", store.create("next", "", null, null).id());
    }

    @Test
    void createUsesJavascriptParseIntPrefixFromHighWaterMark(
            @TempDir Path configHome) throws IOException {
        Path tasksBase = configHome.resolve("tasks");
        Path taskDir = tasksBase.resolve("session");
        Files.createDirectories(taskDir);
        Files.writeString(taskDir.resolve(".highwatermark"), "12stale");

        TodoStore store = new TodoStore(tasksBase, "session");

        assertEquals("13", store.create("next", "", null, null).id());
    }

    @Test
    void createPreservesNullMetadataValuesAcceptedByReleased197(@TempDir Path configHome) {
        TodoStore store = new TodoStore(configHome.resolve("tasks"), "session");
        java.util.LinkedHashMap<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("nullable", null);

        Task created = store.create("metadata", "keep JSON null", null, metadata);

        assertTrue(created.metadata().containsKey("nullable"));
        assertEquals(null, created.metadata().get("nullable"));
    }

    @Test
    void explicitEmptyMetadataRemainsPresentWhileAbsentMetadataIsOmitted(
            @TempDir Path configHome) throws IOException {
        Path tasksBase = configHome.resolve("tasks");
        TodoStore store = new TodoStore(tasksBase, "session");

        Task absent = store.create("absent", "", null, null);
        Task present = store.create("present", "", null, Map.of());

        JsonNode absentJson = JsonUtils.parseTree(Files.readString(
            tasksBase.resolve("session").resolve(absent.id() + ".json")));
        JsonNode presentJson = JsonUtils.parseTree(Files.readString(
            tasksBase.resolve("session").resolve(present.id() + ".json")));
        assertFalse(absentJson.has("metadata"));
        assertTrue(presentJson.path("metadata").isObject());
        assertTrue(presentJson.path("metadata").isEmpty());
    }

    @Test
    void deleteKeepsTaskWhenHighWaterMarkCannotBeWrittenLikeReleased197(
            @TempDir Path configHome) throws IOException {
        Path tasksBase = configHome.resolve("tasks");
        TodoStore store = new TodoStore(tasksBase, "session");
        Task created = store.create("keep", "highwater failure must abort deletion", null, null);
        Path taskDir = tasksBase.resolve("session");
        Files.createDirectory(taskDir.resolve(".highwatermark"));

        assertFalse(store.delete(created.id()));
        assertTrue(store.get(created.id()).isPresent());
        assertTrue(Files.isRegularFile(taskDir.resolve(created.id() + ".json")));
    }

    @Test
    void deleteReturnsFalseWhenRelationshipCleanupFailsAfterDeletionLikeReleased197(
            @TempDir Path configHome) {
        Path tasksBase = configHome.resolve("tasks");
        CleanupFailingTodoStore store = new CleanupFailingTodoStore(tasksBase);
        Task blocker = store.create("blocker", "delete me", null, null);
        Task blocked = store.create("blocked", "retain stale relation on failure", null, null);
        assertTrue(store.block(blocker.id(), blocked.id()));
        store.failUpdates = true;

        assertFalse(store.delete(blocker.id()));
        assertTrue(store.get(blocker.id()).isEmpty(),
            "official deletion has already happened before cleanup fails");
        assertFalse(Files.exists(tasksBase.resolve("session").resolve(blocker.id() + ".json")));
    }

    @Test
    void persistsModelTasksUnderConfigTasksDirectory(@TempDir Path configHome) {
        TodoStore store = new TodoStore(configHome.resolve("tasks"), "team/session");

        Task created = store.create("subject", "description", null, Map.of());

        assertTrue(Files.isRegularFile(
            configHome.resolve("tasks").resolve("team-session").resolve(created.id() + ".json")));
    }

    @Test
    void reloadReplacesCachedTasksInsteadOfMergingThem(@TempDir Path configHome) {
        Path tasksBase = configHome.resolve("tasks");
        TodoStore first = new TodoStore(tasksBase, "session");
        Task stale = first.create("stale", "old", null, Map.of());
        TodoStore second = new TodoStore(tasksBase, "session");
        assertTrue(second.delete(stale.id()));
        Task current = second.create("current", "new", null, Map.of());

        first.reload();

        assertTrue(first.get(stale.id()).isEmpty());
        assertEquals(List.of(current.id()), first.list().stream().map(Task::id).toList());
    }

    @Test
    void deleteReturnsFalseWhenAnotherProcessAlreadyRemovedTheTaskFile(
            @TempDir Path configHome) throws IOException {
        Path tasksBase = configHome.resolve("tasks");
        TodoStore store = new TodoStore(tasksBase, "session");
        Task task = store.create("stale", "removed externally", null, Map.of());
        Files.delete(tasksBase.resolve("session").resolve(task.id() + ".json"));

        assertFalse(store.delete(task.id()));
        assertTrue(store.get(task.id()).isEmpty());
    }

    @Test
    void resetClearsTaskFilesButPreservesHighWaterMark(@TempDir Path configHome) {
        Path tasksBase = configHome.resolve("tasks");
        TodoStore store = new TodoStore(tasksBase, "session");
        Task first = store.create("first", "one", null, Map.of());
        Task second = store.create("second", "two", null, Map.of());
        Path taskDir = tasksBase.resolve("session");

        assertTrue(store.reset());

        assertTrue(store.list().isEmpty());
        assertFalse(Files.exists(taskDir.resolve(first.id() + ".json")));
        assertFalse(Files.exists(taskDir.resolve(second.id() + ".json")));
        assertEquals("3", store.create("third", "three", null, Map.of()).id());
    }
}
