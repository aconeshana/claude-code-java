package com.claudecode.services.session;

import com.claudecode.runtime.session.MessagesDeserializer;

import com.claudecode.runtime.query.DefaultQuerySession;
import com.claudecode.runtime.query.QuerySessionSpec;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.TranscriptSink;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.GoalStatusAttachment;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.session.SessionStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumeStateRestorerTranscriptTest {

    private static final StreamingClient NOOP_CLIENT = new StreamingClient() {
        @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
            return Collections.emptyIterator();
        }

        @Override public String getModel() { return "test-model"; }
    };

    @TempDir
    Path tempDir;

    @Test
    void resumePersistsOnlyRecoveryMessagesSynthesizedAfterTheStoredTail() {
        String sessionId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";
        Path sessionFile = tempDir.resolve(sessionId + ".jsonl");
        SessionStorage storage = new SessionStorage();
        AttachmentMessage storedTail = new AttachmentMessage(
            "stored-attachment",
            GoalStatusAttachment.pending("goal", "status"));
        storage.appendMessageWithParent(sessionFile, storedTail, sessionId,
            tempDir.toString(), false, null, "HEAD", "official-slug", null, null);

        List<Message> recovered = MessagesDeserializer.deserialize(
            storage.readMessages(sessionFile));
        assertEquals(3, recovered.size(),
            "attachment tail should synthesize continuation user + assistant sentinel");

        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .workingDirectory(tempDir.toString())
            .build());
        engine.switchToSession(sessionId);
        CapturingTranscriptSink sink = new CapturingTranscriptSink();
        engine.setTranscriptSink(sink);
        ResumeStateRestorer restorer = new ResumeStateRestorer(
            engine, storage, null, null, _ -> false);

        restorer.persistRecoveredMessages(sessionFile, recovered);

        assertEquals(2, sink.messages.size(),
            "the already-persisted attachment must be skipped");
        UserMessage continuation = assertInstanceOf(UserMessage.class, sink.messages.getFirst());
        assertEquals(MessagesDeserializer.CONTINUE_FROM_WHERE_YOU_LEFT_OFF,
            ((TextBlock) continuation.message().blocks().getFirst()).text());
        assertTrue(continuation.isMeta());

        AssistantMessage sentinel = assertInstanceOf(AssistantMessage.class, sink.messages.get(1));
        assertNotNull(sentinel.message().id());
        assertEquals("<synthetic>", sentinel.message().model());
        assertEquals("stop_sequence", sentinel.message().stopReason());
        assertEquals("", sentinel.message().stopSequence());
        assertEquals(MessagesDeserializer.NO_RESPONSE_REQUESTED,
            ((TextBlock) sentinel.message().content().getFirst()).text());
    }

    @Test
    void headlessResumeCanDeferRecoveryPersistenceUntilAfterQueueOperations() {
        String sessionId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee";
        Path sessionFile = tempDir.resolve(sessionId + ".jsonl");
        SessionStorage storage = new SessionStorage();
        AttachmentMessage storedTail = new AttachmentMessage(
            "stored-attachment-deferred",
            GoalStatusAttachment.pending("goal", "status"));
        storage.appendMessageWithParent(sessionFile, storedTail, sessionId,
            tempDir.toString(), false, null, "HEAD", "official-slug", null, null);

        List<Message> recovered = MessagesDeserializer.deserialize(
            storage.readMessages(sessionFile));
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .workingDirectory(tempDir.toString())
            .build());
        engine.switchToSession(sessionId);
        CapturingTranscriptSink sink = new CapturingTranscriptSink();
        engine.setTranscriptSink(sink);
        ResumeStateRestorer restorer = new ResumeStateRestorer(
            engine, storage, null, null, _ -> false);

        restorer.postSwitch(sessionFile, recovered, tempDir.toString(), false);

        assertTrue(sink.messages.isEmpty(),
            "headless startup must be able to let queue enqueue/dequeue win the write race");
        restorer.persistRecoveredMessages(sessionFile, recovered);
        assertEquals(2, sink.messages.size());
    }

    @Test
    void resumeRestoresPermissionModeFromHeadlessUserMetadata() {
        String sessionId = "ffffffff-ffff-4fff-8fff-ffffffffffff";
        Path sessionFile = tempDir.resolve(sessionId + ".jsonl");
        SessionStorage storage = new SessionStorage();
        storage.appendMessageWithParent(sessionFile, new UserMessage(
            "sdk-user", MessageContent.ofText("seed"), false, false, null,
            MessageOrigin.USER, null, Instant.now(), null,
            "bypassPermissions", sessionId, null, null),
            sessionId, tempDir.toString(), false, null, "HEAD", null, null, null);

        AtomicReference<String> restoredMode = new AtomicReference<>();
        QuerySessionSpec config = QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .workingDirectory(tempDir.toString())
            .build();
        config.setPermissionModeRestorer(restoredMode::set);
        DefaultQuerySession engine = new DefaultQuerySession(config);
        ResumeStateRestorer restorer = new ResumeStateRestorer(
            engine, storage, null, null, _ -> false);

        restorer.restoreMetadata(sessionFile);

        assertEquals("bypassPermissions", restoredMode.get());
    }

    private static final class CapturingTranscriptSink implements TranscriptSink {
        private final List<Message> messages = new ArrayList<>();

        @Override
        public void record(String sessionId, Message message) {
            messages.add(message);
        }
    }
}
