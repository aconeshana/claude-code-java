package com.claudecode.runtime.interaction;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.engine.PermissionAskCallback;
import com.claudecode.core.engine.PermissionAskContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Typed human-interaction coordinator shared by local and remote presenters.
 *
 * <p>The local endpoint holds one presenter (the single TUI); the remote
 * endpoint is a broadcast list — the IM link and the web gateway both
 * register remote presenters, and every pending ask reaches all of them.
 * The first response from any observer wins, per each feature's response
 * policy.
 */
@Explanation("Coordinates typed local and remote human interactions, including sudo input")
public final class InteractionCoordinator
        implements PermissionAskCallback, SudoPasswordInteraction, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(InteractionCoordinator.class);

    private record RegistrationKey(InteractionKind kind, InteractionEndpoint endpoint) {}
    private record Pending<Q, R>(
        InteractionRequest<Q, R> request, CompletableFuture<R> result) {}

    private final Supplier<String> sessionId;
    /** Local presenters: one per kind (the single TUI owns this endpoint). */
    private final ConcurrentHashMap<InteractionKind, InteractionPresenter<?, ?>> localPresenters =
        new ConcurrentHashMap<>();
    /** Remote presenters: a broadcast list (IM link, web gateway, …). */
    private final ConcurrentHashMap<InteractionKind,
        List<InteractionPresenter<?, ?>>> remotePresenters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Pending<?, ?>> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public InteractionCoordinator(Supplier<String> sessionId) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
    }

    public <Q, R> AutoCloseable register(
            InteractionFeature<Q, R> feature,
            InteractionPresenter<Q, R> presenter) {
        Objects.requireNonNull(feature, "feature");
        Objects.requireNonNull(presenter, "presenter");
        requireCanonicalFeature(feature);
        if (closed.get()) throw new IllegalStateException("interaction coordinator is closed");
        InteractionSupport declared = InteractionCapabilities.support(
            feature.kind(), presenter.endpoint());
        if (declared == null || declared != presenter.support()) {
            throw new IllegalArgumentException("presenter support does not match capability matrix");
        }
        if (presenter.endpoint() == InteractionEndpoint.LOCAL) {
            InteractionPresenter<?, ?> previous =
                localPresenters.putIfAbsent(feature.kind(), presenter);
            if (previous != null) {
                throw new IllegalStateException(
                    "interaction presenter is already registered: " + feature.kind());
            }
            return () -> localPresenters.remove(feature.kind(), presenter);
        }
        // The remote endpoint broadcasts: multiple remote observers (IM link,
        // web gateway) coexist; each sees every pending ask.
        List<InteractionPresenter<?, ?>> observers =
            remotePresenters.computeIfAbsent(feature.kind(), _ -> new CopyOnWriteArrayList<>());
        if (!observers.contains(presenter)) observers.add(presenter);
        return () -> observers.remove(presenter);
    }

    @Override public PermissionAskCallback.Result ask(PermissionAskContext context) {
        Objects.requireNonNull(context, "context");
        InteractionFeature<PermissionAskContext, PermissionAskCallback.Result> feature =
            Strings.CS.equals("AskUserQuestion", context.toolName())
                ? InteractionFeatures.USER_QUESTION
                : InteractionFeatures.PERMISSION;
        return request(feature, context);
    }

    @Override public SudoPasswordInteraction.Result request(
            SudoPasswordInteraction.Request request) {
        return request(InteractionFeatures.SUDO_PASSWORD, request);
    }

    public <Q, R> R request(InteractionFeature<Q, R> feature, Q payload) {
        Objects.requireNonNull(feature, "feature");
        Objects.requireNonNull(payload, "payload");
        requireCanonicalFeature(feature);
        if (!feature.requestType().isInstance(payload)) {
            throw new IllegalArgumentException("interaction payload type does not match feature");
        }
        if (closed.get()) return feature.unavailableResult().get();

        InteractionDescriptor descriptor = new InteractionDescriptor(
            UUID.randomUUID().toString(), Objects.requireNonNullElse(sessionId.get(), ""),
            feature.kind(), feature.responsePolicy(), feature.sensitivity(), Instant.now());
        InteractionRequest<Q, R> request = new InteractionRequest<>(descriptor, feature, payload);
        Pending<Q, R> entry = new Pending<>(request, new CompletableFuture<>());
        pending.put(descriptor.id(), entry);

        int responders = 0;
        List<InteractionEndpoint> presentationOrder =
            feature.responsePolicy() == InteractionResponsePolicy.LOCAL_ONLY
                ? List.of(InteractionEndpoint.REMOTE, InteractionEndpoint.LOCAL)
                : List.of(InteractionEndpoint.LOCAL, InteractionEndpoint.REMOTE);
        for (InteractionEndpoint endpoint : presentationOrder) {
            for (InteractionPresenter<Q, R> presenter : presenters(feature, endpoint)) {
                try {
                    if (!presenter.available(descriptor.sessionId())) continue;
                    presenter.present(request);
                    if (presenter.support() == InteractionSupport.SUPPORTED
                            && (feature.responsePolicy() != InteractionResponsePolicy.LOCAL_ONLY
                                || endpoint == InteractionEndpoint.LOCAL)) {
                        responders++;
                    }
                } catch (InteractionNotImplementedException unsupported) {
                    log.warn("Interaction endpoint is unimplemented: requestId={}, sessionId={}, kind={}, endpoint={}",
                        descriptor.id(), descriptor.sessionId(), descriptor.kind(), endpoint);
                    try {
                        presenter.unsupported(new InteractionUnsupported(
                            descriptor, endpoint, unsupported.action()));
                    } catch (RuntimeException notificationFailure) {
                        logSecretSafeFailure("Interaction unsupported notification failed",
                            descriptor, endpoint, notificationFailure);
                    }
                } catch (RuntimeException failure) {
                    logSecretSafeFailure("Interaction presenter failed",
                        descriptor, endpoint, failure);
                }
            }
            if (!pending.containsKey(descriptor.id())) break;
        }
        if (responders == 0 && pending.remove(descriptor.id(), entry)) {
            entry.result().complete(feature.unavailableResult().get());
        }
        return entry.result().join();
    }

    public <Q, R> boolean respond(
            InteractionFeature<Q, R> feature,
            String requestId,
            String respondingSessionId,
            R result,
            InteractionEndpoint origin) {
        Objects.requireNonNull(feature, "feature");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(origin, "origin");
        requireCanonicalFeature(feature);
        if (requestId == null || closed.get() || !feature.resultType().isInstance(result)) {
            return false;
        }
        Pending<?, ?> raw = pending.get(requestId);
        if (raw == null || raw.request().feature() != feature
                || !Objects.equals(raw.request().descriptor().sessionId(), respondingSessionId)
                || (feature.responsePolicy() == InteractionResponsePolicy.LOCAL_ONLY
                    && origin != InteractionEndpoint.LOCAL)
                || !pending.remove(requestId, raw)) {
            return false;
        }
        @SuppressWarnings("unchecked") Pending<Q, R> entry = (Pending<Q, R>) raw;
        resolve(entry, result, origin);
        return true;
    }

    public List<InteractionRequest<?, ?>> pendingRequests(String requestedSessionId) {
        List<InteractionRequest<?, ?>> result = new ArrayList<>();
        for (Pending<?, ?> entry : pending.values()) {
            if (Objects.equals(requestedSessionId,
                    entry.request().descriptor().sessionId())) {
                result.add(entry.request());
            }
        }
        return List.copyOf(result);
    }

    public int cancelSession(String requestedSessionId) {
        int cancelled = 0;
        for (Pending<?, ?> raw : List.copyOf(pending.values())) {
            if (!Objects.equals(requestedSessionId,
                    raw.request().descriptor().sessionId())
                    || !pending.remove(raw.request().descriptor().id(), raw)) continue;
            cancelled++;
            cancel(raw);
        }
        return cancelled;
    }

    private <Q, R> void resolve(Pending<Q, R> entry, R result, InteractionEndpoint origin) {
        InteractionFeature<Q, R> feature = entry.request().feature();
        InteractionResolution<R> resolution = new InteractionResolution<>(
            entry.request().descriptor(), result, origin);
        for (InteractionEndpoint endpoint : List.of(
                InteractionEndpoint.LOCAL, InteractionEndpoint.REMOTE)) {
            if (feature.responsePolicy() == InteractionResponsePolicy.LOCAL_ONLY
                && endpoint != InteractionEndpoint.LOCAL) continue;
            for (InteractionPresenter<Q, R> presenter : presenters(feature, endpoint)) {
                if (presenter.support() != InteractionSupport.SUPPORTED) continue;
                try {
                    presenter.resolved(resolution);
                } catch (RuntimeException failure) {
                    logSecretSafeFailure("Interaction resolution presenter failed",
                        entry.request().descriptor(), endpoint, failure);
                }
            }
        }
        entry.result().complete(result);
    }

    private void cancel(Pending<?, ?> raw) {
        cancelTyped(raw);
    }

    private <Q, R> void cancelTyped(Pending<Q, R> entry) {
        resolve(entry, entry.request().feature().cancelledResult().get(),
            InteractionEndpoint.HOST);
    }

    /** The presenters bound to one feature and endpoint; empty when none. */
    @SuppressWarnings("unchecked")
    private <Q, R> List<InteractionPresenter<Q, R>> presenters(
            InteractionFeature<Q, R> feature, InteractionEndpoint endpoint) {
        if (endpoint == InteractionEndpoint.LOCAL) {
            InteractionPresenter<?, ?> presenter = localPresenters.get(feature.kind());
            return presenter == null ? List.of() : List.of((InteractionPresenter<Q, R>) presenter);
        }
        List<InteractionPresenter<?, ?>> observers = remotePresenters.get(feature.kind());
        return observers == null ? List.of()
            : observers.stream().map(p -> (InteractionPresenter<Q, R>) p).toList();
    }

    private static void requireCanonicalFeature(InteractionFeature<?, ?> feature) {
        if (InteractionFeatures.canonical(feature.kind()) != feature) {
            throw new IllegalArgumentException("interaction feature is not canonical");
        }
    }

    private static void logSecretSafeFailure(
            String message,
            InteractionDescriptor descriptor,
            InteractionEndpoint endpoint,
            RuntimeException failure) {
        if (descriptor.sensitivity() == InteractionSensitivity.SECRET) {
            log.warn("{}: requestId={}, sessionId={}, kind={}, endpoint={}, failureType={}",
                message, descriptor.id(), descriptor.sessionId(), descriptor.kind(), endpoint,
                failure.getClass().getName());
            return;
        }
        log.warn("{}: requestId={}, sessionId={}, kind={}, endpoint={}",
            message, descriptor.id(), descriptor.sessionId(), descriptor.kind(), endpoint, failure);
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (Map.Entry<String, Pending<?, ?>> item : List.copyOf(pending.entrySet())) {
            if (pending.remove(item.getKey(), item.getValue())) cancel(item.getValue());
        }
        localPresenters.clear();
        remotePresenters.clear();
    }
}
