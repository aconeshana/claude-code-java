package com.claudecode.cli;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Composite session-scoped operations exposed through the SDK stdio control protocol.
 *
 * <p>Decomposed by capability domain into {@link SdkMcpControl}, {@link SdkSessionControl},
 * and {@link SdkEnvironmentControl} so callers can depend only on the slice they use.
 */
interface SdkControlRuntime extends SdkMcpControl, SdkSessionControl, SdkEnvironmentControl {

    record McpServerStatus(
        String name,
        String status,
        JsonNode serverInfo,
        String error,
        JsonNode config,
        String scope,
        JsonNode tools,
        JsonNode capabilities
    ) {
        McpServerStatus(String name, String status) {
            this(name, status, null, null, null, null, null, null);
        }
    }

    record RewindFilesResult(boolean canRewind, String error,
                             List<String> filesChanged,
                             Integer insertions, Integer deletions) {
        public RewindFilesResult {
            filesChanged = filesChanged != null ? List.copyOf(filesChanged) : null;
        }
    }

    static SdkControlRuntime unavailable() {
        return new SdkControlRuntime() {
            private UnsupportedOperationException unsupported() {
                return new UnsupportedOperationException("SDK control runtime is unavailable");
            }

            @Override public List<McpServerStatus> mcpStatus() { throw unsupported(); }
            @Override public JsonNode contextUsage() { throw unsupported(); }
            @Override public RewindFilesResult rewindFiles(String id, boolean dryRun) { throw unsupported(); }
            @Override public boolean cancelAsyncMessage(String uuid) { throw unsupported(); }
            @Override public void seedReadState(String path, long mtime) { throw unsupported(); }
            @Override public void stopTask(String taskId) { throw unsupported(); }
            @Override public JsonNode settings() { throw unsupported(); }
        };
    }
}
