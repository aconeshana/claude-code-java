package com.claudecode.mcp;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.process.SubprocessEnvironment;
import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP transport over subprocess stdin/stdout using JSON-RPC 2.0.
 */
public class StdioTransport implements McpTransport {

    private static final Logger LOG = LoggerFactory.getLogger(StdioTransport.class);

    private final McpServerConfig config;
    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;
    private final AtomicInteger requestId = new AtomicInteger(0);

    /** In-flight request futures keyed by outbound JSON-RPC id. */
    private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Map<String, ServerRequestHandler> serverRequestHandlers = new ConcurrentHashMap<>();
    private final Map<String, NotificationHandler> notificationHandlers = new ConcurrentHashMap<>();

/** Set by {@link #close} so the reader loop's terminal IOException stays quiet. */
    private volatile boolean closed = false;

    /** Test hook: overrides both request timeouts when > 0. */
    long requestTimeoutOverrideMs = 0;

    public StdioTransport(McpServerConfig config) {
        this.config = config;
    }

    /**
     * Starts the MCP server subprocess plus the stdout reader and stderr
     * drain threads.
     */
    public void start() {
        try {
            List<String> command = new ArrayList<>();
            command.add(config.command());
            command.addAll(config.args());

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);

            // Merge environment variables
            Map<String, String> processEnv = pb.environment();
            SubprocessEnvironment.applyTo(processEnv);
            processEnv.putAll(config.env());

            process = pb.start();
            writer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

            Thread.ofVirtual().name("mcp-stdio-read-" + config.name()).start(this::readLoop);
            Thread.ofVirtual().name("mcp-stdio-err-" + config.name()).start(this::drainStderr);

            LOG.debug("Started MCP server '{}': {} {}", config.name(), config.command(), config.args());
        } catch (IOException e) {
            throw new McpException("Failed to start MCP server '" + config.name() + "'", e);
        }
    }

    /** Reader-thread body — the only consumer of the server's stdout. */
    private void readLoop() {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (StringUtils.isBlank(line)) continue;
                JsonNode msg;
                try {
                    msg = JsonUtils.getMapper().readTree(line);
                } catch (Exception _) {
                    // Some launchers (npx installing on first run) write plain
                    // text to stdout before the server speaks JSON-RPC — skip,
                    // don't kill the connection over it.
                    LOG.debug("Skipping non-JSON stdout line from '{}': {}",
                        config.name(), line.substring(0, Math.min(120, line.length())));
                    continue;
                }
                McpMessageDispatcher.dispatch(msg, pending,
                    serverRequestHandlers, notificationHandlers, this::writeReply);
            }
        } catch (IOException e) {
            if (!closed) {
                LOG.debug("MCP stdout read loop for '{}' ended: {}", config.name(), e.getMessage());
            }
        } finally {
            failAllPending("MCP server '" + config.name() + "' closed connection");
        }
    }

    /** Keeps the server from blocking on a full stderr pipe; surfaces its logs at debug. */
    private void drainStderr() {
        try (BufferedReader err = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = err.readLine()) != null) {
                LOG.debug("[{} stderr] {}", config.name(), line);
            }
        } catch (IOException _) {
            // Process exit closes the pipe — normal shutdown path.
        }
    }

    private void failAllPending(String reason) {
        for (Integer id : pending.keySet()) {
            CompletableFuture<JsonNode> f = pending.remove(id);
            if (f != null) f.completeExceptionally(new McpException(reason));
        }
    }

    /** Reply sender for server-initiated requests (see {@link McpMessageDispatcher}). */
    private void writeReply(ObjectNode reply) {
        try {
            writeLine(JsonUtils.getMapper().writeValueAsString(reply));
        } catch (IOException e) {
            throw new McpException("Failed to reply to server request on '" + config.name() + "'", e);
        }
    }

    /** Single choke point for stdin writes — serialized so lines never interleave. */
    private synchronized void writeLine(String line) throws IOException {
        writer.write(line);
        writer.newLine();
        writer.flush();
    }


    private long requestTimeoutMs(String method) {
        if (requestTimeoutOverrideMs > 0) return requestTimeoutOverrideMs;
        return McpTimeouts.operationTimeout(method).toMillis();
    }

    @Override
    public JsonNode sendRequest(String method, JsonNode params) {
        if (!isConnected()) {
            throw new McpException("Transport not connected for server '" + config.name() + "'");
        }

        McpJsonRpcRequests.Prepared prepared =
            McpJsonRpcRequests.prepare(requestId, method, params);
        int id = prepared.id();
        ObjectNode request = prepared.request();

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            writeLine(JsonUtils.getMapper().writeValueAsString(request));
        } catch (IOException e) {
            pending.remove(id);
            throw new McpException("Communication error with MCP server '" + config.name() + "'", e);
        }

        long timeoutMs = requestTimeoutMs(method);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof McpException mcp) throw mcp;
            throw new McpException("MCP request " + method + " to '" + config.name()
                + "' failed: " + e.getCause(), e.getCause());
        } catch (TimeoutException _) {
            pending.remove(id);
            throw new McpException("MCP request " + method + " to '" + config.name()
                + "' timed out after " + timeoutMs + "ms");
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            pending.remove(id);
            throw new McpException("Interrupted waiting for MCP response from '"
                + config.name() + "'");
        }
    }

    @Override
    public void sendNotification(String method, JsonNode params) {
        if (!isConnected()) {
            throw new McpException("Transport not connected for server '" + config.name() + "'");
        }
        try {
            ObjectNode notif = JsonUtils.getMapper().createObjectNode();
            notif.put("jsonrpc", "2.0");
            notif.put("method", method);
            if (params != null) notif.set("params", params);
            writeLine(JsonUtils.getMapper().writeValueAsString(notif));
        } catch (IOException e) {
            throw new McpException("Failed to send notification " + method
                + " to '" + config.name() + "'", e);
        }
    }

    @Override
    public void onServerRequest(String method, ServerRequestHandler handler) {
        if (method == null || handler == null) return;
        serverRequestHandlers.put(method, handler);
    }

    @Override
    public void onNotification(String method, NotificationHandler handler) {
        if (method == null || handler == null) return;
        notificationHandlers.put(method, handler);
    }

    @Override
    public boolean isConnected() {
        return process != null && process.isAlive();
    }

    @Override
    public void close() throws Exception {
        closed = true;
        failAllPending("MCP server '" + config.name() + "' connection closed");
        if (writer != null) {
            try { writer.close(); } catch (IOException _) {}
        }
        if (reader != null) {
            // Unblocks the reader thread's readLine.
            try { reader.close(); } catch (IOException _) {}
        }
        if (process != null) {
            process.destroyForcibly();
            process.waitFor();
        }
        LOG.debug("Closed MCP server '{}'", config.name());
    }
}
