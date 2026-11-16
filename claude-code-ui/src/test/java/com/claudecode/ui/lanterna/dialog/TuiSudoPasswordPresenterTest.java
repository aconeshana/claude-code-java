package com.claudecode.ui.lanterna.dialog;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.runtime.interaction.InteractionCoordinator;
import com.claudecode.runtime.interaction.InteractionEndpoint;
import com.claudecode.runtime.interaction.InteractionFeatures;
import com.claudecode.runtime.interaction.InteractionPresenter;
import com.claudecode.runtime.interaction.InteractionRequest;
import com.claudecode.runtime.interaction.InteractionSupport;
import com.claudecode.runtime.interaction.SudoPasswordInteraction;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TuiSudoPasswordPresenterTest {

    @Test
    void localPresenterReturnsOneShotPasswordToCoordinator() throws Exception {
        InteractionCoordinator coordinator = new InteractionCoordinator(() -> "session-1");
        coordinator.register(InteractionFeatures.SUDO_PASSWORD,
            new TuiSudoPasswordPresenter(coordinator, request -> {
                assertEquals("/usr/bin/sudo", request.executable());
                return SudoPasswordInteraction.Result.provided("secret".toCharArray());
            }));

        try (SudoPasswordInteraction.Result.Provided provided =
                (SudoPasswordInteraction.Result.Provided) coordinator.request(
                    new SudoPasswordInteraction.Request("/usr/bin/sudo", "sudo -v"))) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            provided.writeTo(output);
            assertArrayEquals("secret\n".getBytes(StandardCharsets.UTF_8),
                output.toByteArray());
        }
    }

    @Test
    void webAnswerClosesTheTerminalPromptInsteadOfLeavingItOpen() throws Exception {
        InteractionCoordinator coordinator = new InteractionCoordinator(() -> "session-1");
        AtomicInteger dismissals = new AtomicInteger();
        CountDownLatch promptOpen = new CountDownLatch(1);
        CountDownLatch promptDismissed = new CountDownLatch(1);
        // The terminal prompt stays up until the web answer closes it, which
        // is exactly what the real modal does.
        coordinator.register(InteractionFeatures.SUDO_PASSWORD,
            new TuiSudoPasswordPresenter(
                coordinator,
                _ -> {
                    promptOpen.countDown();
                    try {
                        promptDismissed.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                    }
                    return SudoPasswordInteraction.Result.cancelled();
                },
                () -> {
                    dismissals.incrementAndGet();
                    promptDismissed.countDown();
                }));
        coordinator.register(InteractionFeatures.SUDO_PASSWORD,
            new InteractionPresenter<>() {
                @Override public InteractionEndpoint endpoint() {
                    return InteractionEndpoint.REMOTE;
                }

                @Override public InteractionSupport support() {
                    return InteractionSupport.SUPPORTED;
                }

                @Override public boolean available(String sessionId) {
                    return true;
                }

                @Override public void present(
                        InteractionRequest<SudoPasswordInteraction.Request,
                            SudoPasswordInteraction.Result> request) {
                    promptOpen.countDown();
                }
            });

        CompletableFuture<SudoPasswordInteraction.Result> ask = CompletableFuture.supplyAsync(
            () -> coordinator.request(
                new SudoPasswordInteraction.Request("/usr/bin/sudo", "sudo -v")));
        assertTrue(promptOpen.await(2, TimeUnit.SECONDS));
        String requestId = coordinator.pendingRequests("session-1").getFirst().descriptor().id();
        assertTrue(coordinator.respond(InteractionFeatures.SUDO_PASSWORD,
            requestId, "session-1",
            SudoPasswordInteraction.Result.provided("typed-in-browser".toCharArray()),
            InteractionEndpoint.REMOTE));

        SudoPasswordInteraction.Result result = ask.get(2, TimeUnit.SECONDS);
        assertEquals(1, dismissals.get(),
            "the terminal prompt must be closed when the browser answers");
        // The password the browser typed is the one the sudo run receives; the
        // terminal's cancelled result lost the race.
        try (SudoPasswordInteraction.Result.Provided provided =
                assertInstanceOf(SudoPasswordInteraction.Result.Provided.class, result)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            provided.writeTo(output);
            assertEquals("typed-in-browser\n", output.toString(StandardCharsets.UTF_8));
        }
    }
}
