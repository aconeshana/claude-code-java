package com.claudecode.services.memory;

import org.apache.commons.lang3.Strings;

import com.claudecode.runtime.query.MemoryExtractor;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.runtime.query.QuerySessionSpec;
import com.claudecode.runtime.query.QuerySessionFactory;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.SubmitOptions;
import com.claudecode.core.engine.ToolExecutor;
import com.claudecode.core.memdir.AutoMemoryPrompt;
import com.claudecode.core.memdir.ExtractMemoriesPrompt;
import com.claudecode.core.memdir.MemoryManifestScanner;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.util.AgentId;
import com.claudecode.services.claudemd.AutoMemory;
import com.claudecode.services.config.RuntimeSettings;
import com.claudecode.session.SessionManager;
import com.claudecode.session.SessionStorage;
import com.claudecode.session.TranscriptRecorder;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Background memory extraction: at the end of every natural (no-tool-use) main-thread turn, forks a
 * restricted sub-agent that reads the recent conversation and writes durable facts into the
 * auto-memory directory.
 */
public class ExtractMemoriesService implements MemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(ExtractMemoriesService.class);

    private final StreamingClient llmClient;
    private final ToolExecutor realToolExecutor;
    private final QuerySessionFactory querySessionFactory;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<CompletableFuture<Void>> inFlight = ConcurrentHashMap.newKeySet();


    private volatile String lastMemoryMessageUuid;
    private volatile boolean inProgress;
    private volatile PendingContext pendingContext;

    public ExtractMemoriesService(StreamingClient llmClient, ToolExecutor realToolExecutor) {
        this(llmClient, realToolExecutor, null);
    }

    public ExtractMemoriesService(StreamingClient llmClient, ToolExecutor realToolExecutor,
                                  QuerySessionFactory querySessionFactory) {
        this.llmClient = llmClient;
        this.realToolExecutor = realToolExecutor;
        this.querySessionFactory = querySessionFactory;
    }

    private record PendingContext(List<Message> messages, QuerySession engine) {}

    @Override
    public void extractAsync(List<Message> messagesSinceLastTurn, QuerySession engine) {
        if (!RuntimeSettings.loadExtractMemoriesEnabled()) {
            return;
        }
        if (!AutoMemory.isEnabled()) {
            return;
        }
        if (inProgress) {
// Coalesce: only the latest stashed context matters.
            pendingContext = new PendingContext(messagesSinceLastTurn, engine);
            return;
        }
        fireExtraction(messagesSinceLastTurn, engine);
    }

    private void fireExtraction(List<Message> messages, QuerySession engine) {
        inProgress = true;
        CompletableFuture<Void> future = CompletableFuture.runAsync(
            () -> runExtractionThenTrailing(messages, engine), executor);
        inFlight.add(future);
        future.whenComplete((_, _) -> inFlight.remove(future));
    }

    private void runExtractionThenTrailing(List<Message> messages, QuerySession engine) {
        try {
            runExtraction(messages, engine);
        } finally {
            inProgress = false;
            PendingContext trailing = pendingContext;
            pendingContext = null;
            if (trailing != null) {
                // Run inline (already on the virtual-thread executor's worker) —

                inProgress = true;
                runExtractionThenTrailing(trailing.messages(), trailing.engine());
            }
        }
    }

    private void runExtraction(List<Message> messages, QuerySession engine) {
        try {
            String workingDirectory = engine.configuration().getConfig().workingDirectory();
            Path workingDir = Path.of(workingDirectory);

            int newMessageCount = countModelVisibleMessagesSince(messages, lastMemoryMessageUuid);

            if (hasMemoryWritesSince(messages, lastMemoryMessageUuid, workingDir)) {
                log.debug("[extractMemories] skipping — conversation already wrote to memory files");
                advanceCursor(messages);
                return;
            }

            Path memoryDir = Path.of(AutoMemory.autoMemoryPath(workingDir));
            String manifest = MemoryManifestScanner.formatManifest(MemoryManifestScanner.scan(memoryDir));
            String userPrompt = ExtractMemoriesPrompt.buildUserPrompt(newMessageCount, manifest);

            List<String> writtenPaths = runForkedExtractionAgent(userPrompt, engine, memoryDir);

            advanceCursor(messages);

            List<String> memoryPaths = writtenPaths.stream()
                .distinct()
                .filter(p -> !Strings.CS.endsWith(p, "/" + AutoMemoryPrompt.ENTRYPOINT_NAME) && !p.equals(AutoMemoryPrompt.ENTRYPOINT_NAME))
                .toList();
            log.debug("[extractMemories] finished — {} files written", writtenPaths.size());
            if (!memoryPaths.isEmpty()) {
                engine.conversation().queueNotification(new SystemMessage(UUID.randomUUID().toString(), "memory_saved", "info",
                    "Saved memories: " + String.join(", ", memoryPaths)));
            }
        } catch (Exception e) {
            // Extraction is best-effort — never let a failure surface as an error.
            log.debug("[extractMemories] error: {}", e.getMessage());
        }
    }

    private List<String> runForkedExtractionAgent(String userPrompt, QuerySession parentEngine, Path memoryDir) {
        QuerySessionSpec subConfig = QuerySessionSpec.builder()
            .llmClient(llmClient)
            .model(parentEngine.configuration().getConfig().model())
            .systemPrompt(ExtractMemoriesPrompt.SYSTEM_PROMPT)
            .maxTokens(4096)
            .maxTurns(5)
            .toolExecutor(new MemoryExtractionToolExecutor(realToolExecutor, memoryDir))
            .workingDirectory(parentEngine.configuration().getConfig().workingDirectory())
            .agentId(AgentId.create())
            .build();
        QuerySession subEngine = Objects.requireNonNull(
            querySessionFactory, "QuerySessionFactory is not wired").create(subConfig);
        SessionManager sessionManager = new SessionManager(subConfig.workingDirectory());
        subEngine.execution().setTranscriptSink(new TranscriptRecorder(sessionManager, new SessionStorage(),
            subConfig.workingDirectory(), /* isSidechain */ true, subConfig.agentId()));

        List<String> writtenPaths = new ArrayList<>();
        Iterator<SDKMessage> iterator = subEngine.submission().submitMessage(userPrompt, SubmitOptions.DEFAULT);
        while (iterator.hasNext()) {
            SDKMessage msg = iterator.next();
            if (msg instanceof SDKMessage.Assistant assistant && assistant.message().message() != null
                    && assistant.message().message().content() != null) {
                for (ContentBlock block : assistant.message().message().content()) {
                    String path = writtenFilePath(block);
                    if (path != null) writtenPaths.add(path);
                }
            }
        }
        return writtenPaths;
    }

    private void advanceCursor(List<Message> messages) {
        if (!messages.isEmpty()) {
            lastMemoryMessageUuid = messages.getLast().uuid();
        }
    }


    static int countModelVisibleMessagesSince(List<Message> messages, String sinceUuid) {
        if (sinceUuid == null) {
            return (int) messages.stream().filter(ExtractMemoriesService::isModelVisible).count();
        }
        boolean found = false;
        int n = 0;
        for (Message m : messages) {
            if (!found) {
                if (sinceUuid.equals(m.uuid())) found = true;
                continue;
            }
            if (isModelVisible(m)) n++;
        }
        if (!found) {
            // sinceUuid no longer present (e.g. pruned by compaction) — fall back to
            // counting everything rather than permanently disabling extraction.
            return (int) messages.stream().filter(ExtractMemoriesService::isModelVisible).count();
        }
        return n;
    }

    private static boolean isModelVisible(Message m) {
        return m instanceof UserMessage || m instanceof AssistantMessage;
    }


    static boolean hasMemoryWritesSince(List<Message> messages, String sinceUuid, Path workingDirectory) {
        boolean found = sinceUuid == null;
        for (Message m : messages) {
            if (!found) {
                if (sinceUuid.equals(m.uuid())) found = true;
                continue;
            }
            if (!(m instanceof AssistantMessage am) || am.message() == null || am.message().content() == null) {
                continue;
            }
            for (ContentBlock block : am.message().content()) {
                String filePath = writtenFilePath(block);
                if (filePath != null && AutoMemoryPrompt.isAutoMemPath(Path.of(filePath), workingDirectory)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String writtenFilePath(ContentBlock block) {
        if (!(block instanceof ToolUseBlock tub)) return null;
        if (!Strings.CS.equals("Edit", tub.name()) && !Strings.CS.equals("Write", tub.name())) return null;
        JsonNode input = tub.input();
        if (input == null || !input.has("file_path")) return null;
        JsonNode fp = input.get("file_path");
        return (fp == null || fp.isNull()) ? null : fp.asText();
    }

    @Override
    public void drainPending(long timeoutMillis) {
        List<CompletableFuture<Void>> snapshot = List.copyOf(inFlight);
        if (snapshot.isEmpty()) return;
        try {
            CompletableFuture.allOf(snapshot.toArray(new CompletableFuture[0]))
                .get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception _) {
            // Soft timeout — proceed with shutdown regardless.
        }
    }
}
