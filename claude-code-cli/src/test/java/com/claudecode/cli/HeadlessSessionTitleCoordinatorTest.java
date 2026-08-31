package com.claudecode.cli;

import com.claudecode.runtime.query.DefaultQuerySession;
import com.claudecode.runtime.query.QuerySessionSpec;
import com.claudecode.core.engine.TranscriptSink;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageFactory;
import com.claudecode.core.message.SystemMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class HeadlessSessionTitleCoordinatorTest {

    @Test
    void freshHeadlessPromptStartsOneRequestAndPersistsTheTitle() {
        DefaultQuerySession engine = engine();
        List<String> writes = new ArrayList<>();
        engine.setTranscriptSink(sink(writes));
        AtomicInteger requests = new AtomicInteger();
        HeadlessSessionTitleCoordinator titles = coordinator(
            engine, Map.of(), () -> null, _ -> {
                requests.incrementAndGet();
                return CompletableFuture.completedFuture("List MCP resources");
            });

        assertEquals("List MCP resources",
            titles.maybeGenerate("List the paginated MCP directory resource.").join());
        assertNull(titles.maybeGenerate("A different later user prompt").join());

        assertEquals(1, requests.get());
        assertEquals(List.of("ai-title:List MCP resources"), writes);
    }

    @Test
    void systemOnlyHistoryStillCountsAsFreshButConversationHistoryDoesNot() {
        DefaultQuerySession systemOnly = engine();
        systemOnly.loadMessages(List.of(new SystemMessage(
            "system", "init", "info", "startup")));
        AtomicInteger requests = new AtomicInteger();
        HeadlessSessionTitleCoordinator allowed = coordinator(
            systemOnly, Map.of(), () -> null, _ -> {
                requests.incrementAndGet();
                return CompletableFuture.completedFuture("Title");
            });
        allowed.maybeGenerate("A sufficiently long first prompt").join();

        DefaultQuerySession resumed = engine();
        resumed.loadMessages(List.of(MessageFactory.createUserMessage("prior turn")));
        HeadlessSessionTitleCoordinator suppressed = coordinator(
            resumed, Map.of(), () -> null, _ -> {
                requests.incrementAndGet();
                return CompletableFuture.completedFuture("Wrong");
            });
        suppressed.maybeGenerate("A sufficiently long resumed prompt").join();

        assertEquals(1, requests.get());
    }

    @Test
    void exactEnvironmentAndExistingTitleGatesMatch197() {
        for (Map<String, String> env : List.of(
                Map.of("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1"),
                Map.of("CLAUDE_CODE_DISABLE_TERMINAL_TITLE", "1"))) {
            AtomicInteger requests = new AtomicInteger();
            HeadlessSessionTitleCoordinator titles = coordinator(
                engine(), env, () -> null, _ -> {
                    requests.incrementAndGet();
                    return CompletableFuture.completedFuture("Wrong");
                });
            titles.maybeGenerate("A sufficiently long first prompt").join();
            assertEquals(0, requests.get());
        }

        AtomicInteger telemetryOnlyRequests = new AtomicInteger();
        HeadlessSessionTitleCoordinator telemetryOnly = coordinator(
            engine(), Map.of("DISABLE_TELEMETRY", "1"), () -> null, _ -> {
                telemetryOnlyRequests.incrementAndGet();
                return CompletableFuture.completedFuture("Allowed");
            });
        telemetryOnly.maybeGenerate("A sufficiently long first prompt").join();
        assertEquals(1, telemetryOnlyRequests.get(),
            "DISABLE_TELEMETRY is not the binary's essential-traffic gate");

        AtomicInteger existingTitleRequests = new AtomicInteger();
        HeadlessSessionTitleCoordinator existingTitle = coordinator(
            engine(), Map.of(), () -> "Already named", _ -> {
                existingTitleRequests.incrementAndGet();
                return CompletableFuture.completedFuture("Wrong");
            });
        existingTitle.maybeGenerate("A sufficiently long first prompt").join();
        assertEquals(0, existingTitleRequests.get());
    }

    @Test
    void explicitLaunchNameSuppressesOnlyAutomaticTitleGeneration() {
        AtomicInteger requests = new AtomicInteger();
        HeadlessSessionTitleCoordinator titles = coordinator(
            engine(), Map.of(), () -> "CLI session name", _ -> {
                requests.incrementAndGet();
                return CompletableFuture.completedFuture("Wrong");
            });

        assertNull(titles.maybeGenerate("First real SDK prompt").join());
        assertEquals(0, requests.get());
        assertTrue(titles.attempted());
    }

    @Test
    void excludedSyntheticPrefixesDoNotConsumeTheFirstRealPrompt() {
        AtomicInteger requests = new AtomicInteger();
        HeadlessSessionTitleCoordinator titles = coordinator(
            engine(), Map.of(), () -> null, _ -> {
                requests.incrementAndGet();
                return CompletableFuture.completedFuture("Real title");
            });

        assertNull(titles.maybeGenerate(
            "<command-name>/compact</command-name>").join());
        assertFalse(titles.attempted());
        assertEquals("Real title",
            titles.maybeGenerate("This is the first real user question").join());
        assertTrue(titles.attempted());
        assertEquals(1, requests.get());
    }

    @Test
    void nullResultReopensTheGateForTheNextRealPrompt() {
        AtomicInteger requests = new AtomicInteger();
        HeadlessSessionTitleCoordinator titles = coordinator(
            engine(), Map.of(), () -> null, _ -> {
                int request = requests.incrementAndGet();
                return CompletableFuture.completedFuture(request == 1 ? null : "Second title");
            });

        assertNull(titles.maybeGenerate("First sufficiently long prompt").join());
        assertFalse(titles.attempted());
        assertEquals("Second title",
            titles.maybeGenerate("Second sufficiently long prompt").join());
        assertEquals(2, requests.get());
    }

    private static HeadlessSessionTitleCoordinator coordinator(
            DefaultQuerySession engine,
            Map<String, String> env,
            Supplier<String> existingTitle,
            HeadlessSessionTitleCoordinator.TitleRequest request) {
        return new HeadlessSessionTitleCoordinator(
            engine, env, existingTitle, request);
    }

    private static DefaultQuerySession engine() {
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new ClaudeCodeCliTest.MockStreamingClient("OK"))
            .build());
        engine.switchToSession("session-id");
        return engine;
    }

    private static TranscriptSink sink(List<String> writes) {
        return new TranscriptSink() {
            @Override public void record(String sessionId, Message message) { }

            @Override public void recordAiTitle(String sessionId, String title) {
                writes.add("ai-title:" + title);
            }
        };
    }
}
