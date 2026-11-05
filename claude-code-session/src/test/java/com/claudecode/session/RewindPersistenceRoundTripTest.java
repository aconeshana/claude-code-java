package com.claudecode.session;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.UserMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * End-to-end coverage for the rewind persistence round trip: a rewind must leave
 * a durable anchor on disk and a later resume must not read the abandoned rows back.
 *
 * <p>Authority: 2.1.197 / 2.1.236 bundles both write
 * {@code {"type":"last-prompt", leafUuid, explicit:true, rewound:true}} on rewind
 * (197 {@code PWo}+{@code lZt}, 236 {@code peo}+{@code Ifr}) and both resolve the
 * conversation by walking the leaf chain on load (236 {@code G6e}).
 */
class RewindPersistenceRoundTripTest {

    private static final String SESSION = "11111111-2222-3333-4444-555555555555";

    @TempDir
    Path temp;

    /**
     * The regression this test exists for: after rewind the abandoned tail stayed on
     * disk as an orphan branch, and a resume re-read every row because the loader
     * filtered only metadata lines. The user-visible symptom is that rewound content
     * reappears in the context on {@code -c}.
     */
    @Test
    void rewoundRowsStayOnDiskButNeverComeBackOnResume() throws Exception {
        Path file = temp.resolve(SESSION + ".jsonl");
        write(file,
            user("u1", null, "2026-01-01T00:00:00Z", "benign start"),
            assistantText("a1", "u1", "2026-01-01T00:00:01Z", "msg-1", "ack"),
            user("u2", "a1", "2026-01-01T00:00:02Z", "dangerous segment"),
            assistantText("a2", "u2", "2026-01-01T00:00:03Z", "msg-2", "flagged"),
            // Rewind targeted u2. 236 writes the uuid of the assistant *preceding*
            // the target (omy: precedingAssistantUuid), so the anchor is a1 and the
            // retained prefix is [u1, a1]; u2/a2 are abandoned.
            lastPrompt("a1", true, SESSION));

        List<Message> resumed = loadForResume(file);

        assertEquals(List.of("u1", "a1"), uuids(resumed),
            "resume must follow the rewind anchor chain and drop the abandoned rows");

        // The abandoned rows must remain on disk as an alternate branch: rewind
        // rebases, it does not delete.
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(raw.contains("\"uuid\":\"u2\""), "abandoned rows are retained on disk");
        assertTrue(raw.contains("\"uuid\":\"a2\""), "abandoned rows are retained on disk");
    }

    /**
     * Once the retained tail receives a new turn, the fresh rows chain off the anchor
     * and the pre-rewind branch stays unreachable.
     */
    @Test
    void newTurnAfterRewindChainsOffTheAnchorAndExcludesTheOldBranch() throws Exception {
        Path file = temp.resolve(SESSION + ".jsonl");
        write(file,
            user("u1", null, "2026-01-01T00:00:00Z", "benign start"),
            assistantText("a1", "u1", "2026-01-01T00:00:01Z", "msg-1", "ack"),
            user("u2", "a1", "2026-01-01T00:00:02Z", "dangerous segment"),
            assistantText("a2", "u2", "2026-01-01T00:00:03Z", "msg-2", "flagged"),
            lastPrompt("a1", true, SESSION),
            user("u3", "a1", "2026-01-01T00:00:10Z", "clean follow up"),
            lastPrompt("u3", false, SESSION));

        List<Message> resumed = loadForResume(file);

        assertEquals(List.of("u1", "a1", "u3"), uuids(resumed),
            "the fresh turn chains off the retained tail, not off the abandoned branch");
        assertFalse(uuids(resumed).contains("u2"),
            "the pre-rewind user row must not return to the context");
        assertFalse(uuids(resumed).contains("a2"),
            "the pre-rewind assistant row must not return to the context");
    }

    /**
     * Without an anchor the loader keeps the legacy behaviour and reads to the leaf,
     * so the fix cannot silently truncate ordinary transcripts.
     */
    @Test
    void transcriptWithoutARewindAnchorStillLoadsToTheLeaf() throws Exception {
        Path file = temp.resolve(SESSION + ".jsonl");
        write(file,
            user("u1", null, "2026-01-01T00:00:00Z", "start"),
            assistantText("a1", "u1", "2026-01-01T00:00:01Z", "msg-1", "ack"),
            user("u2", "a1", "2026-01-01T00:00:02Z", "continue"),
            lastPrompt("u2", false, SESSION));

        List<Message> resumed = loadForResume(file);

        assertEquals(List.of("u1", "a1", "u2"), uuids(resumed));
    }

    /**
     * The write half: {@link TranscriptSink#rewindConversation} must append the
     * anchor row rather than only rebasing in-memory parent pointers.
     */
    @Test
    void recorderAppendsARewoundAnchorRowOnRewind() throws Exception {
        SessionManager manager = new SessionManager(temp, "/test/project");
        TranscriptRecorder recorder = new TranscriptRecorder(manager, new SessionStorage());
        String sessionId = manager.createSession();
        Path file = manager.getSessionFile(sessionId);

        UserMessage first = new UserMessage("u1", MessageContent.ofText("start"));
        AssistantMessage firstAnswer = new AssistantMessage(
            "a1", AssistantContent.of(List.of(new TextBlock("ack"))));
        UserMessage abandoned = new UserMessage("u2", MessageContent.ofText("doomed"));

        recorder.record(sessionId, first);
        recorder.record(sessionId, firstAnswer);
        recorder.record(sessionId, abandoned);

        // Retain [u1, a1] and anchor at the assistant preceding the abandoned turn,
        // matching 236's omy(bi-1) scan.
        recorder.rewindConversation(sessionId, List.of(first, firstAnswer));
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));

        String raw = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(raw.contains("\"type\":\"last-prompt\""),
            "the anchor is a last-prompt row, matching 197/236");
        assertTrue(raw.contains("\"rewound\":true"),
            "rewind must persist an explicit anchor row; in-memory rebase alone is lost on restart");
        assertTrue(raw.contains("\"leafUuid\":\"a1\""),
            "the anchor points at the assistant preceding the rewound turn");
    }

    /**
     * The full reported symptom, end to end and across a process boundary: a real
     * recorder persists a turn, the user rewinds, the window closes, and {@code -c}
     * starts a new process that loads the session again. The rewound turn must not
     * reappear in the restored conversation.
     */
    @Test
    void rewoundTurnDoesNotReappearAfterClosingAndContinuing() throws Exception {
        SessionManager manager = new SessionManager(temp, "/test/project");
        String sessionId = manager.createSession();
        Path file = manager.getSessionFile(sessionId);

        // First process: record a benign turn, then a turn whose content must be
        // discarded, then rewind back to the benign tail.
        TranscriptRecorder first = new TranscriptRecorder(manager, new SessionStorage());
        UserMessage benign = new UserMessage("u1", MessageContent.ofText("benign start"));
        AssistantMessage benignAnswer = new AssistantMessage(
            "a1", AssistantContent.of(List.of(new TextBlock("ack"))));
        UserMessage risky = new UserMessage(
            "u2", MessageContent.ofText("a segment the user rewound away"));
        AssistantMessage riskyAnswer = new AssistantMessage(
            "a2", AssistantContent.of(List.of(new TextBlock("flagged"))));
        first.record(sessionId, benign);
        first.record(sessionId, benignAnswer);
        first.record(sessionId, risky);
        first.record(sessionId, riskyAnswer);
        assertTrue(first.awaitPendingWrites(sessionId, 5_000));

        first.rewindConversation(sessionId, List.of(benign, benignAnswer));
        assertTrue(first.awaitPendingWrites(sessionId, 5_000));

        // Process ends. A fresh recorder/storage pair models the new `-c` process.
        List<Message> restored = new SessionStorage().loadTranscriptFromFile(file).messages();
        List<String> restoredUuids = uuids(restored);

        assertEquals(List.of("u1", "a1"), restoredUuids,
            "the restored conversation must stop at the rewind anchor");
        assertFalse(restoredUuids.contains("u2"),
            "the rewound user turn must not return to the context after -c");
        assertFalse(restoredUuids.contains("a2"),
            "the rewound assistant turn must not return to the context after -c");
    }

    /** Loads through the same loader the {@code -c} resume path uses. */
    private static List<Message> loadForResume(Path file) {
        return new SessionStorage().loadTranscriptFromFile(file).messages();
    }

    private static List<String> uuids(List<Message> messages) {
        return messages.stream().map(Message::uuid).toList();
    }

    private static void write(Path file, String... lines) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    private static String lastPrompt(String leafUuid, boolean rewound, String sessionId) {
        return "{\"type\":\"last-prompt\",\"leafUuid\":\"" + leafUuid + "\","
            + "\"explicit\":true,\"rewound\":" + rewound + ","
            + "\"sessionId\":\"" + sessionId + "\"}";
    }

    private static String user(String uuid, String parent, String timestamp, String text) {
        return envelope("user", uuid, parent, timestamp,
            "\"message\":{\"role\":\"user\",\"content\":\"" + text + "\"}");
    }

    private static String assistantText(String uuid, String parent, String timestamp,
                                        String messageId, String text) {
        return envelope("assistant", uuid, parent, timestamp,
            "\"message\":{\"id\":\"" + messageId + "\",\"type\":\"message\","
                + "\"role\":\"assistant\",\"content\":[{\"type\":\"text\","
                + "\"text\":\"" + text + "\"}],\"model\":\"claude\","
                + "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":9,"
                + "\"output_tokens\":1,\"cache_creation_input_tokens\":0,"
                + "\"cache_read_input_tokens\":0}}");
    }

    private static String envelope(String type, String uuid, String parent, String timestamp,
                                   String body) {
        String parentJson = parent == null ? "null" : "\"" + parent + "\"";
        return "{\"type\":\"" + type + "\",\"uuid\":\"" + uuid + "\","
            + "\"parentUuid\":" + parentJson + ",\"timestamp\":\"" + timestamp + "\","
            + "\"sessionId\":\"" + SESSION + "\",\"isSidechain\":false," + body + "}";
    }
}