package com.claudecode.runtime.query;

import com.claudecode.core.engine.*;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.attachment.AttachmentContext;
import com.claudecode.core.message.AttachmentPayload;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.PastedContent;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.TextReminderAttachment;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.attachment.FeatureFlag;
import com.claudecode.core.attachment.FeatureFlagRegistry;
import com.claudecode.core.attachment.AttachmentProvider;
import com.claudecode.core.attachment.AttachmentService;
import com.claudecode.core.model.PermissionModeKind;
import com.claudecode.core.model.ModelApiProtocol;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the {@link QueryLoop} turn loop (Phases 1–9) through the
 * {@link QueryDeps} I/O seam, matching the prior query-loop test approach but
 * driving the model via {@code deps.callModel(...)} instead of
 * {@code engine.getConfig.llmClient}. This validates the loop logic
 * independently of the production {@code QueryLoop}.
 */
class QueryLoopTest {

    @AfterEach
    void resetToolSearchProtocolResolver() {
        ToolSearchGate.configureProtocolResolver(null);
    }

    private static List<StreamingClient.StreamingEvent> endTurnEvents(String text) {
        return List.of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-1", "test-model", List.of(), new Usage(10, 0, 0, 0)),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", text),
            new StreamingClient.StreamingEvent.MessageDeltaEvent("end_turn", new Usage(0, 5, 0, 0)),
            new StreamingClient.StreamingEvent.MessageStopEvent()
        );
    }

    private static List<StreamingClient.StreamingEvent> outputTokenLimitEvents(String reason) {
        return List.of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-limit", "test-model", List.of(), new Usage(10, 0, 0, 0)),
            new StreamingClient.StreamingEvent.MessageDeltaEvent(reason, new Usage(0, 8_000, 0, 0)),
            new StreamingClient.StreamingEvent.MessageStopEvent()
        );
    }

    private static List<StreamingClient.StreamingEvent> toolUseEvents(String toolUseId) {
        return List.of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-" + toolUseId, "test-model", List.of(), Usage.EMPTY),
            new StreamingClient.StreamingEvent.ContentBlockStartEvent(
                0, "tool_use", toolUseId, "Bash"),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(
                0, "input_json_delta", "{\"cmd\":\"true\"}"),
            new StreamingClient.StreamingEvent.ContentBlockStopEvent(0),
            new StreamingClient.StreamingEvent.MessageDeltaEvent("tool_use", Usage.EMPTY),
            new StreamingClient.StreamingEvent.MessageStopEvent()
        );
    }

    private static QueryDeps fakeDeps(List<StreamingClient.StreamingEvent> events) {
        return new QueryDeps() {
            @Override
            public Iterator<StreamingClient.StreamingEvent> callModel(StreamingClient.StreamRequest request) {
                return events.iterator();
            }

            @Override
            public MessageCompactor.MicrocompactResult microcompact(List<Message> messages) {
                return new MessageCompactor.MicrocompactResult(messages);
            }

            @Override
            public boolean shouldAutoCompact(List<Message> messages, String model, String querySource) {
                return false;
            }

            @Override
            public QueryDeps.AutoCompactResult autocompact(List<Message> messages, String model, String querySource,
                                                          AutoCompactTrackingState tracking, String customInstructions,
                                                          long snipTokensFreed) {
                return new QueryDeps.AutoCompactResult(null, null);
            }

            @Override
            public String uuid() {
                return UUID.randomUUID().toString();
            }
        };
    }

    private static List<SDKMessage> drain(Iterator<SDKMessage> iter) {
        List<SDKMessage> msgs = new ArrayList<>();
        while (iter.hasNext()) msgs.add(iter.next());
        return msgs;
    }

    @Test
    void singleTurnEndTurnYieldsSuccessResult() {
        var events = endTurnEvents("Hello world");
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingClient.StreamingEvent> createStream(StreamingClient.StreamRequest request) {
                    return events.iterator();
                }

                @Override
                public String getModel() {
                    return "test-model";
                }
            })
            .systemPrompt("Be helpful")
            .build());

        var history = List.<Message>of(new UserMessage(
            UUID.randomUUID().toString(), MessageContent.ofText("Hi there")));
        engine.getMutableMessages().addAll(history);

        var params = QueryParams.builder()
            .messages(history)
            .systemPrompt("Be helpful")
            .model("test-model")
            .querySource("user")
            .deps(fakeDeps(events))
            .build();

        List<SDKMessage> messages = drain(new QueryLoop(engine, params));

        // Assistant message accumulated from the stream in stream order.
        var assistant = messages.stream()
            .filter(SDKMessage.Assistant.class::isInstance)
            .map(SDKMessage.Assistant.class::cast)
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected an Assistant message"));
        var content = assistant.message().message().content();
        assertEquals(1, content.size());
        assertInstanceOf(TextBlock.class, content.getFirst());
        assertEquals("Hello world", ((TextBlock) content.getFirst()).text());
        assertEquals(10, assistant.usage().inputTokens());
        assertEquals(5, assistant.usage().outputTokens());

        // No spurious CompactBoundary (history is far below the auto-compact threshold).
        boolean sawBoundary = messages.stream().anyMatch(SDKMessage.CompactBoundary.class::isInstance);
        assertFalse(sawBoundary, "CompactBoundary must not be emitted without a trigger");

        // Final result is success.
        var last = messages.getLast();
        assertInstanceOf(SDKMessage.Result.class, last);
        SDKMessage.Result result = (SDKMessage.Result) last;
        assertEquals(SDKMessage.Result.SUCCESS, result.resultType());
        assertTrue(result.timeToRequestMs() >= 0);
        assertTrue(engine.getSessionMetrics().complete());
        assertEquals(1, engine.getSessionMetrics().turns());
        assertEquals(1, engine.getSessionMetrics().steps());
        assertEquals(15, engine.getSessionMetrics().billedInputTokens()
            + engine.getSessionMetrics().outputTokens());
        assertTrue(result.ttftStreamMs() >= result.timeToRequestMs(),
            "first decoded stream event cannot precede request dispatch");
        assertTrue(result.ttftMs() >= result.ttftStreamMs(),
            "first output block cannot precede the first decoded stream event");

        // Baseline: no JSON schema → structuredOutput is null.
        assertNull(result.structuredOutput(),
            "structuredOutput must be null when no JSON schema provided");
    }

    @Test
    void blankPromptTextFallsBackToNoContentPlaceholderInsteadOfEmptyTextBlock() {
        var events = endTurnEvents("OK");
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingClient.StreamingEvent> createStream(StreamingClient.StreamRequest request) {
                    return events.iterator();
                }

                @Override
                public String getModel() {
                    return "test-model";
                }
            })
            .systemPrompt("Be helpful")
            .build());

        var params = QueryParams.builder()
            .messages(List.<Message>of())
            .systemPrompt("Be helpful")
            .model("test-model")
            .querySource("user")
            .deps(fakeDeps(events))
            .build();

// A blank raw-String prompt matches a queued command with empty text reaching
        // runPreamble directly (bypassing the UI-edge blank-input guard in
        // LanternaReplScreen.executeQueuedCommands). Without the fallback branch this
        // used to become a literal TextBlock(""), which strict downstream backends
        // reject with "message content cannot be empty".
        drain(new QueryLoop(engine, params, "", SubmitOptions.DEFAULT));

        UserMessage userMsg = engine.getMessages().stream()
            .filter(UserMessage.class::isInstance)
            .map(UserMessage.class::cast)
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected a UserMessage to be submitted"));
        assertEquals(MessageConstants.NO_CONTENT_MESSAGE, userMsg.message().text());
    }

    @Test
    void clearContextPlanSubmissionRetainsAutoContinuationMetadata() {
        var events = endTurnEvents("done");
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override public Iterator<StreamingClient.StreamingEvent> createStream(
                        StreamingClient.StreamRequest request) {
                    return events.iterator();
                }
                @Override public String getModel() { return "test-model"; }
            })
            .systemPrompt("Be helpful")
            .build());
        var params = QueryParams.builder()
            .messages(List.of())
            .systemPrompt("Be helpful")
            .model("test-model")
            .querySource("auto-continuation")
            .deps(fakeDeps(events))
            .build();

        drain(new QueryLoop(engine, params, "Implement the following plan:\nPlan body",
            SubmitOptions.of("auto-continuation").withPlanContent("Plan body")));

        UserMessage submitted = engine.getMessages().stream()
            .filter(UserMessage.class::isInstance)
            .map(UserMessage.class::cast)
            .findFirst().orElseThrow();
        assertEquals(MessageOrigin.AUTO_CONTINUATION, submitted.origin());
        assertEquals("Plan body", submitted.planContent());
    }

    @Test
    void blockingLimitEmitsReleased197PromptTooLongWithoutCallingModel() {
        AtomicInteger modelCalls = new AtomicInteger();
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingClient.StreamingEvent> createStream(
                        StreamingClient.StreamRequest request) {
                    modelCalls.incrementAndGet();
                    return endTurnEvents("must not run").iterator();
                }

                @Override
                public String getModel() {
                    return "test-model";
                }
            })
            .systemPrompt("Be helpful")
            .model("test-model")
            .build(), new MessageCompactor() {
                @Override
                public MicrocompactResult microcompactMessages(List<Message> messages) {
                    return new MicrocompactResult(messages);
                }

                @Override
                public boolean shouldAutoCompact(List<Message> messages, String model,
                                                 String querySource) {
                    return false;
                }

                @Override
                public CompactionResult compactConversation(List<Message> messages,
                                                            boolean isAutoCompact) {
                    throw new AssertionError("blocking-limit precheck must not compact");
                }

                @Override
                public boolean isAtBlockingLimit(List<Message> messages, String model) {
                    return true;
                }
            });
        var history = List.<Message>of(new UserMessage(
            UUID.randomUUID().toString(), MessageContent.ofText("oversized history")));
        engine.getMutableMessages().addAll(history);
        var params = QueryParams.builder()
            .messages(history)
            .systemPrompt("Be helpful")
            .model("test-model")
            .querySource("user")
            .deps(new QueryDeps() {
                @Override
                public Iterator<StreamingClient.StreamingEvent> callModel(
                        StreamingClient.StreamRequest request) {
                    modelCalls.incrementAndGet();
                    return endTurnEvents("must not run").iterator();
                }

                @Override
                public MessageCompactor.MicrocompactResult microcompact(List<Message> messages) {
                    return new MessageCompactor.MicrocompactResult(messages);
                }

                @Override
                public boolean shouldAutoCompact(List<Message> messages, String model,
                                                 String querySource) {
                    return false;
                }

                @Override
                public AutoCompactResult autocompact(List<Message> messages, String model,
                                                     String querySource,
                                                     AutoCompactTrackingState tracking,
                                                     String customInstructions,
                                                     long snipTokensFreed) {
                    return new AutoCompactResult(null, null, null);
                }

                @Override
                public String uuid() {
                    return UUID.randomUUID().toString();
                }
            })
            .build();

        List<SDKMessage> output = drain(new QueryLoop(engine, params));

        assertEquals(0, modelCalls.get());
        SDKMessage.Assistant assistant = output.stream()
            .filter(SDKMessage.Assistant.class::isInstance)
            .map(SDKMessage.Assistant.class::cast)
            .findFirst()
            .orElseThrow();
        assertEquals("Prompt is too long",
            assertInstanceOf(TextBlock.class,
                assistant.message().message().content().getFirst()).text());
        assertEquals("invalid_request", assistant.message().error());
        assertTrue(engine.getMutableMessages().contains(assistant.message()),
            "released blocking-limit errors remain in the replayable conversation");
        SDKMessage.Result result = assertInstanceOf(
            SDKMessage.Result.class, output.getLast());
        assertEquals(SDKMessage.Result.ERROR_DURING_EXECUTION, result.resultType());
    }

    @Test
    void blockingLimitWithReactiveAndAutoCompactEnabledSkipsLocalGuardAndCallsModel() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger reactiveCompacts = new AtomicInteger();
        AtomicInteger blockingChecks = new AtomicInteger();
        var boundary = new SystemMessage("boundary", "compact_boundary", "info", "");
        var summary = new UserMessage("summary", MessageContent.ofText("Summary: retained work"));
        var compacted = new MessageCompactor.CompactionResult(
            boundary, List.of(summary), List.of(), List.of(), List.of(), 100L);
        MessageCompactor compactService = new MessageCompactor() {
            @Override
            public MicrocompactResult microcompactMessages(List<Message> messages) {
                return new MicrocompactResult(messages);
            }

            @Override
            public boolean shouldAutoCompact(List<Message> messages, String model,
                                             String querySource) {
                return false;
            }

            @Override
            public boolean isAtBlockingLimit(List<Message> messages, String model) {
                blockingChecks.incrementAndGet();
                return true;
            }

            @Override
            public boolean isAutoCompactEnabled() {
                return true;
            }

            @Override
            public boolean isReactiveCompactEnabled() {
                return true;
            }

            @Override
            public CompactionResult compactConversation(List<Message> messages,
                                                        boolean isAutoCompact) {
                return compacted;
            }
        };
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingClient.StreamingEvent> createStream(
                        StreamingClient.StreamRequest request) {
                    return endTurnEvents("unused").iterator();
                }

                @Override
                public String getModel() { return "test-model"; }
            })
            .systemPrompt("Be helpful")
            .model("test-model")
            .build(), compactService);
        var history = List.<Message>of(new UserMessage(
            UUID.randomUUID().toString(), MessageContent.ofText("locally oversized request")));
        engine.getMutableMessages().addAll(history);
        QueryDeps deps = new QueryDeps() {
            @Override
            public Iterator<StreamingClient.StreamingEvent> callModel(
                    StreamingClient.StreamRequest request) {
                modelCalls.incrementAndGet();
                return endTurnEvents("continued after local-limit bypass").iterator();
            }

            @Override
            public MessageCompactor.MicrocompactResult microcompact(List<Message> messages) {
                return new MessageCompactor.MicrocompactResult(messages);
            }

            @Override
            public boolean shouldAutoCompact(List<Message> messages, String model,
                                             String querySource) {
                return false;
            }

            @Override
            public AutoCompactResult autocompact(List<Message> messages, String model,
                                                 String querySource,
                                                 AutoCompactTrackingState tracking,
                                                 String customInstructions,
                                                 long snipTokensFreed) {
                throw new AssertionError("proactive compact was not requested by the fixture");
            }

            @Override
            public AutoCompactResult reactiveCompact(List<Message> messages, String model,
                                                      String querySource,
                                                      AutoCompactTrackingState tracking,
                                                      String customInstructions) {
                reactiveCompacts.incrementAndGet();
                return new AutoCompactResult(compacted, 0, null);
            }

            @Override
            public String uuid() { return UUID.randomUUID().toString(); }
        };
        var params = QueryParams.builder()
            .messages(history)
            .systemPrompt("Be helpful")
            .model("test-model")
            .querySource("user")
            .deps(deps)
            .build();

        List<SDKMessage> output = drain(new QueryLoop(engine, params));

        assertEquals(0, blockingChecks.get(),
            "reactive + auto compact must skip the local blocking-limit precheck");
        assertEquals(1, modelCalls.get(),
            "the real API call must happen instead of dying on the local estimate");
        assertEquals(0, reactiveCompacts.get());
        assertTrue(output.stream().noneMatch(message ->
            message instanceof SDKMessage.Assistant assistant
                && assistant.message().message().content().stream()
                    .filter(TextBlock.class::isInstance)
                    .map(TextBlock.class::cast)
                    .anyMatch(text -> Strings.CS.equals("Prompt is too long", text.text()))),
            "no synthetic local Prompt-is-too-long message should be emitted");
        SDKMessage.Result result = assertInstanceOf(SDKMessage.Result.class, output.getLast());
        assertEquals(SDKMessage.Result.SUCCESS, result.resultType());
    }

    @Test
    void blockingLimitBypassLetsProviderPromptTooLongTriggerReactiveCompact() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger reactiveCompacts = new AtomicInteger();
        var boundary = new SystemMessage("boundary", "compact_boundary", "info", "");
        var summary = new UserMessage("summary", MessageContent.ofText("Summary: retained work"));
        var compacted = new MessageCompactor.CompactionResult(
            boundary, List.of(summary), List.of(), List.of(), List.of(), 100L);
        MessageCompactor compactService = new MessageCompactor() {
            @Override
            public MicrocompactResult microcompactMessages(List<Message> messages) {
                return new MicrocompactResult(messages);
            }

            @Override
            public boolean shouldAutoCompact(List<Message> messages, String model,
                                             String querySource) {
                return false;
            }

            @Override
            public boolean isAtBlockingLimit(List<Message> messages, String model) {
                return true;
            }

            @Override
            public boolean isAutoCompactEnabled() {
                return true;
            }

            @Override
            public boolean isReactiveCompactEnabled() {
                return true;
            }

            @Override
            public CompactionResult compactConversation(List<Message> messages,
                                                        boolean isAutoCompact) {
                return compacted;
            }
        };
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingClient.StreamingEvent> createStream(
                        StreamingClient.StreamRequest request) {
                    return endTurnEvents("unused").iterator();
                }

                @Override
                public String getModel() { return "test-model"; }
            })
            .systemPrompt("Be helpful")
            .model("test-model")
            .build(), compactService);
        var history = List.<Message>of(new UserMessage(
            UUID.randomUUID().toString(),
            MessageContent.ofText("locally oversized, provider rejects too")));
        engine.getMutableMessages().addAll(history);
        QueryDeps deps = new QueryDeps() {
            @Override
            public Iterator<StreamingClient.StreamingEvent> callModel(
                    StreamingClient.StreamRequest request) {
                if (modelCalls.incrementAndGet() == 1) {
                    throw new RuntimeException(
                        "Prompt is too long: provider counted extra protocol tokens");
                }
                return endTurnEvents("continued after reactive compact").iterator();
            }

            @Override
            public MessageCompactor.MicrocompactResult microcompact(List<Message> messages) {
                return new MessageCompactor.MicrocompactResult(messages);
            }

            @Override
            public boolean shouldAutoCompact(List<Message> messages, String model,
                                             String querySource) {
                return false;
            }

            @Override
            public AutoCompactResult autocompact(List<Message> messages, String model,
                                                 String querySource,
                                                 AutoCompactTrackingState tracking,
                                                 String customInstructions,
                                                 long snipTokensFreed) {
                throw new AssertionError("reactive recovery must bypass proactive threshold path");
            }

            @Override
            public AutoCompactResult reactiveCompact(List<Message> messages, String model,
                                                      String querySource,
                                                      AutoCompactTrackingState tracking,
                                                      String customInstructions) {
                reactiveCompacts.incrementAndGet();
                return new AutoCompactResult(compacted, 0, null);
            }

            @Override
            public String uuid() { return UUID.randomUUID().toString(); }
        };
        var params = QueryParams.builder()
            .messages(history)
            .systemPrompt("Be helpful")
            .model("test-model")
            .querySource("user")
            .deps(deps)
            .build();

        List<SDKMessage> output = drain(new QueryLoop(engine, params));

        assertEquals(2, modelCalls.get(),
            "local blocking-limit guard must be bypassed so the real call and its retry both happen");
        assertEquals(1, reactiveCompacts.get());
        assertTrue(output.stream().anyMatch(SDKMessage.CompactBoundary.class::isInstance));
        SDKMessage.Result result = assertInstanceOf(SDKMessage.Result.class, output.getLast());
        assertEquals(SDKMessage.Result.SUCCESS, result.resultType());
    }

    @Test
    void providerPromptTooLongTriggersReactiveCompactAndRetriesSameRequest() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger reactiveCompacts = new AtomicInteger();
        var boundary = new SystemMessage("boundary", "compact_boundary", "info", "");
        var summary = new UserMessage("summary", MessageContent.ofText("Summary: retained work"));
        var compacted = new MessageCompactor.CompactionResult(
            boundary, List.of(summary), List.of(), List.of(), List.of(), 100L);
        MessageCompactor compactService = new MessageCompactor() {
            @Override
            public MicrocompactResult microcompactMessages(List<Message> messages) {
                return new MicrocompactResult(messages);
            }

            @Override
            public boolean shouldAutoCompact(List<Message> messages, String model,
                                             String querySource) {
                return false;
            }

            @Override
            public boolean isReactiveCompactEnabled() {
                return true;
            }

            @Override
            public CompactionResult compactConversation(List<Message> messages,
                                                        boolean isAutoCompact) {
                return compacted;
            }
        };
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingClient.StreamingEvent> createStream(
                        StreamingClient.StreamRequest request) {
                    return endTurnEvents("unused").iterator();
                }

                @Override
                public String getModel() {
                    return "test-model";
                }
            })
            .systemPrompt("Be helpful")
            .model("test-model")
            .build(), compactService);
        var history = List.<Message>of(new UserMessage(
            UUID.randomUUID().toString(), MessageContent.ofText("large request")));
        engine.getMutableMessages().addAll(history);
        QueryDeps deps = new QueryDeps() {
            @Override
            public Iterator<StreamingClient.StreamingEvent> callModel(
                    StreamingClient.StreamRequest request) {
                if (modelCalls.incrementAndGet() == 1) {
                    throw new RuntimeException(
                        "Prompt is too long: provider counted extra protocol tokens");
                }
                return endTurnEvents("continued after compact").iterator();
            }

            @Override
            public MessageCompactor.MicrocompactResult microcompact(List<Message> messages) {
                return new MessageCompactor.MicrocompactResult(messages);
            }

            @Override
            public boolean shouldAutoCompact(List<Message> messages, String model,
                                             String querySource) {
                return false;
            }

            @Override
            public AutoCompactResult autocompact(List<Message> messages, String model,
                                                 String querySource,
                                                 AutoCompactTrackingState tracking,
                                                 String customInstructions,
                                                 long snipTokensFreed) {
                throw new AssertionError("reactive recovery must bypass proactive threshold path");
            }

            @Override
            public AutoCompactResult reactiveCompact(List<Message> messages, String model,
                                                      String querySource,
                                                      AutoCompactTrackingState tracking,
                                                      String customInstructions) {
                reactiveCompacts.incrementAndGet();
                return new AutoCompactResult(compacted, 0, null);
            }

            @Override
            public String uuid() {
                return UUID.randomUUID().toString();
            }
        };
        var params = QueryParams.builder()
            .messages(history)
            .systemPrompt("Be helpful")
            .model("test-model")
            .querySource("user")
            .deps(deps)
            .build();

        List<SDKMessage> output = drain(new QueryLoop(engine, params));

        assertEquals(2, modelCalls.get());
        assertEquals(1, reactiveCompacts.get());
        assertTrue(output.stream().anyMatch(SDKMessage.CompactBoundary.class::isInstance));
        assertFalse(output.stream().anyMatch(SDKMessage.Error.class::isInstance),
            "recoverable provider PTL must be withheld while compact+retry succeeds");
        SDKMessage.Result result = assertInstanceOf(SDKMessage.Result.class, output.getLast());
        assertEquals(SDKMessage.Result.SUCCESS, result.resultType());
    }

    @Test
    void proactiveFailureBreakerDoesNotDisableReactiveCompact() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger proactiveCompacts = new AtomicInteger();
        AtomicInteger reactiveCompacts = new AtomicInteger();
        var compacted = new MessageCompactor.CompactionResult(
            new SystemMessage("boundary", "compact_boundary", "info", ""),
            List.of(new UserMessage("summary", MessageContent.ofText("short summary"))),
            List.of(), List.of(), List.of(), 10L);
        MessageCompactor compactService = new MessageCompactor() {
            @Override
            public MicrocompactResult microcompactMessages(List<Message> messages) {
                return new MicrocompactResult(messages);
            }

            @Override
            public boolean shouldAutoCompact(List<Message> messages, String model,
                                             String querySource) {
                return proactiveCompacts.get() < 3;
            }

            @Override
            public boolean isAutoCompactEnabled() {
                return true;
            }

            @Override
            public boolean isReactiveCompactEnabled() {
                return true;
            }

            @Override
            public CompactionResult compactConversation(List<Message> messages,
                                                        boolean isAutoCompact) {
                return compacted;
            }
        };
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingClient.StreamingEvent> createStream(
                        StreamingClient.StreamRequest request) {
                    return endTurnEvents("unused").iterator();
                }

                @Override
                public String getModel() { return "test-model"; }
            })
            .systemPrompt("Be helpful")
            .model("test-model")
            .build(), compactService);
        var history = List.<Message>of(new UserMessage(
            UUID.randomUUID().toString(), MessageContent.ofText("large request")));
        engine.getMutableMessages().addAll(history);
        QueryDeps deps = new QueryDeps() {
            @Override
            public Iterator<StreamingClient.StreamingEvent> callModel(
                    StreamingClient.StreamRequest request) {
                int call = modelCalls.incrementAndGet();
                if (call <= 3) return toolUseEvents("tool-" + call).iterator();
                if (call == 4) throw new RuntimeException("Prompt is too long");
                return endTurnEvents("continued after reactive compact").iterator();
            }

            @Override
            public MessageCompactor.MicrocompactResult microcompact(List<Message> messages) {
                return new MessageCompactor.MicrocompactResult(messages);
            }

            @Override
            public boolean shouldAutoCompact(List<Message> messages, String model,
                                             String querySource) {
                return proactiveCompacts.get() < 3;
            }

            @Override
            public AutoCompactResult autocompact(List<Message> messages, String model,
                                                 String querySource,
                                                 AutoCompactTrackingState tracking,
                                                 String customInstructions,
                                                 long snipTokensFreed) {
                int failures = proactiveCompacts.incrementAndGet();
                return new AutoCompactResult(null, failures, "proactive_failed");
            }

            @Override
            public AutoCompactResult reactiveCompact(List<Message> messages, String model,
                                                      String querySource,
                                                      AutoCompactTrackingState tracking,
                                                      String customInstructions) {
                reactiveCompacts.incrementAndGet();
                return new AutoCompactResult(compacted, 0, null);
            }

            @Override
            public ToolRunner toolRunner() {
                return (blocks, queryEngine, _, _, _,
                        _) -> {
                    ToolUseBlock tool = assertInstanceOf(ToolUseBlock.class, blocks.getFirst());
                    queryEngine.getMutableMessages().add(new UserMessage(
                        UUID.randomUUID().toString(),
                        MessageContent.ofBlocks(List.of(new ToolResultBlock(
                            tool.id(), List.of(new TextBlock("ok")), false)))));
                    return new ToolRunner.RunOutcome(false, null, 0, false, null, null);
                };
            }

            @Override
            public String uuid() { return UUID.randomUUID().toString(); }
        };
        var params = QueryParams.builder()
            .messages(history)
            .systemPrompt("Be helpful")
            .model("test-model")
            .querySource("user")
            .deps(deps)
            .build();

        List<SDKMessage> output = drain(new QueryLoop(engine, params));

        assertEquals(3, proactiveCompacts.get());
        assertEquals(1, reactiveCompacts.get(),
            "reactive compact must have an independent attempt guard");
        assertEquals(5, modelCalls.get());
        SDKMessage.Result result = assertInstanceOf(SDKMessage.Result.class, output.getLast());
        assertEquals(SDKMessage.Result.SUCCESS, result.resultType());
    }

    @Test
    void repeatedProviderPromptTooLongRunsReactiveCompactOnlyOnce() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger reactiveCompacts = new AtomicInteger();
        var compacted = new MessageCompactor.CompactionResult(
            new SystemMessage("boundary", "compact_boundary", "info", ""),
            List.of(new UserMessage("summary", MessageContent.ofText("short summary"))),
            List.of(), List.of(), List.of(), 10L);
        MessageCompactor compactService = new MessageCompactor() {
            @Override
            public MicrocompactResult microcompactMessages(List<Message> messages) {
                return new MicrocompactResult(messages);
            }

            @Override
            public boolean shouldAutoCompact(List<Message> messages, String model,
                                             String querySource) {
                return false;
            }

            @Override
            public boolean isReactiveCompactEnabled() {
                return true;
            }

            @Override
            public CompactionResult compactConversation(List<Message> messages,
                                                        boolean isAutoCompact) {
                return compacted;
            }
        };
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingClient.StreamingEvent> createStream(
                        StreamingClient.StreamRequest request) {
                    return List.<StreamingClient.StreamingEvent>of().iterator();
                }

                @Override
                public String getModel() { return "test-model"; }
            })
            .systemPrompt("Be helpful")
            .model("test-model")
            .build(), compactService);
        var history = List.<Message>of(new UserMessage(
            UUID.randomUUID().toString(), MessageContent.ofText("large request")));
        engine.getMutableMessages().addAll(history);
        QueryDeps deps = new QueryDeps() {
            @Override
            public Iterator<StreamingClient.StreamingEvent> callModel(
                    StreamingClient.StreamRequest request) {
                modelCalls.incrementAndGet();
                throw new RuntimeException("Prompt is too long");
            }

            @Override
            public MessageCompactor.MicrocompactResult microcompact(List<Message> messages) {
                return new MessageCompactor.MicrocompactResult(messages);
            }

            @Override
            public boolean shouldAutoCompact(List<Message> messages, String model,
                                             String querySource) {
                return false;
            }

            @Override
            public AutoCompactResult autocompact(List<Message> messages, String model,
                                                 String querySource,
                                                 AutoCompactTrackingState tracking,
                                                 String customInstructions,
                                                 long snipTokensFreed) {
                throw new AssertionError("reactive recovery must bypass proactive threshold");
            }

            @Override
            public AutoCompactResult reactiveCompact(List<Message> messages, String model,
                                                      String querySource,
                                                      AutoCompactTrackingState tracking,
                                                      String customInstructions) {
                reactiveCompacts.incrementAndGet();
                return new AutoCompactResult(compacted, 0, null);
            }

            @Override
            public String uuid() { return UUID.randomUUID().toString(); }
        };
        var params = QueryParams.builder()
            .messages(history)
            .systemPrompt("Be helpful")
            .model("test-model")
            .querySource("user")
            .deps(deps)
            .build();

        List<SDKMessage> output = drain(new QueryLoop(engine, params));

        assertEquals(2, modelCalls.get());
        assertEquals(1, reactiveCompacts.get(),
            "reactive compact is single-shot; API-round retries happen inside the compactor");
        assertFalse(output.stream().filter(SDKMessage.Assistant.class::isInstance)
            .map(SDKMessage.Assistant.class::cast)
            .flatMap(message -> message.message().message().content().stream())
            .filter(TextBlock.class::isInstance)
            .map(TextBlock.class::cast)
            .anyMatch(text -> Strings.CS.equals("Prompt is too long", text.text())));
        SDKMessage.Result result = assertInstanceOf(SDKMessage.Result.class, output.getLast());
        assertEquals(SDKMessage.Result.ERROR_DURING_EXECUTION, result.resultType());
        assertEquals("Automatic compaction failed", result.errors().getFirst());
    }

    @Test
    void failedAutoCompactEmitsReleased197SdkStatusMetadata() {
        var events = endTurnEvents("done");
        var deps = new QueryDeps() {
            @Override
            public Iterator<StreamingClient.StreamingEvent> callModel(StreamingClient.StreamRequest request) {
                return events.iterator();
            }

            @Override
            public MessageCompactor.MicrocompactResult microcompact(List<Message> messages) {
                return new MessageCompactor.MicrocompactResult(messages);
            }

            @Override
            public boolean shouldAutoCompact(List<Message> messages, String model, String querySource) {
                return true;
            }

            @Override
            public AutoCompactResult autocompact(List<Message> messages, String model,
                                                  String querySource,
                                                  AutoCompactTrackingState tracking,
                                                  String customInstructions,
                                                  long snipTokensFreed) {
                return new AutoCompactResult(
                    null, 1, "too_few_groups", new Usage(1, 0, 0, 0));
            }

            @Override
            public String uuid() {
                return UUID.randomUUID().toString();
            }
        };
        MessageCompactor compactService = new MessageCompactor() {
            @Override
            public MicrocompactResult microcompactMessages(List<Message> messages) {
                return new MicrocompactResult(messages);
            }

            @Override
            public boolean shouldAutoCompact(List<Message> messages, String model,
                                             String querySource) {
                return true;
            }

            @Override
            public CompactionResult compactConversation(List<Message> messages,
                                                        boolean isAutoCompact) {
                throw new UnsupportedOperationException();
            }
        };
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingClient.StreamingEvent> createStream(
                        StreamingClient.StreamRequest request) {
                    return events.iterator();
                }

                @Override
                public String getModel() {
                    return "claude-sonnet-4-6";
                }
            })
            .systemPrompt("Be helpful")
            .model("claude-sonnet-4-6")
            .build(), compactService);
        var history = List.<Message>of(new UserMessage(
            UUID.randomUUID().toString(), MessageContent.ofText("Hi")));
        engine.getMutableMessages().addAll(history);
        var params = QueryParams.builder()
            .messages(history)
            .systemPrompt("Be helpful")
            .model("claude-sonnet-4-6")
            .querySource("sdk")
            .deps(deps)
            .build();

        List<SDKMessage.Status> statuses = drain(new QueryLoop(engine, params)).stream()
            .filter(SDKMessage.Status.class::isInstance)
            .map(SDKMessage.Status.class::cast)
            .toList();

        assertEquals(List.of(
            new SDKMessage.Status("compacting", null, null),
            new SDKMessage.Status(null, "failed", "too_few_groups")
        ), statuses);
        assertEquals(new Usage(11, 5, 0, 0), engine.getTotalUsage(),
            "failed compact request usage remains part of released session totals");
    }

    @Test
    void successfulAutoCompactEmitsReleased197SuccessSdkStatusMetadata() {
        var events = endTurnEvents("done");
        var boundary = new SystemMessage("boundary", "compact_boundary", "info", "");
        var summary = new UserMessage("summary", MessageContent.ofText("Summary: done"));
        var keep = new UserMessage("keep", MessageContent.ofText("Continue"));
        var compacted = new MessageCompactor.CompactionResult(
            boundary, List.of(summary), List.of(), List.of(), List.of(keep), 10L);
        var deps = new QueryDeps() {
            @Override
            public Iterator<StreamingClient.StreamingEvent> callModel(StreamingClient.StreamRequest request) {
                return events.iterator();
            }

            @Override
            public MessageCompactor.MicrocompactResult microcompact(List<Message> messages) {
                return new MessageCompactor.MicrocompactResult(messages);
            }

            @Override
            public boolean shouldAutoCompact(List<Message> messages, String model, String querySource) {
                return true;
            }

            @Override
            public AutoCompactResult autocompact(List<Message> messages, String model,
                                                  String querySource,
                                                  AutoCompactTrackingState tracking,
                                                  String customInstructions,
                                                  long snipTokensFreed) {
                return new AutoCompactResult(compacted, null, null);
            }

            @Override
            public String uuid() {
                return UUID.randomUUID().toString();
            }
        };
        MessageCompactor compactService = new MessageCompactor() {
            @Override
            public MicrocompactResult microcompactMessages(List<Message> messages) {
                return new MicrocompactResult(messages);
            }

            @Override
            public boolean shouldAutoCompact(List<Message> messages, String model,
                                             String querySource) {
                return true;
            }

            @Override
            public CompactionResult compactConversation(List<Message> messages,
                                                        boolean isAutoCompact) {
                return compacted;
            }
        };
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingClient.StreamingEvent> createStream(
                        StreamingClient.StreamRequest request) {
                    return events.iterator();
                }

                @Override
                public String getModel() {
                    return "claude-sonnet-4-6";
                }
            })
            .systemPrompt("Be helpful")
            .model("claude-sonnet-4-6")
            .build(), compactService);
        var history = List.<Message>of(new UserMessage(
            UUID.randomUUID().toString(), MessageContent.ofText("Hi")));
        engine.getMutableMessages().addAll(history);
        List<String> transcriptEvents = new ArrayList<>();
        engine.setTranscriptSink(new TranscriptSink() {
            @Override
            public void record(String sessionId, Message message) {
                transcriptEvents.add("record:" + message.uuid());
            }

            @Override
            public void prepareAutoCompactMetadata(String sessionId, String prompt) {
                transcriptEvents.add("last-prompt:" + prompt);
            }
        });
        var params = QueryParams.builder()
            .messages(history)
            .systemPrompt("Be helpful")
            .model("claude-sonnet-4-6")
            .querySource("sdk")
            .deps(deps)
            .build();

        List<SDKMessage.Status> statuses = drain(
            new QueryLoop(engine, params, "Hi", SubmitOptions.DEFAULT)).stream()
            .filter(SDKMessage.Status.class::isInstance)
            .map(SDKMessage.Status.class::cast)
            .toList();

        assertEquals(List.of(
            new SDKMessage.Status("compacting", null, null),
            new SDKMessage.Status(null, "success", null)
        ), statuses);
        int metadataIndex = transcriptEvents.indexOf("last-prompt:Hi");
        assertTrue(metadataIndex >= 0);
        assertEquals(List.of(
            "last-prompt:Hi",
            "record:boundary",
            "record:summary",
            "record:keep"
        ), transcriptEvents.subList(metadataIndex, metadataIndex + 4));
    }

    @Test
    void activeSkillAttributionIsCarriedByTheNextAssistantEnvelope() {
        var events = endTurnEvents("OK");
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingEvent> createStream(StreamRequest request) {
                    return events.iterator();
                }

                @Override
                public String getModel() {
                    return "test-model";
                }
            })
            .systemPrompt("Be helpful")
            .build());
        engine.applyContextModifier(new ToolContextModifier(
            List.of(), null, null, "wire-skills:wire-probe", "wire-skills"));

        drain(engine.submitMessage("continue", SubmitOptions.DEFAULT));

        AssistantMessage assistant = engine.getMessages().stream()
            .filter(AssistantMessage.class::isInstance)
            .map(AssistantMessage.class::cast)
            .findFirst().orElseThrow();
        assertEquals("wire-skills:wire-probe", assistant.attributionSkill());
        assertEquals("wire-skills", assistant.attributionPlugin());
    }

    @Test
    void activeMcpAttributionIsCarriedByTheNextAssistantEnvelope() {
        var events = endTurnEvents("OK");
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingEvent> createStream(StreamRequest request) {
                    return events.iterator();
                }

                @Override
                public String getModel() {
                    return "test-model";
                }
            })
            .systemPrompt("Be helpful")
            .build());
        engine.activateMcpAttribution("wire-reconnect", "echo_marker");

        drain(engine.submitMessage("continue", SubmitOptions.DEFAULT));
        drain(engine.submitMessage("new user turn", SubmitOptions.DEFAULT));

        List<AssistantMessage> assistants = engine.getMessages().stream()
            .filter(AssistantMessage.class::isInstance)
            .map(AssistantMessage.class::cast)
            .toList();
        AssistantMessage assistant = assistants.getFirst();
        assertEquals("wire-reconnect", assistant.attributionMcpServer());
        assertEquals("echo_marker", assistant.attributionMcpTool());
        assertNull(assistants.getLast().attributionMcpServer(),
            "a new submitted user turn must not inherit the prior MCP invocation");
        assertNull(assistants.getLast().attributionMcpTool());
    }

    @Test
    void resultApiDurationAndModelUsageStaySessionCumulative() {
        SessionCostState costs = SessionCostState.get();
        costs.reset();
        costs.recordApiRequest("earlier-model", new Usage(0, 0, 0, 0), 10_000);
        try {
            var events = endTurnEvents("OK");
            var engine = new DefaultQuerySession(QuerySessionSpec.builder()
                .llmClient(new StreamingClient() {
                    @Override
                    public Iterator<StreamingEvent> createStream(StreamRequest request) {
                        return events.iterator();
                    }

                    @Override
                    public String getModel() {
                        return "test-model";
                    }
                })
                .systemPrompt("Be helpful")
                .build());

            SDKMessage.Result result = drain(engine.submitMessage(
                "continue", SubmitOptions.DEFAULT)).stream()
                .filter(SDKMessage.Result.class::isInstance)
                .map(SDKMessage.Result.class::cast)
                .findFirst().orElseThrow();

            assertTrue(result.durationApiMs() >= 10_000,
                "2.1.197 result.duration_api_ms is the session-global API duration");
            assertTrue(result.modelUsage().containsKey("earlier-model"),
                "modelUsage remains process/session cumulative like 2.1.197");
        } finally {
            costs.reset();
        }
    }

    @Test
    void defaultSubmitCapturesLivePermissionModeForTranscriptReplay() {
        var events = endTurnEvents("OK");
        QuerySessionSpec config = QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingEvent> createStream(StreamRequest request) {
                    return events.iterator();
                }

                @Override
                public String getModel() {
                    return "test-model";
                }
            })
            .model("test-model")
            .build();
        config.setPermissionModeSupplier(() -> PermissionModeKind.BYPASS_PERMISSIONS);
        DefaultQuerySession engine = new DefaultQuerySession(config);

        drain(engine.submitMessage("hello", SubmitOptions.DEFAULT));

        UserMessage submitted = assertInstanceOf(UserMessage.class,
            engine.getMessages().stream()
                .filter(message -> message instanceof UserMessage user
                    && !user.isMeta())
                .findFirst().orElseThrow());
        assertEquals("bypassPermissions", submitted.permissionMode());
    }

    @Test
    void planSlugInitializerRunsBeforeTheFirstTranscriptUserRow() {
        var events = endTurnEvents("OK");
        AtomicReference<Boolean> slugReadyWhenUserWasRecorded = new AtomicReference<>();
        AtomicInteger initializerCalls = new AtomicInteger();
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingEvent> createStream(StreamRequest request) {
                    return events.iterator();
                }

                @Override
                public String getModel() {
                    return "test-model";
                }
            })
            .model("test-model")
            .planSlugInitializer(_ -> initializerCalls.incrementAndGet())
            .build());
        engine.setTranscriptSink((_, message) -> {
            if (message instanceof UserMessage user && !user.isMeta()) {
                slugReadyWhenUserWasRecorded.set(initializerCalls.get() > 0);
            }
        });

        drain(engine.submitMessage("make a plan",
            SubmitOptions.DEFAULT.withPermissionMode("plan")));

        assertEquals(1, initializerCalls.get());
        assertEquals(Boolean.TRUE, slugReadyWhenUserWasRecorded.get());
    }

    @Test
    void exitPlanModeToolUseInitializesSlugBeforeAssistantTranscriptRow() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger initializerCalls = new AtomicInteger();
        AtomicReference<Boolean> slugReadyWhenAssistantWasRecorded = new AtomicReference<>();
        var exitPlanEvents = List.<StreamingClient.StreamingEvent>of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-plan", "test-model", List.of(), Usage.EMPTY),
            new StreamingClient.StreamingEvent.ContentBlockStartEvent(
                0, "tool_use", "exit-1", "ExitPlanMode"),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(
                0, "input_json_delta", "{}"),
            new StreamingClient.StreamingEvent.ContentBlockStopEvent(0),
            new StreamingClient.StreamingEvent.MessageDeltaEvent(
                "tool_use", Usage.EMPTY),
            new StreamingClient.StreamingEvent.MessageStopEvent());
        QueryDeps deps = new QueryDeps() {
            @Override public Iterator<StreamingClient.StreamingEvent> callModel(
                    StreamingClient.StreamRequest request) {
                return (modelCalls.getAndIncrement() == 0
                    ? exitPlanEvents : endTurnEvents("OK")).iterator();
            }
            @Override public MessageCompactor.MicrocompactResult microcompact(List<Message> messages) {
                return new MessageCompactor.MicrocompactResult(messages);
            }
            @Override public boolean shouldAutoCompact(
                    List<Message> messages, String model, String source) { return false; }
            @Override public QueryDeps.AutoCompactResult autocompact(
                    List<Message> messages, String model, String source,
                    AutoCompactTrackingState tracking, String instructions, long freed) {
                return new QueryDeps.AutoCompactResult(null, null);
            }
            @Override public String uuid() { return UUID.randomUUID().toString(); }
            @Override public ToolRunner toolRunner() {
                return (_, _, _, _, _, _) ->
                    new ToolRunner.RunOutcome(false, null, 0, false, null, null);
            }
        };
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override public Iterator<StreamingClient.StreamingEvent> createStream(
                        StreamingClient.StreamRequest request) {
                    return endTurnEvents("unused").iterator();
                }
                @Override public String getModel() { return "test-model"; }
            })
            .model("test-model")
            .planSlugInitializer(_ -> initializerCalls.incrementAndGet())
            .build());
        engine.setTranscriptSink((_, message) -> {
            if (message instanceof AssistantMessage assistant
                    && assistant.message().content().stream().anyMatch(block ->
                        block instanceof ToolUseBlock tool
                            && Strings.CS.equals("ExitPlanMode", tool.name()))) {
                slugReadyWhenAssistantWasRecorded.set(initializerCalls.get() > 0);
            }
        });
        QueryParams params = QueryParams.builder()
            .messages(List.of())
            .model("test-model")
            .querySource("user")
            .deps(deps)
            .build();

        drain(new QueryLoop(engine, params, "leave plan mode", SubmitOptions.DEFAULT));

        assertEquals(1, initializerCalls.get());
        assertEquals(Boolean.TRUE, slugReadyWhenAssistantWasRecorded.get());
    }

    @Test
    void enterPlanModeToolUseInitializesSlugBeforeAssistantTranscriptRow() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger initializerCalls = new AtomicInteger();
        AtomicReference<Boolean> slugReadyWhenAssistantWasRecorded = new AtomicReference<>();
        var enterPlanEvents = List.<StreamingClient.StreamingEvent>of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-plan", "test-model", List.of(), Usage.EMPTY),
            new StreamingClient.StreamingEvent.ContentBlockStartEvent(
                0, "tool_use", "enter-1", "EnterPlanMode"),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(
                0, "input_json_delta", "{}"),
            new StreamingClient.StreamingEvent.ContentBlockStopEvent(0),
            new StreamingClient.StreamingEvent.MessageDeltaEvent(
                "tool_use", Usage.EMPTY),
            new StreamingClient.StreamingEvent.MessageStopEvent());
        QueryDeps deps = new QueryDeps() {
            @Override public Iterator<StreamingClient.StreamingEvent> callModel(
                    StreamingClient.StreamRequest request) {
                return (modelCalls.getAndIncrement() == 0
                    ? enterPlanEvents : endTurnEvents("OK")).iterator();
            }
            @Override public MessageCompactor.MicrocompactResult microcompact(List<Message> messages) {
                return new MessageCompactor.MicrocompactResult(messages);
            }
            @Override public boolean shouldAutoCompact(
                    List<Message> messages, String model, String source) { return false; }
            @Override public QueryDeps.AutoCompactResult autocompact(
                    List<Message> messages, String model, String source,
                    AutoCompactTrackingState tracking, String instructions, long freed) {
                return new QueryDeps.AutoCompactResult(null, null);
            }
            @Override public String uuid() { return UUID.randomUUID().toString(); }
            @Override public ToolRunner toolRunner() {
                return (_, _, _, _, _, _) ->
                    new ToolRunner.RunOutcome(false, null, 0, false, null, null);
            }
        };
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override public Iterator<StreamingClient.StreamingEvent> createStream(
                        StreamingClient.StreamRequest request) {
                    return endTurnEvents("unused").iterator();
                }
                @Override public String getModel() { return "test-model"; }
            })
            .model("test-model")
            .planSlugInitializer(_ -> initializerCalls.incrementAndGet())
            .build());
        engine.setTranscriptSink((_, message) -> {
            if (message instanceof AssistantMessage assistant
                    && assistant.message().content().stream().anyMatch(block ->
                        block instanceof ToolUseBlock tool
                            && Strings.CS.equals("EnterPlanMode", tool.name()))) {
                slugReadyWhenAssistantWasRecorded.set(initializerCalls.get() > 0);
            }
        });
        QueryParams params = QueryParams.builder()
            .messages(List.of())
            .model("test-model")
            .querySource("user")
            .deps(deps)
            .build();

        drain(new QueryLoop(engine, params, "enter plan mode", SubmitOptions.DEFAULT));

        assertEquals(1, initializerCalls.get());
        assertEquals(Boolean.TRUE, slugReadyWhenAssistantWasRecorded.get());
    }

    @Test
    void pastedImage_reachesModelAsImageBlock() {
        var events = endTurnEvents("seen");
        AttachmentProvider listing = new AttachmentProvider() {
            @Override public String name() { return "test_listing"; }
            @Override public List<AttachmentPayload> collect(
                    AttachmentContext context) {
                return List.of(new TextReminderAttachment("listing"));
            }
        };
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingClient.StreamingEvent> createStream(StreamingClient.StreamRequest request) {
                    return events.iterator();
                }

                @Override
                public String getModel() {
                    return "test-model";
                }
            })
            .systemPrompt("Be helpful")
            .attachmentService(new AttachmentService(
                List.of(listing), FeatureFlagRegistry.allOff()))
            .build());

        // A garbled-but-decodable-as-PNG prefix: enough for storeImages (image type) +
        // the inline image block; resizing will no-op since ImageIO can't read it.
        String base64 = Base64.getEncoder().encodeToString(
            new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47, 0, 0, 0, 0, 0x49, 0x48, 0x52, 0x44 });
        var pasted = Map.of(2, PastedContent.image(2, base64, "image/png", null, null));
        var options = SubmitOptions.withPastedContents("user", pasted);

        var params = QueryParams.builder()
            .messages(List.of())
            .systemPrompt("Be helpful")
            .model("test-model")
            .querySource("user")
            .deps(fakeDeps(events))
            .build();

        drain(new QueryLoop(engine, params, "Look at this image", options));

        // The submitted user message must be block-based, carrying the inline image
// block, the prompt text, and the pasted chip id — matching

        UserMessage userMsg = engine.getMutableMessages().stream()
            .filter(m -> m instanceof UserMessage um && !um.isMeta())
            .map(UserMessage.class::cast)
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected a non-meta user message"));
        List<ContentBlock> blocks = userMsg.message().blocks();
        assertNotNull(blocks, "pasted image must produce block-based content, not plain text");
        assertTrue(blocks.stream().anyMatch(ImageBlock.class::isInstance),
            "pasted image must reach the model as an ImageBlock");
        assertTrue(blocks.stream().anyMatch(
            b -> b instanceof TextBlock tb && Strings.CS.contains(tb.text(), "Look at this image")),
            "prompt text must be present alongside the image");
        assertEquals(List.of(2), userMsg.imagePasteIds(), "imagePasteIds must be set from pasted contents");

        // An isMeta user message must follow carrying image metadata (source path)

        boolean sawMeta = engine.getMutableMessages().stream()
            .anyMatch(m -> m instanceof UserMessage um && um.isMeta()
                && um.message().blocks() != null
                && um.message().blocks().stream()
                    .anyMatch(b -> b instanceof TextBlock tb && Strings.CS.contains(tb.text(), "source:")));
        assertTrue(sawMeta, "isMeta image-metadata message must be emitted");

        List<Message> history = engine.getMutableMessages();
        int promptIndex = -1;
        int listingIndex = -1;
        int metadataIndex = -1;
        for (int index = 0; index < history.size(); index++) {
            Message message = history.get(index);
            if (message == userMsg) promptIndex = index;
            if (message instanceof AttachmentMessage attachment
                    && attachment.payload() instanceof TextReminderAttachment) {
                listingIndex = index;
            }
            if (message instanceof UserMessage user && user.isMeta()
                    && user.message().blocks() != null
                    && user.message().blocks().stream().anyMatch(block ->
                        block instanceof TextBlock text && Strings.CS.contains(text.text(), "source:"))) {
                metadataIndex = index;
                assertNull(user.permissionMode(),
                    "image metadata is synthetic and must not inherit the human prompt mode");
            }
        }
        assertTrue(promptIndex < listingIndex && listingIndex < metadataIndex,
            "released order must be prompt → initial attachments → image metadata");
    }

    @Test
    void pastedImage_reachesModelViaSlashCommandResubmit() {
        // Slash/prompt/skill commands re-enter the model through the same prompt path:
        // SlashCommandDispatcher captures inputPanel.getPastedContents() and passes it into
        // host.executeQuery(display, commandOutput, pasted) -> submitMessage -> QueryLoop
        // (runPreamble). A non-local slash prompt falls through to forQuery (DefaultQuerySession
        // .processUserInput default branch), so the shared image wiring applies. This test
        // proves the slash re-submit path delivers pasted images to the model.
        var events = endTurnEvents("ok");
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingClient.StreamingEvent> createStream(StreamingClient.StreamRequest request) {
                    return events.iterator();
                }

                @Override
                public String getModel() {
                    return "test-model";
                }
            })
            .systemPrompt("Be helpful")
            .build());

        String base64 = Base64.getEncoder().encodeToString(
            new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47, 0, 0, 0, 0, 0x49, 0x48, 0x52, 0x44 });
        var pasted = Map.of(3, PastedContent.image(3, base64, "image/png", null, null));
        var options = SubmitOptions.withPastedContents("user", pasted).asSlashCommand();

        // commandOutput is the expanded skill/prompt command text (re-submitted as the query)
        var params = QueryParams.builder()
            .messages(List.of())
            .systemPrompt("Be helpful")
            .model("test-model")
            .querySource("user")
            .deps(fakeDeps(events))
            .build();

        drain(new QueryLoop(engine, params, "/review this picture", options));


        // with the raw (untrimmed) command output as the text.
        UserMessage main = engine.getMutableMessages().stream()
            .filter(m -> m instanceof UserMessage um
                && um.imagePasteIds() != null && um.imagePasteIds().contains(3))
            .map(UserMessage.class::cast)
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected slash main user message with imagePasteIds=[3]"));
        assertTrue(main.isMeta(), "slash main user message must be isMeta:true");
        List<ContentBlock> blocks = main.message().blocks();
        assertNotNull(blocks, "pasted image must produce block-based content");
        assertTrue(blocks.stream().anyMatch(ImageBlock.class::isInstance),
            "pasted image must reach the model via the slash re-submit path");

        int imgIdx = -1, txtIdx = -1;
        for (int i = 0; i < blocks.size(); i++) {
            if (imgIdx < 0 && blocks.get(i) instanceof ImageBlock) imgIdx = i;
            if (txtIdx < 0 && blocks.get(i) instanceof TextBlock) txtIdx = i;
        }
        assertTrue(imgIdx >= 0 && txtIdx >= 0, "both image and text blocks required");
        assertTrue(imgIdx < txtIdx, "pasted image block must precede the text block for slash commands");
        assertEquals(List.of(3), main.imagePasteIds());
        // Text is the RAW command output (untrimmed) — fix #3.
        assertTrue(blocks.stream().anyMatch(
            b -> b instanceof TextBlock tb && Strings.CS.contains(tb.text(), "/review this picture")),
            "command output text must be present alongside the image");
    }

    @Test
    void scheduledMetaPromptStaysHiddenWithoutPretendingToBeASlashCommand() {
        var events = endTurnEvents("hello");
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                    return events.iterator();
                }
                @Override public String getModel() { return "test-model"; }
            })
            .systemPrompt("Be helpful")
            .build());
        var params = QueryParams.builder()
            .messages(List.of())
            .systemPrompt("Be helpful")
            .model("test-model")
            .querySource("user")
            .deps(fakeDeps(events))
            .build();

        drain(new QueryLoop(engine, params, "hello",
            SubmitOptions.DEFAULT.asMeta().withPermissionMode("bypassPermissions")));

        UserMessage scheduled = engine.getMutableMessages().stream()
            .filter(UserMessage.class::isInstance)
            .map(UserMessage.class::cast)
            .findFirst()
            .orElseThrow();
        assertTrue(scheduled.isMeta(), "cron input is hidden from the visible transcript");
        assertEquals("hello", scheduled.message().text());
        assertEquals("bypassPermissions", scheduled.permissionMode(),
            "a scheduled prompt is meta, but it is not a slash-command projection");
    }

    @Test
    void structuredPromptCommandContentIsNotFlattenedToString() {
        var events = endTurnEvents("ok");
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                    return events.iterator();
                }
                @Override public String getModel() { return "test-model"; }
            })
            .systemPrompt("Be helpful")
            .build());
        var imageSource = new ObjectMapper().createObjectNode();
        imageSource.put("type", "base64");
        imageSource.put("media_type", "image/png");
        imageSource.put("data",
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
        MessageContent structured = MessageContent.ofBlocks(List.of(
            new TextBlock("inspect"), new ImageBlock(imageSource)));
        var params = QueryParams.builder()
            .messages(List.of())
            .systemPrompt("Be helpful")
            .model("test-model")
            .querySource("user")
            .deps(fakeDeps(events))
            .build();

        drain(new QueryLoop(engine, params, structured, SubmitOptions.DEFAULT.asSlashCommand()));

        UserMessage submitted = engine.getMutableMessages().stream()
            .filter(UserMessage.class::isInstance)
            .map(UserMessage.class::cast)
            .findFirst().orElseThrow();
        assertTrue(submitted.isMeta());
        assertEquals(2, submitted.message().blocks().size());
        assertInstanceOf(TextBlock.class, submitted.message().blocks().getFirst());
        assertInstanceOf(ImageBlock.class, submitted.message().blocks().get(1));
    }

    @Test
    void invalidStructuredImageUsesReleased197DegradedTextBlock() {
        var events = endTurnEvents("ok");
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                    return events.iterator();
                }
                @Override public String getModel() { return "test-model"; }
            })
            .systemPrompt("Be helpful")
            .build());
        var imageSource = new ObjectMapper().createObjectNode();
        imageSource.put("type", "base64");
        imageSource.put("media_type", "image/png");
        imageSource.put("data", "%%%INVALID-BASE64%%%");
        MessageContent structured = MessageContent.ofBlocks(List.of(
            new TextBlock("inspect"), new ImageBlock(imageSource)));
        var params = QueryParams.builder()
            .messages(List.of())
            .systemPrompt("Be helpful")
            .model("test-model")
            .querySource("user")
            .deps(fakeDeps(events))
            .build();

        drain(new QueryLoop(engine, params, structured, SubmitOptions.DEFAULT));

        UserMessage submitted = engine.getMutableMessages().stream()
            .filter(UserMessage.class::isInstance)
            .map(UserMessage.class::cast)
            .findFirst().orElseThrow();
        assertEquals(2, submitted.message().blocks().size());
        assertInstanceOf(TextBlock.class, submitted.message().blocks().getFirst());
        TextBlock degraded = assertInstanceOf(
            TextBlock.class, submitted.message().blocks().get(1));
        assertEquals(
            "[Image could not be processed: Unable to resize image — image processing is unavailable "
                + "and dimensions could not be read from the file header. Please convert the image "
                + "to PNG, JPEG, GIF, or WebP.]",
            degraded.text());
    }

    @Test
    void promptCommandPrecedingMessagesRemainSeparateInTranscriptOrder() {
        var events = endTurnEvents("ok");
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                    return events.iterator();
                }
                @Override public String getModel() { return "test-model"; }
            })
            .systemPrompt("Be helpful")
            .build());
        var params = QueryParams.builder()
            .messages(List.of())
            .systemPrompt("Be helpful")
            .model("test-model")
            .querySource("user")
            .deps(fakeDeps(events))
            .build();
        SubmitOptions options = SubmitOptions.DEFAULT.asSlashCommand()
            .withPrecedingUserMessages(List.of(
                MessageContent.ofText("<command-name>/goal</command-name>"),
                MessageContent.ofText("<local-command-stdout>Goal set</local-command-stdout>")));

        drain(new QueryLoop(engine, params, MessageContent.ofText("activation"), options));

        List<UserMessage> users = engine.getMutableMessages().stream()
            .filter(UserMessage.class::isInstance)
            .map(UserMessage.class::cast)
            .toList();
        assertEquals(3, users.size());
        assertEquals("<command-name>/goal</command-name>", users.getFirst().message().text());
        assertEquals("<local-command-stdout>Goal set</local-command-stdout>",
            users.get(1).message().text());
        assertEquals("activation",
            ((TextBlock) users.get(2).message().blocks().getFirst()).text());
        assertFalse(users.getFirst().isMeta());
        assertFalse(users.get(1).isMeta());
        assertTrue(users.get(2).isMeta());
    }

    @Test
    void localJsxQueryOutputIsVisibleLikeReleasedPlanCommand() {
        var events = endTurnEvents("ok");
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                    return events.iterator();
                }
                @Override public String getModel() { return "test-model"; }
            })
            .systemPrompt("Be helpful")
            .build());
        var params = QueryParams.builder()
            .messages(List.of())
            .systemPrompt("Be helpful")
            .model("test-model")
            .querySource("user")
            .deps(fakeDeps(events))
            .build();
        SubmitOptions options = SubmitOptions.DEFAULT.asSlashCommand()
            .withPrecedingUserMessages(List.of(
                MessageContent.ofText("<command-name>/plan</command-name>")));

        drain(new QueryLoop(engine, params,
            MessageContent.ofText(
                "<local-command-stdout>Enabled plan mode</local-command-stdout>"),
            options));

        List<UserMessage> users = engine.getMutableMessages().stream()
            .filter(UserMessage.class::isInstance)
            .map(UserMessage.class::cast)
            .toList();
        assertEquals(2, users.size());
        assertFalse(users.getFirst().isMeta());
        assertFalse(users.get(1).isMeta(),
            "local-JSX shouldQuery stdout is a visible user message in 2.1.197");
    }

    @Test
    void promptEntryRunsPreambleThenLoop() {
        var events = endTurnEvents("Hello world");
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingClient.StreamingEvent> createStream(StreamingClient.StreamRequest request) {
                    return events.iterator();
                }

                @Override
                public String getModel() {
                    return "test-model";
                }
            })
            .systemPrompt("Be helpful")
            .build());

        var params = QueryParams.builder()
            .systemPrompt("Be helpful")
            .model("test-model")
            .querySource("user")
            .deps(fakeDeps(events))
            .build();

// 4-arg constructor matches submitMessage(prompt, options) with the flag on:
        // runs the preamble (processUserInput + user/system-init emit) then the loop.
        List<SDKMessage> messages = drain(new QueryLoop(engine, params, "Hi there", SubmitOptions.DEFAULT));

        // Preamble emits the user turn first.
        assertInstanceOf(SDKMessage.User.class, messages.getFirst());
        assertEquals("Hi there", ((SDKMessage.User) messages.getFirst()).message().message().text());

        // Loop emits the assistant reply accumulated from the stream.
        var assistant = messages.stream()
            .filter(SDKMessage.Assistant.class::isInstance)
            .map(SDKMessage.Assistant.class::cast)
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected an Assistant message"));
        assertEquals("Hello world",
            ((TextBlock) assistant.message().message().content().getFirst()).text());

        // Final result is success.
        var last = messages.getLast();
        assertInstanceOf(SDKMessage.Result.class, last);
        assertEquals(SDKMessage.Result.SUCCESS, ((SDKMessage.Result) last).resultType());
    }

    @Test
    void streamErrorYieldsExecutionErrorResult() {
        var errorEvents = List.<StreamingClient.StreamingEvent>of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-1", "test-model", List.of(), new Usage(1, 0, 0, 0)),
            new StreamingClient.StreamingEvent.ErrorEvent(new RuntimeException("boom"))
        );
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingClient.StreamingEvent> createStream(StreamingClient.StreamRequest request) {
                    return errorEvents.iterator();
                }

                @Override
                public String getModel() {
                    return "test-model";
                }
            })
            .systemPrompt("Be helpful")
            .build());

        var history = List.<Message>of(new UserMessage(
            UUID.randomUUID().toString(), MessageContent.ofText("Hi")));
        engine.getMutableMessages().addAll(history);

        var params = QueryParams.builder()
            .messages(history)
            .systemPrompt("Be helpful")
            .model("test-model")
            .querySource("user")
            .deps(fakeDeps(errorEvents))
            .build();

        List<SDKMessage> messages = drain(new QueryLoop(engine, params));
        var last = messages.getLast();
        assertInstanceOf(SDKMessage.Result.class, last);
        assertEquals(SDKMessage.Result.ERROR_DURING_EXECUTION,
            ((SDKMessage.Result) last).resultType());
// On error, dispatchStopFailure runs but Stop hooks must not; result is a terminal
// execution error.
        assertNotNull(last);
    }

    @Test
    void fallbackThrownDuringStreaming_retriesWithoutReportingAbortOrStreamError() {
        SessionCostState.get().reset();
        var successEvents = endTurnEvents("fallback response");
        var calls = new AtomicInteger();
        var requestedModels = new ArrayList<String>();
        var requestedMessages =
            new ArrayList<List<StreamingClient.StreamRequest.RequestMessage>>();
        QueryDeps deps = new QueryDeps() {
            @Override
            public Iterator<StreamingClient.StreamingEvent> callModel(StreamingClient.StreamRequest request) {
                requestedModels.add(request.model());
                requestedMessages.add(List.copyOf(request.messages()));
                if (calls.getAndIncrement() == 0) {
                    LockSupport.parkNanos(15_000_000L);
                    return new Iterator<>() {
                        @Override public boolean hasNext() { return true; }
                        @Override public StreamingClient.StreamingEvent next() {
                            throw new FallbackTriggeredError("primary-model", "fallback-model");
                        }
                    };
                }
                LockSupport.parkNanos(15_000_000L);
                return successEvents.iterator();
            }

            @Override
            public MessageCompactor.MicrocompactResult microcompact(List<Message> messages) {
                return new MessageCompactor.MicrocompactResult(messages);
            }

            @Override
            public boolean shouldAutoCompact(List<Message> messages, String model, String querySource) {
                return false;
            }

            @Override
            public AutoCompactResult autocompact(List<Message> messages, String model, String querySource,
                                                 AutoCompactTrackingState tracking, String customInstructions,
                                                 long snipTokensFreed) {
                return new AutoCompactResult(null, null);
            }

            @Override
            public String uuid() {
                return UUID.randomUUID().toString();
            }
        };

        QuerySessionSpec config = QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingClient.StreamingEvent> createStream(StreamingClient.StreamRequest request) {
                    return successEvents.iterator();
                }

                @Override
                public String getModel() {
                    return "primary-model";
                }
            })
            .systemPrompt("Be helpful")
            .model("primary-model")
            .build();
        config.setFallbackModel("fallback-model");
        var engine = new DefaultQuerySession(config);
        var history = List.<Message>of(new UserMessage(
            UUID.randomUUID().toString(), MessageContent.ofText("Hi")));
        engine.getMutableMessages().addAll(history);
        var params = QueryParams.builder()
            .messages(history)
            .systemPrompt("Be helpful")
            .model("primary-model")
            .querySource("user")
            .deps(deps)
            .build();

        List<SDKMessage> messages = drain(new QueryLoop(engine, params));

        assertEquals(List.of("primary-model", "fallback-model"), requestedModels);
        assertEquals(requestedMessages.getFirst(), requestedMessages.getLast(),
            "an overload fallback changes only the model; it must retain the original prompt attachments");
        assertTrue(messages.stream().anyMatch(message -> message instanceof SDKMessage.System system
            && Strings.CS.equals("model_fallback", system.message().subtype())));
        assertFalse(messages.stream().anyMatch(SDKMessage.Error.class::isInstance));
        assertEquals(SDKMessage.Result.SUCCESS,
            ((SDKMessage.Result) messages.getLast()).resultType());
        assertTrue(SessionCostState.get().apiDurationMs() >= 25,
            "duration including retries must retain the failed primary attempt");
        assertTrue(SessionCostState.get().apiDurationWithoutRetriesMs() >= 10,
            "the successful fallback attempt has its own duration clock");
        assertTrue(SessionCostState.get().apiDurationWithoutRetriesMs()
                < SessionCostState.get().apiDurationMs(),
            "without-retries duration must exclude the abandoned primary attempt");
        SessionCostState.get().reset();
    }

    @Test
    void submitOptionsWithSchemaPopulatesHasJsonSchema() throws Exception {
        JsonNode schema = new ObjectMapper().readTree("{\"type\":\"object\"}");
        SubmitOptions opts = SubmitOptions.withSchema("test", schema);
        assertTrue(opts.hasJsonSchema(), "withSchema must set hasJsonSchema=true");
        assertEquals(schema, opts.jsonSchema());
    }

    @Test
    void submitOptionsDefaultHasNoJsonSchema() {
        assertFalse(SubmitOptions.DEFAULT.hasJsonSchema(),
            "DEFAULT SubmitOptions must have hasJsonSchema=false");
        assertNull(SubmitOptions.DEFAULT.jsonSchema());
    }

    @Test
    void queryStateWithStructuredOutput() throws Exception {
        JsonNode payload = new ObjectMapper().readTree("{\"result\":42}");
        QueryState initial = QueryState.initial();
        assertNull(initial.structuredOutput(),
            "initial state must have null structuredOutput");

        QueryState updated = initial.withStructuredOutput(payload);
        assertEquals(payload, updated.structuredOutput(),
            "withStructuredOutput must carry the payload");
        // Other fields are preserved.
        assertEquals(initial.turnCount(), updated.turnCount());
        assertEquals(initial.stopHookActive(), updated.stopHookActive());
    }

    @Test
    void queryStateCarriesMaxOutputTokensOverrideAcrossTransitions() {
        QueryState initial = QueryState.initial();
        assertNull(initial.maxOutputTokensOverride());

        QueryState updated = initial.withMaxOutputTokensOverride(64_000)
            .withTransition(new Continue.MaxOutputTokensEscalate());

        assertEquals(64_000, updated.maxOutputTokensOverride());
        assertInstanceOf(Continue.MaxOutputTokensEscalate.class, updated.transition());
        assertEquals(initial.turnCount(), updated.turnCount());
    }

    @Test
    void gateOffWithholdsApiErrorAndUsesMetaRecovery() {
        List<Integer> requestMaxTokens = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        List<StreamingClient.StreamingEvent> success = endTurnEvents("recovered");
        QueryDeps deps = new QueryDeps() {
            @Override public Iterator<StreamingClient.StreamingEvent> callModel(StreamingClient.StreamRequest request) {
                requestMaxTokens.add(request.maxOutputTokensOverride() != null
                    ? request.maxOutputTokensOverride() : request.maxTokens());
                return (calls.getAndIncrement() == 0
                    ? outputTokenLimitEvents("max_tokens") : success).iterator();
            }
            @Override public MessageCompactor.MicrocompactResult microcompact(List<Message> messages) {
                return new MessageCompactor.MicrocompactResult(messages);
            }
            @Override public boolean shouldAutoCompact(List<Message> messages, String model, String querySource) { return false; }
            @Override public AutoCompactResult autocompact(List<Message> messages, String model, String querySource,
                    AutoCompactTrackingState tracking, String customInstructions, long snipTokensFreed) {
                return new AutoCompactResult(null, null);
            }
            @Override public String uuid() { return UUID.randomUUID().toString(); }
        };
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override public Iterator<StreamingClient.StreamingEvent> createStream(StreamingClient.StreamRequest request) {
                    return success.iterator();
                }
                @Override public String getModel() { return "test-model"; }
            })
            .model("test-model")
            .maxTokens(32_000)
            .featureFlags(FeatureFlagRegistry.allOff())
            .build());
        List<Message> history = List.of(new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("Hi")));
        engine.getMutableMessages().addAll(history);

        List<SDKMessage> output = drain(new QueryLoop(engine, QueryParams.builder()
            .messages(history).systemPrompt("Be helpful").model("test-model")
            .querySource("user").deps(deps).build()));

        assertEquals(List.of(32_000, 32_000), requestMaxTokens);
        assertEquals(1, engine.getMutableMessages().stream()
            .filter(m -> m instanceof UserMessage um
                && um.message().text() != null
                && Strings.CS.contains(um.message().text(), "Output token limit hit"))
            .count());
        assertFalse(engine.getMutableMessages().stream()
            .filter(AssistantMessage.class::isInstance).map(AssistantMessage.class::cast)
            .anyMatch(AssistantMessage::isApiErrorMessage),
            "a recoverable API error must stay out of mutable history");
        assertFalse(output.stream().anyMatch(m -> m instanceof SDKMessage.Assistant assistant
            && assistant.message().isApiErrorMessage()));
        assertEquals(SDKMessage.Result.SUCCESS,
            assertInstanceOf(SDKMessage.Result.class, output.getLast()).resultType());
    }

    @Test
    void contextWindowExceededUsesTheSameWithheldRecoveryPath() {
        List<Integer> requestMaxTokens = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        List<StreamingClient.StreamingEvent> success = endTurnEvents("recovered");
        QueryDeps deps = new QueryDeps() {
            @Override public Iterator<StreamingClient.StreamingEvent> callModel(StreamingClient.StreamRequest request) {
                requestMaxTokens.add(request.maxOutputTokensOverride() != null
                    ? request.maxOutputTokensOverride() : request.maxTokens());
                return (calls.getAndIncrement() == 0
                    ? outputTokenLimitEvents("model_context_window_exceeded") : success).iterator();
            }
            @Override public MessageCompactor.MicrocompactResult microcompact(List<Message> messages) {
                return new MessageCompactor.MicrocompactResult(messages);
            }
            @Override public boolean shouldAutoCompact(List<Message> messages, String model, String querySource) { return false; }
            @Override public AutoCompactResult autocompact(List<Message> messages, String model, String querySource,
                    AutoCompactTrackingState tracking, String customInstructions, long snipTokensFreed) {
                return new AutoCompactResult(null, null);
            }
            @Override public String uuid() { return UUID.randomUUID().toString(); }
        };
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override public Iterator<StreamingClient.StreamingEvent> createStream(StreamingClient.StreamRequest request) {
                    return success.iterator();
                }
                @Override public String getModel() { return "test-model"; }
            })
            .model("test-model").maxTokens(32_000).build());
        List<Message> history = List.of(new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("Hi")));
        engine.getMutableMessages().addAll(history);

        List<SDKMessage> output = drain(new QueryLoop(engine, QueryParams.builder()
            .messages(history).systemPrompt("Be helpful").model("test-model")
            .querySource("user").deps(deps).build()));

        assertEquals(List.of(32_000, 32_000), requestMaxTokens);
        assertTrue(engine.getMutableMessages().stream().anyMatch(m -> m instanceof UserMessage um
            && um.message().text() != null
            && Strings.CS.contains(um.message().text(), "Output token limit hit")));
        assertEquals(SDKMessage.Result.SUCCESS,
            assertInstanceOf(SDKMessage.Result.class, output.getLast()).resultType());
    }

    @Test
    void gateOnEscalatesFromCappedDefaultWithoutMetaMessage() {
        List<Integer> requestMaxTokens = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        List<StreamingClient.StreamingEvent> success = endTurnEvents("recovered");
        QueryDeps deps = new QueryDeps() {
            @Override public Iterator<StreamingClient.StreamingEvent> callModel(StreamingClient.StreamRequest request) {
                requestMaxTokens.add(request.maxOutputTokensOverride() != null
                    ? request.maxOutputTokensOverride() : request.maxTokens());
                return (calls.getAndIncrement() == 0
                    ? outputTokenLimitEvents("max_tokens") : success).iterator();
            }
            @Override public MessageCompactor.MicrocompactResult microcompact(List<Message> messages) {
                return new MessageCompactor.MicrocompactResult(messages);
            }
            @Override public boolean shouldAutoCompact(List<Message> messages, String model, String querySource) { return false; }
            @Override public AutoCompactResult autocompact(List<Message> messages, String model, String querySource,
                    AutoCompactTrackingState tracking, String customInstructions, long snipTokensFreed) {
                return new AutoCompactResult(null, null);
            }
            @Override public String uuid() { return UUID.randomUUID().toString(); }
        };
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override public Iterator<StreamingClient.StreamingEvent> createStream(StreamingClient.StreamRequest request) {
                    return success.iterator();
                }
                @Override public String getModel() { return "claude-sonnet-4-6"; }
            })
            .model("claude-sonnet-4-6")
            .maxTokens(8_000)
            .featureFlags(FeatureFlagRegistry.builder()
                .enable(FeatureFlag.MAX_OUTPUT_TOKENS_SLOT).build())
            .build());
        List<Message> history = List.of(new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("Hi")));
        engine.getMutableMessages().addAll(history);

        List<SDKMessage> output = drain(new QueryLoop(engine, QueryParams.builder()
            .messages(history).systemPrompt("Be helpful").model("claude-sonnet-4-6")
            .querySource("user").deps(deps).build()));

        assertEquals(List.of(8_000, 64_000), requestMaxTokens);
        assertFalse(engine.getMutableMessages().stream()
            .anyMatch(m -> m instanceof UserMessage um
                && um.message().text() != null
                && Strings.CS.contains(um.message().text(), "Output token limit hit")),
            "the first slot escalation does not add the meta recovery prompt");
        assertEquals(SDKMessage.Result.SUCCESS,
            assertInstanceOf(SDKMessage.Result.class, output.getLast()).resultType());
    }

    @Test
    void explicitQueryOverrideDisablesSlotEscalation() {
        List<Integer> requestMaxTokens = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        List<StreamingClient.StreamingEvent> success = endTurnEvents("recovered");
        QueryDeps deps = new QueryDeps() {
            @Override public Iterator<StreamingClient.StreamingEvent> callModel(StreamingClient.StreamRequest request) {
                requestMaxTokens.add(request.maxOutputTokensOverride() != null
                    ? request.maxOutputTokensOverride() : request.maxTokens());
                return (calls.getAndIncrement() == 0
                    ? outputTokenLimitEvents("max_tokens") : success).iterator();
            }
            @Override public MessageCompactor.MicrocompactResult microcompact(List<Message> messages) {
                return new MessageCompactor.MicrocompactResult(messages);
            }
            @Override public boolean shouldAutoCompact(List<Message> messages, String model, String querySource) { return false; }
            @Override public AutoCompactResult autocompact(List<Message> messages, String model, String querySource,
                    AutoCompactTrackingState tracking, String customInstructions, long snipTokensFreed) {
                return new AutoCompactResult(null, null);
            }
            @Override public String uuid() { return UUID.randomUUID().toString(); }
        };
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override public Iterator<StreamingClient.StreamingEvent> createStream(StreamingClient.StreamRequest request) {
                    return success.iterator();
                }
                @Override public String getModel() { return "claude-sonnet-4-6"; }
            })
            .model("claude-sonnet-4-6").maxTokens(8_000)
            .featureFlags(FeatureFlagRegistry.builder()
                .enable(FeatureFlag.MAX_OUTPUT_TOKENS_SLOT).build())
            .build());
        List<Message> history = List.of(new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("Hi")));
        engine.getMutableMessages().addAll(history);

        List<SDKMessage> output = drain(new QueryLoop(engine, QueryParams.builder()
            .messages(history).systemPrompt("Be helpful").model("claude-sonnet-4-6")
            .maxOutputTokensOverride(20_000)
            .querySource("user").deps(deps).build()));

        assertEquals(List.of(20_000, 8_000), requestMaxTokens);
        assertTrue(engine.getMutableMessages().stream().anyMatch(m -> m instanceof UserMessage um
            && um.message().text() != null
            && Strings.CS.contains(um.message().text(), "Output token limit hit")));
        assertEquals(SDKMessage.Result.SUCCESS,
            assertInstanceOf(SDKMessage.Result.class, output.getLast()).resultType());
    }

    @Test
    void exhaustedOutputTokenRecoveryEmitsOneApiErrorAndOnlyStopFailure() {
        List<Integer> requestMaxTokens = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        List<String> stopReasons = new ArrayList<>();
        List<String> stopFailureReasons = new ArrayList<>();
        HookDispatcher hooks = new HookDispatcher() {
            @Override public boolean dispatchPreToolUse(String toolName, JsonNode input, String toolUseId) { return true; }
            @Override public void dispatchPostToolUse(String toolName, JsonNode input, JsonNode output, String toolUseId) { }
            @Override public void dispatchUserPromptSubmit(String prompt) { }
            @Override public void dispatchSessionStart(String trigger) { }
            @Override public void dispatchStop(String reason) { stopReasons.add(reason); }
            @Override public void dispatchStopFailure(String reason) { stopFailureReasons.add(reason); }
        };
        QueryDeps deps = new QueryDeps() {
            @Override public Iterator<StreamingClient.StreamingEvent> callModel(StreamingClient.StreamRequest request) {
                requestMaxTokens.add(request.maxOutputTokensOverride() != null
                    ? request.maxOutputTokensOverride() : request.maxTokens());
                calls.incrementAndGet();
                return outputTokenLimitEvents("max_tokens").iterator();
            }
            @Override public MessageCompactor.MicrocompactResult microcompact(List<Message> messages) {
                return new MessageCompactor.MicrocompactResult(messages);
            }
            @Override public boolean shouldAutoCompact(List<Message> messages, String model, String querySource) { return false; }
            @Override public AutoCompactResult autocompact(List<Message> messages, String model, String querySource,
                    AutoCompactTrackingState tracking, String customInstructions, long snipTokensFreed) {
                return new AutoCompactResult(null, null);
            }
            @Override public String uuid() { return UUID.randomUUID().toString(); }
        };
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override public Iterator<StreamingClient.StreamingEvent> createStream(StreamingClient.StreamRequest request) {
                    return outputTokenLimitEvents("max_tokens").iterator();
                }
                @Override public String getModel() { return "test-model"; }
            })
            .model("test-model").maxTokens(32_000).build());
        engine.setHookDispatcher(hooks);
        List<Message> history = List.of(new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("Hi")));
        engine.getMutableMessages().addAll(history);

        List<SDKMessage> output = drain(new QueryLoop(engine, QueryParams.builder()
            .messages(history).systemPrompt("Be helpful").model("test-model")
            .querySource("user").deps(deps).build()));

        assertEquals(4, calls.get());
        assertEquals(List.of(32_000, 32_000, 32_000, 32_000), requestMaxTokens);
        List<SDKMessage.Assistant> apiErrors = output.stream()
            .filter(SDKMessage.Assistant.class::isInstance)
            .map(SDKMessage.Assistant.class::cast)
            .filter(message -> message.message().isApiErrorMessage())
            .toList();
        assertEquals(1, apiErrors.size());
        assertEquals("max_output_tokens", apiErrors.getFirst().message().apiError());
        assertEquals("max_output_tokens", apiErrors.getFirst().message().error());
        assertEquals(List.of("max_output_tokens"), stopFailureReasons);
        assertTrue(stopReasons.isEmpty());
        SDKMessage.Result result = assertInstanceOf(SDKMessage.Result.class, output.getLast());
        assertEquals(SDKMessage.Result.SUCCESS, result.resultType());
        assertTrue(result.isError(), "a successful subtype still carries is_error for API errors");
        assertEquals(1, engine.getMutableMessages().stream()
            .filter(m -> m instanceof AssistantMessage am && am.isApiErrorMessage()).count());
    }

    @Test
    void noJsonSchemaYieldsNullInStreamRequest() {
        AtomicReference<StreamingClient.StreamRequest> captured = new AtomicReference<>();

        var events = endTurnEvents("Hello");
        var deps = new QueryDeps() {
            @Override
            public Iterator<StreamingClient.StreamingEvent> callModel(StreamingClient.StreamRequest request) {
                captured.set(request);
                return events.iterator();
            }
            @Override public MessageCompactor.MicrocompactResult microcompact(List<Message> messages) {
                return new MessageCompactor.MicrocompactResult(messages);
            }
            @Override public boolean shouldAutoCompact(List<Message> messages, String model, String querySource) {
                return false;
            }
            @Override public QueryDeps.AutoCompactResult autocompact(List<Message> messages, String model, String querySource,
                                                                      AutoCompactTrackingState tracking, String customInstructions,
                                                                      long snipTokensFreed) {
                return new QueryDeps.AutoCompactResult(null, null);
            }
            @Override public String uuid() { return UUID.randomUUID().toString(); }
        };

        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingClient.StreamingEvent> createStream(StreamingClient.StreamRequest request) {
                    return events.iterator();
                }
                @Override public String getModel() { return "test-model"; }
            })
            .systemPrompt("Be helpful")
            .build());

        var history = List.<Message>of(new UserMessage(
            UUID.randomUUID().toString(), MessageContent.ofText("Hi")));
        engine.getMutableMessages().addAll(history);

        var params = QueryParams.builder()
            .messages(history)
            .systemPrompt("Be helpful")
            .model("test-model")
            .querySource("user")
            .deps(deps)
            .build();

        List<SDKMessage> messages = drain(new QueryLoop(engine, params));

        assertNotNull(captured.get(), "callModel should have been invoked");
        assertNull(captured.get().jsonSchema(),
            "jsonSchema must be null when no JSON schema provided");
    }

    @Test
    void toolDefinitionsReceiveTheCurrentRequestPromptContext() {
        AtomicReference<StreamingClient.StreamRequest> capturedRequest = new AtomicReference<>();
        AtomicReference<ToolExecutionContext> capturedContext = new AtomicReference<>();
        var events = endTurnEvents("OK");
        ToolExecutor executor = new ToolExecutor() {
            @Override
            public ToolResult execute(
                    String toolName, JsonNode input, ToolExecutionContext context) {
                return ToolResult.success("unused");
            }

            @Override
            public List<StreamingClient.StreamRequest.ToolDef> getToolDefinitions() {
                return List.of(new StreamingClient.StreamRequest.ToolDef(
                    "Agent", "legacy-null-context", null));
            }

            @Override
            public List<StreamingClient.StreamRequest.ToolDef> getToolDefinitions(
                    ToolExecutionContext context) {
                capturedContext.set(context);
                return List.of(new StreamingClient.StreamRequest.ToolDef(
                    "Agent", "context:" + context.currentModel(), null));
            }

            @Override
            public List<StreamingClient.StreamRequest.ToolDef> getToolDefinitions(
                    Set<String> discoveredToolNames,
                    ToolExecutionContext context) {
                return getToolDefinitions(context);
            }
        };
        QueryDeps deps = new QueryDeps() {
            @Override
            public Iterator<StreamingClient.StreamingEvent> callModel(
                    StreamingClient.StreamRequest request) {
                capturedRequest.set(request);
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
            public QueryDeps.AutoCompactResult autocompact(
                    List<Message> messages, String model, String querySource,
                    AutoCompactTrackingState tracking, String customInstructions,
                    long snipTokensFreed) {
                return new QueryDeps.AutoCompactResult(null, null);
            }

            @Override public String uuid() { return UUID.randomUUID().toString(); }
        };
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingClient.StreamingEvent> createStream(
                        StreamingClient.StreamRequest request) {
                    return events.iterator();
                }

                @Override public String getModel() { return "gpt-5.6-sol"; }
            })
            .toolExecutor(executor)
            .tools(List.of("Agent", "Read"))
            .workingDirectory("/tmp/prompt-context")
            .systemPrompt("Be helpful")
            .model("gpt-5.6-sol")
            .build());
        var params = QueryParams.builder()
            .messages(List.of())
            .systemPrompt("Be helpful")
            .model("gpt-5.6-sol")
            .querySource("user")
            .deps(deps)
            .build();

        drain(new QueryLoop(engine, params, "inspect", SubmitOptions.DEFAULT));

        assertNotNull(capturedContext.get());
        assertEquals("gpt-5.6-sol", capturedContext.get().currentModel());
        assertEquals("/tmp/prompt-context", capturedContext.get().workingDirectory());
        assertEquals(List.of("Agent", "Read"), capturedContext.get().enabledTools());
        assertEquals("context:gpt-5.6-sol",
            capturedRequest.get().tools().getFirst().description());
    }

    @Test
    void openAiResponsesProtocolBypassesAnthropicToolSearch() {
        ToolSearchGate.configureProtocolResolver(_ -> ModelApiProtocol.OPENAI_RESPONSES);
        AtomicReference<StreamingClient.StreamRequest> capturedRequest = new AtomicReference<>();
        AtomicInteger fullDefinitionCalls = new AtomicInteger();
        AtomicInteger deferredDefinitionCalls = new AtomicInteger();
        var events = endTurnEvents("OK");
        ToolExecutor executor = new ToolExecutor() {
            @Override
            public ToolResult execute(
                    String toolName, JsonNode input, ToolExecutionContext context) {
                return ToolResult.success("unused");
            }

            @Override
            public List<StreamingClient.StreamRequest.ToolDef> getToolDefinitions() {
                return List.of(new StreamingClient.StreamRequest.ToolDef(
                    "DeferredTool", "full model prompt", null));
            }

            @Override
            public List<StreamingClient.StreamRequest.ToolDef> getToolDefinitions(
                    ToolExecutionContext context) {
                fullDefinitionCalls.incrementAndGet();
                return getToolDefinitions();
            }

            @Override
            public List<StreamingClient.StreamRequest.ToolDef> getToolDefinitions(
                    Set<String> discoveredToolNames,
                    ToolExecutionContext context) {
                deferredDefinitionCalls.incrementAndGet();
                return List.of(new StreamingClient.StreamRequest.ToolDef(
                    "ToolSearch", "anthropic discovery", null));
            }

            @Override
            public List<String> getDeferredToolNames() {
                return List.of("DeferredTool");
            }
        };
        QueryDeps deps = new QueryDeps() {
            @Override
            public Iterator<StreamingClient.StreamingEvent> callModel(
                    StreamingClient.StreamRequest request) {
                capturedRequest.set(request);
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
            public QueryDeps.AutoCompactResult autocompact(
                    List<Message> messages, String model, String querySource,
                    AutoCompactTrackingState tracking, String customInstructions,
                    long snipTokensFreed) {
                return new QueryDeps.AutoCompactResult(null, null);
            }

            @Override public String uuid() { return UUID.randomUUID().toString(); }
        };
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingClient.StreamingEvent> createStream(
                        StreamingClient.StreamRequest request) {
                    return events.iterator();
                }

                @Override public String getModel() { return "gpt-5.6-sol"; }
            })
            .toolExecutor(executor)
            .systemPrompt("Be helpful")
            .model("gpt-5.6-sol")
            .build());
        var params = QueryParams.builder()
            .messages(List.of())
            .systemPrompt("Be helpful")
            .model("gpt-5.6-sol")
            .querySource("user")
            .deps(deps)
            .build();

        drain(new QueryLoop(engine, params, "plan this change", SubmitOptions.DEFAULT));

        assertTrue(fullDefinitionCalls.get() > 0);
        assertEquals(0, deferredDefinitionCalls.get());
        assertEquals("DeferredTool", capturedRequest.get().tools().getFirst().name());
        assertEquals("full model prompt", capturedRequest.get().tools().getFirst().description());
        assertFalse(capturedRequest.get().messages().stream()
            .map(message -> String.valueOf(message.content()))
            .anyMatch(content -> content.contains("<available-deferred-tools>")));
    }


    @Test
    void structuredOutputIsCapturedAndSurfacedOnResult() throws Exception {
        JsonNode payload = new ObjectMapper().readTree("{\"answer\":42}");
        AtomicInteger callCount = new AtomicInteger(0);

        // Round 1: model requests the StructuredOutput tool. Round 2: natural end.
        var toolUseEvents = List.<StreamingClient.StreamingEvent>of(
            new StreamingClient.StreamingEvent.MessageStartEvent("msg-1", "test-model", List.of(), new Usage(0, 0, 0, 0)),
            new StreamingClient.StreamingEvent.ContentBlockStartEvent(0, "tool_use", "so-1", "StructuredOutput"),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(0, "input_json_delta", "{\"q\":1}"),
            new StreamingClient.StreamingEvent.ContentBlockStopEvent(0),
            new StreamingClient.StreamingEvent.MessageDeltaEvent("tool_use", new Usage(0, 0, 0, 0)),
            new StreamingClient.StreamingEvent.MessageStopEvent()
        );

        var deps = new QueryDeps() {
            @Override
            public Iterator<StreamingClient.StreamingEvent> callModel(StreamingClient.StreamRequest request) {
                int n = callCount.getAndIncrement();
                return (n == 0 ? toolUseEvents : endTurnEvents("Final answer")).iterator();
            }
            @Override public MessageCompactor.MicrocompactResult microcompact(List<Message> messages) {
                return new MessageCompactor.MicrocompactResult(messages);
            }
            @Override public boolean shouldAutoCompact(List<Message> messages, String model, String querySource) {
                return false;
            }
            @Override public QueryDeps.AutoCompactResult autocompact(List<Message> messages, String model, String querySource,
                                                                      AutoCompactTrackingState tracking, String customInstructions,
                                                                      long snipTokensFreed) {
                return new QueryDeps.AutoCompactResult(null, null);
            }
            @Override public String uuid() { return UUID.randomUUID().toString(); }
            @Override public ToolRunner toolRunner() {
                return (List<ContentBlock> blocks, DefaultQuerySession engine,
                        boolean _, int _, Consumer<SDKMessage> _,
                        String _) -> {
                    for (ContentBlock b : blocks) {
                        if (b instanceof ToolUseBlock tub && Strings.CS.equals("StructuredOutput", tub.name())) {
                            // Record a successful tool result so the completion enforcement passes.
                            engine.getMutableMessages().add(new UserMessage(
                                UUID.randomUUID().toString(),
                                MessageContent.ofBlocks(List.of(new ToolResultBlock(
                                    tub.id(), List.of(new TextBlock("ok")), false)))));
                            return new ToolRunner.RunOutcome(false, null, 0, false, null, payload);
                        }
                    }
                    return new ToolRunner.RunOutcome(false, null, 0, false, null, null);
                };
            }
        };

        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingClient.StreamingEvent> createStream(StreamingClient.StreamRequest request) {
                    return toolUseEvents.iterator();
                }
                @Override public String getModel() { return "test-model"; }
            })
            .systemPrompt("Be helpful")
            .build());

        var history = List.<Message>of(new UserMessage(
            UUID.randomUUID().toString(), MessageContent.ofText("Hi")));
        engine.getMutableMessages().addAll(history);

        var params = QueryParams.builder()
            .messages(history)
            .systemPrompt("Be helpful")
            .model("test-model")
            .querySource("user")
            .deps(deps)
            .build();

        // hasJsonSchema=true routes the StructuredOutput enforcement + payload capture.
        List<SDKMessage> messages = drain(new QueryLoop(engine, params, "Hi",
            SubmitOptions.withSchema("test", payload)));

        var result = messages.stream()
            .filter(SDKMessage.Result.class::isInstance)
            .map(SDKMessage.Result.class::cast)
            .reduce((_, second) -> second)
            .orElseThrow(() -> new AssertionError("expected a Result message"));

        assertEquals(SDKMessage.Result.SUCCESS, result.resultType(),
            "natural turn end with a successful StructuredOutput call must succeed");
        assertEquals(payload, result.structuredOutput(),
            "structured_output payload from the StructuredOutput tool must surface on the result");
    }

    @Test
    void fifthStructuredOutputAttemptMaySucceed() throws Exception {
        JsonNode payload = new ObjectMapper().readTree("{\"answer\":42}");
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolAttempts = new AtomicInteger();

        var deps = new QueryDeps() {
            @Override
            public Iterator<StreamingClient.StreamingEvent> callModel(
                    StreamingClient.StreamRequest request) {
                int call = modelCalls.getAndIncrement();
                if (call >= 5) return endTurnEvents("Final answer").iterator();
                String id = "so-" + (call + 1);
                return List.<StreamingClient.StreamingEvent>of(
                    new StreamingClient.StreamingEvent.MessageStartEvent(
                        "msg-" + id, "test-model", List.of(), Usage.EMPTY),
                    new StreamingClient.StreamingEvent.ContentBlockStartEvent(
                        0, "tool_use", id, "StructuredOutput"),
                    new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(
                        0, "input_json_delta", "{\"answer\":42}"),
                    new StreamingClient.StreamingEvent.ContentBlockStopEvent(0),
                    new StreamingClient.StreamingEvent.MessageDeltaEvent(
                        "tool_use", Usage.EMPTY),
                    new StreamingClient.StreamingEvent.MessageStopEvent()).iterator();
            }

            @Override public MessageCompactor.MicrocompactResult microcompact(List<Message> messages) {
                return new MessageCompactor.MicrocompactResult(messages);
            }
            @Override public boolean shouldAutoCompact(
                    List<Message> messages, String model, String querySource) {
                return false;
            }
            @Override public QueryDeps.AutoCompactResult autocompact(
                    List<Message> messages, String model, String querySource,
                    AutoCompactTrackingState tracking, String customInstructions,
                    long snipTokensFreed) {
                return new QueryDeps.AutoCompactResult(null, null);
            }
            @Override public String uuid() { return UUID.randomUUID().toString(); }
            @Override public ToolRunner toolRunner() {
                return (List<ContentBlock> blocks, DefaultQuerySession engine,
                        boolean _, int _, Consumer<SDKMessage> _, String _) -> {
                    ToolUseBlock call = blocks.stream()
                        .filter(ToolUseBlock.class::isInstance)
                        .map(ToolUseBlock.class::cast)
                        .filter(block -> Strings.CS.equals("StructuredOutput", block.name()))
                        .findFirst().orElseThrow();
                    int attempt = toolAttempts.incrementAndGet();
                    engine.getMutableMessages().add(new UserMessage(
                        UUID.randomUUID().toString(), MessageContent.ofBlocks(List.of(
                            new ToolResultBlock(call.id(),
                                List.of(new TextBlock(attempt == 5 ? "ok" : "invalid")),
                                attempt < 5)))));
                    return new ToolRunner.RunOutcome(
                        false, null, 0, false, null, attempt == 5 ? payload : null);
                };
            }
        };

        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override public Iterator<StreamingClient.StreamingEvent> createStream(
                        StreamingClient.StreamRequest request) {
                    return endTurnEvents("unused").iterator();
                }
                @Override public String getModel() { return "test-model"; }
            })
            .systemPrompt("Be helpful")
            .build());
        var history = List.<Message>of(new UserMessage(
            UUID.randomUUID().toString(), MessageContent.ofText("Hi")));
        engine.getMutableMessages().addAll(history);
        var params = QueryParams.builder()
            .messages(history)
            .systemPrompt("Be helpful")
            .model("test-model")
            .querySource("user")
            .deps(deps)
            .build();

        List<SDKMessage> messages = drain(new QueryLoop(engine, params, "Hi",
            SubmitOptions.withSchema("test", payload)));
        SDKMessage.Result result = messages.stream()
            .filter(SDKMessage.Result.class::isInstance)
            .map(SDKMessage.Result.class::cast)
            .reduce((_, second) -> second)
            .orElseThrow();

        assertEquals(SDKMessage.Result.SUCCESS, result.resultType());
        assertEquals(payload, result.structuredOutput());
        assertEquals(5, toolAttempts.get());
        assertEquals(6, modelCalls.get());
    }

    @Test
    void abortAfterToolResultCountsToolResultAndInterruptionAsUserTurns() {
        var toolUseEvents = List.<StreamingClient.StreamingEvent>of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-permission", "test-model", List.of(), Usage.EMPTY),
            new StreamingClient.StreamingEvent.ContentBlockStartEvent(
                0, "tool_use", "toolu-permission", "Bash"),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(
                0, "input_json_delta", "{\"command\":\"rm /tmp/probe\"}"),
            new StreamingClient.StreamingEvent.ContentBlockStopEvent(0),
            new StreamingClient.StreamingEvent.MessageDeltaEvent(
                "tool_use", Usage.EMPTY),
            new StreamingClient.StreamingEvent.MessageStopEvent());

        var deps = new QueryDeps() {
            @Override
            public Iterator<StreamingClient.StreamingEvent> callModel(
                    StreamingClient.StreamRequest request) {
                return toolUseEvents.iterator();
            }

            @Override
            public MessageCompactor.MicrocompactResult microcompact(List<Message> messages) {
                return new MessageCompactor.MicrocompactResult(messages);
            }

            @Override
            public boolean shouldAutoCompact(List<Message> messages, String model,
                                             String querySource) {
                return false;
            }

            @Override
            public QueryDeps.AutoCompactResult autocompact(
                    List<Message> messages, String model, String querySource,
                    AutoCompactTrackingState tracking, String customInstructions,
                    long snipTokensFreed) {
                return new QueryDeps.AutoCompactResult(null, null);
            }

            @Override public String uuid() { return UUID.randomUUID().toString(); }

            @Override
            public ToolRunner toolRunner() {
                return (blocks, engine, _, _, emit,
                        _) -> {
                    ToolUseBlock toolUse = (ToolUseBlock) blocks.getFirst();
                    UserMessage toolResult = new UserMessage(
                        UUID.randomUUID().toString(),
                        MessageContent.ofBlocks(List.of(new ToolResultBlock(
                            toolUse.id(), List.of(new TextBlock(
                                "User rejected tool use")), true))));
                    engine.getMutableMessages().add(toolResult);
                    emit.accept(new SDKMessage.User(toolResult));
                    engine.getAbortController().abort("user_reject_permission");
                    return new ToolRunner.RunOutcome(
                        false, null, 1, false, null, null);
                };
            }
        };

        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingClient.StreamingEvent> createStream(
                        StreamingClient.StreamRequest request) {
                    return toolUseEvents.iterator();
                }

                @Override public String getModel() { return "test-model"; }
            })
            .systemPrompt("Be helpful")
            .build());
        List<Message> history = List.of(new UserMessage(
            UUID.randomUUID().toString(), MessageContent.ofText("Hi")));
        engine.getMutableMessages().addAll(history);
        QueryParams params = QueryParams.builder()
            .messages(history)
            .systemPrompt("Be helpful")
            .model("test-model")
            .querySource("user")
            .deps(deps)
            .build();

        List<SDKMessage> messages = drain(new QueryLoop(engine, params));

        List<SDKMessage.User> users = messages.stream()
            .filter(SDKMessage.User.class::isInstance)
            .map(SDKMessage.User.class::cast)
            .toList();
        assertEquals(2, users.size(),
            "tool_result and visible interruption are separate user events");
        SDKMessage.Result result = assertInstanceOf(
            SDKMessage.Result.class, messages.getLast());
        assertEquals(3, result.numTurns(),
            "initial turn + tool_result user + interruption user");
    }

    // ------------------------------------------------------------------------
    // Characterization tests for the stream-consumption loop (runLoop lines
    // ~370-482) — locking down current behavior before extracting it into a
    // standalone consumeStream method. These must stay green, unchanged,
    // across the refactor.
    // ------------------------------------------------------------------------

    @Test
    void thinkingAndSignatureDeltaAccumulateIntoThinkingBlock() {
        var events = List.<StreamingClient.StreamingEvent>of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-1", "test-model", List.of(), new Usage(10, 0, 0, 0)),
            new StreamingClient.StreamingEvent.ContentBlockStartEvent(0, "thinking", null, null),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(0, "thinking_delta", "Let me think"),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(0, "signature_delta", "sig123"),
            new StreamingClient.StreamingEvent.ContentBlockStopEvent(0),
            new StreamingClient.StreamingEvent.MessageDeltaEvent("end_turn", new Usage(0, 5, 0, 0)),
            new StreamingClient.StreamingEvent.MessageStopEvent()
        );
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingClient.StreamingEvent> createStream(StreamingClient.StreamRequest request) {
                    return events.iterator();
                }
                @Override
                public String getModel() { return "test-model"; }
            })
            .systemPrompt("Be helpful")
            .build());

        var history = List.<Message>of(new UserMessage(
            UUID.randomUUID().toString(), MessageContent.ofText("Hi")));
        engine.getMutableMessages().addAll(history);

        var params = QueryParams.builder()
            .messages(history)
            .systemPrompt("Be helpful")
            .model("test-model")
            .querySource("user")
            .deps(fakeDeps(events))
            .build();

        List<SDKMessage> messages = drain(new QueryLoop(engine, params));

        var assistant = messages.stream()
            .filter(SDKMessage.Assistant.class::isInstance)
            .map(SDKMessage.Assistant.class::cast)
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected an Assistant message"));
        var content = assistant.message().message().content();
        assertEquals(1, content.size());
        var thinkingBlock = assertInstanceOf(
            ThinkingBlock.class, content.getFirst());
        assertEquals("Let me think", thinkingBlock.thinking());
        assertEquals("sig123", thinkingBlock.signature());

        boolean sawThinkingDeltaEvent = messages.stream().anyMatch(
            m -> m instanceof SDKMessage.StreamEvent se
                && Strings.CS.equals("thinking_delta", se.eventType())
                && se.data() instanceof String data
                && Strings.CS.equals("Let me think", data));
        assertTrue(sawThinkingDeltaEvent, "thinking_delta must be forwarded as a StreamEvent");
    }

    @Test
    void contentBlocksAreOrderedByStopEventNotByStartOrIndex() {
        // Block index 1 STARTS first but STOPS first too here would be trivial;
        // the interesting case is when start order and stop order diverge from
        // index order: index 1 starts before index 0, and index 1 also stops
        // before index 0. contentBlocks must reflect stop order.
        var events = List.<StreamingClient.StreamingEvent>of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-1", "test-model", List.of(), new Usage(1, 0, 0, 0)),
            new StreamingClient.StreamingEvent.ContentBlockStartEvent(1, "text", null, null),
            new StreamingClient.StreamingEvent.ContentBlockStartEvent(0, "thinking", null, null),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(1, "text_delta", "second-started-text"),
            new StreamingClient.StreamingEvent.ContentBlockStopEvent(1),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(0, "thinking_delta", "first-started-thinking"),
            new StreamingClient.StreamingEvent.ContentBlockStopEvent(0),
            new StreamingClient.StreamingEvent.MessageDeltaEvent("end_turn", new Usage(0, 1, 0, 0)),
            new StreamingClient.StreamingEvent.MessageStopEvent()
        );
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingClient.StreamingEvent> createStream(StreamingClient.StreamRequest request) {
                    return events.iterator();
                }
                @Override
                public String getModel() { return "test-model"; }
            })
            .systemPrompt("Be helpful")
            .build());

        var history = List.<Message>of(new UserMessage(
            UUID.randomUUID().toString(), MessageContent.ofText("Hi")));
        engine.getMutableMessages().addAll(history);

        var params = QueryParams.builder()
            .messages(history)
            .systemPrompt("Be helpful")
            .model("test-model")
            .querySource("user")
            .deps(fakeDeps(events))
            .build();

        List<SDKMessage> messages = drain(new QueryLoop(engine, params));

        var assistants = messages.stream()
            .filter(SDKMessage.Assistant.class::isInstance)
            .map(SDKMessage.Assistant.class::cast)
            .toList();
        assertEquals(2, assistants.size(),
            "197 yields one assistant message for each completed content block");
        // Index-1 stopped first, so its one-block assistant must be first.
        var firstContent = assistants.getFirst().message().message().content();
        assertEquals(1, firstContent.size());
        assertInstanceOf(TextBlock.class, firstContent.getFirst());
        assertEquals("second-started-text", ((TextBlock) firstContent.getFirst()).text());
        var secondContent = assistants.get(1).message().message().content();
        assertEquals(1, secondContent.size());
        var thinkingBlock = assertInstanceOf(
            ThinkingBlock.class, secondContent.getFirst());
        assertEquals("first-started-thinking", thinkingBlock.thinking());
    }

    @Test
    void toolUseAccumulatesInputJsonAndEmitsStreamingStartDoneEvents() {
        AtomicInteger callCount = new AtomicInteger(0);
        var toolUseEvents = List.<StreamingClient.StreamingEvent>of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-1", "test-model", List.of(), new Usage(0, 0, 0, 0)),
            new StreamingClient.StreamingEvent.ContentBlockStartEvent(0, "tool_use", "tool-1", "Bash"),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(0, "input_json_delta", "{\"cmd\":"),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(0, "input_json_delta", "\"ls\"}"),
            new StreamingClient.StreamingEvent.ContentBlockStopEvent(0),
            new StreamingClient.StreamingEvent.MessageDeltaEvent("tool_use", new Usage(0, 0, 0, 0)),
            new StreamingClient.StreamingEvent.MessageStopEvent()
        );
        var endEvents = endTurnEvents("done");

        var deps = new QueryDeps() {
            @Override
            public Iterator<StreamingClient.StreamingEvent> callModel(StreamingClient.StreamRequest request) {
                int n = callCount.getAndIncrement();
                return (n == 0 ? toolUseEvents : endEvents).iterator();
            }
            @Override public MessageCompactor.MicrocompactResult microcompact(List<Message> messages) {
                return new MessageCompactor.MicrocompactResult(messages);
            }
            @Override public boolean shouldAutoCompact(List<Message> messages, String model, String querySource) {
                return false;
            }
            @Override public QueryDeps.AutoCompactResult autocompact(List<Message> messages, String model, String querySource,
                                                                      AutoCompactTrackingState tracking, String customInstructions,
                                                                      long snipTokensFreed) {
                return new QueryDeps.AutoCompactResult(null, null);
            }
            @Override public String uuid() { return UUID.randomUUID().toString(); }
            @Override public ToolRunner toolRunner() {
                return (List<ContentBlock> _, DefaultQuerySession _,
                        boolean _, int _, Consumer<SDKMessage> _,
                        String _) ->
                    new ToolRunner.RunOutcome(false, null, 0, false, null, null);
            }
        };

        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingClient.StreamingEvent> createStream(StreamingClient.StreamRequest request) {
                    return toolUseEvents.iterator();
                }
                @Override public String getModel() { return "test-model"; }
            })
            .systemPrompt("Be helpful")
            .build());

        var history = List.<Message>of(new UserMessage(
            UUID.randomUUID().toString(), MessageContent.ofText("Hi")));
        engine.getMutableMessages().addAll(history);

        var params = QueryParams.builder()
            .messages(history)
            .systemPrompt("Be helpful")
            .model("test-model")
            .querySource("user")
            .deps(deps)
            .build();

        List<SDKMessage> messages = drain(new QueryLoop(engine, params));

        boolean sawStart = messages.stream().anyMatch(
            m -> m instanceof SDKMessage.StreamEvent se
                && Strings.CS.equals("tool_streaming_start", se.eventType())
                && se.data() instanceof String data
                && Strings.CS.equals("Bash|tool-1|msg-1", data));
        assertTrue(sawStart,
            "tool_streaming_start StreamEvent must fire with name|id|assistant-message-id");

        boolean sawDone = messages.stream().anyMatch(
            m -> m instanceof SDKMessage.StreamEvent se
                && Strings.CS.equals("tool_streaming_done", se.eventType())
                && se.data() instanceof String s
                && Strings.CS.startsWith(s, "Bash|tool-1|")
                && Strings.CS.contains(s, "ls"));
        assertTrue(sawDone,
            "tool_streaming_done StreamEvent must carry the accumulated input JSON");

        boolean sawMessageStop = messages.stream().anyMatch(
            m -> m instanceof SDKMessage.StreamEvent se
                && Strings.CS.equals("message_stop", se.eventType()));
        assertTrue(sawMessageStop,
            "provider message_stop must be exposed so the UI can enter tool-use while tools execute");

        var firstAssistant = messages.stream()
            .filter(SDKMessage.Assistant.class::isInstance)
            .map(SDKMessage.Assistant.class::cast)
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected an Assistant message"));
        var toolUseBlock = assertInstanceOf(
            ToolUseBlock.class, firstAssistant.message().message().content().getFirst());
        assertEquals("ls", toolUseBlock.input().get("cmd").asText(),
            "input_json_delta fragments must accumulate into one valid JSON object");
    }

    @Test
    void abortDuringStreamDropsUnstoppedNonTextBlocksButKeepsPartialText() {
        var events = List.<StreamingClient.StreamingEvent>of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-1", "test-model", List.of(), new Usage(1, 0, 0, 0)),
            new StreamingClient.StreamingEvent.ContentBlockStartEvent(0, "text", null, null),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", "partial text"),
            new StreamingClient.StreamingEvent.ContentBlockStartEvent(1, "tool_use", "tool-x", "SomeTool"),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(1, "input_json_delta", "{\"a\":1}"),
            // Everything below must NEVER be consumed — abort fires before this point.
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", "-not-consumed"),
            new StreamingClient.StreamingEvent.ContentBlockStopEvent(0),
            new StreamingClient.StreamingEvent.ContentBlockStopEvent(1),
            new StreamingClient.StreamingEvent.MessageDeltaEvent("end_turn", new Usage(0, 1, 0, 0)),
            new StreamingClient.StreamingEvent.MessageStopEvent()
        );

        var engineRef = new AtomicReference<DefaultQuerySession>();
        AtomicInteger consumed = new AtomicInteger(0);
        var deps = new QueryDeps() {
            @Override
            public Iterator<StreamingClient.StreamingEvent> callModel(StreamingClient.StreamRequest request) {
                Iterator<StreamingClient.StreamingEvent> delegate = events.iterator();
                return new Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        // After the 5th event (MessageStart, Start0, Delta0, Start1,
                        // Delta1) has been consumed, simulate a user-initiated abort
                        // arriving mid-stream.
                        if (consumed.get() == 5) {
                            engineRef.get().getAbortController().abort("test-abort");
                        }
                        return delegate.hasNext();
                    }
                    @Override
                    public StreamingClient.StreamingEvent next() {
                        consumed.incrementAndGet();
                        return delegate.next();
                    }
                };
            }
            @Override public MessageCompactor.MicrocompactResult microcompact(List<Message> messages) {
                return new MessageCompactor.MicrocompactResult(messages);
            }
            @Override public boolean shouldAutoCompact(List<Message> messages, String model, String querySource) {
                return false;
            }
            @Override public QueryDeps.AutoCompactResult autocompact(List<Message> messages, String model, String querySource,
                                                                      AutoCompactTrackingState tracking, String customInstructions,
                                                                      long snipTokensFreed) {
                return new QueryDeps.AutoCompactResult(null, null);
            }
            @Override public String uuid() { return UUID.randomUUID().toString(); }
        };

        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingClient.StreamingEvent> createStream(StreamingClient.StreamRequest request) {
                    return events.iterator();
                }
                @Override public String getModel() { return "test-model"; }
            })
            .systemPrompt("Be helpful")
            .build());
        engineRef.set(engine);

        var history = List.<Message>of(new UserMessage(
            UUID.randomUUID().toString(), MessageContent.ofText("Hi")));
        engine.getMutableMessages().addAll(history);

        var params = QueryParams.builder()
            .messages(history)
            .systemPrompt("Be helpful")
            .model("test-model")
            .querySource("user")
            .deps(deps)
            .build();

        List<SDKMessage> messages = drain(new QueryLoop(engine, params));

        var assistant = messages.stream()
            .filter(SDKMessage.Assistant.class::isInstance)
            .map(SDKMessage.Assistant.class::cast)
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected an Assistant message"));
        var content = assistant.message().message().content();
        // Only the salvaged text block survives: the in-progress tool_use block
        // is dropped wholesale on abort, and the unconsumed later delta never
        // reaches the text block either.
        assertEquals(1, content.size());
        assertInstanceOf(TextBlock.class, content.getFirst());
        assertEquals("partial text", ((TextBlock) content.getFirst()).text());

        var last = messages.getLast();
        assertInstanceOf(SDKMessage.Result.class, last);
        assertEquals(SDKMessage.Result.ERROR_DURING_EXECUTION, ((SDKMessage.Result) last).resultType(),
            "abort mid-stream must terminate as an execution error result (aborted_streaming)");
    }

    @Test
    void cancellationErrorEventAfterUserAbortIsClassifiedAsAbortedStreaming() {
        var engineRef = new AtomicReference<DefaultQuerySession>();
        var requestAborted = new RuntimeException("Request aborted");
        var deps = new QueryDeps() {
            @Override
            public Iterator<StreamingClient.StreamingEvent> callModel(StreamingClient.StreamRequest request) {
                return new Iterator<>() {
                    private boolean emitted;

                    @Override
                    public boolean hasNext() {
                        return !emitted;
                    }

                    @Override
                    public StreamingClient.StreamingEvent next() {
                        emitted = true;
                        engineRef.get().getAbortController().abort("user-cancel");
                        return new StreamingClient.StreamingEvent.ErrorEvent(requestAborted);
                    }
                };
            }

            @Override public MessageCompactor.MicrocompactResult microcompact(List<Message> messages) {
                return new MessageCompactor.MicrocompactResult(messages);
            }
            @Override public boolean shouldAutoCompact(List<Message> messages, String model, String querySource) {
                return false;
            }
            @Override public QueryDeps.AutoCompactResult autocompact(
                    List<Message> messages, String model, String querySource,
                    AutoCompactTrackingState tracking, String customInstructions,
                    long snipTokensFreed) {
                return new QueryDeps.AutoCompactResult(null, null);
            }
            @Override public String uuid() { return UUID.randomUUID().toString(); }
        };

        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override public Iterator<StreamingClient.StreamingEvent> createStream(
                        StreamingClient.StreamRequest request) {
                    return List.<StreamingClient.StreamingEvent>of().iterator();
                }
                @Override public String getModel() { return "test-model"; }
            })
            .systemPrompt("Be helpful")
            .build());
        engineRef.set(engine);

        var history = List.<Message>of(new UserMessage(
            UUID.randomUUID().toString(), MessageContent.ofText("Hi")));
        engine.getMutableMessages().addAll(history);
        var params = QueryParams.builder()
            .messages(history)
            .systemPrompt("Be helpful")
            .model("test-model")
            .querySource("user")
            .deps(deps)
            .build();

        List<SDKMessage> messages = drain(new QueryLoop(engine, params));

        assertFalse(messages.stream().anyMatch(SDKMessage.Error.class::isInstance),
            "the transport cancellation error is an implementation detail, not an SDK error event");
        SDKMessage.User interruption = messages.stream()
            .filter(SDKMessage.User.class::isInstance)
            .map(SDKMessage.User.class::cast)
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected a user interruption message"));
        assertEquals("[Request interrupted by user]",
            ((TextBlock) interruption.message().message().blocks().getFirst()).text());
        SDKMessage.Result result = assertInstanceOf(SDKMessage.Result.class, messages.getLast());
        assertEquals(SDKMessage.Result.ERROR_DURING_EXECUTION, result.resultType());
        assertEquals(2, result.numTurns(),
            "the emitted interruption user is the second SDK turn");
        assertEquals(List.of(
            "[ede_diagnostic] result_type=user last_content_type=n/a stop_reason=null"),
            result.errors(),
            "the transport cancellation is suppressed, but the released SDK diagnostic remains");
    }

    @Test
    void messageDeltaReplacesCumulativeUsageAndLastStopReasonWins() {
        AtomicInteger callCount = new AtomicInteger(0);
        var firstTurnEvents = List.<StreamingClient.StreamingEvent>of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-1", "test-model", List.of(), new Usage(10, 1, 0, 0)),
            new StreamingClient.StreamingEvent.ContentBlockStartEvent(0, "text", null, null),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", "partial reply"),
            new StreamingClient.StreamingEvent.ContentBlockStopEvent(0),
            // stopReason arrives null on the first delta, then non-null on the
            // second — Anthropic usage events are cumulative snapshots, so the
            // last output_tokens value replaces the earlier values. The non-null
            // stopReason must also win over the earlier null one.
            new StreamingClient.StreamingEvent.MessageDeltaEvent(null, new Usage(0, 3, 0, 0)),
            new StreamingClient.StreamingEvent.MessageDeltaEvent("max_tokens", new Usage(0, 2, 0, 0)),
            new StreamingClient.StreamingEvent.MessageStopEvent()
        );
        var endEvents = endTurnEvents("final");

        var deps = new QueryDeps() {
            @Override
            public Iterator<StreamingClient.StreamingEvent> callModel(StreamingClient.StreamRequest request) {
                int n = callCount.getAndIncrement();
                return (n == 0 ? firstTurnEvents : endEvents).iterator();
            }
            @Override public MessageCompactor.MicrocompactResult microcompact(List<Message> messages) {
                return new MessageCompactor.MicrocompactResult(messages);
            }
            @Override public boolean shouldAutoCompact(List<Message> messages, String model, String querySource) {
                return false;
            }
            @Override public QueryDeps.AutoCompactResult autocompact(List<Message> messages, String model, String querySource,
                                                                      AutoCompactTrackingState tracking, String customInstructions,
                                                                      long snipTokensFreed) {
                return new QueryDeps.AutoCompactResult(null, null);
            }
            @Override public String uuid() { return UUID.randomUUID().toString(); }
        };

        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingClient.StreamingEvent> createStream(StreamingClient.StreamRequest request) {
                    return firstTurnEvents.iterator();
                }
                @Override public String getModel() { return "test-model"; }
            })
            .systemPrompt("Be helpful")
            .build());

        var history = List.<Message>of(new UserMessage(
            UUID.randomUUID().toString(), MessageContent.ofText("Hi")));
        engine.getMutableMessages().addAll(history);

        var params = QueryParams.builder()
            .messages(history)
            .systemPrompt("Be helpful")
            .model("test-model")
            .querySource("user")
            .deps(deps)
            .build();

        List<SDKMessage> messages = drain(new QueryLoop(engine, params));

        var firstAssistant = messages.stream()
            .filter(SDKMessage.Assistant.class::isInstance)
            .map(SDKMessage.Assistant.class::cast)
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected a first-turn Assistant message"));
        assertEquals(10, firstAssistant.usage().inputTokens());
        assertEquals(1, firstAssistant.usage().outputTokens(),
            "stdout assistant is emitted at content_block_stop before message_delta");
        AssistantMessage persistedFirstTurn = engine.getMutableMessages().stream()
            .filter(AssistantMessage.class::isInstance)
            .map(AssistantMessage.class::cast)
            .filter(message -> message.message().content().stream()
                .anyMatch(block -> block instanceof TextBlock text
                    && Strings.CS.equals("partial reply", text.text())))
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected persisted first-turn assistant"));
        assertEquals(2, persistedFirstTurn.message().usage().outputTokens(),
            "message_delta must update the transcript/request-side assistant usage");

        boolean sawRecoveryMessage = engine.getMutableMessages().stream().anyMatch(
            m -> m instanceof UserMessage um
                && um.message().text() != null
                && Strings.CS.contains(um.message().text(), "Output token limit hit"));
        assertTrue(sawRecoveryMessage,
            "the last non-null stopReason (max_tokens) must drive the recovery-message branch");

        // The loop must have continued to a natural second turn after recovery.
        var last = messages.getLast();
        assertInstanceOf(SDKMessage.Result.class, last);
        assertEquals(SDKMessage.Result.SUCCESS, ((SDKMessage.Result) last).resultType());
    }

    @Test
    void resultUsageResetsForEachSubmittedUserQueryWhileSessionUsageAccumulates() {
        SessionCostState.get().reset();
        var events = endTurnEvents("OK");
        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingClient.StreamingEvent> createStream(
                        StreamingClient.StreamRequest request) {
                    return events.iterator();
                }

                @Override
                public String getModel() {
                    return "test-model";
                }
            })
            .systemPrompt("Be helpful")
            .model("test-model")
            .build());

        SDKMessage.Result first = assertInstanceOf(SDKMessage.Result.class,
            drain(engine.submitMessage("first", SubmitOptions.DEFAULT)).getLast());
        SDKMessage.Result second = assertInstanceOf(SDKMessage.Result.class,
            drain(engine.submitMessage("second", SubmitOptions.DEFAULT)).getLast());

        assertEquals(new Usage(10, 5, 0, 0), first.totalUsage());
        assertEquals(new Usage(10, 5, 0, 0), second.totalUsage(),
            "SDK result.usage is per submitted query, not process-cumulative");
        assertEquals(new Usage(20, 10, 0, 0), engine.getTotalUsage(),
            "session cost/modelUsage accounting must remain cumulative");
        assertEquals(new Usage(20, 10, 0, 0), second.modelUsage().get("test-model"),
            "SDK modelUsage remains session-cumulative even when result.usage resets");
    }

    @Test
    void rawStreamEventsSurroundPerBlockAssistantIn197Order() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode messageStart = mapper.readTree("""
            {"type":"message_start","message":{"id":"msg-1","type":"message",
             "role":"assistant","model":"test-model","content":[],"stop_reason":null,
             "stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":0}}}
            """);
        JsonNode blockStart = mapper.readTree("""
            {"type":"content_block_start","index":0,
             "content_block":{"type":"text","text":""}}
            """);
        JsonNode blockDelta = mapper.readTree("""
            {"type":"content_block_delta","index":0,
             "delta":{"type":"text_delta","text":"OK"}}
            """);
        JsonNode blockStop = mapper.readTree(
            "{\"type\":\"content_block_stop\",\"index\":0}");
        JsonNode messageDelta = mapper.readTree("""
            {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},
             "usage":{"output_tokens":1}}
            """);
        JsonNode messageStop = mapper.readTree("{\"type\":\"message_stop\"}");

        var events = List.<StreamingClient.StreamingEvent>of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-1", "test-model", List.of(), new Usage(1, 0, 0, 0), messageStart),
            new StreamingClient.StreamingEvent.ContentBlockStartEvent(
                0, "text", null, null, null, blockStart),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(
                0, "text_delta", "OK", blockDelta),
            new StreamingClient.StreamingEvent.ContentBlockStopEvent(0, blockStop),
            new StreamingClient.StreamingEvent.MessageDeltaEvent(
                "end_turn", new Usage(0, 1, 0, 0), messageDelta),
            new StreamingClient.StreamingEvent.MessageStopEvent(messageStop));

        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingEvent> createStream(StreamRequest request) {
                    return events.iterator();
                }

                @Override
                public String getModel() {
                    return "test-model";
                }
            })
            .systemPrompt("Be helpful")
            .model("test-model")
            .build());

        List<SDKMessage> all = drain(engine.submitMessage("hello", SubmitOptions.DEFAULT));
        List<SDKMessage> protocol = all.stream()
            .filter(message -> message instanceof SDKMessage.StreamRequestStart
                || message instanceof SDKMessage.RawStreamEvent
                || message instanceof SDKMessage.Assistant)
            .toList();

        assertEquals(8, protocol.size());
        assertInstanceOf(SDKMessage.StreamRequestStart.class, protocol.getFirst());
        assertRawType(protocol.get(1), "message_start");
        assertRawType(protocol.get(2), "content_block_start");
        assertRawType(protocol.get(3), "content_block_delta");
        SDKMessage.Assistant assistant = assertInstanceOf(
            SDKMessage.Assistant.class, protocol.get(4));
        assertEquals("OK", assertInstanceOf(
            TextBlock.class, assistant.message().message().content().getFirst()).text());
        assertRawType(protocol.get(5), "content_block_stop");
        assertRawType(protocol.get(6), "message_delta");
        assertRawType(protocol.get(7), "message_stop");

        int assistantIndex = indexOf(all, SDKMessage.Assistant.class);
        int finalizedIndex = indexOfFinalizedUsageSignal(all);
        int rawDeltaIndex = indexOfRawType(all, "message_delta");
        assertTrue(assistantIndex >= 0 && assistantIndex < finalizedIndex,
            "final-usage signal must follow the streamed assistant block");
        assertTrue(finalizedIndex < rawDeltaIndex,
            "final-usage signal is emitted after state write-back and before raw replay");

        AssistantMessage recorded = assertInstanceOf(AssistantMessage.class,
            engine.getMessages().stream()
                .filter(AssistantMessage.class::isInstance)
                .findFirst().orElseThrow());
        assertEquals("test-model", recorded.message().model());
        assertEquals("end_turn", recorded.message().stopReason());
        assertNull(recorded.message().stopSequence());
        assertEquals(new Usage(1, 1, 0, 0), recorded.message().usage());
    }

    private static void assertRawType(SDKMessage message, String expectedType) {
        SDKMessage.RawStreamEvent raw = assertInstanceOf(
            SDKMessage.RawStreamEvent.class, message);
        assertEquals(expectedType, raw.event().path("type").asText());
    }

    private static int indexOf(List<SDKMessage> messages, Class<? extends SDKMessage> type) {
        for (int i = 0; i < messages.size(); i++) {
            if (type.isInstance(messages.get(i))) return i;
        }
        return -1;
    }

    private static int indexOfFinalizedUsageSignal(List<SDKMessage> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof SDKMessage.StreamEvent event
                    && SDKMessage.ASSISTANT_USAGE_FINALIZED_EVENT.equals(event.eventType())) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfRawType(List<SDKMessage> messages, String type) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof SDKMessage.RawStreamEvent raw
                    && type.equals(raw.event().path("type").asText())) {
                return i;
            }
        }
        return -1;
    }
}
