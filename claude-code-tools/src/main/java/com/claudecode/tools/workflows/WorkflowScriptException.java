package com.claudecode.tools.workflows;

/** Validation or literal-parser failure for a dynamic workflow script. */
public final class WorkflowScriptException extends IllegalArgumentException {
    public WorkflowScriptException(String message) {
        super(message);
    }
}
