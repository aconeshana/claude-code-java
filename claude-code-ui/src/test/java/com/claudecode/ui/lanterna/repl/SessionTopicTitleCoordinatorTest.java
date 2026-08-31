package com.claudecode.ui.lanterna.repl;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;


class SessionTopicTitleCoordinatorTest {

    @Test
    void completedTitleMayPersistBeforeMainPreambleLikeReleasedFastResponseRace() {
        List<String> events = new ArrayList<>();
        SessionTopicTitleCoordinator coordinator = new SessionTopicTitleCoordinator(
            false,
            prompt -> {
                events.add("title:" + prompt);
                return CompletableFuture.completedFuture("Project inspection");
            },
            title -> events.add("applied:" + title));

        coordinator.onUserQuery("Inspect this project", false);
        events.add("main");

        assertEquals(List.of(
            "title:Inspect this project",
            "applied:Project inspection",
            "main"), events,
            "2.1.197 can append ai-title before file-history/user when the helper wins the race");
    }

    @Test
    void delayedTitlePersistsAfterMainPreambleWhenMainWinsTheRace() {
        List<String> events = new ArrayList<>();
        CompletableFuture<String> generated = new CompletableFuture<>();
        SessionTopicTitleCoordinator coordinator = new SessionTopicTitleCoordinator(
            false, _ -> generated, title -> events.add("applied:" + title));

        coordinator.onUserQuery("Inspect this project", false);
        events.add("main");
        generated.complete("Project inspection");

        assertEquals(List.of("main", "applied:Project inspection"), events);
    }

    @Test
    void pendingAsyncTitleNeverDelaysMainSubmission() {
        List<String> events = new ArrayList<>();
        CompletableFuture<String> generated = new CompletableFuture<>();
        SessionTopicTitleCoordinator coordinator = new SessionTopicTitleCoordinator(
            false,
            _ -> generated,
            title -> events.add("applied:" + title));

        assertTimeout(Duration.ofMillis(100),
            () -> coordinator.onUserQuery("Inspect this project", false));
        events.add("main");
        assertEquals(List.of("main"), events);
        generated.complete("Project inspection");
        assertEquals(List.of("main", "applied:Project inspection"), events);
    }

    @Test
    void skipsSyntheticAndSlashInputsThenUsesFirstRealPromptOnlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        SessionTopicTitleCoordinator coordinator = new SessionTopicTitleCoordinator(
            false,
            _ -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture("Title");
            },
            _ -> { });

        coordinator.onUserQuery("<command-message>run skill</command-message>", false);
        coordinator.onUserQuery("expanded skill prompt", true);
        coordinator.onUserQuery("<bash-input>pwd</bash-input>", false);
        coordinator.onUserQuery("Real question", false);
        coordinator.onUserQuery("Second question", false);

        assertEquals(1, calls.get());
    }

    @Test
    void resumedSessionsStayAttemptedWhileFailedFreshGenerationMayRetry() {
        AtomicInteger resumedCalls = new AtomicInteger();
        SessionTopicTitleCoordinator resumed = new SessionTopicTitleCoordinator(
            true,
            _ -> {
                resumedCalls.incrementAndGet();
                return CompletableFuture.completedFuture("Never");
            },
            _ -> { });
        resumed.onUserQuery("Follow up", false);
        assertEquals(0, resumedCalls.get());

        AtomicInteger freshCalls = new AtomicInteger();
        SessionTopicTitleCoordinator fresh = new SessionTopicTitleCoordinator(
            false,
            _ -> {
                freshCalls.incrementAndGet();
                return CompletableFuture.completedFuture(freshCalls.get() == 1 ? null : "Recovered");
            },
            _ -> { });
        fresh.onUserQuery("First", false);
        fresh.onUserQuery("Retry", false);
        assertEquals(2, freshCalls.get());
    }

    @Test
    void explicitlyNamedSessionSuppressesAutomaticTitleUntilClear() {
        AtomicInteger calls = new AtomicInteger();
        SessionTopicTitleCoordinator named = new SessionTopicTitleCoordinator(
            true,
            _ -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture("Generated");
            },
            _ -> { });

        named.onUserQuery("First turn", false);
        assertEquals(0, calls.get());

        named.resetForNewSession();
        named.onUserQuery("After clear", false);
        assertEquals(1, calls.get());
    }
}
