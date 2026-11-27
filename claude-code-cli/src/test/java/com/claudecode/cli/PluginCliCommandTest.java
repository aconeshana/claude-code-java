package com.claudecode.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class PluginCliCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void validatePassesOnValidMarketplaceAndPluginManifests() throws Exception {
        Path marketplaceRoot = tempDir.resolve("sample-marketplace");
        Path pluginRoot = marketplaceRoot.resolve("plugins/demo-plugin");
        Files.createDirectories(marketplaceRoot.resolve(".claude-plugin"));
        Files.createDirectories(pluginRoot.resolve(".claude-plugin"));
        Files.createDirectories(pluginRoot.resolve("skills/hello"));

        Files.writeString(marketplaceRoot.resolve(".claude-plugin/marketplace.json"), """
            {
              "name": "sample-marketplace",
              "owner": { "name": "tester" },
              "plugins": [
                {
                  "name": "demo-plugin",
                  "source": "./plugins/demo-plugin",
                  "description": "Demo plugin",
                  "version": "1.0.0"
                }
              ]
            }
            """);
        Files.writeString(pluginRoot.resolve(".claude-plugin/plugin.json"), """
            {
              "name": "demo-plugin",
              "version": "1.0.0",
              "description": "Demo plugin"
            }
            """);
        Files.writeString(pluginRoot.resolve("skills/hello/SKILL.md"), """
            ---
            name: hello
            description: Demo skill
            ---
            Hello world
            """);

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(new ClaudeCodeCli())
            .setOut(new PrintWriter(out))
            .setErr(new PrintWriter(err));

        int code = cmd.execute("plugin", "validate", marketplaceRoot.toString());
        assertEquals(0, code, () -> "stderr: " + err);
        assertTrue(Strings.CS.contains(out.toString(), "Validation passed."));

        out.getBuffer().setLength(0);
        int pluginCode = cmd.execute("plugin", "validate", pluginRoot.toString());
        assertEquals(0, pluginCode, () -> "stderr: " + err);
        assertTrue(Strings.CS.contains(out.toString(), "Validation passed."));
    }

    @Test
    void barePluginCommandPrintsUsageAndReturnsTwo() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(new ClaudeCodeCli())
            .setOut(new PrintWriter(out))
            .setErr(new PrintWriter(err));

        int code = cmd.execute("plugin");
        assertEquals(2, code);
        assertTrue(Strings.CS.contains(err.toString(), "Usage: claude plugin"));
    }
}
