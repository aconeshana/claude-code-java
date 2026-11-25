package com.claudecode.runtime.sessionhost;

import com.claudecode.core.engine.FileHistoryManager;
import com.claudecode.core.engine.TranscriptSink;
import com.claudecode.core.message.HumanTurns;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.UserMessage;
import com.claudecode.runtime.query.QuerySession;
import java.util.List;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Shared rewind execution service backed by a live {@link QuerySession}, used
 * identically by Web headless sessions and live TUI sessions.
 */
public final class QuerySessionRewindService implements SessionHostRewindController {

    private static final int UUID_PREFIX_LENGTH = 24;
    private static final long REWIND_FLUSH_TIMEOUT_MS = 2_000L;

    private final QuerySession engine;
    private final TranscriptSink transcriptSink;
    private final Runnable onAfterRewind;

    public QuerySessionRewindService(
            QuerySession engine,
            TranscriptSink transcriptSink,
            Runnable onAfterRewind) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.transcriptSink = transcriptSink;
        this.onAfterRewind = onAfterRewind;
    }

    @Override
    public Preview preview(String messageId) {
        List<Message> messages = engine.conversation().getMessages();
        int targetIndex = findUserMessageIndex(messages, messageId);
        if (targetIndex < 0) {
            throw new IllegalArgumentException("user message not found in session: " + messageId);
        }
        String canonicalUuid = messages.get(targetIndex).uuid();
        int messagesToDrop = messages.size() - targetIndex;
        int turnsToDrop = 0;
        for (int i = targetIndex; i < messages.size(); i++) {
            if (messages.get(i) instanceof UserMessage user && HumanTurns.isTypedTurn(user)) {
                turnsToDrop++;
            }
        }
        if (turnsToDrop == 0) {
            turnsToDrop = 1;
        }

        FileHistoryManager history = engine.conversation().getFileHistoryManager();
        if (history == null) {
            return new Preview(
                true, false, "未启用文件历史快照（仅支持回撤对话）",
                turnsToDrop, messagesToDrop, List.of(), 0, 0);
        }
        FileHistoryManager.RewindPreview codePreview = history.previewRewind(canonicalUuid);
        if (!codePreview.snapshotFound()) {
            return new Preview(
                true, false, "未检测到该步骤的文件快照（仅支持回撤对话）",
                turnsToDrop, messagesToDrop, List.of(), 0, 0);
        }
        List<FileDiff> files = codePreview.files().stream()
            .map(file -> new FileDiff(
                file.path(),
                file.insertions(),
                file.deletions(),
                file.willDelete(),
                file.unifiedDiff()))
            .toList();
        return new Preview(
            true,
            true,
            null,
            turnsToDrop,
            messagesToDrop,
            files,
            codePreview.insertions(),
            codePreview.deletions());
    }

    @Override
    public Result execute(String messageId, RewindMode mode) {
        Objects.requireNonNull(mode, "mode");
        List<Message> messages = engine.conversation().getMessages();
        int targetIndex = findUserMessageIndex(messages, messageId);
        if (targetIndex < 0) {
            throw new IllegalArgumentException("user message not found in session: " + messageId);
        }
        String canonicalUuid = messages.get(targetIndex).uuid();
        boolean doCode = mode == RewindMode.BOTH || mode == RewindMode.CODE_ONLY;
        boolean doConversation = mode == RewindMode.BOTH || mode == RewindMode.CONVERSATION_ONLY;

        List<String> restoredFiles = List.of();
        if (doCode) {
            FileHistoryManager history = engine.conversation().getFileHistoryManager();
            if (history == null) {
                throw new IllegalStateException("file history is not enabled for this session");
            }
            restoredFiles = List.copyOf(history.rewind(canonicalUuid));
        }

        int retainedCount = messages.size();
        if (doConversation) {
            List<Message> retained = List.copyOf(messages.subList(0, targetIndex));
            engine.conversation().loadMessages(retained);
            String sessionId = engine.conversation().getSessionId();
            if (transcriptSink != null && StringUtils.isNotBlank(sessionId)) {
                transcriptSink.rewindConversation(sessionId, retained);
                transcriptSink.awaitPendingWrites(sessionId, REWIND_FLUSH_TIMEOUT_MS);
            }
            retainedCount = retained.size();
        }

        if (onAfterRewind != null) {
            onAfterRewind.run();
        }
        return new Result(doConversation, doCode, restoredFiles, retainedCount);
    }

    private static int findUserMessageIndex(List<Message> messages, String targetId) {
        if (messages == null || StringUtils.isBlank(targetId)) {
            return -1;
        }
        String trimmed = targetId.trim();
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (msg instanceof UserMessage && Strings.CS.equals(msg.uuid(), trimmed)) {
                return i;
            }
        }
        String prefix = trimmed.substring(0, Math.min(UUID_PREFIX_LENGTH, trimmed.length()));
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (!(msg instanceof UserMessage) || msg.uuid() == null) continue;
            String candidate = msg.uuid();
            String candPrefix = candidate.substring(0, Math.min(UUID_PREFIX_LENGTH, candidate.length()));
            if (Strings.CS.equals(candPrefix, prefix)) {
                return i;
            }
        }
        return -1;
    }
}
