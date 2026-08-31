package com.claudecode.tools.workflows;

/** Script, sandbox, cap, structured-output, or subagent failure in a workflow run. */
public final class WorkflowRuntimeException extends RuntimeException {
    public WorkflowRuntimeException(String message) { super(message); }
    public WorkflowRuntimeException(String message, Throwable cause) { super(message, cause); }
}
