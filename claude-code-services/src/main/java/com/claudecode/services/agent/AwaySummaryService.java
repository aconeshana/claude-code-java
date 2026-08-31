package com.claudecode.services.agent;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.api.LlmClient;
import com.claudecode.core.message.Message;
import com.claudecode.services.config.RuntimeSettings;
import com.claudecode.services.model.SideQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Generates a short "while you were away" recap when the user has been idle.
 */
public class AwaySummaryService {

    private static final Logger log = LoggerFactory.getLogger(AwaySummaryService.class);


    private static final long BLUR_DELAY_MS = 5L * 60_000;

    /** Polling cadence for the idle detector. */
    private static final long POLL_INTERVAL_MS = 30_000;

    private final LlmClient llmClient;

    public AwaySummaryService(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /** Whether the feature is enabled via {@code settings.awaySummaryEnabled}. */
    public boolean isEnabled() {
        return RuntimeSettings.loadAwaySummaryEnabled();
    }


    public String generateAwaySummary(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return null;
        try {
            SideQuery sq = new SideQuery(llmClient);
            List<Message> recent = messages.size() > 30
                ? messages.subList(messages.size() - 30, messages.size())
                : messages;
            String userPrompt = buildAwaySummaryPrompt(recent);
            String text = sq.queryText(
                SideQuery.resolveSmallFastModel(),
                    "",
                userPrompt,
                300);
            if (StringUtils.isBlank(text)) return null;
            return text.trim();
        } catch (Exception e) {
            log.debug("[awaySummary] generation failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Generates (if enabled) and publishes the recap via {@code sink}. No-op
     * when disabled or when generation yields nothing.
     */
    public void maybePublishAwaySummary(List<Message> messages, Consumer<String> sink) {
        if (!isEnabled()) return;
        String text = generateAwaySummary(messages);
        if (text != null) sink.accept(text);
    }

    /**
     * Starts the idle detector. Every {@link #POLL_INTERVAL_MS} it samples
     * {@code messagesSupplier}; when the message count is unchanged for
     * {@link #BLUR_DELAY_MS} and no recap has been posted for the current idle
     * stretch, it generates one recap and posts it via {@code sink}. The
     * returned {@link Runnable} stops the detector (call on shutdown).
     *
     * <p>Returns a no-op {@link Runnable} when the feature is disabled.
     */
    public Runnable startIdleWatcher(Supplier<List<Message>> messagesSupplier,
                                     Consumer<String> sink) {
        if (!isEnabled()) return () -> {};

        // Ownership of this executor is transferred to the caller: we return
        // `executor::shutdownNow` as the stopper (line below), so it must NOT
        // be closed here — hence the resource-inspection suppression.
        //noinspection resource
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "away-summary-detector");
            t.setDaemon(true);
            return t;
        });
        AtomicLong lastChangeMs = new AtomicLong(System.currentTimeMillis());
        AtomicInteger lastCount = new AtomicInteger(-1);
        AtomicBoolean published = new AtomicBoolean(false);

        executor.scheduleAtFixedRate(() -> {
            try {
                List<Message> msgs = messagesSupplier.get();
                if (msgs == null) return;
                int n = msgs.size();
                if (n != lastCount.get()) {
                    lastCount.set(n);
                    lastChangeMs.set(System.currentTimeMillis());
                    published.set(false);
                    return;
                }
                if (!published.get()
                        && System.currentTimeMillis() - lastChangeMs.get() >= BLUR_DELAY_MS) {
                    String text = generateAwaySummary(msgs);
                    if (text != null) {
                        sink.accept(text);
                        published.set(true);
                    }
                }
            } catch (Exception e) {
                log.debug("[awaySummary] detector tick failed: {}", e.getMessage());
            }
        }, BLUR_DELAY_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);

        return executor::shutdownNow;
    }


    private static String buildAwaySummaryPrompt(List<Message> recent) {
        StringBuilder sb = new StringBuilder();
        sb.append("The user stepped away and is coming back. Write exactly 1-3 short sentences. ")
          .append("Start by stating the high-level task — what they are building or debugging, ")
          .append("not implementation details. Next: the concrete next step. ")
          .append("Skip status reports and commit recaps.\n\n");
        sb.append("Recent conversation:\n");
        for (Message m : recent) {
            sb.append("- ").append(summarizeMessage(m)).append('\n');
        }
        return sb.toString();
    }

    private static String summarizeMessage(Message m) {
        if (m == null) return "(empty)";
        // Best-effort one-line description: message type is enough context for
        // a high-level recap; avoids dumping full tool I/O into the prompt.
        return m.type();
    }
}
