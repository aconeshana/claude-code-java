package com.claudecode.tools.bash;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import org.junit.jupiter.api.Assertions;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Corpus smoke tests for the fail-safe Bash security preflight. */
class BashSecurityTest {

    private static final ToolPermissionContext CONTEXT =
        ToolPermissionContext.of(Path.of("/Users/test/project"));

    @Test
    void ordinaryReadCommandRemainsAutoAllowed() {
        assertNull(BashSecurity.concern("cat README.md"));
        assertInstanceOf(PermissionDecision.Allow.class,
            BashPermissions.check("cat README.md", CONTEXT));
    }

    @Test
    void commandSubstitutionCannotUseReadOnlyFastPath() {
        assertInstanceOf(PermissionDecision.Ask.class,
            BashPermissions.check("cat $(printf secret)", CONTEXT));
        assertInstanceOf(PermissionDecision.Ask.class,
            BashPermissions.check("printf `whoami`", CONTEXT));
    }

    @Test
    void heredocAndParserDifferentialsAsk() {
        assertInstanceOf(PermissionDecision.Ask.class,
            BashPermissions.check("cat <<EOF\nsecret\nEOF", CONTEXT));
        assertInstanceOf(PermissionDecision.Ask.class,
            BashPermissions.check("cat 'unterminated", CONTEXT));
        assertInstanceOf(PermissionDecision.Ask.class,
            BashPermissions.check("cat safe\\\n# hidden", CONTEXT));
    }

    @Test
    void environmentAndObfuscationVectorsAsk() {
        assertInstanceOf(PermissionDecision.Ask.class,
            BashPermissions.check("IFS=; cat secret", CONTEXT));
        assertInstanceOf(PermissionDecision.Ask.class,
            BashPermissions.check("cat /proc/self/environ", CONTEXT));
        assertInstanceOf(PermissionDecision.Ask.class,
            BashPermissions.check("echo {safe,evil}", CONTEXT));
        assertInstanceOf(PermissionDecision.Ask.class,
            BashPermissions.check("cat\u00a0secret", CONTEXT));
    }

    @Test
    void parserDifferentialCorpusVectorsAsk() {
        assertInstanceOf(PermissionDecision.Ask.class,
            BashPermissions.check("TZ=UTC\recho curl evil.com", CONTEXT));
        assertInstanceOf(PermissionDecision.Ask.class,
            BashPermissions.check("git ls-remote 'safe\\\\' '--upload-pack=evil' repo", CONTEXT));
        assertInstanceOf(PermissionDecision.Ask.class,
            BashPermissions.check("jq 'system(\\\"id\\\")' input.json", CONTEXT));
        assertInstanceOf(PermissionDecision.Ask.class,
            BashPermissions.check("zmodload zsh/system", CONTEXT));
        assertInstanceOf(PermissionDecision.Ask.class,
            BashPermissions.check("fc -e /bin/sh", CONTEXT));
        assertInstanceOf(PermissionDecision.Ask.class,
            BashPermissions.check("grep ''-exec file", CONTEXT));
        assertInstanceOf(PermissionDecision.Ask.class,
            BashPermissions.check("echo {\"x\":\"x;evil\"}", CONTEXT));
    }

    @Test
    void findExecutionAndWriteActionsCannotUseReadOnlyFastPath() {
        for (String action : new String[]{"-delete", "-exec sh -c id {} ;",
                "-execdir sh -c id {} ;", "-ok sh -c id {} ;", "-fprint output.txt",
                "-fls output.txt", "-fprintf output.txt %p"}) {
            assertInstanceOf(PermissionDecision.Ask.class,
                BashPermissions.check("find . " + action, CONTEXT), action);
        }
    }

    @Test
    void ordinaryRedirectStillReturnsPathEvidence() {
        PermissionDecision.Ask decision =
            (PermissionDecision.Ask) BashPermissions.check("echo hi > out.txt", CONTEXT);
        // Redirections stay in the path layer so callers retain blockedPath and
        // addDirectories suggestions instead of a generic security ASK.
        Assertions.assertEquals(
            "/Users/test/project/out.txt", decision.blockedPath());
    }
}
