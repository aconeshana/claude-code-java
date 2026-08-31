package com.claudecode.sdk;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.util.UuidUtils;
import com.claudecode.session.SessionInfo;
import com.claudecode.session.SessionListingService;
import com.claudecode.session.SessionOperationsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Public Java Agent SDK entry point.
{@code tool},
 * {@code createSdkMcpServer}, and {@code query} exports.</li></ul>
 */
public final class ClaudeAgentSdk {
    private static final ExecutorService SESSION_IO = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("claude-sdk-session-", 0).factory());

    private ClaudeAgentSdk() {}

    public static SdkMcpToolDefinition tool(
            String name, String description, JsonNode inputSchema,
            SdkMcpToolHandler handler, SdkMcpToolExtras extras) {
        ObjectNode meta = JsonUtils.getMapper().createObjectNode();
        if (extras != null && extras.searchHint() != null) {
            meta.put("anthropic/searchHint", extras.searchHint());
        }
        if (extras != null && extras.alwaysLoad()) meta.put("anthropic/alwaysLoad", true);
        return new SdkMcpToolDefinition(name, description, inputSchema, handler,
            extras == null ? null : extras.annotations(), meta.isEmpty() ? null : meta);
    }

    public static McpSdkServerConfigWithInstance createSdkMcpServer(
            CreateSdkMcpServerOptions options) {
        SdkMcpServer server = new SdkMcpServer(options);
        return new McpSdkServerConfigWithInstance(options.name(), server);
    }

    public static SdkQuery query(String prompt, QueryOptions options) {
        return DefaultSdkQuery.start(prompt, null, options);
    }

    public static SdkQuery query(Iterable<SDKUserMessage> prompt, QueryOptions options) {
        return DefaultSdkQuery.start(null, prompt, options);
    }

    public static ExtendedSdkQuery queryExtended(String prompt, QueryOptions options) {
        return queryExtended(QueryRequest.prompt(prompt, options));
    }

    public static ExtendedSdkQuery queryExtended(Iterable<SDKUserMessage> prompt,
                                                  QueryOptions options) {
        return queryExtended(QueryRequest.stream(prompt, options));
    }

    public static SdkQuery query(QueryRequest request) {
        if (request == null) throw new IllegalArgumentException("request is required");
        return request.prompt() != null
            ? query(request.prompt(), request.options())
            : query(request.messages(), request.options());
    }

    public static ExtendedSdkQuery queryExtended(QueryRequest request) {
        if (request == null) throw new IllegalArgumentException("request is required");
        return DefaultSdkQuery.start(request.prompt(), request.messages(), request.options());
    }

    public static CompletableFuture<List<SessionMessage>> getSessionMessages(
            String sessionId, GetSessionMessagesOptions requested) {
        GetSessionMessagesOptions options = requested == null
            ? GetSessionMessagesOptions.defaults() : requested;
        return async(() -> {
            SessionOperationsService operations = operations();
            List<SessionOperationsService.HistoryMessage> messages;
            if (options.sessionStore() == null) {
                messages = operations.getSessionMessages(sessionId, options.dir(), options.limit(),
                    options.offset(), options.includeSystem());
            } else {
                if (!UuidUtils.isValid(sessionId)) return List.of();
                List<JsonNode> entries = options.sessionStore().load(storeKey(options.dir(), sessionId));
                messages = operations.getSessionMessages(sessionId, entries, options.limit(),
                    options.offset(), options.includeSystem());
            }
            return messages.stream().map(ClaudeAgentSdk::sdkMessage).toList();
        });
    }

    public static CompletableFuture<Optional<SDKSessionInfo>> getSessionInfo(
            String sessionId, GetSessionInfoOptions requested) {
        GetSessionInfoOptions options = requested == null
            ? new GetSessionInfoOptions(null, null) : requested;
        return async(() -> {
            Optional<SessionInfo> info;
            if (options.sessionStore() == null) {
                info = operations().getSessionInfo(sessionId, options.dir());
            } else {
                if (!UuidUtils.isValid(sessionId)) return Optional.empty();
                List<JsonNode> entries = options.sessionStore().load(storeKey(options.dir(), sessionId));
                info = operations().getSessionInfo(sessionId, entries);
            }
            return info.map(ClaudeAgentSdk::sdkInfo);
        });
    }

    public static CompletableFuture<Void> renameSession(String sessionId, String title,
                                                         SessionMutationOptions requested) {
        SessionMutationOptions options = requested == null
            ? new SessionMutationOptions(null, null) : requested;
        return async(() -> {
            if (options.sessionStore() == null) {
                operations().renameSession(sessionId, title, options.dir());
            } else {
                requireUuid(sessionId);
                String normalized = title == null ? "" : title.trim();
                if (normalized.isEmpty()) throw new IllegalArgumentException("title must be non-empty");
                ObjectNode entry = metadata("custom-title", sessionId);
                entry.put("customTitle", normalized);
                options.sessionStore().append(storeKey(options.dir(), sessionId), List.of(entry));
            }
            return null;
        });
    }

    public static CompletableFuture<Void> tagSession(String sessionId, String tag,
                                                      SessionMutationOptions requested) {
        SessionMutationOptions options = requested == null
            ? new SessionMutationOptions(null, null) : requested;
        return async(() -> {
            if (options.sessionStore() == null) {
                operations().tagSession(sessionId, tag, options.dir());
            } else {
                requireUuid(sessionId);
                String normalized = tag;
                if (normalized != null) {
                    normalized = normalized.trim();
                    if (normalized.isEmpty()) {
                        throw new IllegalArgumentException("tag must be non-empty (use null to clear)");
                    }
                }
                ObjectNode entry = metadata("tag", sessionId);
                entry.put("tag", normalized == null ? "" : normalized);
                options.sessionStore().append(storeKey(options.dir(), sessionId), List.of(entry));
            }
            return null;
        });
    }

    public static CompletableFuture<ForkSessionResult> forkSession(String sessionId,
                                                                    ForkSessionOptions requested) {
        ForkSessionOptions options = requested == null ? ForkSessionOptions.defaults() : requested;
        return async(() -> {
            SessionOperationsService.ForkedSession fork;
            if (options.sessionStore() == null) {
                fork = operations().forkSession(sessionId, options.dir(), options.upToMessageId(),
                    options.title());
            } else {
                requireUuid(sessionId);
                List<JsonNode> source = options.sessionStore().load(storeKey(options.dir(), sessionId));
                if (source == null || source.isEmpty()) {
                    throw new SdkSessionException("Session " + sessionId + " not found");
                }
                fork = operations().forkSession(sessionId, source, options.upToMessageId(), options.title());
                options.sessionStore().append(storeKey(options.dir(), fork.sessionId()), fork.entries());
            }
            return new ForkSessionResult(fork.sessionId());
        });
    }

    public static CompletableFuture<List<SDKSessionInfo>> listSessions(ListSessionsOptions requested) {
        ListSessionsOptions options = requested == null ? ListSessionsOptions.defaults() : requested;
        return async(() -> options.sessionStore() == null
            ? listLocal(options) : listStore(options));
    }

    private static List<SDKSessionInfo> listLocal(ListSessionsOptions options) {
        var local = new com.claudecode.session.ListSessionsOptions(options.dir(), options.limit(),
            options.offset(), options.includeWorktrees(), options.includeProgrammatic());
        return new SessionListingService(ClaudePaths.CLAUDE_HOME).listSessions(local).stream()
            .map(ClaudeAgentSdk::sdkInfo).toList();
    }

    private static List<SDKSessionInfo> listStore(ListSessionsOptions options) throws Exception {
        String projectKey = SessionStoreProjectKey.fromDirectory(options.dir());
        List<StoredSession> stored = new ArrayList<>(options.sessionStore().listSessions(projectKey));
        stored.sort(Comparator.comparingLong(StoredSession::mtime).reversed()
            .thenComparing(StoredSession::sessionId, Comparator.reverseOrder()));
        List<SDKSessionInfo> valid = new ArrayList<>();
        for (StoredSession item : stored) {
            try {
                List<JsonNode> entries = options.sessionStore().load(
                    new SessionStoreKey(projectKey, item.sessionId()));
                operations().getSessionInfo(item.sessionId(), entries).map(ClaudeAgentSdk::sdkInfo)
                    .map(info -> new SDKSessionInfo(info.sessionId(), info.summary(), item.mtime(),
                        info.fileSize(), info.customTitle(), info.firstPrompt(), info.gitBranch(),
                        info.cwd(), info.tag(), info.createdAt())).ifPresent(valid::add);
            } catch (Exception _) {
                valid.add(new SDKSessionInfo(item.sessionId(), "", item.mtime(), null,
                    null, null, null, null, null, null));
            }
        }
        int start = Math.min(Math.max(0, options.offset() == null ? 0 : options.offset()), valid.size());
        int end = options.limit() != null && options.limit() > 0
            ? Math.min(valid.size(), start + options.limit()) : valid.size();
        return List.copyOf(valid.subList(start, end));
    }

    private static SessionOperationsService operations() {
        return new SessionOperationsService(ClaudePaths.CLAUDE_HOME);
    }

    private static SessionStoreKey storeKey(String dir, String sessionId) {
        return new SessionStoreKey(SessionStoreProjectKey.fromDirectory(dir), sessionId);
    }

    private static ObjectNode metadata(String type, String sessionId) {
        return JsonUtils.getMapper().createObjectNode().put("type", type)
            .put("sessionId", sessionId).put("uuid", UUID.randomUUID().toString())
            .put("timestamp", Instant.now().toString());
    }

    private static void requireUuid(String sessionId) {
        if (!UuidUtils.isValid(sessionId)) {
            throw new IllegalArgumentException("Invalid sessionId: " + sessionId);
        }
    }

    private static SessionMessage sdkMessage(SessionOperationsService.HistoryMessage message) {
        return new SessionMessage(message.type(), message.uuid(), message.sessionId(), message.message(),
            message.parentToolUseId(), message.timestamp());
    }

    private static SDKSessionInfo sdkInfo(SessionInfo info) {
        return new SDKSessionInfo(info.id(), info.summary(), info.lastModified(),
            info.fileSize() < 0 ? null : info.fileSize(), info.customTitle(), info.firstPrompt(),
            info.gitBranch(), info.cwd(), info.tag(), info.createdAt());
    }

    private static <T> CompletableFuture<T> async(ThrowingSupplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try { return supplier.get(); }
            catch (RuntimeException failure) { throw failure; }
            catch (Exception failure) { throw new SdkSessionException(failure); }
        }, SESSION_IO);
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> { T get() throws Exception; }
}
