package com.claudecode.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.commands.diff.TurnDiffExtractor;
import com.claudecode.core.diff.FileChangeResult;
import com.claudecode.core.diff.StructuredPatchHunk;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.UserMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * The turn diffs are remembered per transcript instance: the same list is
 * extracted once, however many cards ask for it, and any other list is
 * extracted afresh.
 */
class TurnDiffMemoTest {

    private final TurnDiffMemo memo = new TurnDiffMemo();

    private static List<Message> transcript(String path) {
        return List.of(
            new UserMessage("u-1", MessageContent.ofText("请改一下"), false, false,
                null, MessageOrigin.USER, null, Instant.now(), null, null, null),
            new UserMessage("tr-1", MessageContent.ofText(""), false, false,
                FileChangeResult.edited(path, List.of(
                    new StructuredPatchHunk(1, 1, 1, 1, List.of("-a", "+b")))),
                MessageOrigin.USER, null, Instant.now(), null, null, null));
    }

    @Test
    void theSameTranscriptIsExtractedOnce() {
        List<Message> messages = transcript("/a.java");

        List<TurnDiffExtractor.TurnDiff> first = memo.of(messages);

        assertThat(first).isEqualTo(TurnDiffExtractor.extract(messages)).isNotEmpty();
        assertThat(memo.of(messages)).isSameAs(first);
    }

    @Test
    void anotherTranscriptIsExtractedAfresh() {
        List<TurnDiffExtractor.TurnDiff> before = memo.of(transcript("/a.java"));
        List<Message> grown = new ArrayList<>(transcript("/a.java"));
        grown.addAll(transcript("/b.java"));

        List<TurnDiffExtractor.TurnDiff> after = memo.of(List.copyOf(grown));

        assertThat(after).isNotSameAs(before).isEqualTo(TurnDiffExtractor.extract(grown));
    }

    @Test
    void concurrentRequestsForOneTranscriptShareOneExtraction() throws Exception {
        List<Message> messages = transcript("/a.java");
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<TurnDiffExtractor.TurnDiff>>> answers = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                answers.add(pool.submit(() -> {
                    start.await();
                    return memo.of(messages);
                }));
            }
            start.countDown();
            List<TurnDiffExtractor.TurnDiff> first = answers.getFirst().get();
            for (Future<List<TurnDiffExtractor.TurnDiff>> answer : answers) {
                assertThat(answer.get()).isSameAs(first);
            }
        }
    }
}
