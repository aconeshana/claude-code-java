package com.claudecode.ui.lanterna.repl;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.commands.XmlConstants;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Owns the one-shot first-real-user-message title-generation lifecycle for the REPL.
 */
final class SessionTopicTitleCoordinator {

    private final Function<String, CompletableFuture<String>> generator;
    private final Consumer<String> titleSink;
    private boolean attempted;
    private long generation;
    private long acceptedGeneration = Long.MIN_VALUE;

    SessionTopicTitleCoordinator(boolean initiallyAttempted,
                                 Function<String, CompletableFuture<String>> generator,
                                 Consumer<String> titleSink) {
        this.attempted = initiallyAttempted;
        this.generator = generator;
        this.titleSink = Objects.requireNonNull(titleSink, "titleSink");
    }

    void onUserQuery(String text, boolean slashInvocation) {
        if (generator == null || slashInvocation || isSynthetic(text)) return;

        final long requestGeneration;
        synchronized (this) {
            if (attempted) return;
            attempted = true;
            requestGeneration = generation;
        }

        CompletableFuture<String> future;
        try {
            // The generator establishes the streaming request before returning,
// preserving the title-before-main request order.
            future = generator.apply(text);
        } catch (Exception _) {
            resetFailedAttempt(requestGeneration);
            return;
        }
        if (future == null) {
            resetFailedAttempt(requestGeneration);
            return;
        }
        future.whenComplete((title, error) -> {
            String normalized = error == null && title != null ? title.trim() : "";
            if (normalized.isEmpty()) {
                resetFailedAttempt(requestGeneration);
                return;
            }
            acceptGeneratedTitle(requestGeneration, normalized);
        });
    }

    synchronized void resetForNewSession() {
        generation++;
        attempted = false;
    }

    synchronized void markExistingSession() {
        generation++;
        attempted = true;
    }

    private synchronized void resetFailedAttempt(long requestGeneration) {
        if (generation == requestGeneration) {
            attempted = false;
        }
    }

    private synchronized void acceptGeneratedTitle(long requestGeneration, String title) {
        if (generation != requestGeneration || !attempted
                || acceptedGeneration == requestGeneration) return;
        acceptedGeneration = requestGeneration;
        // Keep the acceptance marker and observable sink application atomic. Otherwise
        // the completion thread can set acceptedGeneration, the bounded waiter can see
        // the completed future and return, and the main turn can start before this sink
        // actually appends ai-title.
        titleSink.accept(title);
    }

    private static boolean isSynthetic(String text) {
        if (StringUtils.isBlank(text)) return true;
        return startsWithTag(text, XmlConstants.LOCAL_COMMAND_STDOUT_TAG)
            || startsWithTag(text, XmlConstants.COMMAND_MESSAGE_TAG)
            || startsWithTag(text, XmlConstants.COMMAND_NAME_TAG)
            || startsWithTag(text, XmlConstants.BASH_INPUT_TAG);
    }

    private static boolean startsWithTag(String text, String tag) {
        return Strings.CS.startsWith(text, "<" + tag + ">");
    }
}
