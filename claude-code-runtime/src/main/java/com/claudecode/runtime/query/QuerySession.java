package com.claudecode.runtime.query;

import com.claudecode.core.attachment.AttachmentService;
import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.CompactProgressEvent;
import com.claudecode.core.engine.CostCalculator;
import com.claudecode.core.engine.FileChangeListener;
import com.claudecode.core.engine.FileHistoryManager;
import com.claudecode.core.engine.FileStateCache;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.engine.MessageCompactor;
import com.claudecode.core.engine.PermissionAskCallback;
import com.claudecode.core.engine.ProcessedInput;
import com.claudecode.core.engine.RefusalFallbackPrompt;
import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.SubmitOptions;
import com.claudecode.core.engine.ToolContextModifier;
import com.claudecode.core.engine.TranscriptSink;
import com.claudecode.core.engine.TurnTokenBudget;
import com.claudecode.core.engine.WorkingDirectoryController;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.Usage;
import com.claudecode.core.metrics.SessionMetricsEvent;
import com.claudecode.core.metrics.SessionMetricsSnapshot;
import com.claudecode.core.queue.MessageQueueManager;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Application-facing boundary for one query conversation.
 *
 * <ul>
 *   <li>submission, conversation state, live configuration,
 *       execution telemetry, and cache-sharing fork capabilities.</li>
 * </ul>
 */
public interface QuerySession {

    Submission submission();
    Conversation conversation();
    Configuration configuration();
    Execution execution();
    Forks forks();

    interface Submission {
        Iterator<SDKMessage> submitMessage(Object prompt, SubmitOptions options);
        Iterator<SDKMessage> submitPrepared(PreparedQueryRequest request);
        void interrupt();
        void softInterrupt();
        boolean isSoftInterruptRequested();
        ProcessedInput processUserInput(String rawInput);
        Optional<String> handleOrphanedPermissions();
    }

    interface Conversation {
        List<Message> getMessages();
        /** Fullscreen rewind view: one retained pre-compact interval plus active messages. */
        default List<Message> getMessagesForRewind() { return getMessages(); }
        String getSessionId();
        SessionIdentity sessionIdentity();
        Optional<AssistantMessage> findUnresolvedToolUse(String toolUseId);
        void queueNotification(SystemMessage message);
        List<SystemMessage> drainNotifications();
        MessageQueueManager getMessageQueue();
        void loadMessages(List<Message> messages);
        default void loadCompactedMessages(List<Message> messages) { loadMessages(messages); }
        default void loadCompactedMessages(
                List<Message> messages, List<Message> retainedRewindMessages) {
            loadCompactedMessages(messages);
        }
        String startNewSession();
        String switchToSession(String existingSessionId);
        void injectSystemReminder(String context);
        FileHistoryManager getFileHistoryManager();
        void appendTranscriptMessage(Message message);
        void appendInMemoryMessage(Message message);
        List<Message> getAttachmentContextMessages();
        void putReadFilePaths(Collection<String> absolutePaths);
        Set<String> getBashTools();
        void putBashTools(Collection<String> tools);
        void drainQueuedCommands(Consumer<SDKMessage> emit);
    }

    interface Configuration {
        QuerySessionSpec getConfig();
        void setModel(String model);
        String getModelOverride();
        String getEffortOverride();
        WorkingDirectoryController workingDirectoryController();
        String fetchSystemPromptParts();
        String assembleSystemPrompt(String claudeMd);
        List<String> assembleSystemPromptParts(String claudeMd);
        void applyContextModifier(ToolContextModifier modifier);
        String getFastModeState();
        FastModeController getFastModeController();
        AttachmentService getAttachmentService();
    }

    interface Execution {
        AbortController getAbortController();
        Usage getTotalUsage();
        void setTotalUsage(Usage usage);
        SessionMetricsSnapshot getSessionMetrics();
        void restoreSessionMetrics(String sessionId, List<SessionMetricsEvent> events,
                                   List<String> transcriptTurnIds);
        TurnTokenBudget getTurnTokenBudget();
        Set<String> getDiscoveredSkillNames();
        CostCalculator getCostCalculator();
        MessageCompactor getCompactService();
        HookDispatcher getHookDispatcher();
        void setHookDispatcher(HookDispatcher dispatcher);
        void setHookDispatcherDeferred(HookDispatcher dispatcher);
        void addStartupBarrier(CompletionStage<?> barrier);
        CompletionStage<Void> sealStartupReadiness();
        PermissionAskCallback getPermissionAskCallback();
        void setPermissionAskCallback(PermissionAskCallback callback);
        PermissionAskCallback withDenialRecording(PermissionAskCallback delegate);
        RefusalFallbackPrompt getRefusalFallbackPrompt();
        void setRefusalFallbackPrompt(RefusalFallbackPrompt prompt);
        FileChangeListener getFileChangeListener();
        void setFileChangeListener(FileChangeListener listener);
        TranscriptSink getTranscriptSink();
        void setTranscriptSink(TranscriptSink sink);
        void setBeforeModelRequestCallback(Runnable callback);
        Runnable getPostCompactCallback();
        void setPostCompactCallback(Runnable callback);
        Consumer<CompactProgressEvent> getOnCompactProgress();
        void setOnCompactProgress(Consumer<CompactProgressEvent> callback);
        List<SDKMessage.PermissionDenial> getPermissionDenials();
        void addPermissionDenial(SDKMessage.PermissionDenial denial);
        void resetQueryTiming();
        void markQueryRequestStarted();
        void markQueryStreamEvent();
        void markQueryOutput();
        long getQueryTtftMs();
        long getQueryTtftStreamMs();
        long getQueryTimeToRequestMs();
        String getAttributionSkill();
        String getAttributionPlugin();
        void activateMcpAttribution(String serverName, String toolName);
        String getAttributionMcpServer();
        String getAttributionMcpTool();
        void clearMcpAttribution();
        void setCurrentTurnMessageId(String messageId);
    }

    interface Forks {
        StreamingClient.StreamRequest buildCacheSharingRequest(
            List<Message> messages, String compactPrompt);
        StreamingClient.StreamRequest getLastCacheSafeForkRequest();
        void setLastCacheSafeForkRequest(StreamingClient.StreamRequest request);
        Map<String, String> getReadFileState();
        FileStateCache getFileStateCache();
        Set<String> getLoadedNestedMemoryPaths();
        Set<String> getNestedMemoryAttachmentTriggers();
        void clearNestedMemoryAttachmentTriggers();
        boolean hasCompactionOccurred();
        void setCompactionOccurred(boolean occurred);
        List<String> getPreviousTurnTools();
        void setPreviousTurnTools(List<String> tools);
    }
}
