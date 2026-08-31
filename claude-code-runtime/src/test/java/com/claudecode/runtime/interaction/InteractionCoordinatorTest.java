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
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class InteractionCoordinatorTest {

    @Test
    void capabilityMatrixDeclaresEveryKindAtBothEndpoints() {
        for (InteractionKind kind : InteractionKind.values()) {
            assertTrue(InteractionCapabilities.support(kind, InteractionEndpoint.LOCAL) != null);
            assertTrue(InteractionCapabilities.support(kind, InteractionEndpoint.REMOTE) != null);
        }
        assertEquals(InteractionSupport.UNIMPLEMENTED,
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
    void localOnlySudoReportsRemoteUnimplementedButWaitsForLocalResult() throws Exception {
        InteractionCoordinator coordinator = new InteractionCoordinator(() -> "session-1");
        CompletableFuture<InteractionUnsupported> unsupported = new CompletableFuture<>();
        coordinator.register(InteractionFeatures.SUDO_PASSWORD,
            new InteractionPresenter<>() {
                @Override public InteractionEndpoint endpoint() {
                    return InteractionEndpoint.REMOTE;
                }

                @Override public InteractionSupport support() {
                    return InteractionSupport.UNIMPLEMENTED;
                }

                @Override public boolean available(String sessionId) {
                    return true;
                }

                @Override public void present(
                        InteractionRequest<SudoPasswordInteraction.Request,
                            SudoPasswordInteraction.Result> request) {
                    throw new InteractionNotImplementedException(
                        request.descriptor(), endpoint(), "complete_in_tui");
                }

                @Override public void unsupported(InteractionUnsupported event) {
                    unsupported.complete(event);
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
                    assertFalse(coordinator.respond(InteractionFeatures.SUDO_PASSWORD,
                        request.descriptor().id(), request.descriptor().sessionId(),
                        SudoPasswordInteraction.Result.cancelled(),
                        InteractionEndpoint.REMOTE));
                    assertTrue(coordinator.respond(InteractionFeatures.SUDO_PASSWORD,
                        request.descriptor().id(), request.descriptor().sessionId(),
                        SudoPasswordInteraction.Result.cancelled(), endpoint()));
                }
            });

        SudoPasswordInteraction.Result result = coordinator.request(
            new SudoPasswordInteraction.Request("/usr/bin/sudo", "sudo -v"));

        assertInstanceOf(SudoPasswordInteraction.Result.Cancelled.class, result);
        InteractionUnsupported event = unsupported.get(2, TimeUnit.SECONDS);
        assertEquals(InteractionKind.SUDO_PASSWORD, event.descriptor().kind());
        assertEquals("complete_in_tui", event.action());
        assertFalse(event.toString().contains("sudo -v"));
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
        assertFalse(request.toString().contains("secret-command"));
        try (SudoPasswordInteraction.Result.Provided provided =
                SudoPasswordInteraction.Result.provided("secret-value".toCharArray())) {
            assertFalse(provided.toString().contains("secret-value"));
            assertFalse(provided.toString().contains("12"));
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
                    return InteractionSupport.UNIMPLEMENTED;
                }

                @Override public boolean available(String sessionId) {
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
