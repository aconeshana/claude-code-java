package com.claudecode.core.attachment;
import com.claudecode.core.process.SubprocessEnvironment;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.AttachmentPayload;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.TodoItem;
import com.claudecode.core.message.TodoReminderAttachment;
import com.claudecode.core.message.ToolUseBlock;

/**
 * Nudges the model to use the TodoWrite tool when it has been a while since the last write and the
 * last reminder.
 */
public final class TodoReminderAttachmentProvider implements AttachmentProvider {

    private static final int TURNS_SINCE_WRITE = 10;
    private static final int TURNS_BETWEEN_REMINDERS = 10;
    private static final String TODO_WRITE_TOOL = "TodoWrite";
    private static final String BRIEF_TOOL = "SendUserMessage";
    private final BooleanSupplier remindersEnabled;

    public TodoReminderAttachmentProvider() {
        this(() -> !"off".equals(
            SubprocessEnvironment.get(
                "CLAUDE_CODE_TODO_REMINDER_MODE")));
    }

    TodoReminderAttachmentProvider(BooleanSupplier remindersEnabled) {
        this.remindersEnabled = remindersEnabled;
    }

    @Override
    public String name() {
        return "todo_reminder";
    }

    @Override
    public List<AttachmentPayload> collect(AttachmentContext ctx) {
        List<String> toolNames = ctx.toolNames();
        if (toolNames == null || toolNames.stream().noneMatch(TODO_WRITE_TOOL::equals)) {
            return List.of();
        }
        // SendUserMessage (brief mode) is the primary channel; the TodoWrite nag

        if (toolNames.stream().anyMatch(BRIEF_TOOL::equals)) {
            return List.of();
        }
        if (!remindersEnabled.getAsBoolean()) return List.of();
        List<Message> messages = ctx.messages();
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int[] counts = todoReminderTurnCounts(messages);
        if (counts[0] < TURNS_SINCE_WRITE || counts[1] < TURNS_BETWEEN_REMINDERS) {
            return List.of();
        }
        List<TodoItem> todos = ctx.todos() != null ? ctx.todos() : List.of();
        return List.of(new TodoReminderAttachment(todos, todos.size()));
    }


    private static int[] todoReminderTurnCounts(List<Message> messages) {
        int lastTodoWriteIndex = -1;
        int lastReminderIndex = -1;
        int sinceWrite = 0;
        int sinceReminder = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m instanceof AttachmentMessage am && am.payload() instanceof TodoReminderAttachment) {
                if (lastReminderIndex == -1) lastReminderIndex = i;
            }
            if (m instanceof AssistantMessage am) {
                if (MessageConstants.isThinkingMessage(am)) {
                    continue;
                }
                if (lastTodoWriteIndex == -1) {
                    AssistantContent ac = am.message();
                    if (ac != null && ac.content() != null
                            && ac.content().stream().anyMatch(
                                b -> b instanceof ToolUseBlock tu && TODO_WRITE_TOOL.equals(tu.name()))) {
                        lastTodoWriteIndex = i;
                    }
                }
                if (lastTodoWriteIndex == -1) sinceWrite++;
                if (lastReminderIndex == -1) sinceReminder++;
            }
            if (lastTodoWriteIndex != -1 && lastReminderIndex != -1) {
                break;
            }
        }
        return new int[] {sinceWrite, sinceReminder};
    }
}
