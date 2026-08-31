package com.claudecode.ui.lanterna.transcript;

import com.claudecode.tools.plan.PlanFiles;
import com.claudecode.tools.tasks.TaskOutputPaths;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolVisualContractRegistryTest {

    @AfterEach
    void resetPlans() {
        PlanFiles.resetPlansDirectory();
    }

    @Test
    void searchUseMessageIncludesNamedPatternAndDisplayPath() {
        var view = ToolVisualContractRegistry.useView("Grep",
            "{\"pattern\":\"needle\",\"path\":\"src/main\"}", false);

        assertEquals("Search", view.displayName());
        assertEquals("(pattern: \"needle\", path: \"src/main\")", view.argsPart());
    }

    @Test
    void editNameAndSummaryAreInputAware() {
        var create = ToolVisualContractRegistry.useView("Edit",
            "{\"file_path\":\"new.txt\",\"old_string\":\"\",\"new_string\":\"x\"}", false);
        assertEquals("Create", create.displayName());
        assertEquals("(new.txt)", create.argsPart());

        Path plans = Path.of("target/test-plans").toAbsolutePath();
        PlanFiles.configurePlansDirectory(plans);
        var plan = ToolVisualContractRegistry.useView("Edit",
            "{\"file_path\":\"" + plans.resolve("quiet.md").toString().replace("\\", "\\\\")
                + "\",\"old_string\":\"a\"}", false);
        assertEquals("Updated plan", plan.displayName());
        assertEquals("", plan.argsPart());
    }

    @Test
    void taskOutputSkillAndWorkflowUseReleasedSummaries() {
        var task = ToolVisualContractRegistry.useView("TaskOutput",
            "{\"task_id\":\"42\",\"block\":false}", false);
        assertEquals("Task Output", task.displayName());
        assertEquals("(non-blocking)", task.argsPart());
        assertEquals("", task.tagPart());

        var skill = ToolVisualContractRegistry.useView("Skill",
            "{\"skill\":\"security-review\"}", false);
        assertEquals("(security-review)", skill.argsPart());

        var workflow = ToolVisualContractRegistry.useView("Workflow",
            "{\"name\":\"audit\"}", false);
        assertEquals("(dynamic workflow: audit)", workflow.argsPart());
    }

    @Test
    void agentOutputReadUsesReleasedNameAndSuppressesPathSummary() {
        String path = TaskOutputPaths.outputDirectory().resolve("agent-7.output")
            .toString().replace("\\", "\\\\");

        var view = ToolVisualContractRegistry.useView(
            "Read", "{\"file_path\":\"" + path + "\"}", false);

        assertEquals("Read agent output", view.displayName());
        assertEquals("", view.argsPart());
    }

    @Test
    void agentUseSummaryAlwaysUsesDescriptionAcrossSubtypes() {
        var general = ToolVisualContractRegistry.useView("Agent",
            "{\"isolation\":\"worktree\",\"description\":\"inspect cache\","
                + "\"prompt\":\"Inspect the cache\",\"subagent_type\":\"general-purpose\"}",
            false);
        assertEquals("Agent", general.displayName());
        assertEquals("(inspect cache)", general.argsPart());

        var worker = ToolVisualContractRegistry.useView("Agent",
            "{\"subagent_type\":\"worker\",\"description\":\"apply migration\","
                + "\"prompt\":\"Apply the migration\"}", false);
        assertEquals("Agent", worker.displayName());
        assertEquals("(apply migration)", worker.argsPart());

        var explore = ToolVisualContractRegistry.useView("Agent",
            "{\"subagent_type\":\"Explore\",\"description\":\"trace configuration\","
                + "\"prompt\":\"Trace configuration loading\"}", false);
        assertEquals("Explore", explore.displayName());
        assertEquals("(trace configuration)", explore.argsPart());
    }

    @Test
    void scheduleWakeupIsFullyHidden() {
        assertTrue(ToolVisualContractRegistry.hidesUse("ScheduleWakeup"));
        assertEquals(ToolVisualContractRegistry.ResultMode.HIDDEN,
            ToolVisualContractRegistry.resultMode("ScheduleWakeup"));
        assertFalse(ToolVisualContractRegistry.hidesUse("Workflow"));
    }

    @Test
    void firstBatchUseMessagesMatchReleasedContracts() {
        assertTrue(ToolVisualContractRegistry.useView("AskUserQuestion", "{}", false).hidden());
        assertEquals("", ToolVisualContractRegistry
            .useView("TaskStop", "{\"task_id\":\"7\"}", false).argsPart());
        assertEquals("(create team: platform)", ToolVisualContractRegistry
            .useView("TeamCreate", "{\"team_name\":\"platform\"}", false).argsPart());
        assertEquals("(cleanup team: current)", ToolVisualContractRegistry
            .useView("TeamDelete", "{}", false).argsPart());
        assertEquals("Creating worktree", ToolVisualContractRegistry
            .useView("EnterWorktree", "{}", false).displayName());
        assertEquals("(Exiting worktree…)", ToolVisualContractRegistry
            .useView("ExitWorktree", "{\"action\":\"keep\"}", false).argsPart());
    }

    @Test
    void sendMessageOnlyRevealsPlanApprovalResponses() {
        assertTrue(ToolVisualContractRegistry.useView("SendMessage",
            "{\"to\":\"worker\",\"message\":\"hello\"}", false).hidden());
        var approval = ToolVisualContractRegistry.useView("SendMessage",
            "{\"to\":\"lead\",\"message\":{\"type\":\"plan_approval_response\",\"approve\":true}}",
            false);
        assertFalse(approval.hidden());
        assertEquals("(approve plan from: lead)", approval.argsPart());
    }

    @Test
    void secondBatchUseMessagesMatchReleasedContracts() {
        assertEquals("(operation: \"goToDefinition\", file: \"Demo.java\", position: 3:7)",
            ToolVisualContractRegistry.useView("LSP",
                "{\"operation\":\"goToDefinition\",\"filePath\":\"/tmp/Demo.java\",\"line\":3,\"character\":7}",
                false).argsPart());
        assertEquals("listMcpResources", ToolVisualContractRegistry.useView(
            "ListMcpResourcesTool", "{}", false).displayName());
        assertEquals("(List all MCP resources)", ToolVisualContractRegistry.useView(
            "ListMcpResourcesTool", "{}", false).argsPart());
        assertEquals("(Read resource \"docs://guide\" from server \"docs\")",
            ToolVisualContractRegistry.useView("ReadMcpResourceTool",
                "{\"server\":\"docs\",\"uri\":\"docs://guide\"}", false).argsPart());
        assertEquals("(https://example.com)", ToolVisualContractRegistry.useView(
            "WebFetch", "{\"url\":\"https://example.com\",\"prompt\":\"summarize\"}", false)
            .argsPart());
        assertEquals("(url: \"https://example.com\", prompt: \"summarize\")",
            ToolVisualContractRegistry.useView("WebFetch",
                "{\"url\":\"https://example.com\",\"prompt\":\"summarize\"}", true)
                .argsPart());
        assertEquals("(\"java 21\", only allowing domains: openjdk.org)",
            ToolVisualContractRegistry.useView("WebSearch",
                "{\"query\":\"java 21\",\"allowed_domains\":[\"openjdk.org\"]}", true)
                .argsPart());
        assertEquals("srv - lookup (MCP)", ToolVisualContractRegistry.useView(
            "mcp__srv__lookup", "{\"query\":\"needle\"}", false).displayName());
        assertEquals("(query: \"needle\")", ToolVisualContractRegistry.useView(
            "mcp__srv__lookup", "{\"query\":\"needle\"}", false).argsPart());
    }

    @Test
    void thirdBatchUseAndResultContractsMatchReleasedUi() {
        assertTrue(ToolVisualContractRegistry.useView("TodoWrite", "{\"todos\":[]}", false).hidden());
        assertEquals(ToolVisualContractRegistry.ResultMode.HIDDEN,
            ToolVisualContractRegistry.resultMode("TodoWrite"));
        assertTrue(ToolVisualContractRegistry.useView("ToolSearch",
            "{\"query\":\"browser\"}", false).hidden());
        assertEquals(ToolVisualContractRegistry.ResultMode.HIDDEN,
            ToolVisualContractRegistry.resultMode("ToolSearch"));

        assertEquals("(*/5 * * * *: check build)", ToolVisualContractRegistry.useView(
            "CronCreate", "{\"cron\":\"*/5 * * * *\",\"prompt\":\"check build\"}", false)
            .argsPart());
        assertEquals("(0 9 * * *: first line…)", ToolVisualContractRegistry.useView(
            "CronCreate", "{\"cron\":\"0 9 * * *\",\"prompt\":\"first line\\nsecond line\"}", false)
            .argsPart());
        assertEquals("(job-1)", ToolVisualContractRegistry.useView(
            "CronDelete", "{\"id\":\"job-1\"}", false).argsPart());
        assertEquals("", ToolVisualContractRegistry.useView("CronList", "{}", false).argsPart());
        assertEquals(ToolVisualContractRegistry.ResultMode.CRON_CREATE,
            ToolVisualContractRegistry.resultMode("CronCreate"));
        assertEquals(ToolVisualContractRegistry.ResultMode.CRON_DELETE,
            ToolVisualContractRegistry.resultMode("CronDelete"));
        assertEquals(ToolVisualContractRegistry.ResultMode.CRON_LIST,
            ToolVisualContractRegistry.resultMode("CronList"));

        var auth = ToolVisualContractRegistry.useView(
            "mcp__slack__authenticate", "{}", false);
        assertEquals("slack - authenticate (MCP)", auth.displayName());
        assertEquals("(Authenticate slack MCP server)", auth.argsPart());
        assertEquals(ToolVisualContractRegistry.ResultMode.HIDDEN,
            ToolVisualContractRegistry.resultMode("mcp__slack__authenticate"));
    }
}
