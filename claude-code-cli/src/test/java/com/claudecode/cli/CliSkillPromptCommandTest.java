package com.claudecode.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandRegistry;
import com.claudecode.core.message.TextBlock;
import com.claudecode.tools.skills.Skill;
import com.claudecode.tools.skills.SkillLoader;
import com.claudecode.tools.skills.SkillToolProvider;
import com.claudecode.tools.ToolRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

class CliSkillPromptCommandTest {

    @Test
    void releasedBundledCatalogOwnsLoopCommandMetadata() {
        SkillToolProvider provider = new SkillToolProvider();
        provider.initialize(Path.of("."), new ToolRegistry(), true, false, false, true);

        Skill loop = provider.getSkillLoader().loadAll().stream()
            .filter(skill -> Strings.CS.equals("loop", skill.name()))
            .findFirst().orElseThrow();

        assertTrue(loop.commandProjection());
        assertTrue(loop.userInvocable());
        assertEquals(List.of("proactive"), loop.aliases());
        assertEquals("Repeat a prompt or command on an interval (e.g. /loop 5m /foo)",
            loop.menuDescription());
        assertTrue(Strings.CS.startsWith(loop.argumentHint(), "[interval]"));
    }

    @Test
    void bundledLoopSkillProjectsOneSlashAndHeadlessCommand() {
        Skill loop = loopSkill();
        SkillLoader loader = new SkillLoader();
        loader.setBundledSkills(List.of(loop));
        CommandRegistry registry = new CommandRegistry();

        assertEquals(1, CliSkillCommandSync.sync(registry, loader, Path.of(".")));
        assertSame(registry.find("loop").orElseThrow(),
            registry.find("proactive").orElseThrow());
        assertEquals("Repeat repeatedly",
            registry.find("loop").orElseThrow().menuDescription());
        assertEquals("Run repeatedly",
            registry.find("loop").orElseThrow().description());

        var result = registry.dispatchNonInteractive(
            "/proactive check deploy", CommandContext.minimal()).orElseThrow();

        assertTrue(result.shouldQuery());
        assertEquals("bundled", result.promptInvocation().source());
        assertEquals("bundled", result.promptInvocation().loadedFrom());
        assertEquals("loop", result.promptInvocation().userFacingName());
        assertEquals("""
            <command-message>loop</command-message>
            <command-name>/loop</command-name>
            <command-args>check deploy</command-args>""",
            result.promptInvocation().precedingUserMessages().getFirst().text());
        String prompt = ((TextBlock) result.promptInvocation().content().getFirst()).text();
        assertTrue(Strings.CS.contains(prompt, "check deploy"));
    }

    private static Skill loopSkill() {
        return new Skill("loop", "Run repeatedly", List.of(), "# /loop\n\n$ARGUMENTS",
            null, Skill.SkillSource.BUNDLED, null, null, null, Map.of(
                "aliases", List.of("proactive"),
                "argumentHint", "[interval] <prompt>",
                "userInvocable", true,
                "commandProjection", true,
                "commandDescription", "Run repeatedly",
                "menuDescription", "Repeat repeatedly"));
    }
}
