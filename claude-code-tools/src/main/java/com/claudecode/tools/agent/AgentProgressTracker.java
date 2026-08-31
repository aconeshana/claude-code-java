package com.claudecode.tools.agent;

import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;

/**
 * Aggregates one local Agent run's live progress.
 */
final class AgentProgressTracker {

    record Snapshot(long totalTokens, int toolUseCount) {}

    private final Map<String, Usage> usageByMessage = new LinkedHashMap<>();
    private final Map<String, AssistantMessage> messagesById = new LinkedHashMap<>();
    private final Set<String> toolUseIds = new LinkedHashSet<>();

    synchronized void recordMessage(Message message) {
        if (!(message instanceof AssistantMessage assistant)
                || assistant.message() == null) {
            return;
        }
        messagesById.put(assistant.uuid(), assistant);
        if (assistant.message().usage() != null) {
            usageByMessage.put(assistant.uuid(), assistant.message().usage());
        }
        if (assistant.message().content() != null) {
            assistant.message().content().stream()
                .filter(ToolUseBlock.class::isInstance)
                .map(ToolUseBlock.class::cast)
                .map(ToolUseBlock::id)
                .filter(StringUtils::isNotBlank)
                .forEach(toolUseIds::add);
        }
    }

    synchronized void recordUsage(String messageId, Usage usage) {
        if (StringUtils.isBlank(messageId) || usage == null) return;
        usageByMessage.put(messageId, usage);
    }

    synchronized Snapshot snapshot() {
        Usage latest = latestUsage();
        if (latest == null) return new Snapshot(0L, toolUseIds.size());
        long outputs = usageByMessage.values().stream().mapToLong(Usage::outputTokens).sum();
        long total = latest.inputTokens()
            + latest.cacheCreationInputTokens()
            + latest.cacheReadInputTokens()
            + outputs;
        return new Snapshot(Math.max(0L, total), toolUseIds.size());
    }

    synchronized AssistantMessage messageWithAggregatedUsage(String messageId) {
        AssistantMessage assistant = messagesById.get(messageId);
        Usage latest = latestUsage();
        if (latest == null) return assistant;
        long outputs = usageByMessage.values().stream().mapToLong(Usage::outputTokens).sum();
        Usage aggregate = new Usage(
            latest.inputTokens(), outputs,
            latest.cacheCreationInputTokens(), latest.cacheReadInputTokens(),
            latest.serverToolUse(), latest.serviceTier(), latest.cacheCreation(),
            latest.inferenceGeo(), latest.iterations(), latest.speed(),
            latest.reportedTotalTokens());
        if (assistant == null || assistant.message() == null) {
            assistant = new AssistantMessage(messageId,
                AssistantContent.of(messageId, List.of(), aggregate));
        }
        AssistantContent content = assistant.message();
        AssistantContent updatedContent = new AssistantContent(
            content.id(), content.content(), aggregate, content.model(),
            content.stopReason(), content.stopSequence(), content.stopDetails());
        return new AssistantMessage(
            assistant.uuid(), updatedContent, assistant.isApiErrorMessage(),
            assistant.parentUuidValue(), assistant.timestampValue(),
            assistant.attributionSkill(), assistant.attributionPlugin(),
            assistant.attributionMcpServer(), assistant.attributionMcpTool(),
            assistant.apiError(), assistant.error(), assistant.isVirtual(),
            assistant.requestId(), assistant.advisorModel(), assistant.isMeta());
    }

    private Usage latestUsage() {
        Usage latest = null;
        for (Usage usage : usageByMessage.values()) latest = usage;
        return latest;
    }
}
