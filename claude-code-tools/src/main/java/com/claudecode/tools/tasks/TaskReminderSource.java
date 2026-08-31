package com.claudecode.tools.tasks;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.message.TaskReminderItem;
import java.util.List;

/** Non-owning view of the current task list used by model-facing reminders. */
@Explanation("Separates borrowed reminder reads from the Java-owned task-board lifecycle")
@FunctionalInterface
public interface TaskReminderSource {

    /** Returns the current effective session or team task list. */
    List<TaskReminderItem> currentReminders();
}
