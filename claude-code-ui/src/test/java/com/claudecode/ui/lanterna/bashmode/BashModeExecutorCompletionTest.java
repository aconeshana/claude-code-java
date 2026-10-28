package com.claudecode.ui.lanterna.bashmode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.UserMessage;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.runtime.query.QuerySessionSpec;
import com.claudecode.ui.lanterna.slash.PromptResurrection;
import com.claudecode.ui.lanterna.transcript.MessagePanel;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.TextGUIThread;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

/**
 * The completion callback is what releases the REPL's busy state after a {@code !} command, so
 * it must fire exactly once on every path — a missed call would queue all later input forever.
 */
class BashModeExecutorCompletionTest {

    @Test
    void blankCommandFinishesImmediately() {
        AtomicInteger finished = new AtomicInteger();

        new BashModeExecutor(null, null, null, null, null, null)
            .handle("   ", finished::incrementAndGet);

        assertEquals(1, finished.get());
    }

    @Test
    void anExecutionFailureStillReportsCompletionOnTheGuiThread() throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        // No query session: execute() fails on its first line, the worst case for the callback.
        BashModeExecutor executor = new BashModeExecutor(
            immediateGui(), new MessagePanel(), null, null, null, null);

        executor.handle("echo hi", () -> {
            calls.incrementAndGet();
            finished.countDown();
        });

        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
    }

    @Test
    void anInterruptedRunWithdrawsItsRowsAndHandsOverItsResult() throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<List<UserMessage>> handedOver = new AtomicReference<>();
        MessagePanel panel = new MessagePanel();
        panel.appendLine("before", TextColor.ANSI.DEFAULT);
        BashModeExecutor executor = new BashModeExecutor(
            immediateGui(), panel, session(), null, null, null);

        executor.handle("sleep 5", finished::countDown, messages -> {
            handedOver.set(messages);
            return true;
        });
        awaitRunning(executor);
        executor.interrupt();

        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertEquals(1, panel.snapshotLineCount(), "the pill and output rows are withdrawn");
        assertEquals(List.of("<bash-input>sleep 5</bash-input>",
                MessageConstants.INTERRUPT_MESSAGE),
            PromptResurrection.userTexts(handedOver.get()));
    }

    private static void awaitRunning(BashModeExecutor executor) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!executor.isRunning() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(executor.isRunning());
    }

    private static QuerySession session() {
        QuerySessionSpec spec = QuerySessionSpec.builder()
            .llmClient((StreamingClient) proxy(StreamingClient.class, (_, _) -> null))
            .workingDirectory(System.getProperty("java.io.tmpdir")).build();
        Object configuration = proxy(QuerySession.Configuration.class, (method, _) -> switch (
                method.getName()) {
            case "getConfig" -> spec;
            default -> null;
        });
        return (QuerySession) proxy(QuerySession.class, (method, _) ->
            Strings.CS.equals("configuration", method.getName()) ? configuration : null);
    }

    private static Object proxy(Class<?> type, BiFunction<Method, Object[], Object> answer) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
            (_, method, args) -> answer.apply(method, args));
    }

    private static WindowBasedTextGUI immediateGui() {
        TextGUIThread thread = new TextGUIThread() {
            @Override public void invokeLater(Runnable runnable) { runnable.run(); }
            @Override public boolean processEventsAndUpdate() throws IOException { return false; }
            @Override public void invokeAndWait(Runnable runnable) { runnable.run(); }
            @Override public void setExceptionHandler(ExceptionHandler exceptionHandler) { }
            @Override public Thread getThread() { return Thread.currentThread(); }
        };
        return (WindowBasedTextGUI) Proxy.newProxyInstance(
            WindowBasedTextGUI.class.getClassLoader(), new Class<?>[]{WindowBasedTextGUI.class},
            (_, method, _) -> switch (method.getName()) {
                case "getGUIThread" -> thread;
                case "getWindows" -> List.of();
                default -> null;
            });
    }
}
