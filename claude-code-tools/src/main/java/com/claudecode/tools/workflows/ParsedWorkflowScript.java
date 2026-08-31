package com.claudecode.tools.workflows;

/** Result of separating the required literal metadata export from executable body code. */
public record ParsedWorkflowScript(WorkflowMetadata metadata, String body) {}
