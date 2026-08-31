package com.claudecode.tools.tasks;

import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.message.TaskReminderItem;
import com.claudecode.runtime.tasks.TaskBoardPort;
import com.claudecode.tools.ToolRegistry;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provider for task management tools.
 * <p>
 * Wires two unrelated systems into the registry: the model-facing to-do list
 * (TaskCreate/TaskGet/TaskList/TaskUpdate, backed by {@link TodoStore}) and
 * the background-task tools (TaskStop/TaskOutput, backed by {@link TaskStore}
 * / {@link TaskRegistry}). See {@link TodoStore}'s Javadoc for why these two
 * "Task" systems must never share a backing store.
 */
public class TaskToolProvider implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TaskToolProvider.class);

    private final TodoStore todoStore;
    private final TaskBoardService taskBoard;
    private final TaskBoardPort taskBoardView;
    private final TaskReminderSource taskReminderSource;
    private boolean initialized = false;

    public TaskToolProvider(TodoStore todoStore) {
        this(todoStore, () -> null);
    }

    public TaskToolProvider(TodoStore todoStore, Supplier<String> currentSessionId) {
        this.todoStore = todoStore;
        this.taskBoard = new TaskBoardService(todoStore, currentSessionId);
        this.taskBoardView = borrowedTaskBoard(taskBoard);
        this.taskReminderSource = borrowedReminderSource(taskBoard);
    }

    public TaskToolProvider(TodoStore todoStore, SessionIdentity sessionIdentity) {
        this.todoStore = todoStore;
        this.taskBoard = new TaskBoardService(todoStore, sessionIdentity);
        this.taskBoardView = borrowedTaskBoard(taskBoard);
        this.taskReminderSource = borrowedReminderSource(taskBoard);
    }

    TaskToolProvider(
            TodoStore todoStore,
            Supplier<String> currentSessionId,
            BooleanSupplier taskToolsEnabled) {
        this.todoStore = todoStore;
        this.taskBoard = new TaskBoardService(todoStore, currentSessionId, taskToolsEnabled);
        this.taskBoardView = borrowedTaskBoard(taskBoard);
        this.taskReminderSource = borrowedReminderSource(taskBoard);
    }

    public void initialize(ToolRegistry registry) {
        if (initialized) {
            LOG.warn("TaskToolProvider already initialized");
            return;
        }

// Model-facing to-do list tools share one effective session/team service.
        registry.register(new TaskCreateTool(taskBoard));
        registry.register(new TaskGetTool(taskBoard));
        registry.register(new TaskListTool(taskBoard));
        registry.register(new TaskUpdateTool(taskBoard));
        // Background-task tools use the process-wide TaskRegistry store.
        // TaskStop must read the SAME in-memory store (and live handles) as
// BashTool/AgentTool use — TaskRegistry.global — or it can never
        // find a real background task.
        registry.register(new TaskStopTool(TaskRegistry.global().store()));
// TaskOutput reads BACKGROUND task output — those tasks live only in TaskRegistry.global's
// in-memory store.
        registry.register(new TaskOutputTool(TaskRegistry.global().store()));

        initialized = true;
        LOG.info("Task tools initialized with TodoStore + shared TaskRegistry");
    }

    public TodoStore getTodoStore() {
        return todoStore;
    }

    public TaskBoardPort taskBoard() {
        return taskBoardView;
    }

    public TaskReminderSource taskReminders() {
        return taskReminderSource;
    }

    @Override
    public void close() {
        taskBoard.close();
    }

    private static TaskBoardPort borrowedTaskBoard(TaskBoardPort taskBoard) {
        return new TaskBoardPort() {
            @Override
            public Snapshot snapshot() {
                return taskBoard.snapshot();
            }

            @Override
            public AutoCloseable subscribe(Consumer<Snapshot> listener) {
                return taskBoard.subscribe(listener);
            }

            @Override
            public AutoCloseable subscribeIntents(Consumer<Intent> listener) {
                return taskBoard.subscribeIntents(listener);
            }
        };
    }

    private static TaskReminderSource borrowedReminderSource(TaskBoardService taskBoard) {
        return () -> taskBoard.currentTasks().stream()
            .map(TaskToolProvider::toReminderItem)
            .toList();
    }

    private static TaskReminderItem toReminderItem(Task task) {
        return new TaskReminderItem(
            task.id(), task.subject(), task.description(),
            task.activeForm().orElse(null), task.owner().orElse(null),
            task.status().wireValue(), task.blocks(), task.blockedBy(),
            task.hasMetadata() ? task.metadata() : null);
    }
}
