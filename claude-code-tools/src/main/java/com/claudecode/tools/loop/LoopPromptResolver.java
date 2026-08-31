package com.claudecode.tools.loop;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.state.CwdState;
import com.claudecode.core.git.GitUtils;
import com.claudecode.tools.bundled.BundledResourceCatalog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;


public final class LoopPromptResolver {

    public static final String AUTONOMOUS_SENTINEL = "<<autonomous-loop>>";
    public static final String AUTONOMOUS_DYNAMIC_SENTINEL = "<<autonomous-loop-dynamic>>";
    public static final String LOOP_FILE_SENTINEL = "<<loop.md>>";
    public static final String LOOP_FILE_DYNAMIC_SENTINEL = "<<loop.md-dynamic>>";
    private static final int MAX_LOOP_FILE_CHARS = 25_000;
    private static final String AUTONOMOUS_MARKER = "__autonomous_preamble__";

    private static final String AUTONOMOUS_PREAMBLE = """
        # Autonomous loop check

        You're being invoked on a timer while the user is away or occupied. The point is to keep work moving forward without the user driving every step — finishing things they started, maintaining PRs they're building, catching problems before they come back to find them. You're a steward, not an initiator. The user set you loose on their work, and the value you provide comes from reliably advancing things they've already set in motion, not from finding new things to do.

        The key tension to navigate: the user trusts you enough to run autonomously, but that trust is easily lost. Acting on what the conversation already established is safe and valuable. Inventing new work or making irreversible changes without clear authorization erodes trust fast. When you're unsure whether something falls into "continuing established work" or "inventing new work," lean toward the former only when the transcript provides clear evidence the user wanted it done. If you find yourself reaching for justifications about why a push is probably fine, that's a signal to wait.

        ## What to act on

        The current conversation is your highest-signal source — re-read the transcript above, since everything there is something the user was actively engaged with. The strongest signal is an in-progress PR you've been building together: review comments to address and resolve, failing CI checks to diagnose (and re-enqueue if they're flakes), merge conflicts to fix. The goal is to get the PR into a state where it's ready to merge pending only human review — the user shouldn't come back to find a PR blocked on things you could have handled. After that, look for unfinished implementation where the last exchange left something half-done, and explicit "I'll also..." or "next I'll..." commitments the conversation made and didn't honor. Weaker but still real: dangling questions you could now answer, verification steps that were skipped, edge cases that were mentioned but not handled, and natural continuations that don't require new decisions.

        If you find anything in this category, act on it — actually do the work, don't describe what could be done. Run the tests, don't say "you could run the tests." The whole point of autonomous operation is that work gets done while the user is away.

        When the conversation transcript has nothing left, the current branch's pull/merge request on the user's SCM is the next-best place to look. This is maintenance work — valuable, but lower priority than continuing the user's active work. Find the PR/MR for the current branch via the SCM's CLI, then check three things: CI status, unresolved review threads, and whether the branch has fallen behind the base. For failing CI, pull the failing job's logs and diagnose before acting — flaky-shaped failures (timeout, runner died, transient network) can be re-enqueued; real failures need a reproduction and a minimal fix. For unresolved review threads, fetch the comment, address the feedback, push, and resolve the thread via, for example, the GitHub GraphQL `resolveReviewThread` mutation (or the equivalent for whichever SCM the project uses). Before pushing anything, check whether someone else has pushed to the branch while you were working — if so, rebase (don't merge) to keep history clean.

        When CI is green, threads are clear, and there's idle time, sweeping the branch for issues is a good use of that time — bug-hunt or simplification passes catch problems before reviewers do, saving everyone a round-trip.

        If everything is genuinely quiet — no conversation work, no PR maintenance — say so in one sentence and stop. No summary of what you checked, no list of what you might do later. The user will see your message in the transcript when they come back; three consecutive "nothing to do" results means you should scale back to a quick CI check and stop, not narrate.

        ## Repeated invocations

        If you see earlier autonomous checks in this conversation, adjust your scope accordingly. If a previous check left a question the user hasn't answered, the cost of acting depends on reversibility: for reversible actions (local edits, running tests), make your best call and proceed; for irreversible ones (pushing, deleting, sending), keep waiting — the cost of acting wrongly on something irreversible is much higher than the cost of waiting one more cycle. If three or more consecutive checks have found nothing actionable, things are quiet — do one quick CI/threads check and stop in a single line. Repeated "nothing to do" messages clutter the transcript and waste the user's attention when they come back to review.

        Read and analyze freely — understanding the state of things has no blast radius. Make edits and run tests when you're confident they continue established work. Commit and push only when you're clearly continuing something the user authorized, or when the work pattern makes the intent obvious — like fixing CI on a PR you've been building together.
        """;

    private static final String DYNAMIC_MONITOR_REMINDER = """


        If a Monitor is armed (check TaskList), keep `delaySeconds` at 1200–1800s — the Monitor is the wake signal and this is only the fallback heartbeat. If you were woken by a `<task-notification>`, handle the event before rescheduling. To stop the loop, also TaskStop the monitor (use TaskList to find its task ID if no longer in context).""";

    private static final class GlobalHolder {
        private static final LoopPromptResolver INSTANCE = createGlobal();
    }

    private final BooleanSupplier promptEnabled;
    private final BooleanSupplier persistentEnabled;
    private final Path projectRoot;
    private final Path configHome;
    private boolean autonomousDelivered;
    private String lastLoopFileContent;

    public LoopPromptResolver(BooleanSupplier promptEnabled, BooleanSupplier persistentEnabled,
                       Path projectRoot, Path cwd) {
        this(promptEnabled, persistentEnabled, projectRoot, cwd, ClaudePaths.currentClaudeHome());
    }

    public LoopPromptResolver(BooleanSupplier promptEnabled, BooleanSupplier persistentEnabled,
                       Path projectRoot, Path cwd, Path configHome) {
        this.promptEnabled = promptEnabled;
        this.persistentEnabled = persistentEnabled;
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.configHome = configHome.toAbsolutePath().normalize();
    }

    public static LoopPromptResolver global() { return GlobalHolder.INSTANCE; }

    public static LoopPromptResolver passthrough() {
        Path cwd = Path.of(System.getProperty("user.dir"));
        return new LoopPromptResolver(() -> false, () -> false, cwd, cwd);
    }

    private static LoopPromptResolver createGlobal() {
        Path cwd = CwdState.getOriginalCwd();
        if (cwd == null) cwd = Path.of(System.getProperty("user.dir"));
        Path root = GitUtils.findCanonicalGitRoot(cwd);
        if (root == null) root = cwd;
        return new LoopPromptResolver(
            () -> LoopFeatureGate.system().defaultPromptEnabled(),
            () -> LoopFeatureGate.system().persistentEnabled(), root, cwd);
    }

    public synchronized String resolve(String prompt) {
        if (!promptEnabled.getAsBoolean()) return prompt;
        if (AUTONOMOUS_SENTINEL.equals(prompt) || AUTONOMOUS_DYNAMIC_SENTINEL.equals(prompt)) {
            return resolveAutonomous(AUTONOMOUS_DYNAMIC_SENTINEL.equals(prompt));
        }
        if (LOOP_FILE_SENTINEL.equals(prompt) || LOOP_FILE_DYNAMIC_SENTINEL.equals(prompt)) {
            return resolveLoopFile(LOOP_FILE_DYNAMIC_SENTINEL.equals(prompt));
        }
        return prompt;
    }

    public synchronized void resetDeliveredState() {
        autonomousDelivered = false;
        lastLoopFileContent = null;
    }

    public String autonomousPreamble() {
        return persistentEnabled.getAsBoolean()
            ? readBundledResource("autonomous-loop-persistent.md")
            : AUTONOMOUS_PREAMBLE;
    }

    private static String readBundledResource(String name) {
        return BundledResourceCatalog.current().readText("skills/" + name);
    }

    public LoopFile readLoopFile() {
        for (Path path : new Path[] {
                projectRoot.resolve(".claude/loop.md"), configHome.resolve("loop.md")}) {
            if (!Files.isRegularFile(path)) continue;
            try {
                String content = Files.readString(path).trim();
                if (content.isEmpty()) continue;
                return new LoopFile(path, truncate(content));
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read loop file: " + path, e);
            }
        }
        return null;
    }

    private String resolveAutonomous(boolean dynamic) {
        String tick = dynamic ? autonomousDynamicTick() : autonomousCronTick();
        if (autonomousDelivered || lastLoopFileContent != null) return tick;
        autonomousDelivered = true;
        return autonomousPreamble() + "\n" + tick;
    }

    private String resolveLoopFile(boolean dynamic) {
        LoopFile file = readLoopFile();
        if (file != null) {
            String tick = dynamic ? loopFileDynamicTick() : loopFileCronTick();
            if (file.content().equals(lastLoopFileContent)) return tick;
            lastLoopFileContent = file.content();
            return "# /loop tick — tasks from " + file.path() + "\n\n"
                + "The user configured a loop-tasks file. Work through the tasks defined below; "
                + "these are the instructions for this tick and every subsequent tick (the "
                + "reminder on later fires refers back to this message).\n"
                + file.content() + "\n" + tick;
        }

        String tick = dynamic ? loopFileAbsentDynamicTick() : autonomousCronTick();
        if (AUTONOMOUS_MARKER.equals(lastLoopFileContent) || autonomousDelivered) return tick;
        lastLoopFileContent = AUTONOMOUS_MARKER;
        autonomousDelivered = true;
        return autonomousPreamble() + "\n" + tick;
    }

    private static String autonomousCronTick() {
        return """
            # Autonomous loop tick

            Run the autonomous check using the loop instructions established earlier in this conversation. \
            If you cannot find them, treat this as a no-op tick. The recurring cron will fire the next tick \
            automatically — do not call ScheduleWakeup from this tick.""";
    }

    private static String autonomousDynamicTick() {
        return "# Autonomous loop tick (dynamic pacing)\n\n"
            + "Run the autonomous check using the loop instructions established earlier in this conversation. "
            + "If you cannot find them, treat this as a no-op tick.\n\n"
            + "You scheduled this tick via the ScheduleWakeup tool (not a recurring cron). To keep the loop "
            + "alive, call ScheduleWakeup again at the end of this turn with `prompt` set to the literal "
            + "sentinel `<<autonomous-loop-dynamic>>` — otherwise the loop ends after this tick."
            + DYNAMIC_MONITOR_REMINDER;
    }

    private static String loopFileCronTick() {
        return """
            # /loop tick — loop.md tasks

            Work the tasks from the loop.md contents established earlier in this conversation. If you \
            cannot find them, treat this as a no-op tick. The recurring cron will fire the next tick \
            automatically — do not call ScheduleWakeup from this tick.""";
    }

    private static String loopFileDynamicTick() {
        return "# /loop tick — loop.md tasks (dynamic pacing)\n\n"
            + "Work the tasks from the loop.md contents established earlier in this conversation. If you "
            + "cannot find them, treat this as a no-op tick.\n\n"
            + "You scheduled this tick via the ScheduleWakeup tool (not a recurring cron). To keep the loop "
            + "alive, call ScheduleWakeup again at the end of this turn with `prompt` set to the literal "
            + "sentinel `<<loop.md-dynamic>>` — otherwise the loop ends after this tick."
            + DYNAMIC_MONITOR_REMINDER;
    }

    private static String loopFileAbsentDynamicTick() {
        return "# /loop tick — loop.md absent (dynamic pacing)\n\n"
            + "loop.md is not currently present. Run the autonomous check using the loop instructions "
            + "established earlier in this conversation.\n\n"
            + "You scheduled this tick via the ScheduleWakeup tool (not a recurring cron). To keep the loop "
            + "alive — and to pick up loop.md if it is recreated — call ScheduleWakeup again at the end of "
            + "this turn with `prompt` set to the literal sentinel `<<loop.md-dynamic>>` — otherwise the loop "
            + "ends after this tick." + DYNAMIC_MONITOR_REMINDER;
    }

    private static String truncate(String content) {
        if (content.length() <= MAX_LOOP_FILE_CHARS) return content;
        int newline = content.lastIndexOf('\n', MAX_LOOP_FILE_CHARS);
        int end = newline > 0 ? newline : MAX_LOOP_FILE_CHARS;
        return content.substring(0, end)
            + "\n> WARNING: loop.md was truncated to 25000 bytes. Keep the task list concise.";
    }

    public record LoopFile(Path path, String content) { }
}
