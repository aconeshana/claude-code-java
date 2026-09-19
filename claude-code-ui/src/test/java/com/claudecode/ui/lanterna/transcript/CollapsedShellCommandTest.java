package com.claudecode.ui.lanterna.transcript;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.tools.bash.BashTool;
import com.claudecode.tools.powershell.PowerShellTool;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link CollapsedShellCommand} to the 2.1.236 bundle's {@code oCT}/{@code EMv} classifier and
 * its {@code KDa}/{@code koi} hint formatting.
 */
class CollapsedShellCommandTest {

    private static CollapsedShellCommand.Kind bash(String command) {
        return CollapsedShellCommand.classify("Bash", command);
    }

    @Test
    void aCommandOfOnlyReadBuiltinsIsARead() {
        assertThat(bash("cat pom.xml")).isEqualTo(new CollapsedShellCommand.Kind(false, true, false));
        assertThat(bash("head -20 a.txt | tail -5"))
            .isEqualTo(new CollapsedShellCommand.Kind(false, true, false));
    }

    @Test
    void aCommandOfOnlySearchBuiltinsIsASearch() {
        assertThat(bash("rg --files-with-matches TODO"))
            .isEqualTo(new CollapsedShellCommand.Kind(true, false, false));
    }

    @Test
    void aCommandOfOnlyListBuiltinsIsAListing() {
        assertThat(bash("ls -la /tmp")).isEqualTo(new CollapsedShellCommand.Kind(false, false, true));
    }

    @Test
    void mixedKindsReportEveryKindTheyMatch() {
        assertThat(bash("grep -rn foo . | wc -l"))
            .isEqualTo(new CollapsedShellCommand.Kind(true, true, false));
    }

    @Test
    void oneForeignSegmentDemotesTheWholeCommand() {
        assertThat(bash("cd /tmp && cat notes.txt")).isEqualTo(CollapsedShellCommand.Kind.NONE);
        assertThat(bash("npm test")).isEqualTo(CollapsedShellCommand.Kind.NONE);
        assertThat(bash("./gradlew build")).isEqualTo(CollapsedShellCommand.Kind.NONE);
    }

    @Test
    void neutralWordsAreSkippedRatherThanDemoting() {
        assertThat(bash("echo '---' && cat a.txt"))
            .isEqualTo(new CollapsedShellCommand.Kind(false, true, false));
        // Neutral words alone never saw a real command, so nothing is claimed.
        assertThat(bash("echo hi")).isEqualTo(CollapsedShellCommand.Kind.NONE);
    }

    @Test
    void separatorsInsideQuotesOrSubstitutionsAreOrdinaryCharacters() {
        assertThat(bash("grep -n 'a && b' file.txt"))
            .isEqualTo(new CollapsedShellCommand.Kind(true, false, false));
        assertThat(bash("cat \"$(ls | head -1)\""))
            .isEqualTo(new CollapsedShellCommand.Kind(false, true, false));
    }

    @Test
    void commentsAreNotCommands() {
        assertThat(bash("# just looking\ncat a.txt"))
            .isEqualTo(new CollapsedShellCommand.Kind(false, true, false));
    }

    @Test
    void bashHeadWordsAreMatchedVerbatimWhilePowershellCaseFolds() {
        assertThat(bash("GREP -rn foo .")).isEqualTo(CollapsedShellCommand.Kind.NONE);
        assertThat(CollapsedShellCommand.classify("PowerShell", "Get-Content a.txt"))
            .isEqualTo(new CollapsedShellCommand.Kind(false, true, false));
        // PowerShell has no list category, so Get-ChildItem reports as both search and read.
        assertThat(CollapsedShellCommand.classify("PowerShell", "Get-ChildItem ."))
            .isEqualTo(new CollapsedShellCommand.Kind(true, true, false));
    }

    @Test
    void blankCommandsClaimNothing() {
        assertThat(bash("")).isEqualTo(CollapsedShellCommand.Kind.NONE);
        assertThat(bash(null)).isEqualTo(CollapsedShellCommand.Kind.NONE);
    }

    /** Past {@code V4e} 236 skips the parse and treats the whole command as one segment. */
    @Test
    void anOverlongCommandIsJudgedByItsFirstWordAlone() {
        assertThat(bash("cat " + "x".repeat(10_001)))
            .isEqualTo(new CollapsedShellCommand.Kind(false, true, false));
        assertThat(bash("npm " + "x".repeat(10_001))).isEqualTo(CollapsedShellCommand.Kind.NONE);
    }

    @Test
    void theHintPrefixesADollarAndFlattensEachLine() {
        assertThat(CollapsedShellCommand.preview("cd /tmp\n\ngrep -rn   foo   ."))
            .isEqualTo("$ cd /tmp\ngrep -rn foo .");
    }

    @Test
    void aCommentOnlyCommandShowsItsTitleInsteadOfItsSource() {
        assertThat(CollapsedShellCommand.displayHint("# checking the watchdog\n# and the eviction"))
            .isEqualTo("checking the watchdog");
        // A shebang is not a title, and neither is a comment followed by real work.
        assertThat(CollapsedShellCommand.displayHint("#!/bin/bash\nnpm test"))
            .isEqualTo("$ #!/bin/bash\nnpm test");
        assertThat(CollapsedShellCommand.displayHint("# setup\nnpm test"))
            .isEqualTo("$ # setup\nnpm test");
        // Only displayHint consults the title; the list and read branches never do.
        assertThat(CollapsedShellCommand.preview("# checking the watchdog"))
            .isEqualTo("$ # checking the watchdog");
    }

    @Test
    void theHintIsTruncatedAtThreeHundredCharacters() {
        String hint = CollapsedShellCommand.preview("echo " + "y".repeat(400));
        assertThat(hint).hasSize(300).endsWith("…");
    }

    @Test
    void onlyBashAndPowershellAreShellTools() {
        assertThat(CollapsedShellCommand.isShellTool("Bash")).isTrue();
        assertThat(CollapsedShellCommand.isShellTool("PowerShell")).isTrue();
        assertThat(CollapsedShellCommand.isShellTool("BashOutput")).isFalse();
        assertThat(CollapsedShellCommand.isShellTool("Read")).isFalse();
        assertThat(CollapsedShellCommand.isShellTool(null)).isFalse();
    }

    /**
     * The collapser used to keep its own copy of the cmdlet sets and never resolved aliases, so it
     * called {@code gci}/{@code sls}/{@code dir} plain shell commands while the teammate task board
     * — which does resolve them — called them searches. Delegating makes the two agree by
     * construction; this pins that they do.
     */
    @Test
    void powershellAliasesResolveExactlyAsTheToolsSideClassifierResolvesThem() {
        assertThat(ps("gci .")).isEqualTo(new CollapsedShellCommand.Kind(true, true, false));
        assertThat(ps("dir")).isEqualTo(new CollapsedShellCommand.Kind(true, true, false));
        assertThat(ps("sls TODO *.java"))
            .isEqualTo(new CollapsedShellCommand.Kind(true, false, false));

        assertThat(ps("gci .")).isEqualTo(viaTools("gci ."));
        assertThat(ps("sls TODO *.java")).isEqualTo(viaTools("sls TODO *.java"));
        assertThat(ps("dir")).isEqualTo(viaTools("dir"));
    }

    private static CollapsedShellCommand.Kind ps(String command) {
        return CollapsedShellCommand.classify("PowerShell", command);
    }

    /** The same answer, reached through the tools-side classifier the task board uses. */
    private static CollapsedShellCommand.Kind viaTools(String command) {
        PowerShellTool.SearchReadClassification c =
            PowerShellTool.classifySearchOrReadCommand(command);
        return new CollapsedShellCommand.Kind(c.isSearch(), c.isRead(), false);
    }

    /** Bash keeps its verbatim matching, and now reaches it through the shared classifier. */
    @Test
    void bashDelegationAgreesWithTheToolsSideClassifier() {
        for (String command : new String[] {
                "cat pom.xml", "ls -la /tmp", "grep -rn foo . | wc -l",
                "cd /tmp && cat notes.txt", "npm test", "GREP -rn foo ." }) {
            BashTool.SearchReadClassification c = BashTool.classifySearchOrReadCommand(command);
            assertThat(bash(command))
                .as("collapser and task board must agree about %s", command)
                .isEqualTo(new CollapsedShellCommand.Kind(c.isSearch(), c.isRead(), c.isList()));
        }
    }
}
