package com.claudecode.services.dream;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.SubmitOptions;
import com.claudecode.core.engine.ToolExecutor;
import com.claudecode.core.memdir.AutoMemoryPrompt;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.MessageFactory;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.util.AgentId;
import com.claudecode.core.util.UuidUtils;
import com.claudecode.runtime.query.AutoDreamEngine;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.runtime.query.QuerySessionFactory;
import com.claudecode.runtime.query.QuerySessionSpec;
import com.claudecode.services.claudemd.AutoMemory;
import com.claudecode.services.config.AutoDreamFeatureGate;
import com.claudecode.services.config.RuntimeSettings;
import com.claudecode.services.memory.MemoryExtractionToolExecutor;
import com.claudecode.session.SessionManager;
import com.claudecode.tools.tasks.DreamTaskDetails;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskStore;
import com.claudecode.tools.tasks.TaskType;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Background memory consolidation ("auto-dream").
 */
public final class AutoDreamEngineImpl implements AutoDreamEngine {

    private static final Logger log = LoggerFactory.getLogger(AutoDreamEngineImpl.class);


    static final double MIN_HOURS = 24;

    static final double MIN_SESSIONS = 5;

    private static final long SESSION_SCAN_INTERVAL_MS = 10 * 60 * 1000L;

    private final StreamingClient llmClient;
    private final ToolExecutor realToolExecutor;
    private final QuerySessionFactory querySessionFactory;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

/**
     * Per-process scan-throttle timestamp.
     */
    private volatile long lastSessionScanAt = 0;

    /**
     * Live handle for each in-flight dream, keyed by task id, so the {@code /tasks} dialog's kill path
     * (via {@link TaskRegistry#killDream}) can abort the forked sub-engine and roll back the lock.
     */
    private final Map<String, DreamRun> running = new ConcurrentHashMap<>();

    public AutoDreamEngineImpl(StreamingClient llmClient, ToolExecutor realToolExecutor) {
        this(llmClient, realToolExecutor, null);
    }

    public AutoDreamEngineImpl(StreamingClient llmClient, ToolExecutor realToolExecutor,
                               QuerySessionFactory querySessionFactory) {
        this.llmClient = llmClient;
        this.realToolExecutor = realToolExecutor;
        this.querySessionFactory = querySessionFactory;
    }

    @Override
    public void maybeRunAutoDream(QuerySession engine) {
        // Fire-and-forget — never block the stop hook. The lock is the
        // concurrency guard (a second *process* is also blocked), so we don't
        // need an in-process "in progress" flag.
        CompletableFuture.runAsync(() -> runGuarded(engine), executor);
    }

    private void runGuarded(QuerySession engine) {
        try {
            runDream(engine);
        } catch (Exception e) {
            // Best-effort — never let a failure surface as an error to the user.
            log.debug("[autoDream] error: {}", e.getMessage());
        }
    }

    boolean isGateOpen() {

        return AutoMemory.isEnabled() && RuntimeSettings.isAutoDreamEnabled();
    }

    private void runDream(QuerySession engine) {
        if (!isGateOpen()) {
            return;
        }
        Path workingDir = Path.of(engine.configuration().getConfig().workingDirectory());
        Path memoryRoot = AutoMemoryPrompt.resolveAutoMemPath(workingDir);
        DreamLock lock = new DreamLock(memoryRoot);
        AutoDreamFeatureGate.Schedule schedule = AutoDreamFeatureGate.schedule();

        // --- Time gate ---
        long lastAt = lock.readLastConsolidatedAt();
        double hoursSince = (System.currentTimeMillis() - lastAt) / 3_600_000.0;
        if (hoursSince < schedule.minHours()) {
            return;
        }

        // --- Scan throttle ---
        long sinceScan = System.currentTimeMillis() - lastSessionScanAt;
        if (sinceScan < SESSION_SCAN_INTERVAL_MS) {
            log.debug("[autoDream] scan throttle — time-gate passed but last scan was {}s ago",
                sinceScan / 1000);
            return;
        }
        lastSessionScanAt = System.currentTimeMillis();

        // --- Session gate ---
        List<String> sessionIds = listSessionsTouchedSince(workingDir, lastAt);
        String currentSession = engine.conversation().getSessionId();
        sessionIds = sessionIds.stream()
            .filter(id -> !id.equals(currentSession))
            .collect(Collectors.toList());
        if (sessionIds.size() < schedule.minSessions()) {
            log.debug("[autoDream] skip — {} sessions since last consolidation, need {}",
                sessionIds.size(), schedule.minSessions());
            return;
        }

        // --- Lock ---
        long priorMtime = lock.tryAcquire();
        if (priorMtime < 0) {
            return; // held by a live process or lost a race
        }

        log.debug("[autoDream] firing — {}h since last, {} sessions to review",
            hoursSince, sessionIds.size());

        // Register a background task so the UI can show / kill it.
        TaskStore store = TaskRegistry.global().store();
        TaskState task = store.create(TaskType.DREAM, "dreaming");
        store.initializeDream(task.id(), sessionIds.size());
        store.updateStatus(task.id(), TaskStatus.RUNNING);

// Wire the live handle for /tasks kill.
        AbortController controller = new AbortController();
        DreamRun run = new DreamRun(controller, lock, priorMtime, store, Thread.currentThread());
        running.put(task.id(), run);
        TaskRegistry.global().registerDream(task.id(), () -> killDream(task.id()));

        try {
            Path transcriptDir = new SessionManager(engine.configuration().getConfig().workingDirectory())
                .getProjectDir();
            String extra = buildExtra(sessionIds);
            String prompt = ConsolidationPromptGenerator.buildConsolidationPrompt(
                memoryRoot, transcriptDir, extra);

            List<String> touched = runForkedDream(prompt, engine, memoryRoot, task.id(), store, controller);

            if (run.aborted.get()) {
// Killed mid-run: killDream already set KILLED + rolled back

                // runAutoDream catch on abortController.signal.aborted).
                return;
            }

            // Clear the live "what it did" text before freezing the task.
            store.updateProgressSummary(task.id(), null);
            store.updateStatus(task.id(), TaskStatus.COMPLETED);

            List<String> memoryPaths = touched.stream()
                .distinct()
                .toList();
            if (!memoryPaths.isEmpty()) {


                SystemMessage msg = MessageFactory.createMemorySavedMessage(memoryPaths, "Improved");
                engine.conversation().queueNotification(msg);
            }
            // #3/N3: mark the task notified so it retires from the background list

            // "(done, unread)".
            store.markNotified(task.id());
        } catch (Exception e) {
            if (run.aborted.get()) {
                log.debug("[autoDream] aborted by user");
                return;
            }
            log.debug("[autoDream] fork failed: {}", e.getMessage());
            lock.rollback(priorMtime);
            store.updateStatus(task.id(), TaskStatus.FAILED);
            store.updateError(task.id(), e.getMessage());

            // retires from the background list instead of lingering as unread.
            store.markNotified(task.id());
        } finally {
            running.remove(task.id());
            TaskRegistry.global().unregisterDream(task.id());
        }
    }

    /**
     * Kill a running dream: abort the forked sub-engine, roll back the lock, and mark the task {@code
     * KILLED}.
     */
    boolean killDream(String taskId) {
        DreamRun run = running.get(taskId);
        if (run == null) {
            return false;
        }
        run.aborted.set(true);

        // the thread interrupt below then breaks any blocking I/O the loop is
        // parked on. Interrupt alone was best-effort — the loop only observes
        // it at blocking points, so an in-flight API stream may run to
// completion before dying (matches LocalAgentTask.kill).
        run.controller.abort("killed via /tasks");
        Thread thread = run.runnerThread;
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
        }
        run.lock.rollback(run.priorMtime);
        run.store.updateStatus(taskId, TaskStatus.KILLED);
        run.store.markNotified(taskId);
        return true;
    }

    private List<String> listSessionsTouchedSince(Path workingDir, long sinceMs) {

// not the user-facing listSessionsImpl sort.
        SessionManager sessionManager = new SessionManager(workingDir.toString());
        Path projectDir = sessionManager.getProjectDir();
        try (Stream<Path> entries = Files.list(projectDir)) {
            return entries
                .filter(Files::isRegularFile)
                .filter(path -> Strings.CS.endsWith(path.getFileName().toString(), ".jsonl"))
                .map(path -> path.getFileName().toString()
                    .substring(0, path.getFileName().toString().length() - ".jsonl".length()))
                .filter(UuidUtils::isValid)
                .filter(id -> {
                    try {
                        return Files.getLastModifiedTime(projectDir.resolve(id + ".jsonl"))
                                .toMillis() > sinceMs;
                    } catch (Exception _) {
                        return false;
                    }
                })
                .collect(Collectors.toList());
        } catch (Exception _) {
            return List.of();
        }
    }

    static String buildExtra(List<String> sessionIds) {
        String lines = sessionIds.stream()
            .map(id -> "- " + id)
            .collect(Collectors.joining("\n"));
        return "\n\n**Tool constraints for this run:** Shell access is restricted to read-only commands "
            + "(`ls`, `find`, `grep`, `cat`, `stat`, `wc`, `head`, `tail`, and similar) "
            + "plus deleting `.md` paths inside the memory directory. "
            + "Anything else that writes, redirects to a file, or modifies state will be denied. "
            + "Plan your exploration with this in mind — no need to probe.\n\n"
            + "Sessions since last consolidation (" + sessionIds.size() + "):\n" + lines;
    }

    private List<String> runForkedDream(String prompt, QuerySession parentEngine, Path memoryRoot,
            String taskId, TaskStore store, AbortController controller) {
        QuerySessionSpec parentConfig = parentEngine.configuration().getConfig();
// Inherit the parent's cache-safe fork params (system prompt, model, output-token limit,
// tool catalogue, and every system-prompt input) so the first request hits the parent's
// prompt cache.
        QuerySessionSpec subConfig = parentConfig.cacheSafeForkBuilder()
            .llmClient(llmClient)
            .maxTurns(100)
            // The fork starts from the parent's cache-safe message prefix and
            // appends the Dream prompt as its next user message.
            .initialMessages(new ArrayList<>(parentEngine.conversation().getMessages()))
            .toolExecutor(new MemoryExtractionToolExecutor(realToolExecutor, memoryRoot))
            .agentId(AgentId.create())
// #1: wired so /tasks kill can abort the fork.
            .abortController(controller)
            .build();
        QuerySession subEngine = Objects.requireNonNull(
            querySessionFactory, "QuerySessionFactory is not wired").create(subConfig);

        subEngine.execution().setTranscriptSink(null);


        // each assistant turn's text + tool-use count is pushed to the task's
        // progress summary (the UI's live "what it did"), Edit/Write paths are
        // collected as touched memory files, and abort is checked cooperatively
        // each iteration so /tasks kill stops the loop promptly.
        List<String> touched = new ArrayList<>();
        Iterator<SDKMessage> iterator = subEngine.submission().submitMessage(prompt, SubmitOptions.DEFAULT);
        while (iterator.hasNext()) {
            if (controller.isAborted()) {
                break;
            }
            SDKMessage msg = iterator.next();
            if (msg instanceof SDKMessage.Assistant assistant
                    && assistant.message().message() != null
                    && assistant.message().message().content() != null) {
                StringBuilder text = new StringBuilder();
                int toolUseCount = 0;
                List<String> turnTouched = new ArrayList<>();
                for (ContentBlock block : assistant.message().message().content()) {
                    if (block instanceof TextBlock(String text1)) {
                        text.append(text1);
                    } else if (block instanceof ToolUseBlock tub) {
                        toolUseCount++;
                        String path = writtenFilePath(tub);
                        if (path != null) {
                            touched.add(path);
                            turnTouched.add(path);
                        }
                    }
                }
                String summary = text.toString().trim();
                store.addDreamTurn(taskId,
                    new DreamTaskDetails.DreamTurn(summary, toolUseCount), turnTouched);
                if (!summary.isEmpty() || toolUseCount > 0) {
                    store.updateProgressSummary(taskId,
                        summary.isEmpty() ? "(" + toolUseCount + " tools)" : summary);
                }
            }
        }
        return touched;
    }

    private static String writtenFilePath(ContentBlock block) {
        if (!(block instanceof ToolUseBlock tub)) {
            return null;
        }
        if (!Strings.CS.equals("Edit", tub.name()) && !Strings.CS.equals("Write", tub.name())) {
            return null;
        }
        JsonNode input = tub.input();
        if (input == null || !input.has("file_path")) {
            return null;
        }
        JsonNode fp = input.get("file_path");
        return (fp == null || fp.isNull()) ? null : fp.asText();
    }

    /** Live state for one in-flight dream, captured at fork time. */
    private static final class DreamRun {
        final AbortController controller;
        final DreamLock lock;
        final long priorMtime;
        final TaskStore store;
        final Thread runnerThread;
        final AtomicBoolean aborted = new AtomicBoolean(false);

        DreamRun(AbortController controller, DreamLock lock, long priorMtime,
                TaskStore store, Thread runnerThread) {
            this.controller = controller;
            this.lock = lock;
            this.priorMtime = priorMtime;
            this.store = store;
            this.runnerThread = runnerThread;
        }
    }
}
