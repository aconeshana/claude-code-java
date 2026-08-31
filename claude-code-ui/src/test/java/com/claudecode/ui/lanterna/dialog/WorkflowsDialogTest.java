package com.claudecode.ui.lanterna.dialog;

import org.apache.commons.lang3.Strings;

import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskStore;
import com.claudecode.tools.workflows.WorkflowRun;
import com.claudecode.tools.workflows.WorkflowRunStore;
import com.claudecode.tools.workflows.WorkflowPhase;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.text.FormatUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.graphics.BasicTextImage;
import com.googlecode.lanterna.gui2.TextGUIGraphicsBridge;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowsDialogTest {

    @TempDir Path temp;

    @Test
    void listsLiveRunsWithRunningCountBeforeCompletedCount() {
        WorkflowRunStore store = new WorkflowRunStore();
        store.put(run("wf_done", TaskStatus.COMPLETED, 1));
        store.put(run("wf_live", TaskStatus.RUNNING, 2));
        WorkflowsDialog dialog = new WorkflowsDialog(store, new TaskRegistry(TaskStore.inMemory()));

        dialog.show(() -> {});

        assertTrue(dialog.isActive());
        assertEquals("1 running · 1 completed", dialog.subtitle());
        assertEquals(List.of("wf_live", "wf_done"),
            dialog.items().stream().map(WorkflowRun::runId).toList());
    }

    @Test
    void aSingleRunAutoOpensAndEscapeDismisses() {
        WorkflowRunStore store = new WorkflowRunStore();
        store.put(run("wf_live", TaskStatus.RUNNING, 2));
        WorkflowsDialog dialog = new WorkflowsDialog(store, new TaskRegistry(TaskStore.inMemory()));
        dialog.show(() -> {});

        assertTrue(dialog.isDetailMode());

        dialog.handleKey(new KeyStroke(KeyType.ESCAPE), new AtomicBoolean(true));
        assertFalse(dialog.isActive());
    }

    @Test
    void enterOpensDetailAndEscapeReturnsToAMultiRunList() {
        WorkflowRunStore store = new WorkflowRunStore();
        store.put(run("wf_live", TaskStatus.RUNNING, 2));
        store.put(run("wf_done", TaskStatus.COMPLETED, 1));
        WorkflowsDialog dialog = new WorkflowsDialog(store, new TaskRegistry(TaskStore.inMemory()));
        dialog.show(() -> {});

        dialog.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));
        assertTrue(dialog.isDetailMode());
        dialog.handleKey(new KeyStroke(KeyType.ESCAPE), new AtomicBoolean(true));
        assertTrue(dialog.isActive());
        assertFalse(dialog.isDetailMode());
    }

    @Test
    void backgroundTaskRouteOpensTheRequestedWorkflowDirectly() {
        WorkflowRunStore store = new WorkflowRunStore();
        store.put(WorkflowRun.builder("wf_old", "task_old", TaskStatus.RUNNING)
            .workflowName("old-flow").summary("Old").script("")
            .scriptPath(Path.of("/tmp/old.js")).transcriptDir(Path.of("/tmp"))
            .startTime(1).build());
        store.put(WorkflowRun.builder("wf_target", "task_target", TaskStatus.RUNNING)
            .workflowName("target-flow")
            .summary("Target").script("")
            .scriptPath(Path.of("/tmp/target.js")).transcriptDir(Path.of("/tmp"))
            .startTime(2)
            .phases(List.of(new WorkflowPhase("Discover", "Search", null)))
            .build());
        AtomicBoolean returned = new AtomicBoolean();
        WorkflowsDialog dialog = new WorkflowsDialog(store, new TaskRegistry(TaskStore.inMemory()));

        assertTrue(dialog.showTask("task_target", () -> returned.set(true)));
        assertTrue(dialog.isDetailMode());
        assertTrue(Strings.CS.contains(render(dialog, 100, 20), "target-flow"));
        dialog.handleKey(new KeyStroke(KeyType.ESCAPE), new AtomicBoolean(true));
        assertFalse(dialog.isActive(), "a direct Background route returns instead of revealing /workflows history");
        assertTrue(returned.get());
    }

    @Test
    void saveUsesReleasedNameScopeAndOverwriteConfirmation() throws Exception {
        WorkflowRunStore store = new WorkflowRunStore();
        String script = "export const meta = {name: 'My Flow!', description: 'save'}; return 1;";
        store.put(run("wf_save", TaskStatus.COMPLETED, 1, script));
        Path userDir = temp.resolve("user-workflows");
        Path projectFile = temp.resolve(".claude/workflows/my-flow.js");
        Files.createDirectories(projectFile.getParent());
        Files.writeString(projectFile, "old");
        AtomicReference<String> message = new AtomicReference<>();
        WorkflowsDialog dialog = new WorkflowsDialog(store,
            new TaskRegistry(TaskStore.inMemory()), null, message::set, temp, userDir);
        dialog.show(() -> {});

        press(dialog, 's');
        assertTrue(dialog.isSaveMode());
        dialog.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));
        awaitWorkflowSave(dialog);
        assertEquals("old", Files.readString(projectFile),
            "the first Enter only arms overwrite confirmation");
        dialog.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));
        awaitWorkflowClose(dialog);

        assertEquals(script, Files.readString(projectFile));
        assertTrue(Strings.CS.startsWith(message.get(), "Dynamic workflow saved to "));
        assertFalse(dialog.isActive());
    }

    private static void awaitWorkflowSave(WorkflowsDialog dialog) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (dialog.saveInFlightForTest() && System.nanoTime() < deadline) Thread.onSpinWait();
        assertFalse(dialog.saveInFlightForTest(), "workflow save timed out");
    }

    private static void awaitWorkflowClose(WorkflowsDialog dialog) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (dialog.isActive() && System.nanoTime() < deadline) Thread.onSpinWait();
        assertFalse(dialog.isActive(), "workflow dialog did not close after save");
    }

    @Test
    void rendersReleasedWidePhaseAgentMasterDetailAndOpensTranscript() throws Exception {
        WorkflowRunStore store = new WorkflowRunStore();
        Path transcriptDir = temp.resolve("transcripts");
        Files.createDirectories(transcriptDir);
        String agentId = "a1234567890abcdef";
        Files.writeString(transcriptDir.resolve("agent-" + agentId + ".jsonl"), """
            {"type":"user","message":{"content":"Inspect auth"}}
            {"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read","input":{}},{"type":"text","text":"Auth is sound"}]}}
            """);
        ObjectNode phase = JsonUtils.getMapper().createObjectNode();
        phase.put("type", "workflow_phase");
        phase.put("index", 1);
        phase.put("title", "Inspect");
        ObjectNode agent = JsonUtils.getMapper().createObjectNode();
        agent.put("type", "workflow_agent");
        agent.put("index", 1);
        agent.put("phaseIndex", 1);
        agent.put("phaseTitle", "Inspect");
        agent.put("agentId", agentId);
        agent.put("label", "auth reviewer");
        agent.put("state", "done");
        agent.put("model", "claude-sonnet-5");
        agent.put("tokens", 53_800);
        agent.put("toolCalls", 8);
        agent.put("durationMs", 172_000);
        WorkflowRun base = run("wf_detail", TaskStatus.COMPLETED, 1);
        WorkflowRun detailed = base.toBuilder()
            .transcriptDir(transcriptDir)
            .agentCount(1)
            .workflowProgress(List.of(phase, agent))
            .phases(List.of(new WorkflowPhase("Inspect", "Review", null)))
            .title("Review")
            .build();
        store.put(detailed);
        WorkflowsDialog dialog = new WorkflowsDialog(store, new TaskRegistry(TaskStore.inMemory()));

        dialog.show(() -> {});
        assertTrue(dialog.isPhaseMode());

        String wide = render(dialog, 120, 20);
        assertTrue(Strings.CS.contains(wide, "research"));
        assertTrue(Strings.CS.contains(wide, "1/1 agent ·"), wide);
        assertTrue(Strings.CS.contains(wide, "Phases"));
        assertTrue(Strings.CS.contains(wide, "Inspect · 1 agent"));
        assertTrue(Strings.CS.contains(wide, "auth reviewer"));
        assertTrue(Strings.CS.contains(wide, "Sonnet 5"));
        assertTrue(Strings.CS.contains(wide, "53.8k tok"), wide);
        assertTrue(Strings.CS.contains(wide, "8 tools"));
        assertTrue(Strings.CS.contains(wide, "2m 52s"));

        dialog.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));
        assertTrue(dialog.isAgentListMode());
        String agentSelection = render(dialog, 120, 20);
        assertTrue(Strings.CS.contains(agentSelection, "Phases"),
            "switching selection level must keep the two-pane workflow view visible");
        assertTrue(Strings.CS.contains(agentSelection, "auth reviewer"));
        dialog.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));
        assertTrue(dialog.isTranscriptMode());
        awaitTranscript(dialog);
        assertTrue(dialog.selectedTranscriptLines().contains("Inspect auth"));
        assertTrue(dialog.selectedTranscriptLines().contains("  Read"));
        assertTrue(dialog.selectedTranscriptLines().contains("  Auth is sound"));
        String agentDetail = render(dialog, 120, 20);
        assertTrue(Strings.CS.contains(agentDetail, "Inspect · 1 ag"), agentDetail);
        assertTrue(Strings.CS.contains(agentDetail, "Completed"), agentDetail);
        assertTrue(Strings.CS.contains(agentDetail, "Prompt"), agentDetail);
        assertTrue(Strings.CS.contains(agentDetail, "Activity"), agentDetail);
        assertTrue(Strings.CS.contains(agentDetail, "Outcome"), agentDetail);
        assertTrue(Strings.CS.contains(agentDetail, "Auth is sound"), agentDetail);

        dialog.handleKey(new KeyStroke(KeyType.ESCAPE), new AtomicBoolean(true));
        assertTrue(dialog.isAgentListMode());
        dialog.handleKey(new KeyStroke(KeyType.ESCAPE), new AtomicBoolean(true));
        assertTrue(dialog.isPhaseMode());
        dialog.handleKey(new KeyStroke(KeyType.ESCAPE), new AtomicBoolean(true));
        assertFalse(dialog.isActive());
    }

    @Test
    void compactLayoutKeepsPhasesAndSelectedPhaseAgentsOnOneScreen() {
        ObjectNode phase = JsonUtils.getMapper().createObjectNode();
        phase.put("type", "workflow_phase");
        phase.put("index", 1);
        phase.put("title", "Discover");
        ObjectNode agent = JsonUtils.getMapper().createObjectNode();
        agent.put("type", "workflow_agent");
        agent.put("index", 1);
        agent.put("phaseIndex", 1);
        agent.put("phaseTitle", "Discover");
        agent.put("label", "Search GitHub");
        agent.put("state", "progress");
        agent.put("model", "claude-sonnet-5");
        WorkflowRun detailed = run("wf_compact", TaskStatus.RUNNING, System.currentTimeMillis())
            .toBuilder()
            .workflowName("ecosystem-briefing")
            .summary("Find top projects")
            .agentCount(1)
            .workflowProgress(List.of(phase, agent))
            .phases(List.of(new WorkflowPhase("Discover", "Search GitHub", null)))
            .build();
        WorkflowRunStore store = new WorkflowRunStore();
        store.put(detailed);
        WorkflowsDialog dialog = new WorkflowsDialog(store, new TaskRegistry(TaskStore.inMemory()));
        dialog.show(() -> {});

        String compact = render(dialog, 60, 20);

        assertTrue(Strings.CS.contains(compact, "ecosystem-briefing"), compact);
        assertTrue(Strings.CS.contains(compact, "Discover"));
        assertTrue(Strings.CS.contains(compact, "Search GitHub"));
        assertTrue(Strings.CS.contains(compact, "Sonnet 5"));
        assertTrue(Strings.CS.contains(compact, "↑↓ select"), compact);
        String phaseLine = compact.lines().toList().get(4);
        assertEquals("0/1", phaseLine.substring(17, 20), phaseLine);
        String cardTop = compact.lines().filter(line -> line.length() > 52
            && line.charAt(1) == '┌').findFirst().orElseThrow();
        assertEquals('┐', cardTop.charAt(52), cardTop);
        assertFalse(Strings.CS.contains(cardTop, "Discover"),
            "197 WKm uses an unlabeled outer rule and renders the title inside the card");
    }

    @Test
    void releasedEmptyPhaseUsesNotStartedYetLabel() {
        WorkflowRunStore store = new WorkflowRunStore();
        store.put(run("wf_not_started", TaskStatus.RUNNING, System.currentTimeMillis())
            .toBuilder().phases(List.of(new WorkflowPhase("Discover", "Search", null))).build());
        WorkflowsDialog dialog = new WorkflowsDialog(store,
            new TaskRegistry(TaskStore.inMemory()));
        dialog.show(() -> {});

        String rendered = render(dialog, 100, 20);

        assertTrue(Strings.CS.contains(rendered, "Not started yet"), rendered);
    }

    @Test
    void agentActionsRequireAConcreteReleasedAgentId() {
        ObjectNode queued = JsonUtils.getMapper().createObjectNode();
        queued.put("type", "workflow_agent");
        queued.put("index", 1);
        queued.put("label", "not started");
        queued.put("state", "queued");
        WorkflowRunStore store = new WorkflowRunStore();
        store.put(run("wf_no_agent_id", TaskStatus.RUNNING, System.currentTimeMillis())
            .toBuilder().agentCount(1).workflowProgress(List.of(queued)).build());
        WorkflowsDialog dialog = new WorkflowsDialog(store,
            new TaskRegistry(TaskStore.inMemory()));
        dialog.show(() -> {});

        String rendered = render(dialog, 100, 20);

        assertFalse(Strings.CS.contains(rendered, "x stop"), rendered);
        assertFalse(Strings.CS.contains(rendered, "r restart"), rendered);
    }

    @Test
    void failedReleasedAgentWithIdOffersRestart() {
        ObjectNode failed = JsonUtils.getMapper().createObjectNode();
        failed.put("type", "workflow_agent");
        failed.put("index", 1);
        failed.put("agentId", "agent-retryable");
        failed.put("label", "retry me");
        failed.put("state", "error");
        WorkflowRunStore store = new WorkflowRunStore();
        store.put(run("wf_retry", TaskStatus.RUNNING, System.currentTimeMillis())
            .toBuilder().agentCount(1).workflowProgress(List.of(failed)).build());
        WorkflowsDialog dialog = new WorkflowsDialog(store,
            new TaskRegistry(TaskStore.inMemory()));
        dialog.show(() -> {});

        assertTrue(Strings.CS.contains(render(dialog, 100, 20), "r restart"));
    }

    @Test
    void releasedPhasePrefixMatchingDoesNotDuplicateDeclaredAndRuntimeGroups() {
        ObjectNode runtimePhase = JsonUtils.getMapper().createObjectNode();
        runtimePhase.put("type", "workflow_phase");
        runtimePhase.put("index", 7);
        runtimePhase.put("title", "Discover");
        ObjectNode agent = JsonUtils.getMapper().createObjectNode();
        agent.put("type", "workflow_agent");
        agent.put("index", 1);
        agent.put("phaseIndex", 7);
        agent.put("label", "searcher");
        agent.put("state", "progress");
        WorkflowRunStore store = new WorkflowRunStore();
        store.put(run("wf_phase_prefix", TaskStatus.RUNNING, System.currentTimeMillis())
            .toBuilder()
            .phases(List.of(
                new WorkflowPhase("Discover candidates", "Search", null),
                new WorkflowPhase("Synthesize", "Write", null)))
            .workflowProgress(List.of(runtimePhase, agent))
            .agentCount(1)
            .build());
        WorkflowsDialog dialog = new WorkflowsDialog(store,
            new TaskRegistry(TaskStore.inMemory()));
        dialog.show(() -> {});

        assertEquals(List.of("Discover", "Synthesize"), dialog.phaseTitlesForTest());
        assertEquals(List.of(1, 0), dialog.phaseAgentCountsForTest());
    }

    @Test
    void agentsWithoutAnyPhaseIndexUseReleasedAgentsFallbackGroup() {
        ObjectNode first = JsonUtils.getMapper().createObjectNode();
        first.put("type", "workflow_agent");
        first.put("index", 1);
        first.put("label", "first agent");
        first.put("state", "progress");
        ObjectNode second = first.deepCopy();
        second.put("index", 2);
        second.put("label", "second agent");
        WorkflowRunStore store = new WorkflowRunStore();
        store.put(run("wf_agents_fallback", TaskStatus.RUNNING, System.currentTimeMillis())
            .toBuilder().workflowProgress(List.of(first, second)).agentCount(2).build());
        WorkflowsDialog dialog = new WorkflowsDialog(store,
            new TaskRegistry(TaskStore.inMemory()));
        dialog.show(() -> {});

        assertEquals(List.of("Agents"), dialog.phaseTitlesForTest());
        assertEquals(List.of(2), dialog.phaseAgentCountsForTest());
        String rendered = render(dialog, 100, 20);
        assertTrue(Strings.CS.contains(rendered, "first agent"), rendered);
        assertTrue(Strings.CS.contains(rendered, "second agent"), rendered);
    }

    @Test
    void pausedWorkflowWithoutScriptPathDoesNotOfferReleasedResume() {
        WorkflowRunStore store = new WorkflowRunStore();
        store.put(WorkflowRun.builder("wf_no_resume", "task_no_resume", TaskStatus.PAUSED)
            .workflowName("parked")
            .summary("Parked")
            .script("")
            .scriptPath(null)
            .transcriptDir(Path.of("/tmp"))
            .startTime(System.currentTimeMillis())
            .build());
        AtomicReference<String> resumed = new AtomicReference<>();
        WorkflowsDialog dialog = new WorkflowsDialog(store,
            new TaskRegistry(TaskStore.inMemory()), resumed::set);
        dialog.show(() -> {});

        assertFalse(Strings.CS.contains(render(dialog, 100, 20), "p resume"));
        press(dialog, 'p');
        assertNull(resumed.get());
        assertTrue(dialog.isActive());
    }

    @Test
    void releasedDetailConsumesTheFullOverlayWidthAndTranscriptHeight() {
        WorkflowRunStore store = new WorkflowRunStore();
        store.put(run("wf_fullscreen", TaskStatus.RUNNING, System.currentTimeMillis()));
        WorkflowsDialog dialog = new WorkflowsDialog(store,
            new TaskRegistry(TaskStore.inMemory()));
        dialog.show(() -> {});

        TerminalSize viewport = new TerminalSize(120, 36);
        BasicTextImage image = new BasicTextImage(viewport);
        dialog.setSize(viewport);
        dialog.draw(TextGUIGraphicsBridge.wrap(null, image.newTextGraphics()));
        var body = dialog.getChildren().iterator().next();

        assertEquals(120, body.getSize().getColumns(),
            "the workflow body must fill the full-width overlay allocated by SmartLayout");
        assertTrue(dialog.calculatePreferredSize().getRows() > 20,
            "the released detail covers the transcript instead of requesting a fixed 20 rows");
    }

    @Test
    void releasedWideOverviewUsesNaturalPhaseWidthAndExactOuterMargins() {
        ObjectNode phase = JsonUtils.getMapper().createObjectNode();
        phase.put("type", "workflow_phase");
        phase.put("index", 1);
        phase.put("title", "Discover");
        ObjectNode agent = agent("a5555555555555555", "searcher", "progress", 1);
        WorkflowRunStore store = new WorkflowRunStore();
        store.put(run("wf_geometry", TaskStatus.RUNNING, System.currentTimeMillis())
            .toBuilder().agentCount(1).workflowProgress(List.of(phase, agent)).build());
        WorkflowsDialog dialog = new WorkflowsDialog(store, new TaskRegistry(TaskStore.inMemory()));
        dialog.show(() -> {});

        List<String> rendered = render(dialog, 120, 36).lines().toList();
        String border = rendered.get(4);

        assertEquals('┌', border.charAt(1), border);
        assertEquals('┬', border.charAt(20),
            "197 uses width-9 and the phase row's natural 16-column width");
        assertEquals('┐', border.charAt(112), border);
        assertEquals(' ', border.charAt(113), border);
        assertEquals('●', rendered.get(5).charAt(23), rendered.get(5));
        assertTrue(Strings.CS.startsWith(rendered.get(5).substring(25), "searcher"), rendered.get(5));
    }

    @Test
    void releasedAgentPromptStartsCollapsedAndEnterTogglesExpansion() throws Exception {
        TranscriptFixture fixture = transcriptFixture("wf_prompt", "a1111111111111111", """
            first line
            second line
            third line
            fourth line
            """, List.of(), "done");
        WorkflowsDialog dialog = fixture.dialog();

        openTranscript(dialog);
        String collapsed = render(dialog, 100, 22);
        assertTrue(Strings.CS.contains(collapsed, "Prompt · 4 lines · ⏎ expand"), collapsed);
        assertTrue(Strings.CS.contains(collapsed, "⏎ prompt"), collapsed);
        assertTrue(Strings.CS.contains(collapsed, "2 more lines"), collapsed);
        assertFalse(Strings.CS.contains(collapsed, "third line"), collapsed);

        dialog.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));
        String expanded = render(dialog, 100, 22);
        assertTrue(Strings.CS.contains(expanded, "Prompt · 4 lines"), expanded);
        assertFalse(Strings.CS.contains(expanded, "⏎ expand"), expanded);
        assertTrue(Strings.CS.contains(expanded, "third line"), expanded);
    }

    @Test
    void releasedAgentPromptWrapsAgainstTheActualCompactPaneWidth() throws Exception {
        String prompt = "A".repeat(46) + "B".repeat(46) + "C".repeat(20);
        TranscriptFixture fixture = transcriptFixture("wf_prompt_wrap", "a1212121212121212",
            prompt, List.of(), "done");
        WorkflowsDialog dialog = fixture.dialog();

        openTranscript(dialog);
        String collapsed = render(dialog, 60, 22);
        assertTrue(Strings.CS.contains(collapsed, "Prompt · 3 lines · ⏎ expand"), collapsed);
        assertFalse(Strings.CS.contains(collapsed, "C".repeat(20)), collapsed);

        dialog.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));
        String expanded = render(dialog, 60, 22);
        assertTrue(Strings.CS.contains(expanded, "C".repeat(20)), expanded);
    }

    @Test
    void releasedActivityShowsOnlyLastThreeToolCallsWithSummaries() throws Exception {
        TranscriptFixture fixture = transcriptFixture("wf_activity", "a2222222222222222", "inspect", List.of(
            "{\"type\":\"tool_use\",\"name\":\"Read\",\"input\":{\"file_path\":\"old.txt\"}}",
            "{\"type\":\"tool_use\",\"name\":\"Grep\",\"input\":{\"pattern\":\"needle\"}}",
            "{\"type\":\"tool_use\",\"name\":\"Bash\",\"input\":{\"command\":\"git status\"}}",
            "{\"type\":\"tool_use\",\"name\":\"Read\",\"input\":{\"file_path\":\"new.txt\"}}"), "done");

        openTranscript(fixture.dialog());
        String rendered = render(fixture.dialog(), 110, 24);

        assertTrue(Strings.CS.contains(rendered, "last 3 of 4 tool calls"), rendered);
        assertFalse(Strings.CS.contains(rendered, "old.txt"), rendered);
        assertTrue(Strings.CS.contains(rendered, "Grep(needle)"), rendered);
        assertTrue(Strings.CS.contains(rendered, "Bash(git status)"), rendered);
        assertTrue(Strings.CS.contains(rendered, "Read(new.txt)"), rendered);
    }

    @Test
    void releasedWideAgentDetailUsesExactNaturalColumnGeometry() throws Exception {
        TranscriptFixture fixture = transcriptFixture(
            "wf_agent_geometry", "a8888888888888888", "inspect", List.of(), "done");
        ObjectNode richAgent = fixture.agent().deepCopy();
        richAgent.put("model", "claude-sonnet-5");
        richAgent.put("tokens", 54_100);
        fixture.store().put(fixture.run().toBuilder().workflowProgress(List.of(richAgent)).build());
        openTranscript(fixture.dialog());

        List<String> rendered = render(fixture.dialog(), 120, 36).lines().toList();
        String border = rendered.get(4);

        assertEquals('┌', border.charAt(1), border);
        assertEquals('┬', border.charAt(18),
            "197 clamps the agent list to its 14-column natural minimum");
        assertEquals('┐', border.charAt(112), border);
        assertEquals(' ', border.charAt(113), border);
        assertFalse(Strings.CS.contains(rendered.get(5).substring(3, 17), "Sonnet"), rendered.get(5));
    }

    @Test
    void selectedAgentIdentitySurvivesReleasedProgressReordering() {
        ObjectNode first = agent("a3333333333333333", "first", "progress", 1);
        ObjectNode second = agent("a4444444444444444", "second", "progress", 1);
        WorkflowRunStore store = new WorkflowRunStore();
        WorkflowRun initial = run("wf_identity", TaskStatus.RUNNING, System.currentTimeMillis())
            .toBuilder().agentCount(2).workflowProgress(List.of(first, second)).build();
        store.put(initial);
        WorkflowsDialog dialog = new WorkflowsDialog(store, new TaskRegistry(TaskStore.inMemory()));
        dialog.show(() -> {});
        dialog.handleKey(new KeyStroke(KeyType.ARROW_DOWN), new AtomicBoolean(true));

        store.put(initial.toBuilder().workflowProgress(List.of(second, first)).build());
        String rendered = render(dialog, 100, 20);

        assertTrue(Strings.CS.contains(rendered, "❯● second"), rendered);
    }

    @Test
    void openAgentTranscriptReloadsWhenReleasedToolCallCountChanges() throws Exception {
        TranscriptFixture fixture = transcriptFixture("wf_live", "a5555555555555555", "inspect",
            List.of("{\"type\":\"tool_use\",\"name\":\"Read\",\"input\":{}}"), "progress");
        WorkflowsDialog dialog = fixture.dialog();
        openTranscript(dialog);
        assertTrue(dialog.selectedTranscriptLines().contains("  Read"));

        Files.writeString(fixture.transcript(), transcriptJson("inspect", List.of(
            "{\"type\":\"tool_use\",\"name\":\"Read\",\"input\":{}}",
            "{\"type\":\"tool_use\",\"name\":\"Bash\",\"input\":{\"command\":\"pwd\"}}"), "working"));
        ObjectNode updatedAgent = fixture.agent().deepCopy();
        updatedAgent.put("toolCalls", 2);
        fixture.store().put(fixture.run().toBuilder().workflowProgress(List.of(updatedAgent)).build());
        render(dialog, 100, 22);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!dialog.selectedTranscriptLines().stream().anyMatch(line -> Strings.CS.contains(line, "Bash"))
                && System.nanoTime() < deadline) Thread.onSpinWait();
        assertTrue(dialog.selectedTranscriptLines().stream()
            .anyMatch(line -> Strings.CS.contains(line, "Bash")));
    }

    @Test
    void releasedOutcomeUsesQueuedAndFailedStateMessages() throws Exception {
        TranscriptFixture queued = transcriptFixture("wf_queued", "a6666666666666666", "inspect",
            List.of(), "queued");
        openTranscript(queued.dialog());
        String queuedDetail = render(queued.dialog(), 100, 20);
        assertTrue(Strings.CS.contains(queuedDetail, "Waiting for an agent slot."), queuedDetail);
        assertFalse(Strings.CS.contains(queuedDetail, "Activity"), queuedDetail);

        TranscriptFixture failed = transcriptFixture("wf_failed", "a7777777777777777", "inspect",
            List.of(), "error");
        ObjectNode failedAgent = failed.agent().deepCopy();
        failedAgent.put("error", "rate limited");
        failed.store().put(failed.run().toBuilder().workflowProgress(List.of(failedAgent)).build());
        openTranscript(failed.dialog());
        String failedDetail = render(failed.dialog(), 100, 20);
        assertTrue(Strings.CS.contains(failedDetail, "rate limited"), failedDetail);
    }

    @Test
    void compactAgentBorderUsesTerminalWidthForCjkLabels() {
        String border = WorkflowsDialog.singleBorderForTest(54, "中文代理详情");

        assertEquals(56, FormatUtils.displayWidth(border), border);
        assertTrue(Strings.CS.endsWith(border, "┐"), border);
    }

    @Test
    void releasedAgentTailKeepsModelLeftAndStatsRightAligned() {
        String tail = WorkflowsDialog.agentTailForTest("Sonnet 5", "54.1k tok", 24);

        assertEquals(24, FormatUtils.displayWidth(tail));
        assertTrue(Strings.CS.startsWith(tail, "Sonnet 5"), tail);
        assertTrue(Strings.CS.endsWith(tail, "54.1k tok"), tail);
        assertFalse(Strings.CS.contains(tail, " · "), tail);
    }

    @Test
    void releasedListWindowTracksSelectionAndShowsBothOverflowDirections() {
        assertEquals("↑ 4–8 of 10 ↓", WorkflowsDialog.windowRangeForTest(5, 10, 5));
        assertEquals("  1–5 of 10 ↓", WorkflowsDialog.windowRangeForTest(0, 10, 5));
        assertEquals("↑ 6–10 of 10  ", WorkflowsDialog.windowRangeForTest(9, 10, 5));
    }

    @Test
    void releasedDetailStatsUseToolCallsWithoutRepeatingIsolationOrFailure() {
        ObjectNode agent = JsonUtils.getMapper().createObjectNode();
        agent.put("tokens", 54_100);
        agent.put("toolCalls", 2);
        agent.put("durationMs", 221_000);
        agent.put("isolation", "worktree");
        agent.put("error", "rate limited");

        String stats = WorkflowsDialog.agentDetailStatsForTest(agent, "failed");

        assertEquals("54.1k tok · 2 tool calls · 3m 41s", stats);
    }

    private TranscriptFixture transcriptFixture(String runId, String agentId, String prompt,
                                                List<String> toolBlocks, String state) throws Exception {
        Path transcriptDir = temp.resolve(runId);
        Files.createDirectories(transcriptDir);
        Path transcript = transcriptDir.resolve("agent-" + agentId + ".jsonl");
        Files.writeString(transcript, transcriptJson(prompt, toolBlocks, "complete"));
        ObjectNode agent = agent(agentId, "worker", state, 1);
        if (Strings.CS.equals("queued", state)) agent.put("queuedAt", System.currentTimeMillis());
        agent.put("toolCalls", toolBlocks.size());
        WorkflowRun run = run(runId, TaskStatus.RUNNING, System.currentTimeMillis()).toBuilder()
            .transcriptDir(transcriptDir).agentCount(1).workflowProgress(List.of(agent)).build();
        WorkflowRunStore store = new WorkflowRunStore();
        store.put(run);
        return new TranscriptFixture(store, run, agent, transcript,
            new WorkflowsDialog(store, new TaskRegistry(TaskStore.inMemory())));
    }

    private static ObjectNode agent(String id, String label, String state, int phaseIndex) {
        ObjectNode agent = JsonUtils.getMapper().createObjectNode();
        agent.put("type", "workflow_agent");
        agent.put("index", 1);
        agent.put("phaseIndex", phaseIndex);
        agent.put("agentId", id);
        agent.put("label", label);
        agent.put("state", state);
        return agent;
    }

    private static String transcriptJson(String prompt, List<String> toolBlocks, String result) {
        String content = String.join(",", toolBlocks);
        if (!content.isEmpty()) content += ",";
        return "{\"type\":\"user\",\"message\":{\"content\":"
            + jsonString(prompt) + "}}\n"
            + "{\"type\":\"assistant\",\"message\":{\"content\":[" + content
            + "{\"type\":\"text\",\"text\":" + jsonString(result) + "}]}}\n";
    }

    private static String jsonString(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n") + '"';
    }

    private static void openTranscript(WorkflowsDialog dialog) {
        dialog.show(() -> {});
        if (dialog.isPhaseMode()) {
            dialog.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));
        }
        dialog.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));
        awaitTranscript(dialog);
    }

    private record TranscriptFixture(WorkflowRunStore store, WorkflowRun run, ObjectNode agent,
                                     Path transcript, WorkflowsDialog dialog) {}

    private static void awaitTranscript(WorkflowsDialog dialog) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (dialog.selectedTranscriptLines().isEmpty() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertFalse(dialog.selectedTranscriptLines().isEmpty());
    }

    private static WorkflowRun run(String id, TaskStatus status, long time) {
        return run(id, status, time, "");
    }

    private static WorkflowRun run(String id, TaskStatus status, long time, String script) {
        return WorkflowRun.builder(id, "w12345678", status)
            .workflowName("research")
            .summary("Find facts")
            .script(script)
            .scriptPath(Path.of("/tmp/script.js"))
            .transcriptDir(Path.of("/tmp/transcript"))
            .outputFile(Path.of("/tmp/out"))
            .runFile(Path.of("/tmp/run"))
            .timestamp(Instant.now())
            .startTime(time)
            .build();
    }

    private static void press(WorkflowsDialog dialog, char character) {
        dialog.handleKey(new KeyStroke(character, false, false), new AtomicBoolean(true));
    }

    private static String render(WorkflowsDialog dialog, int columns, int rows) {
        TerminalSize size = new TerminalSize(columns, rows);
        BasicTextImage image = new BasicTextImage(size);
        var body = dialog.getChildren().iterator().next();
        body.setSize(size);
        body.draw(TextGUIGraphicsBridge.wrap(null, image.newTextGraphics()));
        StringBuilder text = new StringBuilder(columns * rows);
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                text.append(image.getCharacterAt(column, row).getCharacter());
            }
            text.append('\n');
        }
        return text.toString();
    }
}
