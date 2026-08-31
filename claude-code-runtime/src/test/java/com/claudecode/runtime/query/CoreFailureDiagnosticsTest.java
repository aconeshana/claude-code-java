package com.claudecode.runtime.query;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.engine.MessageCompactor;
import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.serialization.JsonUtils;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreFailureDiagnosticsTest {

    private static final StreamingClient NOOP_CLIENT = new StreamingClient() {
        @Override
        public Iterator<StreamingEvent> createStream(StreamRequest request) {
            return Collections.emptyIterator();
        }

        @Override
        public String getModel() {
            return "test-model";
        }
    };

    @Test
    void toolExceptionLogRetainsCorrelationAndCauseWithoutToolInput() {
        IllegalStateException failure = new IllegalStateException(
            "DO_NOT_LOG_TOOL_INPUT", new IOException("DO_NOT_LOG_TOOL_INPUT_CAUSE"));
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .toolExecutor((_, _, _) -> { throw failure; })
            .sessionIdentity(SessionIdentity.of("session-tool-diagnostic"))
            .agentId("agent-diagnostic")
            .build());
        var input = JsonUtils.getMapper().createObjectNode()
            .put("command", "DO_NOT_LOG_TOOL_INPUT");

        try (LogCapture logs = new LogCapture(ToolExecution.class)) {
            ToolExecution.ToolStep result = ToolExecution.execute(
                new ToolUseBlock("tool-use-diagnostic", "Bash", input),
                engine, null, _ -> { }, "assistant-diagnostic");

            assertTrue(result.error());
            ILoggingEvent event = logs.eventContaining("Tool execution failed");
            assertTrue(Strings.CS.contains(event.getFormattedMessage(), "session-tool-diagnostic"));
            assertTrue(Strings.CS.contains(event.getFormattedMessage(), "tool-use-diagnostic"));
            assertTrue(Strings.CS.contains(event.getFormattedMessage(), "agent-diagnostic"));
            assertFalse(Strings.CS.contains(event.getFormattedMessage(), "DO_NOT_LOG_TOOL_INPUT"));
            assertThrowable(event, IllegalStateException.class, IOException.class);
            assertRedacted(event, "DO_NOT_LOG_TOOL_INPUT", "DO_NOT_LOG_TOOL_INPUT_CAUSE");
        }
    }

    @Test
    void sessionStartHookFailureLogRetainsSessionAndCause() {
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .sessionIdentity(SessionIdentity.of("session-hook-diagnostic"))
            .build());

        try (LogCapture logs = new LogCapture(DefaultQuerySession.class)) {
            engine.setHookDispatcher(new NoopHookDispatcher() {
                @Override
                public void dispatchSessionStart(String trigger) {
                    throw new IllegalStateException(
                        "DO_NOT_LOG_HOOK_OUTPUT", new IOException("DO_NOT_LOG_HOOK_CAUSE"));
                }
            });

            ILoggingEvent event = logs.eventContaining("SessionStart hook failed");
            assertTrue(Strings.CS.contains(event.getFormattedMessage(), "session-hook-diagnostic"));
            assertThrowable(event, IllegalStateException.class, IOException.class);
            assertRedacted(event, "DO_NOT_LOG_HOOK_OUTPUT", "DO_NOT_LOG_HOOK_CAUSE");
        }
    }

    @Test
    void transcriptFailureLogRetainsMessageIdentityWithoutMessageContent() {
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .sessionIdentity(SessionIdentity.of("session-transcript-diagnostic"))
            .build());
        engine.setTranscriptSink((_, _) -> {
            throw new IllegalStateException(
                "DO_NOT_LOG_TRANSCRIPT_CONTENT",
                new IOException("DO_NOT_LOG_TRANSCRIPT_CAUSE"));
        });

        try (LogCapture logs = new LogCapture(DefaultQuerySession.class)) {
            engine.appendTranscriptMessage(new UserMessage(
                "message-transcript-diagnostic",
                MessageContent.ofText("DO_NOT_LOG_TRANSCRIPT_CONTENT")));

            ILoggingEvent event = logs.eventContaining("Transcript sink failed");
            assertTrue(Strings.CS.contains(
                event.getFormattedMessage(), "session-transcript-diagnostic"));
            assertTrue(Strings.CS.contains(
                event.getFormattedMessage(), "message-transcript-diagnostic"));
            assertTrue(Strings.CS.contains(event.getFormattedMessage(), "user"));
            assertFalse(Strings.CS.contains(
                event.getFormattedMessage(), "DO_NOT_LOG_TRANSCRIPT_CONTENT"));
            assertThrowable(event, IllegalStateException.class, IOException.class);
            assertRedacted(event, "DO_NOT_LOG_TRANSCRIPT_CONTENT",
                "DO_NOT_LOG_TRANSCRIPT_CAUSE");
        }
    }

    @Test
    void terminalStreamFailureLogRetainsQueryCorrelationAndCause() {
        IllegalStateException failure = new IllegalStateException(
            "DO_NOT_LOG_MODEL_RESPONSE", new IOException("DO_NOT_LOG_MODEL_CAUSE"));
        List<StreamingClient.StreamingEvent> events = List.of(
            new StreamingClient.StreamingEvent.ErrorEvent(failure));
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .model("diagnostic-model")
            .sessionIdentity(SessionIdentity.of("session-query-diagnostic"))
            .agentId("agent-query-diagnostic")
            .build());
        List<Message> history = List.of(new UserMessage(
            UUID.randomUUID().toString(), MessageContent.ofText("hello")));
        engine.getMutableMessages().addAll(history);
        QueryParams params = QueryParams.builder()
            .messages(history)
            .systemPrompt("Be helpful")
            .model("diagnostic-model")
            .querySource("user")
            .deps(new QueryDeps() {
                @Override
                public Iterator<StreamingClient.StreamingEvent> callModel(
                        StreamingClient.StreamRequest request) {
                    return events.iterator();
                }

                @Override
                public MessageCompactor.MicrocompactResult microcompact(List<Message> messages) {
                    return new MessageCompactor.MicrocompactResult(messages);
                }

                @Override
                public boolean shouldAutoCompact(
                        List<Message> messages, String model, String querySource) {
                    return false;
                }

                @Override
                public AutoCompactResult autocompact(
                        List<Message> messages, String model, String querySource,
                        AutoCompactTrackingState tracking, String customInstructions,
                        long snipTokensFreed) {
                    return new AutoCompactResult(null, null);
                }

                @Override
                public String uuid() {
                    return UUID.randomUUID().toString();
                }
            })
            .build();

        try (LogCapture logs = new LogCapture(QueryLoop.class)) {
            drain(new QueryLoop(engine, params));

            ILoggingEvent event = logs.eventContaining("Model stream failed");
            assertTrue(Strings.CS.contains(event.getFormattedMessage(), "session-query-diagnostic"));
            assertTrue(Strings.CS.contains(event.getFormattedMessage(), "diagnostic-model"));
            assertTrue(Strings.CS.contains(event.getFormattedMessage(), "querySource=user"));
            assertTrue(Strings.CS.contains(event.getFormattedMessage(), "agent-query-diagnostic"));
            assertThrowable(event, IllegalStateException.class, IOException.class);
            assertRedacted(event, "DO_NOT_LOG_MODEL_RESPONSE", "DO_NOT_LOG_MODEL_CAUSE");
        }
    }

    @Test
    void startupBarrierFailureLogRetainsSessionAndCause() {
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .sessionIdentity(SessionIdentity.of("session-startup-diagnostic"))
            .build());
        IllegalStateException failure = new IllegalStateException(
            "DO_NOT_LOG_STARTUP_OUTPUT", new IOException("DO_NOT_LOG_STARTUP_CAUSE"));
        engine.addStartupBarrier(CompletableFuture.failedFuture(failure));

        try (LogCapture logs = new LogCapture(DefaultQuerySession.class)) {
            engine.sealStartupReadiness().toCompletableFuture().join();

            ILoggingEvent event = logs.eventContaining("Startup barrier failed");
            assertTrue(Strings.CS.contains(
                event.getFormattedMessage(), "session-startup-diagnostic"));
            assertThrowable(event, IllegalStateException.class, IOException.class);
            assertRedacted(event, "DO_NOT_LOG_STARTUP_OUTPUT", "DO_NOT_LOG_STARTUP_CAUSE");
        }
    }

    private static List<SDKMessage> drain(Iterator<SDKMessage> messages) {
        List<SDKMessage> drained = new ArrayList<>();
        messages.forEachRemaining(drained::add);
        return drained;
    }

    private static void assertThrowable(
            ILoggingEvent event, Class<? extends Throwable> failure,
            Class<? extends Throwable> cause) {
        assertNotNull(event.getThrowableProxy());
        assertTrue(Strings.CS.contains(
            event.getFormattedMessage(), "failureType=" + failure.getName()));
        assertEquals(failure.getName(), event.getThrowableProxy().getMessage());
        assertNotNull(event.getThrowableProxy().getCause());
        assertEquals(cause.getName(), event.getThrowableProxy().getCause().getMessage());
    }

    private static void assertRedacted(ILoggingEvent event, String failure, String cause) {
        assertFalse(Strings.CS.contains(event.getThrowableProxy().getMessage(), failure));
        assertFalse(Strings.CS.contains(event.getThrowableProxy().getCause().getMessage(), cause));
    }

    private static class NoopHookDispatcher implements HookDispatcher {
        @Override
        public boolean dispatchPreToolUse(String toolName, JsonNode input,
                                          String toolUseId) {
            return true;
        }

        @Override
        public void dispatchPostToolUse(String toolName, JsonNode input,
                                        JsonNode output,
                                        String toolUseId) { }

        @Override
        public void dispatchUserPromptSubmit(String prompt) { }

        @Override
        public void dispatchSessionStart(String trigger) { }

        @Override
        public void dispatchStop(String reason) { }
    }

    private static final class LogCapture implements AutoCloseable {
        private final Logger logger;
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        private LogCapture(Class<?> owner) {
            logger = (Logger) LoggerFactory.getLogger(owner);
            appender.start();
            logger.addAppender(appender);
        }

        private ILoggingEvent eventContaining(String fragment) {
            return appender.list.stream()
                .filter(event -> Strings.CS.contains(event.getFormattedMessage(), fragment))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                    "Expected log containing '" + fragment + "' but got "
                        + appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList()));
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
