package com.claudecode.tools;

import com.claudecode.tools.mcp.MCPTool;
import com.claudecode.tools.mcp.McpAuthTool;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.apache.commons.lang3.Strings;


class ToolIdentityParityTest {

    private static final Map<String, ToolIdentity> STATIC_IDENTITIES = Map.ofEntries(
        entry("com.claudecode.tools.agent.AgentTool", "Agent", "Task"),
        entry("com.claudecode.tools.questions.AskUserQuestionTool", "AskUserQuestion"),
        entry("com.claudecode.tools.bash.BashTool", "Bash"),
        entry("com.claudecode.tools.cron.CronCreateTool", "CronCreate"),
        entry("com.claudecode.tools.cron.CronDeleteTool", "CronDelete"),
        entry("com.claudecode.tools.cron.CronListTool", "CronList"),
        entry("com.claudecode.tools.plan.EnterPlanModeTool", "EnterPlanMode"),
        entry("com.claudecode.tools.worktree.EnterWorktreeTool", "EnterWorktree"),
        entry("com.claudecode.tools.plan.ExitPlanModeTool", "ExitPlanMode"),
        entry("com.claudecode.tools.worktree.ExitWorktreeTool", "ExitWorktree"),
        entry("com.claudecode.tools.files.FileEditTool", "Edit"),
        entry("com.claudecode.tools.files.FileReadTool", "Read"),
        entry("com.claudecode.tools.files.FileWriteTool", "Write"),
        entry("com.claudecode.tools.files.GlobTool", "Glob"),
        entry("com.claudecode.tools.files.GrepTool", "Grep"),
        entry("com.claudecode.tools.monitor.MonitorTool", "Monitor"),
        entry("com.claudecode.tools.files.NotebookEditTool", "NotebookEdit"),
        entry("com.claudecode.tools.powershell.PowerShellTool", "PowerShell"),
        entry("com.claudecode.tools.cron.ScheduleWakeupTool", "ScheduleWakeup"),
        entry("com.claudecode.tools.messaging.SendMessageTool", "SendMessage"),
        entry("com.claudecode.tools.output.SyntheticOutputTool", "StructuredOutput"),
        entry("com.claudecode.tools.tasks.TodoWriteTool", "TodoWrite"),
        entry("com.claudecode.tools.ToolSearchTool", "ToolSearch"),
        entry("com.claudecode.tools.web.WebBrowserTool", "WebBrowser"),
        entry("com.claudecode.tools.web.WebFetchTool", "WebFetch"),
        entry("com.claudecode.tools.web.WebSearchTool", "WebSearch"),
        entry("com.claudecode.tools.workflows.WorkflowTool", "Workflow", "RunWorkflow"),
        entry("com.claudecode.tools.lsp.LSPTool", "LSP"),
        entry("com.claudecode.tools.mcp.ListMcpResourcesTool", "ListMcpResourcesTool"),
        entry("com.claudecode.tools.mcp.ReadMcpResourceDirTool",
            "ReadMcpResourceDirTool", "ReadMcpResourceDir"),
        entry("com.claudecode.tools.mcp.ReadMcpResourceTool", "ReadMcpResourceTool"),
        entry("com.claudecode.tools.mcp.WaitForMcpServersTool", "WaitForMcpServers"),
        entry("com.claudecode.tools.skills.SkillTool", "Skill"),
        entry("com.claudecode.tools.tasks.TaskCreateTool", "TaskCreate"),
        entry("com.claudecode.tools.tasks.TaskGetTool", "TaskGet"),
        entry("com.claudecode.tools.tasks.TaskListTool", "TaskList"),
        entry("com.claudecode.tools.tasks.TaskOutputTool", "TaskOutput", "AgentOutputTool", "BashOutputTool"),
        entry("com.claudecode.tools.tasks.TaskStopTool", "TaskStop", "KillShell"),
        entry("com.claudecode.tools.tasks.TaskUpdateTool", "TaskUpdate"),
        entry("com.claudecode.tools.tasks.TeamCreateTool", "TeamCreate"),
        entry("com.claudecode.tools.tasks.TeamDeleteTool", "TeamDelete")
    );

    @Test
    void everyProductionToolUsesExactlyTheAuditedIdentityStrategy() throws Exception {
        Set<Class<? extends Tool<?, ?>>> implementations = productionToolImplementations();
        Set<String> expected = Stream.concat(
                STATIC_IDENTITIES.keySet().stream(),
                Stream.of(MCPTool.class.getName(), McpAuthTool.class.getName()))
            .collect(Collectors.toSet());

        assertEquals(43, implementations.size());
        assertEquals(expected, implementations.stream().map(Class::getName).collect(Collectors.toSet()));

        for (Class<? extends Tool<?, ?>> type : implementations) {
            if (type == MCPTool.class || type == McpAuthTool.class) {
                assertFalse(AnnotatedTool.class.isAssignableFrom(type));
                continue;
            }

            assertTrue(AnnotatedTool.class.isAssignableFrom(type), type.getName());
            BuiltInTool annotation = type.getDeclaredAnnotation(BuiltInTool.class);
            assertNotNull(annotation, type.getName());
            assertEquals(STATIC_IDENTITIES.get(type.getName()),
                new ToolIdentity(annotation.name(), List.of(annotation.aliases())), type.getName());
        }
    }

    @Test
    void onlyTheTwoReleasedCompatibilityAliasesAreDeclared() {
        Map<String, List<String>> aliases = STATIC_IDENTITIES.entrySet().stream()
            .filter(entry -> !entry.getValue().aliases().isEmpty())
            .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().aliases()));

        assertEquals(Map.of(
            "com.claudecode.tools.agent.AgentTool", List.of("Task"),
            "com.claudecode.tools.tasks.TaskOutputTool", List.of("AgentOutputTool", "BashOutputTool"),
            "com.claudecode.tools.tasks.TaskStopTool", List.of("KillShell"),
            "com.claudecode.tools.workflows.WorkflowTool", List.of("RunWorkflow"),
            "com.claudecode.tools.mcp.ReadMcpResourceDirTool", List.of("ReadMcpResourceDir")
        ), aliases);
    }

    @SuppressWarnings("unchecked")
    private static Set<Class<? extends Tool<?, ?>>> productionToolImplementations() throws Exception {
        URI location = Tool.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        Path classes = Path.of(location);
        Path packageRoot = classes.resolve("com/claudecode/tools");
        ClassLoader loader = Tool.class.getClassLoader();

        try (Stream<Path> files = Files.walk(packageRoot)) {
            return files
                .filter(path ->Strings.CS.endsWith( path.toString(), ".class"))
                .filter(path -> !Strings.CS.contains(path.getFileName().toString(), "$"))
                .map(path -> className(classes, path))
                .map(name -> loadWithoutInitialization(name, loader))
                .filter(Tool.class::isAssignableFrom)
                .filter(type -> type != Tool.class && type != AnnotatedTool.class)
                .filter(type -> !Modifier.isAbstract(type.getModifiers()))
                .map(type -> (Class<? extends Tool<?, ?>>) type)
                .collect(Collectors.toSet());
        }
    }

    private static String className(Path classes, Path classFile) {
        String relative = classes.relativize(classFile).toString();
        return relative.substring(0, relative.length() - ".class".length())
            .replace('/', '.')
            .replace('\\', '.');
    }

    private static Class<?> loadWithoutInitialization(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Unable to load compiled tool class " + name, e);
        }
    }

    private static Map.Entry<String, ToolIdentity> entry(String type, String name, String... aliases) {
        return Map.entry(type, new ToolIdentity(name, List.of(aliases)));
    }
}
