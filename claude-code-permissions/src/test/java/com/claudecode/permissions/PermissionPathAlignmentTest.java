package com.claudecode.permissions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.state.CwdState;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;


class PermissionPathAlignmentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void starAndDoubleStarFollowIgnoreSemantics() {
        Path cwd = Path.of("/tmp/permission-root");
        PermissionEngine engine = new PermissionEngine();

        ToolPermissionContext star = context(cwd,
            PermissionRule.withPattern("Edit", PermissionBehavior.ALLOW,
                RuleSource.SESSION, "foo/*"));
        assertTrue(isAllowed(engine, star, "foo/bar"));
// ignore treats the ignored foo/bar directory as hiding descendants.
        assertTrue(isAllowed(engine, star, "foo/bar/nested.txt"));
        assertFalse(isAllowed(engine, star, "foo"));
        assertFalse(isAllowed(engine, star, "foobar/bar"));

        ToolPermissionContext recursive = context(cwd,
            PermissionRule.withPattern("Edit", PermissionBehavior.ALLOW,
                RuleSource.SESSION, "foo/**"));
        assertTrue(isAllowed(engine, recursive, "foo"));
        assertTrue(isAllowed(engine, recursive, "foo/a/b/c.txt"));
    }

    @Test
    void rootedPatternsUseRuleSourceRootsAndNeverMatchOutsideThem() {
        Path cwd = Path.of("/tmp/permission-root");
        PermissionEngine engine = new PermissionEngine();
        ToolPermissionContext projectRooted = context(cwd,
            PermissionRule.withPattern("Edit", PermissionBehavior.ALLOW,
                RuleSource.PROJECT_SETTINGS, "/absolute/**"));

        assertTrue(isAllowed(engine, projectRooted, "/tmp/permission-root/absolute/file.txt"));
        assertFalse(isAllowed(engine, projectRooted, "/tmp/other/absolute/file.txt"));

        ToolPermissionContext homeRooted = context(cwd,
            PermissionRule.withPattern("Edit", PermissionBehavior.ALLOW,
                RuleSource.USER_SETTINGS, "~/secrets/**"));
        assertTrue(isAllowed(engine, homeRooted,
            Path.of(System.getProperty("user.home"), "secrets", "token.txt").toString()));
        assertTrue(isAllowed(engine, homeRooted, "~/secrets/token.txt"));
        assertFalse(isAllowed(engine, homeRooted, "/tmp/permission-root/secrets/token.txt"));
    }

    @Test
    void relativePatternsUseLiveCwdWhileSettingsRootsStayAnchoredToSessionRoot() {
        Path sessionRoot = Path.of("/tmp/session-root");
        Path liveCwd = Path.of("/tmp/session-root/worktree");
        PermissionPathContext paths = new PermissionPathContext(liveCwd, Map.of(
            RuleSource.PROJECT_SETTINGS, sessionRoot), Set.of());

        assertTrue(FilePermissionRuleMatcher.matches(
            "relative.txt", RuleSource.SESSION, liveCwd.resolve("relative.txt").toString(),
            paths, PermissionBehavior.ALLOW));
        assertFalse(FilePermissionRuleMatcher.matches(
            "relative.txt", RuleSource.SESSION, sessionRoot.resolve("relative.txt").toString(),
            paths, PermissionBehavior.ALLOW));
        assertTrue(FilePermissionRuleMatcher.matches(
            "/absolute/**", RuleSource.PROJECT_SETTINGS,
            sessionRoot.resolve("absolute/file.txt").toString(), paths, PermissionBehavior.ALLOW));
        assertFalse(FilePermissionRuleMatcher.matches(
            "/absolute/**", RuleSource.PROJECT_SETTINGS,
            liveCwd.resolve("absolute/file.txt").toString(), paths, PermissionBehavior.ALLOW));
        assertEquals(paths.rootFor(RuleSource.USER_SETTINGS), ClaudePaths.currentClaudeHome().toAbsolutePath().normalize());
    }

    @Test
    void sessionSettingsRootsFollowWorktreeRepointing() {
        Path previous = CwdState.getOriginalCwd();
        Path launchRoot = Path.of("/tmp/launch-root");
        Path worktreeRoot = Path.of("/tmp/launch-root/.claude/worktrees/feature");
        try {
            CwdState.setOriginalCwd(launchRoot);
            PermissionPathContext paths = PermissionPathContext.forSession(
                worktreeRoot, Map.of(RuleSource.PROJECT_SETTINGS, launchRoot), Set.of());
            CwdState.setOriginalCwd(worktreeRoot);
            assertEquals(paths.rootFor(RuleSource.PROJECT_SETTINGS), worktreeRoot);
            assertEquals(paths.rootFor(RuleSource.LOCAL_SETTINGS), worktreeRoot);
            assertEquals(paths.rootFor(RuleSource.POLICY_SETTINGS), worktreeRoot);

// Absolute CLI/session patterns use getOriginalCwd too. Relative
            // patterns are intentionally tested separately against liveCwd.
            assertEquals(paths.rootFor(RuleSource.CLI_ARG), worktreeRoot);
            assertEquals(paths.rootFor(RuleSource.COMMAND), worktreeRoot);
            assertEquals(paths.rootFor(RuleSource.SESSION), worktreeRoot);
            assertTrue(FilePermissionRuleMatcher.matches(
                "/absolute/**", RuleSource.SESSION,
                worktreeRoot.resolve("absolute/file.txt").toString(), paths,
                PermissionBehavior.ALLOW));
            assertFalse(FilePermissionRuleMatcher.matches(
                "/absolute/**", RuleSource.SESSION,
                launchRoot.resolve("absolute/file.txt").toString(), paths,
                PermissionBehavior.ALLOW));
        } finally {
            if (previous == null) CwdState.clearForTesting();
            else CwdState.setOriginalCwd(previous);
        }
    }

    @Test
    void driveAndUncRuleSpellingsRemainMatchableWithoutUsingJavaUnixPathSemantics() {
        PermissionEngine engine = new PermissionEngine();
        ToolPermissionContext drive = context(Path.of("/tmp/permission-root"),
            PermissionRule.withPattern("Edit", PermissionBehavior.ALLOW,
                RuleSource.SESSION, "C:/Users/*"));
        assertTrue(isAllowed(engine, drive, "C:\\Users\\Alice\\notes.txt"));

        ToolPermissionContext unc = context(Path.of("/tmp/permission-root"),
            PermissionRule.withPattern("Edit", PermissionBehavior.ALLOW,
                RuleSource.SESSION, "//server/share/**"));
        assertTrue(FilePermissionRuleMatcher.matches(
            "//server/share/**", RuleSource.SESSION, "//server/share/notes.txt",
            unc.pathContext(), PermissionBehavior.ALLOW));
    }

    @Test
    void readSafetyRunsBeforeWorkingDirectoryAutoAllowAndRules() {
        Path cwd = Path.of("/tmp/permission-root");
        PermissionEngine engine = new PermissionEngine();
        ToolPermissionContext context = ToolPermissionContext.builder()
            .workingDirectory(cwd)
            .mode(PermissionMode.DEFAULT)
            .rules(List.of(PermissionRule.of("Read", PermissionBehavior.ALLOW,
                RuleSource.SESSION)))
            .build();

        assertInstanceOf(PermissionDecision.Ask.class,
            engine.evaluateDetailed("Read", path("//server/share/file.txt"), context).decision());
        assertInstanceOf(PermissionDecision.Ask.class,
            engine.evaluateDetailed("Read", path("/net/share/file.txt"), context).decision());
        assertInstanceOf(PermissionDecision.Ask.class,
            engine.evaluateDetailed("Read", path("C:\\safe\\name~1.txt"), context).decision());
        assertInstanceOf(PermissionDecision.Allow.class,
            engine.evaluateDetailed("Read", path(cwd.resolve("normal.txt").toString()), context).decision());
    }

    @Test
    void invalidReadPathIsAskAndDoesNotBypassToModeOrCrash() {
        ToolPermissionContext context = ToolPermissionContext.of(Path.of("/tmp/permission-root"))
            .setMode(PermissionMode.BYPASS_PERMISSIONS);
        assertInstanceOf(PermissionDecision.Ask.class,
            new PermissionEngine().evaluateDetailed("Read", path("\0invalid"), context).decision());
    }

    @Test
    void changedFileReadCheckUsesOnlyLiveDenyAndNetworkGuards() {
        Path cwd = Path.of("/tmp/permission-root");
        ToolPermissionContext context = ToolPermissionContext.builder()
            .workingDirectory(cwd)
            .mode(PermissionMode.DONT_ASK)
            .rules(List.of(
                PermissionRule.withPattern("Read", PermissionBehavior.DENY,
                    RuleSource.SESSION, "secret/**"),
                PermissionRule.withPattern("Read", PermissionBehavior.ASK,
                    RuleSource.SESSION, "review/**")))
            .pathContext(PermissionPathContext.defaults(cwd))
            .build();
        PermissionGate gate = new PermissionGate(context);

        assertTrue(gate.isFileReadDenied(cwd.resolve("secret/token.txt").toString()));
        assertFalse(gate.isFileReadDenied(cwd.resolve("review/notes.txt").toString()),
            "background check must not convert ask rules or dontAsk mode into deny");
        assertTrue(gate.isFileReadDenied("//server/share/file.txt"));

        gate.replaceRules(PermissionBehavior.DENY, RuleSource.SESSION, List.of());
        assertFalse(gate.isFileReadDenied(cwd.resolve("secret/token.txt").toString()),
            "the predicate must observe live rule replacement");
    }

    private static ToolPermissionContext context(Path cwd, PermissionRule rule) {
        return ToolPermissionContext.builder()
            .workingDirectory(cwd)
            .mode(PermissionMode.DEFAULT)
            .rules(List.of(rule))
            .pathContext(PermissionPathContext.defaults(cwd))
            .build();
    }

    private static boolean isAllowed(PermissionEngine engine, ToolPermissionContext context,
                                      String path) {
        return engine.evaluateDetailed("Edit", path(path), context).decision()
            instanceof PermissionDecision.Allow;
    }

    private static ObjectNode path(String value) {
        return MAPPER.createObjectNode().put("file_path", value);
    }
}
