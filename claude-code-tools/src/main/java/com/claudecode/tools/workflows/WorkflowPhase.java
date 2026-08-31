package com.claudecode.tools.workflows;

/** One user-visible phase declared by a dynamic workflow's literal metadata. */
public record WorkflowPhase(String title, String detail, String model) {}
