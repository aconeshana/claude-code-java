package com.claudecode.tools.agent;

import com.claudecode.core.attachment.AttachmentContext;
import com.claudecode.core.message.QueuedCommandAttachment;
import com.claudecode.tools.tasks.LocalAgentTask;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskStore;
import com.claudecode.tools.tasks.TaskType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPendingMessageAttachmentProviderTest {

    @Test
    void drainsOnlyTheCurrentAgentsMessagesWithSenderMetadata() {
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        TaskState task = registry.store().createWithId(
            "agent-123", TaskType.LOCAL_AGENT, "research", null);
        registry.store().updateStatus(task.id(), TaskStatus.RUNNING);
        registry.registerAgent(new LocalAgentTask(task, registry.store()));
        registry.registerAgentName("researcher", task.id());
        assertTrue(registry.queueAgentMessage(
            task.id(), "start on task 1", "researcher"));

        var payloads = new AgentPendingMessageAttachmentProvider(registry)
            .collect(context(task.id()));

        assertEquals(List.of(new QueuedCommandAttachment(
            "start on task 1", "agent-message", "researcher", true)), payloads);
        assertTrue(registry.drainAgentMessageEnvelopes(task.id()).isEmpty());
    }

    private static AttachmentContext context(String agentId) {
        return AttachmentContext.builder("/tmp")
            .input(null)
            .agentId(agentId)
            .querySource("agent")
            .toolNames(List.of("Agent"))
            .todos(List.of())
            .build();
    }
}
