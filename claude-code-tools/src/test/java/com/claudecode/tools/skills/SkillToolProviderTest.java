package com.claudecode.tools.skills;

import com.claudecode.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillToolProviderTest {

    @Test
    void initializeCanConfigureSourcesWithoutScanningThemOnTheCallingThread(@TempDir Path temp) {
        AtomicInteger loads = new AtomicInteger();
        SkillLoader loader = new SkillLoader() {
            @Override public List<Skill> loadAll() {
                loads.incrementAndGet();
                return super.loadAll();
            }
        };
        SkillToolProvider provider = new SkillToolProvider(
            loader, new ShellVariableInjector(), temp.resolve("config"), temp.resolve("managed"));

        provider.initialize(temp.resolve("project"), new ToolRegistry(),
            true, true, true, false);

        assertEquals(0, loads.get());
        loader.loadAll();
        assertEquals(1, loads.get());
    }

    @Test
    void discoversManagedOverrideUserAndAllProjectAncestorsButNotCwdSkills(@TempDir Path temp)
        throws Exception {
        Path configHome = temp.resolve("config-home");
        Path managedRoot = temp.resolve("managed");
        Path repo = temp.resolve("repo");
        Path cwd = repo.resolve("packages/app");
        Files.createDirectories(repo.resolve(".git"));
        writeSkill(managedRoot.resolve(".claude/skills"), "managed-skill");
        writeSkill(configHome.resolve("skills"), "user-skill");
        writeSkill(repo.resolve(".claude/skills"), "root-skill");
        writeSkill(cwd.resolve(".claude/skills"), "nested-skill");
        writeSkill(cwd.resolve("skills"), "fabricated-bundled-skill");
        writeCommand(managedRoot.resolve(".claude/commands"), "managed-command");
        writeCommand(configHome.resolve("commands"), "user-command");
        writeCommand(repo.resolve(".claude/commands"), "root-command");
        writeCommand(cwd.resolve(".claude/commands"), "nested-command");

        SkillLoader loader = new SkillLoader();
        SkillToolProvider provider = new SkillToolProvider(
            loader, new ShellVariableInjector(), configHome, managedRoot);
        provider.initialize(cwd, new ToolRegistry());

        Set<String> names = loader.loadAll().stream().map(Skill::name).collect(Collectors.toSet());
        assertEquals(Set.of(
            "managed-skill", "user-skill", "root-skill", "nested-skill",
            "managed-command", "user-command", "root-command", "nested-command",
            "deep-research", "update-config", "keybindings-help", "verify",
            "code-review", "simplify",
            "fewer-permission-prompts", "loop", "claude-api", "run", "init",
            "review", "security-review"), names);
    }

    @Test
    void disabledSkillsOmitTheToolAndStayEmptyAfterPluginInjection(@TempDir Path temp)
        throws Exception {
        Path configHome = temp.resolve("config-home");
        Path managedRoot = temp.resolve("managed");
        Path cwd = temp.resolve("repo");
        Path pluginRoot = temp.resolve("plugin-skills");
        writeSkill(configHome.resolve("skills"), "user-skill");
        writeSkill(pluginRoot, "plugin-skill");

        SkillLoader loader = new SkillLoader();
        SkillToolProvider provider = new SkillToolProvider(
            loader, new ShellVariableInjector(), configHome, managedRoot);
        ToolRegistry registry = new ToolRegistry();

        provider.initialize(cwd, registry, false);
        loader.setPluginSkillRoots(List.of(
            new SkillLoader.PluginSkillRoot("probe", pluginRoot)));

        assertTrue(registry.get("Skill").isEmpty());
        assertTrue(loader.loadAll().isEmpty());
    }

    @Test
    void settingSourcesFilterUserAndProjectSkillsButKeepBundledSkills(@TempDir Path temp)
        throws Exception {
        Path configHome = temp.resolve("config-home");
        Path managedRoot = temp.resolve("managed");
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo.resolve(".git"));
        writeSkill(configHome.resolve("skills"), "user-skill");
        writeSkill(repo.resolve(".claude/skills"), "project-skill");

        SkillLoader userLoader = new SkillLoader();
        ToolRegistry userRegistry = new ToolRegistry();
        new SkillToolProvider(userLoader, new ShellVariableInjector(), configHome, managedRoot)
            .initialize(repo, userRegistry, true, true, false);
        Set<String> userNames = userLoader.loadAll().stream()
            .map(Skill::name).collect(Collectors.toSet());

        assertTrue(userRegistry.get("Skill").isPresent());
        assertTrue(userNames.contains("user-skill"));
        assertTrue(userNames.contains("verify"), "bundled skills are independent of setting sources");
        assertFalse(userNames.contains("project-skill"));

        SkillLoader projectLoader = new SkillLoader();
        new SkillToolProvider(projectLoader, new ShellVariableInjector(), configHome, managedRoot)
            .initialize(repo, new ToolRegistry(), true, false, true);
        Set<String> projectNames = projectLoader.loadAll().stream()
            .map(Skill::name).collect(Collectors.toSet());

        assertTrue(projectNames.contains("project-skill"));
        assertTrue(projectNames.contains("verify"));
        assertFalse(projectNames.contains("user-skill"));

        SkillLoader isolatedLoader = new SkillLoader();
        new SkillToolProvider(isolatedLoader, new ShellVariableInjector(), configHome, managedRoot)
            .initialize(repo, new ToolRegistry(), true, false, false);
        Set<String> isolatedNames = isolatedLoader.loadAll().stream()
            .map(Skill::name).collect(Collectors.toSet());

        assertTrue(isolatedNames.contains("verify"));
        assertFalse(isolatedNames.contains("user-skill"));
        assertFalse(isolatedNames.contains("project-skill"));
    }

    private static void writeSkill(Path root, String name) throws Exception {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), "---\ndescription: test\n---\nbody\n");
    }

    private static void writeCommand(Path root, String name) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve(name + ".md"), "---\ndescription: test\n---\nbody\n");
    }
}
