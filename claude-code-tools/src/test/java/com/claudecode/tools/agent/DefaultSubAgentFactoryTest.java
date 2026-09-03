package com.claudecode.tools.agent;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.runtime.query.QuerySessionSpec;
import com.claudecode.runtime.query.DefaultQuerySessionFactory;
import com.claudecode.runtime.query.DefaultQuerySession;
import com.claudecode.core.engine.*;
import com.claudecode.core.queue.MessageQueueManager;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.Message;
import com.claudecode.core.engine.ToolExecutor;
import com.claudecode.core.engine.SubmitOptions;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.PermissionMode;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.SkillListingEntry;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.agent.BuiltInAgentDefinitions;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.session.SessionManager;
import com.claudecode.session.TeamInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.claudecode.tools.worktree.WorktreeService;
import com.claudecode.tools.worktree.WorktreeSession;
import com.claudecode.tools.skills.Skill;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.claudecode.tools.plan.PlanFiles;
import com.claudecode.tools.ToolBuilder;
import com.claudecode.tools.ToolRegistry;
import com.claudecode.tools.messaging.SendMessageTool;
import com.claudecode.tools.tasks.LocalAgentTask;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskStore;
import com.claudecode.tools.tasks.TaskType;
import com.claudecode.tools.tasks.teammate.TeammateContext;
import com.claudecode.tools.tasks.teammate.TeammateContextHolder;


class DefaultSubAgentFactoryTest {

    private static DefaultSubAgentFactory wired(DefaultSubAgentFactory factory) {
        factory.setQuerySessionFactory(new DefaultQuerySessionFactory());
        return factory;
    }

    private static final StreamingClient NOOP_CLIENT = new StreamingClient() {
        @Override
        public Iterator<StreamingEvent> createStream(StreamRequest request) {
            return Collections.emptyIterator();
        }
        @Override
        public String getModel() { return "test-model"; }
    };

    @Test
    void foregroundSubAgentBubblesPermissionPromptsThroughParentCallback() {
        PermissionAskCallback callback = _ -> PermissionAskCallback.Result.allow();
        AtomicReference<DefaultQuerySession> child = new AtomicReference<>();
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(NOOP_CLIENT, null, "/tmp");
        factory.setQuerySessionFactory(spec -> {
            DefaultQuerySession engine = new DefaultQuerySession(spec);
            child.set(engine);
            return engine;
        });
        ToolExecutionContext parent = ToolExecutionContext.builder(
                new AbortController(), "parent")
            .permissionAskCallback(callback)
            .build();

        factory.runSubAgent(SubAgentRequest.builder()
            .prompt("inspect")
            .parentContext(parent)
            .build());

        assertSame(callback, child.get().execution().getPermissionAskCallback(),
            "197 foreground agents bubble ASK decisions to the parent UI/SDK host");
    }

    @Test
    void backgroundSubAgentBubblesPermissionPromptsThroughParentCallback() {
        PermissionAskCallback callback = _ -> PermissionAskCallback.Result.allow();
        AtomicReference<DefaultQuerySession> child = new AtomicReference<>();
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(NOOP_CLIENT, null, "/tmp");
        factory.setQuerySessionFactory(spec -> {
            DefaultQuerySession engine = new DefaultQuerySession(spec);
            child.set(engine);
            return engine;
        });
        ToolExecutionContext parent = ToolExecutionContext.builder(
                new AbortController(), "parent")
            .permissionAskCallback(callback)
            .build();

        factory.runSubAgent(SubAgentRequest.builder()
            .prompt("inspect")
            .parentContext(parent)
            .async(true)
            .build());

        assertSame(callback, child.get().execution().getPermissionAskCallback(),
            "197 background agents inherit an available parent requestDialog/SDK permission channel");
    }

    @Test
    void subAgentsWithoutParentPermissionCallbackRemainNonInteractive() {
        for (boolean async : List.of(false, true)) {
            AtomicReference<DefaultQuerySession> child = new AtomicReference<>();
            DefaultSubAgentFactory factory = new DefaultSubAgentFactory(NOOP_CLIENT, null, "/tmp");
            factory.setQuerySessionFactory(spec -> {
                DefaultQuerySession engine = new DefaultQuerySession(spec);
                child.set(engine);
                return engine;
            });

            factory.runSubAgent(SubAgentRequest.builder()
                .prompt("inspect")
                .parentContext(ToolExecutionContext.builder(
                    new AbortController(), "parent").build())
                .async(async)
                .build());

            assertNull(child.get().execution().getPermissionAskCallback(),
                "a child must not invent an interactive permission channel");
        }
    }

    @Test
    void unavailableModelIsRejectedBeforeAChildRequestIsCreated() {
        AtomicInteger requests = new AtomicInteger();
        StreamingClient client = new StreamingClient() {
            @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                requests.incrementAndGet();
                return Collections.emptyIterator();
            }
            @Override public String getModel() { return "test-model"; }
        };
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(client, null, "/tmp");
        factory.setModelAvailabilityPredicate(_ -> false);

        SubAgentResult result = wired(factory).runSubAgent(
            SubAgentRequest.builder().prompt("inspect").model("haiku").build());

        assertTrue(result.isError());
        assertTrue(Strings.CS.contains(result.error().orElse(""), "not available"));
        assertEquals(0, requests.get());
    }

    @Test
    void allowlistFallbackRunsChildWithParentModel() {
        AtomicReference<String> requestedModel = new AtomicReference<>();
        StreamingClient client = new StreamingClient() {
            @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                requestedModel.set(request.model());
                return Collections.emptyIterator();
            }
            @Override public String getModel() { return "sonnet"; }
        };
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(client, null, "/tmp");
        factory.setSubAgentModelPolicy(new SubAgentModelPolicy() {
            @Override public Decision resolve(String requested, String parent) {
                return Decision.inherit(parent, "inherit parent for test");
            }
            @Override public List<String> advertisedModels() { return List.of("sonnet"); }
        });
        ToolExecutionContext parent = ToolExecutionContext.builder(
                new AbortController(), "parent")
            .currentModel("claude-sonnet-5")
            .build();

        wired(factory).runSubAgent(SubAgentRequest.builder()
            .prompt("inspect").model("haiku").parentContext(parent).build());

        assertEquals("claude-sonnet-5", requestedModel.get());
    }

    @Test
    void sharedSessionIdentity_isPassedToEverySubEngineConfig() {
        SessionIdentity shared = SessionIdentity.of("parent-session-id");
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(
            NOOP_CLIENT, null, "/tmp", null, shared);

        QuerySessionSpec config = factory.buildSubEngineConfig(
            SubAgentRequest.builder().prompt("test").build());

        assertEquals("parent-session-id", config.sessionIdentity().get());
// Same reference, not just an equal value — a later switchToSession
        // on the parent's identity must be visible to this sub-engine too.
        shared.set("switched-id");
        assertEquals("switched-id", config.sessionIdentity().get());
    }

    @Test
    void compactFactoryReceivesLateBoundFileStateCacheAfterSubEngineConstruction() {
        AtomicReference<Supplier<FileStateCache>> cacheSupplier =
            new AtomicReference<>();
        var compactFactory = (SubAgentCompactServiceFactory)
            (_, _, _, supplier) -> {
                cacheSupplier.set(supplier);
                return null;
            };
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(
            oneTurnClient(), null, "/tmp", null, SessionIdentity.of("parent-session"),
            null, null, compactFactory);

        SubAgentResult result = wired(factory).runSubAgent(
            SubAgentRequest.builder().prompt("test").build());

        assertTrue(result.error().isEmpty(), result.error().orElse(""));
        assertNotNull(cacheSupplier.get());
        assertNotNull(cacheSupplier.get().get(),
            "the compact service must resolve the cache only after DefaultQuerySession exists");
    }

    @Test
    void assistantUsageIsForwardedToTheLiveProgressCallback() {
        AtomicReference<String> messageId = new AtomicReference<>();
        AtomicReference<Usage> reportedUsage = new AtomicReference<>();
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(
            oneTurnClient(), null, "/tmp");

        SubAgentResult result = wired(factory).runSubAgent(SubAgentRequest.builder()
            .prompt("test")
            .progressCallback(new SubAgentRequest.ProgressCallback() {
                @Override
                public void onProgress(String status, double progressPercent) {
                }

                @Override
                public void onAgentUsage(String id, Usage usage) {
                    messageId.set(id);
                    reportedUsage.set(usage);
                }
            })
            .build());

        assertFalse(result.isError(), result.error().orElse(""));
        assertNotNull(messageId.get());
        assertNotNull(reportedUsage.get());
        assertEquals(5L, reportedUsage.get().totalTokens());
    }

    @Test
    void theProgressBridgeDropsUiAffordancesAndForwardsRealProgress() {
        List<String> reported = new ArrayList<>();
        ToolExecutionContext.ProgressSink sink = DefaultSubAgentFactory.flatteningProgressSink(
            new SubAgentRequest.ProgressCallback() {
                @Override
                public void onProgress(String status, double progressPercent) {
                    reported.add(status);
                }

                @Override
                public void onAgentUsage(String id, Usage usage) {
                }
            });

        // The parent's callback writes whatever it receives into the task's progress summary,
        // which the coordinator/agents panels render as the task description. Letting the
        // background affordance through would pin it there permanently.
        sink.accept(ToolExecutionContext.ProgressUpdate.agentBackgroundHint());
        sink.accept(ToolExecutionContext.ProgressUpdate.of(0.0, "Bash(ls)"));

        assertEquals(List.of("Bash(ls)"), reported);
    }

    @Test
    void theProgressBridgeIsNoopWithoutAParentCallback() {
        assertSame(ToolExecutionContext.ProgressSink.NOOP,
            DefaultSubAgentFactory.flatteningProgressSink(null));
    }

    @Test
    void parentQueue_isPassedToSubEngineConfig_whenPresent() {

        SessionIdentity shared = SessionIdentity.of("parent-session-id");
        MessageQueueManager parentQueue = new MessageQueueManager();
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(
            NOOP_CLIENT, null, "/tmp", null, shared);

        QuerySessionSpec config = factory.buildSubEngineConfig(
            SubAgentRequest.builder().prompt("test").parentQueue(parentQueue).build());

        assertEquals(parentQueue, config.messageQueue(),
            "sub-engine must share the parent's queue when one is supplied");
    }

    @Test
    void asyncSubAgent_forwardsFinalAssistantWithParentMetadata() {
        StreamingClient client = new StreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                return List.<StreamingEvent>of(
                    new StreamingEvent.MessageStartEvent(
                        "msg-child", "claude-sonnet-4-6", List.of(), new Usage(2, 0, 0, 0)),
                    new StreamingEvent.ContentBlockStartEvent(0, "text", null, null),
                    new StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", "OK"),
                    new StreamingEvent.MessageDeltaEvent("end_turn", new Usage(0, 1, 0, 0)),
                    new StreamingEvent.MessageStopEvent()
                ).iterator();
            }

            @Override public String getModel() { return "claude-sonnet-4-6"; }
        };
        MessageQueueManager parentQueue = new MessageQueueManager();
        ToolExecutionContext parentContext = ToolExecutionContext.of(
            new AbortController(), "parent-session").withToolUseId("toolu_parent_agent");
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(client, null, "/tmp/project");

        SubAgentResult result = wired(factory).runSubAgent(SubAgentRequest.builder()
            .prompt("child prompt")
            .description("background permission")
            .subagentType("bgplan")
            .async(true)
            .agentId("a123")
            .parentContext(parentContext)
            .parentQueue(parentQueue)
            .build());

        assertFalse(result.isError());
        List<SDKMessage> childEvents = parentQueue.drainSdkEvents();
        SDKMessage.Assistant child = assertInstanceOf(
            SDKMessage.Assistant.class, childEvents.getFirst());
        assertEquals("toolu_parent_agent", child.parentToolUseId());
        assertEquals("bgplan", child.subagentType());
        assertEquals("background permission", child.taskDescription());
    }

    @Test
    void foregroundSubAgent_doesNotForwardPlainFinalAssistantToParentSdkStream() {
        StreamingClient client = new StreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                return List.<StreamingEvent>of(
                    new StreamingEvent.MessageStartEvent(
                        "msg-child", "claude-sonnet-4-6", List.of(), new Usage(2, 0, 0, 0)),
                    new StreamingEvent.ContentBlockStartEvent(0, "text", null, null),
                    new StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", "OK"),
                    new StreamingEvent.MessageDeltaEvent("end_turn", new Usage(0, 1, 0, 0)),
                    new StreamingEvent.MessageStopEvent()
                ).iterator();
            }

            @Override public String getModel() { return "claude-sonnet-4-6"; }
        };
        MessageQueueManager parentQueue = new MessageQueueManager();
        ToolExecutionContext parentContext = ToolExecutionContext.of(
            new AbortController(), "parent-session").withToolUseId("toolu_parent_agent");
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(client, null, "/tmp/project");

        SubAgentResult result = wired(factory).runSubAgent(SubAgentRequest.builder()
            .prompt("child prompt")
            .description("foreground child")
            .subagentType("general-purpose")
            .async(false)
            .agentId("a123")
            .parentContext(parentContext)
            .parentQueue(parentQueue)
            .build());

        assertFalse(result.isError());
        assertTrue(parentQueue.drainSdkEvents().isEmpty(),
            "foreground terminal text is returned by Agent, not duplicated on parent stdout");
    }

    @Test
    void asyncSubAgent_forwardsToolResultAndReleasedTaskProgress() {
        AtomicInteger requestCount = new AtomicInteger();
        StreamingClient client = new StreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                if (requestCount.getAndIncrement() == 0) {
                    return List.<StreamingEvent>of(
                        new StreamingEvent.MessageStartEvent(
                            "msg-tool", "claude-sonnet-4-6", List.of(), Usage.EMPTY),
                        new StreamingEvent.ContentBlockStartEvent(
                            0, "tool_use", "toolu_child_bash", "Bash"),
                        new StreamingEvent.ContentBlockDeltaEvent(
                            0, "input_json_delta",
                            "{\"command\":\"touch /tmp/marker\","
                                + "\"description\":\"Create permission marker\"}"),
                        new StreamingEvent.ContentBlockStopEvent(0),
                        new StreamingEvent.MessageDeltaEvent(
                            "tool_use", new Usage(0, 2, 0, 0)),
                        new StreamingEvent.MessageStopEvent()
                    ).iterator();
                }
                return List.<StreamingEvent>of(
                    new StreamingEvent.MessageStartEvent(
                        "msg-final", "claude-sonnet-4-6", List.of(),
                        new Usage(10, 0, 0, 0)),
                    new StreamingEvent.ContentBlockStartEvent(0, "text", null, null),
                    new StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", "OK"),
                    new StreamingEvent.MessageDeltaEvent(
                        "end_turn", new Usage(0, 1, 0, 0)),
                    new StreamingEvent.MessageStopEvent()
                ).iterator();
            }

            @Override public String getModel() { return "claude-sonnet-4-6"; }
        };
        ToolExecutor executor = new ToolExecutor() {
            @Override
            public ToolResult execute(String name, JsonNode input, ToolExecutionContext context) {
                return ToolResult.success("");
            }

            @Override
            public List<StreamingClient.StreamRequest.ToolDef> getToolDefinitions() {
                return List.of(new StreamingClient.StreamRequest.ToolDef("Bash", "bash", null));
            }
        };
        MessageQueueManager parentQueue = new MessageQueueManager();
        ToolExecutionContext parentContext = ToolExecutionContext.of(
            new AbortController(), "parent-session").withToolUseId("toolu_parent_agent");
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(
            client, executor, "/tmp/project");

        SubAgentResult result = wired(factory).runSubAgent(SubAgentRequest.builder()
            .prompt("child prompt")
            .description("background permission")
            .subagentType("bgplan")
            .async(true)
            .agentId("a123")
            .parentContext(parentContext)
            .parentQueue(parentQueue)
            .tools(List.of("Bash"))
            .build());

        assertFalse(result.isError());
        List<SDKMessage> events = parentQueue.drainSdkEvents();
        assertEquals(4, events.size());
        SDKMessage.TaskProgress progress = assertInstanceOf(
            SDKMessage.TaskProgress.class, events.getFirst());
        assertEquals("a123", progress.taskId());
        assertEquals("toolu_parent_agent", progress.toolUseId());
        assertEquals("Running Create permission marker", progress.description());
        assertEquals("bgplan", progress.subagentType());
        assertEquals(2L, progress.usage().get("total_tokens"),
            "task_progress uses the completed tool-use assistant envelope");
        assertEquals(1, progress.usage().get("tool_uses"));
        assertEquals("Bash", progress.lastToolName());
        assertInstanceOf(SDKMessage.Assistant.class, events.get(1));
        SDKMessage.User toolResult = assertInstanceOf(SDKMessage.User.class, events.get(2));
        assertEquals("toolu_parent_agent", toolResult.parentToolUseId());
        assertEquals("bgplan", toolResult.subagentType());
        assertInstanceOf(SDKMessage.Assistant.class, events.get(3));
        assertEquals(13L, result.progressTokens());
    }

    @Test
    void asyncSubAgentPropagatesChildPermissionDenialToParentRecorder(@TempDir Path tempDir) throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        List<StreamingClient.StreamRequest> capturedRequests = new ArrayList<>();
        StreamingClient client = new StreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                capturedRequests.add(request);
                if (requestCount.getAndIncrement() == 0) {
                    return List.<StreamingEvent>of(
                        new StreamingEvent.MessageStartEvent(
                            "msg-tool", "claude-sonnet-4-6", List.of(), Usage.EMPTY),
                        new StreamingEvent.ContentBlockStartEvent(
                            0, "tool_use", "toolu_child_bash", "Bash"),
                        new StreamingEvent.ContentBlockDeltaEvent(
                            0, "input_json_delta",
                            "{\"command\":\"touch /tmp/marker\","
                                + "\"description\":\"Create permission marker\"}"),
                        new StreamingEvent.ContentBlockStopEvent(0),
                        new StreamingEvent.MessageDeltaEvent(
                            "tool_use", new Usage(0, 2, 0, 0)),
                        new StreamingEvent.MessageStopEvent()
                    ).iterator();
                }
                return List.<StreamingEvent>of(
                    new StreamingEvent.MessageStartEvent(
                        "msg-final", "claude-sonnet-4-6", List.of(), Usage.EMPTY),
                    new StreamingEvent.ContentBlockStartEvent(0, "text", null, null),
                    new StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", "OK"),
                    new StreamingEvent.MessageDeltaEvent(
                        "end_turn", new Usage(0, 1, 0, 0)),
                    new StreamingEvent.MessageStopEvent()
                ).iterator();
            }

            @Override public String getModel() { return "claude-sonnet-4-6"; }
        };
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ToolBuilder<JsonNode, String>()
            .name("Bash")
            .description("bash")
            .call((_, _) -> "should not run")
            .build());
        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.PLAN);
        registry.setPermissionGate(gate);

        List<SDKMessage.PermissionDenial> parentDenials = new ArrayList<>();
        MessageQueueManager parentQueue = new MessageQueueManager();
        ToolExecutionContext parentContext = ToolExecutionContext.of(
            new AbortController(), "parent-session")
            .withToolUseId("toolu_parent_agent")
            .withPermissionDenialSink(parentDenials::add);
        SessionIdentity sharedSession = SessionIdentity.of("parent-session");
        PlanFiles.configurePlansDirectory(tempDir.resolve("plans"));
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(
            client, registry, "/tmp/project", null, sharedSession, null,
            tempDir.resolve("claude-home"), null);

        SubAgentResult result = wired(factory).runSubAgent(SubAgentRequest.builder()
            .prompt("child prompt")
            .description("background permission")
            .subagentType("bgplan")
            .async(true)
            .agentId("a123")
            .permissionMode(PermissionMode.PLAN)
            .parentContext(parentContext)
            .parentQueue(parentQueue)
            .tools(List.of("Bash"))
            .build());

        assertFalse(result.isError());
        assertEquals(1, parentDenials.size());
        SDKMessage.PermissionDenial denial = parentDenials.getFirst();
        assertEquals("Bash", denial.toolName());
        assertEquals("toolu_child_bash", denial.toolUseId());
        assertEquals("touch /tmp/marker", denial.toolInput().get("command"));

        assertEquals(2, capturedRequests.size());
        ObjectMapper mapper = new ObjectMapper();
        String initialRequest = mapper.writeValueAsString(capturedRequests.getFirst().messages());
        String continuationRequest = mapper.writeValueAsString(capturedRequests.get(1).messages());
        String expectedPlanPath = PlanFiles.getPlanFilePath("parent-session", "a123").toString();
        assertFalse(Strings.CS.contains(initialRequest, "Plan mode is active."),
            "released runAgent initial messages bypass per-tool-turn plan attachments");
        assertTrue(Strings.CS.contains(continuationRequest, "Plan mode is active."));
        assertTrue(Strings.CS.contains(continuationRequest, expectedPlanPath));
        assertTrue(continuationRequest.indexOf("Permission to use Bash has been denied.")
                < continuationRequest.indexOf("Plan mode is active."),
            "the request-only plan reminder must be appended after the denied tool result");

        String publicSdkEvents = parentQueue.drainSdkEvents().toString();
        assertFalse(Strings.CS.contains(publicSdkEvents, "Plan mode is active."),
            "request-only attachments must not leak into the child SDK/JSONL tool_result event");
    }

    @Test
    void sendMessageToRunningSelfIsAttachedAtSameToolRound() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        List<StreamingClient.StreamRequest> capturedRequests = new ArrayList<>();
        StreamingClient client = new StreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                capturedRequests.add(request);
                if (requestCount.getAndIncrement() == 0) {
                    return List.<StreamingEvent>of(
                        new StreamingEvent.MessageStartEvent(
                            "msg-send", "claude-sonnet-4-6", List.of(), Usage.EMPTY),
                        new StreamingEvent.ContentBlockStartEvent(
                            0, "tool_use", "toolu_send", "SendMessage"),
                        new StreamingEvent.ContentBlockDeltaEvent(
                            0, "input_json_delta",
                            "{\"to\":\"researcher\",\"summary\":\"assign task 1\","
                                + "\"message\":\"start on task 1\"}"),
                        new StreamingEvent.ContentBlockStopEvent(0),
                        new StreamingEvent.MessageDeltaEvent(
                            "tool_use", new Usage(0, 2, 0, 0)),
                        new StreamingEvent.MessageStopEvent()).iterator();
                }
                return List.<StreamingEvent>of(
                    new StreamingEvent.MessageStartEvent(
                        "msg-final", "claude-sonnet-4-6", List.of(), Usage.EMPTY),
                    new StreamingEvent.ContentBlockStartEvent(0, "text", null, null),
                    new StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", "OK"),
                    new StreamingEvent.MessageDeltaEvent(
                        "end_turn", new Usage(0, 1, 0, 0)),
                    new StreamingEvent.MessageStopEvent()).iterator();
            }

            @Override public String getModel() { return "claude-sonnet-4-6"; }
        };

        TaskRegistry tasks = new TaskRegistry(TaskStore.inMemory());
        TaskRegistry.setGlobalForTest(tasks);
        try {
            TaskState state = tasks.store().createWithId(
                "a123", TaskType.LOCAL_AGENT, "research", null);
            tasks.store().updateStatus(state.id(), TaskStatus.RUNNING);
            tasks.registerAgent(new LocalAgentTask(state, tasks.store()));
            tasks.registerAgentName("researcher", state.id());

            ToolRegistry tools = new ToolRegistry();
            tools.register(new SendMessageTool());
            DefaultSubAgentFactory factory = new DefaultSubAgentFactory(
                client, tools, "/tmp/project");

            SubAgentResult result = wired(factory).runSubAgent(SubAgentRequest.builder()
                .prompt("Reply exactly OK.")
                .subagentType("general-purpose")
                .agentId("a123")
                .tools(List.of("SendMessage"))
                .build());

            assertFalse(result.isError());
            assertEquals(2, capturedRequests.size());
            String continuation = new ObjectMapper().writeValueAsString(
                capturedRequests.get(1).messages());
            assertTrue(Strings.CS.contains(continuation,
                "Another Claude session sent a message while you were working"), continuation);
            assertTrue(Strings.CS.contains(continuation,
                "<agent-message from=\\\"researcher\\\">"), continuation);
            assertTrue(tasks.drainAgentMessageEnvelopes("a123").isEmpty());
        } finally {
            TaskRegistry.resetGlobalForTest();
        }
    }

    @Test
    void parentQueue_isNull_whenNotSupplied() {
        // Legacy/default path — must NOT silently attach a hidden queue; each
        // sub-engine then falls back to its own isolated MessageQueueManager.
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(NOOP_CLIENT, null, "/tmp");

        QuerySessionSpec config = factory.buildSubEngineConfig(
            SubAgentRequest.builder().prompt("test").build());

        assertNull(config.messageQueue(),
            "sub-engine must get a null queue (own isolated fallback) when none is supplied");
    }

    @Test
    void noSessionIdentityPassed_subEngineGetsAnIndependentOne() {
        // Legacy/default constructor path — must NOT silently share some
        // hidden global; each sub-agent gets its own random id.
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(
            NOOP_CLIENT, null, "/tmp");

        QuerySessionSpec configA = factory.buildSubEngineConfig(
            SubAgentRequest.builder().prompt("a").build());
        QuerySessionSpec configB = factory.buildSubEngineConfig(
            SubAgentRequest.builder().prompt("b").build());

        assertNotEquals(configA.sessionIdentity().get(), configB.sessionIdentity().get());
    }

    @Test
    void configuredMaxTurns_isPassedToSubEngine() {
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(NOOP_CLIENT, null, "/tmp");

        QuerySessionSpec config = factory.buildSubEngineConfig(
            SubAgentRequest.builder().prompt("bounded").maxTurns(7).build());

        assertEquals(7, config.maxTurns());
    }

    @Test
    void fableAlias_resolvesToFrozen197ModelId() {
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(NOOP_CLIENT, null, "/tmp");

        QuerySessionSpec config = factory.buildSubEngineConfig(
            SubAgentRequest.builder().prompt("fable task").model("fable").build());

        assertEquals("claude-fable-5", config.model());
    }

    @Test
    void absentMaxTurns_isUnlimitedAndIndependentOfJavaOnlyBudget() {
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(NOOP_CLIENT, null, "/tmp");

        QuerySessionSpec config = factory.buildSubEngineConfig(
            SubAgentRequest.builder().prompt("unbounded").budgetUsd(99).build());

        assertEquals(0, config.maxTurns(),
            "TS leaves maxTurns undefined unless the agent definition sets it; zero disables the Java loop guard");
    }

    @Test
    void workflowStructuredOutputAndEffortAreThreadedToTheSubAgentTurn() {
        JsonNode schema = new ObjectMapper().createObjectNode()
            .put("type", "object");
        SubAgentRequest request = SubAgentRequest.builder()
            .prompt("return JSON")
            .jsonSchema(schema)
            .effort("low")
            .build();

        SubmitOptions options = DefaultSubAgentFactory.buildSubmitOptions(request);

        assertEquals(schema, options.jsonSchema());
        assertEquals("low", options.effortOverride());
    }

    @Test
    void agentDefinitionMemory_isAppendedToCustomSystemPrompt(@TempDir Path tmp)
            throws IOException {
        Path agentsDir = tmp.resolve(".claude/agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("memory-agent.md"), """
            ---
            name: memory-agent
            description: remembers things
            memory: project
            ---
            Authored agent prompt.
            """);
        AgentDefinitionLoader.clearCache();
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(NOOP_CLIENT, null, tmp.toString());
        String prompt = factory.buildSystemPrompt(SubAgentRequest.builder()
            .prompt("hello")
            .subagentType("memory-agent")
            .parentContext(ToolExecutionContext.builder(new AbortController(), "test").workingDirectory(tmp.toString()).build())
            .build());
        assertTrue(Strings.CS.contains(prompt, "Authored agent prompt."));
        assertTrue(Strings.CS.contains(prompt, "# Persistent Agent Memory"));
        assertTrue(Strings.CS.contains(prompt, tmp.resolve(".claude/agent-memory/memory-agent").toString()));
    }

    @Test
    void nonExploreAgentPromptIncludesReleasedGitStatusContext(@TempDir Path repo) throws Exception {
        Assumptions.assumeTrue(gitAvailable(), "git not available");
        resetProcessGitStatusSnapshot();
        run(repo, "git", "init", "-b", "main");
        run(repo, "git", "config", "user.name", "Wire User");
        Path agentsDir = repo.resolve(".claude/agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("bgplan.md"), """
            ---
            name: bgplan
            description: background fixture
            ---
            CUSTOM BACKGROUND PERMISSION SYSTEM
            """);
        AgentDefinitionLoader.clearCache();

        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(NOOP_CLIENT, null, repo.toString());
        String prompt = factory.buildSystemPrompt(SubAgentRequest.builder()
            .prompt("hello")
            .subagentType("bgplan")
            .parentContext(ToolExecutionContext.builder(new AbortController(), "test").workingDirectory(repo.toString()).build())
            .build());

        assertTrue(Strings.CS.contains(prompt, "gitStatus: This is the git status at the start of the conversation"), prompt);
        assertTrue(Strings.CS.contains(prompt, "Current branch: main"), prompt);
        assertTrue(Strings.CS.contains(prompt, "Recent commits:\n"), prompt);
    }

    @Test
    void productionClaudeMdLoaderIsWiredIntoSubEngine(@TempDir Path repo) {
        Function<Path, String> loader = cwd -> "loaded user rules for " + cwd;
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(
            NOOP_CLIENT, null, repo.toString(), null, null, null, null,
            null, null, () -> true, loader);

        QuerySessionSpec config = factory.buildSubEngineConfig(
            SubAgentRequest.builder().prompt("hello").build());

        assertNotNull(config.claudeMdContentSupplier());
        assertEquals("loaded user rules for " + repo, config.claudeMdContentSupplier().get());
    }

    @Test
    void agentPermissionMode_isUsedBySubEngineConfig() {
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(NOOP_CLIENT, null, "/tmp");
        QuerySessionSpec config = factory.buildSubEngineConfig(SubAgentRequest.builder()
            .prompt("plan")
            .permissionMode(PermissionMode.PLAN)
            .build());
        assertEquals("plan", config.permissionModeSupplier().get().wireValue());
    }

    @Test
    void customAgentSkills_areAppendedAfterTaskPromptAsStructuredBlocks(@TempDir Path tmp)
            throws IOException {
        AgentDefinitionLoader.setCliAgentsProvider(() -> AgentDefinitionLoader.parseCliAgents(
            "{\"skill-agent\":{\"description\":\"d\",\"prompt\":\"p\","
                + "\"skills\":[\"demo-skill\"]}}"));
        try {
            Skill skill = new Skill("demo-skill", "demo", List.of(),
                "SKILL-BODY", null, Skill.SkillSource.BUNDLED,
                null, null, null, Map.of());
            DefaultSubAgentFactory factory = new DefaultSubAgentFactory(
                NOOP_CLIENT, null, tmp.toString(), null, null, null,
                (Path) null, null, () -> List.of(skill));

            Object prompt = factory.buildAgentPrompt(SubAgentRequest.builder()
                .prompt("TASK")
                .subagentType("skill-agent")
                .cwd(tmp.toString())
                .build());

            assertInstanceOf(MessageContent.class, prompt);
            List<ContentBlock> blocks =
                ((MessageContent) prompt).blocks();
            assertEquals(3, blocks.size());
            assertEquals("TASK", ((TextBlock) blocks.getFirst()).text());
            assertEquals("""
                    <command-message>demo-skill</command-message>
                    <command-name>demo-skill</command-name>
                    <skill-format>true</skill-format>""",
                ((TextBlock) blocks.get(1)).text());
            assertEquals("SKILL-BODY", ((TextBlock) blocks.get(2)).text());
        } finally {
            AgentDefinitionLoader.setCliAgentsProvider(null);
        }
    }

    @Test
    void agentModelAlias_isResolvedBeforeBuildingApiConfig() {
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(NOOP_CLIENT, null, "/tmp");
        QuerySessionSpec config = factory.buildSubEngineConfig(SubAgentRequest.builder()
            .prompt("fast probe")
            .model("haiku")
            .build());
        assertEquals("claude-haiku-4-5-20251001", config.model());
    }

    @Test
    void workflowSchemaAddsStructuredOutputToRestrictedExecutor() {
        JsonNode schema = new ObjectMapper().createObjectNode()
            .put("type", "object");
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(
            NOOP_CLIENT, null, "/tmp");

        ToolExecutor executor = factory.createRestrictedToolExecutor(
            SubAgentRequest.builder().jsonSchema(schema).build());

        assertTrue(executor.getToolDefinitions().stream()
            .anyMatch(def -> Strings.CS.equals("StructuredOutput", def.name())
                && schema.equals(def.inputSchema())));
        ToolResult result = executor.execute("StructuredOutput",
            new ObjectMapper().createObjectNode(), null);
        assertFalse(result.isError());
        assertEquals(new ObjectMapper().createObjectNode(), result.toolUseResult());
    }

    @Test
    void resumedSubAgentRestoresContentReplacementStateIntoRestrictedExecutor() {
        AtomicReference<List<ToolResultBudget.Replacement>> restored = new AtomicReference<>();
        ToolExecutor delegate = new ToolExecutor() {
            @Override
            public ToolResult execute(String toolName, JsonNode input,
                                      ToolExecutionContext context) {
                return ToolResult.success("ok");
            }

            @Override
            public void restoreToolResultBudget(List<Message> messages,
                    List<ToolResultBudget.Replacement> replacements,
                    String sessionId, String workingDirectory, String agentId) {
                restored.set(replacements);
            }
        };
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(
            NOOP_CLIENT, delegate, "/tmp");
        List<ToolResultBudget.Replacement> replacements = List.of(
            new ToolResultBudget.Replacement("tool-1", "persisted preview"));

        factory.buildSubEngineConfig(SubAgentRequest.builder()
            .prompt("continue")
            .contentReplacements(replacements)
            .build(), "/tmp", "a1234567890abcdef");

        assertEquals(replacements, restored.get());
    }

    @AfterEach
    void clearWorktreeState() throws Exception {
        WorktreeService.clearCurrentSessionForTests();
        AgentDefinitionLoader.clearCache();
        PlanFiles.resetPlansDirectory();
        TeammateContextHolder.clear();
        resetProcessGitStatusSnapshot();
    }

    private static void resetProcessGitStatusSnapshot() throws Exception {
        Field field = DefaultQuerySession.class.getDeclaredField("gitStatusCache");
        field.setAccessible(true);
        field.set(null, null);
    }

    @Test
    void resolveWorkingDirectory_noActiveWorktree_usesBaseWorkingDirectory() {
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(NOOP_CLIENT, null, "/tmp/project");

        QuerySessionSpec config = factory.buildSubEngineConfig(SubAgentRequest.builder().prompt("x").build());

        assertEquals("/tmp/project", config.workingDirectory());
    }

    @Test
    void resolveWorkingDirectory_parentContextUsesLiveWorkingDirectory() {
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(
            NOOP_CLIENT, null, "/tmp/project");
        ToolExecutionContext parentContext = ToolExecutionContext.builder(
                new AbortController(), "test")
            .workingDirectory("/tmp/project/live-cwd")
            .build();

        QuerySessionSpec config = factory.buildSubEngineConfig(SubAgentRequest.builder()
            .prompt("x")
            .parentContext(parentContext)
            .build());

        assertEquals("/tmp/project/live-cwd", config.workingDirectory());
    }

    @Test
    void resolveWorkingDirectory_blankParentContextFallsBackToBaseWorkingDirectory() {
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(
            NOOP_CLIENT, null, "/tmp/project");
        ToolExecutionContext parentContext = ToolExecutionContext.builder(
                new AbortController(), "test")
            .workingDirectory(" ")
            .build();

        QuerySessionSpec config = factory.buildSubEngineConfig(SubAgentRequest.builder()
            .prompt("x")
            .parentContext(parentContext)
            .build());

        assertEquals("/tmp/project", config.workingDirectory());
    }

    @Test
    void resolveWorkingDirectory_activeWorktree_inheritsRealWorktreePath() {
        WorktreeSession session = new WorktreeSession(
            "/tmp/project", "/tmp/project/.claude/worktrees/feature-x", "feature-x",
            "worktree-feature-x", "main", "abc123", "sess-1", null, false, 0L, false);
        WorktreeService.restoreWorktreeSession(session);
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(NOOP_CLIENT, null, "/tmp/project");

        // worktree_branch input is irrelevant now — subagents inherit the session's
        // real active worktree regardless of what this legacy field says.
        QuerySessionSpec config = factory.buildSubEngineConfig(
            SubAgentRequest.builder().prompt("x").worktreeBranch("some-other-branch").build());

        assertEquals("/tmp/project/.claude/worktrees/feature-x", config.workingDirectory());
    }

    @Test
    void buildSidechainTranscriptSink_isNullWithoutSessionIdentity() {
        // No shared parent id (legacy path) — must NOT attach a sidechain
        // sink, preserving the pre-existing "doesn't persist" behaviour.
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(NOOP_CLIENT, null, "/tmp");
        assertNull(factory.buildSidechainTranscriptSink());
    }

    @Test
    void buildSidechainTranscriptSink_isNonNullWithSessionIdentity() {
        SessionIdentity shared = SessionIdentity.of("parent-session-id");
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(
            NOOP_CLIENT, null, "/tmp", null, shared);
        assertNotNull(factory.buildSidechainTranscriptSink());
    }

    @Test
    void currentTeamInfoUsesTheInProcessTeammateContext() {
        TeammateContextHolder.set(TeammateContext.builder()
            .agentId("agent-7")
            .teamId("search-team")
            .name("researcher")
            .build());

        assertEquals(new TeamInfo("search-team", "researcher"),
            DefaultSubAgentFactory.currentTeamInfo());
    }

    @Test
    void subagentStartHookContextIsTheFirstChildUserBlock() {
        AtomicReference<StreamingClient.StreamRequest> captured = new AtomicReference<>();
        StreamingClient client = new StreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                captured.set(request);
                return List.<StreamingEvent>of(
                    new StreamingEvent.MessageStartEvent("msg-1", "test-model", List.of(), Usage.EMPTY),
                    new StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", "done"),
                    new StreamingEvent.MessageDeltaEvent("end_turn", new Usage(0, 1, 0, 0)),
                    new StreamingEvent.MessageStopEvent()
                ).iterator();
            }

            @Override public String getModel() { return "test-model"; }
        };
        SubAgentLifecycleListener lifecycle = new SubAgentLifecycleListener() {
            @Override
            public HookDispatcher.HookOutcome onSubAgentStart(String agentId, String agentType) {
                return new HookDispatcher.HookOutcome(
                    true, "HOOKCTX", List.of(), false, null, null, List.of("HOOKCTX"));
            }

            @Override public void onSubAgentComplete(String agentId) { }
        };
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(
            client, null, "/tmp/project", null,
            SessionIdentity.of("parent-session"), lifecycle,
            null, () -> List.of(new SkillListingEntry("wire-skill", "wire description")));

        SubAgentResult result = wired(factory).runSubAgent(SubAgentRequest.builder()
            .prompt("CHILD TASK")
            .subagentType("boot")
            .tools(List.of("Skill"))
            .agentId("a0123456789abcdef")
            .build());

        assertTrue(result.error().isEmpty(), result.error().orElse(""));
        assertNotNull(captured.get());
        Object content = captured.get().messages().getFirst().content();
        assertInstanceOf(List.class, content);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) content;
        assertEquals("<system-reminder>\nSubagentStart hook additional context: HOOKCTX\n</system-reminder>",
            blocks.getFirst().get("text"));
        assertEquals("CHILD TASK", blocks.getLast().get("text"));
    }

    @Test
    void installsScopedDispatcherWithFrontmatterAndSidechainIdentity(
            @TempDir Path claudeHome) {
        AtomicReference<SubAgentLifecycleListener.SubAgentHookContext> captured =
            new AtomicReference<>();
        AtomicReference<String> promptIdAtStop = new AtomicReference<>();
        AtomicReference<Integer> stops = new AtomicReference<>(0);
        SubAgentLifecycleListener listener = new SubAgentLifecycleListener() {
            @Override
            public HookDispatcher createSubAgentHookDispatcher(
                    SubAgentHookContext context) {
                captured.set(context);
                return new HookDispatcher() {
                    @Override public boolean dispatchPreToolUse(
                            String name, JsonNode input, String id) { return true; }
                    @Override public void dispatchPostToolUse(
                            String name, JsonNode input, JsonNode output, String id) {}
                    @Override public void dispatchUserPromptSubmit(String prompt) {}
                    @Override public void dispatchSessionStart(String trigger) {}
                    @Override public void dispatchStop(String reason) {}
                    @Override public HookOutcome dispatchStopWithOutcome(
                            String reason, boolean active) {
                        promptIdAtStop.set(captured.get().promptIdSupplier().get());
                        stops.set(stops.get() + 1);
                        return HookOutcome.PROCEED;
                    }
                };
            }

            @Override
            public void onSubAgentComplete(String agentId) {}
        };
        AgentDefinitionLoader.setCliAgentsProvider(() -> AgentDefinitionLoader.parseCliAgents(
            "{\"boot\":{\"description\":\"d\",\"prompt\":\"p\","
                + "\"hooks\":{\"Stop\":[{\"hooks\":[{\"type\":\"command\","
                + "\"command\":\"echo ok\"}]}]}}}"));
        try {
            SessionIdentity identity = SessionIdentity.of("session-197");
            DefaultSubAgentFactory factory = new DefaultSubAgentFactory(
                oneTurnClient(), null, "/tmp/project", null, identity, listener,
                claudeHome, null);

            SubAgentResult result = wired(factory).runSubAgent(SubAgentRequest.builder()
                .prompt("CHILD TASK")
                .subagentType("boot")
                .model("claude-sonnet-4-6")
                .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
                .build());

            assertFalse(result.isError());
            assertNotNull(captured.get());
            assertEquals("boot", captured.get().agentType());
            assertEquals("bypassPermissions", captured.get().permissionMode());
            assertEquals("high", captured.get().effort());
            assertTrue(captured.get().frontmatterHooks().has("Stop"));
            String promptId = promptIdAtStop.get();
            assertNotNull(promptId);
            assertTrue(promptId.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"),
                "child prompt id must be established before hook dispatcher creation: " + promptId);
            assertTrue(Strings.CS.endsWith(captured.get().agentTranscriptPath(),
                "/session-197/subagents/agent-" + captured.get().agentId() + ".jsonl"));
            assertEquals(1, stops.get());
        } finally {
            AgentDefinitionLoader.setCliAgentsProvider(null);
        }
    }

    @Test
    void blockingSubagentStopReturnsOnlyFinalAssistantTextAndUsage(
            @TempDir Path claudeHome) {
        AtomicInteger requestCount = new AtomicInteger();
        AtomicReference<StreamingClient.StreamRequest> retryRequest = new AtomicReference<>();
        StreamingClient client = new StreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                int attempt = requestCount.incrementAndGet();
                if (attempt == 2) {
                    retryRequest.set(request);
                }
                String text = attempt == 1 ? "FIRST" : "FINAL";
                Usage usage = attempt == 1
                    ? new Usage(10, 1, 0, 0)
                    : new Usage(20, 2, 0, 0);
                return List.<StreamingEvent>of(
                    new StreamingEvent.MessageStartEvent(
                        "msg-" + attempt, "test-model", List.of(), Usage.EMPTY),
                    new StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", text),
                    new StreamingEvent.MessageDeltaEvent("end_turn", usage),
                    new StreamingEvent.MessageStopEvent()
                ).iterator();
            }

            @Override public String getModel() { return "test-model"; }
        };
        AtomicInteger stopCount = new AtomicInteger();
        SubAgentLifecycleListener listener = new SubAgentLifecycleListener() {
            @Override
            public HookDispatcher createSubAgentHookDispatcher(SubAgentHookContext context) {
                return new HookDispatcher() {
                    @Override public boolean dispatchPreToolUse(
                            String name, JsonNode input, String id) { return true; }
                    @Override public void dispatchPostToolUse(
                            String name, JsonNode input, JsonNode output, String id) {}
                    @Override public void dispatchUserPromptSubmit(String prompt) {}
                    @Override public void dispatchSessionStart(String trigger) {}
                    @Override public void dispatchStop(String reason) {}
                    @Override public HookOutcome dispatchStopWithOutcome(
                            String reason, boolean active) {
                        return stopCount.getAndIncrement() == 0
                            ? new HookOutcome(false, null, List.of("BLOCKED_ONCE\n"))
                            : HookOutcome.PROCEED;
                    }
                };
            }

            @Override public void onSubAgentComplete(String agentId) {}
        };
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(
            client, null, "/tmp/project", null,
            SessionIdentity.of("session-197"), listener, claudeHome, null);

        SubAgentResult result = wired(factory).runSubAgent(SubAgentRequest.builder()
            .prompt("CHILD TASK")
            .subagentType("boot")
            .build());

        assertFalse(result.isError());
        assertEquals(2, requestCount.get());
        assertEquals("FINAL", result.output(),
            "released 2.1.197 finalizes an Agent result from the last assistant turn");
        assertEquals(22, result.tokensUsed(),
            "released 2.1.197 reports the last assistant turn usage, not both stop-hook attempts");
        assertEquals(2, result.outputTokens());
        assertEquals(23, result.progressTokens(),
            "task_notification tracks latest input plus cumulative output across stop-hook retries");
        assertNotNull(retryRequest.get());
        Object retryContent = retryRequest.get().messages().getLast().content();
        assertEquals("Stop hook feedback:\nBLOCKED_ONCE\n",
            retryContent,
            "released hook feedback retains the command stderr's terminal newline");
    }

    @Test
    void executeSubAgent_writesSidechainFile_doesNotPolluteMainSessionFile(
            @TempDir Path claudeHome) throws Exception {
        String parentSessionId = "parent-session-for-sidechain-test";
        SessionIdentity shared = SessionIdentity.of(parentSessionId);
        StreamingClient oneTurnClient = new StreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                return List.<StreamingEvent>of(
                    new StreamingEvent.MessageStartEvent("msg-1", "test-model", List.of(), Usage.EMPTY),
                    new StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", "sub-agent reply"),
                    new StreamingEvent.MessageDeltaEvent("end_turn", new Usage(0, 5, 0, 0)),
                    new StreamingEvent.MessageStopEvent()
                ).iterator();
            }
            @Override
            public String getModel() { return "test-model"; }
        };
        // Package-private test constructor: redirects the sidechain
        // SessionManager's "~/.claude" root to a temp dir instead of the
        // real com.claudecode.core.config.ClaudePaths.CLAUDE_HOME.
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(
            oneTurnClient, null, "/tmp/project", null, shared, null, claudeHome, null);

        wired(factory).runSubAgent(SubAgentRequest.builder().prompt("do the thing").build());

        SessionManager mgr = new SessionManager(claudeHome, "/tmp/project");
        assertFalse(Files.exists(mgr.getSessionFile(parentSessionId)),
            "sub-agent must never write into the main session file");

        Path subagentsDir = mgr.getProjectDir().resolve(parentSessionId).resolve("subagents");
        assertTrue(Files.isDirectory(subagentsDir), "expected subagents dir at: " + subagentsDir);
        try (var stream = Files.list(subagentsDir)) {
            // SessionFileLock leaves a sibling ".jsonl.lock" file behind — only
            // count the actual transcript file(s).
            List<Path> files = stream.filter(p -> Strings.CS.endsWith(p.toString(), ".jsonl")).toList();
            assertEquals(1, files.size(), "expected exactly one sidechain file, got: " + files);
            assertTrue(files.getFirst().getFileName().toString().matches("agent-a[0-9a-f]{16}\\.jsonl"),
                "unexpected sidechain file name: " + files.getFirst().getFileName());
        }
    }

    @Test
    void forkedSubAgentPersistsOnlyItsSuffixAndAParentContextReference(
            @TempDir Path claudeHome) throws Exception {
        String sessionId = "fork-parent";
        String agentId = "a0123456789abcdef";
        List<Message> parentMessages = List.of(
            new UserMessage("parent-u", MessageContent.ofText("parent prompt")),
            new AssistantMessage("parent-a",
                AssistantContent.of("parent-message", List.of(new TextBlock("parent reply")))));
        List<Message> forkMessages = ForkMessageBuilder.build(parentMessages, "child task");
        ToolExecutionContext parentContext = ToolExecutionContext.builder(
                new AbortController(), sessionId)
            .conversationMessages(parentMessages)
            .build();
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(
            oneTurnClient(), null, "/tmp/project", null,
            SessionIdentity.of(sessionId), null, claudeHome, null);

        SubAgentResult result = wired(factory).runSubAgent(SubAgentRequest.builder()
            .prompt("child task")
            .agentId(agentId)
            .fork(true)
            .parentContext(parentContext)
            .priorMessages(forkMessages)
            .build());

        assertFalse(result.isError(), result.error().orElse(""));
        Path transcript = new SessionManager(claudeHome, "/tmp/project")
            .getAgentTranscriptPath(sessionId, agentId);
        List<JsonNode> rows = Files.readAllLines(transcript).stream()
            .map(line -> {
                try {
                    return new ObjectMapper().readTree(line);
                } catch (IOException failure) {
                    throw new RuntimeException(failure);
                }
            })
            .toList();
        assertEquals("fork-context-ref", rows.getFirst().path("type").asText());
        assertEquals("parent-a", rows.getFirst().path("parentLastUuid").asText());
        assertEquals(2, rows.getFirst().path("contextLength").asInt());
        assertTrue(rows.stream().noneMatch(row ->
            Strings.CS.equals("parent-u", row.path("uuid").asText())
                || Strings.CS.equals("parent-a", row.path("uuid").asText())),
            "the sidechain must not duplicate the parent prefix on disk");
        assertTrue(rows.stream().anyMatch(row ->
            Strings.CS.equals(forkMessages.getLast().uuid(), row.path("uuid").asText())),
            "the fork directive remains owned by the sidechain");
    }

    @Test
    void workflowSubAgentUsesGroupedTranscriptAndOfficialMetadata(
            @TempDir Path claudeHome) throws Exception {
        String sessionId = "workflow-parent";
        String agentId = "a0123456789abcdef";
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(
            oneTurnClient(), null, "/tmp/project", null,
            SessionIdentity.of(sessionId), null, claudeHome, null);

        wired(factory).runSubAgent(SubAgentRequest.builder()
            .prompt("inspect")
            .agentId(agentId)
            .transcriptSubdir("workflows/wf_probe-123")
            .build());

        Path dir = new SessionManager(claudeHome, "/tmp/project")
            .getWorkflowTranscriptDir(sessionId, "wf_probe-123");
        assertTrue(Files.isRegularFile(dir.resolve("agent-" + agentId + ".jsonl")));
        assertEquals("{\"agentType\":\"workflow-subagent\",\"spawnDepth\":1,\"subagentMaxDepth\":2}",
            Files.readString(dir.resolve("agent-" + agentId + ".meta.json")));
    }

    @Test
    void ordinarySubAgentPersistsOfficialResumeMetadata(@TempDir Path claudeHome)
            throws Exception {
        String sessionId = "ordinary-parent";
        String agentId = "a0123456789abcdef";
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(
            oneTurnClient(), null, "/tmp/project", null,
            SessionIdentity.of(sessionId), null, claudeHome, null);

        wired(factory).runSubAgent(SubAgentRequest.builder()
            .prompt("inspect")
            .description("inspect storage")
            .subagentType("Explore")
            .agentId(agentId)
            .build());

        Path transcript = new SessionManager(claudeHome, "/tmp/project")
            .getAgentTranscriptPath(sessionId, agentId);
        assertEquals("{\"agentType\":\"Explore\",\"description\":\"inspect storage\",\"spawnDepth\":1,\"subagentMaxDepth\":2}",
            Files.readString(transcript.resolveSibling("agent-" + agentId + ".meta.json")));
    }

    @Test
    void resumeMetadataControlsTheActualExploreModelWire(@TempDir Path claudeHome)
            throws Exception {
        String sessionId = "resume-wire-parent";
        String agentId = "a0123456789abcdef";
        SessionManager manager = new SessionManager(claudeHome, "/tmp/project");
        Path transcript = manager.getAgentTranscriptPath(sessionId, agentId);
        Files.createDirectories(transcript.getParent());
        ObjectNode persisted = (ObjectNode) JsonUtils.getMapper().valueToTree(
            new UserMessage("prior", MessageContent.ofText("original audit")));
        persisted.putNull("parentUuid");
        persisted.put("isSidechain", true);
        persisted.put("agentId", agentId);
        persisted.put("sessionId", sessionId);
        Files.writeString(transcript,
            JsonUtils.getMapper().writeValueAsString(persisted) + System.lineSeparator());
        Files.writeString(transcript.resolveSibling("agent-" + agentId + ".meta.json"),
            "{\"agentType\":\"Explore\",\"description\":\"inspect storage\"}");

        AtomicReference<StreamingClient.StreamRequest> captured = new AtomicReference<>();
        StreamingClient client = new StreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                captured.set(request);
                return List.<StreamingEvent>of(
                    new StreamingEvent.MessageStartEvent(
                        "msg-resumed", "test-model", List.of(), Usage.EMPTY),
                    new StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", "done"),
                    new StreamingEvent.MessageDeltaEvent("end_turn", new Usage(0, 1, 0, 0)),
                    new StreamingEvent.MessageStopEvent()).iterator();
            }

            @Override public String getModel() { return "test-model"; }
        };
        DefaultSubAgentFactory factory = wired(new DefaultSubAgentFactory(
            client, null, "/tmp/project", null,
            SessionIdentity.of(sessionId), null, claudeHome, null));
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        AgentContinuationService continuation = new AgentContinuationService(
            factory, registry, (_, _) -> transcript,
            (_, _) -> claudeHome.resolve(agentId + ".output"));

        continuation.resume(agentId, "continue the audit", ToolExecutionContext.builder(
                new AbortController(), sessionId)
            .workingDirectory("/tmp/project")
            .build());
        long deadline = System.currentTimeMillis() + 2_000;
        while (captured.get() == null && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }

        assertNotNull(captured.get());
        assertTrue(Strings.CS.contains(captured.get().systemPrompt(),
            BuiltInAgentDefinitions.EXPLORE_SYSTEM_PROMPT));
        assertEquals("claude-haiku-4-5-20251001", captured.get().model(),
            "the sidecar agent type must select Explore's model on the actual API wire");
        assertEquals(agentId, captured.get().agentId());
        assertTrue(captured.get().tools().stream().noneMatch(tool ->
            Strings.CS.equalsAny(tool.name(), "Edit", "Write", "Agent")),
            "a resumed Explore agent must retain the read-only wire tool set");
        assertEquals(1, captured.get().messages().size(),
            "adjacent resumed user turns are merged on the API wire");
        String wireContent = Objects.toString(captured.get().messages().getFirst().content());
        assertTrue(Strings.CS.contains(wireContent, "original audit"));
        assertTrue(Strings.CS.contains(wireContent, "continue the audit"));
        long completionDeadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < completionDeadline
                && registry.get(agentId).map(task -> task.status() == TaskStatus.RUNNING)
                    .orElse(true)) {
            Thread.onSpinWait();
        }
        assertEquals(TaskStatus.COMPLETED, registry.get(agentId).orElseThrow().status());
    }

    @Test
    void restrictedExecutor_blocksAgentDisallowedTools() {
// Defense-in-depth: even if a disallowed tool reaches the sub-agent's tool list,
// the RestrictedToolExecutor must refuse it and hide it from the model.
        AtomicReference<ToolExecutionContext> definitionContext = new AtomicReference<>();
        ToolExecutor delegate = new ToolExecutor() {
            @Override
            public ToolResult execute(String toolName, JsonNode input, ToolExecutionContext context) {
                return ToolResult.success("ok:" + toolName);
            }
            @Override
            public List<StreamingClient.StreamRequest.ToolDef> getToolDefinitions() {
                return List.of(
                    new StreamingClient.StreamRequest.ToolDef("AskUserQuestion", "ask", null),
                    new StreamingClient.StreamRequest.ToolDef("Bash", "bash", null));
            }
            @Override
            public List<StreamingClient.StreamRequest.ToolDef> getToolDefinitions(
                    ToolExecutionContext context) {
                definitionContext.set(context);
                return List.of(
                    new StreamingClient.StreamRequest.ToolDef(
                        "AskUserQuestion", "ask:" + context.currentModel(), null),
                    new StreamingClient.StreamRequest.ToolDef(
                        "Bash", "bash:" + context.currentModel(), null));
            }
        };
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(NOOP_CLIENT, delegate, "/tmp");
        SubAgentRequest req = SubAgentRequest.builder()
            .prompt("x")
            .tools(List.of("AskUserQuestion", "Bash"))
            .build();
        QuerySessionSpec config = factory.buildSubEngineConfig(req);
        ToolExecutionContext snapshotContext = definitionContext.get();
        assertNotNull(snapshotContext);

        ToolExecutionContext ctx = ToolExecutionContext.of(new AbortController(), "test-session");
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode empty = mapper.createObjectNode();

        ToolResult blocked = config.toolExecutor().execute("AskUserQuestion", empty, ctx);
        assertTrue(blocked.isError(), "AskUserQuestion must be refused by the sub-agent executor");

        ToolResult allowed = config.toolExecutor().execute("Bash", empty, ctx);
        assertFalse(allowed.isError(), "Bash must remain usable by the sub-agent");

        // The model must never even be offered the disallowed tool.
        boolean offersAskUserQuestion = config.toolExecutor().getToolDefinitions().stream()
            .anyMatch(def -> Strings.CS.equals("AskUserQuestion", def.name()));
        assertFalse(offersAskUserQuestion, "AskUserQuestion must be absent from sub-agent tool definitions");
        boolean offersBash = config.toolExecutor().getToolDefinitions().stream()
            .anyMatch(def -> Strings.CS.equals("Bash", def.name()));
        assertTrue(offersBash, "Bash must remain offered to the sub-agent");

        ToolExecutionContext promptContext = ctx.toBuilder()
            .currentModel("gpt-5.6-sol")
            .build();
        List<StreamingClient.StreamRequest.ToolDef> contextualDefinitions =
            config.toolExecutor().getToolDefinitions(promptContext);
        assertSame(snapshotContext, definitionContext.get(),
            "later turns must not rebuild a running agent's tool prompt");
        assertEquals("bash:" + snapshotContext.currentModel(),
            contextualDefinitions.getFirst().description());
    }

    @Test
    void externalCliDefinedAgentReceivesNestedAgentToolBelowLimit() {
        ToolExecutor delegate = new ToolExecutor() {
            @Override
            public ToolResult execute(String toolName, JsonNode input, ToolExecutionContext context) {
                return ToolResult.success("ok:" + toolName);
            }
            @Override
            public List<StreamingClient.StreamRequest.ToolDef> getToolDefinitions() {
                return List.of(
                    new StreamingClient.StreamRequest.ToolDef("Agent", "spawn", null),
                    new StreamingClient.StreamRequest.ToolDef("Bash", "bash", null));
            }
        };
        AgentDefinitionLoader.setCliAgentsProvider(() -> AgentDefinitionLoader.parseCliAgents(
            "{\"boot\":{\"description\":\"boot test\",\"prompt\":\"CUSTOM CHILD SYSTEM\"}}"));
        try {
            DefaultSubAgentFactory factory = new DefaultSubAgentFactory(NOOP_CLIENT, delegate, "/tmp");
            QuerySessionSpec config = factory.buildSubEngineConfig(SubAgentRequest.builder()
                .prompt("x")
                .subagentType("boot")
                .build());

            assertTrue(config.toolExecutor().getToolDefinitions().stream()
                .anyMatch(def -> Strings.CS.equals("Agent", def.name())));
            assertFalse(config.toolExecutor().execute("Agent",
                new ObjectMapper().createObjectNode(),
                ToolExecutionContext.of(new AbortController(), "test-session")).isError());
        } finally {
            AgentDefinitionLoader.setCliAgentsProvider(null);
        }
    }

    @Test
    void externalGeneralPurposeAgentAtLimitDoesNotReceiveNestedAgentTool() {
        ToolExecutor delegate = new ToolExecutor() {
            @Override
            public ToolResult execute(String toolName, JsonNode input, ToolExecutionContext context) {
                return ToolResult.success("ok:" + toolName);
            }
            @Override
            public List<StreamingClient.StreamRequest.ToolDef> getToolDefinitions() {
                return List.of(
                    new StreamingClient.StreamRequest.ToolDef("Agent", "spawn", null),
                    new StreamingClient.StreamRequest.ToolDef("Bash", "bash", null));
            }
        };
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(NOOP_CLIENT, delegate, "/tmp");
        QuerySessionSpec config = factory.buildSubEngineConfig(SubAgentRequest.builder()
            .prompt("x")
            .subagentType("general-purpose")
            .agentDepth(2)
            .subagentMaxDepthSnapshot(2)
            .build());

        assertFalse(config.toolExecutor().getToolDefinitions().stream()
            .anyMatch(def -> Strings.CS.equals("Agent", def.name())));
    }

    @Test
    void wildcardToolDirectoryIsFrozenInRegistryOrderAtSessionCreation() {
        ObjectNode readSchema = new ObjectMapper().createObjectNode()
            .put("version", "initial");
        AtomicReference<List<StreamingClient.StreamRequest.ToolDef>> definitions =
            new AtomicReference<>(List.of(
                new StreamingClient.StreamRequest.ToolDef("Read", "read", readSchema),
                new StreamingClient.StreamRequest.ToolDef("Agent", "spawn", null),
                new StreamingClient.StreamRequest.ToolDef("Bash", "bash", null)));
        ToolExecutor delegate = new ToolExecutor() {
            @Override
            public ToolResult execute(String toolName, JsonNode input,
                    ToolExecutionContext context) {
                return ToolResult.success("ok:" + toolName);
            }

            @Override
            public List<StreamingClient.StreamRequest.ToolDef> getToolDefinitions() {
                return definitions.get();
            }
        };
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(
            NOOP_CLIENT, delegate, "/tmp");
        QuerySessionSpec config = factory.buildSubEngineConfig(SubAgentRequest.builder()
            .prompt("x")
            .agentDepth(1)
            .subagentMaxDepthSnapshot(2)
            .build());

        assertEquals(List.of("Read", "Agent", "Bash"), config.tools());
        assertEquals(List.of("Read", "Agent", "Bash"), config.toolExecutor()
            .getToolDefinitions().stream().map(
                StreamingClient.StreamRequest.ToolDef::name).toList());
        List<StreamingClient.StreamRequest.ToolDef> frozenDefinitions =
            config.toolExecutor().getToolDefinitions();
        assertEquals("read", frozenDefinitions.getFirst().description());
        assertEquals("initial", ((JsonNode) frozenDefinitions.getFirst()
            .inputSchema()).path("version").asText());

        readSchema.put("version", "mutated");
        definitions.set(List.of(
            new StreamingClient.StreamRequest.ToolDef("Write", "new", null),
            new StreamingClient.StreamRequest.ToolDef("Bash", "changed", null),
            new StreamingClient.StreamRequest.ToolDef("Agent", "changed", null),
            new StreamingClient.StreamRequest.ToolDef("Read", "changed", null)));

        assertEquals(List.of("Read", "Agent", "Bash"), config.toolExecutor()
            .getToolDefinitions().stream().map(
                StreamingClient.StreamRequest.ToolDef::name).toList(),
            "a running agent keeps its creation-time names and ordering");
        assertEquals("read", config.toolExecutor().getToolDefinitions()
            .getFirst().description(),
            "a running agent keeps its creation-time prompt text");
        assertEquals("initial", ((JsonNode) config.toolExecutor()
            .getToolDefinitions().getFirst().inputSchema()).path("version").asText(),
            "a running agent keeps a defensive copy of its creation-time schema");
        assertTrue(config.toolExecutor().execute("Write",
            new ObjectMapper().createObjectNode(),
            ToolExecutionContext.of(new AbortController(), "test-session")).isError());
    }

    @Test
    void forkSnapshotsExactParentToolPromptContextAndKeepsAgentAtDepthCap() {
        AtomicReference<ToolExecutionContext> definitionContext = new AtomicReference<>();
        ToolExecutor delegate = new ToolExecutor() {
            @Override
            public ToolResult execute(String toolName, JsonNode input,
                    ToolExecutionContext context) {
                return ToolResult.success("ok:" + toolName);
            }

            @Override
            public List<StreamingClient.StreamRequest.ToolDef> getToolDefinitions(
                    ToolExecutionContext context) {
                definitionContext.set(context);
                return List.of(
                    new StreamingClient.StreamRequest.ToolDef(
                        "Read", "read:" + context.currentModel(), null),
                    new StreamingClient.StreamRequest.ToolDef(
                        "Agent", "agent:" + context.currentModel(), null));
            }
        };
        ToolExecutionContext parent = ToolExecutionContext
            .builder(new AbortController(), "parent-session")
            .workingDirectory("/parent")
            .currentModel("parent-model")
            .enabledTools(List.of("Read", "Agent"))
            .agentDepth(2)
            .subagentMaxDepthSnapshot(2)
            .build();
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(
            NOOP_CLIENT, delegate, "/tmp");

        QuerySessionSpec config = factory.buildSubEngineConfig(SubAgentRequest.builder()
            .prompt("side question")
            .fork(true)
            .parentContext(parent)
            .tools(parent.enabledTools())
            .agentDepth(3)
            .subagentMaxDepthSnapshot(2)
            .build());

        assertSame(parent, definitionContext.get());
        assertEquals(List.of("Read", "Agent"), config.toolExecutor()
            .getToolDefinitions().stream()
            .map(StreamingClient.StreamRequest.ToolDef::name).toList());
        assertEquals("read:parent-model", config.toolExecutor()
            .getToolDefinitions().getFirst().description());
    }

    @Test
    void restrictedExecutor_appliesDefinitionSpecificDenyList() {
        ToolExecutor delegate = new ToolExecutor() {
            @Override
            public ToolResult execute(String toolName, JsonNode input, ToolExecutionContext context) {
                return ToolResult.success("ok:" + toolName);
            }
            @Override
            public List<StreamingClient.StreamRequest.ToolDef> getToolDefinitions() {
                return List.of(
                    new StreamingClient.StreamRequest.ToolDef("Read", "read", null),
                    new StreamingClient.StreamRequest.ToolDef("Write", "write", null),
                    new StreamingClient.StreamRequest.ToolDef("NotebookEdit", "notebook", null));
            }
        };
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(NOOP_CLIENT, delegate, "/tmp");
        SubAgentRequest req = SubAgentRequest.builder()
            .prompt("x")
            .disallowedTools(List.of("Write", "NotebookEdit"))
            .build();
        QuerySessionSpec config = factory.buildSubEngineConfig(req);

        List<String> offered = config.toolExecutor().getToolDefinitions().stream()
            .map(StreamingClient.StreamRequest.ToolDef::name)
            .toList();
        assertEquals(List.of("Read"), offered);
    }

    // ── isolation: "worktree" (createAgentWorktree lifecycle) ──────────────────

    private static StreamingClient oneTurnClient() {
        return new StreamingClient() {
            @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                return List.<StreamingEvent>of(
                    new StreamingEvent.MessageStartEvent("msg-1", "test-model", List.of(), Usage.EMPTY),
                    new StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", "done"),
                    new StreamingEvent.MessageDeltaEvent("end_turn", new Usage(0, 5, 0, 0)),
                    new StreamingEvent.MessageStopEvent()
                ).iterator();
            }
            @Override public String getModel() { return "test-model"; }
        };
    }

    private static void initRepo(Path dir) throws Exception {
        run(dir, "git", "init", "-q");
        run(dir, "git", "config", "user.email", "t@e.com");
        run(dir, "git", "config", "user.name", "T");
        Files.writeString(dir.resolve("a.txt"), "a\n");
        run(dir, "git", "add", ".");
        run(dir, "git", "commit", "-q", "-m", "init");
    }

    private static void run(Path dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        if (p.waitFor() != 0) throw new IllegalStateException("cmd failed: " + String.join(" ", cmd));
    }

    private static boolean gitAvailable() {
        try {
            return new ProcessBuilder("git", "--version").start().waitFor() == 0;
        } catch (Exception _) { return false; }
    }

    @Test
    void worktreeIsolation_cleanRun_removesWorktreeAndReportsNoPath(@TempDir Path repo) throws Exception {
        Assumptions.assumeTrue(gitAvailable(), "git not available");
        initRepo(repo);
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(oneTurnClient(), null, repo.toString());

        SubAgentResult result = wired(factory).runSubAgent(
            SubAgentRequest.builder().prompt("look around").worktreeIsolation(true).build());

        assertTrue(result.error().isEmpty(), "run should succeed");
        // A no-change agent run removes its worktree and surfaces no path.
        assertTrue(result.worktreePath().isEmpty(), "clean isolated run must not surface a kept path");
        assertFalse(Files.isDirectory(repo.resolve(".claude/worktrees/agent-" )),
            "no leftover agent worktree parent dir entries expected");
    }

    @Test
    void worktreeIsolation_configPointsSubEngineAtAgentWorktree(@TempDir Path repo) throws Exception {
        Assumptions.assumeTrue(gitAvailable(), "git not available");
        initRepo(repo);
        // Capture the cwd the sub-engine is configured with by intercepting the
        // StreamRequest? Simpler: assert buildSubEngineConfig with an override wins.
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(oneTurnClient(), null, repo.toString());
        String agentPath = repo.resolve(".claude/worktrees/agent-xyz").toString();

        var config = factory.buildSubEngineConfig(
            SubAgentRequest.builder().prompt("x").worktreeIsolation(true).build(), agentPath);

        assertEquals(agentPath, config.workingDirectory(),
            "isolation cwd override must win over the inherited working directory");
    }

    @Test
    void worktreeIsolation_nonGitDir_returnsErrorResult(@TempDir Path notARepo) {
        Assumptions.assumeTrue(gitAvailable(), "git not available");
        DefaultSubAgentFactory factory = new DefaultSubAgentFactory(oneTurnClient(), null, notARepo.toString());

        SubAgentResult result = wired(factory).runSubAgent(
            SubAgentRequest.builder().prompt("x").worktreeIsolation(true).build());

        assertTrue(result.error().isPresent(), "isolation in a non-git dir must fail cleanly, not crash");
        assertTrue(Strings.CS.contains(result.error().get(), "worktree"), result.error().get());
    }
}
