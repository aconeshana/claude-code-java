package com.claudecode.tools.workflows;

/** Injectable boundary between deterministic workflow control flow and an LLM subagent. */
@FunctionalInterface
public interface WorkflowAgentExecutor {
    WorkflowAgentResult execute(WorkflowAgentRequest request) throws Exception;
}
