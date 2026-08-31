package com.claudecode.tools.agent;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.attachment.AttachmentContext;
import com.claudecode.core.attachment.AttachmentProvider;
import com.claudecode.core.message.AttachmentPayload;
import com.claudecode.core.message.QueuedCommandAttachment;
import com.claudecode.tools.tasks.TaskRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Drains messages queued for the currently executing local Agent at a tool-round boundary.
 */
final class AgentPendingMessageAttachmentProvider implements AttachmentProvider {

    private final TaskRegistry registry;

    AgentPendingMessageAttachmentProvider(TaskRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return "agent_pending_messages";
    }

    @Override
    public List<AttachmentPayload> collect(AttachmentContext ctx) {
        if (ctx.agentId() == null) return List.of();
        List<TaskRegistry.PendingAgentMessage> pending =
            registry.drainAgentMessageEnvelopes(ctx.agentId());
        if (pending.isEmpty()) return List.of();

        List<AttachmentPayload> out = new ArrayList<>(pending.size());
        for (TaskRegistry.PendingAgentMessage message : pending) {
            String from = StringUtils.isBlank(message.from())
                ? registry.resolveAgentName(ctx.agentId())
                : message.from();
            out.add(new QueuedCommandAttachment(
                message.text(), "agent-message", from, true));
        }
        return List.copyOf(out);
    }
}
