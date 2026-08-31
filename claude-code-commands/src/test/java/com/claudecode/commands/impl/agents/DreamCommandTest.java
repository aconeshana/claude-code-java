package com.claudecode.commands.impl.agents;


import org.apache.commons.lang3.Strings;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.dream.DreamPort;
import com.claudecode.core.message.Usage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DreamCommandTest {

    private final DreamCommand command = new DreamCommand();
    private String savedUserHome;
    private Path tempHome;

    @BeforeEach
    void setUp() throws IOException {
        savedUserHome = System.getProperty("user.home");
        tempHome = Files.createTempDirectory("dream-cmd-test");
        System.setProperty("user.home", tempHome.toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", savedUserHome);
    }

    private void writeSettings(String json) throws IOException {
        Path settings = tempHome.resolve(".claude").resolve("settings.json");
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, json);
    }

    @Test
    void metadata() {
        assertEquals("dream", command.name());
        assertTrue(command.aliases().isEmpty(), "dream has no aliases");
        assertEquals("Consolidate and improve your memory files", command.description());
    }

    @Test
    void executeInjectsConsolidationPromptAsQuery() {
        CommandContext context = CommandContext.builder(
                "m", List::of, () -> { }, _ -> { }, () -> Usage.EMPTY,
                _ -> 0.0, "/tmp", false)
            .dream(new DreamPort() {
                @Override public boolean available() { return true; }
                @Override public String buildPrompt(String workingDirectory) {
                    return "# Dream: Memory Consolidation\n## Phase 1 — Orient";
                }
            })
            .build();
        CommandResult r = command.execute(context, "");
        assertTrue(r.shouldQuery(), "must inject the prompt into the main loop (TS 'prompt' type)");
        assertFalse(r.shouldExit(), "must not exit the REPL");
        String prompt = r.output();
        assertTrue(Strings.CS.contains(prompt, "# Dream: Memory Consolidation"), "uses the four-phase prompt");
        assertTrue(Strings.CS.contains(prompt, "## Phase 1 — Orient"), "phase 1 included");
        // Manual /dream runs in the main loop, so no tool-constraint 'extra'.
        assertFalse(Strings.CS.contains(prompt, "## Additional context"), "no extra section for manual /dream");
    }

    @Test
    void unavailableWithoutReleasedGrowthBookRollout() {
        assertFalse(command.isAvailable(CommandContext.minimal()),
            "/dream stays hidden in an isolated API-key test without tengu_onyx_plover");
    }

    @Test
    void unavailableWhenAutoDreamDisabled() throws IOException {
        writeSettings("{\"autoDreamEnabled\": false}");
        assertFalse(command.isAvailable(CommandContext.minimal()), "/dream hidden when autoDream disabled");
    }

    @Test
    void unavailableWhenAutoMemoryDisabled() throws IOException {
        writeSettings("{\"autoMemoryEnabled\": false}");
        assertFalse(command.isAvailable(CommandContext.minimal()), "/dream hidden when auto-memory disabled");
    }
}
