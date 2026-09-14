package com.claudecode.commands.impl.review;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.prompt.PromptInvocation;
import com.claudecode.core.effort.EffortHelpers;
import com.claudecode.core.message.TextBlock;

import org.apache.commons.lang3.Strings;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code /code-review} (aliases {@code review}) — multi-angle review of the
 * current diff or an explicit PR/branch/path target, at a chosen effort level.
 *
 * <p>Pure prompt-type command: parses the effort level, flags, and target,
 * then builds the staged review prompt and injects it into the main loop.
 * No orchestration happens in the command itself — the main agent runs the
 * phases (spawning finder/verifier subagents through its own Agent tool when
 * available, or the single-pass fallback the prompt carries).
 *
 * <p>Stage structure per level (ported from the 236 bundle): low is a two-turn
 * single-pass read; medium/high run 8 angles (3 correctness + 5 cleanup) with
 * a 1-vote verify (high's is recall-biased); xhigh/max run 10 angles
 * (5 correctness + 5 cleanup), a plain 1-vote verify plus a recall-mode
 * clause, and a Phase 3 gap sweep.
 */
@SlashCommand(
    name = "code-review",
    description = "Review the current diff or a PR for bugs and cleanups",
    aliases = {"review"}
)
public class CodeReviewCommand implements AnnotatedCommand {

    /** Effort vocabulary, weakest → strongest (upstream 236's KF list). */
    static final List<String> EFFORT_LEVELS = List.of(
        "low", "medium", "high", "xhigh", "max");

    private static final Set<String> FLAGS = Set.of("comment", "fix", "post", "no-post");

    @Override public String argumentHint() {
        return "[low|medium|high|xhigh|max] [--fix] [--comment] [<pr#>|<branch>|<path>]";
    }

    @Override
    public boolean supportsNonInteractive() {
        return true;
    }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        ParsedArgs parsed = parseArgs(args);
        String model = context.session().model();
        // No explicit level → the model's catalog default (236's PS(t) session
        // default; e.g. sonnet-4-6 → high). Levels the model cannot run clamp
        // down to high (236's XDe/n4e gates; xhigh is sonnet-4-6's only clamp).
        String level = resolveLevel(parsed.explicitLevel(), model);
        String prompt = buildPrompt(level, parsed.target(), parsed.fix(), parsed.comment());
        return CommandResult.forPrompt(PromptInvocation.builder(List.of(new TextBlock(prompt)))
            .progressMessage(progressFor(parsed.target()))
            // 236's getEffort threads the resolved level (p3c) onto the query's
            // output_config.effort — for the default model family it is the
            // level verbatim, after the capability clamp.
            .effort(level)
            .source("builtin")
            .userFacingName(name())
            .contentLength(0)
            .build());
    }

    /**
     * Level resolution, ported from 236's {@code ozg}/{@code hNd}: an explicit
     * level survives when the model supports it, otherwise xhigh/max clamp to
     * high; no level falls back to the model's catalog default ("medium" when
     * unknown).
     */
    static String resolveLevel(String explicitLevel, String model) {
        if (explicitLevel != null) {
            var capabilities = EffortHelpers.capabilitiesForModel(model);
            String level = explicitLevel;
            if (capabilities.known()) {
                if (!capabilities.supports("xhigh") && Strings.CS.equals("xhigh", level)) level = "high";
                if (!capabilities.supports("max") && Strings.CS.equals("max", level)) level = "high";
            }
            return level;
        }
        String defaultLevel = EffortHelpers.getDefaultEffortForModel(model);
        return defaultLevel != null ? defaultLevel : "medium";
    }

    /**
     * Parsed command arguments: the leading effort token (optional), the
     * boolean flags, and the remaining tokens joined as the review target
     * (a PR number, branch name, or file path).
     */
    record ParsedArgs(String explicitLevel, String target, boolean fix, boolean comment) { }

    /**
     * Argument grammar (upstream 236's {@code z_s}/{@code R3g}): flags may
     * appear anywhere and are stripped; the first non-flag token is the
     * effort level when it names one (full name, {@code med}, or a 3-letter
     * prefix), otherwise it starts the target; the rest join into the target.
     */
    static ParsedArgs parseArgs(String args) {
        String trimmed = args == null ? "" : args.trim();
        boolean fix = false;
        boolean comment = false;
        StringBuilder rest = new StringBuilder();
        for (String token : trimmed.split("\\s+")) {
            if (token.isEmpty()) continue;
            String flag = token.toLowerCase(Locale.ROOT);
            if (Strings.CS.startsWith(token, "--") && FLAGS.contains(flag.substring(2))) {
                if (Strings.CS.equals("--fix", flag)) fix = true;
                if (Strings.CS.equals("--comment", flag)) comment = true;
                continue;
            }
            if (!rest.isEmpty()) rest.append(' ');
            rest.append(token);
        }
        String remaining = rest.toString();
        String explicitLevel = null;
        String target = "";
        if (!remaining.isEmpty()) {
            String first = remaining.split("\\s+", 2)[0];
            String level = effortOf(first);
            if (level != null) {
                explicitLevel = level;
                int after = remaining.indexOf(' ');
                target = after < 0 ? "" : remaining.substring(after + 1).trim();
            } else {
                target = remaining;
            }
        }
        return new ParsedArgs(explicitLevel, target, fix, comment);
    }

    /** Resolves one token to an effort level, or null (upstream's {@code RZt} + {@code DWE} prefix regex). */
    static String effortOf(String token) {
        String value = token.trim().toLowerCase(Locale.ROOT);
        if (Strings.CS.equals("med", value)) return "medium";
        if (EFFORT_LEVELS.contains(value)) return value;
        if (value.length() >= 3) {
            for (String level : EFFORT_LEVELS) {
                if (Strings.CS.startsWith(level, value.substring(0, 3))) return level;
            }
        }
        return null;
    }

    private static String progressFor(String target) {
        return target.isEmpty()
            ? "reviewing your changes"
            : "reviewing pull request " + target;
    }

    // -------------------------------------------------------------------------
    // Prompt assembly — verbatim ports of the 236 bundle's template constants,
    // byte-checked against the frozen 2.1.236 wire baseline
    // (wire-tests baselines/2.1.236/S236-P1-CODE-REVIEW). Blank-line placement
    // is part of the contract: every section head is separated by one blank
    // line; the tag line is followed by one; RWE/IWE open with their own blank
    // line after the output contract's trailing newline.
    // -------------------------------------------------------------------------

    static String buildPrompt(String level, String target, boolean fix, boolean comment) {
        StringBuilder prompt = new StringBuilder();
        if (!target.isEmpty()) {
            // P — the target line rides before the level template (FWE's ${P}).
            prompt.append("Review target: `").append(target).append("`\n\n");
        }
        if (Strings.CS.equals("low", level)) {
            // N3g is self-contained: tag, two turns, and its own one-line output
            // contract — no Phase 0/1/2/3 and no JSON output block.
            prompt.append(leadIn(level));
        } else {
            prompt.append(leadIn(level));
            prompt.append(GATHER_DIFF).append("\n\n");
            prompt.append(angles(level));
            if (!Strings.CS.equals("xhigh", level) && !Strings.CS.equals("max", level)) {
                // GEr's ordering-rule tail + the "Pass every candidate" para only
                // ride the medium/high templates ($3g/B3g), not U3g (xhigh/max).
                prompt.append(PASS_EVERY_CANDIDATE);
            }
            prompt.append(verify(level));
            prompt.append(sweep(level));
            prompt.append(output(level));
        }
        if (comment) {
            // RWE opens with a leading newline of its own (bundle constant).
            prompt.append('\n').append(POST_COMMENT);
        }
        if (fix) {
            // IWE likewise (bundle constant).
            prompt.append('\n').append(APPLY_FIXES);
        }
        return prompt.toString();
    }

    private static String leadIn(String level) {
        return switch (level) {
            case "low" -> "`low effort → 1 diff pass → no verify → ≤4 findings`\n\n"
                + "## Turn 1 — read\n\n"
                + LOW_TURNS;
            case "medium" -> """
                `medium effort → 3+5 angles × 6 candidates → 1-vote verify → ≤8 findings`
                
                You are reviewing for **precision** at medium effort: every finding you surface
                should be one a maintainer would act on.
                
                """;
            case "high" -> """
                `high effort → 3+5 angles × 6 candidates → 1-vote verify (recall-biased) → ≤10 findings`
                
                You are reviewing for **recall** at high effort: catch every real bug a careful
                reviewer would catch in one sitting. At this level, catching real bugs matters
                more than avoiding false positives. Err on the side of surfacing.
                
                """;
            default -> "`" + level + " effort → 5+5 angles × 8 candidates → 1-vote verify → sweep → ≤15 findings`\n\n"
                + "You are reviewing for **recall** at " + (Strings.CS.equals("max", level) ? "maximum" : "extra-high")
                + " effort: catch every real bug. At\n"
                + "this level, catching real bugs matters more than avoiding false positives — a\n"
                + "missed bug ships. Err on the side of surfacing.\n\n";
        };
    }

    /** Phase 0 — verbatim from the 236 bundle's {@code M8e} constant. */
    private static final String GATHER_DIFF = """
        ## Phase 0 — Gather the diff

        Run `git diff @{upstream}...HEAD` (or `git diff main...HEAD` / `git diff HEAD~1`
        if there's no upstream) to get the unified diff under review. If there are
        uncommitted changes, or the range diff is empty, also run `git diff HEAD` and
        include the working-tree changes in scope — the review often runs before the
        commit. If a PR number, branch name, or file path was passed as an argument,
        review that target instead. Treat this diff as the review scope.""";

    /**
     * Phase 1 — the 236 bundle's two angle tables: {@code j_s} (A, B, C +
     * cleanup, used by medium/high) and {@code I3g} (A–E + cleanup, used by
     * xhigh/max). The Phase-1 head clause differs per table (checked against
     * the frozen wire): medium/high carry the {@code file}/{@code line}/
     * {@code summary} field clause, xhigh/max the "Do NOT let one angle's…"
     * clause. Low has no Phase 1 (its two-turn body is {@link #LOW_TURNS}).
     */
    private static String angles(String level) {
        boolean extended = Strings.CS.equals("xhigh", level) || Strings.CS.equals("max", level);
        String title = extended
            ? """
            ## Phase 1 — Find candidates (5 correctness angles + 3 cleanup angles \
            + 1 altitude angle + 1 conventions angle, up to 8 each)
            
            """
            : """
                ## Phase 1 — Find candidates (3 correctness angles + 3 cleanup angles \
                + 1 altitude angle + 1 conventions angle, up to 6 each)
                
                """;
        String head = extended
            ? """
            Run **10 independent finder angles** via the Agent tool. Each
            surfaces **up to 8 candidate findings**. Do NOT let one angle's conclusions
            suppress another's — if two angles flag the same line for different reasons,
            record both. If the Agent tool is not available in your current tool set, do not error — perform each angle (and each verification) yourself, sequentially, in this context.
            
            """
            : """
                Run **8 independent finder angles** via the Agent tool. Each
                surfaces **up to 6 candidate findings** with `file`, `line`, a one-line
                `summary`, and a concrete `failure_scenario`. If the Agent tool is not available in your current tool set, do not error — perform each angle (and each verification) yourself, sequentially, in this context.
                
                """;
        return title + head + ANGLES_A_TO_C + (extended ? ANGLES_D_E : "") + CLEANUP_ANGLES;
    }

    /** The two-turn single-pass body for the low template (236's {@code N3g} tail). */
    private static final String LOW_TURNS = """
        One tool call: read the unified diff (`git diff @{upstream}...HEAD; git diff HEAD`
        to cover both committed and uncommitted changes, or `git diff main...HEAD` /
        the target passed as an argument). Skip test/fixture
        hunks (`test/`, `spec/`, `__tests__/`, `*_test.*`, `*.test.*`,
        `fixtures/`, `testdata/`) — test-file changes are not reviewed at this level.
        No subagents, no full-file reads.

        ## Turn 2 — findings

        Flag runtime-correctness bugs visible from the hunk alone: inverted/wrong
        condition, off-by-one, null/undefined deref where adjacent lines show the value
        can be absent, removed guard, falsy-zero check, missing `await`,
        wrong-variable copy-paste, error swallowed in a catch that should propagate.
        Also flag — still from the hunk alone — new code that duplicates an existing
        helper visible in the diff context, and dead code the diff leaves behind.

        Do **not** flag style, naming, perf, missing tests, or anything outside the
        hunk.

        Output at most **4 findings**, most-severe first, one line each:
        `path/to/file.ext:123 — what's wrong and the concrete failure`. If nothing
        qualifies, output exactly `(none)`. Do not call the
        ReportFindings tool even if it is available.
        """;

    /** Angles A–C — shared by both tables ({@code P3g} inside {@code j_s}/{@code I3g}). */
    private static final String ANGLES_A_TO_C = """
        ### Angle A — line-by-line diff scan

        Read every hunk in the diff, line by line. Then Read the enclosing function for
        each hunk — bugs in unchanged lines of a touched function are in scope (the PR
        re-exposes or fails to fix them). For every line ask: what input, state, timing,
        or platform makes this line wrong? Look for inverted/wrong conditions,
        off-by-one, null/undefined deref, missing `await`, falsy-zero checks,
        wrong-variable copy-paste, error swallowed in catch, unescaped regex metachars.

        ### Angle B — removed-behavior auditor

        For every line the diff DELETES or replaces, name the invariant or behavior it
        enforced, then search the new code for where that invariant is re-established.
        If you can't find it, that's a candidate: a removed guard, a dropped error
        path, a narrowed validation, a deleted test that was covering a real case.

        ### Angle C — cross-file tracer

        For each function the diff changes, find its callers (Grep for the symbol) and
        check whether the change breaks any call site: a new precondition, a changed
        return shape, a new exception, a timing/ordering dependency. Also check callees:
        does a parallel change in the same PR make a call unsafe?

        """;

    /** Angles D and E — only the xhigh/max table ({@code I3g}) carries them. */
    private static final String ANGLES_D_E = """
        ### Angle D — language-pitfall specialist

        Scan for the classic pitfalls of the diff's language/framework — for example:
        JS falsy-zero, `==` coercion, closure-captured loop var; Python mutable default
        args, late-binding closures; Go nil-map write, range-var capture; SQL injection;
        timezone/DST drift; float equality. Flag any instance the diff introduces.

        ### Angle E — wrapper/proxy correctness

        When the PR adds or modifies a type that wraps another (cache, proxy, decorator,
        adapter): check that every method routes to the wrapped instance and not back
        through a registry/session/global — e.g. a caching provider holding a
        `delegate` field that resolves IDs via `session.get(...)` instead of
        `delegate.get(...)` will re-enter the cache or recurse. Also check that the
        wrapper forwards all the methods the callers actually use.

        """;

    /** Reuse/Simplification/Efficiency/Altitude/Conventions + the ordering rule ({@code j_s}/{@code I3g} tails + {@code GEr}). */
    private static final String CLEANUP_ANGLES = """
        ### Reuse

        The angles above hunt for bugs; this one and the next two hunt for cleanup in
        the changed code. Flag new code that re-implements something the codebase
        already has — Grep shared/utility modules and files adjacent to the change,
        and name the existing helper to call instead.

        ### Simplification

        Flag unnecessary complexity the diff adds: redundant or derivable state,
        copy-paste with slight variation, deep nesting, dead code left behind. Name
        the simpler form that does the same job.

        ### Efficiency

        Flag wasted work the diff introduces: redundant computation or repeated I/O,
        independent operations run sequentially, blocking work added to startup or
        hot paths. Also flag long-lived objects built from closures or captured
        environments — they keep the entire enclosing scope alive for the object's
        lifetime (a memory leak when that scope holds large values); prefer a
        class/struct that copies only the fields it needs. Name the cheaper
        alternative.

        ### Altitude

        Check that each change is implemented at the right depth, not as a fragile
        bandaid. Special cases layered on shared infrastructure are a sign the fix
        isn't deep enough — prefer generalizing the underlying mechanism over adding
        special cases.

        ### Conventions (CLAUDE.md)

        Find the CLAUDE.md files that govern the changed code: the user-level
        ~/.claude/CLAUDE.md, the repo-root CLAUDE.md, plus any CLAUDE.md or
        CLAUDE.local.md in a directory that is an ancestor of a changed file (a
        directory's CLAUDE.md only applies to files at or below it). Read each one
        that exists, then check the diff for clear violations of the rules they state.

        Only flag a violation when you can quote the exact rule and the exact line
        that breaks it — no style preferences, no vague "spirit of the doc"
        inferences. In the finding, name the CLAUDE.md path and quote the rule so the
        report can cite it. If no CLAUDE.md applies, return nothing for this angle.

        Cleanup, altitude, and conventions candidates use the same
        `file`/`line`/`summary` shape; in `failure_scenario`, state the concrete
        cost (what is duplicated, wasted, harder to maintain, or which CLAUDE.md rule
        is broken) instead of a crash. Correctness bugs always outrank cleanup,
        altitude, and conventions findings when the output cap forces a cut.

        """;

    /**
     * The "Pass every candidate" paragraph ({@code $3g}/{@code B3g} tail after
     * {@code GEr}); the xhigh/max template ({@code U3g}) omits it.
     */
    private static final String PASS_EVERY_CANDIDATE = """
        Pass every candidate with a nameable failure scenario through — finders that
        silently drop half-believed candidates bypass the verify step and are the
        dominant cause of misses.

        """;

    /**
     * Phase 2 — 236's two verify templates: {@code D3g} (plain 1-vote 3-state,
     * medium and xhigh/max) and {@code _WE} (recall-biased variant, high).
     * xhigh/max append the bundle's recall-mode clause after the plain template.
     */
    private static String verify(String level) {
        if (Strings.CS.equals("low", level)) return "";
        if (Strings.CS.equals("high", level)) {
            return """
                ## Phase 2 — Verify (1-vote, recall-biased)

                Dedup near-duplicates (same defect, same location, same reason → keep one). For
                each remaining candidate, run **one verifier** via the Agent tool:
                give it the diff, the relevant file(s), and the candidate; it returns exactly
                one of **CONFIRMED / PLAUSIBLE / REFUTED**.

                **PLAUSIBLE by default** — do not refute a candidate for being "speculative" or
                "depends on runtime state" when the state is realistic: concurrency races,
                nil/undefined on a rare-but-reachable path (error handler, cold cache, missing
                optional field), falsy-zero treated as missing, off-by-one on a boundary the
                code does not exclude, retry storms / partial failures, regex/allowlist that
                lost an anchor. These are PLAUSIBLE.

                **REFUTED** only when constructible from the code: factually wrong (quote the
                actual line); provably impossible (type/constant/invariant — show it); already
                handled in this diff (cite the guard); or pure style with no observable effect.

                Keep **CONFIRMED and PLAUSIBLE**. Drop REFUTED.

                """;
        }
        // medium and xhigh/max — the plain D3g template; xhigh/max add the recall-mode clause.
        return """
            ## Phase 2 — Verify (1-vote, 3-state)

            Dedup candidates that point at the same line/mechanism, keeping the one with
            the most concrete failure scenario. For each remaining candidate, run **one
            verifier** via the Agent tool: give it the diff, the relevant
            file(s), and the candidate, and have it return exactly one of:

            - **CONFIRMED** — can name the inputs/state that trigger it and the wrong
              output or crash. Quote the line.
            - **PLAUSIBLE** — mechanism is real, trigger is uncertain (timing, env,
              config). State what would confirm it.
            - **REFUTED** — factually wrong (code doesn't say that) or guarded elsewhere.
              Quote the line that proves it.

            Keep candidates where the vote is CONFIRMED or PLAUSIBLE.

            """
            + (Strings.CS.equals("xhigh", level) || Strings.CS.equals("max", level) ? """
            This is recall mode — a single non-REFUTED vote carries the finding. Do NOT
            drop on uncertainty.

            """ : "");
    }

    /**
     * Phase 3 — the xhigh/max gap sweep (236's {@code bWE}); other levels have none.
     */
    private static String sweep(String level) {
        if (!(Strings.CS.equals("xhigh", level) || Strings.CS.equals("max", level))) return "";
        return """
            ## Phase 3 — Sweep for gaps

            Run **one more finder** as a fresh reviewer who has the verified list. Re-read
            the diff and enclosing functions looking ONLY for defects not already listed.
            Do not re-derive or re-confirm anything already there — the job is gaps. Focus
            on what the first pass tends to miss: moved/extracted code that dropped a guard
            or anchor; second-tier footguns (dataclass default evaluated once, `hash()`
            non-determinism, lock-scope shrink, predicate methods with side effects);
            setup/teardown asymmetry in tests; config defaults flipped.

            Surface **up to 8 additional candidates**, each naming a defect not already on
            the list. If nothing new, return an empty sweep — do not pad.

            """;
    }

    /** RWE — verbatim from the bundle (the frozen wire carries the mcp tool + gh fallback). */
    private static final String POST_COMMENT = """

        ## Posting to GitHub (--comment)

        The `--comment` flag was passed. After producing the findings list, if the
        review target is a GitHub PR, post each finding as an inline PR comment via
        `mcp__github_inline_comment__create_inline_comment` (one call per finding;
        include a suggestion block only when it fully fixes the issue). If that tool
        is not available in this session, fall back to `gh api` (repos/{owner}/{repo}/pulls/{pr}/comments)
        or print the findings instead. If the target is not a PR, print the findings
        to the terminal and note that `--comment` was ignored.
        """;

    /** IWE — verbatim from the bundle (the no-ReportFindings-tool branch of its tail). */
    private static final String APPLY_FIXES = """

        ## Applying fixes (--fix)

        The `--fix` flag was passed. After producing the findings list, apply the
        findings to the working tree instead of stopping at the report: fix each one
        directly — correctness bugs and reuse/simplification/efficiency cleanups alike.
        Skip any finding whose fix would change intended behavior, require changes well
        outside the reviewed diff, or that you judge to be a false positive — note the
        skip rather than arguing with it. Finish with a brief summary of what was fixed
        and what was skipped.
        """;

    /**
     * Output contract — 236's no-ReportFindings-tool variants: the medium→max
     * levels share {@code L3g}'s JSON-array contract (the frozen wire carries the
     * full `[\n  {\n…\n  }\n]` block); low's single-line-list contract is the tail
     * of {@link #LOW_TURNS} ({@code N3g}).
     */
    private static String output(String level) {
        if (Strings.CS.equals("low", level)) return "";
        int cap = switch (level) {
            case "medium" -> 8;
            case "high" -> 10;
            default -> 15;
        };
        return """
            ## Output

            Return findings as a JSON array of at most %d objects:

            ```json
            [
              {
                "file": "path/to/file.ext",
                "line": 123,
                "summary": "one-sentence statement of the bug",
                "failure_scenario": "concrete inputs/state → wrong output/crash"
              }
            ]
            ```

            Ranked most-severe first. If more than %d survive, keep the %d most
            severe. If nothing survives verification, return `[]`. Do not call the
            ReportFindings tool even if it is available - this review's
            output contract is the JSON block above.
            """.formatted(cap, cap, cap);
    }
}
