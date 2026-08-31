package com.claudecode.tools.bash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.claudecode.core.engine.PermissionUpdate;
import com.claudecode.core.model.PermissionModeKind;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.PermissionMode;
import com.claudecode.permissions.ToolPermissionContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;


class BashPermissionsTest {

    // Avoid macOS's /home automount symlink: the production containment check
    // intentionally validates symlink targets as well as the lexical path.
    private static final Path CWD = Path.of("/Users/test/project");

    private static ToolPermissionContext ctx() {
        return ToolPermissionContext.of(CWD);
    }

    private static PermissionDecision.Ask ask(String command) {
        PermissionDecision d = BashPermissions.check(command, ctx());
        assertInstanceOf(PermissionDecision.Ask.class, d, "expected Ask for: " + command);
        return (PermissionDecision.Ask) d;
    }

    @Test
    void emptyAndIncompleteCommandsDenied() {
        assertInstanceOf(PermissionDecision.Deny.class, BashPermissions.check("", ctx()));
        assertInstanceOf(PermissionDecision.Deny.class, BashPermissions.check("ls |", ctx()));
    }

    @Test
    void uncPathBlockedWithResolvedPath() {
        PermissionDecision.Ask a = ask("rm //evil/share");
        assertEquals("//evil/share", a.blockedPath());
    }

    @Test
    void tildeVariantBlocked() {
        PermissionDecision.Ask a = ask("rm ~root/secret");
        assertEquals("~root/secret", a.blockedPath());
    }

    @Test
    void shellExpansionBlocked() {
        PermissionDecision.Ask a = ask("cp $HOME/x /y");
        assertEquals("$HOME/x", a.blockedPath());
    }

    @Test
    void writeGlobBlocked() {
        PermissionDecision.Ask a = ask("rm *.log");
        assertEquals("*.log", a.blockedPath());
    }

    @Test
    void readGlobAllowed() {
        // Read-only glob resolves its base directory and is permitted.
        assertInstanceOf(PermissionDecision.Allow.class, BashPermissions.check("cat *.log", ctx()));
    }

    @Test
    void rgGlobFlagValueIsPatternNotPath() {

        // glob PATTERN, not a filesystem path, so the single token that follows it
        // is the search pattern — never opened as a file. Thus `rg -g '*.go'
        // //evil/share` must NOT be flagged on //evil/share; the command searches
        // the cwd and is allowed. With the old single shared flag set (grep+rg
        // merged), -g was not recognized as flag-with-args, so '*.go' became the
        // pattern and //evil/share a path → wrongly returned Ask(blocked).
        assertInstanceOf(PermissionDecision.Allow.class,
            BashPermissions.check("rg -g '*.go' //evil/share", ctx()));
    }

    @Test
    void dangerousRemovalPathBlocked() {
        PermissionDecision.Ask a = ask("rm -rf /");
        assertEquals("/", a.blockedPath());
    }

    @Test
    void flagBearingCpMvLnInstallRequiresApproval() {

        PermissionDecision.Ask a = ask("cp -r a b");
        assertNull(a.blockedPath());
        assertNull(ask("mv -f a b").blockedPath());
        assertNull(ask("ln -s a b").blockedPath());
        assertNull(ask("install -m 644 a b").blockedPath());
    }

    @Test
    void cdCompoundWriteRequiresApproval() {
        PermissionDecision.Ask a = ask("cd sub && rm foo");
        assertNull(a.blockedPath());
    }

    @Test
    void normalCommandsClassifiedAsBefore() {
        assertInstanceOf(PermissionDecision.Allow.class, BashPermissions.check("git status", ctx()));
        assertInstanceOf(PermissionDecision.Allow.class, BashPermissions.check("ls -la", ctx()));
// Default mode does not auto-allow writes, even inside the cwd.
        PermissionDecision.Ask a = ask("cp a.txt b.txt");
        assertEquals("/Users/test/project/a.txt", a.blockedPath());
    }

    @Test
    void redirectTargetValidated() {
        // A redirect is a write operation. In default mode it asks even when
        // the target is inside the cwd and carries the resolved blocked path.
        assertEquals("/Users/test/project/out.txt",
            ask("echo hi > out.txt").blockedPath());

        // A dangerous redirect target IS still caught (UNC path on the `>` side).
        PermissionDecision.Ask blocked = ask("echo hi > //evil/share");
        assertEquals("//evil/share", blocked.blockedPath());
    }

    @Test
    void noContextSkipsPathConstraints() {
        // Legacy shape (permCtx == null) preserves the original classification
        // without running path validation.
        assertInstanceOf(PermissionDecision.Allow.class, BashPermissions.check("git status"));
        assertInstanceOf(PermissionDecision.Ask.class, BashPermissions.check("rm file.txt"));
    }

    @Test
    void writeInsideWorkingDirRequiresAcceptEditsAndCarriesBlockedPath() {
        PermissionDecision.Ask a = ask("rm sub/file.txt");
        assertEquals("/Users/test/project/sub/file.txt", a.blockedPath());
    }

    @Test
    void acceptEditsAllowsSupportedWriteInsideWorkingDirectory() {
        ToolPermissionContext acceptEdits = ctx().setMode(PermissionMode.ACCEPT_EDITS);
        assertInstanceOf(PermissionDecision.Allow.class,
            BashPermissions.check("rm sub/file.txt", acceptEdits));
    }

    @Test
    void acceptEditsDoesNotAllowWriteOutsideWorkingDirectories() {
        ToolPermissionContext acceptEdits = ctx().setMode(PermissionMode.ACCEPT_EDITS);
        PermissionDecision decision = BashPermissions.check(
            "rm -f /private/tmp/cc197-can-use-tool-nonexistent", acceptEdits);
        PermissionDecision.Ask ask = assertInstanceOf(PermissionDecision.Ask.class, decision);
        assertEquals("/private/tmp/cc197-can-use-tool-nonexistent", ask.blockedPath());
    }

    @Test
    void writeOutsideWorkingDirectoriesCarriesResolvedBlockedPath() {
        PermissionDecision.Ask a = ask("rm -f /private/tmp/cc197-can-use-tool-nonexistent");
        assertEquals("/private/tmp/cc197-can-use-tool-nonexistent", a.blockedPath());
        assertEquals(List.of(
            new PermissionUpdate.AddDirectories(
                List.of("/private/tmp"), PermissionUpdate.Destination.SESSION),
            new PermissionUpdate.SetMode(
                PermissionModeKind.ACCEPT_EDITS, PermissionUpdate.Destination.SESSION)),
            a.suggestions());
    }

    @Test
    void readOutsideWorkingDirectoriesCarriesResolvedBlockedPath() {
        PermissionDecision.Ask a = ask("cat /private/tmp/cc197-can-use-tool-nonexistent");
        assertEquals("/private/tmp/cc197-can-use-tool-nonexistent", a.blockedPath());
        assertEquals(List.of(new PermissionUpdate.AddRules(
            List.of(new PermissionUpdate.RuleValue("Read", "//private/tmp/**")),
            PermissionUpdate.Behavior.ALLOW,
            PermissionUpdate.Destination.SESSION)), a.suggestions());
    }

    @Test
    void createOutsideWorkingDirectoriesCarriesDirectoryThenAcceptEditsSuggestions() {
        PermissionDecision.Ask a = ask("touch /private/tmp/cc197-tty-permission-approve-marker");
        assertEquals(List.of(
            new PermissionUpdate.AddDirectories(
                List.of("/private/tmp"), PermissionUpdate.Destination.SESSION),
            new PermissionUpdate.SetMode(
                PermissionModeKind.ACCEPT_EDITS, PermissionUpdate.Destination.SESSION)),
            a.suggestions());
    }

    @Test
    void redirectionCarriesOnlyDirectorySuggestionLikeReleased197() {
        PermissionDecision.Ask a = ask("echo hi > /private/tmp/cc197-output.txt");
        assertEquals(List.of(new PermissionUpdate.AddDirectories(
            List.of("/private/tmp"), PermissionUpdate.Destination.SESSION)),
            a.suggestions());
    }

    @Test
    void gitDiffNoIndexExtractsPaths() {
        // git diff --no-index takes arbitrary filesystem paths; the extractor
        // routes them through validation, so a UNC path is still surfaced.
        PermissionDecision.Ask a = ask("git diff --no-index a //evil/share");
        assertEquals("//evil/share", a.blockedPath());
        // A benign no-index diff is classified read and allowed.
        assertInstanceOf(PermissionDecision.Allow.class,
            BashPermissions.check("git diff --no-index a /Users/test/project/x", ctx()));
    }

    @Test
    void findExtractsStartPath() {
        // find's start path is validated; a UNC start path is surfaced.
        PermissionDecision.Ask a = ask("find //evil/share -name secret");
        assertEquals("//evil/share", a.blockedPath());

        // files even though the base command is normally a search.
        assertInstanceOf(PermissionDecision.Ask.class,
            BashPermissions.check("find . -name x -delete", ctx()));
    }

    @Test
    void grepExtractsPathAfterPattern() {
        // The pattern is consumed first; the trailing path is validated.
        PermissionDecision.Ask a = ask("grep pat //evil/share");
        assertEquals("//evil/share", a.blockedPath());
    }

    @Test
    void readableFileExtractionReusesGrepQuotingAndPatternRules(@TempDir Path project)
            throws Exception {
        Path fixture = Files.writeString(project.resolve("with space.txt"), "match\n");

        assertEquals(List.of(fixture), BashPermissions.extractReadableFilePaths(
            "grep match 'with space.txt'", project));
    }

    @Test
    void doubleDashExtractorCapturesRealTarget() {
        // POSIX -- end-of-options must not be dropped; the real target is extracted
        // and the dangerous-removal guard still fires on "/".
        PermissionDecision.Ask a = ask("rm -- -rf /");
        assertEquals("/", a.blockedPath());
    }

    @Test
    void timeoutWrapperStrippedBeforeValidation() {
        // A wrapped `rm -rf /` must reach validation through the wrapper stripper.
        PermissionDecision.Ask a = ask("timeout 10 rm -rf /");
        assertEquals("/", a.blockedPath());
    }

    @Test
    void redirectTargetValidatedForReadCommand() {
        // A read command writing via redirection to a blocked target is surfaced.
        PermissionDecision.Ask a = ask("ls > //evil/share");
        assertEquals("//evil/share", a.blockedPath());
    }
}
