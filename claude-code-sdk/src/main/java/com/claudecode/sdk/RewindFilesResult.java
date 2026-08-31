package com.claudecode.sdk;

import java.util.List;

/** Result of rewinding tracked files to a user-message checkpoint. */
public record RewindFilesResult(boolean canRewind, String error, List<String> filesChanged,
                                Integer insertions, Integer deletions) {
    public RewindFilesResult {
        filesChanged = filesChanged == null ? List.of() : List.copyOf(filesChanged);
    }
}
