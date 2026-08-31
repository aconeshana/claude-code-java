package com.claudecode.commands.impl.git;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class InitCommandTest {


    private static final Path TS_INIT = referenceSourceRoot()
        .resolve("src/commands/init.ts");

    private static Path referenceSourceRoot() {
        String configured = System.getenv("CLAUDE_CODE_REFERENCE_SOURCE_ROOT");
        return StringUtils.isBlank(configured)
            ? Path.of(System.getProperty("user.home"), "claude-code-reference")
            : Path.of(configured);
    }


    private static String extractTsTemplate(String name) throws Exception {
        String src = Files.readString(TS_INIT);
        String marker = "const " + name + " = `";
        int i = src.indexOf(marker);
        assertTrue(i >= 0, "TS constant not found: " + name);
        i += marker.length();
        int j = i;
        while (true) {
            j = src.indexOf('`', j);
            if (src.charAt(j - 1) != '\\') break;
            j++;
        }
        return src.substring(i, j)
            .replace("\\`", "`")
            .replace("\\${", "${")
            .replace("\\\\", "\\");
    }

    @Test
    void oldInitPrompt_isByteIdenticalToTsSource() throws Exception {
        assumeTrue(Files.isRegularFile(TS_INIT), "TS snapshot not present — skipping fidelity check");
        assertEquals(InitCommand.OLD_INIT_PROMPT, extractTsTemplate("OLD_INIT_PROMPT"));
    }

    @Test
    void newInitPrompt_isByteIdenticalToTsSource() throws Exception {
        assumeTrue(Files.isRegularFile(TS_INIT), "TS snapshot not present — skipping fidelity check");
        assertEquals(InitCommand.NEW_INIT_PROMPT, extractTsTemplate("NEW_INIT_PROMPT"));
    }

    @Test
    void promptFor_switchesOnTheGate() {
        // Regression guard: a prior version switched only the description on
// CLAUDE_CODE_NEW_INIT and always returned OLD from execute.
        assertSame(InitCommand.OLD_INIT_PROMPT, InitCommand.promptFor(false));
        assertSame(InitCommand.NEW_INIT_PROMPT, InitCommand.promptFor(true));
    }

    @Test
    void newInitPrompt_containsAllEightPhases() {
        String p = InitCommand.NEW_INIT_PROMPT;
        for (String phase : new String[] {
                "## Phase 1: Ask what to set up",
                "## Phase 2: Explore the codebase",
                "## Phase 3: Fill in the gaps",
                "## Phase 4: Write CLAUDE.md (if user chose project or both)",
                "## Phase 5: Write CLAUDE.local.md (if user chose personal or both)",
                "## Phase 6: Suggest and create skills (if user chose \"Skills + hooks\" or \"Skills only\")",
                "## Phase 7: Suggest additional optimizations",
                "## Phase 8: Summary and next steps"}) {
            assertTrue(Strings.CS.contains(p, phase), "missing phase header: " + phase);
        }
    }

    @Test
    void execute_returnsShouldQuery_notShouldExit() {
        CommandResult r = new InitCommand().execute(CommandContext.minimal(), "");
        assertTrue(r.shouldQuery(),   "should send prompt as query (TS 'prompt' type)");
        assertFalse(r.shouldExit(),   "must not exit the REPL");
        assertEquals("analyzing your codebase", r.promptInvocation().progressMessage());
        assertEquals("builtin", r.promptInvocation().source());
        assertEquals("init", r.promptInvocation().userFacingName());
    }

    @Test
    void prompt_matchesTsOldInitPromptKey() {

        String prompt = InitCommand.OLD_INIT_PROMPT;
        assertTrue(Strings.CS.contains(prompt, "CLAUDE.md"),
            "prompt must mention CLAUDE.md");
        assertTrue(Strings.CS.contains(prompt, "Commands that will be commonly used"),
            "must describe section 1: commands");
        assertTrue(Strings.CS.contains(prompt, "High-level code architecture"),
            "must describe section 2: architecture");
        assertTrue(Strings.CS.contains(prompt, "do not repeat yourself"),
            "must include the no-repetition instruction");
        assertTrue(Strings.CS.contains(prompt, "# CLAUDE.md"),
            "must include the required file prefix verbatim");
        assertTrue(Strings.CS.contains(prompt, "claude.ai/code"),
            "must include the canonical tool reference");
    }

    @Test
    void output_isNotBlank() {
        String out = new InitCommand().execute(CommandContext.minimal(), "").output();
        assertNotNull(out);
        assertFalse(StringUtils.isBlank(out));
    }

    @Test
    void commandResultForQuery_factory() {
// Regression guard: forQuery must set shouldQuery=true, shouldExit=false.
        CommandResult r = CommandResult.forQuery("some prompt");
        assertTrue(r.shouldQuery());
        assertFalse(r.shouldExit());
        assertEquals("some prompt", r.output());
    }

    @Test
    void commandResultOf_hasNoFlags() {
        CommandResult r = CommandResult.of("text");
        assertFalse(r.shouldQuery());
        assertFalse(r.shouldExit());
    }

    @Test
    void commandResultExit_hasExitFlag() {
        CommandResult r = CommandResult.exit("bye");
        assertTrue(r.shouldExit());
        assertFalse(r.shouldQuery());
    }
}
