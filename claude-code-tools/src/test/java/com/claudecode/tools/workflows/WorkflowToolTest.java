package com.claudecode.tools.workflows;

import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.StringUtils;

import static org.junit.jupiter.api.Assertions.*;

import com.claudecode.core.engine.*;
import com.claudecode.core.message.TextBlock;
import com.claudecode.tools.ValidationResult;
import com.claudecode.tools.ToolRegistry;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.PermissionRule;
import com.claudecode.permissions.RuleSource;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.session.SessionManager;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskStore;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Spliterators;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkflowToolTest {

    @TempDir Path temp;

    @Test
    void exposesReleased197SchemaAndAlias() {
        WorkflowTool tool = tool(new TaskRegistry(TaskStore.inMemory()), new WorkflowRunStore());
        JsonNode schema = tool.inputSchema();

        assertEquals(List.of("RunWorkflow"), tool.aliases());
        assertEquals("https://json-schema.org/draft/2020-12/schema", schema.path("$schema").asText());
        assertFalse(schema.has("required"));
        assertFalse(schema.path("additionalProperties").asBoolean(true));
        assertEquals(524_288, schema.path("properties").path("script").path("maxLength").asInt());
        assertEquals("^wf_[a-z0-9-]{6,}$",
            schema.path("properties").path("resumeFromRunId").path("pattern").asText());
        assertEquals(List.of("script", "name", "description", "title", "args",
                "scriptPath", "resumeFromRunId"),
            StreamSupport.stream(Spliterators.spliteratorUnknownSize(
                    schema.path("properties").fieldNames(), 0), false).toList());
    }

    @Test
    void namedPermissionPreviewCarriesResolvedScriptAndOfficialPrompt() {
        WorkflowCatalog catalog = new WorkflowCatalog(temp.resolve("user"),
            List.of(definition("audit", "return 'ok';")), List::of);
        WorkflowTool tool = new WorkflowTool(
            new WorkflowRuntime(_ -> WorkflowAgentResult.of("unused"), catalog, 2),
            catalog, new TaskRegistry(TaskStore.inMemory()), new WorkflowRunStore(),
            temp.resolve("claude-home"), true);
        ObjectNode input = JsonUtils.getMapper().createObjectNode().put("name", "audit");

        PermissionDecision.Ask ask = (PermissionDecision.Ask) tool.checkPermissions(
            input, ToolPermissionContext.of(temp));

        assertEquals("Review dynamic workflow before running", ask.message());
        assertTrue(Strings.CS.contains(ask.updatedInput().path("script").asText(), "return 'ok'"));
        assertEquals(List.of(new PermissionUpdate.AddRules(
            List.of(new PermissionUpdate.RuleValue("Workflow", "audit")),
            PermissionUpdate.Behavior.ALLOW,
            PermissionUpdate.Destination.LOCAL_SETTINGS)), ask.suggestions());
    }

    @Test
    void namedPermissionRulesMatchReleasedDenyAskAllowOrdering() {
        WorkflowCatalog catalog = new WorkflowCatalog(temp.resolve("user"),
            List.of(definition("audit", "return 'ok';")), List::of);
        WorkflowTool tool = new WorkflowTool(
            new WorkflowRuntime(_ -> WorkflowAgentResult.of("unused"), catalog, 2),
            catalog, new TaskRegistry(TaskStore.inMemory()), new WorkflowRunStore(),
            temp.resolve("claude-home"), true);
        ObjectNode input = JsonUtils.getMapper().createObjectNode().put("name", "audit");

        PermissionDecision denied = tool.checkPermissions(input, permissionContext(
            PermissionBehavior.DENY, "audit"));
        PermissionDecision asked = tool.checkPermissions(input, permissionContext(
            PermissionBehavior.ASK, "audit"));
        PermissionDecision allowed = tool.checkPermissions(input, permissionContext(
            PermissionBehavior.ALLOW, "audit"));

        assertEquals("Workflow audit blocked by permission rules",
            ((PermissionDecision.Deny) denied).message());
        PermissionDecision.Ask ask = (PermissionDecision.Ask) asked;
        assertTrue(ask.suggestions().isEmpty(), "an explicit ask rule must not offer always-allow");
        assertNull(ask.suggestionRuleContent());
        assertInstanceOf(PermissionDecision.Allow.class, allowed);
    }

    @Test
    void releasedNamedDenyRunsBeforeCatalogResolution() {
        WorkflowTool tool = tool(new TaskRegistry(TaskStore.inMemory()), new WorkflowRunStore());
        ObjectNode input = JsonUtils.getMapper().createObjectNode().put("name", "missing");

        PermissionDecision decision = tool.checkPermissions(input, permissionContext(
            PermissionBehavior.DENY, "missing"));

        assertEquals("Workflow missing blocked by permission rules",
            ((PermissionDecision.Deny) decision).message());
    }

    @Test
    void releasedValidationParsesMetadataButDefersJavaScriptCompilationUntilCall() {
        WorkflowTool tool = tool(new TaskRegistry(TaskStore.inMemory()), new WorkflowRunStore());
        ObjectNode input = JsonUtils.getMapper().createObjectNode().put("script", """
            export const meta = {name: 'compile-later', description: 'broken body'};
            return await ;
            """);

        ValidationResult result = tool.validateInput(input, context("session-compile", "toolu_compile"));

        assertInstanceOf(ValidationResult.Valid.class, result, "2.1.197 validateInput uses lk(); QTt compilation happens in call() after IDs are minted");
    }

    @Test
    void releasedCompileFailureReturnsStructuredErrorWithoutRegisteringOrLaunchingTask() {
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        WorkflowRunStore runs = new WorkflowRunStore();
        WorkflowTool tool = tool(registry, runs);
        ObjectNode input = JsonUtils.getMapper().createObjectNode().put("script", """
            export const meta = {name: 'compile-later', description: 'broken body'};
            return await ;
            """);

        StructuredToolOutput raw = (StructuredToolOutput) tool.call(
            input, context("session-compile", "toolu_compile"));
        JsonNode data = (JsonNode) raw.toolUseResult();

        assertEquals("async_launched", data.path("status").asText());
        assertEquals("local_workflow", data.path("taskType").asText());
        assertEquals("compile-later", data.path("workflowName").asText());
        assertEquals("broken body", data.path("summary").asText());
        assertTrue(data.path("taskId").asText().matches("w[a-z0-9]{8}"));
        assertTrue(data.path("runId").asText().matches("wf_[a-z0-9-]{6,}"));
        assertFalse(StringUtils.isBlank(data.path("error").asText()));
        assertEquals("Workflow script has a syntax error and was not launched:\n"
            + data.path("error").asText(), raw.text());
        assertTrue(registry.store().list().isEmpty(),
            "IF(local_workflow) only mints an id; xDo registers the task after successful compile");
        assertTrue(runs.list().isEmpty());

        ToolResult mapped = tool.mapResult(raw, input,
            context("session-compile", "toolu_compile"));
        assertTrue(mapped.isError());
        assertEquals(data, mapped.toolUseResult());
    }

    @Test
    void registryPreservesReleasedCompileFailureAsAnErrorToolResult() {
        TaskRegistry tasks = new TaskRegistry(TaskStore.inMemory());
        WorkflowRunStore runs = new WorkflowRunStore();
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool(tasks, runs));
        registry.setPermissionGate(new PermissionGate());
        ObjectNode input = JsonUtils.getMapper().createObjectNode().put("script", """
            export const meta = {name: 'compile-later', description: 'broken body'};
            return await ;
            """);
        ToolExecutionContext context = ToolExecutionContext
            .builder(new AbortController(), "session-registry-compile")
            .workingDirectory(temp.toString())
            .permissionAskCallback(_ -> PermissionAskCallback.Result.allow())
            .build()
            .withToolUseId("toolu_registry_compile");

        ToolResult result = registry.execute("Workflow", input, context);

        assertTrue(result.isError());
        assertTrue(Strings.CS.startsWith(
            ((TextBlock) result.content().getFirst()).text(),
            "Workflow script has a syntax error and was not launched:\n"));
        JsonNode data = (JsonNode) result.toolUseResult();
        assertEquals("async_launched", data.path("status").asText());
        assertEquals("local_workflow", data.path("taskType").asText());
        assertTrue(tasks.store().list().isEmpty());
        assertTrue(runs.list().isEmpty());
    }

    @Test
    void launchesInBackgroundAndPersistsOfficialArtifacts() throws Exception {
        Path script = temp.resolve("probe.js");
        Files.writeString(script, """
            export const meta = {
              name: "wire-probe",
              title: "Wire probe",
              description: "Probe workflow output",
              phases: [{title: "Inspect", detail: "Inspect the fixture"}],
            };
            log("hello");
            return {ok: true};
            """);
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        WorkflowRunStore runs = new WorkflowRunStore();
        WorkflowTool tool = tool(registry, runs);
        ObjectNode input = JsonUtils.getMapper().createObjectNode()
            .put("scriptPath", script.toString())
            .put("args", "audit src");

        Object raw = tool.call(input, context("session-197", "toolu_workflow_197"));

        StructuredToolOutput launched = (StructuredToolOutput) raw;
        JsonNode data = (JsonNode) launched.toolUseResult();
        assertEquals("async_launched", data.path("status").asText());
        assertEquals("local_workflow", data.path("taskType").asText());
        assertEquals("wire-probe", data.path("workflowName").asText());
        assertEquals("Probe workflow output", data.path("summary").asText());
        assertEquals(script.toAbsolutePath().normalize().toString(), data.path("scriptPath").asText());
        assertTrue(data.path("runId").asText().matches("wf_[a-z0-9-]{6,}"));
        assertTrue(Strings.CS.startsWith(launched.text(), "Workflow launched in background. Task ID: "));
        assertTrue(Strings.CS.contains(launched.text(), "\nSummary: Probe workflow output\n"));
        assertTrue(Strings.CS.contains(launched.text(), "\nYou will be notified when it completes. Use /workflows to watch live progress."));

        String taskId = data.path("taskId").asText();
        var terminal = registry.store().awaitTerminal(taskId, Duration.ofSeconds(5)).orElseThrow();
        assertEquals(TaskStatus.COMPLETED, terminal.status());
        assertEquals("toolu_workflow_197", terminal.toolUseId().orElseThrow());
        assertEquals(0, terminal.usage().orElseThrow().totalTokens());

        WorkflowRun run = runs.get(data.path("runId").asText()).orElseThrow();
        Path outputFile = run.outputFile();
        JsonNode output = JsonUtils.parseTree(Files.readString(outputFile));
        assertEquals("Probe workflow output", output.path("summary").asText());
        assertEquals(0, output.path("agentCount").asInt());
        assertEquals(List.of("hello"),
            JsonUtils.getMapper().convertValue(output.path("logs"), List.class));
        assertTrue(output.path("result").path("ok").asBoolean());

        assertEquals(TaskStatus.COMPLETED, run.status());
        assertEquals(outputFile, run.outputFile());
        assertTrue(Files.isRegularFile(run.runFile()));
        JsonNode persisted = JsonUtils.parseTree(Files.readString(run.runFile()));
        assertEquals("wire-probe", persisted.path("workflowName").asText());
        assertEquals("completed", persisted.path("status").asText());
        assertEquals(0, persisted.path("totalTokens").asInt());
        assertEquals("audit src", persisted.path("args").asText());
        assertEquals("Wire probe", persisted.path("title").asText());
        assertEquals("Inspect", persisted.path("phases").path(0).path("title").asText());
        assertFalse(persisted.has("endTime"));
        assertFalse(persisted.has("agentCache"));
    }

    @Test
    void inlineScriptsUseReleasedSessionScriptPathAndResumeKeepsRunId() throws Exception {
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        WorkflowRunStore runs = new WorkflowRunStore();
        WorkflowTool tool = tool(registry, runs);
        ObjectNode firstInput = JsonUtils.getMapper().createObjectNode()
            .put("script", "export const meta = {name: 'My Flow!', description: 'inline'}; return 1;");

        StructuredToolOutput first = (StructuredToolOutput) tool.call(
            firstInput, context("session-inline", "toolu_inline"));
        JsonNode firstData = (JsonNode) first.toolUseResult();
        String runId = firstData.path("runId").asText();
        Path scriptPath = Path.of(firstData.path("scriptPath").asText());
        assertEquals(new SessionManager(temp.resolve("claude-home"), temp.toString())
                .getProjectDir()
                .resolve("session-inline").resolve("workflows").resolve("scripts")
                .resolve("my-flow-" + runId + ".js"), scriptPath);
        registry.store().awaitTerminal(firstData.path("taskId").asText(),
            Duration.ofSeconds(5)).orElseThrow();

        ObjectNode resumeInput = JsonUtils.getMapper().createObjectNode()
            .put("scriptPath", scriptPath.toString())
            .put("resumeFromRunId", runId);
        // A new run index simulates a CLI restart: the journal is authoritative.
        WorkflowTool resumedTool = tool(registry, new WorkflowRunStore());
        StructuredToolOutput resumed = (StructuredToolOutput) resumedTool.call(
            resumeInput, context("session-inline", "toolu_resume"));
        JsonNode resumedData = (JsonNode) resumed.toolUseResult();
        assertEquals(runId, resumedData.path("runId").asText());
        registry.store().awaitTerminal(resumedData.path("taskId").asText(),
            Duration.ofSeconds(5)).orElseThrow();
    }

    private WorkflowTool tool(TaskRegistry registry, WorkflowRunStore runs) {
        WorkflowCatalog catalog = new WorkflowCatalog(
            temp.resolve("user-workflows"), List.of(), List::of);
        WorkflowRuntime runtime = new WorkflowRuntime(
            _ -> WorkflowAgentResult.of("unused"), catalog, 2);
        return new WorkflowTool(runtime, catalog, registry, runs, temp.resolve("claude-home"), true);
    }

    private ToolExecutionContext context(String sessionId, String toolUseId) {
        return ToolExecutionContext.builder(new AbortController(), sessionId).workingDirectory(temp.toString()).build().withToolUseId(toolUseId);
    }

    private ToolPermissionContext permissionContext(PermissionBehavior behavior, String name) {
        return ToolPermissionContext.of(temp).addRules(List.of(
            PermissionRule.withPattern("Workflow", behavior, RuleSource.LOCAL_SETTINGS, name)));
    }

    private static WorkflowDefinition definition(String name, String body) {
        String script = "export const meta = {name: '" + name
            + "', description: 'test'}; " + body;
        var parsed = WorkflowScriptParser.parse(script);
        return new WorkflowDefinition(parsed.metadata(),
            script, parsed.body(), WorkflowSource.BUILT_IN,
            null, null, false, false);
    }
}
