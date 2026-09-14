package com.claudecode.commands.impl.review;

import com.claudecode.commands.bootstrap.CommandFactory;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandRegistry;
import com.claudecode.core.message.Usage;

import org.apache.commons.lang3.Strings;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeReviewCommandTest {

    @Test
    void effortTokensResolveToLevels() {
        assertAll(
            () -> assertEquals("low", CodeReviewCommand.effortOf("low")),
            () -> assertEquals("low", CodeReviewCommand.effortOf("LOW")),
            () -> assertEquals("medium", CodeReviewCommand.effortOf("medium")),
            () -> assertEquals("medium", CodeReviewCommand.effortOf("med")),
            () -> assertEquals("medium", CodeReviewCommand.effortOf("med")),
            () -> assertEquals("high", CodeReviewCommand.effortOf("hig")),
            () -> assertEquals("xhigh", CodeReviewCommand.effortOf("xhig")),
            () -> assertEquals("max", CodeReviewCommand.effortOf("max")),
            // Non-effort tokens (targets) stay null.
            () -> assertNull(CodeReviewCommand.effortOf("1234")),
            () -> assertNull(CodeReviewCommand.effortOf("main")),
            () -> assertNull(CodeReviewCommand.effortOf("src/foo.java")),
            () -> assertNull(CodeReviewCommand.effortOf("")),
            // Ambiguous short prefixes (m..: medium/max, both start 'm') stay null.
            () -> assertNull(CodeReviewCommand.effortOf("m")),
            () -> assertNull(CodeReviewCommand.effortOf("ma")));
    }

    @Test
    void parsesLevelFlagsAndTarget() {
        assertAll(
            () -> assertEquals(new CodeReviewCommand.ParsedArgs("high", "1234", false, false),
                CodeReviewCommand.parseArgs("high 1234")),
            () -> assertEquals(new CodeReviewCommand.ParsedArgs(null, "feature-branch", false, false),
                CodeReviewCommand.parseArgs("feature-branch")),
            () -> assertFalse(new CodeReviewCommand.ParsedArgs(null, null, false, false)
                .toString().isEmpty()),
            // Flags are position-independent and stripped from the target.
            () -> assertEquals(new CodeReviewCommand.ParsedArgs("medium", "1234", true, true),
                CodeReviewCommand.parseArgs("medium 1234 --fix --comment")),
            () -> assertEquals(new CodeReviewCommand.ParsedArgs(null, "1234", true, false),
                CodeReviewCommand.parseArgs("--fix 1234")),
            // No args at all.
            () -> assertEquals(new CodeReviewCommand.ParsedArgs(null, "", false, false),
                CodeReviewCommand.parseArgs("")),
            () -> assertEquals(new CodeReviewCommand.ParsedArgs(null, "", false, false),
                CodeReviewCommand.parseArgs(null)),
            // A path target survives level detection.
            () -> assertEquals(new CodeReviewCommand.ParsedArgs("low", "src/foo.java", false, false),
                CodeReviewCommand.parseArgs("low src/foo.java")),
            // Unknown flags are not consumed; they stay part of the target.
            () -> assertEquals(new CodeReviewCommand.ParsedArgs(null, "--verbose", false, false),
                CodeReviewCommand.parseArgs("--verbose")));
    }

    @Test
    void promptCarriesTheStagesForItsLevel() {
        String medium = CodeReviewCommand.buildPrompt("medium", "", false, false);
        assertAll(
            () -> assertTrue(Strings.CS.contains(medium, "## Phase 0 — Gather the diff")),
            () -> assertTrue(Strings.CS.contains(medium, "## Phase 1 — Find candidates")),
            () -> assertTrue(Strings.CS.contains(medium, "Run **8 independent finder angles**")),
            () -> assertTrue(Strings.CS.contains(medium, "### Angle A — line-by-line diff scan")),
            () -> assertTrue(Strings.CS.contains(medium, "### Angle C — cross-file tracer")),
            // The j_s angle table (medium/high) stops at three correctness angles.
            () -> assertFalse(Strings.CS.contains(medium, "### Angle D —")),
            () -> assertFalse(Strings.CS.contains(medium, "### Angle E —")),
            () -> assertTrue(Strings.CS.contains(medium, "### Conventions (CLAUDE.md)")),
            () -> assertTrue(Strings.CS.contains(medium, "## Phase 2 — Verify (1-vote, 3-state)")),
            () -> assertTrue(Strings.CS.contains(medium, "## Output")),
            // No flags: neither optional section rides along.
            () -> assertFalse(Strings.CS.contains(medium, "Applying fixes")),
            () -> assertFalse(Strings.CS.contains(medium, "Posting to GitHub")));
        String high = CodeReviewCommand.buildPrompt("high", "", false, false);
        assertAll(
            () -> assertTrue(Strings.CS.contains(high, "## Phase 2 — Verify (1-vote, recall-biased)")),
            () -> assertTrue(Strings.CS.contains(high, "**PLAUSIBLE by default**")),
            // High keeps the j_s table: no D/E angles, no Phase 3 sweep.
            () -> assertFalse(Strings.CS.contains(high, "### Angle D —")),
            () -> assertFalse(Strings.CS.contains(high, "## Phase 3 — Sweep")));
    }

    @Test
    void promptScalesWithEffortAndTarget() {
        String max = CodeReviewCommand.buildPrompt("max", "42", true, true);
        assertAll(
            () -> assertTrue(Strings.CS.contains(max, "Run **10 independent finder angles**")),
            () -> assertTrue(Strings.CS.contains(max, "### Angle D — language-pitfall specialist")),
            () -> assertTrue(Strings.CS.contains(max, "### Angle E — wrapper/proxy correctness")),
            () -> assertTrue(Strings.CS.contains(max, "This is recall mode")),
            () -> assertTrue(Strings.CS.contains(max, "## Phase 3 — Sweep for gaps")),
            () -> assertTrue(Strings.CS.contains(max, "Review target: `42`")),
            () -> assertTrue(Strings.CS.contains(max, "Applying fixes (--fix)")),
            () -> assertTrue(Strings.CS.contains(max, "Posting to GitHub (--comment)")),
            // U3g (xhigh/max) omits $3g/B3g's "Pass every candidate" paragraph.
            () -> assertFalse(Strings.CS.contains(max, "Pass every candidate")));
        String low = CodeReviewCommand.buildPrompt("low", "", false, false);
        assertAll(
            () -> assertTrue(Strings.CS.contains(low, "1 diff pass")),
            () -> assertTrue(Strings.CS.contains(low, "## Turn 2 — findings")),
            () -> assertFalse(Strings.CS.contains(low, "## Phase 2 — Verify")),
            // N3g is self-contained: no Phase 0 and no angle table ride along.
            () -> assertFalse(Strings.CS.contains(low, "## Phase 0 — Gather the diff")),
            () -> assertFalse(Strings.CS.contains(low, "### Angle A —")),
            () -> assertFalse(Strings.CS.contains(low, "Run **8 independent finder angles**")),
            () -> assertFalse(Strings.CS.contains(low, "Review target:")),
            () -> assertTrue(Strings.CS.contains(low, "Output at most **4 findings**")));
    }

    @Test
    void resolvesLevelAgainstTheModelCapabilityGate() {
        assertAll(
            // Explicit levels the model supports survive untouched.
            () -> assertEquals("high", CodeReviewCommand.resolveLevel("high", "claude-sonnet-4-6")),
            () -> assertEquals("low", CodeReviewCommand.resolveLevel("low", "claude-sonnet-4-6")),
            () -> assertEquals("max", CodeReviewCommand.resolveLevel("max", "claude-sonnet-4-6")),
            // claude-sonnet-4-6 lacks xhigh_effort: xhigh clamps to high (XDe).
            () -> assertEquals("high", CodeReviewCommand.resolveLevel("xhigh", "claude-sonnet-4-6")),
            // Unknown models keep the typed level (no catalog to gate it).
            () -> assertEquals("xhigh", CodeReviewCommand.resolveLevel("xhigh", "claude-fable-5")),
            // No explicit level: the model's catalog default; unknown non-custom
            // models fall back to EffortHelpers' "high", not 236's "medium".
            () -> assertEquals("high", CodeReviewCommand.resolveLevel(null, "claude-sonnet-4-6")),
            () -> assertEquals("high", CodeReviewCommand.resolveLevel(null, "claude-fable-5")));
    }

    @Test
    void invocationThreadsTheResolvedLevelAsEffort() {
        // 236's getEffort: the resolved level (post capability clamp) rides the
        // query's output_config.effort — frozen on the wire as low→low,
        // max→max, xhigh→high (clamped for sonnet-4-6).
        CodeReviewCommand command = new CodeReviewCommand();
        CommandContext sonnet = CommandContext.builder(
            "claude-sonnet-4-6", List::of, () -> { }, _ -> { },
            () -> Usage.EMPTY, _ -> 0.0, System.getProperty("user.dir"), false).build();
        assertAll(
            () -> assertEquals("low", command.execute(sonnet, "low")
                .promptInvocation().effort()),
            () -> assertEquals("max", command.execute(sonnet, "max")
                .promptInvocation().effort()),
            () -> assertEquals("high", command.execute(sonnet, "xhigh")
                .promptInvocation().effort()),
            () -> assertEquals("high", command.execute(sonnet, "")
                .promptInvocation().effort()));
    }

    @Test
    void registryResolvesCodeReviewAndTheReviewAlias() {
        CommandRegistry registry = CommandFactory.createDefault();
        assertAll(
            () -> assertTrue(registry.find("code-review").isPresent()),
            () -> assertTrue(registry.find("review").isPresent()),
            () -> assertEquals(registry.find("code-review"), registry.find("review"),
                "the /review stub predates 236 merging /review into /code-review as an alias"));
    }
}
