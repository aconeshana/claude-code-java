package com.claudecode.cli;

import org.apache.commons.lang3.Strings;

import com.claudecode.commands.CommandResult;
import com.claudecode.runtime.query.DefaultQuerySession;
import com.claudecode.runtime.query.QuerySessionSpec;
import com.claudecode.core.engine.SdkEventSequencedIterator;
import com.claudecode.core.engine.SubmitOptions;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolExecutor;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.engine.SessionCostState;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.CompactMetadata;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.PreservedMessages;
import com.claudecode.core.message.PreservedSegment;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.queue.QueuedCommand;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.services.hooks.CallbackHook;
import com.claudecode.services.hooks.HookEngine;
import com.claudecode.services.hooks.HookEvent;
import com.claudecode.services.hooks.HookMatcher;
import com.claudecode.services.hooks.HooksSettings;
import com.claudecode.runtime.plugins.PluginCommandDefinition;
import com.claudecode.tools.skills.Skill;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class StdoutMessageWriterTest {

    @Test
    void initReportsNonessentialTrafficFlagsFromRuntimeEnvironment() {
        SubprocessEnvironment.clearRuntimeOverrides();
        try {
            ObjectNode enabled = StdoutMessageWriter.toJson(
                new SDKMessage.System(new SystemMessage(
                    "init", "system_init", "info", "internal")), META, false);
            boolean inheritedDisabled = System.getenv(
                "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC") != null;
            assertEquals(inheritedDisabled,
                enabled.path("analytics_disabled").asBoolean());
            assertEquals(inheritedDisabled,
                enabled.path("product_feedback_disabled").asBoolean());

            SubprocessEnvironment.updateRuntime(Map.of(
                "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1"));
            ObjectNode disabled = StdoutMessageWriter.toJson(
                new SDKMessage.System(new SystemMessage(
                    "init", "system_init", "info", "internal")), META, false);
            assertTrue(disabled.path("analytics_disabled").asBoolean());
            assertTrue(disabled.path("product_feedback_disabled").asBoolean());
        } finally {
            SubprocessEnvironment.clearRuntimeOverrides();
        }
    }

    @Test
    void notificationUsesReleasedSystemEnvelope() {
        ObjectNode node = StdoutMessageWriter.toJson(
            new SDKMessage.Notification(
                "stop-hook-error", "Stop hook error occurred · ctrl+o to see", "immediate"),
            META, false);

        assertEquals("system", node.path("type").asText());
        assertEquals("notification", node.path("subtype").asText());
        assertEquals("stop-hook-error", node.path("key").asText());
        assertEquals("immediate", node.path("priority").asText());
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final StdoutMessageWriter.SdkOutputMetadata META =
        new StdoutMessageWriter.SdkOutputMetadata(
            "session-1", "/tmp/project", "glm-5.2", "default",
            List.of("Task", "Bash"),
            List.of(new StdoutMessageWriter.McpServerStatus("alpha", "connected")),
            List.of("compact", "verify"), "ANTHROPIC_API_KEY", "2.1.197",
            "default", List.of("claude", "Explore"), List.of("verify"), List.of());

    @Test
    void verboseJsonLocalSystemCommandIncludesInitSyntheticAssistantAndResult() throws Exception {
        DefaultQuerySession engine = localCommandEngine();
        StringWriter buffer = new StringWriter();

        StdoutMessageWriter.writeCommandResult(
            engine, CommandResult.of("Total cost: $0.00"),
            new PrintWriter(buffer, true), "json", true, 7L);

        var events = MAPPER.readTree(buffer.toString());
        assertEquals(3, events.size());
        assertEquals("init", events.get(0).path("subtype").asText());
        assertEquals("assistant", events.get(1).path("type").asText());
        assertEquals("<synthetic>", events.get(1).path("message").path("model").asText());
        assertEquals("Total cost: $0.00",
            events.get(1).path("message").path("content").get(0).path("text").asText());
        assertEquals("success", events.get(2).path("subtype").asText());
        assertFalse(events.get(2).path("is_error").asBoolean());
        assertEquals("Total cost: $0.00", events.get(2).path("result").asText());
    }

    @Test
    void verboseJsonLocalTextCommandUsesSyntheticAssistantOutput() throws Exception {
        DefaultQuerySession engine = localCommandEngine();
        StringWriter buffer = new StringWriter();

        StdoutMessageWriter.writeCommandResult(
            engine, CommandResult.local("Total cost: $0.00"),
            new PrintWriter(buffer, true), "json", true, 7L);

        var events = MAPPER.readTree(buffer.toString());
        assertEquals(3, events.size());
        assertEquals("assistant", events.get(1).path("type").asText());
        assertEquals("<synthetic>", events.get(1).path("message").path("model").asText());
        assertEquals("Total cost: $0.00",
            events.get(1).path("message").path("content").get(0).path("text").asText());
        assertEquals("Total cost: $0.00", events.get(2).path("result").asText());
    }

    @Test
    void streamJsonPromptFailureIncludesTaggedUserStderrAndEmptySuccessResult() throws Exception {
        DefaultQuerySession engine = localCommandEngine();
        StringWriter buffer = new StringWriter();

        StdoutMessageWriter.writeCommandResult(
            engine, CommandResult.error("Error: network down"),
            new PrintWriter(buffer, true), "stream-json", true, 9L);

        List<ObjectNode> events = Arrays.stream(buffer.toString().strip().split("\\R"))
            .map(line -> {
                try {
                    return (ObjectNode) MAPPER.readTree(line);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            })
            .toList();
        assertEquals(3, events.size());
        assertEquals("init", events.getFirst().path("subtype").asText());
        assertEquals("user", events.get(1).path("type").asText());
        assertEquals("<local-command-stderr>Error: network down</local-command-stderr>",
            events.get(1).path("message").path("content").asText());
        assertTrue(events.get(1).path("isReplay").asBoolean());
        assertEquals("success", events.get(2).path("subtype").asText());
        assertFalse(events.get(2).path("is_error").asBoolean());
        assertEquals("", events.get(2).path("result").asText());
    }

    @Test
    void nonVerboseJsonLocalFailureStillWritesOnlyTheFinalSuccessResult() throws Exception {
        DefaultQuerySession engine = localCommandEngine();
        StringWriter buffer = new StringWriter();

        StdoutMessageWriter.writeCommandResult(
            engine, CommandResult.error("Error: network down"),
            new PrintWriter(buffer, true), "json", false, 9L);

        ObjectNode result = (ObjectNode) MAPPER.readTree(buffer.toString());
        assertEquals("result", result.path("type").asText());
        assertEquals("success", result.path("subtype").asText());
        assertFalse(result.path("is_error").asBoolean());
        assertEquals("", result.path("result").asText());
    }

    @Test
    void localCommandResultReportsGlobalApiDuration() throws Exception {
        SessionCostState.get().reset();
        try {
            SessionCostState.get().recordApiRequest(
                "glm-5.2", new Usage(1, 1, 0, 0), 4321L, 3210L);
            DefaultQuerySession engine = localCommandEngine();
            StringWriter buffer = new StringWriter();

            StdoutMessageWriter.writeCommandResult(
                engine, CommandResult.local("done"),
                new PrintWriter(buffer, true), "json", false, 9L);

            ObjectNode result = (ObjectNode) MAPPER.readTree(buffer.toString());
            assertEquals(4321L, result.path("duration_api_ms").asLong());
        } finally {
            SessionCostState.get().reset();
        }
    }

    private static DefaultQuerySession localCommandEngine() {
        return new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingEvent> createStream(StreamRequest request) {
                    throw new AssertionError("local command must not call the model");
                }

                @Override
                public String getModel() {
                    return "glm-5.2";
                }
            })
            .workingDirectory("/tmp/project")
            .model("glm-5.2")
            .build());
    }

    @Test
    void suppressesUserMessagesUnlessReplayWasRequested() {
        UserMessage user = new UserMessage(
            "user-1", MessageContent.ofText("hello"), false, false, null,
            MessageOrigin.USER, null, Instant.EPOCH, null, null, "session-1", null);

        assertNull(StdoutMessageWriter.toJson(new SDKMessage.User(user, true), META, false));
        ObjectNode replay = StdoutMessageWriter.toJson(new SDKMessage.User(user, true), META, true);
        assertNotNull(replay);
        assertEquals("user", replay.path("type").asText());
        assertEquals("hello", replay.path("message").path("content").asText());
        assertEquals("user-1", replay.path("uuid").asText());
        assertEquals("1970-01-01T00:00:00Z", replay.path("timestamp").asText());
        assertTrue(replay.path("isReplay").asBoolean());
    }

    @Test
    void serializesReleasedTaskLifecycleEvents() throws Exception {
        ObjectNode started = StdoutMessageWriter.toJson(new SDKMessage.TaskStarted(
            "b12345678", "toolu_1", "wire events", "local_agent", null,
            "Return exactly OK", "general-purpose"),
            META, false);
        assertEquals("system", started.path("type").asText());
        assertEquals("task_started", started.path("subtype").asText());
        assertEquals("b12345678", started.path("task_id").asText());
        assertEquals("toolu_1", started.path("tool_use_id").asText());
        assertEquals("wire events", started.path("description").asText());
        assertEquals("local_agent", started.path("task_type").asText());
        assertEquals("Return exactly OK", started.path("prompt").asText());
        assertEquals("general-purpose", started.path("subagent_type").asText());
        assertEquals("session-1", started.path("session_id").asText());
        assertTrue(started.hasNonNull("uuid"));

        ObjectNode updated = StdoutMessageWriter.toJson(new SDKMessage.TaskUpdated(
            "b12345678", Map.of("status", "completed", "end_time", 1234L)), META, false);
        assertEquals("task_updated", updated.path("subtype").asText());
        assertEquals("completed", updated.path("patch").path("status").asText());
        assertEquals(1234L, updated.path("patch").path("end_time").asLong());

        ObjectNode terminal = StdoutMessageWriter.toJson(new SDKMessage.TaskNotification(
            "s12345678", "toolu_2", "stopped", "", "socket events",
            Map.of("total_tokens", 2L, "tool_uses", 0, "duration_ms", 20L)), META, false);
        assertEquals("task_notification", terminal.path("subtype").asText());
        assertEquals("stopped", terminal.path("status").asText());
        assertEquals("", terminal.path("output_file").asText());
        assertEquals("socket events", terminal.path("summary").asText());
        assertEquals(2L, terminal.path("usage").path("total_tokens").asLong());

        UserMessage child = new UserMessage(
            "child-user", MessageContent.ofText("Search the repository"), false, false,
            null, MessageOrigin.USER, null, Instant.EPOCH, null, null);
        ObjectNode childEvent = StdoutMessageWriter.toJson(new SDKMessage.User(
            child, false, null, "Explore", "Explore repository"), META, false);
        assertEquals("Explore", childEvent.path("subagent_type").asText());
        assertEquals("Explore repository", childEvent.path("task_description").asText());

        AssistantMessage childAssistant = new AssistantMessage(
            "child-assistant",
            AssistantContent.of("msg_child", List.of(new TextBlock("OK")), new Usage(2, 1, 0, 0)));
        ObjectNode childAssistantEvent = StdoutMessageWriter.toJson(new SDKMessage.Assistant(
            childAssistant, new Usage(2, 1, 0, 0), "claude-sonnet-4-6",
            "toolu_parent", "bgplan", "background permission"), META, false);
        assertEquals("toolu_parent", childAssistantEvent.path("parent_tool_use_id").asText());
        assertEquals("bgplan", childAssistantEvent.path("subagent_type").asText());
        assertEquals("background permission", childAssistantEvent.path("task_description").asText());

        ObjectNode progress = StdoutMessageWriter.toJson(new SDKMessage.TaskProgress(
            "a123", "toolu_parent", "Running Create permission marker", "bgplan",
            Map.of("total_tokens", 2L, "tool_uses", 1, "duration_ms", 32L), "Bash"),
            META, false);
        assertEquals("task_progress", progress.path("subtype").asText());
        assertEquals("a123", progress.path("task_id").asText());
        assertEquals("toolu_parent", progress.path("tool_use_id").asText());
        assertEquals("Running Create permission marker", progress.path("description").asText());
        assertEquals("bgplan", progress.path("subagent_type").asText());
        assertEquals(2L, progress.path("usage").path("total_tokens").asLong());
        assertEquals("Bash", progress.path("last_tool_name").asText());

        String agentXml = """
            <task-notification>
            <task-id>a123</task-id>
            <tool-use-id>toolu_parent</tool-use-id>
            <output-file>/tmp/a123.output</output-file>
            <status>completed</status>
            <summary>Agent "background permission" finished</summary>
            <note>resume note</note>
            <result>OK</result>
            <usage><subagent_tokens>13</subagent_tokens><tool_uses>1</tool_uses>\
            <duration_ms>1622</duration_ms></usage>
            </task-notification>""";
        StringWriter notificationBuffer = new StringWriter();
        StdoutMessageWriter.writeTaskNotificationEvent(
            QueuedCommand.notification(agentXml), new PrintWriter(notificationBuffer, true), META);
        ObjectNode parsedNotification = (ObjectNode) MAPPER.readTree(notificationBuffer.toString());
        assertEquals("a123", parsedNotification.path("task_id").asText());
        assertEquals("toolu_parent", parsedNotification.path("tool_use_id").asText());
        assertEquals("/tmp/a123.output", parsedNotification.path("output_file").asText());
        assertEquals("Agent \"background permission\" finished",
            parsedNotification.path("summary").asText());
        assertEquals(13L, parsedNotification.path("usage").path("total_tokens").asLong());
        assertEquals(1, parsedNotification.path("usage").path("tool_uses").asInt());
        assertEquals(1622L, parsedNotification.path("usage").path("duration_ms").asLong());
    }

    @Test
    void sequencedQueryOutputKeepsLaterChildEventsBehindTheParentToolResult() throws Exception {
        class SequencedEngine extends DefaultQuerySession {
            SequencedEngine() {
                super(QuerySessionSpec.builder().llmClient(new StreamingClient() {
                    @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                        return List.<StreamingEvent>of().iterator();
                    }
                    @Override public String getModel() { return "glm-5.2"; }
                }).model("glm-5.2").build());
            }

            @Override public Iterator<SDKMessage> submitMessage(Object prompt, SubmitOptions options) {
                SDKMessage.System init = new SDKMessage.System(new SystemMessage(
                    "sys-1", "system_init", "info", "internal"));
                AssistantMessage parentToolUse = new AssistantMessage(
                    "parent-tool-use", AssistantContent.of("msg-parent-tool",
                        List.of(new ToolUseBlock("toolu-agent", "Agent",
                            MAPPER.createObjectNode())), Usage.EMPTY));
                UserMessage launchResult = new UserMessage(
                    "parent-launch-result",
                    MessageContent.ofBlocks(List.of(new ToolResultBlock(
                        "toolu-agent", List.of(new TextBlock("Agent launched successfully.")),
                        false, false, true))),
                    false, false, Map.of("status", "async_launched"), MessageOrigin.USER,
                    null, Instant.EPOCH, null, null, "session-1", "parent-tool-use");
                AssistantMessage parentFinal = new AssistantMessage(
                    "parent-final", AssistantContent.of(
                        "msg-parent-final", List.of(new TextBlock("OK")), Usage.EMPTY));

                getMessageQueue().enqueueSdkEvent(new SDKMessage.TaskStarted(
                    "agent-1", "toolu-agent", "background", "local_agent",
                    null, "do work", "general-purpose"));
                long parentResultCutoff = getMessageQueue().sdkEventSequence();
                AssistantMessage childFirst = new AssistantMessage(
                    "child-first", AssistantContent.of("msg-child-first",
                        List.of(new ToolUseBlock("toolu-bash", "Bash",
                            MAPPER.createObjectNode())), Usage.EMPTY));
                getMessageQueue().enqueueSdkEvent(new SDKMessage.Assistant(
                    childFirst, Usage.EMPTY, "glm-5.2", "toolu-agent",
                    "general-purpose", "background"));
                long parentFinalCutoff = getMessageQueue().sdkEventSequence();

                record Sequenced(SDKMessage message, long cutoff) {}
                List<Sequenced> output = List.of(
                    new Sequenced(init, 0L),
                    new Sequenced(new SDKMessage.Assistant(parentToolUse, Usage.EMPTY, "glm-5.2"), 0L),
                    new Sequenced(new SDKMessage.User(launchResult), parentResultCutoff),
                    new Sequenced(new SDKMessage.Assistant(parentFinal, Usage.EMPTY, "glm-5.2"),
                        parentFinalCutoff),
                    new Sequenced(new SDKMessage.Result(
                        SDKMessage.Result.SUCCESS, List.of(), Usage.EMPTY, "session-1"),
                        parentFinalCutoff));
                return new SdkEventSequencedIterator() {
                    private int index;
                    private long cutoff;
                    @Override public boolean hasNext() { return index < output.size(); }
                    @Override public SDKMessage next() {
                        Sequenced next = output.get(index++);
                        cutoff = next.cutoff();
                        return next.message();
                    }
                    @Override public long sdkEventSequenceForLastMessage() { return cutoff; }
                };
            }
        }

        StringWriter buffer = new StringWriter();
        StdoutMessageWriter.run(new SequencedEngine(), "launch",
            SubmitOptions.DEFAULT, new PrintWriter(buffer, true),
            "stream-json", true, false, META, false);

        List<ObjectNode> lines = Arrays.stream(buffer.toString().strip().split("\\R"))
            .map(line -> {
                try {
                    return (ObjectNode) MAPPER.readTree(line);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            })
            .toList();
        assertEquals(List.of(
                "init", "Agent", "task_started", "parent-launch-result",
                "Bash", "OK", "result"),
            lines.stream().map(line -> {
                if (Strings.CS.equals("result", line.path("type").asText())) return "result";
                if (Strings.CS.equals("task_started", line.path("subtype").asText())) {
                    return "task_started";
                }
                if (Strings.CS.equals("init", line.path("subtype").asText())) return "init";
                if (Strings.CS.equals("assistant", line.path("type").asText())) {
                    var content = line.path("message").path("content").get(0);
                    return Strings.CS.equals("tool_use", content.path("type").asText())
                        ? content.path("name").asText() : content.path("text").asText();
                }
                return line.path("uuid").asText();
            }).toList());
        assertTrue(lines.get(3).path("message").path("content").get(0)
            .path("content").isArray(),
            "Agent async_launched tool_result content stays a text-block array");
    }

    @Test
    void interruptionUserIsAlwaysEmittedAndIsNeverMarkedAsReplay() {
        UserMessage interruption = new UserMessage(
            "interrupt-user",
            MessageContent.ofBlocks(List.of(new TextBlock("[Request interrupted by user]"))),
            false, false, null, MessageOrigin.USER, null, Instant.EPOCH,
            null, null, "session-1", null);

        ObjectNode withoutReplay = StdoutMessageWriter.toJson(
            new SDKMessage.User(interruption), META, false);
        ObjectNode withReplay = StdoutMessageWriter.toJson(
            new SDKMessage.User(interruption), META, true);

        assertNotNull(withoutReplay,
            "the synthetic interruption is an output event, not an input replay");
        assertNotNull(withReplay);
        assertFalse(withoutReplay.has("isReplay"));
        assertFalse(withReplay.has("isReplay"));
    }

    @Test
    void activeInterruptControlResponseOvertakesDeferredInitialReplay() throws Exception {
        CountDownLatch queryBlocked = new CountDownLatch(1);
        CountDownLatch releaseQuery = new CountDownLatch(1);
        UserMessage initial = new UserMessage(
            "input-user", MessageContent.ofText("keep going"), false, false, null,
            MessageOrigin.USER, null, Instant.EPOCH, null, null, "session-1", null);
        UserMessage interruption = new UserMessage(
            "interrupt-user",
            MessageContent.ofBlocks(List.of(new TextBlock("[Request interrupted by user]"))),
            false, false, null, MessageOrigin.USER, null, Instant.EPOCH.plusMillis(1),
            null, null, "session-1", null);

        class BlockingEngine extends DefaultQuerySession {
            BlockingEngine() {
                super(QuerySessionSpec.builder().llmClient(new StreamingClient() {
                    @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                        return List.<StreamingEvent>of().iterator();
                    }
                    @Override public String getModel() { return "glm-5.2"; }
                }).model("glm-5.2").build());
            }

            @Override public Iterator<SDKMessage> submitMessage(
                    Object prompt, SubmitOptions options) {
                return new Iterator<>() {
                    private int index;

                    @Override public boolean hasNext() {
                        return index < 4;
                    }

                    @Override public SDKMessage next() {
                        return switch (index++) {
                            case 0 -> new SDKMessage.User(initial, true);
                            case 1 -> new SDKMessage.System(new SystemMessage(
                                "system-init", "system_init", "info", "internal"));
                            case 2 -> {
                                queryBlocked.countDown();
                                try {
                                    if (!releaseQuery.await(5, TimeUnit.SECONDS)) {
                                        throw new AssertionError("timed out waiting to release query");
                                    }
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new AssertionError(e);
                                }
                                yield new SDKMessage.User(interruption);
                            }
                            case 3 -> new SDKMessage.Result(
                                SDKMessage.Result.ERROR_DURING_EXECUTION,
                                List.of(initial, interruption), Usage.EMPTY, "session-1");
                            default -> throw new AssertionError("unexpected iterator index");
                        };
                    }
                };
            }
        }

        StringWriter buffer = new StringWriter();
        PrintWriter out = new PrintWriter(buffer, true);
        Thread writer = Thread.startVirtualThread(() -> StdoutMessageWriter.run(
            new BlockingEngine(), "keep going",
            SubmitOptions.DEFAULT,
            out, "stream-json", true, false,
            () -> new StdoutMessageWriter.SdkOutputState(
                META, SdkInboundControlHandler.ControlCatalog.empty()), true));

        assertTrue(queryBlocked.await(5, TimeUnit.SECONDS));
        ObjectNode control = MAPPER.createObjectNode();
        control.put("type", "control_response");
        ObjectNode response = control.putObject("response");
        response.put("subtype", "success");
        response.put("request_id", "interrupt-1");
        StdoutMessageWriter.writeControlMessage(control, out);
        releaseQuery.countDown();
        writer.join(5_000);
        assertFalse(writer.isAlive());

        List<ObjectNode> lines = Arrays.stream(buffer.toString().strip().split("\\R"))
            .map(line -> {
                try {
                    return (ObjectNode) MAPPER.readTree(line);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            })
            .toList();
        assertEquals(5, lines.size());
        assertEquals("init", lines.getFirst().path("subtype").asText());
        assertEquals("control_response", lines.get(1).path("type").asText());
        assertEquals("input-user", lines.get(2).path("uuid").asText());
        assertTrue(lines.get(2).path("isReplay").asBoolean());
        assertEquals("interrupt-user", lines.get(3).path("uuid").asText());
        assertFalse(lines.get(3).has("isReplay"));
        assertEquals("result", lines.get(4).path("type").asText());
    }

    @Test
    void toolResultUsersAreAlwaysEmittedAndKeepThe197StructuredPayload() {
        Map<String, Object> toolUseResult = Map.of(
            "type", "text",
            "file", Map.of(
                "filePath", "/tmp/project/probe.txt",
                "content", "probe\n",
                "numLines", 2,
                "startLine", 1,
                "totalLines", 2));
        UserMessage result = new UserMessage(
            "tool-result-user",
            MessageContent.ofBlocks(List.of(new ToolResultBlock(
                "toolu-read", List.of(new TextBlock("1\tprobe\n2\t")), false))),
            false, false, toolUseResult, MessageOrigin.USER, null, Instant.EPOCH,
            null, null, "session-1", "assistant-row");

        ObjectNode json = StdoutMessageWriter.toJson(
            new SDKMessage.User(result), META, false);

        assertNotNull(json);
        assertEquals("user", json.path("type").asText());
        assertFalse(json.has("isReplay"));
        assertEquals("toolu-read",
            json.path("message").path("content").get(0).path("tool_use_id").asText());
        assertTrue(json.path("message").path("content").get(0).path("content").isTextual());
        assertEquals("1\tprobe\n2\t",
            json.path("message").path("content").get(0).path("content").asText());
        assertEquals("text", json.path("tool_use_result").path("type").asText());
        assertEquals("/tmp/project/probe.txt",
            json.path("tool_use_result").path("file").path("filePath").asText());
    }

    @Test
    void mcpToolResultUsesContentBlockArrayInSdkStdout() {
        var mcpContent = MAPPER.createArrayNode();
        mcpContent.addObject().put("type", "text").put("text", "echo:WIRE197");
        UserMessage result = new UserMessage(
            "tool-result-user",
            MessageContent.ofBlocks(List.of(new ToolResultBlock(
                "toolu-mcp", List.of(new TextBlock("echo:WIRE197")), false))),
            false, false, mcpContent, MessageOrigin.USER, null, Instant.EPOCH,
            null, null, "session-1", "assistant-row");

        ObjectNode json = StdoutMessageWriter.toJson(
            new SDKMessage.User(result), META, false);

        var content = json.path("message").path("content").get(0).path("content");
        assertTrue(content.isArray());
        assertEquals("text", content.get(0).path("type").asText());
        assertEquals("echo:WIRE197", content.get(0).path("text").asText());
        assertEquals(mcpContent, json.path("tool_use_result"));
    }

    @Test
    void structuredJsonArrayToolResultUsesScalarTextInSdkStdout() {
        var resources = MAPPER.createArrayNode();
        resources.addObject().put("name", "wire-list").put("uri", "wire://resource/list");
        UserMessage result = new UserMessage(
            "tool-result-user",
            MessageContent.ofBlocks(List.of(new ToolResultBlock(
                "toolu-list", List.of(new TextBlock(resources.toString())), false))),
            false, false, resources, MessageOrigin.USER, null, Instant.EPOCH,
            null, null, "session-1", "assistant-row");

        ObjectNode json = StdoutMessageWriter.toJson(
            new SDKMessage.User(result), META, false);

        var content = json.path("message").path("content").get(0).path("content");
        assertTrue(content.isTextual());
        assertEquals(resources.toString(), content.asText());
        assertEquals(resources, json.path("tool_use_result"));
    }

    @Test
    void streamJsonEmitsCommandsChangedAfterToolResultAndRefreshesNextInit() throws Exception {
        StdoutMessageWriter.SdkOutputMetadata initialMetadata = metadataWithSkills(
            List.of("verify", "clear"), List.of("verify"));
        StdoutMessageWriter.SdkOutputMetadata changedMetadata = metadataWithSkills(
            List.of("verify", "nested-probe", "clear"), List.of("verify", "nested-probe"));
        SdkInboundControlHandler.ControlCatalog initialCatalog = new SdkInboundControlHandler.ControlCatalog(
            List.of(new SdkInboundControlHandler.CommandInfo(
                "verify", "Verify changes", "", List.of())), List.of(), List.of("default"));
        SdkInboundControlHandler.ControlCatalog changedCatalog = new SdkInboundControlHandler.ControlCatalog(
            List.of(
                new SdkInboundControlHandler.CommandInfo(
                    "verify", "Verify changes", "", List.of()),
                new SdkInboundControlHandler.CommandInfo(
                    "nested-probe",
                    "NESTED_DYNAMIC_SKILL_DESCRIPTION (from packages/app/.claude/skills — applies when working on files under packages/app/) (project)",
                    "", List.of())),
            List.of(), List.of("default"));
        AtomicReference<StdoutMessageWriter.SdkOutputState> state = new AtomicReference<>(
            new StdoutMessageWriter.SdkOutputState(initialMetadata, initialCatalog));
        AtomicInteger submissions = new AtomicInteger();

        class FixedEngine extends DefaultQuerySession {
            FixedEngine() {
                super(QuerySessionSpec.builder().llmClient(new StreamingClient() {
                    @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                        return List.<StreamingEvent>of().iterator();
                    }
                    @Override public String getModel() { return "glm-5.2"; }
                }).model("glm-5.2").build());
            }

            @Override public Iterator<SDKMessage> submitMessage(Object prompt,
                                                                 SubmitOptions options) {
                int turn = submissions.incrementAndGet();
                if (turn == 2) {
                    return List.<SDKMessage>of(
                        new SDKMessage.System(new SystemMessage(
                            "sys-2", "system_init", "info", "internal")),
                        new SDKMessage.Result(SDKMessage.Result.SUCCESS, List.of(), Usage.EMPTY, "session-1"))
                        .iterator();
                }
                UserMessage result = new UserMessage(
                    "tool-result-user",
                    MessageContent.ofBlocks(List.of(new ToolResultBlock(
                        "toolu-read", List.of(new TextBlock("1\tprobe\n2\t")), false))),
                    false, false, Map.of("type", "text"), MessageOrigin.USER,
                    null, Instant.EPOCH, null, null, "session-1", "assistant-row");
                List<SDKMessage> messages = List.of(
                    new SDKMessage.System(new SystemMessage(
                        "sys-1", "system_init", "info", "internal")),
                    new SDKMessage.User(result),
                    new SDKMessage.Result(SDKMessage.Result.SUCCESS, List.of(), Usage.EMPTY, "session-1"));
                Iterator<SDKMessage> delegate = messages.iterator();
                return new Iterator<>() {
                    @Override public boolean hasNext() { return delegate.hasNext(); }
                    @Override public SDKMessage next() {
                        SDKMessage next = delegate.next();
                        if (next instanceof SDKMessage.Result) {
                            state.set(new StdoutMessageWriter.SdkOutputState(
                                changedMetadata, changedCatalog));
                        }
                        return next;
                    }
                };
            }
        }

        StringWriter buffer = new StringWriter();
        FixedEngine engine = new FixedEngine();
        PrintWriter out = new PrintWriter(buffer, true);
        StdoutMessageWriter.run(engine, "first", SubmitOptions.DEFAULT,
            out, "stream-json", true, false, state::get, false);
        StdoutMessageWriter.run(engine, "second", SubmitOptions.DEFAULT,
            out, "stream-json", true, false, state::get, false);

        List<ObjectNode> lines = Arrays.stream(buffer.toString().strip().split("\\R"))
            .map(line -> {
                try {
                    return (ObjectNode) MAPPER.readTree(line);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            })
            .toList();
        int userIndex = firstIndex(lines, "type", "user");
        assertEquals("commands_changed", lines.get(userIndex + 1).path("subtype").asText());
        assertEquals("nested-probe",
            lines.get(userIndex + 1).path("commands").get(1).path("name").asText());
        assertFalse(lines.get(userIndex + 1).path("commands").get(0).has("aliases"));

        List<ObjectNode> inits = lines.stream()
            .filter(line -> Strings.CS.equals("init", line.path("subtype").asText()))
            .toList();
        assertEquals(2, inits.size());
        assertEquals("nested-probe", inits.get(1).path("skills").get(1).asText());
        assertEquals("nested-probe", inits.get(1).path("slash_commands").get(1).asText());
    }

    private static StdoutMessageWriter.SdkOutputMetadata metadataWithSkills(
            List<String> slashCommands, List<String> skills) {
        return new StdoutMessageWriter.SdkOutputMetadata(
            "session-1", "/tmp/project", "glm-5.2", "default",
            List.of("Task", "Read"), List.of(), slashCommands,
            "ANTHROPIC_API_KEY", "2.1.197", "default",
            List.of("claude"), skills, List.of());
    }

    private static int firstIndex(List<ObjectNode> lines, String field, String value) {
        for (int i = 0; i < lines.size(); i++) {
            if (value.equals(lines.get(i).path(field).asText())) return i;
        }
        return -1;
    }

    @Test
    void streamReplayEmitsInitBeforeTheSourceIdentifiedUserAcknowledgement() throws Exception {
        List<StreamingClient.StreamingEvent> events = List.of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-1", "glm-5.2", List.of(), new Usage(1, 0, 0, 0)),
            new StreamingClient.StreamingEvent.ContentBlockStartEvent(0, "text", null, null),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", "OK"),
            new StreamingClient.StreamingEvent.ContentBlockStopEvent(0),
            new StreamingClient.StreamingEvent.MessageDeltaEvent("end_turn", new Usage(0, 1, 0, 0)),
            new StreamingClient.StreamingEvent.MessageStopEvent());
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingEvent> createStream(StreamRequest request) {
                    return events.iterator();
                }

                @Override
                public String getModel() {
                    return "glm-5.2";
                }
            })
            .workingDirectory("/tmp/project")
            .model("glm-5.2")
            .build());
        StringWriter buffer = new StringWriter();

        StdoutMessageWriter.run(engine, "hello",
            SubmitOptions.DEFAULT.withPromptIdentity(
                "source-user-uuid", Instant.EPOCH),
            new PrintWriter(buffer, true), "stream-json", true, false,
            META, true);

        List<ObjectNode> lines = Arrays.stream(buffer.toString().strip().split("\\R"))
            .map(line -> {
                try {
                    return (ObjectNode) MAPPER.readTree(line);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            })
            .toList();
        assertEquals("system", lines.getFirst().path("type").asText());
        assertEquals("init", lines.getFirst().path("subtype").asText());
        assertEquals("user", lines.get(1).path("type").asText());
        assertEquals("source-user-uuid", lines.get(1).path("uuid").asText());
        assertEquals("1970-01-01T00:00:00Z", lines.get(1).path("timestamp").asText());
        assertTrue(lines.get(1).path("isReplay").asBoolean());
        assertTrue(lines.get(1).path("message").path("content").isTextual());
        assertEquals("hello", lines.get(1).path("message").path("content").asText());
        assertEquals("assistant", lines.get(2).path("type").asText());
    }

    @Test
    void sdkUserPromptSubmitHookCallbackIsWrittenBeforeSystemInit() throws Exception {
        List<StreamingClient.StreamingEvent> events = List.of(
            new StreamingClient.StreamingEvent.MessageStartEvent(
                "msg-1", "glm-5.2", List.of(), new Usage(1, 0, 0, 0)),
            new StreamingClient.StreamingEvent.ContentBlockStartEvent(0, "text", null, null),
            new StreamingClient.StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", "OK"),
            new StreamingClient.StreamingEvent.ContentBlockStopEvent(0),
            new StreamingClient.StreamingEvent.MessageDeltaEvent("end_turn", new Usage(0, 1, 0, 0)),
            new StreamingClient.StreamingEvent.MessageStopEvent());
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingEvent> createStream(StreamRequest request) {
                    return events.iterator();
                }

                @Override
                public String getModel() {
                    return "glm-5.2";
                }
            })
            .workingDirectory("/tmp/project")
            .model("glm-5.2")
            .build());
        StringWriter buffer = new StringWriter();
        PrintWriter out = new PrintWriter(buffer, true);
        CallbackHook callback = new CallbackHook(
            "wire-user-prompt-hook",
            (_, _) -> {
                ObjectNode control = MAPPER.createObjectNode();
                control.put("type", "control_request");
                control.putObject("request").put("subtype", "hook_callback");
                StdoutMessageWriter.writeControlMessage(control, out);
                return MAPPER.createObjectNode()
                    .set("hookSpecificOutput", MAPPER.createObjectNode()
                        .put("hookEventName", "UserPromptSubmit"));
            },
            Optional.empty());
        HookEngine hookEngine = new HookEngine(HooksSettings.EMPTY, "/tmp/project");
        hookEngine.setSdkHooks(Map.of(HookEvent.USER_PROMPT_SUBMIT,
            List.of(new HookMatcher(Optional.empty(), List.of(callback)))));
        engine.setHookDispatcher(hookEngine);

        StdoutMessageWriter.run(engine, "hello", SubmitOptions.DEFAULT,
            out, "stream-json", true, false, META, false);

        List<ObjectNode> lines = Arrays.stream(buffer.toString().strip().split("\\R"))
            .map(line -> {
                try {
                    return (ObjectNode) MAPPER.readTree(line);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            })
            .toList();
        assertEquals("control_request", lines.getFirst().path("type").asText());
        assertEquals("hook_callback", lines.getFirst().path("request").path("subtype").asText());
        assertEquals("system", lines.get(1).path("type").asText());
        assertEquals("init", lines.get(1).path("subtype").asText());
    }

    @Test
    void mapsInternalSystemInitToClaudeCodeSdkInitShape() {
        ObjectNode json = StdoutMessageWriter.toJson(
            new SDKMessage.System(new SystemMessage("sys-1", "system_init", "info", "internal")),
            META, false);

        assertEquals("system", json.path("type").asText());
        assertEquals("init", json.path("subtype").asText());
        assertEquals("/tmp/project", json.path("cwd").asText());
        assertEquals("2.1.197", json.path("claude_code_version").asText());
        assertEquals("Task", json.path("tools").get(0).asText());
        assertEquals("Bash", json.path("tools").get(1).asText());
        assertEquals("alpha", json.path("mcp_servers").get(0).path("name").asText());
        assertEquals("connected", json.path("mcp_servers").get(0).path("status").asText());
        assertFalse(json.has("level"));
        assertFalse(json.has("content"));
    }

    @Test
    void initToolNamesComeFromTheDefinitionsActuallyExposedToTheModel() {
        ToolExecutor executor = new ToolExecutor() {
            @Override
            public ToolResult execute(String toolName, JsonNode input,
                                      ToolExecutionContext context) {
                return ToolResult.success("unused");
            }

            @Override
            public List<StreamingClient.StreamRequest.ToolDef> getToolDefinitions() {
                return List.of(
                    new StreamingClient.StreamRequest.ToolDef("Agent", "agent", Map.of()),
                    new StreamingClient.StreamRequest.ToolDef("Read", "read", Map.of()));
            }
        };
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingEvent> createStream(StreamRequest request) {
                    return List.<StreamingEvent>of().iterator();
                }

                @Override
                public String getModel() {
                    return "glm-5.2";
                }
            })
            .tools(List.of("TeamCreate", "Agent", "Read"))
            .toolExecutor(executor)
            .workingDirectory("/tmp/project")
            .model("glm-5.2")
            .build());

        var metadata = StdoutMessageWriter.SdkOutputMetadata.fromEngine(engine);

        assertEquals(List.of("Task", "Read"), metadata.tools());
    }

    @Test
    void sdkSkillCatalogUsesUserInvocable197SubsetIncludingDisabledModelSkills() {
        List<Skill> loaded = List.of(
            skill("deep-research", Skill.SkillSource.BUNDLED),
            skill("update-config", Skill.SkillSource.BUNDLED),
            skill("keybindings-help", Skill.SkillSource.BUNDLED),
            skill("verify", Skill.SkillSource.BUNDLED),
            skill("code-review", Skill.SkillSource.BUNDLED),
            skill("simplify", Skill.SkillSource.BUNDLED),
            skill("fewer-permission-prompts", Skill.SkillSource.BUNDLED),
            skill("loop", Skill.SkillSource.BUNDLED),
            skill("claude-api", Skill.SkillSource.BUNDLED),
            skill("run", Skill.SkillSource.BUNDLED),
            skill("init", Skill.SkillSource.BUILTIN),
            skill("review", Skill.SkillSource.BUILTIN),
            skill("security-review", Skill.SkillSource.BUILTIN));

        assertEquals(List.of(
            "deep-research", "update-config", "verify", "debug", "code-review",
            "simplify", "batch", "fewer-permission-prompts", "loop", "claude-api",
            "run", "run-skill-generator"), CliHeadlessOutput.sdkInvocableSkillNames(loaded));
    }

    @Test
    void sdkSkillCatalogExcludesPluginCommandsButKeepsPluginSkills() {
        Skill pluginCommand = new Skill("wire:wire-ping", "description", List.of(),
            "content", null, Skill.SkillSource.PLUGIN, null, null, null,
            Map.of("pluginCommand", true));
        Skill pluginSkill = skill("wire:wire-plugin", Skill.SkillSource.PLUGIN);

        assertEquals(List.of("wire:wire-plugin"),
            CliHeadlessOutput.sdkInvocableSkillNames(List.of(pluginCommand, pluginSkill)));
    }

    @Test
    void sdkSkillCatalogPreservesUserShadowBeforeBundledSkillAndDerivesAliasAtBundledPosition() {
        List<Skill> loaded = List.of(
            skill("verify", Skill.SkillSource.USER),
            skill("deep-research", Skill.SkillSource.BUNDLED),
            skill("update-config", Skill.SkillSource.BUNDLED),
            skill("verify", Skill.SkillSource.BUNDLED),
            skill("code-review", Skill.SkillSource.BUNDLED));

        assertEquals(List.of(
            "verify", "deep-research", "update-config", "verify", "debug", "code-review"),
            CliHeadlessOutput.sdkInvocableSkillNames(loaded));
    }

    @Test
    void sdkCommandMetadataUsesPluginManifestNameHintAndStableLoopPickerText() {
        Skill pluginSkill = skill("wire-inline:ping", Skill.SkillSource.PLUGIN);
        var pluginCommand = PluginCommandDefinition
            .builder("wire-inline:ping", "Ping $ARGUMENTS", "wire-inline")
            .description("Ping from the inline plugin")
            .argumentHint("[target]")
            .version("1.0.0")
            .contentLength(15)
            .source("wire-inline@inline")
            .loadedFrom("/tmp/inline-plugin")
            .hasUserSpecifiedDescription(true)
            .build();

        var pluginInfo = CliHeadlessOutput.sdkCommandInfo(
            "wire-inline:ping", pluginSkill, pluginCommand);
        assertEquals("(wire-inline) Ping from the inline plugin", pluginInfo.description());
        assertEquals("[target]", pluginInfo.argumentHint());

        Skill loopSkill = new Skill("loop",
            "Run a prompt on an interval - internal model invocation guidance",
            List.of(), "content", null, Skill.SkillSource.BUNDLED,
            null, null, null, Map.of(
                "commandDescription", "Run a prompt or slash command on a recurring interval "
                    + "(e.g. /loop 5m /foo, defaults to 10m)",
                "argumentHint", "[interval] <prompt>",
                "aliases", List.of("proactive")));
        var loopInfo = CliHeadlessOutput.sdkCommandInfo("loop", loopSkill, null);
        assertEquals("Run a prompt or slash command on a recurring interval "
            + "(e.g. /loop 5m /foo, defaults to 10m)", loopInfo.description());
        assertEquals(List.of("proactive"), loopInfo.aliases());
    }

    private static Skill skill(String name, Skill.SkillSource source) {
        return new Skill(name, "description", List.of(), "content", null, source,
            null, null, null, Map.of());
    }

    @Test
    void mapsAssistantToRawAnthropicMessageShape() {
        Usage usage = new Usage(1, 2, 3, 4);
        AssistantMessage assistant = new AssistantMessage(
            "assistant-row", AssistantContent.of("msg_123", List.of(new TextBlock("OK")), usage));

        ObjectNode json = StdoutMessageWriter.toJson(
            new SDKMessage.Assistant(assistant, usage, "claude-sonnet-4-6"), META, false);

        ObjectNode message = (ObjectNode) json.path("message");
        assertEquals("msg_123", message.path("id").asText());
        assertEquals("message", message.path("type").asText());
        assertEquals("assistant", message.path("role").asText());
        assertEquals("claude-sonnet-4-6", message.path("model").asText());
        assertEquals("text", message.path("content").get(0).path("type").asText());
        assertEquals("OK", message.path("content").get(0).path("text").asText());
        assertEquals(3, message.path("usage").path("cache_creation_input_tokens").asLong());
        assertEquals(4, message.path("usage").path("cache_read_input_tokens").asLong());
        assertFalse(message.path("usage").has("server_tool_use"));
        assertTrue(message.path("stop_reason").isNull());
        assertTrue(message.path("stop_sequence").isNull());
        assertTrue(message.path("context_management").isNull());
        assertFalse(message.has("uuid"));
        assertFalse(message.has("timestamp"));
    }

    @Test
    void mcpAssistantToolUseIncludes197DisplayMetadata() {
        var metadata = new StdoutMessageWriter.SdkOutputMetadata(
            META.sessionId(), META.cwd(), META.model(), META.permissionMode(),
            META.tools(), META.mcpServers(), META.slashCommands(), META.apiKeySource(),
            META.claudeCodeVersion(), META.outputStyle(), META.agents(), META.skills(),
            META.plugins(), List.of(new StdoutMessageWriter.McpToolUseMetadata(
                "mcp__wire-reconnect__echo_marker", "Echo Marker", "wire-fake-mcp")));
        AssistantMessage assistant = new AssistantMessage(
            "assistant-row", AssistantContent.of("msg_mcp", List.of(new ToolUseBlock(
                "toolu_mcp", "mcp__wire-reconnect__echo_marker",
                MAPPER.createObjectNode().put("marker", "WIRE197"))), Usage.EMPTY));

        ObjectNode json = StdoutMessageWriter.toJson(
            new SDKMessage.Assistant(assistant, Usage.EMPTY, "claude-sonnet-4-6"),
            metadata, false);

        var toolMeta = json.path("tool_use_meta").get(0);
        assertEquals("toolu_mcp", toolMeta.path("id").asText());
        assertEquals("Echo Marker", toolMeta.path("display_name").asText());
        assertEquals("wire-fake-mcp", toolMeta.path("server_display_name").asText());
    }

    @Test
    void resultContainsBinary197StableSdkFieldsAndModelUsage() {
        Usage usage = new Usage(10, 4, 2, 3);
        SDKMessage.Result result = new SDKMessage.Result(
            SDKMessage.Result.SUCCESS, List.of(), usage,
            Map.of("glm-5.2", new Usage(20, 8, 4, 6)), "session-1", 0.25,
            List.of(), null, null, 50, 20, 60, 59, 51, 1, "end_turn", "result-uuid",
            "OK", false, List.of());

        ObjectNode json = StdoutMessageWriter.toJson(result, META, false);

        assertTrue(json.path("api_error_status").isNull());
        assertEquals(60, json.path("ttft_ms").asLong());
        assertEquals(59, json.path("ttft_stream_ms").asLong());
        assertEquals(51, json.path("time_to_request_ms").asLong());
        assertEquals("completed", json.path("terminal_reason").asText());
        assertEquals("off", json.path("fast_mode_state").asText());
        assertEquals("standard", json.path("usage").path("service_tier").asText());
        assertEquals(0, json.path("usage").path("cache_creation")
            .path("ephemeral_1h_input_tokens").asLong());
        assertTrue(json.path("usage").path("iterations").isArray());
        assertTrue(json.path("modelUsage").has("glm-5.2"));
        assertEquals(20, json.path("modelUsage").path("glm-5.2").path("inputTokens").asLong());
        assertEquals(10, json.path("usage").path("input_tokens").asLong());
        assertFalse(json.has("errors"));
    }

    @Test
    void resultUsesRecordedPerRequestModelCostWithoutReaggregatingUsage() {
        double recordedCost = 0.10804800000000002;
        SDKMessage.Result result = new SDKMessage.Result(
            SDKMessage.Result.SUCCESS, List.of(), new Usage(12_000, 1, 0, 0),
            Map.of("claude-sonnet-4-6", new Usage(36_001, 3, 0, 0)),
            Map.of("claude-sonnet-4-6", recordedCost),
            "session-1", recordedCost, List.of(), null, null,
            50, 20, 60, 59, 51, 1, "end_turn", "result-uuid",
            "OK", false, List.of());

        ObjectNode json = StdoutMessageWriter.toJson(result, META, false);

        assertEquals(Double.doubleToLongBits(recordedCost), Double.doubleToLongBits(
            json.path("modelUsage").path("claude-sonnet-4-6").path("costUSD").asDouble()));
    }

    @Test
    void opus46ModelUsageReportsReleasedDefaultMaxOutputTokens() {
        SDKMessage.Result result = new SDKMessage.Result(
            SDKMessage.Result.SUCCESS, List.of(), Usage.EMPTY,
            Map.of("claude-opus-4-6", new Usage(1, 1, 0, 0)), "session-1", 0.0,
            List.of(), null, null, 1, 1, 1, 1, 1, 1, "end_turn", "result-uuid",
            "OK", false, List.of());

        ObjectNode json = StdoutMessageWriter.toJson(result, META, false);

        assertEquals(64_000,
            json.path("modelUsage").path("claude-opus-4-6").path("maxOutputTokens").asInt());
    }

    @Test
    void abortedStreamingResultUsesTheSparse197Envelope() {
        UserMessage interruption = new UserMessage(
            "interrupt-user",
            MessageContent.ofBlocks(List.of(new TextBlock("[Request interrupted by user]"))),
            false, false, null, MessageOrigin.USER, null, Instant.EPOCH,
            null, null, "session-1", null);
        SDKMessage.Result result = new SDKMessage.Result(
            SDKMessage.Result.ERROR_DURING_EXECUTION, List.of(interruption), Usage.EMPTY,
            Map.of(), "session-1", 0.0, List.of(), "off", null,
            96, 0, 12, 11, 4, 2, null, "result-uuid", "", true,
            List.of("[ede_diagnostic] result_type=user last_content_type=n/a stop_reason=null"));

        ObjectNode json = StdoutMessageWriter.toJson(result, META, true);

        assertEquals("aborted_streaming", json.path("terminal_reason").asText());
        assertEquals(2, json.path("num_turns").asInt());
        assertFalse(json.has("api_error_status"));
        assertFalse(json.has("ttft_ms"));
        assertFalse(json.has("ttft_stream_ms"));
        assertFalse(json.has("time_to_request_ms"));
        assertFalse(json.has("result"));
        assertEquals(
            "[ede_diagnostic] result_type=user last_content_type=n/a stop_reason=null",
            json.path("errors").get(0).asText());
    }

    @Test
    void oldInterruptionInHistoryDoesNotRewriteALaterSuccessfulResult() {
        UserMessage oldInterruption = new UserMessage(
            "old-interrupt",
            MessageContent.ofBlocks(List.of(new TextBlock("[Request interrupted by user]"))),
            false, false, null, MessageOrigin.USER, null, Instant.EPOCH,
            null, null, "session-1", null);
        AssistantMessage currentAssistant = new AssistantMessage(
            "current-assistant",
            AssistantContent.of("msg-current", List.of(new TextBlock("done")), Usage.EMPTY));
        SDKMessage.Result result = new SDKMessage.Result(
            SDKMessage.Result.SUCCESS, List.of(oldInterruption, currentAssistant), Usage.EMPTY,
            Map.of(), "session-1", 0.0, List.of(), "off", null,
            20, 10, 3, 2, 1, 1, "end_turn", "result-uuid", "done", false,
            List.of());

        ObjectNode json = StdoutMessageWriter.toJson(result, META, true);

        assertEquals("completed", json.path("terminal_reason").asText());
        assertEquals("done", json.path("result").asText());
        assertTrue(json.has("ttft_ms"));
    }

    @Test
    void mapsStreamRequestStartTo197RequestingStatusEnvelope() {
        ObjectNode json = StdoutMessageWriter.toJson(
            new SDKMessage.StreamRequestStart("glm-5.2", 1), META, false);

        assertEquals("system", json.path("type").asText());
        assertEquals("status", json.path("subtype").asText());
        assertEquals("requesting", json.path("status").asText());
        assertEquals("session-1", json.path("session_id").asText());
        assertTrue(json.hasNonNull("uuid"));
        assertFalse(json.has("model"));
        assertFalse(json.has("message_count"));
    }

    @Test
    void mapsCompactSdkStatusMetadataToReleased197Envelope() {
        ObjectNode start = StdoutMessageWriter.toJson(
            new SDKMessage.Status("compacting", null, null), META, false);
        ObjectNode failed = StdoutMessageWriter.toJson(
            new SDKMessage.Status(null, "failed", "too_few_groups"), META, false);

        assertEquals("system", start.path("type").asText());
        assertEquals("status", start.path("subtype").asText());
        assertEquals("compacting", start.path("status").asText());
        assertFalse(start.has("compact_result"));
        assertFalse(start.has("compact_error"));

        assertTrue(failed.path("status").isNull());
        assertEquals("failed", failed.path("compact_result").asText());
        assertEquals("too_few_groups", failed.path("compact_error").asText());
    }

    @Test
    void mapsCompactBoundaryToReleased197SdkEnvelope() {
        CompactMetadata metadata = new CompactMetadata(
            "auto", 12008L, 11L,
            new PreservedSegment("head-uuid", "anchor-uuid", "tail-uuid"),
            new PreservedMessages("anchor-uuid", List.of("anchor-uuid", "tail-uuid"),
                List.of("anchor-uuid", "tail-uuid")),
            640L, 11368L, null, null, null, null);
        SystemMessage boundary = new SystemMessage(
            "boundary-uuid", "compact_boundary", "info", "Conversation compacted",
            null, Instant.EPOCH, metadata);

        ObjectNode json = StdoutMessageWriter.toJson(
            new SDKMessage.CompactBoundary(List.of("m1", "m2"), Usage.EMPTY, boundary),
            META, false);

        assertEquals("system", json.path("type").asText());
        assertEquals("compact_boundary", json.path("subtype").asText());
        assertEquals("session-1", json.path("session_id").asText());
        assertEquals("boundary-uuid", json.path("uuid").asText());
        assertFalse(json.has("content"));
        assertFalse(json.has("level"));
        assertEquals("auto", json.path("compact_metadata").path("trigger").asText());
        assertEquals(12008L, json.path("compact_metadata").path("pre_tokens").asLong());
        assertEquals(640L, json.path("compact_metadata").path("post_tokens").asLong());
        assertEquals(11368L,
            json.path("compact_metadata").path("cumulative_dropped_tokens").asLong());
        assertEquals("head-uuid",
            json.path("compact_metadata").path("preserved_segment").path("head_uuid").asText());
        assertEquals("anchor-uuid",
            json.path("compact_metadata").path("preserved_messages").path("anchor_uuid").asText());
        assertEquals(2, json.path("compact_metadata").path("preserved_messages")
            .path("all_uuids").size());
    }

    @Test
    void mapsSyntheticCompactSummaryUserAsTextContent() {
        UserMessage summary = new UserMessage(
            "summary-uuid", MessageContent.ofText("Summary text"), false, true,
            null, MessageOrigin.USER, null, Instant.EPOCH, null, null,
            "session-1", null, null);

        ObjectNode json = StdoutMessageWriter.toJson(
            new SDKMessage.User(summary, false, null, null, null, true), META, false);

        assertTrue(json.path("isSynthetic").asBoolean());
        assertFalse(json.has("isReplay"));
        assertEquals("user", json.path("message").path("role").asText());
        assertTrue(json.path("message").path("content").isArray());
        assertEquals("text", json.path("message").path("content").path(0).path("type").asText());
        assertEquals("Summary text",
            json.path("message").path("content").path(0).path("text").asText());
    }

    @Test
    void mapsManualCompactSummaryAsReleasedScalarReplayEnvelope() {
        UserMessage summary = new UserMessage(
            "summary-uuid", MessageContent.ofText("Summary text"), false, true,
            null, MessageOrigin.USER, null, Instant.EPOCH, null, null,
            "session-1", null, null);

        ObjectNode json = StdoutMessageWriter.toJson(
            new SDKMessage.User(summary, false, null, null, null, true, true), META, false);

        assertTrue(json.path("isSynthetic").asBoolean());
        assertTrue(json.has("isReplay"));
        assertFalse(json.path("isReplay").asBoolean());
        assertEquals("Summary text", json.path("message").path("content").asText());
    }

    @Test
    void serializesRawAnthropicStreamEventWithoutFlatteningItsPayload() throws Exception {
        var raw = MAPPER.readTree("""
            {
              "type":"content_block_delta",
              "index":0,
              "delta":{"type":"text_delta","text":"OK"}
            }
            """);

        ObjectNode json = StdoutMessageWriter.toJson(
            new SDKMessage.RawStreamEvent(raw, null), META, false);

        assertEquals("stream_event", json.path("type").asText());
        assertEquals(raw, json.path("event"));
        assertEquals("session-1", json.path("session_id").asText());
        assertTrue(json.path("parent_tool_use_id").isNull());
        assertTrue(json.hasNonNull("uuid"));
    }

    @Test
    void messageStartRawEventCarriesApiRelativeTtft() throws Exception {
        var raw = MAPPER.readTree("""
            {"type":"message_start","message":{"id":"msg-1"}}
            """);

        ObjectNode json = StdoutMessageWriter.toJson(
            new SDKMessage.RawStreamEvent(raw, 11L), META, false);

        assertEquals(11, json.path("ttft_ms").asLong());
    }

    @Test
    void internalUiStreamEventsAreNeverSerializedAsSdkRawEvents() {
        assertNull(StdoutMessageWriter.toJson(
            new SDKMessage.StreamEvent("stop_hook_run_start", "Stop"), META, false));
        assertNull(StdoutMessageWriter.toJson(
            new SDKMessage.StreamEvent("content_block_delta", "OK"), META, false));
    }
}
