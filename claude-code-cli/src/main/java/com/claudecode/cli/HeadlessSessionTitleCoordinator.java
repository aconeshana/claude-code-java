package com.claudecode.cli;

import com.claudecode.runtime.query.QuerySession;
import com.claudecode.core.engine.TranscriptSink;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.effort.EffortHelpers;
import org.apache.commons.lang3.StringUtils;
import com.claudecode.services.titles.TerminalSessionTitleGenerator;
import com.claudecode.session.SessionManager;
import com.claudecode.core.process.SubprocessEnvironment;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

final class HeadlessSessionTitleCoordinator {

    private static final List<String> EXCLUDED_PREFIXES = List.of(
        "<command-name>",
        "<local-command-stdout>",
        "<local-command-stderr>",
        "<bash-input>",
        "<local-command-caveat>");

    @FunctionalInterface
    interface TitleRequest {
        CompletableFuture<String> generate(String description);
    }

    private final QuerySession engine;
    private final Supplier<String> existingTitle;
    private final TitleRequest request;
    private final AtomicBoolean attempted;
    private volatile CompletableFuture<String> pending =
        CompletableFuture.completedFuture(null);

    static HeadlessSessionTitleCoordinator create(
            QuerySession engine,
            TerminalSessionTitleGenerator generator,
            String cwd,
            String initialTitle) {
        String launchTitle = StringUtils.trimToNull(initialTitle);
        Supplier<String> existingTitle = () -> {
            if (launchTitle != null) return launchTitle;
            try {
                return new SessionManager(cwd).readCustomTitle(engine.conversation().getSessionId());
            } catch (RuntimeException _) {
                return null;
            }
        };
        TitleRequest request = generator == null ? null : description -> {
            String sessionId = engine.conversation().getSessionId();
            String model = engine.configuration().getConfig().model();
            String effort = EffortHelpers.resolveAppliedEffort(
                model, engine.configuration().getConfig().effortValue());
            return generator.generateAsync(description, sessionId,
                LlmClientAdapter.requestMetadata(sessionId), effort, true);
        };
        return new HeadlessSessionTitleCoordinator(
            engine, SubprocessEnvironment.snapshot(), existingTitle, request);
    }

    HeadlessSessionTitleCoordinator(
            QuerySession engine,
            Map<String, String> environment,
            Supplier<String> existingTitle,
            TitleRequest request) {
        this.engine = engine;
        this.existingTitle = existingTitle != null ? existingTitle : () -> null;
        this.request = request;
        boolean disabled = request == null
            || present(environment, "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC")
            || present(environment, "CLAUDE_CODE_DISABLE_TERMINAL_TITLE")
            || engine.conversation().getMessages().stream().anyMatch(message -> !(message instanceof SystemMessage))
            || hasExistingTitle();
        this.attempted = new AtomicBoolean(disabled);
    }

    CompletableFuture<String> maybeGenerate(Object content) {
        String description = extractText(content);
        if (StringUtils.isBlank(description) || excluded(description)
                || !attempted.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }

        String sessionId = engine.conversation().getSessionId();
        CompletableFuture<String> started;
        try {
            started = request.generate(description);
        } catch (RuntimeException _) {
            attempted.set(false);
            return CompletableFuture.completedFuture(null);
        }
        if (started == null) {
            attempted.set(false);
            return CompletableFuture.completedFuture(null);
        }

        pending = started.handle((title, error) -> {
            String normalized = error == null && title != null ? title.trim() : null;
            if (StringUtils.isEmpty(normalized)) {
                attempted.set(false);
                return null;
            }
            if (!hasExistingTitle()) {
                TranscriptSink transcript = engine.execution().getTranscriptSink();
                if (transcript != null) {
                    transcript.recordAiTitle(sessionId, normalized);
                }
            }
            return normalized;
        });
        return pending;
    }

    void markTitlePresent() {
        attempted.set(true);
    }

    boolean attempted() {
        return attempted.get();
    }

    private boolean hasExistingTitle() {
        try {
            String title = existingTitle.get();
            return StringUtils.isNotBlank(title);
        } catch (RuntimeException _) {
            return false;
        }
    }

    private static boolean present(Map<String, String> environment, String name) {
        if (environment == null) return false;
        String value = environment.get(name);
        return StringUtils.isNotEmpty(value);
    }

    private static boolean excluded(String description) {
        String text = description.stripLeading();
        return EXCLUDED_PREFIXES.stream().anyMatch(text::startsWith);
    }

    private static String extractText(Object content) {
        if (content instanceof String text) return text;
        if (!(content instanceof MessageContent message)) return null;
        if (message.isText()) return message.text();
        List<ContentBlock> blocks = message.blocks();
        if (blocks == null) return null;
        return blocks.stream()
            .filter(TextBlock.class::isInstance)
            .map(TextBlock.class::cast)
            .map(TextBlock::text)
            .filter(StringUtils::isNotBlank)
            .reduce((left, right) -> left + "\n" + right)
            .orElse(null);
    }
}
