package com.claudecode.tools.agent;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.agent.BuiltInAgentDefinitions.AgentDefinition;
import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.permissions.PermissionMode;
import com.claudecode.session.AgentMetadata;
import com.claudecode.session.SessionManager;
import com.claudecode.session.SessionStorage;
import com.claudecode.tools.tasks.LocalAgentTask;
import com.claudecode.tools.tasks.TaskOutputPaths;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Restarts a completed local sub-agent from its persisted sidechain transcript.
 */
public final class AgentContinuationService {

    private final SubAgentFactory subAgentFactory;
    private final TaskRegistry registry;
    private final BiFunction<ToolExecutionContext, String, Path> transcriptResolver;
    private final BiFunction<ToolExecutionContext, String, Path> outputResolver;

    public AgentContinuationService(SubAgentFactory subAgentFactory) {
        this(subAgentFactory, TaskRegistry.global(),
            (context, agentId) -> new SessionManager(context.workingDirectory())
                .getAgentTranscriptPath(context.sessionId(), agentId),
            (context, agentId) -> TaskOutputPaths.outputPath(agentId, context));
    }

    public AgentContinuationService(
            SubAgentFactory subAgentFactory, TaskRegistry registry,
            BiFunction<ToolExecutionContext, String, Path> transcriptResolver,
            BiFunction<ToolExecutionContext, String, Path> outputResolver) {
        this.subAgentFactory = subAgentFactory;
        this.registry = registry;
        this.transcriptResolver = transcriptResolver;
        this.outputResolver = outputResolver;
    }

    public ResumeResult resume(String agentId, String prompt, ToolExecutionContext context) {
        return resume(agentId, prompt, context, false);
    }

    /**
     * Resumes an agent, allowing an explicit human transcript submission to clear the stopped-by-user
     * guard.
     */
    public ResumeResult resume(String agentId, String prompt, ToolExecutionContext context,
                               boolean userInitiated) {
        if (subAgentFactory == null) {
            throw new IllegalStateException("sub-agent continuation is not configured");
        }
        TaskState previous = registry.get(agentId).orElse(null);
        if (!userInitiated && previous != null && previous.status() == TaskStatus.KILLED) {
            throw new UserStoppedAgentException(
                "Agent \"" + agentId + "\" was stopped by the user and was not resumed.");
        }

        Path transcript = transcriptResolver.apply(context, agentId);
        SessionStorage storage = new SessionStorage();
        var loaded = storage.getAgentTranscript(transcript, agentId).orElse(null);
        if (loaded == null || loaded.messages().isEmpty()) {
            throw new IllegalStateException("No transcript found for agent ID: " + agentId);
        }
        List<Message> resumedMessages = sanitize(loaded.messages());
        AgentMetadata metadata = storage.readAgentMetadata(transcript).orElse(null);
        if (!userInitiated && metadata != null && metadata.stoppedByUser()) {
            throw new UserStoppedAgentException(
                "Agent \"" + agentId + "\" was stopped by the user and was not resumed.");
        }
        if (userInitiated && metadata != null && metadata.stoppedByUser()) {
            metadata = new AgentMetadata(metadata.agentType(), metadata.worktreePath(),
                metadata.description(), false, metadata.spawnDepth(),
                metadata.subagentMaxDepth());
            storage.writeAgentMetadata(transcript, metadata);
        }
        String persistedAgentType = metadata == null
            || StringUtils.isBlank(metadata.agentType())
            ? "general-purpose" : metadata.agentType();
        boolean resumedFork = Strings.CS.equals("fork", persistedAgentType);
        Path resumedWorktree = resolveResumedWorktree(metadata);
        List<AgentDefinition> definitions = AgentDefinitionLoader.getAll(context.workingDirectory());
        AgentDefinition definition = resumedFork ? null : definitions.stream()
            .filter(candidate -> candidate.agentType().equals(persistedAgentType))
            .findFirst().orElseGet(() -> definitions.stream()
                .filter(candidate -> Strings.CS.equals("general-purpose", candidate.agentType()))
                .findFirst().orElse(null));
        String agentType = resumedFork ? "fork"
            : definition == null ? "general-purpose" : definition.agentType();

        String description = metadata != null && metadata.description() != null
            ? metadata.description()
            : previous == null ? "(resumed)" : previous.description();
        TaskState task = registry.prepareAgentResume(
            agentId, description, prompt, agentType, context.toolUseId());
        LocalAgentTask handle = new LocalAgentTask(task, registry.store());
        AbortController abortController = new AbortController();
        handle.setAbortController(abortController);
        AgentMetadata persistedMetadata = metadata;
        handle.setStoppedByUserPersister(() -> storage.writeAgentMetadata(transcript,
            new AgentMetadata(
                persistedMetadata == null ? agentType : persistedMetadata.agentType(),
                persistedMetadata == null ? null : persistedMetadata.worktreePath(),
                persistedMetadata == null ? description : persistedMetadata.description(),
                true,
                persistedMetadata == null ? null : persistedMetadata.spawnDepth(),
                persistedMetadata == null ? null : persistedMetadata.subagentMaxDepth())));
        registry.registerAgent(handle);

        DepthSnapshot depthSnapshot = resumeDepthSnapshot(metadata);
        SubAgentRequest request = buildRequest(
            agentId, agentType, definition, prompt, resumedMessages, context, abortController,
            description, resumedWorktree, resumedFork, depthSnapshot)
            .withContentReplacements(loaded.contentReplacements());
        SubAgentRequest trackedRequest = AgentTool.withTrackedProgress(
            request, agentId, handle, System.currentTimeMillis());
        Path outputPath = outputResolver.apply(context, agentId);
        boolean transcriptLinked = linkOutput(outputPath, transcript);

        Thread runner = Thread.ofVirtual().name("resume-agent-" + agentId).unstarted(() -> {
            try {
                SubAgentRequest turnRequest = trackedRequest;
                Deque<String> pending = new ArrayDeque<>();
                while (true) {
                    SubAgentResult result = subAgentFactory.runSubAgent(turnRequest);
                    if (!transcriptLinked) writeOutput(outputPath, result.output());
                    if (result.isError()) {
                        handle.fail(result.error().orElse("unknown error"));
                        return;
                    }
                    pending.addAll(registry.drainAgentMessages(agentId));
                    if (pending.isEmpty()) {
                        handle.complete(result);
                        return;
                    }
                    turnRequest = turnRequest.withPrompt(pending.removeFirst())
                        .withPriorMessages(result.conversation().orElse(List.of()));
                }
            } catch (Exception error) {
                if (!transcriptLinked) {
                    writeOutput(outputPath, "Error: sub-agent execution failed: " + error.getMessage());
                }
                handle.fail(error.getMessage());
            } finally {
                registry.clearAgentMessages(agentId);
            }
        });
        handle.setRunnerThread(runner);
        runner.start();
        return new ResumeResult(agentId, description, outputPath);
    }

    private static SubAgentRequest buildRequest(
            String agentId, String agentType, AgentDefinition definition, String prompt,
            List<Message> history, ToolExecutionContext context, AbortController abortController,
            String description, Path resumedWorktree, boolean resumedFork,
            DepthSnapshot depthSnapshot) {
        SubAgentRequest.Builder builder = SubAgentRequest.builder()
            .prompt(prompt)
            .subagentType(agentType)
            .parentContext(context)
            .parentQueue(context.messageQueueManager())
            .priorMessages(history)
            .description(description)
            .cwd(resumedWorktree == null ? context.workingDirectory() : resumedWorktree.toString())
            .async(true)
            .agentId(agentId)
            .agentDepth(depthSnapshot.depth())
            .subagentMaxDepthSnapshot(depthSnapshot.maxDepth())
            .fork(resumedFork);
        if (resumedFork) {
            if (StringUtils.isBlank(context.renderedSystemPrompt())) {
                throw new IllegalStateException(
                    "Cannot resume fork agent: unable to reconstruct parent system prompt");
            }
            builder.systemPromptOverride(context.renderedSystemPrompt())
                .tools(context.enabledTools())
                .maxTurns(200);
        }
        if (StringUtils.isNotBlank(context.currentModel())) {
            builder.model(context.currentModel());
        }
        if (context.currentPermissionMode() != null) {
            builder.permissionMode(PermissionMode.fromString(context.currentPermissionMode().wireValue()));
        }
        if (definition != null) {
            if (!definition.tools().contains("*")) builder.tools(definition.tools());
            builder.disallowedTools(definition.disallowedTools())
                .mcpServerIds(definition.mcpServers())
                .maxTurns(definition.maxTurns())
                .criticalSystemReminder(definition.criticalSystemReminder());
            if (definition.model() != null
                    && !Strings.CS.equals("inherit", definition.model())) {
                builder.model(definition.model());
            }
            if (StringUtils.isNotBlank(definition.effort())) {
                builder.effort(definition.effort());
            }
            if (StringUtils.isNotBlank(definition.permissionMode())) {
                builder.permissionMode(AgentTool.parseInternalPermissionMode(
                    definition.permissionMode()));
            }
        }
        return builder.build().withAbortController(abortController);
    }

    private static DepthSnapshot resumeDepthSnapshot(AgentMetadata metadata) {
        if (metadata != null && metadata.spawnDepth() != null) {
            int depth = Math.max(0, metadata.spawnDepth());
            Integer persistedMax = metadata.subagentMaxDepth();
            int maxDepth = persistedMax != null && persistedMax >= 1 && persistedMax <= 5
                ? persistedMax : 5;
            return new DepthSnapshot(depth, maxDepth);
        }
        return new DepthSnapshot(1, 1);
    }

    private record DepthSnapshot(int depth, int maxDepth) {}

    private static Path resolveResumedWorktree(AgentMetadata metadata) {
        if (metadata == null || StringUtils.isBlank(metadata.worktreePath())) return null;
        try {
            Path worktree = Path.of(metadata.worktreePath());
            if (!Files.isDirectory(worktree)) return null;
            Files.setLastModifiedTime(worktree, FileTime.fromMillis(System.currentTimeMillis()));
            return worktree;
        } catch (IOException error) {
            throw new UncheckedIOException("Failed to refresh resumed agent worktree", error);
        } catch (RuntimeException _) {
            return null;
        }
    }

    static List<Message> sanitize(List<Message> messages) {
        Set<String> toolUses = new HashSet<>();
        Set<String> toolResults = new HashSet<>();
        for (Message message : messages) {
            for (ContentBlock block : blocks(message)) {
                if (block instanceof ToolUseBlock use) toolUses.add(use.id());
                if (block instanceof ToolResultBlock result) toolResults.add(result.toolUseId());
            }
        }
        toolUses.removeAll(toolResults);
        Set<String> assistantIdsWithContent = new HashSet<>();
        for (Message message : messages) {
            if (message instanceof AssistantMessage assistant
                    && assistant.message() != null && assistant.message().id() != null
                    && blocks(message).stream().anyMatch(block -> !(block instanceof ThinkingBlock))) {
                assistantIdsWithContent.add(assistant.message().id());
            }
        }
        List<Message> filtered = new ArrayList<>();
        for (Message message : messages) {
            List<ContentBlock> content = blocks(message);
            if (message instanceof AssistantMessage assistant) {
                List<String> ids = content.stream().filter(ToolUseBlock.class::isInstance)
                    .map(ToolUseBlock.class::cast).map(ToolUseBlock::id).toList();
                if (!ids.isEmpty() && toolUses.containsAll(ids)) continue;
                boolean thinkingOnly = !content.isEmpty()
                    && content.stream().allMatch(ThinkingBlock.class::isInstance);
                if (thinkingOnly && (assistant.message() == null
                        || assistant.message().id() == null
                        || !assistantIdsWithContent.contains(assistant.message().id()))) continue;
            }
            filtered.add(message);
        }
        return MessageConstants.filterWhitespaceOnlyAssistantMessages(filtered);
    }

    private static List<ContentBlock> blocks(Message message) {
        if (message instanceof AssistantMessage assistant && assistant.message() != null
                && assistant.message().content() != null) return assistant.message().content();
        if (message instanceof UserMessage user
                && user.message() != null && user.message().blocks() != null) {
            return user.message().blocks();
        }
        return List.of();
    }

    private static boolean linkOutput(Path outputPath, Path transcript) {
        try {
            Files.createDirectories(outputPath.getParent());
            Files.deleteIfExists(outputPath);
            Files.createSymbolicLink(outputPath, transcript);
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException _) {
            return false;
        }
    }

    private static void writeOutput(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content == null ? "" : content, StandardCharsets.UTF_8);
        } catch (IOException _) {
            // Completion state remains authoritative when the fallback file fails.
        }
    }

    public record ResumeResult(String agentId, String description, Path outputFile) {}

    public static final class UserStoppedAgentException extends IllegalStateException {
        public UserStoppedAgentException(String message) { super(message); }
    }
}
