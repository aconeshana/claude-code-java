package com.claudecode.runtime.sessionhost;

import com.claudecode.core.annotation.Explanation;
import java.time.Instant;

/**
 * Adapter-neutral metadata for one host-owned conversation.
 */
@Explanation("Metadata exposed by the local multi-end Session Host")
public record SessionHostInfo(
        String id,
        String workDir,
        String summary,
        int messageCount,
        Instant modifiedAt,
        String gitBranch) {

    public SessionHostInfo {
        id = id == null ? "" : id;
        workDir = workDir == null ? "" : workDir;
        summary = summary == null ? "" : summary;
        gitBranch = gitBranch == null ? "" : gitBranch;
    }
}
