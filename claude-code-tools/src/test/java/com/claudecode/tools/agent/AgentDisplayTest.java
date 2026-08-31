package com.claudecode.tools.agent;
import com.claudecode.core.agent.BuiltInAgentDefinitions;
import com.claudecode.core.agent.AgentSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgentDisplay}.
 */
class AgentDisplayTest {

    private static BuiltInAgentDefinitions.AgentDefinition builtIn(String type) {
        return BuiltInAgentDefinitions.AgentDefinition.builder(type, "built-in " + type)
            .tools(List.of("*")).build();
    }

    private static BuiltInAgentDefinitions.AgentDefinition custom(String type, AgentSource source) {
        return BuiltInAgentDefinitions.AgentDefinition.builder(type, "custom " + type)
            .tools(List.of("*")).source(source).build();
    }

    @Test
    void resolveOverrides_activeAgentIsNotFlaggedAsShadowed() {
        var builtin = builtIn("Explore");
        var resolved = AgentDisplay.resolveOverrides(List.of(builtin), List.of(builtin));
        assertEquals(1, resolved.size());
        assertNull(resolved.getFirst().overriddenBy());
    }

    @Test
    void resolveOverrides_flagsShadowedAgent() {
        var builtin = builtIn("Explore");
        var projectOverride = custom("Explore", AgentSource.PROJECT);
        // allAgents in lowest-precedence-first order; activeAgents has only the winner.
        var resolved = AgentDisplay.resolveOverrides(List.of(builtin, projectOverride), List.of(projectOverride));

        var builtinResolved = resolved.stream().filter(r -> r.agent() == builtin).findFirst().orElseThrow();
        assertEquals(AgentSource.PROJECT, builtinResolved.overriddenBy());

        var projectResolved = resolved.stream().filter(r -> r.agent() == projectOverride).findFirst().orElseThrow();
        assertNull(projectResolved.overriddenBy());
    }

    @Test
    void compareByName_caseInsensitive() {
        assertTrue(AgentDisplay.compareByName(builtIn("apple"), builtIn("Banana")) < 0);
        assertEquals(0, AgentDisplay.compareByName(builtIn("Explore"), builtIn("explore")));
    }

    @Test
    void resolveModelDisplay_nullOrInherit_isInherit() {
        assertEquals("inherit", AgentDisplay.resolveModelDisplay(builtIn("x")));
        var withInherit = BuiltInAgentDefinitions.AgentDefinition.builder("x", "x")
            .tools(List.of("*")).model("inherit").source(AgentSource.USER).build();
        assertEquals("inherit", AgentDisplay.resolveModelDisplay(withInherit));
    }

    @Test
    void resolveModelDisplay_explicitModel_isRaw() {
        var withOpus = BuiltInAgentDefinitions.AgentDefinition.builder("x", "x")
            .tools(List.of("*")).model("opus").source(AgentSource.USER).build();
        assertEquals("opus", AgentDisplay.resolveModelDisplay(withOpus));
    }
}
