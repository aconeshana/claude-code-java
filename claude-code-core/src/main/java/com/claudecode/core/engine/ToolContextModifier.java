package com.claudecode.core.engine;

import org.apache.commons.lang3.StringUtils;
import java.util.List;

/**
 * Per-tool-run context override returned by a tool's {@link ToolResult} so the engine applies it to
 * subsequent turns.
 */
public record ToolContextModifier(
    List<String> allowedTools,
    String model,
    String effort,
    String attributionSkill,
    String attributionPlugin
) {
    /** Backward-compatible shape for non-Skill callers. */
    public ToolContextModifier(List<String> allowedTools, String model, String effort) {
        this(allowedTools, model, effort, null, null);
    }

    /** True when this modifier actually changes something (non-null/non-empty field). */
    public boolean isEmpty() {
        return (StringUtils.isBlank(model))
            && (StringUtils.isBlank(effort))
            && (StringUtils.isBlank(attributionSkill))
            && (StringUtils.isBlank(attributionPlugin))
            && (allowedTools == null || allowedTools.isEmpty());
    }
}
