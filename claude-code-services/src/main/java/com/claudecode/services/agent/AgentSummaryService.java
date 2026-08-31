package com.claudecode.services.agent;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.api.LlmClient;
import com.claudecode.core.engine.SubAgentProgressSummarizer;
import com.claudecode.core.message.Message;
import com.claudecode.services.config.RuntimeSettings;
import com.claudecode.services.model.SideQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Periodic background summarization for coordinator-mode sub-agents.
 */
public class AgentSummaryService implements SubAgentProgressSummarizer, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentSummaryService.class);


    private static final long SUMMARY_INTERVAL_MS = 30_000;

/** Best-effort wait for in-flight ticks to bail out on {@link #close}. */
    private static final long SHUTDOWN_AWAIT_SECONDS = 2;

    private final LlmClient llmClient;

    /**
     * Shared scheduling pool for the whole service lifetime. Lazily created on
     * the first {@link #startSummarization} call and nulled in {@link #close}.
     */
    private ScheduledExecutorService executor;

/** Set once {@link #close} runs so late {@link #startSummarization} calls no-op. */
    private boolean closed = false;

    private final Object lock = new Object();

    public AgentSummaryService(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /** Whether the feature is enabled via {@code settings.agentProgressSummariesEnabled}. */
    public boolean isEnabled() {
        return RuntimeSettings.loadAgentProgressSummariesEnabled();
    }

    /**
     * Starts periodic progress summarization for a running sub-agent.
     */
    @Override public Runnable startSummarization(String taskId,
                                       Supplier<List<Message>> transcriptSupplier,
                                       BiConsumer<String, String> onSummary) {
        if (!isEnabled()) return () -> {};

        // Lazily create the shared scheduling pool under lock. The pool is
// owned by this service and torn down in close — it must NOT be
        // closed here, so we read the field directly rather than hold the
        // AutoCloseable returned from a helper (avoids the spurious
        // try-with-resources warning).
        synchronized (lock) {
            if (closed) return () -> {};
            if (executor == null) {
                executor = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "agent-summary-pool");
                    t.setDaemon(true);
                    return t;
                });
            }
        }

        final String safeTaskId = taskId != null ? taskId : "unknown";
        ScheduledFuture<?> future = executor.scheduleAtFixedRate(() -> {
            try {
                List<Message> transcript = transcriptSupplier.get();
                if (transcript == null || transcript.size() < 3) return;
                SideQuery sq = new SideQuery(llmClient);
                String prompt = buildSummaryPrompt();
                String text = sq.queryText(
                    SideQuery.resolveSmallFastModel(),
                    "",
                    prompt + "\n\nRecent conversation:\n" + recentTypes(transcript),
                    64);
                if (StringUtils.isNotBlank(text)) {
                    onSummary.accept(safeTaskId, text.trim());
                }
            } catch (Exception e) {
                log.debug("[agentSummary] tick failed: {}", e.getMessage());
            }
        }, SUMMARY_INTERVAL_MS, SUMMARY_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // Per-task stopper: cancel only this task's future, never the shared pool.
        return () -> future.cancel(false);
    }

    /**
     * Tears down the shared scheduling pool. Idempotent — safe to call from both
     * the explicit engine-shutdown path and a JVM shutdown hook. After this
     * returns, further {@link #startSummarization} calls are no-ops.
     */
    @Override
    public void close() {
        ScheduledExecutorService ex;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            ex = executor;
            executor = null;
        }
        if (ex != null) {
            ex.shutdownNow();
            try {
                if (!ex.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                    log.warn("[agentSummary] scheduler did not terminate within {}s", SHUTDOWN_AWAIT_SECONDS);
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }


    private static String buildSummaryPrompt() {
        return """
            Describe your most recent action in 3-5 words using present tense (-ing). \
            Name the file or function, not the branch. Do not use tools.
            Good: "Reading runAgent.ts"
            Good: "Fixing null check in validate.ts"
            Good: "Running auth module tests"
            Bad (past tense): "Analyzed the branch diff"
            Bad (too vague): "Investigating the issue"
            Bad (too long): "Reviewing full branch diff and AgentTool.tsx integration"
            Bad (branch name): "Analyzed adam/background-summary branch diff\"""";
    }

    private static String recentTypes(List<Message> transcript) {
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, transcript.size() - 15);
        for (int i = start; i < transcript.size(); i++) {
            Message m = transcript.get(i);
            sb.append("- ").append(m == null ? "?" : m.type()).append('\n');
        }
        return sb.toString();
    }
}
