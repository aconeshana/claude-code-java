package com.claudecode.tools.agent;

import org.apache.commons.lang3.Strings;

import java.util.List;

/**
 * Agent model picker options — the fable/sonnet/opus/haiku/inherit family aliases, distinct from
 * the full model list {@code /model} exposes (no effort row, no env-var overrides).
 */
public final class AgentModelOptions {

    private AgentModelOptions() {}

    public record Option(String value, String label, String description) {}

    private static final List<Option> OPTIONS = List.of(
        new Option("fable", "Fable", "Most capable for your hardest and longest-running tasks"),
        new Option("sonnet", "Sonnet", "Efficient for routine tasks"),
        new Option("opus", "Opus", "Best for everyday, complex tasks"),
        new Option("haiku", "Haiku", "Fastest for quick answers"),
        new Option("inherit", "Inherit", "Use the same model as the main conversation"));

    public static List<Option> options() {
        return OPTIONS;
    }

    /** {@code null} → "Inherit from parent (default)"; {@code "inherit"} → "Inherit from parent"; else capitalized. */
    public static String displayName(String model) {
        if (model == null) return "Inherit from parent (default)";
        if (Strings.CS.equals(model, "inherit")) return "Inherit from parent";
        return Character.toUpperCase(model.charAt(0)) + model.substring(1);
    }
}
