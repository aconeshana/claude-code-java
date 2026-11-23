package com.claudecode.cli;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.gateway.GatewayBackgroundTasksPort;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.session.SessionManager;
import com.claudecode.tools.Tool;
import com.claudecode.tools.ToolRegistry;
import com.claudecode.tools.agent.AgentContinuationService;
import com.claudecode.tools.agent.AgentTool;
import com.claudecode.tools.tasks.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;

/**
 * CLI implementation of {@link GatewayBackgroundTasksPort}: reads background
 * shells from {@link TaskRegistry#global()} and routes sub-agent follow-up
 * prompts through {@link TaskRegistry#queueAgentMessage} (when running) or
 * {@link AgentContinuationService#resume} (when idle/completed), mirroring the
 * TUI's {@code LocalAgentInputRouter}.
 */
final class CliBackgroundTasksPort implements GatewayBackgroundTasksPort {

    /** Cap shell output reads to the newest 256 KiB so huge logs stay responsive. */
    private static final int MAX_OUTPUT_BYTES = 256 * 1024;

    private final TaskRegistry taskRegistry;
    private final Function<String, Optional<QuerySession>> engineResolver;
    private final Supplier<ToolRegistry> toolRegistrySupplier;

    CliBackgroundTasksPort(
            TaskRegistry taskRegistry,
            Function<String, Optional<QuerySession>> engineResolver,
            Supplier<ToolRegistry> toolRegistrySupplier) {
        this.taskRegistry = taskRegistry;
        this.engineResolver = engineResolver;
        this.toolRegistrySupplier = toolRegistrySupplier;
    }

    @Override
    public List<ShellEntry> listShells() {
        return taskRegistry.store().list().stream()
            .filter(task -> task.type() == TaskType.LOCAL_BASH)
            .sorted(Comparator.comparing(TaskState::startTime).reversed())
            .map(this::toShellEntry)
            .toList();
    }

    @Override
    public Optional<ShellOutput> shellOutput(String taskId) {
        if (StringUtils.isBlank(taskId)) return Optional.empty();
        TaskState task = taskRegistry.get(taskId).orElse(null);
        if (task == null || task.type() != TaskType.LOCAL_BASH) {
            return Optional.empty();
        }
        LocalShellTask handle = taskRegistry.getShellHandle(taskId).orElse(null);
        String command = handle != null && StringUtils.isNotBlank(handle.getCommand())
            ? handle.getCommand()
            : task.description();
        Path outputPath = handle != null ? handle.getOutputPath() : null;
        TailRead tail = readTail(outputPath);
        return Optional.of(new ShellOutput(
            task.id(),
            command,
            task.description(),
            statusWire(task.status()),
            task.startTime().toEpochMilli(),
            task.endTime().map(Instant::toEpochMilli).orElse(null),
            task.exitCode().orElse(null),
            tail.text(),
            tail.truncated()));
    }

    @Override
    public boolean stopShell(String taskId) {
        if (StringUtils.isBlank(taskId)) return false;
        return taskRegistry.killShell(taskId);
    }

    @Override
    public AgentPromptResult promptAgent(String sessionId, String agentId, String prompt) {
        if (StringUtils.isBlank(agentId) || StringUtils.isBlank(prompt)) {
            return AgentPromptResult.error("agentId and prompt are required");
        }
        TaskState existing = taskRegistry.get(agentId).orElse(null);
        if (existing != null
                && existing.type() == TaskType.LOCAL_AGENT
                && existing.status() == TaskStatus.RUNNING) {
            boolean queued = taskRegistry.queueAgentMessage(agentId, prompt, "user");
            return queued
                ? AgentPromptResult.queued()
                : AgentPromptResult.error("Failed to queue message for running agent " + agentId);
        }

        QuerySession engine = engineResolver.apply(sessionId).orElse(null);
        if (engine == null) {
            return AgentPromptResult.unavailable(
                "No active host session is running for session " + sessionId);
        }
        ToolRegistry registry = toolRegistrySupplier.get();
        Tool<?, ?> registered = registry == null ? null : registry.get("Agent").orElse(null);
        if (!(registered instanceof AgentTool agentTool) || agentTool.subAgentFactory() == null) {
            return AgentPromptResult.unavailable("Agent continuation is unavailable in this host");
        }
        try {
            ToolExecutionContext context = buildContext(engine);
            new AgentContinuationService(agentTool.subAgentFactory(), taskRegistry,
                (ctx, id) -> new SessionManager(ctx.workingDirectory())
                    .getAgentTranscriptPath(ctx.sessionId(), id),
                (ctx, id) -> TaskOutputPaths.outputPath(id, ctx))
                .resume(agentId, prompt, context, true);
            return AgentPromptResult.resumed();
        } catch (IllegalStateException failure) {
            return AgentPromptResult.unavailable(failure.getMessage());
        } catch (RuntimeException failure) {
            return AgentPromptResult.error(failure.getMessage());
        }
    }

    @Override
    public boolean stopAgent(String sessionId, String agentId) {
        if (StringUtils.isBlank(agentId)) return false;
        return taskRegistry.killAgentByUser(agentId);
    }

    @Override
    public boolean isAgentRunning(String agentId) {
        if (StringUtils.isBlank(agentId)) return false;
        return taskRegistry.get(agentId)
            .filter(task -> task.type() == TaskType.LOCAL_AGENT)
            .map(task -> task.status() == TaskStatus.RUNNING)
            .orElse(false);
    }

    private ShellEntry toShellEntry(TaskState task) {
        LocalShellTask handle = taskRegistry.getShellHandle(task.id()).orElse(null);
        String command = handle != null && StringUtils.isNotBlank(handle.getCommand())
            ? handle.getCommand()
            : task.description();
        return new ShellEntry(
            task.id(),
            command,
            task.description(),
            statusWire(task.status()),
            task.startTime().toEpochMilli(),
            task.endTime().map(Instant::toEpochMilli).orElse(null),
            task.exitCode().orElse(null));
    }

    private static String statusWire(TaskStatus status) {
        return status == null ? "unknown" : status.name().toLowerCase(Locale.ROOT);
    }

    private record TailRead(String text, boolean truncated) {}

    private static TailRead readTail(Path outputPath) {
        if (outputPath == null || !Files.isRegularFile(outputPath)) {
            return new TailRead("", false);
        }
        try (SeekableByteChannel channel = Files.newByteChannel(outputPath, StandardOpenOption.READ)) {
            long size = channel.size();
            boolean truncated = size > MAX_OUTPUT_BYTES;
            long start = truncated ? size - MAX_OUTPUT_BYTES : 0L;
            int toRead = (int) Math.min(size, MAX_OUTPUT_BYTES);
            if (toRead <= 0) return new TailRead("", false);
            channel.position(start);
            ByteBuffer buf = ByteBuffer.allocate(toRead);
            while (buf.hasRemaining()) {
                int read = channel.read(buf);
                if (read < 0) break;
            }
            return new TailRead(
                new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8),
                truncated);
        } catch (IOException _) {
            return new TailRead("", false);
        }
    }

    private static ToolExecutionContext buildContext(QuerySession queryEngine) {
        var config = queryEngine.configuration().getConfig();
        var permissionMode = config.permissionModeSupplier() == null
            ? null : config.permissionModeSupplier().get();
        return ToolExecutionContext
            .builder(new AbortController(), queryEngine.conversation().getSessionId())
            .workingDirectory(config.workingDirectory())
            .permissionAskCallback(queryEngine.execution().getPermissionAskCallback())
            .fileStateCache(queryEngine.forks().getFileStateCache())
            .fileHistoryManager(queryEngine.conversation().getFileHistoryManager())
            .messageQueueManager(queryEngine.conversation().getMessageQueue())
            .agentId(config.agentId())
            .nestedMemoryAttachmentTriggers(queryEngine.forks().getNestedMemoryAttachmentTriggers())
            .loadedNestedMemoryPaths(queryEngine.forks().getLoadedNestedMemoryPaths())
            .teamMemoryEnabled(config.teamMemoryEnabledSupplier().get())
            .currentModel(config.model())
            .sandboxConfig(config.sandboxConfigSupplier().get())
            .readDenyIgnorePatterns(config.readDenyIgnorePatternsSupplier().get())
            .turnTokenBudget(queryEngine.execution().getTurnTokenBudget())
            .workingDirectoryController(queryEngine.configuration().workingDirectoryController())
            .enabledTools(config.tools())
            .currentPermissionMode(permissionMode)
            .conversationMessages(queryEngine.conversation().getMessages())
            .renderedSystemPrompt(queryEngine.configuration().fetchSystemPromptParts())
            .build();
    }
}
