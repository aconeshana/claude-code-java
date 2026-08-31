package com.claudecode.session;

/** Persisted sidecar data required to resume a local sub-agent faithfully. */
public record AgentMetadata(
        String agentType, String worktreePath, String description, boolean stoppedByUser,
        Integer spawnDepth, Integer subagentMaxDepth) {

    /** Source-compatible shape for metadata written before depth snapshots existed. */
    public AgentMetadata(String agentType, String worktreePath, String description,
                         boolean stoppedByUser) {
        this(agentType, worktreePath, description, stoppedByUser, null, null);
    }

    /** Source-compatible shape for metadata written before the stop marker existed. */
    public AgentMetadata(String agentType, String worktreePath, String description) {
        this(agentType, worktreePath, description, false, null, null);
    }
}
