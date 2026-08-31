package com.claudecode.commands.workflows;

import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowPromptCommandTest {

    @Test
    void buildsReleased197PromptWithPhasesAndJsonEscapedArgs() {
        String script = """
            export const meta = {
              name: "deep-research",
              description: "Research a topic deeply",
              whenToUse: "Use for broad research",
              phases: [{ title: "Discover", detail: "Search sources" }, { title: "Synthesize" }]
            };
            return await agent("research");
            """;
        WorkflowCommandDefinition def = new WorkflowCommandDefinition(
            "deep-research", "deep-research", "Research a topic deeply",
            "Use for broad research", List.of(
                new WorkflowCommandDefinition.Phase("Discover", "Search sources"),
                new WorkflowCommandDefinition.Phase("Synthesize", null)),
            script, WorkflowCommandDefinition.Source.USER, null, false);

        CommandResult result = new WorkflowPromptCommand(def)
            .execute(CommandContext.minimal(), "  cache \"behavior\"  ");

        String expected = """
            Run the "deep-research" workflow.

            Research a topic deeply

            Use for broad research

            Phases:
            - Discover: Search sources
            - Synthesize

            Invoke: Workflow({ name: "deep-research", args: "cache \\"behavior\\"" })""";
        assertEquals(expected, result.promptInvocation().textContent());
        assertEquals("running dynamic workflow", result.promptInvocation().progressMessage());
        assertEquals("skills", result.promptInvocation().loadedFrom());
        assertEquals("user", result.promptInvocation().source());
        assertEquals(script.length(), result.promptInvocation().contentLength());
        assertTrue(result.shouldQuery());
    }

    @Test
    void omitsArgsAndPhasesWhenAbsentAndMarksBundledSource() {
        String script = "export const meta = { name: \"simple\", description: \"Do it\" }; return 1;";
        WorkflowCommandDefinition def = new WorkflowCommandDefinition(
            "simple", "simple", "Do it", null, List.of(), script,
            WorkflowCommandDefinition.Source.BUILT_IN, null, false);

        CommandResult result = new WorkflowPromptCommand(def)
            .execute(CommandContext.minimal(), "   ");

        assertEquals("Run the \"simple\" workflow.\n\nDo it\n\nInvoke: Workflow({ name: \"simple\" })",
            result.promptInvocation().textContent());
        assertEquals("bundled", result.promptInvocation().source());
        assertEquals("bundled", result.promptInvocation().loadedFrom());
    }
}
