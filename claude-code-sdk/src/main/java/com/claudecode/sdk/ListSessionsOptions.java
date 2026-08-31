package com.claudecode.sdk;

/** Public Agent SDK options for local or custom-store session enumeration. */
public record ListSessionsOptions(String dir, Integer limit, Integer offset,
                                  Boolean includeWorktrees, Boolean includeProgrammatic,
                                  SessionStore sessionStore) {
    public static ListSessionsOptions defaults() {
        return new ListSessionsOptions(null, null, null, null, null, null);
    }
}
