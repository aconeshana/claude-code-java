package com.claudecode.permissions;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.engine.PermissionUpdate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PermissionEngine.
 */
class PermissionEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private PermissionEngine engine;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        engine = new PermissionEngine();
    }

    @Test
    void defaultModeReturnsAsk() {
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.DEFAULT)
            .build();

        PermissionDecision decision = engine.evaluateDetailed("Bash", createInput("command", "ls"), ctx).decision();
        assertInstanceOf(PermissionDecision.Ask.class, decision);
    }

    @Test
    void workflowNamedRuleMatchesTheWorkflowNameRatherThanSerializedInput() {
        ToolPermissionContext ctx = ToolPermissionContext.of(Path.of("."))
            .addRules(List.of(PermissionRule.withPattern("Workflow", PermissionBehavior.ALLOW,
                RuleSource.SESSION, "code-review")));
        ObjectNode input = MAPPER.createObjectNode().put("name", "code-review").put("args", "high");

        PermissionDecision decision = engine.evaluateDetailed("Workflow", input, ctx).decision();

        assertInstanceOf(PermissionDecision.Allow.class, decision);
    }

    @Test
    void toolDecisionOverridesEngineDecision() {
        // In DEFAULT mode the engine falls back to Ask for an arbitrary tool call.
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.DEFAULT)
            .build();
        JsonNode input = createInput("command", "ls");

        // Tool Deny forces a deny even though the engine would otherwise ask.
        PermissionDecisionResult denied = engine.evaluateDetailed("Bash", input, ctx, new PermissionDecision.Deny());
        assertInstanceOf(PermissionDecision.Deny.class, denied.decision());

        // Tool Allow (passthrough) auto-allows unless the engine already denied.
        PermissionDecisionResult allowed = engine.evaluateDetailed("Bash", input, ctx, new PermissionDecision.Allow());
        assertInstanceOf(PermissionDecision.Allow.class, allowed.decision());

        // Tool Ask defers to the engine's normal decision (Ask in DEFAULT mode).
        PermissionDecisionResult asked = engine.evaluateDetailed("Bash", input, ctx, new PermissionDecision.Ask());
        assertInstanceOf(PermissionDecision.Ask.class, asked.decision());

        // Respecting an explicit deny rule: tool Allow cannot override a deny rule.
        ToolPermissionContext denyCtx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.DEFAULT)
            .rules(List.of(PermissionRule.of("Bash", PermissionBehavior.DENY, RuleSource.COMMAND)))
            .build();
        PermissionDecisionResult denyRuleWins = engine.evaluateDetailed("Bash", input, denyCtx, new PermissionDecision.Allow());
        assertInstanceOf(PermissionDecision.Deny.class, denyRuleWins.decision());
    }

    @Test
    void toolAskPayloadSurvivesNormalEngineAsk() {
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.DEFAULT)
            .build();
        PermissionUpdate suggestion = new PermissionUpdate.AddRules(
            List.of(new PermissionUpdate.RuleValue("WebSearch", null)),
            PermissionUpdate.Behavior.ALLOW,
            PermissionUpdate.Destination.LOCAL_SETTINGS);

        PermissionDecisionResult result = engine.evaluateDetailed(
            "WebSearch", MAPPER.createObjectNode().put("query", "latest"), ctx,
            new PermissionDecision.Ask(null, null, "WebSearchTool requires permission.",
                null, null, List.of(suggestion)));

        PermissionDecision.Ask ask = assertInstanceOf(PermissionDecision.Ask.class, result.decision());
        assertEquals("WebSearchTool requires permission.", ask.message());
        assertEquals(List.of(suggestion), ask.suggestions());
    }

    @Test
    void toolSpecificDenyPayloadSurvivesEngineMerge() {
        ToolPermissionContext ctx = ToolPermissionContext.of(Path.of("."));
        PermissionDecision.Deny toolDeny = new PermissionDecision.Deny(
            "Remove-Item on system path '/' is blocked. This path is protected from removal.",
            new DecisionReason.Other("Removal targets a protected system path"));

        PermissionDecisionResult result = engine.evaluateDetailed(
            "PowerShell", createInput("command", "Remove-Item /"), ctx, toolDeny);

        PermissionDecision.Deny merged = assertInstanceOf(PermissionDecision.Deny.class, result.decision());
        assertEquals(toolDeny.message(), merged.message());
        assertEquals(toolDeny.reason(), merged.reason());
    }

    @Test
    void autoAllowBashIfSandboxed_sandboxedCommand_isAllowed() {
        engine.setBashSandboxGate((_, _) -> true);
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.DEFAULT)
            .build();
        // In DEFAULT mode a Bash command would normally Ask; with the sandbox
        // gate reporting "would run sandboxed" it is auto-allowed.
        PermissionDecision decision = engine.evaluateDetailed("Bash", createInput("command", "rm -rf /"), ctx).decision();
        assertInstanceOf(PermissionDecision.Allow.class, decision);
        PermissionDecisionResult detailed = engine.evaluateDetailed("Bash", createInput("command", "rm -rf /"), ctx);
        assertInstanceOf(PermissionDecision.Allow.class, detailed.decision());
        assertInstanceOf(DecisionReason.SandboxOverride.class, detailed.reason());
    }

    @Test
    void autoAllowBashIfSandboxed_nonBash_notAffected() {
        engine.setBashSandboxGate((_, _) -> true);
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.DEFAULT)
            .build();
        // Gate only applies to Bash; a non-Bash write tool still asks.
        PermissionDecision decision = engine.evaluateDetailed("Write", createInput("file_path", "/tmp/x"), ctx).decision();
        assertInstanceOf(PermissionDecision.Ask.class, decision);
    }

    @Test
    void autoAllowBashIfSandboxed_denyRuleStillWins() {
        engine.setBashSandboxGate((_, _) -> true);
        ToolPermissionContext denyCtx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.DEFAULT)
            .rules(List.of(PermissionRule.of("Bash", PermissionBehavior.DENY, RuleSource.COMMAND)))
            .build();
        // An explicit deny rule outranks the sandbox auto-allow.
        PermissionDecision decision = engine.evaluateDetailed("Bash", createInput("command", "rm -rf /"), denyCtx).decision();
        assertInstanceOf(PermissionDecision.Deny.class, decision);
    }

    @Test
    void autoAllowBashIfSandboxed_gateNotSet_noAutoAllow() {
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.DEFAULT)
            .build();
        PermissionDecision decision = engine.evaluateDetailed("Bash", createInput("command", "ls"), ctx).decision();
        assertInstanceOf(PermissionDecision.Ask.class, decision);
    }


    @Test
    void workingDirectoryAutoAllowAndSafetyThroughMerge() {
        ToolPermissionContext defaultCtx = ToolPermissionContext.builder()
            .workingDirectory(tempDir)
            .mode(PermissionMode.DEFAULT)
            .build();
        ToolPermissionContext acceptCtx = ToolPermissionContext.builder()
            .workingDirectory(tempDir)
            .mode(PermissionMode.ACCEPT_EDITS)
            .build();
        ToolPermissionContext autoCtx = ToolPermissionContext.builder()
            .workingDirectory(tempDir)
            .mode(PermissionMode.AUTO)
            .build();

        Path inWd = tempDir.resolve("file.txt");


        assertInstanceOf(PermissionDecision.Allow.class,
            engine.evaluateDetailed("Read", createInput("file_path", inWd.toString()), defaultCtx, new PermissionDecision.Ask()).decision());
        // Grep is read-only too → Allow unconditionally inside WD.
        assertInstanceOf(PermissionDecision.Allow.class,
            engine.evaluateDetailed("Grep", createInput("path", inWd.toString()), defaultCtx, new PermissionDecision.Ask()).decision());


        assertInstanceOf(PermissionDecision.Ask.class,
            engine.evaluateDetailed("Write", createInput("file_path", inWd.toString()), defaultCtx, new PermissionDecision.Ask()).decision());
        assertInstanceOf(PermissionDecision.Ask.class,
            engine.evaluateDetailed("Edit", createInput("file_path", inWd.toString()), defaultCtx, new PermissionDecision.Ask()).decision());
        assertInstanceOf(PermissionDecision.Ask.class,
            engine.evaluateDetailed("NotebookEdit", createInput("notebook_path", inWd.toString()), defaultCtx, new PermissionDecision.Ask()).decision());


        assertInstanceOf(PermissionDecision.Allow.class,
            engine.evaluateDetailed("Write", createInput("file_path", inWd.toString()), acceptCtx, new PermissionDecision.Ask()).decision());
        assertInstanceOf(PermissionDecision.Allow.class,
            engine.evaluateDetailed("Edit", createInput("file_path", inWd.toString()), acceptCtx, new PermissionDecision.Ask()).decision());

        // AUTO first re-runs the acceptEdits check before invoking its classifier.

        assertInstanceOf(PermissionDecision.Allow.class,
            engine.evaluateDetailed("Write", createInput("file_path", inWd.toString()), autoCtx, new PermissionDecision.Ask()).decision());
        assertInstanceOf(PermissionDecision.Allow.class,
            engine.evaluateDetailed("Edit", createInput("file_path", inWd.toString()), autoCtx, new PermissionDecision.Ask()).decision());

        // Outside working dir → Ask (no auto-allow), for both read and write.
        Path outside = Path.of("/etc/hosts");
        assertInstanceOf(PermissionDecision.Ask.class,
            engine.evaluateDetailed("Read", createInput("file_path", outside.toString()), defaultCtx, new PermissionDecision.Ask()).decision());
        assertInstanceOf(PermissionDecision.Ask.class,
            engine.evaluateDetailed("Write", createInput("file_path", outside.toString()), acceptCtx, new PermissionDecision.Ask()).decision());
        assertInstanceOf(PermissionDecision.Ask.class,
            engine.evaluateDetailed("Write", createInput("file_path", outside.toString()), autoCtx, new PermissionDecision.Ask()).decision());

        // Safety: inside WD but under a dangerous directory (.git) → Ask even in ACCEPT_EDITS.
        Path dangerous = tempDir.resolve(".git").resolve("config");
        assertInstanceOf(PermissionDecision.Ask.class,
            engine.evaluateDetailed("Edit", createInput("file_path", dangerous.toString()), acceptCtx, new PermissionDecision.Ask()).decision());
    }

    @Test
    void bypassModeReturnsAllow() {
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.BYPASS_PERMISSIONS)
            .build();

        PermissionDecision decision = engine.evaluateDetailed("Bash", createInput("command", "rm -rf /"), ctx).decision();
        assertInstanceOf(PermissionDecision.Allow.class, decision);
    }

    @Test
    void planModeAsksForWriteTools() {
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.PLAN)
            .build();


        // their ordinary permission decisions through the interactive prompt.
        assertInstanceOf(PermissionDecision.Ask.class, engine.evaluateDetailed("Bash", createInput("command", "rm file"), ctx).decision());
        assertInstanceOf(PermissionDecision.Ask.class, engine.evaluateDetailed("Write", createInput("path", "/tmp/f"), ctx).decision());
        assertInstanceOf(PermissionDecision.Ask.class, engine.evaluateDetailed("Edit", createInput("path", "/tmp/f"), ctx).decision());
        assertInstanceOf(PermissionDecision.Ask.class, engine.evaluateDetailed("NotebookEdit", createInput("path", "/tmp/n.ipynb"), ctx).decision());
    }

    @Test
    void planModeNoRuleFallbackAsksForUnknownTools() {
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.PLAN)
            .build();

        assertInstanceOf(PermissionDecision.Ask.class,
            engine.evaluateDetailed("UnknownAction", createInput("value", "x"), ctx).decision());
    }

    @Test
    void denyRuleOverridesAllowRule() {
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.DEFAULT)
            .rules(List.of(
                PermissionRule.of("Bash", PermissionBehavior.ALLOW, RuleSource.USER_SETTINGS),
                PermissionRule.of("Bash", PermissionBehavior.DENY, RuleSource.POLICY_SETTINGS)
            ))
            .build();

        PermissionDecision decision = engine.evaluateDetailed("Bash", createInput("command", "ls"), ctx).decision();
        assertInstanceOf(PermissionDecision.Deny.class, decision);
    }

    @Test
    void allowRuleOverridesDefaultAsk() {
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.DEFAULT)
            .rules(List.of(
                PermissionRule.of("Bash", PermissionBehavior.ALLOW, RuleSource.USER_SETTINGS)
            ))
            .build();

        PermissionDecision decision = engine.evaluateDetailed("Bash", createInput("command", "ls"), ctx).decision();
        assertInstanceOf(PermissionDecision.Allow.class, decision);
    }

    @Test
    void wildcardRuleMatchesAnyTool() {
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.DEFAULT)
            .rules(List.of(
                PermissionRule.of("*", PermissionBehavior.DENY, RuleSource.POLICY_SETTINGS)
            ))
            .build();

        assertInstanceOf(PermissionDecision.Deny.class, engine.evaluateDetailed("Bash", createInput("command", "ls"), ctx).decision());
        assertInstanceOf(PermissionDecision.Deny.class, engine.evaluateDetailed("FileWrite", createInput("path", "/tmp"), ctx).decision());
    }

    @Test
    void patternMatchingOnInput() {
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.DEFAULT)
            .rules(List.of(
                PermissionRule.withPattern("Bash", PermissionBehavior.ALLOW, RuleSource.USER_SETTINGS, "git *")
            ))
            .build();

        // "git status" matches "git *"
        assertInstanceOf(PermissionDecision.Allow.class,
            engine.evaluateDetailed("Bash", createInput("command", "git status"), ctx).decision());

        // "rm -rf /" does not match "git *", falls through to DEFAULT → ASK
        assertInstanceOf(PermissionDecision.Ask.class,
            engine.evaluateDetailed("Bash", createInput("command", "rm -rf /"), ctx).decision());
    }

    @Test
    void wildcardPatternTrimsLeadingAndTrailingWhitespaceLikeTs() {
        assertTrue(PermissionEngine.matchWildcardPattern("  git *  ", "git status"));
        assertTrue(PermissionEngine.matchWildcardPattern("  git *  ", "git"));
    }

    @Test
    void autoModeUsesReleasedSafeAllowlistButStillAsksForClassifierActions() {
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.AUTO)
            .build();

        assertInstanceOf(PermissionDecision.Allow.class,
            engine.evaluateDetailed("Read", createInput("file_path", "/etc/hosts"), ctx).decision());
        assertInstanceOf(PermissionDecision.Allow.class,
            engine.evaluateDetailed("TaskList", createInput("unused", ""), ctx).decision());
        assertInstanceOf(PermissionDecision.Ask.class, engine.evaluateDetailed("Bash", createInput("command", "ls"), ctx).decision());
    }

    @Test
    void dontAskModeWithoutMatchingRuleDenies() {

        // fallback to 'deny' at the very end.
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.DONT_ASK)
            .build();

        assertInstanceOf(PermissionDecision.Deny.class, engine.evaluateDetailed("FileWrite", createInput("path", "/tmp"), ctx).decision());
    }

    @Test
    void dontAskModeWithAllowRuleStillAllows() {
        // dontAsk only converts 'ask' results; an explicit allow rule still allows.
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.DONT_ASK)
            .rules(List.of(
                PermissionRule.of("Bash", PermissionBehavior.ALLOW, RuleSource.USER_SETTINGS)
            ))
            .build();

        assertInstanceOf(PermissionDecision.Allow.class, engine.evaluateDetailed("Bash", createInput("command", "ls"), ctx).decision());
    }

    @Test
    void dontAskModePreservesToolLevelReadOnlyAllow() {
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.DONT_ASK)
            .build();

        PermissionDecisionResult result = engine.evaluateDetailed(
            "Bash", createInput("command", "printf 'ok\\n'"), ctx,
            new PermissionDecision.Allow());

        assertInstanceOf(PermissionDecision.Allow.class, result.decision());
    }

    @Test
    void dontAskModeAskRuleBecomesDeny() {
        // An ASK rule produces 'ask', which dontAsk converts to 'deny' at the end.
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.DONT_ASK)
            .rules(List.of(
                PermissionRule.of("Bash", PermissionBehavior.ASK, RuleSource.USER_SETTINGS)
            ))
            .build();

        assertInstanceOf(PermissionDecision.Deny.class, engine.evaluateDetailed("Bash", createInput("command", "ls"), ctx).decision());
    }

    @Test
    void toolPermissionContextImmutability() {
        ToolPermissionContext original = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.DEFAULT)
            .rules(List.of(PermissionRule.of("Bash", PermissionBehavior.ALLOW, RuleSource.USER_SETTINGS)))
            .build();

        ToolPermissionContext updated = original.setMode(PermissionMode.PLAN);

        assertEquals(PermissionMode.DEFAULT, original.mode());
        assertEquals(PermissionMode.PLAN, updated.mode());
        assertEquals(original.rules(), updated.rules());
    }

    @Test
    void addAndRemoveRules() {
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.DEFAULT)
            .build();

        PermissionRule rule1 = PermissionRule.of("Bash", PermissionBehavior.ALLOW, RuleSource.USER_SETTINGS);
        PermissionRule rule2 = PermissionRule.of("FileWrite", PermissionBehavior.DENY, RuleSource.POLICY_SETTINGS);

        ToolPermissionContext withRules = ctx.addRules(List.of(rule1, rule2));
        assertEquals(2, withRules.rules().size());

        ToolPermissionContext afterRemove = withRules.removeRules(r -> Strings.CS.equals(r.toolName(), "Bash"));
        assertEquals(1, afterRemove.rules().size());
        assertEquals("FileWrite", afterRemove.rules().getFirst().toolName());
    }

    @Test
    void addAndRemoveDirectories() {
        ToolPermissionContext ctx = ToolPermissionContext.of(Path.of("."));

        ToolPermissionContext withDirs = ctx.addDirectories(List.of(Path.of("/tmp"), Path.of("/var")));
        assertEquals(2, withDirs.additionalDirs().size());

        ToolPermissionContext afterRemove = withDirs.removeDirectories(List.of(Path.of("/tmp")));
        assertEquals(1, afterRemove.additionalDirs().size());
        assertEquals(Path.of("/var"), List.copyOf(afterRemove.additionalDirs().keySet()).getFirst());
    }

    // ── H8: addDirectories de-duplicates by path and records source ──

    @Test
    void addDirectoriesDeduplicatesByPathAndTracksSource() {
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .build();

        // Adding the same dir twice with different sources → one entry, source updated.
        ToolPermissionContext once = ctx.addDirectories(List.of(Path.of("/tmp")), RuleSource.USER_SETTINGS);
        ToolPermissionContext twice = once.addDirectories(List.of(Path.of("/tmp")), RuleSource.SESSION);

        assertEquals(1, twice.additionalDirs().size());
        assertEquals(RuleSource.SESSION, twice.additionalDirs().get(Path.of("/tmp")));

        // The default (List) overload uses SESSION provenance.
        ToolPermissionContext viaDefault = ctx.addDirectories(List.of(Path.of("/var")));
        assertEquals(RuleSource.SESSION, viaDefault.additionalDirs().get(Path.of("/var")));
        assertTrue(viaDefault.additionalDirs().containsKey(Path.of("/var")));
    }

    @Test
    void builderPattern() {
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("/home/user"))
            .mode(PermissionMode.PLAN)
            .rules(List.of(PermissionRule.of("Bash", PermissionBehavior.ALLOW, RuleSource.CLI_ARG)))
            .additionalDirs(Map.of(Path.of("/tmp"), RuleSource.SESSION))
            .build();

        assertEquals(Path.of("/home/user"), ctx.workingDirectory());
        assertEquals(PermissionMode.PLAN, ctx.mode());
        assertEquals(1, ctx.rules().size());
        assertEquals(1, ctx.additionalDirs().size());
    }

    @Test
    void builderRoundTripPreservesValues() {
        ToolPermissionContext original = ToolPermissionContext.builder()
            .workingDirectory(Path.of("/home"))
            .mode(PermissionMode.AUTO)
            .rules(List.of(PermissionRule.of("Bash", PermissionBehavior.DENY, RuleSource.SESSION)))
            .additionalDirs(Map.of(Path.of("/var"), RuleSource.SESSION))
            .build();

        ToolPermissionContext copy = ToolPermissionContext.builder()
            .workingDirectory(original.workingDirectory())
            .mode(original.mode())
            .rules(original.rules())
            .additionalDirs(original.additionalDirs())
            .build();

        assertEquals(original, copy);
    }

// ── Working-directory auto-allow.

    @Test
    void readInsideWorkingDirectoryIsAutoAllowedInDefaultMode() {
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.DEFAULT)
            .build();

        PermissionDecision decision = engine.evaluateDetailed("Read", createInput("file_path", "pom.xml"), ctx).decision();
        assertInstanceOf(PermissionDecision.Allow.class, decision);
    }

    @Test
    void readInsideAdditionalDirIsAutoAllowed() {
        String tmpFile = Path.of(System.getProperty("java.io.tmpdir"), "add-dir-test-file.txt").toString();
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("/some/unrelated/project"))
            .mode(PermissionMode.DEFAULT)
            .additionalDirs(Map.of(Path.of(System.getProperty("java.io.tmpdir")), RuleSource.SESSION))
            .build();

        PermissionDecision decision = engine.evaluateDetailed("Read", createInput("file_path", tmpFile), ctx).decision();
        assertInstanceOf(PermissionDecision.Allow.class, decision);
    }

    @Test
    void writeInsideWorkingDirectoryStillAsksInDefaultMode() {
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.DEFAULT)
            .build();

        PermissionDecision decision = engine.evaluateDetailed("Write", createInput("file_path", "pom.xml"), ctx).decision();
        assertInstanceOf(PermissionDecision.Ask.class, decision);
    }

    @Test
    void writeInsideWorkingDirectoryIsAutoAllowedInAcceptEditsMode() {
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.ACCEPT_EDITS)
            .build();

        assertInstanceOf(PermissionDecision.Allow.class,
            engine.evaluateDetailed("Write", createInput("file_path", "pom.xml"), ctx).decision());
        assertInstanceOf(PermissionDecision.Allow.class,
            engine.evaluateDetailed("Edit", createInput("file_path", "pom.xml"), ctx).decision());
        assertInstanceOf(PermissionDecision.Allow.class,
            engine.evaluateDetailed("NotebookEdit", createInput("notebook_path", "nb.ipynb"), ctx).decision());
    }

    @Test
    void acceptEditsModeDoesNotAutoAllowBashOrOutOfWorkingDirWrites() {

        // acceptEdits mode; only in-WD file edits are auto-approved (handled by
        // workingDirectoryAutoAllow). The no-rule fallback must therefore Ask.
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("/wd"))
            .mode(PermissionMode.ACCEPT_EDITS)
            .build();

        // Bash must not be silently approved.
        assertInstanceOf(PermissionDecision.Ask.class,
            engine.evaluateDetailed("Bash", createInput("command", "rm -rf /tmp/x"), ctx).decision());

        // Out-of-working-directory Write must not be silently approved.
        assertInstanceOf(PermissionDecision.Ask.class,
            engine.evaluateDetailed("Write", createInput("file_path", "/elsewhere/secret.txt"), ctx).decision());
    }

    @Test
    void explicitAskRuleOverridesWorkingDirectoryAutoAllow() {
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.DEFAULT)
            .rules(List.of(PermissionRule.withPattern("Read", PermissionBehavior.ASK, RuleSource.USER_SETTINGS, "pom.xml")))
            .build();

        PermissionDecision decision = engine.evaluateDetailed("Read", createInput("file_path", "pom.xml"), ctx).decision();
        assertInstanceOf(PermissionDecision.Ask.class, decision);
    }

// ── LSP tool (LSPTool.checkPermissions → PermissionEngine.evaluateDetailed("LSP", …).decision)
// ── LSP only reads, so it auto-allows unconditionally inside the working dir.

    @Test
    void lspInsideWorkingDirectoryIsAutoAllowedInDefaultMode() {
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.DEFAULT)
            .build();

        PermissionDecision decision = engine.evaluateDetailed("LSP", createInput("filePath", "pom.xml"), ctx).decision();
        assertInstanceOf(PermissionDecision.Allow.class, decision);
    }

    @Test
    void lspInsideWorkingDirectoryIsAutoAllowedInAcceptEditsMode() {
        // Read-only tool: auto-allowed regardless of mode (not gated on ACCEPT_EDITS).
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.ACCEPT_EDITS)
            .build();

        PermissionDecision decision = engine.evaluateDetailed("LSP", createInput("filePath", "pom.xml"), ctx).decision();
        assertInstanceOf(PermissionDecision.Allow.class, decision);
    }

    @Test
    void lspOutsideWorkingDirectoryAsksInDefaultMode() {
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("/wd"))
            .mode(PermissionMode.DEFAULT)
            .build();

        PermissionDecision decision = engine.evaluateDetailed("LSP", createInput("filePath", "/elsewhere/secret.txt"), ctx).decision();
        assertInstanceOf(PermissionDecision.Ask.class, decision);
    }

    @Test
    void lspDenyRuleBlocksEvenInsideWorkingDirectory() {
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.DEFAULT)
            .rules(List.of(PermissionRule.withPattern("LSP", PermissionBehavior.DENY, RuleSource.USER_SETTINGS, "secret.txt")))
            .build();

        PermissionDecision decision = engine.evaluateDetailed("LSP", createInput("filePath", "secret.txt"), ctx).decision();
        assertInstanceOf(PermissionDecision.Deny.class, decision);
    }

    @Test
    void explicitDenyRuleOverridesWorkingDirectoryAutoAllow() {
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.DEFAULT)
            .rules(List.of(PermissionRule.withPattern("Read", PermissionBehavior.DENY, RuleSource.POLICY_SETTINGS, "pom.xml")))
            .build();

        PermissionDecision decision = engine.evaluateDetailed("Read", createInput("file_path", "pom.xml"), ctx).decision();
        assertInstanceOf(PermissionDecision.Deny.class, decision);
    }

    @Test
    void bashIsNotAutoAllowedByWorkingDirectory() {
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.DEFAULT)
            .build();

        // Bash/PowerShell deliberately excluded — command strings aren't paths.
        PermissionDecision decision = engine.evaluateDetailed("Bash", createInput("command", "cat pom.xml"), ctx).decision();
        assertInstanceOf(PermissionDecision.Ask.class, decision);
    }

// ── H3: MCP server-level authorization.

    @Test
    void mcpServerLevelRuleMatchesToolsFromSameServer() {
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.DEFAULT)
            .rules(List.of(
                PermissionRule.of("mcp__server1", PermissionBehavior.ALLOW, RuleSource.USER_SETTINGS)
            ))
            .build();

        // Server-level rule "mcp__server1" authorizes any tool on that server.
        assertInstanceOf(PermissionDecision.Allow.class,
            engine.evaluateDetailed("mcp__server1__tool1", createInput("command", "ls"), ctx).decision());
        assertInstanceOf(PermissionDecision.Allow.class,
            engine.evaluateDetailed("mcp__server1__tool2", createInput("command", "ls"), ctx).decision());
    }

    @Test
    void mcpWildcardRuleMatchesToolsFromSameServer() {
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.DEFAULT)
            .rules(List.of(
                PermissionRule.of("mcp__server1__*", PermissionBehavior.ALLOW, RuleSource.USER_SETTINGS)
            ))
            .build();

        assertInstanceOf(PermissionDecision.Allow.class,
            engine.evaluateDetailed("mcp__server1__tool1", createInput("command", "ls"), ctx).decision());
    }

    @Test
    void mcpRuleDoesNotMatchDifferentServer() {
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.DEFAULT)
            .rules(List.of(
                PermissionRule.of("mcp__server1", PermissionBehavior.ALLOW, RuleSource.USER_SETTINGS)
            ))
            .build();

        // Server name must match exactly — different server is not authorized.
        assertInstanceOf(PermissionDecision.Ask.class,
            engine.evaluateDetailed("mcp__server2__tool1", createInput("command", "ls"), ctx).decision());
    }

// ── H4: tool name case must match exactly.

    @Test
    void toolNameMatchIsCaseSensitive() {
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.DEFAULT)
            .rules(List.of(
                PermissionRule.of("Bash", PermissionBehavior.ALLOW, RuleSource.USER_SETTINGS)
            ))
            .build();

        // Lowercase "bash" does NOT match rule "Bash" (strict case).
        assertInstanceOf(PermissionDecision.Ask.class,
            engine.evaluateDetailed("bash", createInput("command", "ls"), ctx).decision());
        // Exact case matches.
        assertInstanceOf(PermissionDecision.Allow.class,
            engine.evaluateDetailed("Bash", createInput("command", "ls"), ctx).decision());
    }

    @Test
    void readOutsideWorkingDirectoryFallsThroughToAsk() {
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.DEFAULT)
            .build();

        PermissionDecision decision = engine.evaluateDetailed("Read", createInput("file_path", "/etc/hosts"), ctx).decision();
        assertInstanceOf(PermissionDecision.Ask.class, decision);
    }

    // ── H6: "auto" retains its wire-visible mode identity ──

    @Test
    void autoStringRetainsAutoModeIdentity() {

        // permissionMode:"auto" in the SDK init envelope. Java still falls
        // back to ASK when no classifier decision exists, but must not erase
        // the selected mode before request/JSONL assembly.
        assertEquals(PermissionMode.AUTO, PermissionMode.fromString("auto"));
        assertEquals(PermissionMode.AUTO, PermissionGate.parseMode("auto"));
        assertEquals("auto", PermissionMode.AUTO.external());
    }

    @Test
    void modeParsingIsStrictCanonicalOnly() {

        assertEquals(PermissionMode.ACCEPT_EDITS, PermissionMode.fromString("acceptEdits"));
        assertEquals(PermissionMode.BYPASS_PERMISSIONS, PermissionMode.fromString("bypassPermissions"));
        assertEquals(PermissionMode.DONT_ASK, PermissionMode.fromString("dontAsk"));
        assertEquals(PermissionMode.PLAN, PermissionMode.fromString("plan"));
        // Non-canonical forms must NOT resolve (would have been "acceptEdits"/
        // "bypassPermissions" under the old lenient parser).
        assertEquals(PermissionMode.DEFAULT, PermissionMode.fromString("accept-edits"));
        assertEquals(PermissionMode.DEFAULT, PermissionMode.fromString("bypass"));
        assertEquals(PermissionMode.DEFAULT, PermissionMode.fromString("ACCEPT_EDITS"));
        assertEquals(PermissionMode.DEFAULT, PermissionMode.fromString("acceptedits"));
        assertEquals(PermissionMode.DEFAULT, PermissionMode.fromString("bogus"));
// parseMode matches the same strictness.
        assertEquals(PermissionMode.ACCEPT_EDITS, PermissionGate.parseMode("acceptEdits"));
        assertEquals(PermissionMode.DEFAULT, PermissionGate.parseMode("accept-edits"));
    }

    private static JsonNode createInput(String key, String value) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put(key, value);
        return node;
    }

    @Test
    void permissionRuleExtractPrefixHandlesLegacyColonStar() {

        assertEquals("npm", PermissionEngine.permissionRuleExtractPrefix("npm:*"));
        assertEquals("Bash", PermissionEngine.permissionRuleExtractPrefix("Bash:*"));
        assertEquals("a*", PermissionEngine.permissionRuleExtractPrefix("a*:*"));
        assertNull(PermissionEngine.permissionRuleExtractPrefix("git *"));
        assertNull(PermissionEngine.permissionRuleExtractPrefix("npm"));
        assertNull(PermissionEngine.permissionRuleExtractPrefix("npm:foo"));
        assertNull(PermissionEngine.permissionRuleExtractPrefix(":*"));
        assertNull(PermissionEngine.permissionRuleExtractPrefix(null));
    }

    @Test
    void filterDeniedAgentsExcludesDenied() {
        PermissionRule deny = PermissionRule.withPattern(
            "Agent", PermissionBehavior.DENY, RuleSource.SESSION, "bad-agent");
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .rules(List.of(deny))
            .build();
        assertEquals(List.of("bad-agent"),
            PermissionEngine.filterDeniedAgents(ctx, List.of("bad-agent", "good-agent")));
        assertTrue(PermissionEngine.getDenyRuleForAgent(ctx, "bad-agent").isPresent());
        assertTrue(PermissionEngine.getDenyRuleForAgent(ctx, "good-agent").isEmpty());
        // Wildcard deny rule matches any agent.
        PermissionRule wildcard = PermissionRule.withPattern(
            "Agent", PermissionBehavior.DENY, RuleSource.SESSION, "team-*");
        ToolPermissionContext ctx2 = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .rules(List.of(wildcard))
            .build();
        assertTrue(PermissionEngine.getDenyRuleForAgent(ctx2, "team-alpha").isPresent());
    }
}
