package com.claudecode.tools.workflows;

import com.claudecode.core.engine.ToolExecutionContext;

/** One workflow-originated subagent call after option validation. */
public record WorkflowAgentRequest(
    String prompt,
    WorkflowAgentOptions options,
    ToolExecutionContext parentContext,
    String agentId,
    ProgressCallback progressCallback,
    String transcriptSubdir) {

    public WorkflowAgentRequest(String prompt, WorkflowAgentOptions options,
                                ToolExecutionContext parentContext, String agentId,
                                ProgressCallback progressCallback) {
        this(prompt, options, parentContext, agentId, progressCallback, null);
    }

    /** Compatibility shape for simple/test executors. */
    public WorkflowAgentRequest(String prompt, WorkflowAgentOptions options,
                                ToolExecutionContext parentContext) {
        this(prompt, options, parentContext, null, null, null);
    }

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(String status, double percent);
    }
}
