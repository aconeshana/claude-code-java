package com.claudecode.gateway;

import static com.claudecode.gateway.GatewayResponses.errorBody;
import static com.claudecode.gateway.GatewayResponses.rejectIfUnauthenticated;
import static com.claudecode.gateway.GatewayResponses.respondJson;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP request router and SSE mirror streamer for {@link GatewayServer}.
 */
final class GatewayRouter {

    private static final int NOT_FOUND = 404;
    private static final int UNAUTHORIZED = 401;
    private static final int INTERNAL_ERROR = 500;
    private static final Logger log = LoggerFactory.getLogger(GatewayRouter.class);

    private final GatewayServer.Config config;
    private final GatewayAuthFilter auth;
    private final MirrorHub mirror;
    private final MessagesHandler messages;
    private final ChatHandler chat;
    private final ResponsesHandler responses;
    private final GatewaySessionsHandler sessionsApi;
    private final GatewaySessionActionsHandler sessionActionsApi;
    private final GatewayRewindHandler rewindApi;
    private final GatewaySessionCatalogPort catalog;
    private final GatewayPermissionsHandler permissionsApi;
    private final GatewaySudoRespondHandler sudoApi;
    private final GatewayMessagesSnapshotHandler messagesSnapshotApi;
    private final GatewayTrajectoryHandler trajectoryApi;
    private final GatewaySettingsHandler settingsApi;
    private final GatewayScheduleHandler scheduleApi;
    private final GatewayRuntimeControlHandler runtimeControlApi;
    private final GatewayModelsHandler modelsApi;
    private final GatewayCommandsHandler commandsApi;
    private final GatewaySpinnerVerbsHandler spinnerVerbsApi;
    private final GatewayFileSuggestionsHandler fileSuggestionsApi;
    private final GatewayDirectoryPickerHandler directoryPickerApi;
    private final GatewayProjectsHandler projectsApi;
    private final GatewaySessionContextHandler sessionContextApi;
    private final GatewayCapabilitiesHandler capabilitiesApi;
    private final GatewayPermissionModeHandler permissionModeApi;
    private final GatewaySessionCancelHandler sessionCancelApi;
    private final GatewayTaskBoardHandler taskBoardApi;
    private final GatewayChangesHandler changesApi;
    private final GatewayWorkspaceFileHandler workspaceFileApi;
    private final GatewayBackgroundTasksHandler backgroundTasksApi;
    private final LanModelProxyHandler lanModelProxy;
    private final GatewayLanAdminHandler lanAdminApi;
    private final GatewayCcConnectHandler ccConnectApi;
    private final GatewayContextTimelineHandler contextTimelineApi;
    private final String localMachineHostName;
    private final GatewaySessionListingProjection sessionListing;

    GatewayRouter(
            GatewayServer.Config config,
            GatewayAuthFilter auth,
            MirrorHub mirror,
            MessagesHandler messages,
            ChatHandler chat,
            ResponsesHandler responses,
            GatewaySessionsHandler sessionsApi,
            GatewaySessionActionsHandler sessionActionsApi,
            GatewayRewindHandler rewindApi,
            GatewaySessionCatalogPort catalog,
            GatewayPermissionsHandler permissionsApi,
            GatewaySudoRespondHandler sudoApi,
            GatewayMessagesSnapshotHandler messagesSnapshotApi,
            GatewayTrajectoryHandler trajectoryApi,
            GatewaySettingsHandler settingsApi,
            GatewayScheduleHandler scheduleApi,
            GatewayRuntimeControlHandler runtimeControlApi,
            GatewayModelsHandler modelsApi,
            GatewayCommandsHandler commandsApi,
            GatewaySpinnerVerbsHandler spinnerVerbsApi,
            GatewayFileSuggestionsHandler fileSuggestionsApi,
            GatewayDirectoryPickerHandler directoryPickerApi,
            GatewayProjectsHandler projectsApi,
            GatewaySessionContextHandler sessionContextApi,
            GatewayCapabilitiesHandler capabilitiesApi,
            GatewayPermissionModeHandler permissionModeApi,
            GatewaySessionCancelHandler sessionCancelApi,
            GatewayTaskBoardHandler taskBoardApi,
            GatewayChangesHandler changesApi,
            GatewayWorkspaceFileHandler workspaceFileApi,
            GatewayBackgroundTasksHandler backgroundTasksApi,
            LanModelProxyHandler lanModelProxy,
            GatewayLanAdminHandler lanAdminApi,
            GatewayCcConnectHandler ccConnectApi,
            GatewayContextTimelineHandler contextTimelineApi,
            String localMachineHostName,
            GatewaySessionListingProjection sessionListing) {
        this.config = config;
        this.auth = auth;
        this.mirror = mirror;
        this.messages = messages;
        this.chat = chat;
        this.responses = responses;
        this.sessionsApi = sessionsApi;
        this.sessionActionsApi = sessionActionsApi;
        this.rewindApi = rewindApi;
        this.catalog = catalog;
        this.permissionsApi = permissionsApi;
        this.sudoApi = sudoApi;
        this.messagesSnapshotApi = messagesSnapshotApi;
        this.trajectoryApi = trajectoryApi;
        this.settingsApi = settingsApi;
        this.scheduleApi = scheduleApi;
        this.runtimeControlApi = runtimeControlApi;
        this.modelsApi = modelsApi;
        this.commandsApi = commandsApi;
        this.spinnerVerbsApi = spinnerVerbsApi;
        this.fileSuggestionsApi = fileSuggestionsApi;
        this.directoryPickerApi = directoryPickerApi;
        this.projectsApi = projectsApi;
        this.sessionContextApi = sessionContextApi;
        this.capabilitiesApi = capabilitiesApi;
        this.permissionModeApi = permissionModeApi;
        this.sessionCancelApi = sessionCancelApi;
        this.taskBoardApi = taskBoardApi;
        this.changesApi = changesApi;
        this.workspaceFileApi = workspaceFileApi;
        this.backgroundTasksApi = backgroundTasksApi;
        this.lanModelProxy = lanModelProxy;
        this.lanAdminApi = lanAdminApi;
        this.ccConnectApi = ccConnectApi;
        this.contextTimelineApi = contextTimelineApi;
        this.localMachineHostName = localMachineHostName;
        this.sessionListing = sessionListing;
    }

    /**
     * Answers any failure escaping {@link #route} with a 500 instead of a dead
     * connection. {@code OutOfMemoryError} and {@code IOException} propagate.
     */
    void routeGuarded(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (RuntimeException | Error failure) {
            if (failure instanceof OutOfMemoryError) throw failure;
            log.error("gateway handler failed for {} {}; answering {}",
                exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                INTERNAL_ERROR, failure);
            respondFailure(exchange, failure);
        }
    }

    /**
     * Best-effort 500. A handler that failed after starting its response — or an
     * SSE lane already streaming — has a sent status line that cannot be taken
     * back, so closing the exchange is all that is left.
     */
    private static void respondFailure(HttpExchange exchange, Throwable failure) {
        try (exchange) {
            if (exchange.getResponseCode() != -1) return;
            respondJson(exchange, INTERNAL_ERROR,
                errorBody("api_error", "request failed: " + failure));
        } catch (IOException | RuntimeException _) {
            // The client is gone, or the response was already committed.
        }
    }

    /** One synchronous handler body; {@code IOException} means the client went away. */
    private interface Handler {
        void handle() throws IOException;
    }

    /**
     * Runs one synchronous handler, then closes the exchange.
     *
     * <p>The 500 is written here rather than in {@link #routeGuarded} because
     * closing the exchange is what makes a failure unanswerable: {@code
     * ExchangeImpl.close()} drops the connection outright when no response was
     * sent, so a handler wrapped in a plain {@code try (exchange)} turns any
     * escaping throwable into {@code ERR_EMPTY_RESPONSE} before the guard above
     * ever sees it. See {@link #routeGuarded} for why {@code Error} counts.
     */
    private static void serve(HttpExchange exchange, Handler handler) throws IOException {
        try (exchange) {
            try {
                handler.handle();
            } catch (RuntimeException | Error failure) {
                if (failure instanceof OutOfMemoryError) throw failure;
                log.error("gateway handler failed for {} {}; answering {}",
                    exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    INTERNAL_ERROR, failure);
                if (exchange.getResponseCode() == -1) {
                    respondJson(exchange, INTERNAL_ERROR,
                        errorBody("api_error", "request failed: " + failure));
                }
            }
        }
    }

    /**
     * Runs one handler off the exchange thread, then closes the exchange.
     *
     * <p>The async twin of {@link #serve}: routes whose body blocks on slow I/O
     * (engine assembly, transcript reads and writes) hand the exchange to a
     * virtual thread and return from {@link #route} immediately. That return is
     * also what puts them out of {@link #routeGuarded}'s reach — the guard only
     * wraps the synchronous call, so everything the guard promises has to be
     * repeated here, on the thread that actually runs the handler.
     *
     * <p>A plain try/finally, not try-with-resources: the fallback 500 must
     * still reach the wire from the catch block, and try-with-resources closes
     * the exchange in its own finally <em>before</em> that catch runs — closing
     * the socket out from under the 500 write and producing the empty reply the
     * guard exists to prevent. See {@link #routeGuarded} for why {@code Error}
     * counts, and why {@code OutOfMemoryError} is re-thrown instead.
     */
    private static void serveAsync(HttpExchange exchange, Handler handler) {
        Thread.ofVirtual().name("gateway-api-async").start(() -> {
            try {
                handler.handle();
            } catch (IOException _) {
                // The client disconnected mid-request; nothing to recover.
            } catch (RuntimeException | Error failure) {
                if (failure instanceof OutOfMemoryError) throw failure;
                log.error("gateway handler failed for {} {}; answering {}",
                    exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    INTERNAL_ERROR, failure);
                try {
                    if (exchange.getResponseCode() == -1) {
                        respondJson(exchange, INTERNAL_ERROR,
                            errorBody("api_error", "request failed: " + failure));
                    }
                } catch (IOException | RuntimeException _) {
                    // The client is gone, or the response was already committed.
                }
            } finally {
                exchange.close();
            }
        });
    }

    private void route(HttpExchange exchange) throws IOException {
        boolean directLoopback = LanModelProxyHandler.isDirectLoopback(exchange);
        if (!directLoopback || LanModelProxyHandler.carriesGatewayKey(exchange)) {
            serveAsync(exchange, () -> lanModelProxy.handle(exchange));
            return;
        }
        if (LanModelProxyHandler.isDnsRebindingAttempt(exchange, localMachineHostName)
                && !auth.authenticated(exchange)) {
            try (exchange) {
                respondJson(exchange, 403, errorBody("forbidden",
                    "Refused browser request with foreign Host header (DNS rebinding guard)"));
            }
            return;
        }
        String targetPath = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        boolean get = Strings.CS.equals("GET", method);
        boolean post = Strings.CS.equals("POST", method);
        boolean put = Strings.CS.equals("PUT", method);
        boolean patch = Strings.CS.equals("PATCH", method);
        boolean delete = Strings.CS.equals("DELETE", method);
        // Static webui serving takes the GET paths the API never claims: the
        // landing page and the bundle's /webui/** assets. Every API route
        // below matches before this fallback runs, so the static handler sees
        // only unmatched GETs.
        if (get && GatewayStaticFiles.serveIfStatic(exchange)) {
            return;
        }
        // Long-lived SSE exchanges and the async sessions listing keep their
        // exchange open past this handler's return; every other exchange is
        // closed here.
        if (get && Strings.CS.equals("/api/events", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            streamMirror(exchange);
            return;
        }
        if (post && Strings.CS.equals("/v1/messages", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            messages.handle(exchange);
            return;
        }
        if (post && Strings.CS.equals("/v1/chat/completions", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            chat.handle(exchange);
            return;
        }
        if (post && Strings.CS.equals("/v1/responses", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            responses.handle(exchange);
            return;
        }
        if (get && Strings.CS.equals("/api/sessions", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            // The fingerprint-validated catalog listing may block on transcript
            // reads; respond off the exchange thread and own the exchange there.
            listSessions(exchange);
            return;
        }
        if (post && (Strings.CS.equals("/api/sessions/open", targetPath)
                || Strings.CS.equals("/api/sessions/close", targetPath))) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            // The open path assembles an engine (slow I/O) off the exchange thread.
            serveAsync(exchange, () -> sessionsApi.handle(exchange,
                Strings.CS.equals("/api/sessions/open", targetPath)));
            return;
        }
        if (get && Strings.CS.startsWith(targetPath, "/api/sessions/")
                && Strings.CS.endsWith(targetPath, "/messages")) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            String sessionId = URLDecoder.decode(
                targetPath.substring("/api/sessions/".length(),
                    targetPath.length() - "/messages".length()), StandardCharsets.UTF_8);
            if (sessionId.isEmpty()) {
                try (exchange) {
                    respondJson(exchange, 400, errorBody("invalid_request",
                        "session id is required"));
                }
                return;
            }
            messagesSnapshotApi.handle(exchange, sessionId);
            return;
        }
        if (get && Strings.CS.startsWith(targetPath, "/api/sessions/")
                && Strings.CS.endsWith(targetPath, "/transcript")) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            // /api/sessions/{sessionId}/agents/{agentId}/transcript — a
            // sub-agent's own conversation, restored from its sidechain
            // transcript. Both ids are path segments, so the tail after
            // /api/sessions/ splits on the fixed "/agents/" marker.
            String tail = targetPath.substring("/api/sessions/".length(),
                targetPath.length() - "/transcript".length());
            int marker = tail.indexOf("/agents/");
            if (marker <= 0 || marker + "/agents/".length() >= tail.length()) {
                try (exchange) {
                    respondJson(exchange, 400, errorBody("invalid_request",
                        "session id and agent id are required"));
                }
                return;
            }
            String sessionId = URLDecoder.decode(tail.substring(0, marker), StandardCharsets.UTF_8);
            String agentId = URLDecoder.decode(
                tail.substring(marker + "/agents/".length()), StandardCharsets.UTF_8);
            if (sessionId.isEmpty() || agentId.isEmpty()) {
                try (exchange) {
                    respondJson(exchange, 400, errorBody("invalid_request",
                        "session id and agent id are required"));
                }
                return;
            }
            // Reading a sidechain transcript walks a JSONL file; run it off
            // the exchange thread like the other session reads.
            serveAsync(exchange, () -> messagesSnapshotApi.handleAgent(
                exchange, sessionId, agentId, backgroundTasksApi.isAgentRunning(agentId)));
            return;
        }
        if (get && Strings.CS.startsWith(targetPath, "/api/sessions/")
                && Strings.CS.endsWith(targetPath, "/trajectory")) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            String tail = targetPath.substring("/api/sessions/".length(),
                targetPath.length() - "/trajectory".length());
            int marker = tail.indexOf("/agents/");
            if (marker > 0 && marker + "/agents/".length() < tail.length()) {
                String sessionId = URLDecoder.decode(tail.substring(0, marker), StandardCharsets.UTF_8);
                String agentId = URLDecoder.decode(
                    tail.substring(marker + "/agents/".length()), StandardCharsets.UTF_8);
                if (sessionId.isEmpty() || agentId.isEmpty()) {
                    try (exchange) {
                        respondJson(exchange, 400, errorBody("invalid_request",
                            "session id and agent id are required"));
                    }
                    return;
                }
                serveAsync(exchange, () -> trajectoryApi.handleAgent(
                    exchange, sessionId, agentId, backgroundTasksApi.isAgentRunning(agentId)));
                return;
            }
            if (!Strings.CS.contains(tail, "/")) {
                String sessionId = URLDecoder.decode(tail, StandardCharsets.UTF_8);
                if (sessionId.isEmpty()) {
                    try (exchange) {
                        respondJson(exchange, 400, errorBody("invalid_request",
                            "session id is required"));
                    }
                    return;
                }
                serveAsync(exchange, () -> trajectoryApi.handleSession(exchange, sessionId));
                return;
            }
        }
        if (post && Strings.CS.startsWith(targetPath, "/api/sessions/")
                && (Strings.CS.endsWith(targetPath, "/prompt")
                    || Strings.CS.endsWith(targetPath, "/stop"))) {
            String suffix = Strings.CS.endsWith(targetPath, "/prompt") ? "/prompt" : "/stop";
            String tail = targetPath.substring("/api/sessions/".length(),
                targetPath.length() - suffix.length());
            int marker = tail.indexOf("/agents/");
            if (marker > 0 && marker + "/agents/".length() < tail.length()) {
                if (rejectIfUnauthenticated(auth, exchange)) return;
                String sessionId = URLDecoder.decode(
                    tail.substring(0, marker), StandardCharsets.UTF_8);
                String agentId = URLDecoder.decode(
                    tail.substring(marker + "/agents/".length()), StandardCharsets.UTF_8);
                if (sessionId.isEmpty() || agentId.isEmpty()) {
                    try (exchange) {
                        respondJson(exchange, 400, errorBody("invalid_request",
                            "session id and agent id are required"));
                    }
                    return;
                }
                serveAsync(exchange, () -> {
                    if (Strings.CS.equals("/prompt", suffix)) {
                        backgroundTasksApi.handlePromptAgent(exchange, sessionId, agentId);
                    } else {
                        backgroundTasksApi.handleStopAgent(exchange, sessionId, agentId);
                    }
                });
                return;
            }
        }
        if (get && Strings.CS.equals("/api/shells", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            serve(exchange, () -> backgroundTasksApi.handleListShells(exchange));
            return;
        }
        if (get && Strings.CS.startsWith(targetPath, "/api/shells/")) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            String taskId = URLDecoder.decode(
                targetPath.substring("/api/shells/".length()), StandardCharsets.UTF_8);
            if (taskId.isEmpty() || Strings.CS.contains(taskId, "/")) {
                try (exchange) {
                    respondJson(exchange, 400, errorBody("invalid_request",
                        "shell task id is required"));
                }
                return;
            }
            serveAsync(exchange, () -> backgroundTasksApi.handleGetShell(exchange, taskId));
            return;
        }
        if (post && Strings.CS.startsWith(targetPath, "/api/shells/")
                && Strings.CS.endsWith(targetPath, "/stop")) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            String taskId = URLDecoder.decode(
                targetPath.substring("/api/shells/".length(),
                    targetPath.length() - "/stop".length()), StandardCharsets.UTF_8);
            if (taskId.isEmpty() || Strings.CS.contains(taskId, "/")) {
                try (exchange) {
                    respondJson(exchange, 400, errorBody("invalid_request",
                        "shell task id is required"));
                }
                return;
            }
            serveAsync(exchange, () -> backgroundTasksApi.handleStopShell(exchange, taskId));
            return;
        }
        if (get && Strings.CS.startsWith(targetPath, "/api/sessions/")
                && Strings.CS.endsWith(targetPath, "/rewind/preview")) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            String sessionId = URLDecoder.decode(
                targetPath.substring("/api/sessions/".length(),
                    targetPath.length() - "/rewind/preview".length()),
                StandardCharsets.UTF_8);
            if (sessionId.isEmpty()) {
                try (exchange) {
                    respondJson(exchange, 400, errorBody("invalid_request",
                        "session id is required"));
                }
                return;
            }
            String messageId = queryParam(exchange, "messageId");
            serveAsync(exchange, () -> rewindApi.handlePreview(exchange, sessionId, messageId));
            return;
        }
        if (post && Strings.CS.startsWith(targetPath, "/api/sessions/")
                && Strings.CS.endsWith(targetPath, "/rewind")) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            String sessionId = URLDecoder.decode(
                targetPath.substring("/api/sessions/".length(),
                    targetPath.length() - "/rewind".length()),
                StandardCharsets.UTF_8);
            if (sessionId.isEmpty()) {
                try (exchange) {
                    respondJson(exchange, 400, errorBody("invalid_request",
                        "session id is required"));
                }
                return;
            }
            serveAsync(exchange, () -> rewindApi.handleExecute(exchange, sessionId));
            return;
        }
        if (post && Strings.CS.startsWith(targetPath, "/api/sessions/") && (Strings.CS.endsWith(
            targetPath, "/rename")
                || Strings.CS.endsWith(targetPath, "/fork") || Strings.CS.endsWith(targetPath, "/archive"))) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            String suffix = Strings.CS.endsWith(targetPath, "/rename") ? "/rename"
                : Strings.CS.endsWith(targetPath, "/fork") ? "/fork" : "/archive";
            String sessionId = URLDecoder.decode(
                targetPath.substring("/api/sessions/".length(), targetPath.length() - suffix.length()),
                StandardCharsets.UTF_8);
            if (sessionId.isEmpty()) {
                try (exchange) {
                    respondJson(exchange, 400, errorBody("invalid_request",
                        "session id is required"));
                }
                return;
            }
            // Rename/fork/archive append to (or copy) a transcript on disk;
            // run the write off the exchange thread.
            serveAsync(exchange, () -> {
                switch (suffix) {
                    case "/rename" -> sessionActionsApi.handleRename(exchange, sessionId);
                    case "/fork" -> sessionActionsApi.handleFork(exchange, sessionId);
                    default -> sessionActionsApi.handleArchive(exchange, sessionId);
                }
            });
            return;
        }
        if (delete && Strings.CS.startsWith(targetPath, "/api/sessions/") && !Strings.CS.contains(
                targetPath.substring("/api/sessions/".length()), "/")) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            String sessionId = URLDecoder.decode(
                targetPath.substring("/api/sessions/".length()), StandardCharsets.UTF_8);
            if (sessionId.isEmpty()) {
                try (exchange) {
                    respondJson(exchange, 400, errorBody("invalid_request",
                        "session id is required"));
                }
                return;
            }
            // Deletion walks the session's sidecar tree; run it off the
            // exchange thread like the other mutating session actions.
            serveAsync(exchange, () -> sessionActionsApi.handleDelete(exchange, sessionId));
            return;
        }
        if (post && Strings.CS.equals("/api/permissions/respond", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            if (permissionsApi == null) {
                try (exchange) {
                    respondJson(exchange, NOT_FOUND, errorBody("not_found",
                        "permission interaction is not configured"));
                }
                return;
            }
            permissionsApi.handle(exchange);
            return;
        }
        if (post && Strings.CS.equals("/api/sudo/respond", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            if (sudoApi == null) {
                try (exchange) {
                    respondJson(exchange, NOT_FOUND, errorBody("not_found",
                        "sudo interaction is not configured"));
                }
                return;
            }
            sudoApi.handle(exchange);
            return;
        }
        if ((get || post) && Strings.CS.equals("/api/settings", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            serve(exchange, () -> {
                if (get) {
                    settingsApi.handleGet(exchange);
                } else {
                    settingsApi.handlePost(exchange);
                }
            });
            return;
        }
        if ((get || post) && Strings.CS.equals("/api/runtime/keepalive", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            serve(exchange, () -> {
                if (get) {
                    runtimeControlApi.handleGetKeepalive(exchange);
                } else {
                    runtimeControlApi.handleSetKeepalive(exchange);
                }
            });
            return;
        }
        if (post && Strings.CS.equals("/api/runtime/restart", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            serve(exchange, () -> runtimeControlApi.handleRestart(exchange));
            return;
        }
        if ((get || post) && Strings.CS.equals("/api/schedule", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            serve(exchange, () -> {
                if (get) {
                    scheduleApi.handleGet(exchange);
                } else {
                    scheduleApi.handlePost(exchange);
                }
            });
            return;
        }
        if (post && Strings.CS.startsWith(targetPath, "/api/schedule/")
                && Strings.CS.endsWith(targetPath, "/run")) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            String id = URLDecoder.decode(
                targetPath.substring("/api/schedule/".length(),
                    targetPath.length() - "/run".length()),
                StandardCharsets.UTF_8);
            serveAsync(exchange, () -> scheduleApi.handleRun(exchange, id));
            return;
        }
        if ((put || patch) && Strings.CS.startsWith(targetPath, "/api/schedule/")) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            String taskId = URLDecoder.decode(
                targetPath.substring("/api/schedule/".length()), StandardCharsets.UTF_8);
            serve(exchange, () -> scheduleApi.handleUpdate(exchange, taskId));
            return;
        }
        if (delete && Strings.CS.startsWith(targetPath, "/api/schedule/")) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            String taskId = URLDecoder.decode(
                targetPath.substring("/api/schedule/".length()), StandardCharsets.UTF_8);
            serve(exchange, () -> scheduleApi.handleDelete(exchange, taskId));
            return;
        }
        if (get && Strings.CS.equals("/api/commands", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            serve(exchange, () -> commandsApi.handleGet(exchange));
            return;
        }
        if (get && Strings.CS.equals("/api/file-suggestions", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            serve(exchange, () -> fileSuggestionsApi.handleGet(exchange));
            return;
        }
        if (Strings.CS.equals("/api/directories", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            serve(exchange, () -> directoryPickerApi.handle(exchange));
            return;
        }
        if (Strings.CS.equals("/api/projects", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            serve(exchange, () -> projectsApi.handleProjects(exchange));
            return;
        }
        if (Strings.CS.equals("/api/projects/customizations", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            serve(exchange, () -> projectsApi.handleCustomizations(exchange));
            return;
        }
        if (get && Strings.CS.equals("/api/spinner-verbs", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            serve(exchange, () -> spinnerVerbsApi.handleGet(exchange));
            return;
        }
        if (get && Strings.CS.startsWith(targetPath, "/api/session/context/")) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            String face = targetPath.substring("/api/session/context/".length());
            serve(exchange, () -> {
                switch (face) {
                    case "timeline" -> contextTimelineApi.handleTimeline(exchange);
                    case "detail" -> contextTimelineApi.handleDetail(exchange);
                    case "content" -> contextTimelineApi.handleContent(exchange);
                    case "overview" -> contextTimelineApi.handleOverview(exchange);
                    default -> respondJson(exchange, NOT_FOUND, errorBody("not_found",
                        "unknown context face: " + face));
                }
            });
            return;
        }
        if ((get || post) && Strings.CS.equals("/api/session/capabilities", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            if (get) {
                serve(exchange, () -> capabilitiesApi.handleGet(exchange));
            } else {
                serveAsync(exchange, () -> capabilitiesApi.handlePost(exchange));
            }
            return;
        }
        if ((get || post) && Strings.CS.equals("/api/session/context", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            serve(exchange, () -> {
                if (get) {
                    sessionContextApi.handleGet(exchange);
                } else {
                    sessionContextApi.handlePost(exchange);
                }
            });
            return;
        }
        if ((get || post) && Strings.CS.equals("/api/session/permission-mode", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            serve(exchange, () -> {
                if (get) {
                    permissionModeApi.handleGet(exchange);
                } else {
                    permissionModeApi.handlePost(exchange);
                }
            });
            return;
        }
        if (post && Strings.CS.equals("/api/session/cancel", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            serve(exchange, () -> sessionCancelApi.handlePost(exchange));
            return;
        }
        if (get && Strings.CS.equals("/api/session/tasks", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            serve(exchange, () -> taskBoardApi.handleGet(exchange));
            return;
        }
        if (get && Strings.CS.equals("/api/changes.summary", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            serve(exchange, () -> changesApi.handleSummary(exchange));
            return;
        }
        if (get && Strings.CS.equals("/api/changes.diff", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            serve(exchange, () -> changesApi.handleDiff(exchange));
            return;
        }
        if (get && Strings.CS.equals("/api/workspace-file", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            serve(exchange, () -> workspaceFileApi.handleGet(exchange));
            return;
        }
        if (get && Strings.CS.equals("/api/workspace-tree", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            serve(exchange, () -> workspaceFileApi.handleList(exchange));
            return;
        }
        if (post && Strings.CS.equals("/api/workspace-open", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            serve(exchange, () -> workspaceFileApi.handleOpen(exchange));
            return;
        }
        if ((get || post) && Strings.CS.equals("/api/models", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            serve(exchange, () -> {
                if (get) {
                    modelsApi.handleGet(exchange);
                } else {
                    modelsApi.handlePost(exchange);
                }
            });
            return;
        }
        if (post && Strings.CS.equals("/api/models/discover", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            serveAsync(exchange, () -> modelsApi.handleDiscover(exchange));
            return;
        }
        if (post && Strings.CS.equals("/api/models/batch", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            serve(exchange, () -> modelsApi.handleBatchSave(exchange));
            return;
        }
        if (delete && Strings.CS.startsWith(targetPath, "/api/models/")) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            String modelName = URLDecoder.decode(
                targetPath.substring("/api/models/".length()), StandardCharsets.UTF_8);
            serve(exchange, () -> modelsApi.handleDelete(exchange, modelName));
            return;
        }
        if (get && Strings.CS.equals("/api/lan-gateway", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            serve(exchange, () -> lanAdminApi.handleGetStatus(exchange));
            return;
        }
        if (post && Strings.CS.equals("/api/lan-gateway", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            lanAdminApi.handleUpdateSettings(exchange);
            return;
        }
        if (post && Strings.CS.equals("/api/lan-gateway/keys", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            serve(exchange, () -> lanAdminApi.handleCreateKey(exchange));
            return;
        }
        if ((post || patch || delete) && Strings.CS.startsWith(targetPath, "/api/lan-gateway/keys/")) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            String keyId = URLDecoder.decode(
                targetPath.substring("/api/lan-gateway/keys/".length()), StandardCharsets.UTF_8);
            if (keyId.isEmpty() || Strings.CS.contains(keyId, "/")) {
                try (exchange) {
                    respondJson(exchange, 400, errorBody("invalid_request", "key id is required"));
                }
                return;
            }
            serve(exchange, () -> {
                if (delete) {
                    lanAdminApi.handleDeleteKey(exchange, keyId);
                } else {
                    lanAdminApi.handleUpdateKey(exchange, keyId);
                }
            });
            return;
        }
        if ((get || delete) && Strings.CS.equals("/api/lan-gateway/calls", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            serve(exchange, () -> {
                if (get) {
                    lanAdminApi.handleGetCalls(exchange);
                } else {
                    lanAdminApi.handleClearCalls(exchange);
                }
            });
            return;
        }
        if (get && Strings.CS.equals("/api/cc-connect", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            serve(exchange, () -> ccConnectApi.handleGetStatus(exchange));
            return;
        }
        if (post && Strings.CS.equals("/api/cc-connect/attach", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            serve(exchange, () -> ccConnectApi.handleAttach(exchange));
            return;
        }
        if (post && Strings.CS.equals("/api/cc-connect/detach", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            serve(exchange, () -> ccConnectApi.handleDetach(exchange));
            return;
        }
        if (get && Strings.CS.equals("/api/cc-connect/logs", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            serve(exchange, () -> ccConnectApi.handleLogs(exchange));
            return;
        }
        if (post && Strings.CS.equals("/api/cc-connect/setup", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            serve(exchange, () -> ccConnectApi.handleSetup(exchange));
            return;
        }
        if (post && Strings.CS.equals("/api/cc-connect/setup/cancel", targetPath)) {
            if (rejectIfUnauthenticated(auth, exchange)) return;
            serve(exchange, () -> ccConnectApi.handleCancelSetup(exchange));
            return;
        }
        try (exchange) {
            if (!auth.authenticated(exchange)) {
                respondJson(exchange, UNAUTHORIZED, errorBody("authentication_required",
                    "Provide the launch token as Authorization: Bearer or ?token="));
                return;
            }
            respondJson(exchange, NOT_FOUND, errorBody("not_found", "Unknown route"));
        }
    }

    /** Sessions per project row by default: paged listing keeps one request's rows bounded. */
    private static final int DEFAULT_PER_PROJECT = 5;

    private void listSessions(HttpExchange exchange) throws IOException {
        drain(exchange);
        int perProjectLimit = perProjectLimitOf(exchange);
        // Off the exchange thread: the fingerprint-validated listing may block
        // on transcript reads. See {@link #serveAsync} for the close discipline
        // that keeps a failure answerable there.
        serveAsync(exchange, () -> respondJson(exchange, 200, sessionListing.sessionsBody(perProjectLimit)));
    }

    private void streamMirror(HttpExchange exchange) throws IOException {
        // The request body must be drained before the response starts, or the
        // connection cannot be reused (same keep-alive rule the smoke fake
        // server documents).
        drain(exchange);
        // No cursor (absent or unparsable Last-Event-ID) starts live at the
        // journal's current tip instead of replaying the whole ring — only an
        // explicit cursor asks for missed-frame replay.
        long lastEventId = parseLastEventId(exchange).orElseGet(mirror::latestId);
        // A cursorless client starts live, but a permission still awaiting an
        // answer must not be lost to that: it is replayed so the dialog appears.
        boolean freshClient = parseLastEventId(exchange).isEmpty();
        // Optional session filter: frames from every followed session stream
        // by default; ?session_id= narrows the stream to one conversation view.
        String sessionFilter = queryParam(exchange, "session_id");
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        OutputStream output = exchange.getResponseBody();
        MirrorSubscriptions subscriptions = new MirrorSubscriptions();
        SseConnection connection = SseConnection.start(
            output, config.laneCapacity(), config.keepAlive(), subscriptions::close);
        Iterable<MirrorHub.MirrorFrame> replay = freshClient
            ? mirror.pendingPermissionFrames(sessionFilter)
            : mirror.replayAfter(lastEventId);
        for (MirrorHub.MirrorFrame frame : replay) {
            if (sessionFilter == null
                    || Strings.CS.contains(frame.data(), "\"session_id\":\"" + sessionFilter + "\"")) {
                connection.offer(SseFrameWriter.event(
                    frame.event(), Long.toString(frame.id()), frame.data()));
            }
        }
        subscriptions.set(mirror.subscribe(frame -> {
            if (sessionFilter == null
                    || Strings.CS.contains(frame.data(), "\"session_id\":\"" + sessionFilter + "\"")) {
                // A transient live-stream frame carries no id line, so the
                // client's Last-Event-ID stays on the last journaled frame.
                connection.offer(SseFrameWriter.event(frame.event(),
                    frame.journaled() ? Long.toString(frame.id()) : "", frame.data()));
            }
        }));
        // A private, unjournaled marker: once a caller sees it, the live
        // subscription above is guaranteed registered, so no frame published
        // from this point on can be missed. Callers that raced ahead of the
        // subscription (e.g. publishing right after opening the stream) can
        // wait on this instead of depending on ring history to prove liveness.
        connection.offer(SseFrameWriter.event(
            "connection.ready", Long.toString(lastEventId), "{}"));
    }

    /** One query parameter's decoded value, or null when absent. */
    private static String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (!Strings.CS.equals(name, key)) continue;
            String raw = eq < 0 ? "" : pair.substring(eq + 1);
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        }
        return null;
    }

    /**
     * The {@code ?per_project=} page size: 1..1000 rows per project, the
     * default {@link #DEFAULT_PER_PROJECT} when absent or unparsable, and
     * unlimited ({@code 0}) only on the explicit {@code all}.
     */
    private static int perProjectLimitOf(HttpExchange exchange) {
        String raw = queryParam(exchange, "per_project");
        if (raw == null) return DEFAULT_PER_PROJECT;
        if (Strings.CS.equals("all", raw.strip())) return 0;
        try {
            int parsed = Integer.parseInt(raw.strip());
            if (parsed < 1) return 0;
            return Math.min(parsed, 1_000);
        } catch (NumberFormatException _) {
            return DEFAULT_PER_PROJECT;
        }
    }

    /** Releases the mirror subscription when the connection lane closes. */
    private static final class MirrorSubscriptions {
        private volatile AutoCloseable subscription;

        void set(AutoCloseable subscription) {
            this.subscription = subscription;
        }

        void close() {
            AutoCloseable current = subscription;
            subscription = null;
            if (current != null) {
                try { current.close(); } catch (Exception _) {}
            }
        }
    }

    private static OptionalLong parseLastEventId(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Last-Event-ID");
        if (StringUtils.isEmpty(header)) return OptionalLong.empty();
        try {
            return OptionalLong.of(Long.parseLong(header.trim()));
        } catch (NumberFormatException _) {
            return OptionalLong.empty();
        }
    }


    private static void drain(HttpExchange exchange) throws IOException {
        try (var body = exchange.getRequestBody()) {
            body.readAllBytes();
        }
    }
}
