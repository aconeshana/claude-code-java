package com.claudecode.session;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.engine.TranscriptSink;
import com.claudecode.core.error.ErrorUtils;
import com.claudecode.core.metrics.SessionMetricsEvent;
import com.claudecode.core.engine.ToolResultBudget;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.plan.PlanSlugRegistry;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.git.GitUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.BiConsumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fire-and-forget async transcript writer.
 */
public class TranscriptRecorder implements TranscriptSink {

    private static final Logger log = LoggerFactory.getLogger(TranscriptRecorder.class);

    private final SessionManager sessionManager;
    private final SessionStorage sessionStorage;
    private final String cwd;
    private final boolean isSidechain;
    private final String agentId;
    private final String attributionAgent;
    private final Path explicitTranscriptFile;
    private final Supplier<String> gitBranchSupplier;
    private volatile Function<String, TeamInfo> teamInfoResolver = _ -> TeamInfo.EMPTY;


    private static final ConcurrentMap<String, Set<String>> recordedUuids = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, ReentrantLock> fileLocks = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, CompletableFuture<Void>> writeTails = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, String> cachedLastPrompts = new ConcurrentHashMap<>();
    /** Textual checkpoint most recently materialized for each concrete session file. */
    private static final ConcurrentMap<String, String> materializedLastPrompts =
        new ConcurrentHashMap<>();
    private static final Executor VIRTUAL_EXECUTOR = command ->
        Thread.ofVirtual().name("transcript-writer").start(command);


    private static final ConcurrentMap<String, String> currentParent = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, String> lastAssistantUuid = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, String> sessionSlugs = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, String> currentPromptIds = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, String> currentPromptSources = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, String> currentModes = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, String> currentPermissionModes =
        new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, LeafState> currentLeaves = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Set<String>> replToolUseIds =
        new ConcurrentHashMap<>();
    private static final Set<String> preparedManualCompactMetadata = ConcurrentHashMap.newKeySet();
    private static final Set<String> resumedSessionFiles = ConcurrentHashMap.newKeySet();
    private static final Set<String> freshlyMaterializedRestoredModes =
        ConcurrentHashMap.newKeySet();




    private final Object activeSessionMetadataLock = new Object();
    private String activeMetadataSessionId;
    private String cachedSessionTitle;
    private String cachedSessionAgentName;
    private String cachedAgentSetting;
    private boolean activeSessionMetadataMaterialized;

    private record LeafState(String uuid, Instant timestamp) {}

    public TranscriptRecorder(SessionManager sessionManager, SessionStorage sessionStorage,
                               String cwd, boolean isSidechain) {
        this(sessionManager, sessionStorage, cwd, isSidechain, null);
    }


    public TranscriptRecorder(SessionManager sessionManager, SessionStorage sessionStorage,
                               String cwd, boolean isSidechain, String agentId) {
        this(sessionManager, sessionStorage, cwd, isSidechain, agentId, null, null);
    }

/**
     * Routes a grouped sub-agent transcript to an explicit.
     */
    public TranscriptRecorder(SessionManager sessionManager, SessionStorage sessionStorage,
                              String cwd, boolean isSidechain, String agentId,
                              Path explicitTranscriptFile) {
        this(sessionManager, sessionStorage, cwd, isSidechain, agentId,
            explicitTranscriptFile, null, null);
    }

/**
     * Routes a grouped sub-agent transcript and stamps its.
     */
    public TranscriptRecorder(SessionManager sessionManager, SessionStorage sessionStorage,
                              String cwd, boolean isSidechain, String agentId,
                              Path explicitTranscriptFile, String attributionAgent) {
        this(sessionManager, sessionStorage, cwd, isSidechain, agentId,
            explicitTranscriptFile, attributionAgent, null);
    }

    /** Test seam for deterministic branch stamping; production resolves live git state. */
    TranscriptRecorder(SessionManager sessionManager, SessionStorage sessionStorage,
                       String cwd, boolean isSidechain, String agentId,
                       Supplier<String> gitBranchSupplier) {
        this(sessionManager, sessionStorage, cwd, isSidechain, agentId, null,
            null, gitBranchSupplier);
    }

    private TranscriptRecorder(SessionManager sessionManager, SessionStorage sessionStorage,
                       String cwd, boolean isSidechain, String agentId,
                       Path explicitTranscriptFile,
                       String attributionAgent,
                       Supplier<String> gitBranchSupplier) {
        this.sessionManager = sessionManager;
        this.sessionStorage = sessionStorage;
        this.cwd = cwd != null ? cwd : System.getProperty("user.dir");
        this.isSidechain = isSidechain;
        this.agentId = agentId;
        this.attributionAgent = attributionAgent;
        this.explicitTranscriptFile = explicitTranscriptFile;
        this.gitBranchSupplier = gitBranchSupplier != null
            ? gitBranchSupplier
            : () -> GitUtils.currentBranch(Path.of(this.cwd));
    }

    public TranscriptRecorder(SessionManager sessionManager, SessionStorage sessionStorage) {
        this(sessionManager, sessionStorage, System.getProperty("user.dir"), false);
    }

    public TranscriptRecorder(SessionManager sessionManager) {
        this(sessionManager, new SessionStorage());
    }

    public TranscriptRecorder() {
        this(new SessionManager(System.getProperty("user.dir")), new SessionStorage());
    }

    /**
     * Supplies the active swarm identity for each logical session. The value is
     * resolved synchronously in {@link #record} so later team changes cannot
     * alter an already queued transcript row.
     */
    public void setTeamInfoResolver(Function<String, TeamInfo> resolver) {
        teamInfoResolver = resolver == null ? _ -> TeamInfo.EMPTY : resolver;
    }

    /**
     * Asynchronously records a message to the session transcript.
     * <p>
     * The write is performed on a virtual thread with file locking for concurrent safety.
     * The caller does not wait for the write to complete. Errors are logged but not propagated.
     */
    public void recordTranscript(String sessionId, Message message) {
        record(sessionId, message);
    }

    @Override
    public void record(String sessionId, Message message) {
        if (!TranscriptMessageCleaner.isLoggableMessage(message)) return;
        Path sessionFile = sessionFile(sessionId);
        String key = sessionFile.toString();
        Set<String> sessionReplIds = replToolUseIds.computeIfAbsent(
            key, _ -> ConcurrentHashMap.newKeySet());
        List<Message> cleaned = TranscriptMessageCleaner.cleanMessagesForLogging(
            List.of(message), sessionReplIds, SessionStorage.USER_TYPE);
        if (cleaned.isEmpty()) return;
        Message persistedMessage = cleaned.getFirst();

        prepareSessionMaterialization(sessionId);
        updateCurrentLeaf(key, persistedMessage);
        if (isChainParticipant(persistedMessage)) {
            // Once recovery or any new turn advances the chain, the restored
            // leaf is no longer the only resumable checkpoint. Keeping this
            // marker would make compact/shutdown append a spurious leaf-only
            // last-prompt row for the previous process.
            resumedSessionFiles.remove(key);
        }
        String promptId = persistedMessage instanceof UserMessage ? currentPromptIds.get(key) : null;
        String promptSource = persistedMessage instanceof UserMessage user
                && !user.isMeta() && !isCommandMetadataUser(user)
            ? currentPromptSources.get(key) : null;

        // asynchronous write transaction. Freezing it here prevents a later
        // ExitPlanMode result from retroactively stamping already-queued rows.
        String slug = sessionSlug(sessionId, sessionFile);
        TeamInfo teamInfo = teamInfoResolver.apply(sessionId);
        if (teamInfo == null) teamInfo = TeamInfo.EMPTY;
        TeamInfo frozenTeamInfo = teamInfo;
        enqueue(sessionFile, () -> writeOne(
            sessionId, persistedMessage, sessionFile, promptId, promptSource, slug,
            frozenTeamInfo));
    }

    @Override
    public void rewindConversation(String sessionId, List<Message> retainedMessages) {
        if (StringUtils.isBlank(sessionId)) return;
        Path sessionFile = sessionFile(sessionId);
        List<Message> retained = retainedMessages == null
            ? List.of() : List.copyOf(retainedMessages);
        enqueue(sessionFile, () -> rebaseConversationChain(sessionFile, retained));
    }

    @Override
    public void remove(String sessionId, String messageUuid) {
        if (StringUtils.isBlank(sessionId) || StringUtils.isBlank(messageUuid)) return;
        Path sessionFile = sessionFile(sessionId);
        String key = sessionFile.toString();
        enqueue(sessionFile, () -> {
            try {
                SessionFileLock.withLock(sessionFile,
                    () -> sessionStorage.removeTranscriptMessage(sessionFile, messageUuid));
            } catch (Exception failure) {
                log.debug("Failed to retract transcript message {} for session {}: {}",
                    messageUuid, sessionId, failure.getMessage());
            } finally {
                // The removed row may have been the cached chain/assistant tail.
                // Force the next queued write to reseed from the actual file.
                recordedUuids.remove(key);
                currentParent.remove(key);
                lastAssistantUuid.remove(key);
                currentLeaves.remove(key);
            }
        });
    }

    @Override
    @Explanation("Persists Java HUD telemetry as non-chain JSONL rows ignored by transcript loaders")
    public void recordSessionMetrics(String sessionId, SessionMetricsEvent event) {
        if (event == null || StringUtils.isBlank(sessionId)) return;
        Path transcript = sessionFile(sessionId);
        Path file = SessionMetricsFiles.useSidecar()
            ? SessionMetricsFiles.sidecar(transcript) : transcript;
        ObjectNode entry = JsonUtils.getMapper().createObjectNode();
        entry.put("type", SessionMetricsEvent.TRANSCRIPT_TYPE);
        entry.put("schemaVersion", event.schemaVersion());
        entry.put("seq", event.seq());
        entry.put("time", event.time());
        entry.put("sessionId", sessionId);
        entry.put("event", event.kind().wireName());
        if (event.turnId() != null) entry.put("turnId", event.turnId());
        if (event.turn() > 0) entry.put("turn", event.turn());
        if (event.step() > 0) entry.put("step", event.step());
        if (event.callId() != null) entry.put("callId", event.callId());
        if (event.kind() == SessionMetricsEvent.Kind.ASSISTANT_USAGE) {
            entry.put("uncachedInputTokens", event.uncachedInputTokens());
            entry.put("outputTokens", event.outputTokens());
            entry.put("cacheWriteTokens", event.cacheWriteTokens());
            entry.put("cacheReadTokens", event.cacheReadTokens());
        }
        if (event.synthetic()) entry.put("synthetic", true);
        enqueue(file, () -> writeCustom(sessionId, file, entry));
    }

    @Override
    public void recordPromptStart(String sessionId, String promptSource) {
        String key = sessionFile(sessionId).toString();
        currentPromptIds.put(key, UUID.randomUUID().toString());
        if (StringUtils.isNotBlank(promptSource)) {
            currentPromptSources.put(key, promptSource);
        } else {
            currentPromptSources.remove(key);
        }
    }

    @Override
    public void recordQueueOperation(String sessionId, String operation, String content) {
        prepareSessionMaterialization(sessionId);
        Path sessionFile = sessionFile(sessionId);
        ObjectNode entry = JsonUtils.getMapper().createObjectNode();
        entry.put("type", "queue-operation");
        entry.put("operation", operation);
        entry.put("timestamp", Instant.now().toString());
        entry.put("sessionId", sessionId);
        if (content != null) {
            entry.put("content", content);
        }
        enqueue(sessionFile, () -> writeCustom(sessionId, sessionFile, entry));
    }


    public void recordForkContextRef(
            String sessionId,
            String forkAgentId,
            String parentSessionId,
            String parentLastUuid,
            int contextLength) {
        if (StringUtils.isBlank(sessionId) || StringUtils.isBlank(forkAgentId)
                || StringUtils.isBlank(parentSessionId) || StringUtils.isBlank(parentLastUuid)) {
            return;
        }
        Path sessionFile = sessionFile(sessionId);
        ObjectNode entry = JsonUtils.getMapper().createObjectNode();
        entry.put("type", "fork-context-ref");
        entry.put("agentId", forkAgentId);
        entry.put("parentSessionId", parentSessionId);
        entry.put("parentLastUuid", parentLastUuid);
        entry.put("contextLength", Math.max(0, contextLength));
        enqueue(sessionFile, () -> writeCustom(sessionId, sessionFile, entry));
    }

    /** Returns the active headless queue turn's prompt id, if one exists. */
    public String currentPromptId(String sessionId) {
        return currentPromptIds.get(sessionFile(sessionId).toString());
    }

    /** Restores the lazy plan slug before resumed-session plan attachments run. */
    public void restoreSessionSlug(String sessionId) {
        Path file = sessionFile(sessionId);
        sessionStorage.readSessionSlug(file).ifPresent(slug -> {
            sessionSlugs.put(file.toString(), slug);
            PlanSlugRegistry.set(sessionId, slug);
        });
    }


    public void restoreSessionMetadata(String sessionId, List<Message> messages) {
        if (StringUtils.isBlank(sessionId)) return;
        Path file = sessionFile(sessionId);
        String key = file.toString();
        recordedUuids.computeIfAbsent(key, _ -> seedState(file));

        LeafState restoredLeaf = lastChainLeaf(messages);
        if (restoredLeaf != null) {
            currentLeaves.put(key, restoredLeaf);
            resumedSessionFiles.add(key);
        }

        // Restore a fallback only when the source JSONL has no last-prompt
        // row. Existing metadata remains authoritative on disk and must not
        // be revived into this process's cache, or compact/shutdown will
        // duplicate it.
        if (sessionStorage.scanMetadata(file).lastPrompt().isEmpty()) {
            String rendered = renderLastPrompt(firstMeaningfulUserPrompt(messages));
            if (StringUtils.isNotBlank(rendered)) {
                enqueueLastPrompt(sessionId, rendered, false);
            }
        }
    }


    public void cacheSessionTitle(String title) {
        String normalized = StringUtils.trimToNull(title);
        synchronized (activeSessionMetadataLock) {
            cachedSessionTitle = normalized;
            cachedSessionAgentName = normalized;
        }
    }

    /** Persists a SessionStart hook title against the active logical session. */
    public void recordSessionTitle(String sessionId, String title) {
        String normalized = StringUtils.trimToNull(title);
        if (StringUtils.isBlank(sessionId) || normalized == null) return;
        synchronized (activeSessionMetadataLock) {
            cachedSessionTitle = normalized;
        }
        enqueueMetadata(sessionId, sessionFile(sessionId),
            "custom-title", "customTitle", normalized);
    }

    /** Stages the startup {@code --agent} row until the final session identity is known. */
    public void cacheAgentSetting(String agentSetting) {
        synchronized (activeSessionMetadataLock) {
            cachedAgentSetting = StringUtils.trimToNull(agentSetting);
        }
    }

    /**
     * Binds launch metadata to the final session id. Restored disk values only
     * fill missing caches, preserving an explicit CLI name. A resumed session
     * materializes the effective values before startup routing can exit.
     */
    public void activateSessionMetadata(String sessionId, boolean restored) {
        if (StringUtils.isBlank(sessionId)) return;
        Path sessionFile = sessionFile(sessionId);
        SessionStorage.MetadataSnapshot restoredMetadata = restored
            ? sessionStorage.scanMetadata(sessionFile)
            : SessionStorage.MetadataSnapshot.empty();
        boolean materializeNow;
        synchronized (activeSessionMetadataLock) {
            boolean alreadyMaterialized = Strings.CS.equals(
                activeMetadataSessionId, sessionId) && activeSessionMetadataMaterialized;
            activeMetadataSessionId = sessionId;
            activeSessionMetadataMaterialized = alreadyMaterialized;
            if (cachedSessionTitle == null) {
                cachedSessionTitle = restoredMetadata.customTitle().orElse(null);
            }
            if (cachedSessionAgentName == null) {
                cachedSessionAgentName = restoredMetadata.agentName().orElse(null);
            }
            materializeNow = restored && !alreadyMaterialized;
        }
        if (materializeNow) {
            prepareSessionMaterialization(sessionId);
        }
        if (restored) {
            awaitPendingWrites(sessionId, 5_000);
        }
    }

    @Override
    public void prepareSessionMaterialization(String sessionId) {
        if (StringUtils.isBlank(sessionId)) return;
        String title;
        String agentName;
        String agentSetting;
        synchronized (activeSessionMetadataLock) {
            if (activeMetadataSessionId == null) {
                activeMetadataSessionId = sessionId;
            } else if (!Strings.CS.equals(activeMetadataSessionId, sessionId)) {
                clearActiveSessionMetadata();
                activeMetadataSessionId = sessionId;
            }
            if (activeSessionMetadataMaterialized) return;
            activeSessionMetadataMaterialized = true;
            title = cachedSessionTitle;
            agentName = cachedSessionAgentName;
            agentSetting = cachedAgentSetting;
        }
        Path sessionFile = sessionFile(sessionId);
        if (StringUtils.isNotBlank(title)) {
            enqueueMetadata(sessionId, sessionFile, "custom-title", "customTitle", title);
        }
        if (StringUtils.isNotBlank(agentName)) {
            enqueueMetadata(sessionId, sessionFile, "agent-name", "agentName", agentName);
        }
        if (StringUtils.isNotBlank(agentSetting)) {
            enqueueMetadata(sessionId, sessionFile, "agent-setting", "agentSetting", agentSetting);
        }
    }

    @Override
    public void recordLastPrompt(String sessionId, String prompt) {
        String key = sessionFile(sessionId).toString();
        resumedSessionFiles.remove(key);
        String rendered = renderLastPrompt(prompt);
        if (rendered != null) materializedLastPrompts.put(key, rendered);
        enqueueLastPrompt(sessionId, prompt, true);
    }

    @Override
    public void recordQueriedCommandLastPrompt(String sessionId, String prompt) {
        resumedSessionFiles.remove(sessionFile(sessionId).toString());
        enqueueLastPrompt(sessionId, prompt, true, false);
    }

    @Override
    public void recordPreCompactLastPrompt(String sessionId, String prompt) {
        enqueueLastPrompt(sessionId, prompt, false);
    }

    @Override
    public void prepareManualCompactMetadata(String sessionId) {
        prepareManualCompactMetadata(sessionId, null);
    }

    @Override
    public void prepareManualCompactMetadata(String sessionId, String commandMessageId) {
        if (StringUtils.isBlank(sessionId)) return;
        Path sessionFile = sessionFile(sessionId);
        String key = sessionFile.toString();
        // This is the pre-compact metadata checkpoint. Freeze the current leaf
        // at scheduling time: compact summary/attachment records are created
        // immediately afterward and may advance currentSessionLeafUuid before

        // anchored to the completed pre-compact turn, then writes a second
        // last-prompt at EOF using the post-compact leaf.
        if (cachedLastPrompts.containsKey(key)) {
            enqueueLastPrompt(sessionId, cachedLastPrompts.get(key), false);
        }
        enqueue(sessionFile, () -> reappendCompactMetadata(
            sessionId, sessionFile, commandMessageId));
        preparedManualCompactMetadata.add(key);
        recordPromptStart(sessionId, "typed");
    }

    @Override
    public void prepareAutoCompactMetadata(String sessionId, String currentPrompt) {
        if (StringUtils.isBlank(sessionId)) return;
        Path sessionFile = sessionFile(sessionId);
        String key = sessionFile.toString();
        enqueueLastPrompt(sessionId, currentPrompt, false);
        enqueue(sessionFile, () -> reappendCompactMetadata(
            sessionId, sessionFile, null, false, false));
        preparedManualCompactMetadata.add(key);
    }

    private void enqueueLastPrompt(String sessionId, String prompt, boolean endPromptIdentity) {
        enqueueLastPrompt(sessionId, prompt, endPromptIdentity, true);
    }

    private void enqueueLastPrompt(String sessionId, String prompt, boolean endPromptIdentity,
                                   boolean suppressSlashText) {
        Path sessionFile = sessionFile(sessionId);
        String renderedPrompt = renderLastPrompt(prompt);
        if (renderedPrompt != null && (StringUtils.isBlank(renderedPrompt)
                || (suppressSlashText && Strings.CS.startsWith(renderedPrompt, "/")))) {
            renderedPrompt = null;
        }
        LeafState leaf = currentLeaves.get(sessionFile.toString());
        if (renderedPrompt != null || leaf != null) {
            String promptValue = renderedPrompt;
            String leafUuid = leaf == null ? null : leaf.uuid();
            enqueue(sessionFile, () -> {
                LeafState effectiveLeaf = endPromptIdentity
                    ? currentLeaves.get(sessionFile.toString()) : null;
                String effectiveLeafUuid = effectiveLeaf != null
                    ? effectiveLeaf.uuid() : leafUuid;
                ObjectNode entry = JsonUtils.getMapper().createObjectNode();
                entry.put("type", "last-prompt");
                if (promptValue != null) entry.put("lastPrompt", promptValue);
                if (effectiveLeafUuid != null) entry.put("leafUuid", effectiveLeafUuid);
                entry.put("sessionId", sessionId);
                writeCustom(sessionId, sessionFile, entry);
            });
        }
        if (endPromptIdentity) {
            cachedLastPrompts.remove(sessionFile.toString());
            currentPromptIds.remove(sessionFile.toString());
            currentPromptSources.remove(sessionFile.toString());
        }
    }

    @Override
    public void cacheLastPrompt(String sessionId, String prompt) {
        Path sessionFile = sessionFile(sessionId);
        if (prompt == null) {
            cachedLastPrompts.remove(sessionFile.toString());
        } else {
            cachedLastPrompts.put(sessionFile.toString(), prompt);
        }
        resumedSessionFiles.remove(sessionFile.toString());
        currentPromptIds.remove(sessionFile.toString());
        currentPromptSources.remove(sessionFile.toString());
    }

    @Override
    public void flushCachedLastPrompt(String sessionId) {
        Path sessionFile = sessionFile(sessionId);
        String key = sessionFile.toString();
        String prompt = cachedLastPrompts.remove(key);
        boolean resumedLeafOnly = resumedSessionFiles.remove(key);
        if (prompt != null && Strings.CS.equals(
                renderLastPrompt(prompt), materializedLastPrompts.get(key))) {
            prompt = null;
        }
        if (prompt == null && !resumedLeafOnly) return;
        enqueueLastPrompt(sessionId, prompt, true);
    }

    private static void updateCurrentLeaf(String key, Message message) {
        if (message == null || message.uuid() == null || !isChainParticipant(message)) return;
        Instant timestamp = message.timestamp().orElseGet(Instant::now);
        // currentSessionLeafUuid follows logical insertion order, not message
        // timestamps. Post-compact attachments are created before the local
        // command rows but appended after them; their older timestamps must
        // not prevent the resumable leaf from advancing to the actual tail.
        currentLeaves.put(key, new LeafState(message.uuid(), timestamp));
    }

    private static String renderLastPrompt(String prompt) {
        if (prompt == null) {
            return null;
        }
        String flat = prompt.replace('\n', ' ').strip();
        if (flat.length() <= 200) {
            return flat;
        }
        return flat.substring(0, 200).strip() + "…";
    }

    private static LeafState lastChainLeaf(List<Message> messages) {
        if (messages == null) return null;
        for (int index = messages.size() - 1; index >= 0; index--) {
            Message message = messages.get(index);
            if (message == null || message.uuid() == null || !isChainParticipant(message)) continue;
            return new LeafState(
                message.uuid(), message.timestamp().orElse(Instant.EPOCH));
        }
        return null;
    }

    private static String firstMeaningfulUserPrompt(List<Message> messages) {
        if (messages == null) return null;
        for (Message message : messages) {
            if (!(message instanceof UserMessage user)
                    || user.isMeta() || user.isCompactSummary()
                    || user.message() == null) {
                continue;
            }
            MessageContent content = user.message();
            if (StringUtils.isNotBlank(content.text())) {
                return content.text();
            }
            if (content.blocks() == null) continue;
            for (var block : content.blocks()) {
                if (block instanceof TextBlock(String text1)
                    && text1 != null && !StringUtils.isBlank(text1)) {
                    return text1;
                }
            }
        }
        return null;
    }

    @Override
    public void recordMode(String sessionId, String mode) {
        prepareSessionMaterialization(sessionId);
        Path sessionFile = sessionFile(sessionId);
        if (StringUtils.isNotBlank(mode)) {
            currentModes.put(sessionFile.toString(), mode);
        }
        ObjectNode entry = JsonUtils.getMapper().createObjectNode();
        entry.put("type", "mode");
        entry.put("mode", mode);
        entry.put("sessionId", sessionId);
        enqueue(sessionFile, () -> writeCustom(sessionId, sessionFile, entry));
    }

    @Override
    public void recordRestoredMode(String sessionId, String mode) {
        if (StringUtils.isBlank(sessionId)) return;
        freshlyMaterializedRestoredModes.add(sessionFile(sessionId).toString());
        recordMode(sessionId, mode);
    }

    @Override
    public void recordAiTitle(String sessionId, String title) {
        Path sessionFile = sessionFile(sessionId);
        ObjectNode entry = JsonUtils.getMapper().createObjectNode();
        entry.put("type", "ai-title");
        entry.put("aiTitle", title);
        entry.put("sessionId", sessionId);
        enqueue(sessionFile, () -> writeCustom(sessionId, sessionFile, entry));
    }

    @Override
    public void recordPermissionMode(String sessionId, String permissionMode) {
        Path sessionFile = sessionFile(sessionId);
        cachePermissionMode(sessionId, permissionMode);
        ObjectNode entry = JsonUtils.getMapper().createObjectNode();
        entry.put("type", "permission-mode");
        entry.put("permissionMode", permissionMode);
        entry.put("sessionId", sessionId);
        enqueue(sessionFile, () -> writeCustom(sessionId, sessionFile, entry));
    }

    @Override
    public void cachePermissionMode(String sessionId, String permissionMode) {
        if (StringUtils.isBlank(sessionId)) return;
        String key = sessionFile(sessionId).toString();
        if (StringUtils.isBlank(permissionMode)) {
            currentPermissionModes.remove(key);
        } else {
            currentPermissionModes.put(key, permissionMode);
        }
    }

    @Override
    public void recordContentReplacements(String sessionId,
                                           List<ToolResultBudget.Replacement> replacements) {
        if (replacements == null || replacements.isEmpty()) return;
        Path sessionFile = sessionFile(sessionId);
        ObjectNode entry = JsonUtils.getMapper().createObjectNode();
        entry.put("type", "content-replacement");
        entry.put("sessionId", sessionId);
        var array = entry.putArray("replacements");
        for (ToolResultBudget.Replacement replacement : replacements) {
            if (replacement == null) continue;
            ObjectNode item = array.addObject();
            item.put("kind", "tool-result");
            item.put("toolUseId", replacement.toolUseId());
            item.put("replacement", replacement.replacement());
        }
        if (array.isEmpty()) return;
        enqueue(sessionFile, () -> writeCustom(sessionId, sessionFile, entry));
    }

    @Override
    public boolean hasPersistedMode(String sessionId) {
        return sessionStorage.scanMetadata(sessionFile(sessionId)).mode().isPresent();
    }

    @Override
    public boolean hasPersistedPermissionMode(String sessionId) {
        Path file = sessionFile(sessionId);
        if (!Files.isReadable(file)) return false;
        try {
            return JsonUtils.readJsonLines(file).stream().anyMatch(node ->
                Strings.CS.equals("permission-mode", node.path("type").asText())
                    && node.hasNonNull("permissionMode"));
        } catch (IOException _) {
            return false;
        }
    }

    @Override
    public boolean awaitPendingWrites(String sessionId, long timeoutMillis) {
        String key = sessionFile(sessionId).toString();
        long remainingNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        long deadline = System.nanoTime() + remainingNanos;
        while (true) {
            CompletableFuture<Void> tail = writeTails.get(key);
            if (tail == null) return true;
            try {
                tail.get(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return false;
            } catch (ExecutionException e) {
                Throwable failure = e.getCause() != null ? e.getCause() : e;
                log.warn("[TRANSCRIPT] Transcript write queue failed "
                        + "[sessionId={}, failureType={}]",
                    sessionId, failure.getClass().getName(),
                    ErrorUtils.redactedForLogging(failure));
                return false;
            } catch (TimeoutException _) {
                return false;
            }
            CompletableFuture<Void> latest = writeTails.get(key);
            if (latest == null || latest == tail) return true;
            remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0L) return false;
        }
    }

    /**
     * Drains the target transcript queue and releases all process-wide state
     * keyed by that concrete session file. Call this when a session is no
     * longer active (shutdown, /clear, or a completed sidechain).
     *
     * @return {@code false} when pending writes did not drain before the timeout
     */
    public boolean releaseSessionState(String sessionId, long timeoutMillis) {
        if (StringUtils.isBlank(sessionId)) return true;
        if (!awaitPendingWrites(sessionId, timeoutMillis)) return false;
        String key = sessionFile(sessionId).toString();
        recordedUuids.remove(key);
        fileLocks.remove(key);
        writeTails.remove(key);
        cachedLastPrompts.remove(key);
        materializedLastPrompts.remove(key);
        currentParent.remove(key);
        lastAssistantUuid.remove(key);
        sessionSlugs.remove(key);
        currentPromptIds.remove(key);
        currentPromptSources.remove(key);
        currentModes.remove(key);
        currentPermissionModes.remove(key);
        currentLeaves.remove(key);
        replToolUseIds.remove(key);
        preparedManualCompactMetadata.remove(key);
        resumedSessionFiles.remove(key);
        freshlyMaterializedRestoredModes.remove(key);
        synchronized (activeSessionMetadataLock) {
            if (Strings.CS.equals(activeMetadataSessionId, sessionId)) {
                clearActiveSessionMetadata();
            }
        }
        if (!isSidechain) PlanSlugRegistry.clear(sessionId);
        return true;
    }

    /**
     * Invalidates persisted-message and chain caches after compaction rewrites
     * the logical conversation, while retaining live prompt/session metadata.
     */
    public boolean clearCompactionCaches(String sessionId, long timeoutMillis) {
        if (StringUtils.isBlank(sessionId)) return true;
        if (!awaitPendingWrites(sessionId, timeoutMillis)) return false;
        String key = sessionFile(sessionId).toString();
        recordedUuids.remove(key);
        currentParent.remove(key);
        lastAssistantUuid.remove(key);
        return true;
    }

    boolean hasCachedStateForTests(String sessionId) {
        String key = sessionFile(sessionId).toString();
        return recordedUuids.containsKey(key)
            || fileLocks.containsKey(key)
            || writeTails.containsKey(key)
            || cachedLastPrompts.containsKey(key)
            || materializedLastPrompts.containsKey(key)
            || currentParent.containsKey(key)
            || lastAssistantUuid.containsKey(key)
            || sessionSlugs.containsKey(key)
            || currentPromptIds.containsKey(key)
            || currentPromptSources.containsKey(key)
            || currentModes.containsKey(key)
            || currentPermissionModes.containsKey(key)
            || currentLeaves.containsKey(key)
            || replToolUseIds.containsKey(key)
            || preparedManualCompactMetadata.contains(key)
            || resumedSessionFiles.contains(key)
            || activeMetadataFor(sessionId);
    }

    private boolean activeMetadataFor(String sessionId) {
        synchronized (activeSessionMetadataLock) {
            return Strings.CS.equals(activeMetadataSessionId, sessionId);
        }
    }

    private void clearActiveSessionMetadata() {
        activeMetadataSessionId = null;
        cachedSessionTitle = null;
        cachedSessionAgentName = null;
        cachedAgentSetting = null;
        activeSessionMetadataMaterialized = false;
    }

    private Path sessionFile(String sessionId) {
        return explicitTranscriptFile != null ? explicitTranscriptFile : agentId != null
            ? sessionManager.getAgentTranscriptPath(sessionId, agentId)
            : sessionManager.getSessionFile(sessionId);
    }

    private void enqueue(Path sessionFile, Runnable write) {
        String queueKey = sessionFile.toString();
        writeTails.compute(queueKey, (key, previous) -> {
            CompletableFuture<Void> ready = previous != null
                ? previous.handle((_, _) -> null)
                : CompletableFuture.completedFuture(null);
            CompletableFuture<Void> next = ready.thenRunAsync(write, VIRTUAL_EXECUTOR);
            next.whenComplete((_, _) -> writeTails.remove(key, next));
            return next;
        });
    }

    private void enqueueMetadata(String sessionId, Path sessionFile,
                                 String type, String field, String value) {
        ObjectNode entry = JsonUtils.getMapper().createObjectNode();
        entry.put("type", type);
        entry.put(field, value);
        entry.put("sessionId", sessionId);
        enqueue(sessionFile, () -> writeCustom(sessionId, sessionFile, entry));
    }

    private void writeCustom(String sessionId, Path sessionFile, ObjectNode entry) {
        try {
            SessionFileLock.withLock(sessionFile,
                () -> sessionStorage.appendCustomEntry(sessionFile, entry));
        } catch (Exception e) {
            log.error("Failed to record transcript metadata for session {}: {}",
                sessionId, e.getMessage(), e);
        }
    }

    private void writeOne(String sessionId, Message message, Path sessionFile,
                          String promptId, String promptSource, String slug,
                          TeamInfo teamInfo) {
        try {
                        var parentDir = sessionFile.getParent();
                        if (parentDir != null) {
                            Files.createDirectories(parentDir);
                        }
                        String uuid = message.uuid();
                        if (uuid == null) {
                            // No uuid → cannot dedup or chain; write directly.
                            SessionFileLock.withLock(sessionFile, () ->
                                sessionStorage.appendMessageWithParent(sessionFile, message,
                                    sessionId, cwd, isSidechain, agentId,
                                    currentBranch(), slug, null, null,
                                    promptId, promptSource, attributionAgent, teamInfo));
                            return;
                        }

                        String key = sessionFile.toString();
                        ReentrantLock lock = fileLocks.computeIfAbsent(key, _ -> new ReentrantLock());
                        lock.lock();
                        try {
                            Set<String> seen = recordedUuids.computeIfAbsent(key, _ -> seedState(sessionFile));
                            if (seen.contains(uuid)) {
                                return; // already on disk → skip (prefix-skip; seed captured it)
                            }
                            String parent = computeParent(message,
                                currentParent.get(key), lastAssistantUuid.get(key));

                            // compact boundary, where it preserves the running parent
                            // (the last chain participant before the boundary).
                            String logicalParent = isCompactBoundary(message)
                                ? compactLogicalParent(message, currentParent.get(key)) : null;
                            SessionFileLock.withLock(sessionFile, () -> {
                                if (isCompactBoundary(message)
                                        && !preparedManualCompactMetadata.remove(key)
                                        && !freshlyMaterializedRestoredModes.remove(key)) {
                                    String mode = currentModes.get(key);
                                    if (StringUtils.isNotBlank(mode)) {
                                        sessionStorage.appendMode(sessionFile, sessionId, mode);
                                    }
                                }
                                sessionStorage.appendMessageWithParent(sessionFile, message,
                                    sessionId, cwd, isSidechain, agentId,
                                    currentBranch(), slug,
                                    logicalParent, parent, promptId, promptSource,
                                    attributionAgent, teamInfo);
                            });
                            seen.add(uuid);
                            if (isChainParticipant(message)) {
                                currentParent.put(key, uuid);
                                if (Strings.CS.equals("assistant", message.type())) {
                                    lastAssistantUuid.put(key, uuid);
                                }
                            }
                        } finally {
                            lock.unlock();
                        }
        } catch (Exception e) {
            log.error("Failed to record transcript for session {}: {}",
                    sessionId, e.getMessage(), e);
        }
    }

    private String currentBranch() {
        try {
            String branch = gitBranchSupplier.get();
            return StringUtils.isBlank(branch) ? "HEAD" : branch;
        } catch (Exception _) {
            return "HEAD";
        }
    }


    private void reappendCompactMetadata(String sessionId, Path sessionFile,
                                         String commandMessageId) {
        reappendCompactMetadata(sessionId, sessionFile, commandMessageId, true, true);
    }

    private void reappendCompactMetadata(String sessionId, Path sessionFile,
                                         String commandMessageId,
                                         boolean includeFileHistorySnapshot,
                                         boolean synthesizePermissionMode) {
        if (!Files.isReadable(sessionFile)) return;
        try {
            List<String> lines = Files.readAllLines(sessionFile);
            List<String> metadataTypes = includeFileHistorySnapshot
                ? List.of("ai-title", "mode", "permission-mode", "file-history-snapshot")
                : List.of("ai-title", "mode", "permission-mode");
            for (String type : metadataTypes) {
                if (Strings.CS.equals("mode", type)
                        && freshlyMaterializedRestoredModes.remove(sessionFile.toString())) {
                    continue;
                }
                ObjectNode latest = null;
                int latestIndex = -1;
                for (int i = lines.size() - 1; i >= 0; i--) {
                    JsonNode node;
                    try { node = JsonUtils.getMapper().readTree(lines.get(i)); }
                    catch (Exception _) { continue; }
                    if (node instanceof ObjectNode object
                            && Strings.CS.equals(type, object.path("type").asText())) {
                        latest = object.deepCopy();
                        latestIndex = i;
                        break;
                    }
                }
                if (latest == null && synthesizePermissionMode
                        && Strings.CS.equals("permission-mode", type)) {
                    String restored = latestPersistedPermissionMode(lines);
                    if (restored != null) {
                        latest = JsonUtils.getMapper().createObjectNode();
                        latest.put("type", "permission-mode");
                        latest.put("permissionMode", restored);
                    }
                }
                if (synthesizePermissionMode && Strings.CS.equals("permission-mode", type)) {
                    String effective = currentPermissionModes.get(sessionFile.toString());
                    if (effective != null) {
                        if (latest == null) {
                            latest = JsonUtils.getMapper().createObjectNode();
                            latest.put("type", "permission-mode");
                        }
                        latest.put("permissionMode", effective);
                    }
                }
                if (latest == null && Strings.CS.equals("file-history-snapshot", type)
                        && commandMessageId != null && !StringUtils.isBlank(commandMessageId)) {
                    latest = emptyFileHistorySnapshot(commandMessageId);
                }
                if (latest != null) {
// A restored local /compact can materialize its initial mode immediately before
// this queued transaction.
                    if (latestIndex >= 0 && !hasChainEntryAfter(lines, latestIndex)) {
                        continue;
                    }
                    if (Strings.CS.equals("file-history-snapshot", type)) {
                        latest.remove("sessionId");
                        if (StringUtils.isNotBlank(commandMessageId)) {
                            latest.put("messageId", commandMessageId);
                            if (latest.path("snapshot") instanceof ObjectNode snapshot) {
                                snapshot.put("messageId", commandMessageId);
                            }
                        }
                    } else {
                        latest.put("sessionId", sessionId);
                    }
                    sessionStorage.appendCustomEntry(sessionFile, latest);
                }
            }
        } catch (IOException e) {
            log.debug("Failed to re-append manual compact metadata for {}: {}",
                sessionId, e.getMessage());
        }
    }

    private static String latestPersistedPermissionMode(List<String> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            try {
                JsonNode node = JsonUtils.getMapper().readTree(lines.get(i));
                String value = node.path("permissionMode").asText(null);
                if (StringUtils.isNotBlank(value)) return value;
            } catch (Exception _) {
                // Continue through malformed or non-object historical rows.
            }
        }
        return null;
    }

    private static boolean hasChainEntryAfter(List<String> lines, int index) {
        for (int i = index + 1; i < lines.size(); i++) {
            try {
                JsonNode node = JsonUtils.getMapper().readTree(lines.get(i));
                if (node.hasNonNull("uuid")) return true;
            } catch (Exception _) {
                // A malformed historical row cannot safely prove freshness.
                return true;
            }
        }
        return false;
    }

    private static ObjectNode emptyFileHistorySnapshot(String messageId) {
        ObjectNode entry = JsonUtils.getMapper().createObjectNode();
        entry.put("type", "file-history-snapshot");
        entry.put("messageId", messageId);
        entry.put("isSnapshotUpdate", false);
        ObjectNode snapshot = entry.putObject("snapshot");
        snapshot.put("messageId", messageId);
        snapshot.put("timestamp", Instant.now().toString());
        snapshot.putObject("trackedFileBackups");
        return entry;
    }

    private String sessionSlug(String sessionId, Path sessionFile) {
        String active = PlanSlugRegistry.get(sessionId).orElse(null);
        if (active != null) {
            sessionSlugs.put(sessionFile.toString(), active);
            return active;
        }
        String key = sessionFile.toString();
        String cached = sessionSlugs.get(key);
        if (cached != null) return cached;
        String persisted = sessionStorage.readSessionSlug(sessionFile).orElse(null);
        if (persisted != null) {
            sessionSlugs.putIfAbsent(key, persisted);
            PlanSlugRegistry.set(sessionId, persisted);
        }
        return persisted;
    }

    /**
     * Seeds the per-file dedup + chain state from the existing transcript file: builds the set of
     * already-persisted UUIDs and captures the last chain participant / last assistant UUID.
     */
    private Set<String> seedState(Path file) {
        Set<String> seen = new HashSet<>();
        String lastChain = null;
        String lastAssistant = null;
        String key = file.toString();
        Set<String> knownReplIds = replToolUseIds.computeIfAbsent(
            key, _ -> ConcurrentHashMap.newKeySet());
        for (Message m : sessionStorage.readMessages(file)) {
            TranscriptMessageCleaner.rememberReplToolUseIds(List.of(m), knownReplIds);
            String u = m.uuid();
            if (u != null) {
                seen.add(u);
            }
            if (isChainParticipant(m)) {
                lastChain = u;
            }
            if (Strings.CS.equals("assistant", m.type())) {
                lastAssistant = u;
            }
        }
        // ConcurrentHashMap forbids null values, so only seed when non-null.
        if (lastChain != null) currentParent.put(key, lastChain);
        if (lastAssistant != null) lastAssistantUuid.put(key, lastAssistant);
        SessionStorage.MetadataSnapshot metadata = sessionStorage.scanMetadata(file);
        metadata.mode().ifPresent(mode -> currentModes.put(key, mode));
        metadata.leafUuid().ifPresent(uuid -> currentLeaves.putIfAbsent(
            key, new LeafState(uuid, Instant.EPOCH)));
        return seen;
    }

    private void rebaseConversationChain(Path file, List<Message> retainedMessages) {
        String key = file.toString();
        Set<String> persistedUuids = recordedUuids.computeIfAbsent(key, _ -> seedState(file));
        String retainedParent = null;
        String retainedAssistant = null;
        for (Message message : retainedMessages) {
            if (message == null || StringUtils.isBlank(message.uuid())
                    || !persistedUuids.contains(message.uuid())) {
                continue;
            }
            if (isChainParticipant(message)) retainedParent = message.uuid();
            if (Strings.CS.equals("assistant", message.type())) {
                retainedAssistant = message.uuid();
            }
        }
        putOrRemove(currentParent, key, retainedParent);
        putOrRemove(lastAssistantUuid, key, retainedAssistant);
    }

    private static void putOrRemove(
            ConcurrentMap<String, String> state, String key, String value) {
        if (value == null) state.remove(key);
        else state.put(key, value);
    }


    private static String computeParent(Message message, String currentParentUuid, String lastAssistantUuid) {
        if (isCompactBoundary(message)) {
            return null;
        }

        // for ANY user message carrying it — not only tool results. In practice the field

        // latent divergence if it is set elsewhere.
        if (message instanceof UserMessage um && um.sourceToolAssistantUUID() != null) {
            return um.sourceToolAssistantUUID();
        }
        if (isToolResult(message)) {


            return lastAssistantUuid;
        }
        return currentParentUuid;
    }


    private static boolean isCompactBoundary(Message m) {
        return m instanceof SystemMessage sm && Strings.CS.equals("compact_boundary", sm.subtype());
    }

    private static String compactLogicalParent(Message message, String currentParent) {
        if (message instanceof SystemMessage system) {
            if (StringUtils.isNotBlank(system.parentUuidValue())) {
                return system.parentUuidValue();
            }
            if (system.compactMetadata() != null
                    && system.compactMetadata().preservedSegment() != null
                    && system.compactMetadata().preservedSegment().tailUuid() != null) {
                return system.compactMetadata().preservedSegment().tailUuid();
            }
        }
        return currentParent;
    }


    private static boolean isChainParticipant(Message m) {
        return !Strings.CS.equals("progress", m.type());
    }


    private static boolean isToolResult(Message m) {
        return m instanceof UserMessage um && um.toolUseResult() != null;
    }

    private static boolean isCommandMetadataUser(UserMessage user) {
        String text = user.message() == null ? null : user.message().text();
        return text != null && Strings.CS.startsWith(text, "<command-message>");
    }

/** Installs the SDK session-match observer without changing persistence ordering. */
    public void setAppendListener(BiConsumer<Path, ObjectNode> listener) {
        sessionStorage.setAppendListener(listener);
    }
}
