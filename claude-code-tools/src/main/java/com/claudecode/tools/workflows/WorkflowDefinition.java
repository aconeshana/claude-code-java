package com.claudecode.tools.workflows;

import java.nio.file.Path;

/** A validated, loadable workflow script plus icompatibility baseline provenance. */
public record WorkflowDefinition(
    WorkflowMetadata metadata,
    String script,
    String body,
    WorkflowSource source,
    Path path,
    String pluginName,
    boolean hidden,
    /** True only when this invocation supplied an inline {@code script}. */
    boolean inlineScript) {

    public WorkflowDefinition {
        if (metadata == null) throw new IllegalArgumentException("metadata must not be null");
        script = script == null ? "" : script;
        body = body == null ? "" : body;
        if (source == null) throw new IllegalArgumentException("source must not be null");
    }
}
