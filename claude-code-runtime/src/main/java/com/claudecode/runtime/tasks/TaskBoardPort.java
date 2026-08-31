package com.claudecode.runtime.tasks;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Application port exposing immutable task-board snapshots and UI intents. */
public interface TaskBoardPort {

    enum Status {
        PENDING,
        IN_PROGRESS,
        COMPLETED
    }

    enum Intent {
        EXPAND_TASKS
    }

    record TaskItem(
            String id,
            String subject,
            String description,
            String activeForm,
            String owner,
            Status status,
            List<String> blocks,
            List<String> blockedBy) {
        public TaskItem {
            id = Objects.requireNonNull(id, "id");
            subject = Objects.requireNonNullElse(subject, "");
            description = Objects.requireNonNullElse(description, "");
            status = Objects.requireNonNull(status, "status");
            blocks = List.copyOf(blocks == null ? List.of() : blocks);
            blockedBy = List.copyOf(blockedBy == null ? List.of() : blockedBy);
        }
    }

    record Snapshot(String listId, long revision, List<TaskItem> tasks, boolean hidden) {
        public static final Snapshot EMPTY = new Snapshot("", 0L, List.of(), true);

        public Snapshot {
            listId = Objects.requireNonNullElse(listId, "");
            tasks = List.copyOf(tasks == null ? List.of() : tasks);
        }
    }

    Snapshot snapshot();

    AutoCloseable subscribe(Consumer<Snapshot> listener);

    AutoCloseable subscribeIntents(Consumer<Intent> listener);

    static TaskBoardPort none() {
        return EmptyTaskBoardPort.INSTANCE;
    }

    final class EmptyTaskBoardPort implements TaskBoardPort {
        private static final EmptyTaskBoardPort INSTANCE = new EmptyTaskBoardPort();
        private static final AutoCloseable NOOP_SUBSCRIPTION = () -> { };

        private EmptyTaskBoardPort() {}

        @Override
        public Snapshot snapshot() {
            return Snapshot.EMPTY;
        }

        @Override
        public AutoCloseable subscribe(Consumer<Snapshot> listener) {
            Objects.requireNonNull(listener, "listener");
            return NOOP_SUBSCRIPTION;
        }

        @Override
        public AutoCloseable subscribeIntents(Consumer<Intent> listener) {
            Objects.requireNonNull(listener, "listener");
            return NOOP_SUBSCRIPTION;
        }
    }
}
