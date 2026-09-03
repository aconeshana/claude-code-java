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

    /** Computes another project's status block without disturbing the process snapshot. */
    public static String computeGitStatusSnapshot(String cwd) {
        return DefaultQuerySession.computeGitStatusSnapshot(cwd);
    }

    /** Installs a snapshot computed by {@link #computeGitStatusSnapshot} after a project switch. */
    public static void publishGitStatusSnapshot(String snapshot) {
        DefaultQuerySession.publishGitStatusSnapshot(snapshot);
    }
}
