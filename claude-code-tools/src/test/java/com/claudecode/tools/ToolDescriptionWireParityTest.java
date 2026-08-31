package com.claudecode.tools;

import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.ToolExecutor;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.tools.plan.EnterPlanModeTool;
import com.claudecode.tools.plan.ExitPlanModeTool;
import com.claudecode.tools.skills.SkillToolProvider;
import com.claudecode.tools.tasks.TodoStore;
import com.claudecode.tools.tasks.TaskCreateTool;
import com.claudecode.tools.tasks.TaskGetTool;
import com.claudecode.tools.tasks.TaskListTool;
import com.claudecode.tools.tasks.TaskUpdateTool;
import com.claudecode.tools.worktree.EnterWorktreeTool;
import com.claudecode.tools.worktree.ExitWorktreeTool;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Anti-ambiguity guard for the tool-description dual source.
 */
class ToolDescriptionWireParityTest {


    private static final Set<String> BASELINE_TOOLS = Set.of(
        "Agent", "AskUserQuestion", "Bash", "Edit", "EnterPlanMode",
        "EnterWorktree", "ExitWorktree", "Monitor", "Read", "SendMessage", "Skill",
        "TaskCreate", "TaskGet", "TaskList", "TaskUpdate", "WebFetch",
        "WebSearch", "Workflow", "Write");

    /**
     * Tools fully exempt from the single-source assertions.
     */
    private static final Set<String> INTENTIONAL_DIVERGENCES =
        Set.of("Agent", "EnterPlanMode", "TaskCreate", "TaskGet", "TaskList", "TaskUpdate");

    /**
 * Tools whose {@code description} is rendered (Bash injects current-model attribution; WebSearch
 * injects the current month), so it equals the frozen.
     */
    private static final Set<String> DYNAMIC_RENDER = Set.of("Bash", "WebSearch");

    private static ToolRegistry buildRegistry() {
        StreamingClient client = new StreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                return List.<StreamingEvent>of().iterator();
            }
            @Override
            public String getModel() {
                return "test";
            }
        };
        ToolExecutor executor = new ToolExecutor() {
            @Override
            public ToolResult execute(String toolName, JsonNode input,
                                      ToolExecutionContext context) {
                return null;
            }
        };
        ToolRegistry registry = new ToolRegistry();
        ToolBootstrap.registerBuiltInTools(registry, client, executor);
        // Providers the bootstrap does not cover for the baseline tools.
        new SkillToolProvider().initialize(Path.of("."), registry);
        TodoStore todoStore = new TodoStore("wire-parity-test");
        registry.register(new TaskCreateTool(todoStore));
        registry.register(new TaskGetTool(todoStore));
        registry.register(new TaskListTool(todoStore));
        registry.register(new TaskUpdateTool(todoStore));
        registry.register(new EnterPlanModeTool(new PermissionGate()));
        registry.register(new ExitPlanModeTool(new PermissionGate()));
        registry.register(new EnterWorktreeTool());
        registry.register(new ExitWorktreeTool());
        return registry;
    }

    @Test
    void wireAndPermissionDescriptionsShareSingleSource() {
        ToolRegistry registry = buildRegistry();
        Collection<Tool<?, ?>> tools = registry.getAll();

        for (Tool<?, ?> tool : tools) {
            String name = tool.name();
            if (!BASELINE_TOOLS.contains(name)) continue;

// Wire (prompt(null)) and permission UI (description) must agree:
            // the registry builds the model tool catalogue from prompt(null), so
            // any divergence is exactly the ambiguity we removed. Agent is exempt
// (its short description intentionally differs from its full
// prompt).
            if (!INTENTIONAL_DIVERGENCES.contains(name)) {
                assertEquals(tool.description(), tool.prompt(null),
                    name + ": wire prompt() must equal permission description()");
            }


            if (!INTENTIONAL_DIVERGENCES.contains(name)
                    && !DYNAMIC_RENDER.contains(name)) {
                String baseline = ToolTexts.prompt(name);
                assertEquals(baseline, tool.description(),
                    name + ": description() must derive from the 197 baseline");
            }
        }

        // Every baseline tool reachable in a unit test must be registered
        // (catches accidental drops from the converged set). Workflow is excluded
        // here only because its registration needs a workflow runtime/catalog;
// it is still covered by the end-to-end wire contract test.
        Set<String> expectedRegistered = new HashSet<>(BASELINE_TOOLS);
        expectedRegistered.remove("Workflow");
        Map<String, Tool<?, ?>> byName = new HashMap<>();
        tools.forEach(t -> byName.put(t.name(), t));
        for (String name : expectedRegistered) {
            assertTrue(byName.containsKey(name),
                name + ": baseline tool must be registered");
        }
    }
}
