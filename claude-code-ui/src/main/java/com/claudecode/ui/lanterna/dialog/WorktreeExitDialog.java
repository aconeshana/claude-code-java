package com.claudecode.ui.lanterna.dialog;

import com.claudecode.tools.worktree.WorktreeService;
import com.claudecode.tools.worktree.WorktreeSession;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.ui.lanterna.input.ContextKeybindingDispatcher;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Interactable;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import org.apache.commons.lang3.StringUtils;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * Inline confirmation dialog shown at exit-time when the REPL is running inside a Claude-managed
 * git worktree.
 */
public final class WorktreeExitDialog extends Panel implements InlineOverlay {

    private static final int LEFT_PAD = 2;

    /** Callback payload: transcript line + whether to proceed with shutdown. */
    public record Result(String message, boolean proceedExit) {}

    private enum State { LOADING, ASKING, KEEPING, REMOVING, DONE }

    private enum Option {
        KEEP, REMOVE,
        // tmux-aware variants
        KEEP_WITH_TMUX, KEEP_KILL_TMUX, REMOVE_WITH_TMUX
    }

    private final Body body;

    private volatile boolean active;
    private volatile State state;
    private volatile WorktreeSession session;
    private volatile List<String> changes = List.of();
    private volatile int commitCount = 0;
    private volatile String sessionTitle;
    private volatile Option focused;

    private Consumer<Result> onResult;
    private volatile Supplier<String> sessionTitleSource = () -> null;
    private final ContextKeybindingDispatcher keybindings =
        new ContextKeybindingDispatcher();

    public WorktreeExitDialog() {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        this.body = new Body();
        this.body.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(body);
    }

    public void setKeybindingsStore(UserKeybindingsStore store) {
        keybindings.setStore(store);
    }

    /**
     * Source of the session's {@code /rename} title. A named session is one the user means to
     * come back to, so a clean worktree is offered for keeping instead of being removed silently.
     */
    public void setSessionTitleSource(Supplier<String> source) {
        sessionTitleSource = source != null ? source : () -> null;
    }

    /**
     * Activate and start scanning. Must run on the GUI thread; the git probe
     * itself is scheduled to a virtual thread to avoid stalling the UI.
     */
    public synchronized void show(WorktreeSession session, Consumer<Result> onResult) {
        if (session == null) {

            if (onResult != null) onResult.accept(new Result("No active worktree session found", true));
            return;
        }
        this.session = session;
        this.onResult = onResult;
        this.state = State.LOADING;
        this.changes = List.of();
        this.commitCount = 0;
        this.sessionTitle = null;
        this.focused = session.hasTmuxSession() ? Option.KEEP_WITH_TMUX : Option.KEEP;
        this.active = true;
        invalidate();


        Thread.ofVirtual().name("worktree-exit-probe").start(this::loadChanges);
    }

    @Override public boolean isActive() { return active; }

    /**
     * Background probe: {@code git status --porcelain} + {@code git rev-list} to size the changeset.
     */
    private void loadChanges() {
        try {
            this.sessionTitle = StringUtils.trimToNull(readSessionTitle());
            String cwd = session.worktreePath();
            List<String> lines = WorktreeService.gitStatusPorcelain(cwd);
            int count = WorktreeService.commitCountAhead(cwd, session.originalHeadCommit());
            this.changes = lines;
            this.commitCount = count;

            if (lines.isEmpty() && count == 0 && sessionTitle == null) {
                // Fast path: nothing to lose, remove silently.
                this.state = State.REMOVING;
                invalidate();
                String msg = WorktreeService.cleanupWorktree();
                resolve(new Result(msg + " (no changes)", true));
                return;
            }
            this.state = State.ASKING;
            invalidate();
        } catch (Throwable _) {
            // Probe failure → fall back to asking with empty stats.
            this.state = State.ASKING;
            invalidate();
        }
    }

    private String readSessionTitle() {
        try {
            return sessionTitleSource.get();
        } catch (RuntimeException _) {
            return null;
        }
    }

    @Override
    public synchronized void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!active) return;
        // Loading / in-flight actions swallow keys but don't act on them; nothing else
        // in the REPL should react to those keystrokes either.
        if (state == State.LOADING || state == State.KEEPING || state == State.REMOVING) {
            deliver.set(false);
            return;
        }
        if (state != State.ASKING) return;
        KeyType t = key.getKeyType();
        List<Option> options = optionsForState();
        int idx = options.indexOf(focused);
        if (idx < 0) idx = 0;
        ContextKeybindingDispatcher.Result resolved = keybindings.resolve("Select", key);
        if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) {
            deliver.set(false);
            return;
        }
        if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String value)) {
            boolean handled = switch (value) {
                case "select:previous" -> {
                    focused = options.get((idx - 1 + options.size()) % options.size());
                    invalidate();
                    yield true;
                }
                case "select:next" -> {
                    focused = options.get((idx + 1) % options.size());
                    invalidate();
                    yield true;
                }
                case "select:accept" -> { performSelection(focused); yield true; }
                case "select:cancel" -> {
                    resolve(new Result("Exit cancelled", false));
                    yield true;
                }
                default -> false;
            };
            if (handled) {
                deliver.set(false);
                return;
            }
        }
        if (t == KeyType.CHARACTER && !key.isCtrlDown() && !key.isAltDown()) {
            int pick = Character.digit(key.getCharacter(), 10) - 1;
            if (pick >= 0 && pick < options.size()) {
                deliver.set(false);
                focused = options.get(pick);
                performSelection(focused);
                return;
            }
        }
        if (t == KeyType.ARROW_UP) {
            focused = options.get((idx - 1 + options.size()) % options.size());
            invalidate();
            deliver.set(false);
            return;
        }
        if (t == KeyType.ARROW_DOWN) {
            focused = options.get((idx + 1) % options.size());
            invalidate();
            deliver.set(false);
            return;
        }
        if (t == KeyType.ENTER) {
            deliver.set(false);
            performSelection(focused);
            return;
        }
        if (t == KeyType.ESCAPE) {

            resolve(new Result("Exit cancelled", false));
            deliver.set(false);
        }
    }

    private List<Option> optionsForState() {
        return session.hasTmuxSession()
            ? List.of(Option.KEEP_WITH_TMUX, Option.KEEP_KILL_TMUX, Option.REMOVE_WITH_TMUX)
            : List.of(Option.KEEP, Option.REMOVE);
    }

    private void performSelection(Option opt) {
        boolean hasTmux = session.hasTmuxSession();
        switch (opt) {
            case KEEP, KEEP_WITH_TMUX -> {
                state = State.KEEPING;
                invalidate();
                Thread.ofVirtual().name("worktree-keep").start(() -> {
                    String msg = WorktreeService.keepWorktree();
                    if (hasTmux) {
                        msg = msg + ". Reattach to tmux session with: tmux attach -t "
                            + session.tmuxSessionName();
                    }
                    resolve(new Result(msg, true));
                });
            }
            case KEEP_KILL_TMUX -> {
                state = State.KEEPING;
                invalidate();
                final String tmuxName = session.tmuxSessionName();
                Thread.ofVirtual().name("worktree-keep-kill-tmux").start(() -> {
                    WorktreeService.killTmuxSession(tmuxName);
                    String msg = WorktreeService.keepWorktree();
                    resolve(new Result(msg + ". Tmux session terminated.", true));
                });
            }
            case REMOVE, REMOVE_WITH_TMUX -> {
                state = State.REMOVING;
                invalidate();
                final String tmuxName = session.tmuxSessionName();
                final int commits = commitCount;
                final int changed = changes.size();
                final String branch = session.worktreeBranch();
                Thread.ofVirtual().name("worktree-remove").start(() -> {
                    if (StringUtils.isNotBlank(tmuxName)) {
                        WorktreeService.killTmuxSession(tmuxName);
                    }
                    String msg = WorktreeService.cleanupWorktree();
                    String tmuxNote = hasTmux ? " Tmux session terminated." : "";
                    String detail;
                    if (commits > 0 && changed > 0) {
                        detail = String.format(
                            "%s %d %s and uncommitted changes were discarded.%s",
                            msg, commits, commits == 1 ? "commit" : "commits", tmuxNote);
                    } else if (commits > 0) {
                        detail = String.format(
                            "%s %d %s on %s %s discarded.%s",
                            msg, commits, commits == 1 ? "commit" : "commits",
                            branch != null ? branch : "(unknown)",
                            commits == 1 ? "was" : "were",
                            tmuxNote);
                    } else if (changed > 0) {
                        detail = msg + " Uncommitted changes were discarded." + tmuxNote;
                    } else {
                        detail = msg + tmuxNote;
                    }
                    resolve(new Result(detail, true));
                });
            }
        }
    }

    private synchronized void resolve(Result r) {
        if (!active) return;
        Consumer<Result> cb = onResult;
        hide();
        if (cb != null) cb.accept(r);
    }

    private synchronized void hide() {
        active = false;
        state = State.DONE;
        session = null;
        onResult = null;
        invalidate();
    }

    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        if (!active) return new TerminalSize(0, 0);
        // Header + subtitle (may wrap) + options + footer.
        int rows = state == State.LOADING || state == State.KEEPING || state == State.REMOVING
            ? 3
            : Math.max(8, 4 + optionsForState().size());
        // Do NOT call super.calculatePreferredSize(): outer Panel would ask
        // Body's size, Body's renderer calls back here → infinite recursion.
        return new TerminalSize(72, rows);
    }

    @Override public Interactable nextFocus(Interactable fromThis) { return active ? super.nextFocus(fromThis) : null; }
    @Override public Interactable previousFocus(Interactable fromThis) { return active ? super.previousFocus(fromThis) : null; }

    // ── Rendering ────────────────────────────────────────────────────────

    private final class Body extends AbstractComponent<Body> {
        @Override protected ComponentRenderer<Body> createDefaultRenderer() {
            return new BodyRenderer();
        }
    }

    private final class BodyRenderer implements ComponentRenderer<Body> {
        @Override public TerminalSize getPreferredSize(Body c) {
            return active ? calculatePreferredSize() : new TerminalSize(0, 0);
        }

        @Override public void drawComponent(TextGUIGraphics g, Body c) {
            if (!active) return;
            g.fill(' ');
            g.setForegroundColor(LanternaTheme.divider());
            g.putString(0, 0, "─".repeat(Math.max(0, g.getSize().getColumns())));

            switch (state) {
                case LOADING  -> drawSpinner(g, "Checking worktree changes…");
                case KEEPING  -> drawSpinner(g, "Keeping worktree…");
                case REMOVING -> drawSpinner(g, "Removing worktree…");
                case ASKING   -> drawAsking(g);
                case DONE     -> {}
            }
        }

        private void drawSpinner(TextGUIGraphics g, String label) {
            g.setForegroundColor(LanternaTheme.statusCost());
            g.enableModifiers(SGR.BOLD);
            g.putString(LEFT_PAD, 1, "◐ " + label);
            g.disableModifiers(SGR.BOLD);
        }

        private void drawAsking(TextGUIGraphics g) {
            g.setForegroundColor(LanternaTheme.statusCost());
            g.enableModifiers(SGR.BOLD);
            g.putString(LEFT_PAD, 1, "Exiting worktree session");
            g.disableModifiers(SGR.BOLD);

            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, 2, subtitleText());

            List<Option> options = optionsForState();
            int labelWidth = 0;
            for (int i = 0; i < options.size(); i++) {
                labelWidth = Math.max(labelWidth, ((i + 1) + ". " + labelFor(options.get(i))).length());
            }
            int y = 4;
            for (int i = 0; i < options.size(); i++) {
                drawOption(g, y++, i, options.get(i), labelWidth);
            }
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, y + 1,
                "↑/↓ to select · Enter to confirm · Esc to cancel");
        }

        private String subtitleText() {
            String branch = session.worktreeBranch() != null ? session.worktreeBranch() : "(unknown)";
            boolean uncommitted = !changes.isEmpty();
            boolean commits = commitCount > 0;
            if (uncommitted && commits) {
                return String.format(
                    "You have %d uncommitted %s and %d %s on %s. All will be lost if you remove.",
                    changes.size(), changes.size() == 1 ? "file" : "files",
                    commitCount, commitCount == 1 ? "commit" : "commits", branch);
            }
            if (uncommitted) {
                return String.format(
                    "You have %d uncommitted %s. These will be lost if you remove the worktree.",
                    changes.size(), changes.size() == 1 ? "file" : "files");
            }
            if (commits) {
                return String.format(
                    "You have %d %s on %s. The branch will be deleted if you remove the worktree.",
                    commitCount, commitCount == 1 ? "commit" : "commits", branch);
            }
            if (sessionTitle != null) {
                return "This session was named \"" + sessionTitle
                    + "\". Keep the worktree to resume it later, or remove it to clean up.";
            }
            return "You are working in a worktree. Keep it to continue working there, or remove it to clean up.";
        }

        private void drawOption(TextGUIGraphics g, int y, int index, Option opt, int labelWidth) {
            boolean isFocused = focused == opt;
            String label = StringUtils.rightPad((index + 1) + ". " + labelFor(opt), labelWidth);
            if (isFocused) {
                g.setForegroundColor(LanternaTheme.statusCost());
                g.enableModifiers(SGR.BOLD);
                g.putString(LEFT_PAD, y, "❯ " + label);
                g.disableModifiers(SGR.BOLD);
            } else {
                g.setForegroundColor(LanternaTheme.welcomeDim());
                g.putString(LEFT_PAD, y, "  " + label);
            }
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD + 2 + labelWidth + 2, y, descriptionFor(opt));
        }

        private String labelFor(Option opt) {
            return switch (opt) {
                case KEEP           -> "Keep worktree";
                case REMOVE         -> "Remove worktree";
                case KEEP_WITH_TMUX -> "Keep worktree and tmux session";
                case KEEP_KILL_TMUX -> "Keep worktree, end tmux session";
                case REMOVE_WITH_TMUX -> "Remove worktree and tmux session";
            };
        }

        private String descriptionFor(Option opt) {
            return switch (opt) {
                case KEEP -> "Stays at " + session.worktreePath();
                case KEEP_WITH_TMUX -> "Stays at " + session.worktreePath()
                    + ". Reattach with: tmux attach -t " + session.tmuxSessionName();
                case KEEP_KILL_TMUX -> "Keeps worktree at " + session.worktreePath()
                    + ", terminates tmux session.";
                case REMOVE, REMOVE_WITH_TMUX -> changes.isEmpty() && commitCount == 0
                    ? "Clean up the worktree directory."
                    : "All changes and commits will be lost.";
            };
        }
    }
}
