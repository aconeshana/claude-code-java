package com.claudecode.permissions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;


class PermissionEngineParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private PermissionEngine engine;

    @BeforeEach
    void setUp() {
        engine = new PermissionEngine();
    }

    private static JsonNode input(String key, String value) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put(key, value);
        return node;
    }

    // Finding 1: content (path) rules are bucketed by the canonical file-tool name
    // (FILE_EDIT_TOOL_NAME="Edit" / FILE_READ_TOOL_NAME="Read"). An "Edit(...)" rule
    // must also match Write / NotebookEdit invocations. Only content rules bucket —
    // a bare rule keeps strict name matching. The file is created so the safety gate

    @Test
    void editBucketRuleAllowsWriteAndNotebookEditTools() throws Exception {
        // Existing files in the project dir (not /tmp) so the safety gate, which


        // file matcher roots relative path rules at it; a literal "." would make
        // isWithin(candidate, ".") fail and the allow rule would never fire.
        Path cwd = Path.of(".").toAbsolutePath().normalize();
        Path rel = cwd.resolve("target/perm-rel.txt");
        Files.writeString(rel, "x");

        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(cwd)
            .mode(PermissionMode.DEFAULT)
            .rules(List.of(PermissionRule.withPattern("Edit", PermissionBehavior.ALLOW,
                RuleSource.SESSION, "target/perm-rel.txt")))
            .build();
        // Write / NotebookEdit in DEFAULT mode are not auto-allowed inside the WD,
        // so Allow can only come from the Edit-bucket rule matching these tools.
        assertInstanceOf(PermissionDecision.Allow.class,
            engine.evaluateDetailed("Write", input("file_path", rel.toString()), ctx).decision());
        assertInstanceOf(PermissionDecision.Allow.class,
            engine.evaluateDetailed("NotebookEdit", input("notebook_path", rel.toString()), ctx).decision());
        // The same rule also matches a literal Edit invocation.
        assertInstanceOf(PermissionDecision.Allow.class,
            engine.evaluateDetailed("Edit", input("file_path", rel.toString()), ctx).decision());
    }

    // Finding 4: trailing /** must be glob-aware, not a literal prefix compare.
    // "foo-*/**" must match "foo-bar/baz" (segment glob in the prefix) and the
    // bare "foo-bar" directory, but must NOT match a mid-path "x/foo-bar/baz".
    @Test
    void globPrefixDoubleStarMatchesSegmentGlob() {
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("/wd"))
            .mode(PermissionMode.DEFAULT)
            .rules(List.of(PermissionRule.withPattern("Edit", PermissionBehavior.ALLOW,
                RuleSource.SESSION, "foo-*/**")))
            .build();
        assertInstanceOf(PermissionDecision.Allow.class,
            engine.evaluateDetailed("Edit", input("file_path", "/wd/foo-bar/baz"), ctx).decision());
        assertInstanceOf(PermissionDecision.Allow.class,
            engine.evaluateDetailed("Edit", input("file_path", "/wd/foo-bar"), ctx).decision());
        assertInstanceOf(PermissionDecision.Ask.class,
            engine.evaluateDetailed("Edit", input("file_path", "/wd/x/foo-bar/baz"), ctx).decision());
        assertInstanceOf(PermissionDecision.Ask.class,
            engine.evaluateDetailed("Edit", input("file_path", "/wd/foobar/baz"), ctx).decision());
    }


// runs BEFORE the 1.7 safety check). Editing on would
    // otherwise hit the safety gate and return Ask.
    @Test
    void sessionClaudeFolderAllowBypassesSafety(@TempDir Path dir) throws Exception {
        Path cwd = dir.toRealPath();
        Path settings = cwd.resolve(".claude/settings.json");
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, "{}");

        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(cwd)
            .mode(PermissionMode.DEFAULT)
            .rules(List.of(PermissionRule.withPattern("Edit", PermissionBehavior.ALLOW,
                RuleSource.SESSION, "/.claude/**")))
            .build();
        assertInstanceOf(PermissionDecision.Allow.class,
            engine.evaluateDetailed("Write", input("file_path", settings.toString()), ctx).decision());
    }

    // Negative: the 1.6 bypass is restricted to SESSION-sourced rules and to
    // patterns ending in /**, so a project-scoped .claude rule (or a session rule
    // without /**) must NOT bypass the safety gate.
    @Test
    void nonSessionClaudeFolderRuleDoesNotBypassSafety(@TempDir Path dir) throws Exception {
        Path cwd = dir.toRealPath();
        Path settings = cwd.resolve(".claude/settings.json");
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, "{}");

        ToolPermissionContext projectScoped = ToolPermissionContext.builder()
            .workingDirectory(cwd)
            .mode(PermissionMode.DEFAULT)
            .rules(List.of(PermissionRule.withPattern("Edit", PermissionBehavior.ALLOW,
                RuleSource.PROJECT_SETTINGS, "/.claude/**")))
            .build();
        assertInstanceOf(PermissionDecision.Ask.class,
            engine.evaluateDetailed("Write", input("file_path", settings.toString()), projectScoped).decision());

        ToolPermissionContext noGlob = ToolPermissionContext.builder()
            .workingDirectory(cwd)
            .mode(PermissionMode.DEFAULT)
            .rules(List.of(PermissionRule.withPattern("Edit", PermissionBehavior.ALLOW,
                RuleSource.SESSION, "/.claude")))
            .build();
        assertInstanceOf(PermissionDecision.Ask.class,
            engine.evaluateDetailed("Write", input("file_path", settings.toString()), noGlob).decision());
    }


    // ordinary path-safety asks. In bypassPermissions, sensitive file writes and
    // untrusted network reads therefore fall through to the mode Allow. Explicit
    // ask/deny rules remain higher-priority checks.
    @Test
    void bypassModeSkipsOrdinaryPathSafetyChecks(@TempDir Path dir) {
        Path cwd = dir.toAbsolutePath().normalize();
        ToolPermissionContext bypass = ToolPermissionContext.builder()
            .workingDirectory(cwd)
            .mode(PermissionMode.BYPASS_PERMISSIONS)
            .build();

        assertInstanceOf(PermissionDecision.Allow.class,
            engine.evaluateDetailed("Write",
                input("file_path", cwd.resolve(".git/config").toString()), bypass).decision());
        assertInstanceOf(PermissionDecision.Allow.class,
            engine.evaluateDetailed("Write",
                input("file_path", cwd.resolve(".claude/settings.json").toString()), bypass).decision());
        assertInstanceOf(PermissionDecision.Allow.class,
            engine.evaluateDetailed("Read",
                input("file_path", "//server/share/file.txt"), bypass).decision());
    }

    @Test
    void defaultModeStillAsksForSensitivePaths(@TempDir Path dir) {
        Path cwd = dir.toAbsolutePath().normalize();
        ToolPermissionContext defaults = ToolPermissionContext.builder()
            .workingDirectory(cwd)
            .mode(PermissionMode.DEFAULT)
            .build();

        assertInstanceOf(PermissionDecision.Ask.class,
            engine.evaluateDetailed("Write",
                input("file_path", cwd.resolve(".git/config").toString()), defaults).decision());
        assertInstanceOf(PermissionDecision.Ask.class,
            engine.evaluateDetailed("Read",
                input("file_path", "//server/share/file.txt"), defaults).decision());
    }

    @Test
    void bypassModeStillRespectsExplicitAskAndDenyRules(@TempDir Path dir) {
        Path cwd = dir.toAbsolutePath().normalize();
        Path sensitive = cwd.resolve(".git/config");
        ToolPermissionContext explicitAsk = ToolPermissionContext.builder()
            .workingDirectory(cwd)
            .mode(PermissionMode.BYPASS_PERMISSIONS)
            .rules(List.of(PermissionRule.of(
                "Write", PermissionBehavior.ASK, RuleSource.PROJECT_SETTINGS)))
            .build();
        ToolPermissionContext explicitDeny = ToolPermissionContext.builder()
            .workingDirectory(cwd)
            .mode(PermissionMode.BYPASS_PERMISSIONS)
            .rules(List.of(PermissionRule.of(
                "Write", PermissionBehavior.DENY, RuleSource.POLICY_SETTINGS)))
            .build();

        assertInstanceOf(PermissionDecision.Ask.class,
            engine.evaluateDetailed("Write",
                input("file_path", sensitive.toString()), explicitAsk).decision());
        assertInstanceOf(PermissionDecision.Deny.class,
            engine.evaluateDetailed("Write",
                input("file_path", sensitive.toString()), explicitDeny).decision());
    }

    // Finding 8: an unrecognized content rule (input has no known field such as
    // file_path/command/content) must NOT match the whole serialized JSON — it
    // returns null and falls through to the mode decision (Ask in DEFAULT mode),
// instead of letting input.toString spuriously match a glob.
    @Test
    void unrecognizedContentRuleDoesNotMatchWholeJson() {
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.DEFAULT)
            .rules(List.of(PermissionRule.withPattern("StrangeTool", PermissionBehavior.ALLOW,
                RuleSource.SESSION, "*")))
            .build();
        // No recognized field → the rule must not fire.
        ObjectNode weird = MAPPER.createObjectNode();
        weird.put("weird", "value");
        assertInstanceOf(PermissionDecision.Ask.class,
            engine.evaluateDetailed("StrangeTool", weird, ctx).decision());
        // A recognized "content" field IS extracted and matched.
        ObjectNode withContent = MAPPER.createObjectNode();
        withContent.put("content", "hello");
        ToolPermissionContext contentCtx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.DEFAULT)
            .rules(List.of(PermissionRule.withPattern("StrangeTool", PermissionBehavior.ALLOW,
                RuleSource.SESSION, "hello")))
            .build();
        assertInstanceOf(PermissionDecision.Allow.class,
            engine.evaluateDetailed("StrangeTool", withContent, contentCtx).decision());
    }

    @Test
    void readBucketRuleAllowsGrepTool() {
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("/wd"))
            .mode(PermissionMode.DEFAULT)
            .rules(List.of(PermissionRule.withPattern("Read", PermissionBehavior.ALLOW,
                RuleSource.SESSION, "data.log")))
            .build();
        assertInstanceOf(PermissionDecision.Allow.class,
            engine.evaluateDetailed("Grep", input("file_path", "/wd/data.log"), ctx).decision());
    }


    // fetches rules by the canonical "Edit" bucket name, never "Write".
    @Test
    void writeRuleDoesNotMatchEditTool() throws Exception {
        Path rel = Path.of("target", "perm-rel.txt");
        Files.writeString(rel, "x");

        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.DEFAULT)
            .rules(List.of(PermissionRule.withPattern("Write", PermissionBehavior.ALLOW,
                RuleSource.SESSION, "target/perm-rel.txt")))
            .build();
        assertInstanceOf(PermissionDecision.Ask.class,
            engine.evaluateDetailed("Edit", input("file_path", rel.toString()), ctx).decision());
    }

    // Finding 2: legacy :* prefix syntax (e.g. "git:*") was dead — the regex builder
    // turned it into the literal "git:*" and matched nothing. It must now match the
    // command itself or a "command " word-boundary prefix.
    @Test
    void colonStarPrefixMatchesBashCommand() {
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .mode(PermissionMode.DEFAULT)
            .rules(List.of(PermissionRule.withPattern("Bash", PermissionBehavior.ALLOW,
                RuleSource.SESSION, "git:*")))
            .build();
        assertInstanceOf(PermissionDecision.Allow.class,
            engine.evaluateDetailed("Bash", input("command", "git status"), ctx).decision());
        assertInstanceOf(PermissionDecision.Allow.class,
            engine.evaluateDetailed("Bash", input("command", "git"), ctx).decision());
        // Word boundary: "gitx" must not satisfy the "git:*" prefix.
        assertInstanceOf(PermissionDecision.Ask.class,
            engine.evaluateDetailed("Bash", input("command", "gitx"), ctx).decision());
        // Unrelated command is not allowed.
        assertInstanceOf(PermissionDecision.Ask.class,
            engine.evaluateDetailed("Bash", input("command", "rm -rf /"), ctx).decision());
    }

    // Finding 5: DENY/ASK path rules must also block the resolved symlink form

    @Test
    void denyRuleBlocksSymlinkTarget(@TempDir Path dir) throws Exception {
        // Follow links so the rule root and the symlink-resolved candidate share the
        // same canonical spelling (avoids the macOS /tmp -> /private/tmp rewrite).
        Path realDir = dir.toRealPath();
        Path secret = realDir.resolve("secret.txt");
        Files.writeString(secret, "x");
        Path link = realDir.resolve("link.txt");
        Files.createSymbolicLink(link, secret);

        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(realDir)
            .mode(PermissionMode.DEFAULT)
            .rules(List.of(PermissionRule.withPattern("Read", PermissionBehavior.DENY,
                RuleSource.SESSION, "secret.txt")))
            .build();
        // Reading through the symlink must still be denied.
        assertInstanceOf(PermissionDecision.Deny.class,
            engine.evaluateDetailed("Read", input("file_path", link.toString()), ctx).decision());
    }
}
