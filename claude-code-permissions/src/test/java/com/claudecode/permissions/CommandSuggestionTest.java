package com.claudecode.permissions;

import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "don't ask again" row writes a persistent allow rule, so what this derives is what the
 * user is agreeing to. Every case here is a rule that must either be right or not be offered.
 */
class CommandSuggestionTest {

    private static final String CWD = "/Users/dev/Projects/ontology";

    @Test
    void prefixRuleIsKeyedOnTheCommandNotOnALeadingVariableAssignment() {
        // The reported defect: the dialog offered "don't ask again for total=0 commands",
        // having taken the first whitespace token of a counter initialiser as a command name.
        // A rule of `total=0:*` matches nothing, so the row was pure misdirection.
        assertEquals("npm:*", ruleFor("NODE_ENV=test npm run lint"));
        assertEquals("npm commands in ontology", labelFor("NODE_ENV=test npm run lint"));
        assertEquals("grep:*", ruleFor("LC_ALL=C grep -a foo bar"));
        assertEquals("make:*", ruleFor("CC=clang CXX=clang++ make -j8"),
            "every leading assignment is skipped, not just the first");
    }

    @Test
    void multiLineScriptGetsNoPersistentRuleOffer() {
        // This is the shape that produced `total=0:*`. The command that matters lives inside
        // a loop body; naming it needs the bash parse the released client has and we do not,
        // so the row is withheld rather than filled in with the nearest token.
        String script = """
            total=0
            for f in *.md; do
              total=$((total + 1))
            done
            echo "$total"
            """;

        assertTrue(CommandSuggestion.forBash(script, CWD).isEmpty());
    }

    @Test
    void firstCommandOfAListCarriesTheRule() {
        assertEquals("cd:*", ruleFor("cd packages/core && npm test"));
        assertEquals("git:*", ruleFor("git status; git diff"));
        assertEquals("rg:*", ruleFor("rg --files | head -20"));
    }

    @Test
    void aBareCommandGetsAnExactRuleRatherThanAPrefixRule() {
        assertEquals("ls", ruleFor("ls"));
        assertEquals("\"ls\" in ontology", labelFor("ls"));
        assertEquals("git:*", ruleFor("git status"), "arguments mean a prefix rule");
    }

    @Test
    void redirectionIsNotMistakenForTheCommandWord() {
        assertEquals("make", ruleFor("make > build.log"),
            "stripping the redirection leaves a bare command, so the rule stays exact");
        assertEquals("tar:*", ruleFor("tar czf out.tgz src > /dev/null"));
    }

    @Test
    void commandSubstitutionIsRefusedBecauseTheRuleCouldNotBeRead() {
        // A rule keyed on literal text would go on allowing whatever the substitution
        // expands to next time. The 2.1.197 pre-check called this out by name
        // (`command_injection_detected`) and declined to produce a prefix.
        assertTrue(CommandSuggestion.forBash("grep $(cat patterns.txt) .", CWD).isEmpty());
        assertTrue(CommandSuggestion.forBash("echo `whoami`", CWD).isEmpty());
        assertTrue(CommandSuggestion.forBash("ls ${HOME}", CWD).isEmpty());
    }

    @Test
    void interpreterInvocationsAreRefusedBecauseThePrefixWouldAllowAnything() {
        assertTrue(CommandSuggestion.forBash("bash deploy.sh", CWD).isEmpty());
        assertTrue(CommandSuggestion.forBash("sh -c 'rm -rf build'", CWD).isEmpty());
        assertTrue(CommandSuggestion.forBash("env FOO=1 ./run", CWD).isEmpty());
        assertTrue(CommandSuggestion.forBash("sudo systemctl restart nginx", CWD).isEmpty());
        assertTrue(CommandSuggestion.forBash("python3 train.py", CWD).isEmpty());
    }

    @Test
    void controlKeywordsAreRefusedRatherThanProposedAsCommands() {
        assertTrue(CommandSuggestion.forBash("if test -f x; then echo y; fi", CWD).isEmpty());
        assertTrue(CommandSuggestion.forBash("while read l; do echo $l; done", CWD).isEmpty(),
            "a rule of `while:*` would be nonsense");
    }

    @Test
    void absolutePathsAndDotSlashStayUsableCommandWords() {
        assertEquals("/usr/bin/time:*", ruleFor("/usr/bin/time -v make"));
        assertEquals("./gradlew:*", ruleFor("./gradlew test"));
    }

    @Test
    void blankAndOptionOnlyInputYieldNothing() {
        assertTrue(CommandSuggestion.forBash(null, CWD).isEmpty());
        assertTrue(CommandSuggestion.forBash("   ", CWD).isEmpty());
        assertTrue(CommandSuggestion.forBash("--help", CWD).isEmpty(),
            "an option leader is not a command name");
        assertTrue(CommandSuggestion.forBash("FOO=1", CWD).isEmpty(),
            "assignments only, so there is no command to name");
    }

    private static String ruleFor(String command) {
        return require(command).ruleContent();
    }

    private static String labelFor(String command) {
        return require(command).label();
    }

    private static CommandSuggestion require(String command) {
        Optional<CommandSuggestion> suggestion = CommandSuggestion.forBash(command, CWD);
        assertTrue(suggestion.isPresent(), () -> "expected a suggestion for: " + command);
        return suggestion.get();
    }
}
