package com.claudecode.tools.agent;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentModelOptionsTest {

    @Test
    void options_matchReleased197AgentModelPicker() {
        var options = AgentModelOptions.options();

        assertEquals(List.of("fable", "sonnet", "opus", "haiku", "inherit"),
            options.stream().map(AgentModelOptions.Option::value).toList());
        assertEquals(List.of(
                "Most capable for your hardest and longest-running tasks",
                "Efficient for routine tasks",
                "Best for everyday, complex tasks",
                "Fastest for quick answers",
                "Use the same model as the main conversation"),
            options.stream().map(AgentModelOptions.Option::description).toList());
    }

    @Test
    void displayName_nullIsInheritDefault() {
        assertEquals("Inherit from parent (default)", AgentModelOptions.displayName(null));
    }

    @Test
    void displayName_inheritLiteral() {
        assertEquals("Inherit from parent", AgentModelOptions.displayName("inherit"));
    }

    @Test
    void displayName_capitalizesModelName() {
        assertEquals("Opus", AgentModelOptions.displayName("opus"));
        assertEquals("Sonnet", AgentModelOptions.displayName("sonnet"));
        assertEquals("Fable", AgentModelOptions.displayName("fable"));
    }
}
