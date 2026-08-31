package com.claudecode.runtime.sessionhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.apache.commons.lang3.StringUtils;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.claudecode.runtime.turn.SessionEventHub;
import com.claudecode.runtime.turn.SessionSink;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import com.claudecode.core.message.SDKMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SessionHostRegistryTest {

    @Test
    void requestedCurrentSessionAttachesWithoutResuming() {
        List<SessionOpenRequest> activations = new ArrayList<>();
        SessionHostRegistry registry = new SessionHostRegistry(new SessionHostRegistry.Activator() {
            @Override public CompletableFuture<SessionHostSession> activate(SessionOpenRequest request) {
                activations.add(request);
                return CompletableFuture.completedFuture(session(request.requestedSessionId()));
            }
            @Override public List<SessionHostInfo> list() { return List.of(); }
        });
        SessionHostSession current = session("current");
        registry.activateLocal(current);

        SessionHostSession attached = registry.open(
            new SessionOpenRequest("current", "/project")).toCompletableFuture().join();

        assertSame(current, attached);
        assertEquals(List.of(), activations);
    }

    @Test
    void borrowsNonOwningViewOnlyForTheActiveSession() {
        SessionHostRegistry registry = new SessionHostRegistry(new SessionHostRegistry.Activator() {
            @Override public CompletableFuture<SessionHostSession> activate(SessionOpenRequest request) {
                return CompletableFuture.completedFuture(session(request.requestedSessionId()));
            }
            @Override public List<SessionHostInfo> list() { return List.of(); }
        });
        SessionHostSession current = session("current");
        registry.activateLocal(current);

        SessionHostSessionView view = registry.borrowCurrent("current").orElseThrow();

        assertEquals(current.info(), view.info());
        assertSame(current.models(), view.models());
        assertSame(current.efforts(), view.efforts());
        assertSame(current.compacts(), view.compacts());
        assertTrue(registry.borrowCurrent("other").isEmpty());
    }

    @Test
    void emptyOrDifferentIdDelegatesToUiActivatorAndPublishesOrigin() {
        List<String> lifecycle = new ArrayList<>();
        SessionHostRegistry registry = new SessionHostRegistry(new SessionHostRegistry.Activator() {
            @Override public CompletableFuture<SessionHostSession> activate(SessionOpenRequest request) {
                String id = StringUtils.isBlank(request.requestedSessionId()) ? "new-session" : request.requestedSessionId();
                return CompletableFuture.completedFuture(session(id));
            }

            @Override public List<SessionHostInfo> list() { return List.of(); }
        });
        registry.subscribe(event -> lifecycle.add(event.info().id() + ":" + event.origin()));

        registry.open(new SessionOpenRequest("", "/project")).toCompletableFuture().join();
        registry.open(new SessionOpenRequest("older", "/project")).toCompletableFuture().join();

        assertEquals(List.of("new-session:REMOTE", "older:REMOTE"), lifecycle);
        assertEquals("older", registry.current().orElseThrow().info().id());
    }

    @Test
    void remoteOpenPublishesOneCausalRemoteActivationWhenUiPerformsTheResume() {
        List<SessionHostRegistry.LifecycleEvent> lifecycle = new ArrayList<>();
        AtomicReference<SessionHostRegistry> registryRef = new AtomicReference<>();
        SessionHostRegistry registry = new SessionHostRegistry(new SessionHostRegistry.Activator() {
            @Override public CompletableFuture<SessionHostSession> activate(SessionOpenRequest request) {
                SessionHostSession created = session("new-session");
                registryRef.get().activateLocal(created);
                return CompletableFuture.completedFuture(created);
            }
            @Override public List<SessionHostInfo> list() { return List.of(); }
        });
        registryRef.set(registry);
        registry.subscribe(lifecycle::add);

        SessionHostRegistry.ActivationResult opened = registry.openActivation(
            new SessionOpenRequest("", "/project", "activation-remote-1"))
            .toCompletableFuture().join();

        assertEquals("new-session", opened.session().info().id());
        assertEquals("new-session", registry.current().orElseThrow().info().id());
        assertEquals(1, lifecycle.size(),
            "the native UI resume must publish exactly one authoritative activation");
        SessionHostRegistry.LifecycleEvent event = lifecycle.getFirst();
        assertEquals(SessionHostRegistry.Origin.REMOTE, event.origin());
        assertEquals("activation-remote-1", event.activationId());
        assertTrue(event.activationGeneration() > 0);
        assertEquals(event.activationGeneration(), opened.activationGeneration());
        assertEquals(event.activationId(), opened.activationId());
        assertEquals(SessionHostRegistry.Origin.REMOTE, opened.origin());
    }

    @Test
    void localActivationGenerationsIncreaseMonotonically() {
        List<Long> generations = new ArrayList<>();
        SessionHostRegistry registry = new SessionHostRegistry(new SessionHostRegistry.Activator() {
            @Override public CompletableFuture<SessionHostSession> activate(SessionOpenRequest request) {
                return CompletableFuture.completedFuture(session(request.requestedSessionId()));
            }
            @Override public List<SessionHostInfo> list() { return List.of(); }
        });
        registry.subscribe(event -> generations.add(event.activationGeneration()));

        registry.activateLocal(session("first"));
        registry.activateLocal(session("second"));

        assertEquals(2, generations.size());
        assertTrue(generations.getFirst() > 0);
        assertTrue(generations.get(1) > generations.getFirst());
    }

    @Test
    void attachingCurrentSessionEchoesActivationIdWithoutPublishingAnotherEvent() {
        List<SessionHostRegistry.LifecycleEvent> lifecycle = new ArrayList<>();
        SessionHostRegistry registry = new SessionHostRegistry(new SessionHostRegistry.Activator() {
            @Override public CompletableFuture<SessionHostSession> activate(SessionOpenRequest request) {
                throw new AssertionError("current session must not be resumed again");
            }
            @Override public List<SessionHostInfo> list() { return List.of(); }
        });
        registry.activateLocal(session("current"));
        registry.subscribe(lifecycle::add);

        SessionHostRegistry.ActivationResult attached = registry.openActivation(
            new SessionOpenRequest("current", "/project", "activation-idempotent"))
            .toCompletableFuture().join();

        assertEquals("current", attached.session().info().id());
        assertEquals("activation-idempotent", attached.activationId());
        assertTrue(attached.activationGeneration() > 0);
        assertEquals(SessionHostRegistry.Origin.LOCAL, attached.origin());
        assertTrue(lifecycle.isEmpty());
    }

    @Test
    void sessionOpenRequestRejectsControlCharactersAndUnboundedActivationIds() {
        assertThrows(IllegalArgumentException.class,
            () -> new SessionOpenRequest("session\nother", "/project", "activation-1"));
        assertThrows(IllegalArgumentException.class,
            () -> new SessionOpenRequest("session", "/project", "activation\nother"));
        assertThrows(IllegalArgumentException.class,
            () -> new SessionOpenRequest("session", "/project", "x".repeat(129)));
    }

    @Test
    void localActivationSupersedesAnInFlightRemoteActivation() {
        CompletableFuture<SessionHostSession> remote = new CompletableFuture<>();
        List<String> lifecycle = new ArrayList<>();
        SessionHostRegistry registry = new SessionHostRegistry(new SessionHostRegistry.Activator() {
            @Override public CompletableFuture<SessionHostSession> activate(SessionOpenRequest request) {
                return remote;
            }
            @Override public List<SessionHostInfo> list() { return List.of(); }
        });
        registry.subscribe(event -> lifecycle.add(event.info().id() + ":" + event.origin()));

        CompletableFuture<SessionHostSession> opening = registry.open(
            new SessionOpenRequest("remote", "/project")).toCompletableFuture();
        registry.activateLocal(session("local"));
        remote.complete(session("remote"));

        assertThrows(CompletionException.class, opening::join);
        assertEquals("local", registry.current().orElseThrow().info().id());
        assertEquals(List.of("local:LOCAL"), lifecycle,
            "a stale remote completion must not publish or replace the local session");
    }

    @Test
    void endingLocallyInvalidatesAnInFlightRemoteActivation() {
        CompletableFuture<SessionHostSession> remote = new CompletableFuture<>();
        SessionHostRegistry registry = new SessionHostRegistry(new SessionHostRegistry.Activator() {
            @Override public CompletableFuture<SessionHostSession> activate(SessionOpenRequest request) {
                return remote;
            }
            @Override public List<SessionHostInfo> list() { return List.of(); }
        });
        registry.activateLocal(session("local"));

        CompletableFuture<SessionHostSession> opening = registry.open(
            new SessionOpenRequest("remote", "/project")).toCompletableFuture();
        registry.endLocal("terminal_exit");
        remote.complete(session("remote"));

        assertThrows(CompletionException.class, opening::join);
        assertTrue(registry.current().isEmpty());
    }

    @Test
    void activeTurnPreventsAnotherThreadFromSwitchingTheTuiSession() {
        AtomicInteger activations = new AtomicInteger();
        SessionHostRegistry registry = new SessionHostRegistry(new SessionHostRegistry.Activator() {
            @Override public CompletableFuture<SessionHostSession> activate(SessionOpenRequest request) {
                activations.incrementAndGet();
                return CompletableFuture.completedFuture(session(request.requestedSessionId()));
            }
            @Override public List<SessionHostInfo> list() { return List.of(); }
        });
        SessionHostSession current = session("current");
        registry.activateLocal(current);
        current.events().onTurnStart(UserInput.of("prompt", "prompt", null, "default"));

        CompletableFuture<SessionHostSession> opening = registry.open(
            new SessionOpenRequest("other", "/project")).toCompletableFuture();

        assertThrows(CompletionException.class, opening::join);
        assertEquals(0, activations.get());
        assertEquals("current", registry.current().orElseThrow().info().id());
    }

    private static SessionHostSession session(String id) {
        SessionSink primary = new SessionSink() {
            @Override public void onTurnStart(UserInput input) {}
            @Override public void onMessage(SDKMessage msg) {}
            @Override public void onError(Throwable error, boolean userCancel) {}
            @Override public void onTurnComplete(TurnOutcome outcome) {}
            @Override public void onIdle() {}
        };
        return new SessionHostSession(
            new SessionHostInfo(id, "/project", "", 0, null, ""),
            new SessionEventHub(primary, _ -> {}),
            _ -> CompletableFuture.completedFuture(null));
    }

    @Test
    void metadataRefreshKeepsActivationGenerationAndPublishesUpdate() {
        SessionHostSession initial = session("current");
        SessionHostRegistry registry = new SessionHostRegistry(new SessionHostRegistry.Activator() {
            @Override public CompletableFuture<SessionHostSession> activate(SessionOpenRequest request) {
                return CompletableFuture.completedFuture(initial);
            }
            @Override public List<SessionHostInfo> list() { return List.of(initial.info()); }
        });
        List<SessionHostRegistry.LifecycleEvent> events = new ArrayList<>();
        registry.subscribe(events::add);
        registry.activateLocal(initial);
        long generation = registry.currentActivation().orElseThrow().activationGeneration();

        SessionHostSession titled = new SessionHostSession(
            new SessionHostInfo("current", "/project", "Readable title", 1, null, ""),
            initial.events(), initial.submitter());
        registry.refreshLocal(titled);

        assertEquals(SessionHostRegistry.EventType.UPDATED, events.getLast().type());
        assertEquals("Readable title", events.getLast().info().summary());
        assertEquals(generation,
            registry.currentActivation().orElseThrow().activationGeneration());
    }
}
