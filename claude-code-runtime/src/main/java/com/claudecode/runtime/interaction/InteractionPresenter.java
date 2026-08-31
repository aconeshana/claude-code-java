package com.claudecode.runtime.interaction;

/** One explicit channel adapter for a strongly typed interaction feature. */
public interface InteractionPresenter<Q, R> {
    InteractionEndpoint endpoint();
    InteractionSupport support();
    boolean available(String sessionId);
    void present(InteractionRequest<Q, R> request);
    default void resolved(InteractionResolution<R> resolution) {}
    default void unsupported(InteractionUnsupported event) {}
}
