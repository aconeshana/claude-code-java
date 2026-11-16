package com.claudecode.runtime.interaction;

import static org.junit.jupiter.api.Assertions.*;

import com.claudecode.core.engine.PermissionAskCallback;
import com.claudecode.core.engine.PermissionAskContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.apache.commons.lang3.Strings;

class InteractionCoordinatorTest {

    @Test
    void capabilityMatrixDeclaresEveryKindAtBothEndpoints() {
        for (InteractionKind kind : InteractionKind.values()) {
            assertTrue(InteractionCapabilities.support(kind, InteractionEndpoint.LOCAL) != null);
            assertTrue(InteractionCapabilities.support(kind, InteractionEndpoint.REMOTE) != null);
        }
        // Sudo is answerable from a web client too: the password is typed
        // either in the terminal dialog or in the browser card, and whichever
        // answers first wins.
        assertEquals(InteractionSupport.SUPPORTED,
            InteractionCapabilities.support(
                InteractionKind.SUDO_PASSWORD, InteractionEndpoint.REMOTE));
    }

    @Test
    void firstResponderWinsAndLateOrCrossSessionResponsesAreRejected() throws Exception {
        InteractionCoordinator coordinator = new InteractionCoordinator(() -> "session-1");
        List<InteractionResolution<PermissionAskCallback.Result>> resolutions =
            new ArrayList<>();
        coordinator.register(InteractionFeatures.PERMISSION,
            presenter(InteractionEndpoint.LOCAL, _ -> {}));
        coordinator.register(InteractionFeatures.PERMISSION,
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
                        InteractionRequest<PermissionAskContext,
                            PermissionAskCallback.Result> request) {
                    assertFalse(coordinator.respond(InteractionFeatures.PERMISSION,
                        request.descriptor().id(), "other-session",
                        PermissionAskCallback.Result.allow(), endpoint()));
                    assertTrue(coordinator.respond(InteractionFeatures.PERMISSION,
                        request.descriptor().id(), request.descriptor().sessionId(),
                        PermissionAskCallback.Result.allow(), endpoint()));
                    assertFalse(coordinator.respond(InteractionFeatures.PERMISSION,
                        request.descriptor().id(), request.descriptor().sessionId(),
                        PermissionAskCallback.Result.deny(), InteractionEndpoint.LOCAL));
                }

                @Override public void resolved(
                        InteractionResolution<PermissionAskCallback.Result> resolution) {
                    resolutions.add(resolution);
                }
            });

        PermissionAskCallback.Result result = coordinator.ask(
            PermissionAskContext.simple("Bash", null, "tool-1"));

        assertTrue(result.allowed());
        assertEquals(1, resolutions.size());
        assertEquals(InteractionEndpoint.REMOTE, resolutions.getFirst().origin());
    }

    @Test
    void sudoIsFirstResponderAcrossTerminalAndWeb() throws Exception {
        InteractionCoordinator coordinator = new InteractionCoordinator(() -> "session-1");
        CountDownLatch bothPresented = new CountDownLatch(2);
        AtomicReference<InteractionEndpoint> resolver = new AtomicReference<>();
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
                    bothPresented.countDown();
                }
            });
        coordinator.register(InteractionFeatures.SUDO_PASSWORD,
            new InteractionPresenter<>() {
                @Override public InteractionEndpoint endpoint() {
                    return InteractionEndpoint.LOCAL;
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
                    bothPresented.countDown();
                    try {
                        // Wait until both prompts are up before answering, so
                        // the web answer really races a live terminal prompt.
                        bothPresented.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                    }
                    resolver.set(InteractionEndpoint.REMOTE);
                    assertTrue(coordinator.respond(InteractionFeatures.SUDO_PASSWORD,
                        request.descriptor().id(), request.descriptor().sessionId(),
                        SudoPasswordInteraction.Result.provided("hunter2".toCharArray()),
                        InteractionEndpoint.REMOTE));
                    // The terminal's late answer for the same ask is refused:
                    // a sudo ask is answered exactly once.
                    assertFalse(coordinator.respond(InteractionFeatures.SUDO_PASSWORD,
                        request.descriptor().id(), request.descriptor().sessionId(),
                        SudoPasswordInteraction.Result.cancelled(),
                        InteractionEndpoint.LOCAL));
                }
            });

        SudoPasswordInteraction.Result result = coordinator.request(
            new SudoPasswordInteraction.Request("/usr/bin/sudo", "sudo -v"));

        assertEquals(InteractionEndpoint.REMOTE, resolver.get());
        assertInstanceOf(SudoPasswordInteraction.Result.Provided.class, result);
        // Neither the ask nor the answer leaks the command or the password.
        assertFalse(Strings.CS.contains(result.toString(), "hunter2"));
        assertFalse(Strings.CS.contains(
            new SudoPasswordInteraction.Request("/usr/bin/sudo", "sudo -v").toString(),
            "sudo -v"));
    }

    @Test
    void blockingLocalPromptDoesNotHoldTheAskBackFromRemoteObservers() throws Exception {
        InteractionCoordinator coordinator = new InteractionCoordinator(() -> "session-1");
        CountDownLatch remotePresented = new CountDownLatch(1);
        CountDownLatch terminalDismissed = new CountDownLatch(1);
        AtomicReference<String> askId = new AtomicReference<>();
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
                    // The browser types the password while the terminal
                    // dialog is still waiting on its own answer.
                    askId.set(request.descriptor().id());
                    remotePresented.countDown();
                }
            });
        coordinator.register(InteractionFeatures.SUDO_PASSWORD,
            new InteractionPresenter<>() {
                @Override public InteractionEndpoint endpoint() {
                    return InteractionEndpoint.LOCAL;
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
                    // The terminal dialog blocks until the user answers it —
                    // exactly like the real TUI modal.
                    try {
                        terminalDismissed.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                    }
                }
            });

        CompletableFuture<SudoPasswordInteraction.Result> ask = CompletableFuture.supplyAsync(
            () -> coordinator.request(
                new SudoPasswordInteraction.Request("/usr/bin/sudo", "sudo -v")));
        // The web card shows up while the terminal prompt is still open, and
        // the password typed there is the answer the sudo run receives.
        assertTrue(remotePresented.await(2, TimeUnit.SECONDS),
            "the web observer must see the ask while the terminal prompt is open");
        SudoPasswordInteraction.Result typedInBrowser = SudoPasswordInteraction.Result
            .provided("typed-in-browser".toCharArray());
        assertTrue(coordinator.respond(InteractionFeatures.SUDO_PASSWORD,
            askId.get(), "session-1", typedInBrowser, InteractionEndpoint.REMOTE));
        terminalDismissed.countDown();

        assertSame(typedInBrowser, ask.get(2, TimeUnit.SECONDS));
    }

    @Test
    void duplicateEndpointRegistrationIsRejected() {
        InteractionCoordinator coordinator = new InteractionCoordinator(() -> "session-1");
        coordinator.register(InteractionFeatures.PERMISSION,
            presenter(InteractionEndpoint.LOCAL, _ -> {}));

        assertThrows(IllegalStateException.class, () -> coordinator.register(
            InteractionFeatures.PERMISSION,
            presenter(InteractionEndpoint.LOCAL, _ -> {})));
    }

    @Test
    void brokenPresenterDoesNotBlockHealthyPresenter() {
        InteractionCoordinator coordinator = new InteractionCoordinator(() -> "session-1");
        coordinator.register(InteractionFeatures.PERMISSION,
            new InteractionPresenter<>() {
                @Override public InteractionEndpoint endpoint() {
                    return InteractionEndpoint.LOCAL;
                }

                @Override public InteractionSupport support() {
                    return InteractionSupport.SUPPORTED;
                }

                @Override public boolean available(String sessionId) {
                    throw new IllegalStateException("broken local endpoint");
                }

                @Override public void present(
                        InteractionRequest<PermissionAskContext,
                            PermissionAskCallback.Result> request) {}
            });
        coordinator.register(InteractionFeatures.PERMISSION,
            presenter(InteractionEndpoint.REMOTE, request -> assertTrue(
                coordinator.respond(InteractionFeatures.PERMISSION,
                    request.descriptor().id(), request.descriptor().sessionId(),
                    PermissionAskCallback.Result.allow(), InteractionEndpoint.REMOTE))));

        assertTrue(coordinator.ask(
            PermissionAskContext.simple("Bash", null, "tool-1")).allowed());
    }

    @Test
    void cancellationUsesEachFeatureFallback() throws Exception {
        InteractionCoordinator coordinator = new InteractionCoordinator(() -> "session-1");
        CountDownLatch permissionPresented = new CountDownLatch(1);
        CountDownLatch sudoPresented = new CountDownLatch(1);
        coordinator.register(InteractionFeatures.PERMISSION,
            presenter(InteractionEndpoint.LOCAL, _ -> permissionPresented.countDown()));
        coordinator.register(InteractionFeatures.SUDO_PASSWORD,
            new InteractionPresenter<>() {
                @Override public InteractionEndpoint endpoint() {
                    return InteractionEndpoint.LOCAL;
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
                    sudoPresented.countDown();
                }
            });

        CompletableFuture<PermissionAskCallback.Result> permission =
            CompletableFuture.supplyAsync(() -> coordinator.ask(
                PermissionAskContext.simple("Bash", null, "tool-1")));
        CompletableFuture<SudoPasswordInteraction.Result> sudo =
            CompletableFuture.supplyAsync(() -> coordinator.request(
                new SudoPasswordInteraction.Request("/usr/bin/sudo", "sudo -v")));
        assertTrue(permissionPresented.await(2, TimeUnit.SECONDS));
        assertTrue(sudoPresented.await(2, TimeUnit.SECONDS));

        assertEquals(2, coordinator.cancelSession("session-1"));
        assertFalse(permission.get(2, TimeUnit.SECONDS).allowed());
        assertInstanceOf(SudoPasswordInteraction.Result.Cancelled.class,
            sudo.get(2, TimeUnit.SECONDS));
    }

    @Test
    void sudoWithoutLocalPresenterIsUnavailableAndSecretStringsStayRedacted() {
        InteractionCoordinator coordinator = new InteractionCoordinator(() -> "session-1");
        SudoPasswordInteraction.Request request =
            new SudoPasswordInteraction.Request("/usr/bin/sudo", "sudo secret-command");

        SudoPasswordInteraction.Result result = coordinator.request(request);

        assertInstanceOf(SudoPasswordInteraction.Result.Unavailable.class, result);
        assertFalse(Strings.CS.contains(request.toString(), "secret-command"));
        try (SudoPasswordInteraction.Result.Provided provided =
                SudoPasswordInteraction.Result.provided("secret-value".toCharArray())) {
            assertFalse(Strings.CS.contains(provided.toString(), "secret-value"));
            assertFalse(Strings.CS.contains(provided.toString(), "12"));
        }
    }

    @Test
    void unavailableRemoteEndpointDoesNotPublishUnsupportedNotice() {
        InteractionCoordinator coordinator = new InteractionCoordinator(() -> "session-1");
        AtomicInteger notices = new AtomicInteger();
        coordinator.register(InteractionFeatures.SUDO_PASSWORD,
            new InteractionPresenter<>() {
                @Override public InteractionEndpoint endpoint() {
                    return InteractionEndpoint.REMOTE;
                }

                @Override public InteractionSupport support() {
                    return InteractionSupport.SUPPORTED;
                }

                @Override public boolean available(String sessionId) {
                    // No browser attached: the terminal still gets its prompt.
                    return false;
                }

                @Override public void present(
                        InteractionRequest<SudoPasswordInteraction.Request,
                            SudoPasswordInteraction.Result> request) {
                    throw new AssertionError("unavailable presenter must not receive requests");
                }

                @Override public void unsupported(InteractionUnsupported event) {
                    notices.incrementAndGet();
                }
            });
        coordinator.register(InteractionFeatures.SUDO_PASSWORD,
            new InteractionPresenter<>() {
                @Override public InteractionEndpoint endpoint() {
                    return InteractionEndpoint.LOCAL;
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
                    coordinator.respond(InteractionFeatures.SUDO_PASSWORD,
                        request.descriptor().id(), request.descriptor().sessionId(),
                        SudoPasswordInteraction.Result.cancelled(), endpoint());
                }
            });

        coordinator.request(new SudoPasswordInteraction.Request("/usr/bin/sudo", "sudo -v"));

        assertEquals(0, notices.get());
    }

    private static InteractionPresenter<PermissionAskContext,
            PermissionAskCallback.Result> presenter(
                InteractionEndpoint endpoint,
                Consumer<InteractionRequest<PermissionAskContext,
                    PermissionAskCallback.Result>> consumer) {
        return new InteractionPresenter<>() {
            @Override public InteractionEndpoint endpoint() { return endpoint; }
            @Override public InteractionSupport support() { return InteractionSupport.SUPPORTED; }
            @Override public boolean available(String sessionId) { return true; }
            @Override public void present(
                    InteractionRequest<PermissionAskContext,
                        PermissionAskCallback.Result> request) {
                consumer.accept(request);
            }
        };
    }
}
