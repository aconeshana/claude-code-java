package com.claudecode.sdk;

/** Options for creating an offline branch of a stored session. */
public record ForkSessionOptions(String dir, SessionStore sessionStore,
                                 String upToMessageId, String title) {
    public static ForkSessionOptions defaults() { return new ForkSessionOptions(null, null, null, null); }
}
