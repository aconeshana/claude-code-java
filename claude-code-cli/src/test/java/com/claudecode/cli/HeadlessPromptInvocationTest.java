package com.claudecode.cli;

import org.apache.commons.lang3.Strings;

import com.claudecode.runtime.query.DefaultQuerySession;
import com.claudecode.runtime.query.QuerySessionSpec;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.SubmitOptions;
import com.claudecode.core.engine.TranscriptSink;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.queue.QueuedCommand;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskStore;
import com.claudecode.tools.tasks.TaskType;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that print mode does not collapse a prompt-command envelope. */
class HeadlessPromptInvocationTest {

    @AfterEach
    void resetTasks() {
        TaskRegistry.resetGlobalForTest();
    }

    @Test
    void processPromptPassesStructuredContentAndTurnOverridesToEngine() {
        class CapturingEngine extends DefaultQuerySession {
            Object prompt;
            SubmitOptions options;
            CapturingEngine() {
                super(QuerySessionSpec.builder().llmClient(new StreamingClient() {
                    @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                        return Collections.emptyIterator();
                    }
                    @Override public String getModel() { return "session-model"; }
                }).build());
            }
            @Override public Iterator<SDKMessage> submitMessage(Object prompt, SubmitOptions options) {
                this.prompt = prompt;
                this.options = options;
                return Collections.emptyIterator();
            }
        }
        var source = JsonUtils.getMapper().createObjectNode();
        source.put("type", "base64");
        source.put("media_type", "image/png");
        source.put("data", "aGVsbG8=");
        MessageContent content = MessageContent.ofBlocks(List.of(
            new TextBlock("inspect"), new ImageBlock(source)));
        SubmitOptions options = SubmitOptions.DEFAULT.asSlashCommand()
            .withPromptOverrides("command-model", "medium");
        CapturingEngine engine = new CapturingEngine();

        CliHeadlessOutput.processPrompt(engine, content, options,
            new PrintWriter(new StringWriter()), false);

        assertSame(content, engine.prompt);
        assertTrue(engine.options.isSlashCommand());
        assertEquals("command-model", engine.options.modelOverride());
        assertEquals("medium", engine.options.effortOverride());
    }

    @Test
    void textPrintModeDoesNotAppendJavaOnlyUsageFooterEvenWhenVerbose() {
        class FixedEngine extends DefaultQuerySession {
            FixedEngine() {
                super(QuerySessionSpec.builder().llmClient(new StreamingClient() {
                    @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                        return Collections.emptyIterator();
                    }
                    @Override public String getModel() { return "glm-5.2"; }
                }).model("glm-5.2").build());
            }

            @Override public Iterator<SDKMessage> submitMessage(Object prompt, SubmitOptions options) {
                Usage usage = new Usage(1, 1, 0, 0);
                AssistantMessage assistant = new AssistantMessage(
                    "assistant-row", AssistantContent.of(
                        "msg_197", List.of(new TextBlock("OK")), usage));
                return List.<SDKMessage>of(
                    new SDKMessage.Assistant(assistant, usage, "glm-5.2"),
                    new SDKMessage.Result(SDKMessage.Result.SUCCESS, List.of(), usage, "session-1"))
                    .iterator();
            }
        }
        StringWriter buffer = new StringWriter();

        CliHeadlessOutput.processPrompt(new FixedEngine(), "probe", SubmitOptions.DEFAULT,
            new PrintWriter(buffer), true);

        assertEquals("OK" + System.lineSeparator(), buffer.toString());
    }

    @Test
    void oneShotPrintReturnsNonZeroWhenFinalResultIsError() {
        class FailingEngine extends DefaultQuerySession {
            FailingEngine() {
                super(QuerySessionSpec.builder().llmClient(new StreamingClient() {
                    @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                        return Collections.emptyIterator();
                    }
                    @Override public String getModel() { return "test-model"; }
                }).build());
            }

            @Override public Iterator<SDKMessage> submitMessage(Object prompt, SubmitOptions options) {
                return List.<SDKMessage>of(new SDKMessage.Result(
                    SDKMessage.Result.ERROR_DURING_EXECUTION,
                    List.of(), Usage.EMPTY, getSessionId())).iterator();
            }
        }

        int exit = CliHeadlessOutput.processPrompt(
            new FailingEngine(), "probe", SubmitOptions.DEFAULT,
            new PrintWriter(new StringWriter()), false);

        assertEquals(1, exit,
            "released 2.1.197 exits non-zero when one-shot print ends with is_error=true");
    }

    @Test
    void headlessPrintFeedsQueuedTaskNotificationsBackBeforeExit() {
        class RewakingEngine extends DefaultQuerySession {
            final List<Object> prompts = new ArrayList<>();

            RewakingEngine() {
                super(QuerySessionSpec.builder().llmClient(new StreamingClient() {
                    @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                        return Collections.emptyIterator();
                    }
                    @Override public String getModel() { return "test-model"; }
                }).build());
            }

            @Override public Iterator<SDKMessage> submitMessage(Object prompt, SubmitOptions options) {
                prompts.add(prompt);
                if (prompts.size() == 1) {
                    getMessageQueue().enqueuePendingNotification(
                        QueuedCommand.notification("<task-notification>event</task-notification>"));
                }
                Usage usage = new Usage(1, 1, 0, 0);
                AssistantMessage assistant = new AssistantMessage(
                    "assistant-" + prompts.size(), AssistantContent.of(
                        "msg-" + prompts.size(),
                        List.of(new TextBlock(prompts.size() == 1 ? "FIRST" : "SECOND")), usage));
                return List.<SDKMessage>of(
                    new SDKMessage.Assistant(assistant, usage, "test-model"),
                    new SDKMessage.Result(SDKMessage.Result.SUCCESS, List.of(), usage, "session-1"))
                    .iterator();
            }
        }
        TaskRegistry.setGlobalForTest(new TaskRegistry(TaskStore.inMemory()));
        RewakingEngine engine = new RewakingEngine();
        StringWriter buffer = new StringWriter();

        CliHeadlessOutput.processPrompt(engine, "probe", SubmitOptions.DEFAULT,
            new PrintWriter(buffer), false);

        assertEquals(List.of("probe", "<task-notification>event</task-notification>"),
            engine.prompts);
        assertEquals("FIRST" + System.lineSeparator() + "SECOND" + System.lineSeparator(),
            buffer.toString());
    }

    @Test
    void headlessTaskNotificationStartsANewTranscriptPromptLineage() {
        class NotificationEngine extends DefaultQuerySession {
            int turns;

            NotificationEngine() {
                super(QuerySessionSpec.builder().llmClient(new StreamingClient() {
                    @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                        return Collections.emptyIterator();
                    }
                    @Override public String getModel() { return "test-model"; }
                }).build());
            }

            @Override public Iterator<SDKMessage> submitMessage(Object prompt, SubmitOptions options) {
                if (++turns == 1) {
                    getMessageQueue().enqueuePendingNotification(
                        QueuedCommand.notification("<task-notification>event</task-notification>"));
                }
                Usage usage = new Usage(1, 1, 0, 0);
                return List.<SDKMessage>of(new SDKMessage.Result(
                    SDKMessage.Result.SUCCESS, List.of(), usage, "session-1")).iterator();
            }
        }

        TaskRegistry.setGlobalForTest(new TaskRegistry(TaskStore.inMemory()));
        NotificationEngine engine = new NotificationEngine();
        List<String> writes = new ArrayList<>();
        engine.setTranscriptSink(new TranscriptSink() {
            @Override public void record(
                    String sessionId, Message message) {}

            @Override public void recordQueueOperation(
                    String sessionId, String operation, String content) {
                writes.add("queue:" + operation);
            }

            @Override public void recordPromptStart(String sessionId, String promptSource) {
                writes.add("prompt-start:" + promptSource);
            }
        });

        CliHeadlessOutput.processPrompt(engine, "probe", SubmitOptions.DEFAULT,
            new PrintWriter(new StringWriter()), false);

        assertEquals(List.of("queue:enqueue", "prompt-start:sdk", "queue:dequeue"), writes);
    }

    @Test
    void streamJsonHoldsResultsUntilBackgroundAgentNotificationTurnCompletes() {
        TaskStore store = TaskStore.inMemory();
        TaskRegistry.setGlobalForTest(new TaskRegistry(store));
        store.createWithId("agent-1", TaskType.LOCAL_AGENT, "background", null);
        store.updateStatus("agent-1", TaskStatus.RUNNING);

        class BackgroundEngine extends DefaultQuerySession {
            int turns;

            BackgroundEngine() {
                super(QuerySessionSpec.builder().llmClient(new StreamingClient() {
                    @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                        return Collections.emptyIterator();
                    }
                    @Override public String getModel() { return "test-model"; }
                }).model("test-model").build());
            }

            @Override public Iterator<SDKMessage> submitMessage(Object prompt, SubmitOptions options) {
                turns++;
                if (turns == 1) {
                    Thread.startVirtualThread(() -> {
                        try {
                            Thread.sleep(150);
                        } catch (InterruptedException _) {
                            Thread.currentThread().interrupt();
                        }
                        getMessageQueue().enqueuePendingNotification(
                            QueuedCommand.notification("""
                                <task-notification>
                                <task-id>agent-1</task-id>
                                <tool-use-id>toolu_parent</tool-use-id>
                                <output-file>/tmp/agent-1.output</output-file>
                                <status>completed</status>
                                <summary>Agent finished</summary>
                                <result>done</result>
                                </task-notification>"""));
                        store.updateStatus("agent-1", TaskStatus.COMPLETED);
                    });
                }
                Usage usage = new Usage(1, 1, 0, 0);
                AssistantMessage assistant = new AssistantMessage(
                    "assistant-" + turns, AssistantContent.of(
                        "msg-" + turns,
                        List.of(new TextBlock(turns == 1 ? "FIRST" : "SECOND")), usage));
                List<SDKMessage.PermissionDenial> denials = turns == 1
                    ? List.of(new SDKMessage.PermissionDenial(
                        "Bash", "toolu_child", Map.of("command", "touch /tmp/marker")))
                    : List.of();
                SDKMessage.Result result = new SDKMessage.Result(
                    SDKMessage.Result.SUCCESS, List.of(), usage, Map.of(), "session-1", 0.0,
                    denials, "off", null, 1, 1, 0, 0, 0,
                    turns == 1 ? 2 : 1, "end_turn", "result-" + turns,
                    turns == 1 ? "FIRST" : "SECOND", false, List.of());
                return List.<SDKMessage>of(
                    new SDKMessage.Assistant(assistant, usage, "test-model"), result).iterator();
            }
        }

        BackgroundEngine engine = new BackgroundEngine();
        StringWriter buffer = new StringWriter();
        PrintWriter out = new PrintWriter(buffer, true);
        CliHeadlessOutput.processPrompt(engine, "probe", SubmitOptions.DEFAULT, out,
            "stream-json", true, false,
            () -> new StdoutMessageWriter.SdkOutputState(
                StdoutMessageWriter.SdkOutputMetadata.fromEngine(engine),
                SdkInboundControlHandler.ControlCatalog.empty()));

        List<JsonNode> lines = buffer.toString().lines()
            .map(line -> {
                try {
                    return JsonUtils.getMapper().readTree(line);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            })
            .toList();
        int notificationIndex = IntStream.range(0, lines.size())
            .filter(i -> Strings.CS.equals("task_notification", lines.get(i).path("subtype").asText()))
            .findFirst().orElseThrow();
        List<Integer> resultIndexes = IntStream.range(0, lines.size())
            .filter(i -> Strings.CS.equals("result", lines.get(i).path("type").asText()))
            .boxed().toList();

        assertEquals(2, resultIndexes.size());

        // task is live. It releases that result before processing the queued
        // task-notification turn.
        assertTrue(resultIndexes.getFirst() < notificationIndex, buffer.toString());
        assertEquals("toolu_child",
            lines.get(resultIndexes.getFirst()).path("permission_denials").get(0)
                .path("tool_use_id").asText());
        assertTrue(lines.get(resultIndexes.getLast()).path("permission_denials").isEmpty(),
            buffer.toString());
        assertEquals("task-notification",
            lines.get(resultIndexes.getLast()).path("origin").path("kind").asText());
    }

    @Test
    void sessionRestoreNoticeIsSuppressedForPrintAndStreamJsonOutput() {
        StringWriter printBuffer = new StringWriter();
        CliSessionRestoreCoordinator.writeSessionRestoreNotice(
            CliOutput.borrowed(new PrintWriter(printBuffer, true)), true, "text", "restored");
        assertEquals("", printBuffer.toString());

        StringWriter streamBuffer = new StringWriter();
        CliSessionRestoreCoordinator.writeSessionRestoreNotice(
            CliOutput.borrowed(new PrintWriter(streamBuffer, true)), false, "stream-json", "restored");
        assertEquals("", streamBuffer.toString());

        StringWriter interactiveBuffer = new StringWriter();
        CliSessionRestoreCoordinator.writeSessionRestoreNotice(
            CliOutput.borrowed(new PrintWriter(interactiveBuffer, true)), false, "text", "restored");
        assertEquals("restored" + System.lineSeparator(), interactiveBuffer.toString());
    }
}
