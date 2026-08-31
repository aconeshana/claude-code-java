package com.claudecode.ui.lanterna.repl;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Startup boundary the CLI supplies to the fully pre-mounted REPL scene. */
public record ReplStartupReadiness(
        CompletionStage<Void> inputSemanticReady,
        Consumer<String> milestone) {

    public ReplStartupReadiness {
        inputSemanticReady = Objects.requireNonNullElseGet(
            inputSemanticReady, () -> CompletableFuture.completedFuture(null));
        milestone = milestone == null ? _ -> { } : milestone;
    }

    public ReplStartupReadiness(CompletionStage<Void> inputSemanticReady) {
        this(inputSemanticReady, null);
    }

    public static ReplStartupReadiness ready() {
        return new ReplStartupReadiness(CompletableFuture.completedFuture(null));
    }

    public void mark(String name) {
        milestone.accept(name);
    }
}
