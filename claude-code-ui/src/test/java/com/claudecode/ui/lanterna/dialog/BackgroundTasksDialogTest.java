package com.claudecode.ui.lanterna.dialog;

import org.apache.commons.lang3.Strings;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.tools.tasks.LocalAgentTask;
import com.claudecode.tools.tasks.LocalShellTask;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskStore;
import com.claudecode.tools.tasks.TaskType;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.PasteKeyStroke;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.claudecode.ui.lanterna.transcript.BackgroundTasksRenderer;

/**
 * Unit tests for {@link BackgroundTasksDialog} — drives
 * {@link BackgroundTasksDialog#handleKey} directly (no real GUI thread
 * needed, same pattern as {@code MessageSelectorDialogTest}) against an
 * in-memory {@link TaskRegistry} so no test touches the real
 * {@code ~/.claude/tasks} directory.
 */
class BackgroundTasksDialogTest {

    private static final KeyStroke UP = new KeyStroke(KeyType.ARROW_UP);
    private static final KeyStroke DOWN = new KeyStroke(KeyType.ARROW_DOWN);
    private static final KeyStroke ENTER = new KeyStroke(KeyType.ENTER);
    private static final KeyStroke ESC = new KeyStroke(KeyType.ESCAPE);
    private static final KeyStroke LEFT = new KeyStroke(KeyType.ARROW_LEFT);
    private static final KeyStroke X = new KeyStroke('x', false, false);
    private static final KeyStroke SPACE = new KeyStroke(' ', false, false);

    private static void key(BackgroundTasksDialog d, KeyStroke k) {
        d.handleKey(k, new AtomicBoolean(true));
    }

    private static TaskRegistry newRegistry() {
        return new TaskRegistry(TaskStore.inMemory());
    }

    private static TaskState runningShell(TaskRegistry registry, String command) {
        TaskState t = registry.store().create(TaskType.LOCAL_BASH, command);
        registry.store().updateStatus(t.id(), TaskStatus.RUNNING);
        registry.registerShell(new LocalShellTask(t, command, registry.store()));
        return t;
    }

    private static TaskState pendingShell(TaskRegistry registry, String command) {
        return registry.store().create(TaskType.LOCAL_BASH, command);
    }

    private static TaskState runningAgent(TaskRegistry registry, String description) {
        TaskState t = registry.store().create(TaskType.LOCAL_AGENT, description);
        registry.store().updateStatus(t.id(), TaskStatus.RUNNING);
        registry.registerAgent(new LocalAgentTask(t, registry.store()));
        return t;
    }

    private static TaskState runningWebSocketMonitor(TaskRegistry registry, String description) {
        TaskState task = registry.store().create(TaskType.MONITOR_WS, description);
        registry.store().updateStatus(task.id(), TaskStatus.RUNNING);
        return task;
    }

    private static TaskState runningWorkflow(TaskRegistry registry, String description) {
        TaskState task = registry.store().create(TaskType.LOCAL_WORKFLOW, description);
        registry.store().updateStatus(task.id(), TaskStatus.RUNNING);
        return task;
    }

    // ── idle / activation ───────────────────────────────────────────────

    @Test
    void idle_hasZeroPreferredSize_andIsInactive() {
        BackgroundTasksDialog d = new BackgroundTasksDialog(newRegistry());
        assertFalse(d.isActive());
        assertEquals(new TerminalSize(0, 0), d.calculatePreferredSize());
    }

    @Test
    void handleKey_noOpWhileIdle() {
        BackgroundTasksDialog d = new BackgroundTasksDialog(newRegistry());
        AtomicBoolean deliver = new AtomicBoolean(true);
        d.handleKey(ENTER, deliver);
        assertTrue(deliver.get());
        assertFalse(d.isActive());
    }

    @Test
    void show_emptyRegistry_stillActivatesOnListMode() {
        BackgroundTasksDialog d = new BackgroundTasksDialog(newRegistry());
        d.show(() -> {});
        assertTrue(d.isActive());
        assertEquals(BackgroundTasksDialog.Mode.LIST, d.mode());
        assertTrue(d.items().isEmpty());
    }

    @Test
    void show_twoTasks_opensOnListMode() {
        TaskRegistry registry = newRegistry();
        runningShell(registry, "npm run build");
        runningAgent(registry, "explore repo");
        BackgroundTasksDialog d = new BackgroundTasksDialog(registry);

        d.show(() -> {});

        assertEquals(BackgroundTasksDialog.Mode.LIST, d.mode());
        assertEquals(2, d.items().size());
    }

    @Test
    void localAgentListKeepsDescriptionOnlyEvenWhenDetailHasRawAgentType() {
        TaskRegistry registry = newRegistry();
        runningShell(registry, "npm run build");
        TaskState agent = runningAgent(registry, "inspect terminal rendering");
        registry.store().updateAgentType(agent.id(), "general-purpose");
        BackgroundTasksDialog dialog = new BackgroundTasksDialog(registry);

        dialog.show(() -> {});

        BackgroundTasksDialog.ListItem item = dialog.items().stream()
            .filter(candidate -> candidate.task().id().equals(agent.id()))
            .findFirst().orElseThrow();
        assertEquals("inspect terminal rendering", item.label());
    }

    @Test
    void show_exactlyOneTask_skipsListAndEntersDetail() {
        TaskRegistry registry = newRegistry();
        TaskState only = runningShell(registry, "npm run build");
        BackgroundTasksDialog d = new BackgroundTasksDialog(registry);

        d.show(() -> {});

        assertEquals(BackgroundTasksDialog.Mode.DETAIL, d.mode());
        assertEquals(only.id(), d.detailTaskId());
        assertTrue(d.skippedListOnMount());
    }

    @Test
    void singleWorkflowRoutesToReleasedWorkflowDetailInsteadOfGenericAgentDetail() {
        TaskRegistry registry = newRegistry();
        TaskState workflow = runningWorkflow(registry, "research ecosystem");
        BackgroundTasksDialog dialog = new BackgroundTasksDialog(registry);
        AtomicReference<TaskState> viewed = new AtomicReference<>();
        AtomicBoolean returnToList = new AtomicBoolean(true);
        AtomicBoolean dismissed = new AtomicBoolean();
        dialog.setOnViewWorkflowRoute((task, back) -> {
            viewed.set(task);
            returnToList.set(back);
        });

        dialog.show(() -> dismissed.set(true));

        assertFalse(dialog.isActive());
        assertEquals(workflow.id(), viewed.get().id());
        assertFalse(dismissed.get(),
            "routing to WorkflowDetailDialog is a view transition, not a background-dialog dismissal");
        assertFalse(returnToList.get());
    }

    @Test
    void mixedTaskListIncludesDynamicWorkflowAndEnterRoutesToWorkflowDetail() {
        TaskRegistry registry = newRegistry();
        runningShell(registry, "npm test");
        TaskState workflow = runningWorkflow(registry, "research ecosystem");
        BackgroundTasksDialog dialog = new BackgroundTasksDialog(registry);
        AtomicReference<TaskState> viewed = new AtomicReference<>();
        AtomicBoolean returnToList = new AtomicBoolean();
        dialog.setOnViewWorkflowRoute((task, back) -> {
            viewed.set(task);
            returnToList.set(back);
        });
        dialog.show(() -> {});

        assertEquals(List.of(TaskType.LOCAL_BASH, TaskType.LOCAL_WORKFLOW),
            dialog.items().stream().map(item -> item.task().type()).toList());
        key(dialog, DOWN);
        key(dialog, ENTER);

        assertEquals(workflow.id(), viewed.get().id());
        assertFalse(dialog.isActive());
        assertTrue(returnToList.get());
    }

    @Test
    void singleWebSocketMonitorStaysInTheReleasedMonitorsList() {
        TaskRegistry registry = newRegistry();
        TaskState monitor = runningWebSocketMonitor(registry, "socket events");
        BackgroundTasksDialog dialog = new BackgroundTasksDialog(registry);

        dialog.show(() -> {});

        assertEquals(BackgroundTasksDialog.Mode.LIST, dialog.mode());
        assertFalse(dialog.skippedListOnMount());
        assertEquals(List.of(monitor.id()),
            dialog.items().stream().map(item -> item.task().id()).toList());
        assertTrue(dialog.calculatePreferredSize().getRows() >= 8,
            "the always-visible Monitors header and monitor row must both be rendered");
    }

    // ── list navigation / clamping ───────────────────────────────────────

    @Test
    void listNavigation_upDown_clampsAtBounds() {
        TaskRegistry registry = newRegistry();
        runningShell(registry, "cmd-a");
        runningShell(registry, "cmd-b");
        BackgroundTasksDialog d = new BackgroundTasksDialog(registry);
        d.show(() -> {});

        assertEquals(0, d.selectedIndex());
        key(d, UP); // clamp at 0, does not go negative
        assertEquals(0, d.selectedIndex());

        key(d, DOWN);
        assertEquals(1, d.selectedIndex());
        key(d, DOWN); // clamp at last index, does not wrap
        assertEquals(1, d.selectedIndex());

        key(d, UP);
        assertEquals(0, d.selectedIndex());
    }

    @Test
    void fOpensSelectedLocalAgentTranscriptAndClosesDialog() {
        TaskRegistry registry = newRegistry();
        runningShell(registry, "cmd-a");
        TaskState agent = runningAgent(registry, "inspect repository");
        BackgroundTasksDialog dialog = new BackgroundTasksDialog(registry);
        AtomicReference<TaskState> viewed = new AtomicReference<>();
        dialog.setOnViewAgent(viewed::set);
        dialog.show(() -> {});
        key(dialog, DOWN);

        key(dialog, new KeyStroke('f', false, false));

        assertEquals(agent.id(), viewed.get().id());
        assertFalse(dialog.isActive());
    }

    @Test
    void fOpensSingleAutoSelectedAgentTranscriptFromDetail() {
        TaskRegistry registry = newRegistry();
        TaskState agent = runningAgent(registry, "inspect repository");
        BackgroundTasksDialog dialog = new BackgroundTasksDialog(registry);
        AtomicReference<TaskState> viewed = new AtomicReference<>();
        dialog.setOnViewAgent(viewed::set);
        dialog.show(() -> {});
        assertEquals(BackgroundTasksDialog.Mode.DETAIL, dialog.mode());

        key(dialog, new KeyStroke('f', false, false));

        assertEquals(agent.id(), viewed.get().id());
        assertFalse(dialog.isActive());
    }

    @Test
    void listUsesReboundConfirmationNavigationAndHonorsUnbind(@TempDir Path tmp)
            throws Exception {
        TaskRegistry registry = newRegistry();
        runningShell(registry, "cmd-a");
        runningShell(registry, "cmd-b");
        BackgroundTasksDialog d = new BackgroundTasksDialog(registry);
        var store = createStore(tmp.resolve("keybindings.json"), """
            [{"context":"Confirmation","bindings":{
              "ctrl+j":"confirm:next",
              "down":null
            }}]
            """);
        try {
            d.setKeybindingsStore(store);
            d.show(() -> {});
            key(d, DOWN);
            assertEquals(0, d.selectedIndex());
            key(d, new KeyStroke('j', true, false));
            assertEquals(1, d.selectedIndex());
        } finally {
            store.dispose();
        }
    }

    private static UserKeybindingsStore createStore(
            Path file, String json) throws Exception {
        Files.writeString(file, json);
        Method create = UserKeybindingsStore.class
            .getDeclaredMethod("create", Path.class, boolean.class);
        create.setAccessible(true);
        return (UserKeybindingsStore) create.invoke(null, file, true);
    }

    @Test
    void listSort_runningFirst_thenStartTimeDescending() throws InterruptedException {
        TaskRegistry registry = newRegistry();
        TaskState older = pendingShell(registry, "older-pending");
        Thread.sleep(5);
        TaskState newer = pendingShell(registry, "newer-pending");
        Thread.sleep(5);
        TaskState running = runningShell(registry, "the-running-one");
        BackgroundTasksDialog d = new BackgroundTasksDialog(registry);

        d.show(() -> {});

        List<BackgroundTasksDialog.ListItem> items = d.items();
        assertEquals(3, items.size());
        assertEquals(running.id(), items.getFirst().task().id(), "running task sorts first");
        assertEquals(newer.id(), items.get(1).task().id(), "newer pending before older pending");
        assertEquals(older.id(), items.get(2).task().id());
    }

    // ── Enter -> detail, then back to list ───────────────────────────────

    @Test
    void enterOnSelectedRow_entersDetailMode() {
        TaskRegistry registry = newRegistry();
        TaskState a = runningShell(registry, "cmd-a");
        runningShell(registry, "cmd-b");
        BackgroundTasksDialog d = new BackgroundTasksDialog(registry);
        d.show(() -> {});

        // default selectedIndex 0 -> the first sorted item
        String firstId = d.items().getFirst().task().id();
        key(d, ENTER);

        assertEquals(BackgroundTasksDialog.Mode.DETAIL, d.mode());
        assertEquals(firstId, d.detailTaskId());
    }

    @Test
    void leftArrowFromDetail_returnsToListWhenNotSkipped() {
        TaskRegistry registry = newRegistry();
        runningShell(registry, "cmd-a");
        runningShell(registry, "cmd-b");
        BackgroundTasksDialog d = new BackgroundTasksDialog(registry);
        d.show(() -> {});
        key(d, ENTER); // list -> detail

        key(d, LEFT); // detail -> list ("go back")

        assertTrue(d.isActive());
        assertEquals(BackgroundTasksDialog.Mode.LIST, d.mode());
    }

    // ── goBackToList: skippedListOnMount branches ────────────────────────

    @Test
    void goBackToList_skippedOnMount_singleTaskStillOnly_closesEntireDialog() {
        TaskRegistry registry = newRegistry();
        runningShell(registry, "only-task");
        BackgroundTasksDialog d = new BackgroundTasksDialog(registry);
        AtomicInteger closedCount = new AtomicInteger();
        d.show(closedCount::incrementAndGet);
        assertTrue(d.skippedListOnMount());
        assertEquals(BackgroundTasksDialog.Mode.DETAIL, d.mode());

        key(d, LEFT); // "go back" with only 1 task ever -> close whole dialog

        assertFalse(d.isActive());
        assertEquals(1, closedCount.get());
    }

    @Test
    void goBackToList_skippedOnMount_butSecondTaskAppeared_showsListInstead() {
        TaskRegistry registry = newRegistry();
        runningShell(registry, "only-task");
        BackgroundTasksDialog d = new BackgroundTasksDialog(registry);
        AtomicInteger closedCount = new AtomicInteger();
        d.show(closedCount::incrementAndGet);
        assertTrue(d.skippedListOnMount());

        // A second background task starts while viewing the first one's detail.
        runningShell(registry, "second-task");

        key(d, LEFT); // re-checks CURRENT count (now 2) -> must reveal list, not close

        assertTrue(d.isActive());
        assertEquals(0, closedCount.get());
        assertEquals(BackgroundTasksDialog.Mode.LIST, d.mode());
        assertEquals(2, d.items().size());
    }

    // ── kill: only running tasks are killable ────────────────────────────

    @Test
    void killSelected_runningShell_transitionsToKilled() {
        TaskRegistry registry = newRegistry();
        TaskState shell = runningShell(registry, "sleep 100");
        runningAgent(registry, "keep list at 2");
        BackgroundTasksDialog d = new BackgroundTasksDialog(registry);
        d.show(() -> {});
        int idx = indexOf(d, shell.id());
        for (int i = 0; i < idx; i++) key(d, DOWN);

        key(d, X);

        assertEquals(TaskStatus.KILLED, registry.get(shell.id()).get().status());
    }

    @Test
    void killSelected_pendingTask_isNoOp() {
        TaskRegistry registry = newRegistry();
        TaskState pending = pendingShell(registry, "not started yet");
        runningAgent(registry, "keep list at 2");
        BackgroundTasksDialog d = new BackgroundTasksDialog(registry);
        d.show(() -> {});
        int idx = indexOf(d, pending.id());
        for (int i = 0; i < idx; i++) key(d, DOWN);

        key(d, X);

        assertEquals(TaskStatus.PENDING, registry.get(pending.id()).get().status(),
            "x on a non-running task must be a no-op — mirrors TS status === 'running' guard");
    }

    @Test
    void killDetailTask_runningAgent_killsAndFallsBackToList() {
        TaskRegistry registry = newRegistry();
        TaskState agent = runningAgent(registry, "long running agent");
        runningShell(registry, "other-task");
        BackgroundTasksDialog d = new BackgroundTasksDialog(registry);
        d.show(() -> {});
        int idx = indexOf(d, agent.id());
        for (int i = 0; i < idx; i++) key(d, DOWN);
        key(d, ENTER); // -> detail

        key(d, X); // kill from detail

        assertEquals(TaskStatus.KILLED, registry.get(agent.id()).get().status());
        assertEquals(BackgroundTasksDialog.Mode.LIST, d.mode(), "not skipped-on-mount -> falls back to list");
    }

    @Test
    void killDetailTask_skippedOnMount_closesWholeDialogAfterKill() {
        TaskRegistry registry = newRegistry();
        TaskState only = runningShell(registry, "only-task");
        BackgroundTasksDialog d = new BackgroundTasksDialog(registry);
        AtomicInteger closedCount = new AtomicInteger();
        d.show(closedCount::incrementAndGet);
        assertTrue(d.skippedListOnMount());

        key(d, X); // kill from the auto-skipped detail view

        assertEquals(TaskStatus.KILLED, registry.get(only.id()).get().status());
        assertFalse(d.isActive());
        assertEquals(1, closedCount.get());
    }

    // ── close paths ───────────────────────────────────────────────────────

    @Test
    void escapeFromList_closesDialog() {
        BackgroundTasksDialog d = new BackgroundTasksDialog(newRegistry());
        AtomicInteger closedCount = new AtomicInteger();
        d.show(closedCount::incrementAndGet);

        key(d, ESC);

        assertFalse(d.isActive());
        assertEquals(1, closedCount.get());
    }

    @Test
    void leftArrowFromList_closesDialog() {
        BackgroundTasksDialog d = new BackgroundTasksDialog(newRegistry());
        AtomicInteger closedCount = new AtomicInteger();
        d.show(closedCount::incrementAndGet);

        key(d, LEFT);

        assertFalse(d.isActive());
        assertEquals(1, closedCount.get());
    }

    @Test
    void spaceFromDetail_closesEntireDialog() {
        TaskRegistry registry = newRegistry();
        runningShell(registry, "cmd-a");
        runningShell(registry, "cmd-b");
        BackgroundTasksDialog d = new BackgroundTasksDialog(registry);
        d.show(() -> {});
        key(d, ENTER); // -> detail

        key(d, SPACE);

        assertFalse(d.isActive());
    }

    @Test
    void escFromDetail_closesEntireDialog() {
        TaskRegistry registry = newRegistry();
        runningShell(registry, "cmd-a");
        runningShell(registry, "cmd-b");
        BackgroundTasksDialog d = new BackgroundTasksDialog(registry);
        d.show(() -> {});
        key(d, ENTER); // -> detail

        key(d, ESC);

        assertFalse(d.isActive());
    }



    private static TaskState task(TaskType type, TaskStatus status) {
        TaskState t = TaskState.withId("t-" + type + "-" + status, type, "desc");
        return switch (status) {
            case PENDING -> t;
            case RUNNING -> t.withStatus(TaskStatus.RUNNING);
            default -> t.withStatus(TaskStatus.RUNNING).withStatus(status);
        };
    }

    @Test
    void rowStatusSuffix_shell_mirrorsShellProgressLabels() {

        // running AND pending both render "(running)"; no unread suffix.
        assertEquals("(running)", BackgroundTasksRenderer.rowStatusSuffix(task(TaskType.LOCAL_BASH, TaskStatus.RUNNING)));
        assertEquals("(running)", BackgroundTasksRenderer.rowStatusSuffix(task(TaskType.LOCAL_BASH, TaskStatus.PENDING)));
        assertEquals("(done)", BackgroundTasksRenderer.rowStatusSuffix(task(TaskType.LOCAL_BASH, TaskStatus.COMPLETED)));
        assertEquals("(error)", BackgroundTasksRenderer.rowStatusSuffix(task(TaskType.LOCAL_BASH, TaskStatus.FAILED)));
        assertEquals("(stopped)", BackgroundTasksRenderer.rowStatusSuffix(task(TaskType.LOCAL_BASH, TaskStatus.KILLED)));
    }

    @Test
    void rowStatusSuffix_agent_mirrorsBackgroundTaskLocalAgentBranch() {

        // suffix ", unread" only when completed && !notified.
        assertEquals("(running)", BackgroundTasksRenderer.rowStatusSuffix(task(TaskType.LOCAL_AGENT, TaskStatus.RUNNING)));
        assertEquals("(pending)", BackgroundTasksRenderer.rowStatusSuffix(task(TaskType.LOCAL_AGENT, TaskStatus.PENDING)));
        assertEquals("(failed)", BackgroundTasksRenderer.rowStatusSuffix(task(TaskType.LOCAL_AGENT, TaskStatus.FAILED)));
        assertEquals("(killed)", BackgroundTasksRenderer.rowStatusSuffix(task(TaskType.LOCAL_AGENT, TaskStatus.KILLED)));
        assertEquals("(done, unread)", BackgroundTasksRenderer.rowStatusSuffix(task(TaskType.LOCAL_AGENT, TaskStatus.COMPLETED)));
        assertEquals("(done)", BackgroundTasksRenderer.rowStatusSuffix(
            task(TaskType.LOCAL_AGENT, TaskStatus.COMPLETED).withNotified(true)));
    }

    // ── H-3: navigation order must match render order (shells, then agents) ──

    @Test
    void listOrder_shellsBeforeAgents_matchingRenderOrder_evenWhenAgentIsNewer() throws InterruptedException {

        // cursor visually downward". A globally-sorted mixed list would put the
        // newer running agent at index 0 while the renderer draws the Shells
        // group first — the pointer would jump upward on ↓.
        TaskRegistry registry = newRegistry();
        TaskState shell = runningShell(registry, "older-shell");
        Thread.sleep(5);
        TaskState agent = runningAgent(registry, "newer-agent");
        BackgroundTasksDialog d = new BackgroundTasksDialog(registry);

        d.show(() -> {});

        List<BackgroundTasksDialog.ListItem> items = d.items();
        assertEquals(2, items.size());
        assertEquals(shell.id(), items.getFirst().task().id(),
            "index 0 must be the first visually rendered row (Shells group)");
        assertEquals(agent.id(), items.get(1).task().id());
    }

    // ── H-2: list mode must track the live registry, not a show()-time snapshot ──

    @Test
    void tickRefresh_listMode_picksUpExternallyKilledAndNewTasks() {
        TaskRegistry registry = newRegistry();
        TaskState a = runningShell(registry, "cmd-a");
        runningShell(registry, "cmd-b");
        BackgroundTasksDialog d = new BackgroundTasksDialog(registry);
        d.show(() -> {});
        assertEquals(2, d.items().size());

        // Task killed + a new task started while the dialog sits open.
        registry.killShell(a.id());
        runningShell(registry, "cmd-c");

        d.tickRefresh();

        assertEquals(2, d.items().size(), "killed task drops out, new task appears");
        assertTrue(d.items().stream().noneMatch(i -> i.task().id().equals(a.id())));
        assertTrue(d.items().stream().anyMatch(i -> Strings.CS.equals(i.label(), "cmd-c")));
    }

    @Test
    void killSelected_usesLiveStatus_notTheStaleRowSnapshot() {
        // Regression: the row snapshot said PENDING but the task is really
        // RUNNING by the time x is pressed — the kill must go through.
        TaskRegistry registry = newRegistry();
        TaskState shell = pendingShell(registry, "was-pending");
        runningAgent(registry, "keep list at 2");
        BackgroundTasksDialog d = new BackgroundTasksDialog(registry);
        d.show(() -> {});
        assertEquals(shell.id(), d.items().getFirst().task().id(), "shell partition renders first");

        // Task starts running between the snapshot and the key press.
        registry.store().updateStatus(shell.id(), TaskStatus.RUNNING);
        registry.registerShell(new LocalShellTask(
            registry.get(shell.id()).get(), "was-pending", registry.store()));

        key(d, X);

        assertEquals(TaskStatus.KILLED, registry.get(shell.id()).get().status(),
            "x must judge kill eligibility on the live status");
    }



    @Test
    void tickRefresh_detailTaskNoLongerBackground_fallsBackToList() {
        TaskRegistry registry = newRegistry();
        runningShell(registry, "cmd-a");
        runningShell(registry, "cmd-b");
        BackgroundTasksDialog d = new BackgroundTasksDialog(registry);
        d.show(() -> {});
        key(d, ENTER); // detail on whichever row sorted first
        String viewed = d.detailTaskId();

        registry.killShell(viewed); // killed externally while viewing
        d.tickRefresh();

        assertTrue(d.isActive());
        assertEquals(BackgroundTasksDialog.Mode.LIST, d.mode(),
            "task left the background set -> effect falls back to the list");
    }

    @Test
    void tickRefresh_detailTaskGone_skippedOnMount_closesDialog() {
        TaskRegistry registry = newRegistry();
        TaskState only = runningShell(registry, "only-task");
        BackgroundTasksDialog d = new BackgroundTasksDialog(registry);
        AtomicInteger closedCount = new AtomicInteger();
        d.show(closedCount::incrementAndGet);
        assertTrue(d.skippedListOnMount());

        // Completes on its own while the detail view is open.
        registry.store().updateStatus(only.id(), TaskStatus.COMPLETED);
        d.tickRefresh();

        assertFalse(d.isActive(), "skipped-on-mount + task gone -> whole dialog closes");
        assertEquals(1, closedCount.get());
    }

    @Test
    void killDetail_nonRunningTask_isStrictNoOp_noKillNoNavigation() {

        // navigate anywhere either.
        TaskRegistry registry = newRegistry();
        TaskState pending = pendingShell(registry, "pending-task");
        runningAgent(registry, "keep list at 2");
        BackgroundTasksDialog d = new BackgroundTasksDialog(registry);
        d.show(() -> {});
        key(d, ENTER); // detail on the pending shell (renders first)
        assertEquals(pending.id(), d.detailTaskId());

        key(d, X);

        assertTrue(d.isActive());
        assertEquals(BackgroundTasksDialog.Mode.DETAIL, d.mode(), "no navigation on a no-op x");
        assertEquals(TaskStatus.PENDING, registry.get(pending.id()).get().status());
    }

    // ── H-4: DETAIL preferred size must fit the actual content ───────────

    @Test
    void detailRowCount_shell_growsWithOutputLines(@TempDir Path tmp) throws Exception {
        TaskRegistry registry = newRegistry();
        TaskState t = registry.store().create(TaskType.LOCAL_BASH, "noisy");
        registry.store().updateStatus(t.id(), TaskStatus.RUNNING);
        Path output = tmp.resolve(t.id() + ".output");
        Files.writeString(output, "l1\nl2\nl3\nl4\nl5\nl6\nl7\nl8\nl9\nl10\n");
        registry.registerShell(new LocalShellTask(t, "noisy", registry.store(), output));
        BackgroundTasksDialog d = new BackgroundTasksDialog(registry);
        d.show(() -> {}); // single task -> straight to detail
        awaitDetailRows(d, 20);

        // 9 fixed rows + (10 output lines + 1 note) = 20. A fixed-height 11
        // used to clip the footer as soon as output passed ~2 lines.
        assertEquals(20, d.detailRowCount());
        assertEquals(20, d.calculatePreferredSize().getRows());
    }

    private static void awaitDetailRows(BackgroundTasksDialog dialog, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (dialog.detailRowCount() != expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(expected, dialog.detailRowCount());
    }

    @Test
    void detailRowCount_shell_noOutput_usesPlaceholderRow() {
        TaskRegistry registry = newRegistry();
        TaskState t = runningShell(registry, "quiet");
        BackgroundTasksDialog d = new BackgroundTasksDialog(registry);
        d.show(() -> {});
        assertEquals(t.id(), d.detailTaskId());

        // 9 fixed rows + 1 "No output available" placeholder.
        assertEquals(10, d.detailRowCount());
    }

    @Test
    void detailRowCount_agent_runningWithProgressBlock() {
        TaskRegistry registry = newRegistry();
        runningAgent(registry, "thinking");
        BackgroundTasksDialog d = new BackgroundTasksDialog(registry);
        d.show(() -> {});

        // divider+title+subtitle+blank + progress(3) + prompt hdr+prompt+blank+footer.
        assertEquals(11, d.detailRowCount());
        assertEquals(11, d.calculatePreferredSize().getRows());
    }

    // ── M-4: scroll window over the list rows ─────────────────────────────

    @Test
    void clampScroll_keepsSelectionInsideWindow() {
        // In-window: offset unchanged.
        assertEquals(0, BackgroundTasksDialog.clampScroll(3, 20, 12, 0));
        // Below the window: scrolls down just enough.
        assertEquals(4, BackgroundTasksDialog.clampScroll(15, 20, 12, 0));
        // Above the window: jumps up to the selection.
        assertEquals(2, BackgroundTasksDialog.clampScroll(2, 20, 12, 5));
        // Never past the end.
        assertEquals(8, BackgroundTasksDialog.clampScroll(19, 20, 12, 30));
        // Small lists never scroll.
        assertEquals(0, BackgroundTasksDialog.clampScroll(1, 3, 12, 0));
    }

    // ── L-2: unhandled plain characters must not leak past the modal ─────

    @Test
    void unhandledPlainCharacter_isConsumed_notDeliveredToInputBehind() {
        BackgroundTasksDialog d = new BackgroundTasksDialog(newRegistry());
        d.show(() -> {});
        AtomicBoolean deliver = new AtomicBoolean(true);

        d.handleKey(new KeyStroke('q', false, false), deliver);

        assertFalse(deliver.get(),
            "typed characters would land in the suppressed prompt input and reappear on close");
        assertTrue(d.isActive());
    }

    @Test
    void ctrlChord_stillFallsThroughToGlobalBindings() {
        BackgroundTasksDialog d = new BackgroundTasksDialog(newRegistry());
        d.show(() -> {});
        AtomicBoolean deliver = new AtomicBoolean(true);

        d.handleKey(new KeyStroke('c', true, false), deliver);

        assertTrue(deliver.get(), "Ctrl+C must stay deliverable (global double-press exit)");
    }

    // ── PASTE must be swallowed, never leak to the input behind ──────────

    @Test
    void pasteKey_isSwallowed_doesNotDeliver() {
        BackgroundTasksDialog d = new BackgroundTasksDialog(newRegistry());
        d.show(() -> {});
        AtomicBoolean deliver = new AtomicBoolean(true);

        d.handleKey(new PasteKeyStroke("pasted text"), deliver);

        assertFalse(deliver.get(), "PASTE must be consumed — this overlay has no real Interactable to hold focus");
        assertTrue(d.isActive());
    }

    private static int indexOf(BackgroundTasksDialog d, String taskId) {
        List<BackgroundTasksDialog.ListItem> items = d.items();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).task().id().equals(taskId)) return i;
        }
        throw new AssertionError("task " + taskId + " not found in list items");
    }
}
