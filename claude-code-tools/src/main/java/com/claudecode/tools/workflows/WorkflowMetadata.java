package com.claudecode.tools.workflows;

import java.util.List;

/** Pure-literal metadata from the first statement of a workflow script. */
public record WorkflowMetadata(
    String name,
    String title,
    String description,
    String whenToUse,
    List<WorkflowPhase> phases) {

    public WorkflowMetadata {
        phases = phases == null ? List.of() : List.copyOf(phases);
    }
}
