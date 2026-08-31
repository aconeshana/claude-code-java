package com.claudecode.tools.tasks;

import org.apache.commons.lang3.Strings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.queue.MessageQueueManager;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okio.ByteString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MonitorWebSocketTaskTest {

    @TempDir Path tempDir;

    @Test
    void textBinaryAndCloseFramesUseOfficialEventMessages() throws Exception {
        TaskStore store = TaskStore.inMemory();
        TaskState state = store.create(TaskType.MONITOR_WS, "deploy events");
        store.updateStatus(state.id(), TaskStatus.RUNNING);
        MessageQueueManager queue = new MessageQueueManager();
        MonitorWebSocketTask task = new MonitorWebSocketTask(state, store,
            tempDir.resolve("monitor.output"), URI.create("wss://example.com/events"),
            HttpUrl.get("https://example.com/events"), null, List.of(),
            new OkHttpClient(), queue, 3000, true);
        FakeWebSocket socket = new FakeWebSocket();

        task.onMessage(socket, "hello");
        Thread.sleep(250);
        assertEquals("""
            <task-notification>
            <task-id>%s</task-id>
            <summary>Monitor event: "deploy events"</summary>
            <event>hello</event>
            </task-notification>""".formatted(state.id()), queue.dequeue().text());

        task.onMessage(socket, ByteString.of((byte) 1, (byte) 2, (byte) 3));
        task.onClosing(socket, 1000, "bye");
        assertTrue(Strings.CS.contains(queue.dequeue().text(), "<event>[binary frame, 3 bytes]</event>"));
        assertTrue(Strings.CS.contains(queue.dequeue().text(), "<event>[WebSocket closed: 1000 bye]</event>"));
        assertEquals(TaskStatus.COMPLETED, store.get(state.id()).orElseThrow().status());
        assertTrue(store.get(state.id()).orElseThrow().notified());
        var sdkEvents = queue.drainSdkEvents();
        SDKMessage.TaskUpdated updated = assertInstanceOf(
            SDKMessage.TaskUpdated.class, sdkEvents.getFirst());
        assertEquals("completed", updated.patch().get("status"));
        SDKMessage.TaskNotification terminal = assertInstanceOf(
            SDKMessage.TaskNotification.class, sdkEvents.get(1));
        assertEquals("stopped", terminal.status());
        assertEquals("", terminal.outputFile());
        assertEquals("deploy events", terminal.summary());
    }

    private static final class FakeWebSocket implements WebSocket {
        @Override public Request request() {
            return new Request.Builder().url("https://example.com").build();
        }
        @Override public long queueSize() { return 0; }
        @Override public boolean send(String text) { return true; }
        @Override public boolean send(ByteString bytes) { return true; }
        @Override public boolean close(int code, String reason) { return true; }
        @Override public void cancel() { }
    }
}
