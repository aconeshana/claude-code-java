package com.claudecode.commands.workflows;

import java.util.List;

/**
 * Command-facing, implementation-neutral description of a dynamic workflow.
 */
public record WorkflowCommandDefinition(
    String name,
    String title,
    String description,
    String whenToUse,
    List<Phase> phases,
    String script,
    Source source,
    String pluginName,
    boolean hidden
) {
    public WorkflowCommandDefinition {
        phases = phases == null ? List.of() : List.copyOf(phases);
        script = script == null ? "" : script;
        source = source == null ? Source.PROJECT : source;
    }

    public record Phase(String title, String detail) { }

    public enum Source { BUILT_IN, PLUGIN, USER, PROJECT }
}
