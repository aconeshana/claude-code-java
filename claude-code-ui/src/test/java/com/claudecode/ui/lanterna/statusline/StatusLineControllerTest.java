package com.claudecode.ui.lanterna.statusline;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.runtime.statusline.StatusLinePort;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.apache.commons.lang3.Strings;

/**
 * Verifies {@link StatusLineController}'s update lifecycle: debounced coalescing
 * (bursts run the command once), command output → render, absent config →
 * clear, and padding pass-through. Uses the injection constructor so config is
 * controlled directly (no dependency on the developer's real settings files).
 */
class StatusLineControllerTest {

    private static StatusLineInputBuilder.Ingredients ingredients() {
        return new StatusLineInputBuilder.Ingredients(
            "sess", null, "/t/sess.jsonl", "/t", "/t", List.of(),
            "claude-opus-4-8", null, null, "0.1.0");
    }

    private static StatusLineInputBuilder.Ingredients gptIngredients() {
        return new StatusLineInputBuilder.Ingredients(
            "sess", null, "/t/sess.jsonl", "/t", "/t", List.of(),
            "gpt-5.6-sol", null, null, "0.1.0");
    }

    private StatusLineController controller(StatusLinePort statusLine,
                                            AtomicReference<String> rendered,
                                            AtomicInteger renderCount,
                                            AtomicInteger clearCount,
                                            AtomicReference<Integer> paddingSeen) {
        return new StatusLineController(
            statusLine,
            StatusLineControllerTest::ingredients,
            List::of,
            Runnable::run,                        // GUI thread = inline
            (text, padding) -> { rendered.set(text); renderCount.incrementAndGet(); paddingSeen.set(padding); },
            clearCount::incrementAndGet,
            () -> false,
            () -> 120);
    }

    @Test
    void rendersCommandOutput() throws Exception {
        var rendered = new AtomicReference<String>();
        var renderCount = new AtomicInteger();
        var clearCount = new AtomicInteger();
        var padding = new AtomicReference<Integer>();
        StatusLineController c = controller(
            _ -> Optional.of(new StatusLinePort.Output("HUD-LINE", 2)),
            rendered, renderCount, clearCount, padding);

        c.scheduleUpdate();
        assertTrue(waitFor(() -> renderCount.get() == 1), "should render once");
        assertEquals("HUD-LINE", rendered.get());
        assertEquals(2, padding.get(), "padding passed through");
        assertEquals(0, clearCount.get());
    }

    @Test
    void debouncesBurstToSingleRun() throws Exception {
        var renderCount = new AtomicInteger();
        StatusLineController c = controller(
            _ -> Optional.of(new StatusLinePort.Output("X", 0)),
            new AtomicReference<>(), renderCount, new AtomicInteger(), new AtomicReference<>());

        // A rapid burst — only the last should survive the debounce.
        for (int i = 0; i < 5; i++) c.scheduleUpdate();

        assertTrue(waitFor(() -> renderCount.get() == 1), "burst should render exactly once");
        Thread.sleep(200);  // give any stray runs a chance to (wrongly) fire
        assertEquals(1, renderCount.get(), "debounce must coalesce the burst");
    }

    @Test
    void absentConfigClears() throws Exception {
        var renderCount = new AtomicInteger();
        var clearCount = new AtomicInteger();
        StatusLineController c = controller(
            _ -> Optional.empty(), new AtomicReference<>(), renderCount, clearCount, new AtomicReference<>());

        c.scheduleUpdate();
        assertTrue(waitFor(() -> clearCount.get() == 1), "no config → clear");
        assertEquals(0, renderCount.get(), "must not render when unconfigured");
    }

    @Test
    void failedCommandClears() throws Exception {
        var renderCount = new AtomicInteger();
        var clearCount = new AtomicInteger();
        StatusLineController c = controller(
            _ -> Optional.empty(),
            new AtomicReference<>(), renderCount, clearCount, new AtomicReference<>());

        c.scheduleUpdate();
        assertTrue(waitFor(() -> clearCount.get() == 1), "non-zero exit → clear (TS undefined)");
        assertEquals(0, renderCount.get());
    }

    @Test
    void builtInHudRendersWhenEnabledAndCommandHasNoOutput() throws Exception {
        var rendered = new AtomicReference<String>();
        var renderCount = new AtomicInteger();
        var clearCount = new AtomicInteger();
        var padding = new AtomicReference<Integer>();
        StatusLineController c = new StatusLineController(
            _ -> Optional.empty(),
            StatusLineControllerTest::ingredients,
            List::of,
            Runnable::run,
            (text, p) -> { rendered.set(text); padding.set(p); renderCount.incrementAndGet(); },
            clearCount::incrementAndGet,
            () -> true,
            () -> 120);

        c.scheduleUpdate();

        assertTrue(waitFor(() -> renderCount.get() == 1), "built-in HUD should render");
        assertTrue(Strings.CS.contains(rendered.get(), "[Opus"), rendered.get());
        assertTrue(Strings.CS.contains(rendered.get(), "Context"), rendered.get());
        assertEquals(0, padding.get());
        assertEquals(0, clearCount.get());
    }

    @Test
    void builtInHudDoesNotInvokeConfiguredExternalCommand() throws Exception {
        var externalCalls = new AtomicInteger();
        var rendered = new AtomicReference<String>();
        var renderCount = new AtomicInteger();
        StatusLineController c = new StatusLineController(
            _ -> {
                externalCalls.incrementAndGet();
                return Optional.of(new StatusLinePort.Output("STALE-PLUGIN", 0));
            },
            StatusLineControllerTest::ingredients,
            List::of,
            Runnable::run,
            (text, _) -> { rendered.set(text); renderCount.incrementAndGet(); },
            () -> {},
            () -> true,
            () -> 120);

        c.scheduleUpdate();

        assertTrue(waitFor(() -> renderCount.get() == 1), "built-in HUD should render");
        assertEquals(0, externalCalls.get(), "native HUD must not invoke the old plugin command");
        assertTrue(Strings.CS.contains(rendered.get(), "Context"), rendered.get());
    }

    @Test
    void initialUpdateDoesNotWaitForInteractionDebounce() throws Exception {
        var renderCount = new AtomicInteger();
        StatusLineController c = controller(
            _ -> Optional.of(new StatusLinePort.Output("X", 0)),
            new AtomicReference<>(), renderCount, new AtomicInteger(), new AtomicReference<>());

        long started = System.nanoTime();
        c.scheduleInitialUpdate();

        assertTrue(waitFor(() -> renderCount.get() == 1));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(
            System.nanoTime() - started);
        assertTrue(elapsedMs < StatusLineController.DEBOUNCE_MS,
            "initial HUD paint waited " + elapsedMs + "ms");
    }

    @Test
    void closeCancelsPendingDebouncedRefresh() throws Exception {
        var commandCalls = new AtomicInteger();
        StatusLineController c = controller(
            _ -> {
                commandCalls.incrementAndGet();
                return Optional.of(new StatusLinePort.Output("late", 0));
            },
            new AtomicReference<>(), new AtomicInteger(),
            new AtomicInteger(), new AtomicReference<>());

        c.scheduleUpdate();
        c.close();

        Thread.sleep(StatusLineController.DEBOUNCE_MS + 150);
        assertEquals(0, commandCalls.get(), "unmount cleanup must clear the debounce timer");
    }

    @Test
    void closeInterruptsRunningRefreshAndDropsItsResult() throws Exception {
        var commandStarted = new CountDownLatch(1);
        var commandInterrupted = new CountDownLatch(1);
        var renderCount = new AtomicInteger();
        var clearCount = new AtomicInteger();
        StatusLineController c = controller(
            _ -> {
                commandStarted.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException _) {
                    commandInterrupted.countDown();
                    Thread.currentThread().interrupt();
                }
                return Optional.empty();
            },
            new AtomicReference<>(), renderCount, clearCount, new AtomicReference<>());

        c.scheduleInitialUpdate();
        assertTrue(commandStarted.await(3, TimeUnit.SECONDS), "command should start");

        c.close();

        assertTrue(commandInterrupted.await(3, TimeUnit.SECONDS),
            "unmount cleanup must abort the active command");
        Thread.sleep(100);
        assertEquals(0, renderCount.get(), "aborted output must not render");
        assertEquals(0, clearCount.get(), "aborted output must not clear the footer");
    }

    @Test
    void nextRefreshInterruptsPreviousRunningRefresh() throws Exception {
        var calls = new AtomicInteger();
        var firstStarted = new CountDownLatch(1);
        var firstInterrupted = new CountDownLatch(1);
        var rendered = new AtomicReference<String>();
        var renderCount = new AtomicInteger();
        StatusLineController c = controller(
            _ -> {
                if (calls.incrementAndGet() == 1) {
                    firstStarted.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException _) {
                        firstInterrupted.countDown();
                        Thread.currentThread().interrupt();
                    }
                    return Optional.empty();
                }
                return Optional.of(new StatusLinePort.Output("new", 0));
            },
            rendered, renderCount, new AtomicInteger(), new AtomicReference<>());

        c.scheduleInitialUpdate();
        assertTrue(firstStarted.await(3, TimeUnit.SECONDS), "first command should start");

        c.scheduleInitialUpdate();

        assertTrue(firstInterrupted.await(3, TimeUnit.SECONDS),
            "starting doUpdate must abort the previous command");
        assertTrue(waitFor(() -> renderCount.get() == 1), "replacement should render");
        assertEquals("new", rendered.get());
        c.close();
    }

    @Test
    void immediateRefreshReadsChangedRuntimeModel() throws Exception {
        var model = new AtomicReference<>("anthropic.claude-sonnet-5");
        var rendered = new AtomicReference<String>();
        var renderCount = new AtomicInteger();
        StatusLineController c = new StatusLineController(
            StatusLinePort.disabled(),
            () -> new StatusLineInputBuilder.Ingredients(
                "sess", null, "/t/sess.jsonl", "/t", "/t", List.of(),
                model.get(), null, null, "0.1.0"),
            List::of,
            Runnable::run,
            (text, _) -> { rendered.set(text); renderCount.incrementAndGet(); },
            () -> {},
            () -> true,
            () -> 120);

        c.scheduleInitialUpdate();
        assertTrue(waitFor(() -> renderCount.get() == 1));
        assertTrue(Strings.CS.contains(
            rendered.get(), "[Sonnet 5]"), rendered.get());

        model.set("gpt-5.6-sol");
        c.scheduleInitialUpdate();
        assertTrue(waitFor(() -> renderCount.get() == 2));
        assertTrue(Strings.CS.contains(rendered.get(), "[gpt-5.6-sol]"), rendered.get());
    }

    @Test
    void debouncedAssistantRefreshReadsFinalizedGptUsage() throws Exception {
        List<Message> messages = new ArrayList<>();
        messages.add(assistant(Usage.EMPTY));
        var rendered = new AtomicReference<String>();
        var renderCount = new AtomicInteger();
        StatusLineController c = new StatusLineController(
            StatusLinePort.disabled(),
            StatusLineControllerTest::gptIngredients,
            () -> messages,
            Runnable::run,
            (text, _) -> { rendered.set(text); renderCount.incrementAndGet(); },
            () -> {},
            () -> true,
            () -> 120);

        // QueryLoop emits the status-line signal only after writing final usage
        // from message_delta into the same conversation slot. The controller's
        // debounce must snapshot that finalized value, not the initial Usage.EMPTY.
        c.scheduleUpdate();
        messages.set(0, assistant(new Usage(51_845, 101, 0, 108_032)));

        assertTrue(waitFor(() -> renderCount.get() == 1), "GPT HUD should refresh");
        assertTrue(Strings.CS.contains(rendered.get(), "43%"), rendered.get());
    }

    @Test
    void gptToolLoopDoesNotLetNextProvisionalRoundHideFinalizedUsage() throws Exception {
        List<Message> messages = new ArrayList<>();
        messages.add(assistant("assistant-1", "response-1",
            new Usage(20_000, 100, 0, 60_000), "tool_use"));
        var rendered = new AtomicReference<String>();
        var renderCount = new AtomicInteger();
        StatusLineController c = new StatusLineController(
            StatusLinePort.disabled(),
            () -> new StatusLineInputBuilder.Ingredients(
                "sess", null, "/t/sess.jsonl", "/t", "/t", List.of(),
                "gpt-5.6-sol", null, null, "0.1.0", 200_000L),
            () -> messages,
            Runnable::run,
            (text, _) -> { rendered.set(text); renderCount.incrementAndGet(); },
            () -> {},
            () -> true,
            () -> 120);

        c.scheduleUpdate();
        // The next Responses request starts before the previous 300 ms HUD
        // refresh fires. Its first assistant block has no terminal delta yet.
        messages.add(assistant("assistant-2", "response-2", Usage.EMPTY, null));

        assertTrue(waitFor(() -> renderCount.get() == 1));
        assertTrue(Strings.CS.contains(rendered.get(), "40%"), rendered.get());

        messages.set(1, assistant("assistant-2", "response-2",
            new Usage(25_000, 100, 0, 75_000), "end_turn"));
        c.scheduleUpdate();

        assertTrue(waitFor(() -> renderCount.get() == 2));
        assertTrue(Strings.CS.contains(rendered.get(), "50%"), rendered.get());
    }

    private static AssistantMessage assistant(Usage usage) {
        return assistant("assistant-1", "response-1", usage, "tool_use");
    }

    private static AssistantMessage assistant(
            String uuid, String responseId, Usage usage, String stopReason) {
        return new AssistantMessage(uuid, AssistantContent.apiResponse(
            responseId, List.of(new TextBlock("done")), usage,
            "gpt-5.6-sol", stopReason, null));
    }

    /** Polls {@code cond} for up to 3s (async: debounce + subprocess + thread hop). */
    private static boolean waitFor(BooleanSupplier cond) throws Exception {
        for (int i = 0; i < 300; i++) {
            if (cond.getAsBoolean()) return true;
            Thread.sleep(10);
        }
        return cond.getAsBoolean();
    }
}
