package com.claudecode.runtime.sessionhost;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.engine.FileHistoryManager;
import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.engine.TranscriptSink;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageFactory;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.runtime.query.QuerySession;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuerySessionRewindServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void previewAndExecuteBothRestoresFileAndTruncatesConversation() throws Exception {
        Path workDir = tempDir.resolve("repo");
        Files.createDirectories(workDir);
        Path targetFile = workDir.resolve("hello.txt");
        Files.writeString(targetFile, "line1\nline2\n");

        FileHistoryManager history = new FileHistoryManager(
            SessionIdentity.of("sess-rewind-1"), workDir, tempDir.resolve("backups"));

        UserMessage u1 = MessageFactory.createUserMessage("first prompt");
        AssistantMessage a1 = assistant("a-1", "first answer");
        UserMessage u2 = MessageFactory.createUserMessage("second prompt");
        AssistantMessage a2 = assistant("a-2", "second answer");

        history.makeSnapshot(u2.uuid());
        history.trackEdit(targetFile.toString(), u2.uuid());
        Files.writeString(targetFile, "line1\nline2-edited\nline3\n");

        List<Message> liveMessages = new ArrayList<>(List.of(u1, a1, u2, a2));
        AtomicReference<List<Message>> recordedRetained = new AtomicReference<>();
        AtomicBoolean afterCalled = new AtomicBoolean();
        TranscriptSink sink = new TranscriptSink() {
            @Override public void record(String sessionId, Message message) {}
            @Override public void rewindConversation(String sessionId, List<Message> retainedMessages) {
                recordedRetained.set(List.copyOf(retainedMessages));
            }
        };

        QuerySession engine = stubEngine("sess-rewind-1", liveMessages, history);
        QuerySessionRewindService service = new QuerySessionRewindService(
            engine, sink, () -> afterCalled.set(true));

        SessionHostRewindController.Preview preview = service.preview(u2.uuid());
        assertThat(preview.canRewindConversation()).isTrue();
        assertThat(preview.canRewindCode()).isTrue();
        assertThat(preview.turnsToDrop()).isEqualTo(1);
        assertThat(preview.messagesToDrop()).isEqualTo(2);
        assertThat(preview.files()).hasSize(1);
        assertThat(preview.files().getFirst().unifiedDiff()).contains("line2");

        SessionHostRewindController.Result result =
            service.execute(u2.uuid(), SessionHostRewindController.RewindMode.BOTH);
        assertThat(result.rewoundConversation()).isTrue();
        assertThat(result.rewoundCode()).isTrue();
        assertThat(result.retainedMessageCount()).isEqualTo(2);
        assertThat(result.restoredFiles()).hasSize(1);
        assertThat(Files.readString(targetFile)).isEqualTo("line1\nline2\n");
        assertThat(liveMessages).containsExactly(u1, a1);
        assertThat(recordedRetained.get()).containsExactly(u1, a1);
        assertThat(afterCalled.get()).isTrue();
    }

    @Test
    void executeConversationOnlyKeepsWorkspaceFilesIntact() throws Exception {
        Path workDir = tempDir.resolve("repo2");
        Files.createDirectories(workDir);
        Path targetFile = workDir.resolve("keep.txt");
        Files.writeString(targetFile, "before\n");

        FileHistoryManager history = new FileHistoryManager(
            SessionIdentity.of("sess-rewind-2"), workDir, tempDir.resolve("backups2"));
        UserMessage u1 = MessageFactory.createUserMessage("prompt");
        AssistantMessage a1 = assistant("a-1", "reply");

        history.makeSnapshot(u1.uuid());
        history.trackEdit(targetFile.toString(), u1.uuid());
        Files.writeString(targetFile, "after\n");

        List<Message> liveMessages = new ArrayList<>(List.of(u1, a1));
        QuerySessionRewindService service = new QuerySessionRewindService(
            stubEngine("sess-rewind-2", liveMessages, history), null, null);

        SessionHostRewindController.Result result =
            service.execute(u1.uuid(), SessionHostRewindController.RewindMode.CONVERSATION_ONLY);
        assertThat(result.rewoundConversation()).isTrue();
        assertThat(result.rewoundCode()).isFalse();
        assertThat(result.retainedMessageCount()).isZero();
        assertThat(Files.readString(targetFile)).isEqualTo("after\n");
        assertThat(liveMessages).isEmpty();
    }

    private static AssistantMessage assistant(String uuid, String text) {
        return new AssistantMessage(
            uuid,
            AssistantContent.apiResponse(
                uuid,
                List.of(new TextBlock(text)),
                new Usage(0, 0, 0, 0, Usage.ServerToolUse.ZERO, null, Usage.CacheCreation.ZERO, null, null, null),
                "test-model",
                "end_turn",
                ""),
            false,
            null,
            Instant.now());
    }

    private static QuerySession stubEngine(
            String sessionId, List<Message> messages, FileHistoryManager history) {
        QuerySession.Conversation conversation = (QuerySession.Conversation) Proxy.newProxyInstance(
            QuerySession.Conversation.class.getClassLoader(),
            new Class<?>[] {QuerySession.Conversation.class},
            (_, method, args) -> switch (method.getName()) {
                case "getMessages" -> List.copyOf(messages);
                case "getSessionId" -> sessionId;
                case "getFileHistoryManager" -> history;
                case "loadMessages" -> {
                    messages.clear();
                    @SuppressWarnings("unchecked")
                    List<Message> next = (List<Message>) args[0];
                    messages.addAll(next);
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });
        return (QuerySession) Proxy.newProxyInstance(
            QuerySession.class.getClassLoader(),
            new Class<?>[] {QuerySession.class},
            (_, method, _) -> switch (method.getName()) {
                case "conversation" -> conversation;
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
