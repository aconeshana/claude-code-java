package com.claudecode.cli;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.gateway.GatewaySupervisorPort;
import com.claudecode.runtime.sessionhost.CollaborationSetupPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TUI-side {@link CollaborationSetupPort} bridge that delegates CC Connector
 * lifecycle and Feishu onboarding to the standalone {@code claude web} daemon.
 *
 * <p>When the user enables collaboration or runs setup from the TUI footer, this
 * bridge ensures the Web daemon is running (via {@link GatewaySupervisorPort})
 * and reports the request over loopback HTTP using the gateway's launch token.
 */
final class WebGatewayCollaborationBridge implements CollaborationSetupPort {

    private static final Logger log = LoggerFactory.getLogger(WebGatewayCollaborationBridge.class);
    private static final Duration STATUS_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration SETUP_TIMEOUT = Duration.ofMinutes(12);

    private record GatewayEndpoint(String baseUrl, String token) {}

    private final GatewaySupervisorPort gatewaySupervisor;
    private final Path gatewayRecordFile;
    private final Path fallbackConfig;
    private final String workDir;
    private final long pid;
    private final HttpClient http;
    private final AtomicReference<Thread> setupThread = new AtomicReference<>();

    WebGatewayCollaborationBridge(
            GatewaySupervisorPort gatewaySupervisor,
            Path gatewayRecordFile,
            Path fallbackConfig,
            String workDir) {
        this(gatewaySupervisor, gatewayRecordFile, fallbackConfig, workDir,
            ProcessHandle.current().pid());
    }

    WebGatewayCollaborationBridge(
            GatewaySupervisorPort gatewaySupervisor,
            Path gatewayRecordFile,
            Path fallbackConfig,
            String workDir,
            long pid) {
        this.gatewaySupervisor = Objects.requireNonNull(gatewaySupervisor, "gatewaySupervisor");
        this.gatewayRecordFile = Objects.requireNonNull(gatewayRecordFile, "gatewayRecordFile");
        this.fallbackConfig = fallbackConfig;
        this.workDir = workDir == null ? "" : workDir;
        this.pid = pid;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    }

    static WebGatewayCollaborationBridge standard(
            GatewaySupervisorPort gatewaySupervisor, String workDir) {
        Path claudeHome = ClaudePaths.currentClaudeHome();
        return new WebGatewayCollaborationBridge(
            gatewaySupervisor,
            ClaudePaths.WEB_GATEWAY_JSON,
            CcConnectSupport.resolveDefaultConfig(claudeHome),
            workDir);
    }

    @Override
    public boolean configured() {
        Optional<JsonNode> remote = fetchRemoteStatusIfLive();
        if (remote.isPresent()) {
            return remote.get().path("configured").asBoolean(false);
        }
        return CcConnectSupport.targetConfigured(fallbackConfig);
    }

    @Override
    public boolean setupPending() {
        Optional<JsonNode> remote = fetchRemoteStatusIfLive();
        if (remote.isPresent()) {
            return remote.get().path("setup_pending").asBoolean(false);
        }
        return CcConnectSupport.setupPending(fallbackConfig);
    }

    @Override
    public List<String> configuredChannels() {
        Optional<JsonNode> remote = fetchRemoteStatusIfLive();
        if (remote.isPresent()) {
            List<String> channels = new ArrayList<>();
            for (JsonNode item : remote.get().path("available_channels")) {
                if (item.isTextual() && StringUtils.isNotBlank(item.asText())) {
                    channels.add(item.asText().trim().toLowerCase(Locale.ROOT));
                }
            }
            return List.copyOf(channels);
        }
        return CcConnectSupport.configuredChannels(fallbackConfig);
    }

    @Override
    public boolean remoteManaged() {
        return true;
    }

    @Override
    public CompletionStage<Void> attachChannel(String sessionId, String channel) {
        return CompletableFuture.runAsync(() -> {
            try {
                GatewayEndpoint endpoint = ensureGatewayEndpoint();
                ObjectNode body = JsonUtils.getMapper().createObjectNode();
                body.put("pid", pid);
                if (StringUtils.isNotBlank(sessionId)) body.put("session_id", sessionId);
                body.put("channel", channel == null ? "feishu" : channel);
                body.put("origin", "tui");
                postJson(endpoint, "/api/cc-connect/attach", body, CONTROL_TIMEOUT);
            } catch (Exception failure) {
                throw new CompletionException(failure);
            }
        });
    }

    @Override
    public CompletionStage<Void> detachChannel(String sessionId) {
        return CompletableFuture.runAsync(() -> {
            Optional<GatewayEndpoint> live = readLiveEndpoint();
            if (live.isEmpty()) return;
            try {
                ObjectNode body = JsonUtils.getMapper().createObjectNode();
                body.put("pid", pid);
                if (StringUtils.isNotBlank(sessionId)) body.put("session_id", sessionId);
                postJson(live.get(), "/api/cc-connect/detach", body, CONTROL_TIMEOUT);
            } catch (Exception failure) {
                throw new CompletionException(failure);
            }
        });
    }

    @Override
    public CompletionStage<Result> setup(Request request, Consumer<String> progress) {
        if (request == null) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("setup request is required"));
        }
        return CompletableFuture.supplyAsync(() -> {
            char[] secret = request.appSecret();
            setupThread.set(Thread.currentThread());
            try {
                GatewayEndpoint endpoint = ensureGatewayEndpoint();
                ObjectNode body = JsonUtils.getMapper().createObjectNode();
                body.put("mode", request.mode().name().toLowerCase(Locale.ROOT));
                if (StringUtils.isNotBlank(request.appId())) {
                    body.put("app_id", request.appId());
                }
                if (secret.length > 0) {
                    body.put("app_secret", new String(secret));
                }
                if (StringUtils.isNotBlank(workDir)) {
                    body.put("work_dir", workDir);
                }
                byte[] payload = JsonUtils.getMapper().writeValueAsBytes(body);
                HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint.baseUrl() + "/api/cc-connect/setup"))
                    .timeout(SETUP_TIMEOUT)
                    .header("Authorization", "Bearer " + endpoint.token())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();
                HttpResponse<InputStream> response =
                    http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    String errBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                    throw new IllegalStateException(extractError(errBody,
                        "Web gateway rejected setup (HTTP " + response.statusCode() + ")"));
                }
                Result finalResult = null;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        response.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (StringUtils.isBlank(line)) continue;
                        JsonNode frame = JsonUtils.getMapper().readTree(line);
                        String type = frame.path("type").asText("");
                        if (Strings.CS.equals("progress", type)) {
                            if (progress != null) progress.accept(frame.path("line").asText(""));
                        } else if (Strings.CS.equals("result", type)) {
                            boolean ok = frame.path("ok").asBoolean(false);
                            String channel = frame.path("channel").asText("");
                            String message = frame.path("message").asText("");
                            if (!ok) {
                                throw new IllegalStateException(
                                    StringUtils.isBlank(message) ? "Feishu setup failed" : message);
                            }
                            finalResult = new Result(
                                StringUtils.isBlank(channel) ? "feishu" : channel,
                                StringUtils.isBlank(message) ? "Feishu collaboration is ready" : message);
                        }
                    }
                }
                if (finalResult == null) {
                    throw new IllegalStateException("Web gateway closed setup stream without a result");
                }
                return finalResult;
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new CompletionException(failure);
            } finally {
                setupThread.compareAndSet(Thread.currentThread(), null);
                Arrays.fill(secret, '\0');
                request.clearAppSecret();
            }
        });
    }

    @Override
    public void cancel() {
        readLiveEndpoint().ifPresent(endpoint -> {
            try {
                postJson(endpoint, "/api/cc-connect/setup/cancel",
                    JsonUtils.getMapper().createObjectNode(), STATUS_TIMEOUT);
            } catch (Exception failure) {
                log.debug("Could not cancel remote cc-connect setup: {}", failure.getMessage());
            }
        });
        Thread current = setupThread.getAndSet(null);
        if (current != null) current.interrupt();
    }

    private GatewayEndpoint ensureGatewayEndpoint() {
        Optional<GatewayEndpoint> live = readLiveEndpoint();
        if (live.isPresent()) return live.get();
        GatewaySupervisorPort.Started started = gatewaySupervisor.start();
        return parseEndpoint(started.url())
            .orElseThrow(() -> new IllegalStateException(
                "Web gateway returned an invalid URL: " + started.url()));
    }

    private Optional<GatewayEndpoint> readLiveEndpoint() {
        return WebGatewayRecord.readLive(gatewayRecordFile)
            .flatMap(record -> parseEndpoint(record.url()));
    }

    private Optional<JsonNode> fetchRemoteStatusIfLive() {
        Optional<GatewayEndpoint> live = readLiveEndpoint();
        if (live.isEmpty()) return Optional.empty();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(live.get().baseUrl() + "/api/cc-connect"))
                .timeout(STATUS_TIMEOUT)
                .header("Authorization", "Bearer " + live.get().token())
                .GET()
                .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return Optional.of(JsonUtils.getMapper().readTree(response.body()));
            }
        } catch (Exception failure) {
            log.debug("Could not query Web gateway cc-connect status: {}", failure.getMessage());
        }
        return Optional.empty();
    }

    private JsonNode postJson(
            GatewayEndpoint endpoint, String path, ObjectNode body, Duration timeout)
            throws IOException, InterruptedException {
        byte[] payload = JsonUtils.getMapper().writeValueAsBytes(body);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint.baseUrl() + path))
            .timeout(timeout)
            .header("Authorization", "Bearer " + endpoint.token())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
            .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(extractError(response.body(),
                "Web gateway returned HTTP " + response.statusCode()));
        }
        return JsonUtils.getMapper().readTree(response.body());
    }

    private static String extractError(String body, String fallback) {
        if (StringUtils.isBlank(body)) return fallback;
        try {
            JsonNode node = JsonUtils.getMapper().readTree(body);
            String msg = node.path("error").path("message").asText("");
            if (StringUtils.isBlank(msg)) msg = node.path("message").asText("");
            return StringUtils.isBlank(msg) ? fallback : msg;
        } catch (Exception _) {
            return fallback;
        }
    }

    static Optional<GatewayEndpoint> parseEndpoint(String url) {
        if (StringUtils.isBlank(url)) return Optional.empty();
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            if (StringUtils.isBlank(scheme) || StringUtils.isBlank(host)) return Optional.empty();
            String baseUrl = port > 0 ? scheme + "://" + host + ":" + port : scheme + "://" + host;
            String token = "";
            String rawQuery = uri.getRawQuery();
            if (StringUtils.isNotBlank(rawQuery)) {
                for (String pair : rawQuery.split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0 && Strings.CS.equals("token", pair.substring(0, eq))) {
                        token = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                        break;
                    }
                }
            }
            if (StringUtils.isBlank(token)) return Optional.empty();
            return Optional.of(new GatewayEndpoint(baseUrl, token));
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }
}
