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
import java.util.Map;
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
 * <p>The prompt segments are frozen wire extracts loaded from
 * {@code command-text/official/2.1.236/code-review/} via
 * {@link CodeReviewTemplates} (byte-checked against the
 * {@code S236-P1-CODE-REVIEW} baseline, 8 variants identical). Stage
 * structure per level (ported from the 236 bundle): low is a two-turn
 * single-pass read; medium/high run 8 angles (3 correctness + 5 cleanup)
 * with a 1-vote verify (high's is recall-biased); xhigh/max run 10 angles
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
    // Prompt assembly — the frozen 236 wire segments (loaded verbatim from
    // command-text resources; blank-line placement is part of the contract
    // and lives in the files themselves, not in this code).
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
            prompt.append(CodeReviewTemplates.template("template-low"));
        } else {
            boolean extended = Strings.CS.equals("xhigh", level) || Strings.CS.equals("max", level);
            prompt.append(leadIn(level, extended));
            prompt.append(CodeReviewTemplates.template("phase-0-gather-diff"));
            prompt.append(CodeReviewTemplates.template(extended ? "phase-1-head-I3g" : "phase-1-head-j_s"));
            prompt.append(CodeReviewTemplates.template("angles-a-to-c"));
            if (extended) {
                prompt.append(CodeReviewTemplates.template("angles-d-e"));
            }
            prompt.append(CodeReviewTemplates.template("cleanup-angles"));
            if (!extended) {
                // GEr's ordering-rule tail + the "Pass every candidate" para only
                // ride the medium/high templates ($3g/B3g), not U3g (xhigh/max).
                prompt.append(CodeReviewTemplates.template("pass-every-candidate"));
            }
            prompt.append(verify(level));
            if (extended) {
                prompt.append(CodeReviewTemplates.template("phase-3-sweep"));
            }
            prompt.append(output(level));
        }
        if (comment) {
            // RWE opens with a leading newline of its own (bundle constant).
            prompt.append(CodeReviewTemplates.template("post-comment"));
        }
        if (fix) {
            // IWE likewise (bundle constant).
            prompt.append(CodeReviewTemplates.template("apply-fixes"));
        }
        return prompt.toString();
    }

    private static String leadIn(String level, boolean extended) {
        if (extended) {
            // xhigh and max share the extended lead-in; only the effort adjective differs.
            return CodeReviewTemplates.render("lead-in-extended",
                Map.of("EFFORT_ADJECTIVE", Strings.CS.equals("max", level) ? "maximum" : "extra-high"));
        }
        return CodeReviewTemplates.template(Strings.CS.equals("medium", level)
            ? "lead-in-medium" : "lead-in-high");
    }

    /**
     * Phase 2 — 236's two verify templates: {@code D3g} (plain 1-vote 3-state,
     * medium and xhigh/max) and {@code _WE} (recall-biased variant, high).
     * xhigh/max append the bundle's recall-mode clause after the plain template.
     */
    private static String verify(String level) {
        if (Strings.CS.equals("high", level)) {
            return CodeReviewTemplates.template("verify-recall-biased");
        }
        boolean extended = Strings.CS.equals("xhigh", level) || Strings.CS.equals("max", level);
        return CodeReviewTemplates.template("verify-plain")
            + (extended ? CodeReviewTemplates.template("verify-recall-clause") : "");
    }

    /**
     * Output contract — 236's no-ReportFindings-tool variants: the medium→max
     * levels share {@code L3g}'s JSON-array contract (cap 8/10/15 by level);
     * low's single-line-list contract is the tail of the low template
     * ({@code N3g}).
     */
    private static String output(String level) {
        int cap = switch (level) {
            case "medium" -> 8;
            case "high" -> 10;
            default -> 15;
        };
        return CodeReviewTemplates.render("output-contract", Map.of("CAP", String.valueOf(cap)));
    }
}
