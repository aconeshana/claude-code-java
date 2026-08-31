package com.claudecode.ui.lanterna.transcript;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks which (if any) agent transcript the REPL is currently viewing, plus the in-progress
 * selection index used while stepping through teammates.
 */
public final class ViewedTeammateHolder {


    public enum Mode {
        /** No teammate interaction active. */
        NONE,
        /** Stepping through leader/teammates (Shift+↑/↓), awaiting f/Enter/k/Esc. */
        SELECTING,
        /** A teammate's transcript is being viewed (Esc interrupts its turn). */
        VIEWING
    }

    public enum ViewKind {
        NONE,
        TEAMMATE,
        LOCAL_AGENT
    }

    private static final ViewedTeammateHolder INSTANCE = new ViewedTeammateHolder();

    public static ViewedTeammateHolder instance() {
        return INSTANCE;
    }

    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.NONE);
// -1 = leader, 0..n-1 = teammates.
    private final AtomicInteger selectedIndex = new AtomicInteger(-1);
    private final AtomicReference<String> viewingTaskId = new AtomicReference<>();
    private final AtomicReference<ViewKind> viewKind = new AtomicReference<>(ViewKind.NONE);

    private ViewedTeammateHolder() {}

    public Mode mode() {
        return mode.get();
    }

    public boolean isActive() {
        return mode.get() != Mode.NONE;
    }

    public boolean isSelecting() {
        return mode.get() == Mode.SELECTING;
    }

    public boolean isViewing() {
        return mode.get() == Mode.VIEWING;
    }

    public boolean isViewingLocalAgent() {
        return isViewing() && viewKind.get() == ViewKind.LOCAL_AGENT;
    }

    /** A teammate transcript remains foregrounded while its tree row is being selected. */
    public boolean hasForegroundedTeammate() {
        return viewingTaskId.get() != null && viewKind.get() == ViewKind.TEAMMATE;
    }

    /** Current selection index (−1 = leader). Only meaningful while active. */
    public int selectedIndex() {
        return selectedIndex.get();
    }

    /** Task id of the teammate currently being viewed, or {@code null}. */
    public String viewingTaskId() {
        return viewingTaskId.get();
    }

    /** Enters SELECTING mode parked on {@code index} (use −1 for leader). */
    public void enterSelecting(int index) {
        selectedIndex.set(index);

        mode.set(Mode.SELECTING);
    }

    /** Leaves row selection without changing the foreground transcript. */
    public void leaveSelecting() {
        selectedIndex.set(-1);
        mode.set(Mode.NONE);
    }

    /** Clamps a retained selection after the running teammate set changes. */
    public void updateSelectedIndex(int index) {
        selectedIndex.set(index);
    }

    /** Enters VIEWING mode for {@code taskId} at selection {@code index}. */
    public void enterViewing(String taskId, int index) {
        selectedIndex.set(index);
        viewingTaskId.set(taskId);
        viewKind.set(ViewKind.TEAMMATE);
        mode.set(Mode.VIEWING);
    }

    /** Views a normal local-agent sidechain without enabling teammate-only keys. */
    public void enterLocalAgentViewing(String taskId) {
        selectedIndex.set(-1);
        viewingTaskId.set(taskId);
        viewKind.set(ViewKind.LOCAL_AGENT);
        mode.set(Mode.VIEWING);
    }

    /** Resets to NONE (back to the leader's own transcript). */
    public void exit() {
        mode.set(Mode.NONE);
        selectedIndex.set(-1);
        viewingTaskId.set(null);
        viewKind.set(ViewKind.NONE);
    }

    /** Test hook — clears all state. */
    void resetForTest() {
        exit();
    }
}
