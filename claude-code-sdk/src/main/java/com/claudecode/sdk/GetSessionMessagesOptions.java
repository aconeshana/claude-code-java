package com.claudecode.sdk;

/** Options for reading a stored session's projected conversation chain. */
public record GetSessionMessagesOptions(String dir, Integer limit, Integer offset,
                                        Boolean includeSystemMessages, SessionStore sessionStore) {
    public static GetSessionMessagesOptions defaults() {
        return new GetSessionMessagesOptions(null, null, null, false, null);
    }
    boolean includeSystem() { return Boolean.TRUE.equals(includeSystemMessages); }
}
