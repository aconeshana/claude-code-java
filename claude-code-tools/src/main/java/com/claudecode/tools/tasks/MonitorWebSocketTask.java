package com.claudecode.tools.tasks;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.queue.MessageQueueManager;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Live WebSocket-backed Monitor task.
 */
public final class MonitorWebSocketTask extends WebSocketListener implements MonitorTaskHandle {

    private static final int MAX_FRAME_BYTES = 1_048_576;
    private static final ScheduledThreadPoolExecutor TIMER = createTimer();

    private final TaskState task;
    private final TaskStore store;
    private final Path outputPath;
    private final URI uri;
    private final HttpUrl connectUrl;
    private final String hostHeader;
    private final List<String> protocols;
    private final OkHttpClient client;
    private final MessageQueueManager queue;
    private final long timeoutMs;
    private final boolean persistent;
    private final MonitorEventDispatcher events;
    private final AtomicBoolean finished = new AtomicBoolean();
    private final Object outputLock = new Object();

    private volatile WebSocket socket;
    private volatile BufferedWriter output;
    private volatile ScheduledFuture<?> timeoutFuture;
    public MonitorWebSocketTask(TaskState task, TaskStore store, Path outputPath,
                                URI uri, HttpUrl connectUrl, String hostHeader,
                                List<String> protocols, OkHttpClient client,
                                MessageQueueManager queue, long timeoutMs,
                                boolean persistent) {
        this.task = task;
        this.store = store;
        this.outputPath = outputPath;
        this.uri = uri;
        this.connectUrl = connectUrl;
        this.hostHeader = hostHeader;
        this.protocols = List.copyOf(protocols);
        this.client = client;
        this.queue = queue;
        this.timeoutMs = timeoutMs;
        this.persistent = persistent;
        this.events = MonitorEventDispatcher.forQueue(task.id(), task.description(),
            task.agentId().orElse(null), queue, this::stopQuietly);
    }

    @Override public String getTaskId() { return task.id(); }
    @Override public Path getOutputPath() { return outputPath; }
    @Override public String displaySource() { return uri.toString(); }

    public void start() throws IOException {
        Files.createDirectories(outputPath.getParent());
        output = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE);
        Request.Builder request = new Request.Builder().url(connectUrl);
        if (hostHeader != null) request.header("Host", hostHeader);
        if (!protocols.isEmpty()) {
            request.header("Sec-WebSocket-Protocol", String.join(", ", protocols));
        }
        store.updateStatus(task.id(), TaskStatus.RUNNING);
        socket = client.newWebSocket(request.build(), this);
        if (!persistent) {
            timeoutFuture = TIMER.schedule(this::timeout, timeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        if (finished.get()) webSocket.cancel();
        else socket = webSocket;
    }

    @Override
    public void onMessage(WebSocket webSocket, String event) {
        if (finished.get()) return;
        int bytes = event.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_FRAME_BYTES) {
            String dropped = "[Dropped " + bytes + "-byte frame (exceeds "
                + MAX_FRAME_BYTES + "); closing]";
            appendOutputQuietly(dropped);
            events.emitHousekeeping(dropped);
            stopQuietly();
        } else {
            appendOutputQuietly(event);
            events.accept(event);
        }
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
        if (finished.get()) return;
        String event = bytes.size() > MAX_FRAME_BYTES
            ? "[Dropped " + bytes.size() + "-byte frame (exceeds " + MAX_FRAME_BYTES
                + "); closing]"
            : "[binary frame, " + bytes.size() + " bytes]";
        appendOutputQuietly(event);
        if (bytes.size() > MAX_FRAME_BYTES) {
            events.emitHousekeeping(event);
            stopQuietly();
        } else {
            events.accept(event);
        }
    }

    @Override
    public void onClosing(WebSocket webSocket, int statusCode, String reason) {
        if (finished.get()) return;
        String suffix = StringUtils.isBlank(reason) ? "" : " " + reason;
        String event = "[WebSocket closed: " + statusCode + suffix + "]";
        appendOutputQuietly(event);
        events.emitHousekeeping(event);
        webSocket.close(statusCode, reason == null ? "" : reason);
        stopQuietly();
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable error, Response response) {
        if (finished.get()) return;
        String message = error == null || error.getMessage() == null
            ? "Unknown error" : error.getMessage();
        String event = "[WebSocket error: " + message + "]";
        appendOutputQuietly(event);
        events.emitHousekeeping(event);
        // OkHttp's failure callback is terminal and is not followed by close.
        stopQuietly();
    }

    private void timeout() {
        if (finished.get()) return;
        events.emitHousekeeping("[Monitor timed out — re-arm if needed.]");
        stopQuietly();
    }

    private boolean stopQuietly() {
        var current = store.get(task.id());
        if (current.isEmpty() || current.get().status() != TaskStatus.RUNNING) return false;
        if (!finished.compareAndSet(false, true)) return false;
        cancelTimeout();
        events.close();

        store.markNotified(task.id());
        TaskState terminal = store.updateStatus(task.id(), TaskStatus.COMPLETED);
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("status", "completed");
        terminal.endTime().ifPresent(end -> patch.put("end_time", end.toEpochMilli()));
        queue.enqueueSdkEvent(new SDKMessage.TaskUpdated(task.id(), patch));
        queue.enqueueSdkEvent(new SDKMessage.TaskNotification(
            task.id(), task.toolUseId().orElse(null), "stopped", "", task.description()));
        WebSocket live = socket;
        if (live != null) live.cancel();
        closeOutput();
        return true;
    }

    @Override
    public boolean kill() {
        if (finished.get()) return false;
        events.emitHousekeeping("[Monitor stopped]");
        return stopQuietly();
    }

    private void appendOutputQuietly(String event) {
        synchronized (outputLock) {
            if (output == null) return;
            try {
                output.write(event);
                output.newLine();
                output.flush();
            } catch (IOException _) { }
        }
    }

    private void closeOutput() {
        synchronized (outputLock) {
            if (output == null) return;
            try { output.close(); } catch (IOException _) { }
            output = null;
        }
    }

    private void cancelTimeout() {
        ScheduledFuture<?> future = timeoutFuture;
        if (future != null) future.cancel(false);
    }

    private static ScheduledThreadPoolExecutor createTimer() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(
            1, Thread.ofVirtual().name("monitor-ws-timeout-", 0).factory());
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }
}
