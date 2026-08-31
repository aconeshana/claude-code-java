package com.claudecode.core.attachment;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.AttachmentPayload;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.TaskReminderAttachment;
import com.claudecode.core.message.TaskReminderItem;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.process.SubprocessEnvironment;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Timed TaskCreate/TaskUpdate reminder used when the v2 task tools are enabled. */
public final class TaskReminderAttachmentProvider implements AttachmentProvider {

    private static final int TURNS_SINCE_MANAGEMENT = 10;
    private static final int TURNS_BETWEEN_REMINDERS = 10;
    private static final String TASK_CREATE = "TaskCreate";
    private static final String TASK_UPDATE = "TaskUpdate";
    private static final String BRIEF_TOOL = "SendUserMessage";

    private final Supplier<List<TaskReminderItem>> tasksSupplier;
    private final BooleanSupplier remindersEnabled;

    public TaskReminderAttachmentProvider(Supplier<List<TaskReminderItem>> tasksSupplier) {
        this(tasksSupplier, TaskReminderAttachmentProvider::remindersEnabledByMode);
    }

    public TaskReminderAttachmentProvider(
            Supplier<List<TaskReminderItem>> tasksSupplier,
            BooleanSupplier remindersEnabled) {
        this.tasksSupplier = Objects.requireNonNull(tasksSupplier, "tasksSupplier");
        this.remindersEnabled = Objects.requireNonNull(remindersEnabled, "remindersEnabled");
    }

    @Override
    public String name() {
        return "task_reminder";
    }

    @Override
    public List<AttachmentPayload> collect(AttachmentContext ctx) {
        List<String> toolNames = ctx.toolNames();
        if (toolNames == null || toolNames.stream().noneMatch(TASK_UPDATE::equals)
                || toolNames.stream().anyMatch(BRIEF_TOOL::equals)
                || !remindersEnabled.getAsBoolean()) {
            return List.of();
        }
        List<Message> messages = ctx.messages();
        if (messages == null || messages.isEmpty()) return List.of();
        int[] counts = reminderTurnCounts(messages);
        if (counts[0] < TURNS_SINCE_MANAGEMENT
                || counts[1] < TURNS_BETWEEN_REMINDERS) {
            return List.of();
        }
        List<TaskReminderItem> tasks = tasksSupplier.get();
        if (tasks == null) tasks = List.of();
        return List.of(new TaskReminderAttachment(tasks, tasks.size()));
    }

    private static int[] reminderTurnCounts(List<Message> messages) {
        int lastManagementIndex = -1;
        int lastReminderIndex = -1;
        int sinceManagement = 0;
        int sinceReminder = 0;
        for (int index = messages.size() - 1; index >= 0; index--) {
            Message message = messages.get(index);
            if (message instanceof AttachmentMessage attachment
                    && attachment.payload() instanceof TaskReminderAttachment
                    && lastReminderIndex == -1) {
                lastReminderIndex = index;
            }
            if (message instanceof AssistantMessage assistant) {
                if (MessageConstants.isThinkingMessage(assistant)) continue;
                if (lastManagementIndex == -1) {
                    AssistantContent content = assistant.message();
                    if (content != null && content.content() != null
                            && content.content().stream().anyMatch(block ->
                                block instanceof ToolUseBlock toolUse
                                    && (TASK_CREATE.equals(toolUse.name())
                                        || TASK_UPDATE.equals(toolUse.name())))) {
                        lastManagementIndex = index;
                    }
                }
                if (lastManagementIndex == -1) sinceManagement++;
                if (lastReminderIndex == -1) sinceReminder++;
            }
            if (lastManagementIndex != -1 && lastReminderIndex != -1) break;
        }
        return new int[] {sinceManagement, sinceReminder};
    }

    private static boolean remindersEnabledByMode() {
        return !"off".equals(
            SubprocessEnvironment.get("CLAUDE_CODE_TODO_REMINDER_MODE"));
    }
}
