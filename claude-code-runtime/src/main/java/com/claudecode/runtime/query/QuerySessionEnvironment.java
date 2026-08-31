package com.claudecode.runtime.query;

/** Process-scoped query environment snapshots shared by main and child sessions. */
public final class QuerySessionEnvironment {
    private QuerySessionEnvironment() {}

    public static void primeGitStatusSnapshot(String cwd) {
        DefaultQuerySession.primeGitStatusSnapshot(cwd);
    }

    public static String initialGitStatusSnapshot(String cwd) {
        return DefaultQuerySession.initialGitStatusSnapshot(cwd);
    }
}
