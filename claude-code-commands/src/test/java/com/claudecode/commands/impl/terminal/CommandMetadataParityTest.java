package com.claudecode.commands.impl.terminal;

import com.claudecode.commands.impl.agents.AdvisorCommand;
import com.claudecode.commands.impl.agents.AgentsCommand;
import com.claudecode.commands.impl.agents.DreamCommand;
import com.claudecode.commands.impl.config.AddDirCommand;
import com.claudecode.commands.impl.config.ColorCommand;
import com.claudecode.commands.impl.config.ConfigCommand;
import com.claudecode.commands.impl.config.EffortCommand;
import com.claudecode.commands.impl.config.ModelCommand;
import com.claudecode.commands.impl.config.OutputStyleCommand;
import com.claudecode.commands.impl.config.PermissionsCommand;
import com.claudecode.commands.impl.config.SandboxToggleCommand;
import com.claudecode.commands.impl.config.ThemeCommand;
import com.claudecode.commands.impl.context.CompactCommand;
import com.claudecode.commands.impl.context.ContextCommand;
import com.claudecode.commands.impl.context.GoalCommand;
import com.claudecode.commands.impl.context.InsightsCommand;
import com.claudecode.commands.impl.context.MemoryCommand;
import com.claudecode.commands.impl.git.BranchCommand;
import com.claudecode.commands.impl.git.DiffCommand;
import com.claudecode.commands.impl.git.InitCommand;
import com.claudecode.commands.impl.git.PlanCommand;
import com.claudecode.commands.impl.info.CostCommand;
import com.claudecode.commands.impl.info.DoctorCommand;
import com.claudecode.commands.impl.info.HelpCommand;
import com.claudecode.commands.impl.info.StatsCommand;
import com.claudecode.commands.impl.info.StatusCommand;
import com.claudecode.commands.impl.info.TagCommand;
import com.claudecode.commands.impl.info.UsageCommand;
import com.claudecode.commands.impl.info.VersionCommand;
import com.claudecode.commands.impl.integration.HooksCommand;
import com.claudecode.commands.impl.integration.McpCommand;
import com.claudecode.commands.impl.integration.McpPromptCommand;
import com.claudecode.commands.impl.integration.PluginCommand;
import com.claudecode.commands.impl.integration.PluginMarkdownCommand;
import com.claudecode.commands.impl.integration.ReloadPluginsCommand;
import com.claudecode.commands.impl.integration.SkillsCommand;
import com.claudecode.commands.impl.session.ClearCommand;
import com.claudecode.commands.impl.session.ExitCommand;
import com.claudecode.commands.impl.session.ExportCommand;
import com.claudecode.commands.impl.session.RenameCommand;
import com.claudecode.commands.impl.session.ResumeCommand;
import com.claudecode.commands.impl.session.RewindCommand;
import com.claudecode.core.engine.SandboxConfig;
import com.claudecode.core.serialization.JsonUtils;

import org.apache.commons.lang3.Strings;

import com.claudecode.commands.Command;
import com.claudecode.commands.CommandContext;
import com.claudecode.runtime.mcp.McpManagementPort;
import com.claudecode.runtime.settings.SettingsManagementPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class CommandMetadataParityTest {

    @Test
    void argumentHintsMatchTheTsCommandDefinitions() {
        assertAll(
                () -> assertEquals("<path>", new AddDirCommand().argumentHint()),
                () -> assertEquals("[<model>|off]", new AdvisorCommand().argumentHint()),
                () -> assertEquals("[name]", new BranchCommand().argumentHint()),
                () -> assertEquals("<question>", new BtwCommand().argumentHint()),
                () -> assertEquals("<color|default>", new ColorCommand().argumentHint()),
                () -> assertEquals("<optional custom summarization instructions>",
                    new CompactCommand().argumentHint()),
                () -> assertEquals("[none|minimal|low|medium|high|xhigh|max|auto]",
                    new EffortCommand().argumentHint()),
                () -> assertEquals("[enable|disable [server-name]]",
                    new McpCommand(McpManagementPort.none()).argumentHint()),
                () -> assertEquals("[filename]", new ExportCommand().argumentHint()),
                () -> assertEquals("[<condition> | clear]", new GoalCommand().argumentHint()),
                () -> assertEquals("[model]", new ModelCommand().argumentHint()),
                () -> assertEquals("[open|<description>]", new PlanCommand().argumentHint()),
                () -> assertEquals("[name]", new RenameCommand().argumentHint()),
                () -> assertEquals("[conversation id or search term]",
                    new ResumeCommand().argumentHint()),
                () -> assertNull(new RewindCommand().argumentHint(),
                    "TS rewind's empty hint maps to Java's null default"),
                () -> assertNull(new OutputStyleCommand().argumentHint(),
                    "TS output-style declares no argumentHint"),
                () -> assertEquals("exclude \"command pattern\"",
                    new SandboxToggleCommand().argumentHint()),
                () -> assertEquals("<tag-name>", new TagCommand().argumentHint())
            );
    }

    @Test
    void memoryIsNotImmediateBecauseTsDoesNotDeclareImmediate() {
        assertFalse(new MemoryCommand().isImmediate());
    }

    @Test
    void advisorStaysUnavailableAndHiddenWithoutItsUnportedRuntimeGate() {
        AdvisorCommand command = new AdvisorCommand();

        assertAll(
            () -> assertFalse(command.isAvailable(CommandContext.minimal()),
                "Java has no GrowthBook advisor runtime consumer, so the command must stay unavailable"),
            () -> assertTrue(command.isHidden())
        );
    }

    @Test
    void keybindingsAvailabilityUsesTheSameFeatureGateAsExecution() throws Exception {
        KeybindingsCommand command = new KeybindingsCommand();

        assertEquals(KeybindingsCommand.isEnabled(),
            command.isAvailable(CommandContext.minimal()));
    }

    @Test
    void tagAndVersionAreUnavailableWhenUserTypeIsNotAnt() {
        assumeFalse(Strings.CS.equals("ant", System.getenv("USER_TYPE")),
            "Run this ANT-external parity test with USER_TYPE unset or non-ant");

        assertAll(
            () -> assertFalse(new TagCommand().isAvailable(CommandContext.minimal())),
            () -> assertFalse(new VersionCommand().isAvailable(CommandContext.minimal()))
        );
    }

    @Test
    void outputStyleIsHiddenLikeTheDeprecatedTsCommand() {
        assertTrue(new OutputStyleCommand().isHidden());
    }

    @Test
    void sandboxIsHiddenWhenTheCurrentPlatformIsExcluded(
            @TempDir Path tempDir) throws Exception {
        String originalHome = System.getProperty("user.home");
        String originalDir = System.getProperty("user.dir");
        Path home = tempDir.resolve("home");
        Path work = tempDir.resolve("work");
        Files.createDirectories(home);
        Files.createDirectories(work);

        try {
            System.setProperty("user.home", home.toString());
            System.setProperty("user.dir", work.toString());
            SettingsManagementPort.Sandbox sandbox = new SettingsManagementPort.Sandbox() {
                @Override public SandboxConfig config() {
                    var node = JsonUtils.getMapper()
                        .createObjectNode();
                    node.putArray("enabledPlatforms");
                    return SandboxConfig.fromJson(node);
                }
                @Override public boolean lockedByPolicy() { return false; }
                @Override public void saveSettings(String cwd, Boolean enabled,
                        Boolean autoAllow, Boolean allowUnsandboxed) { }
                @Override public String addExcludedCommand(String cwd, String pattern) { return ""; }
                @Override public void saveAdditionalDirectory(String cwd, String path) { }
            };
            assertTrue(new SandboxToggleCommand(sandbox).isHidden(),
                "TS hides /sandbox when enabledPlatforms excludes the current host");
        } finally {
            System.setProperty("user.home", originalHome);
            System.setProperty("user.dir", originalDir);
        }
    }

    @Test
    void nonInteractiveEligibilityMatchesTheTsHeadlessFilter() {
        assertAll(

            () -> assertTrue(new AdvisorCommand().supportsNonInteractive()),
            () -> assertTrue(new CompactCommand().supportsNonInteractive()),
            () -> assertTrue(new ContextCommand().supportsNonInteractive()),
            () -> assertTrue(new CostCommand().supportsNonInteractive()),
            () -> assertTrue(new VersionCommand().supportsNonInteractive()),


            () -> assertTrue(new InitCommand().supportsNonInteractive()),
            () -> assertTrue(new InsightsCommand().supportsNonInteractive()),
            () -> assertTrue(new DreamCommand().supportsNonInteractive()),
            () -> assertTrue(new GoalCommand().supportsNonInteractive()),


            () -> assertFalse(new ClearCommand().supportsNonInteractive()),
            () -> assertFalse(new KeybindingsCommand().supportsNonInteractive()),
            () -> assertFalse(new ReloadPluginsCommand().supportsNonInteractive()),
            () -> assertFalse(new RewindCommand().supportsNonInteractive()),
            () -> assertFalse(new PlanCommand().supportsNonInteractive()),
            () -> assertFalse(new StatuslineCommand().supportsNonInteractive(),
                "TS prompt command declares disableNonInteractive: true")
        );
    }
}
