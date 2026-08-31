package com.claudecode.ui.lanterna.dialog;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BtwSideQuestionDialog197Test {

    @AfterEach void resetHistory() {
        BtwSideQuestionDialog.resetHistoryForTest();
    }

    @Test
    void rendersOnlyLatestFiveHistoryRowsAndReleasedHeightFormula() {
        BtwSideQuestionDialog.seedHistoryForTest(List.of(
            "one", "two", "three", "four", "five", "six", "seven"));
        BtwSideQuestionDialog dialog = new BtwSideQuestionDialog();
        dialog.show("  current   question  ", 30, BtwSideQuestionDialog197Test::block,
            null, () -> { });

        assertEquals(List.of(
            "(+2 earlier /btw)",
            "/btw three", "/btw four", "/btw five", "/btw six", "/btw seven",
            "/btw current question"), dialog.visibleHeaderLinesForTest(80));
        assertEquals(13, dialog.maxBodyHeightForTest(),
            "max(5, rows - CHROME_ROWS - OUTER_CHROME_ROWS - renderedHistoryRows)");
        dialog.hide();
    }

    @Test
    void leftAndRightSwitchBetweenVisibleHistoryAndCurrentResponse() {
        BtwSideQuestionDialog.seedHistoryForTest(List.of("old one", "old two"));
        BtwSideQuestionDialog dialog = new BtwSideQuestionDialog();
        dialog.show("current", 30, _ -> "current answer", null, () -> { });
        awaitResponse(dialog, "current answer");

        key(dialog, new KeyStroke(KeyType.ARROW_LEFT));
        assertEquals("answer: old two", dialog.displayedResponseForTest());
        key(dialog, new KeyStroke(KeyType.ARROW_LEFT));
        assertEquals("answer: old one", dialog.displayedResponseForTest());
        key(dialog, new KeyStroke(KeyType.ARROW_RIGHT));
        assertEquals("answer: old two", dialog.displayedResponseForTest());
        key(dialog, new KeyStroke(KeyType.ARROW_RIGHT));
        assertEquals("current answer", dialog.displayedResponseForTest());
        dialog.hide();
    }

    @Test
    void successfulAnswerEntersProcessHistoryForTheNextInvocation() {
        AtomicReference<String> wrapped = new AtomicReference<>();
        BtwSideQuestionDialog first = new BtwSideQuestionDialog();
        first.show("why?", 30, prompt -> {
            wrapped.set(prompt);
            return "because";
        }, null, () -> { });
        awaitResponse(first, "because");
        assertTrue(wrapped.get().startsWith("<system-reminder>This is a side question"));
        first.hide();

        BtwSideQuestionDialog second = new BtwSideQuestionDialog();
        second.show("next", 30, BtwSideQuestionDialog197Test::block, null, () -> { });
        assertEquals(List.of("/btw why?", "/btw next"),
            second.visibleHeaderLinesForTest(80));
        second.hide();
    }

    @Test
    void clearKeepsOnlyTheCurrentNonSyntheticAnswer() {
        BtwSideQuestionDialog.seedHistoryForTest(List.of("old one", "old two"));
        BtwSideQuestionDialog dialog = new BtwSideQuestionDialog();
        dialog.show("current", 30, _ -> "current answer", null, () -> { });
        awaitResponse(dialog, "current answer");

        key(dialog, new KeyStroke('x', false, false));
        assertEquals(List.of("/btw current"),
            dialog.visibleHeaderLinesForTest(80));
        dialog.hide();
    }

    @Test
    void forkActionReceivesCurrentQuestionAndAnswerAndLocksInput() throws Exception {
        AtomicReference<String> forked = new AtomicReference<>();
        CountDownLatch called = new CountDownLatch(1);
        BtwSideQuestionDialog dialog = new BtwSideQuestionDialog();
        dialog.show("fork me", 30, _ -> "answer", (question, answer) -> {
            forked.set(question + "\n" + answer);
            called.countDown();
        }, () -> { });
        awaitResponse(dialog, "answer");

        key(dialog, new KeyStroke('f', false, false));
        assertTrue(called.await(1, TimeUnit.SECONDS));
        assertEquals("fork me\nanswer", forked.get());
        key(dialog, new KeyStroke(KeyType.ESCAPE));
        assertTrue(dialog.isActive(), "released UI ignores input while Forking… is active");
        dialog.finishFork();
        key(dialog, new KeyStroke(KeyType.ESCAPE));
        assertFalse(dialog.isActive());
    }

    private static String block(String ignored) {
        try {
            Thread.sleep(Duration.ofMinutes(1));
            return "unreachable";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("cancelled", interrupted);
        }
    }

    private static void awaitResponse(BtwSideQuestionDialog dialog, String expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (expected.equals(dialog.displayedResponseForTest())) return;
            try { Thread.sleep(10); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }
        assertEquals(expected, dialog.displayedResponseForTest());
    }

    private static void key(BtwSideQuestionDialog dialog, KeyStroke key) {
        AtomicBoolean deliver = new AtomicBoolean(true);
        dialog.handleKey(key, deliver);
        assertFalse(deliver.get());
    }
}
