package com.claudecode.tools.skills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class SkillLoaderPluginRootsTest {

    @TempDir
    Path tmp;

    private static Optional<Skill> byName(List<Skill> skills, String name) {
        return skills.stream().filter(s -> s.name().equals(name)).findFirst();
    }

    @Test
    void loadsSkillSubdirectoriesWithPluginPrefix() throws IOException {
        Path root = tmp.resolve("skills");
        Files.createDirectories(root.resolve("pdf"));
        Files.writeString(root.resolve("pdf/SKILL.md"), """
            ---
            description: PDF handling
            ---
            pdf body""");
        Files.createDirectories(root.resolve("csv"));
        Files.writeString(root.resolve("csv/SKILL.md"), "csv body");

        SkillLoader loader = new SkillLoader();
        loader.setPluginSkillRoots(List.of(new SkillLoader.PluginSkillRoot("myplugin", root)));

        List<Skill> skills = loader.loadAll();
        Skill pdf = byName(skills, "myplugin:pdf").orElseThrow();
        assertEquals(Skill.SkillSource.PLUGIN, pdf.source());
        assertEquals("PDF handling", pdf.description());
        assertEquals("pdf body", pdf.content());
        assertTrue(byName(skills, "myplugin:csv").isPresent());
    }

    @Test
    void directSkillRootUsesItsOwnDirectoryName() throws IOException {
        Path root = tmp.resolve("deploy");
        Files.createDirectories(root);
        Files.writeString(root.resolve("SKILL.md"), "deploy body");

        SkillLoader loader = new SkillLoader();
        loader.setPluginSkillRoots(List.of(new SkillLoader.PluginSkillRoot("p", root)));

        assertTrue(byName(loader.loadAll(), "p:deploy").isPresent(),
            "a root containing SKILL.md directly is one skill named after the root dir");
    }

    @Test
    void directSkillRootCanUseMarketplaceLogicalNameInsteadOfCacheHash() throws IOException {
        Path root = tmp.resolve("acd2bf5a7126");
        Files.createDirectories(root);
        Files.writeString(root.resolve("SKILL.md"), "dataset body");

        SkillLoader loader = new SkillLoader();
        loader.setPluginSkillRoots(List.of(new SkillLoader.PluginSkillRoot(
            "huggingface-datasets", root, "huggingface-datasets")));

        List<Skill> skills = loader.loadAll();
        assertTrue(byName(skills,
            "huggingface-datasets:huggingface-datasets").isPresent());
        assertTrue(byName(skills,
            "huggingface-datasets:acd2bf5a7126").isEmpty());
    }

    @Test
    void frontmatterNameDoesNotOverridePluginSkillNaming() throws IOException {
        Path root = tmp.resolve("skills");
        Files.createDirectories(root.resolve("real-dir"));
        Files.writeString(root.resolve("real-dir/SKILL.md"), """
            ---
            name: fancy-name
            description: d
            ---
            body""");

        SkillLoader loader = new SkillLoader();
        loader.setPluginSkillRoots(List.of(new SkillLoader.PluginSkillRoot("p", root)));

        List<Skill> skills = loader.loadAll();
        assertTrue(byName(skills, "p:real-dir").isPresent(),
            "TS derives plugin skill names from directory names, not frontmatter");
        assertTrue(byName(skills, "p:fancy-name").isEmpty());
        assertTrue(byName(skills, "fancy-name").isEmpty());
    }

    @Test
    void settingRootsReplacesPreviousGeneration() throws IOException {
        Path rootA = tmp.resolve("a-skills/one");
        Files.createDirectories(rootA);
        Files.writeString(rootA.resolve("SKILL.md"), "a");
        Path rootB = tmp.resolve("b-skills/two");
        Files.createDirectories(rootB);
        Files.writeString(rootB.resolve("SKILL.md"), "b");

        SkillLoader loader = new SkillLoader();
        loader.setPluginSkillRoots(List.of(
            new SkillLoader.PluginSkillRoot("a", rootA.getParent())));
        assertTrue(byName(loader.loadAll(), "a:one").isPresent());

        loader.setPluginSkillRoots(List.of(
            new SkillLoader.PluginSkillRoot("b", rootB.getParent())));
        List<Skill> skills = loader.loadAll();
        assertTrue(byName(skills, "a:one").isEmpty(), "old generation gone after swap");
        assertTrue(byName(skills, "b:two").isPresent());
    }

    @Test
    void settingPluginCommandsReplacesPreviousGeneration() {
        SkillLoader loader = new SkillLoader();
        Skill oldCommand = new Skill("demo:old", "Old command", List.of(), "old", null,
            Skill.SkillSource.PLUGIN, null, null, null,
            Map.of("pluginCommand", true));
        Skill newCommand = new Skill("demo:new", "New command", List.of(), "new", null,
            Skill.SkillSource.PLUGIN, null, null, null,
            Map.of("pluginCommand", true));

        loader.setPluginCommandSkills(List.of(oldCommand));
        assertTrue(byName(loader.loadAll(), "demo:old").isPresent());

        loader.setPluginCommandSkills(List.of(newCommand));
        List<Skill> skills = loader.loadAll();
        assertTrue(byName(skills, "demo:old").isEmpty(), "old command generation must be evicted");
        assertTrue(byName(skills, "demo:new").isPresent());
    }

    @Test
    void missingRootDirectoryIsIgnored() {
        SkillLoader loader = new SkillLoader();
        loader.setPluginSkillRoots(List.of(
            new SkillLoader.PluginSkillRoot("p", tmp.resolve("does-not-exist"))));
        assertTrue(loader.loadAll().isEmpty());
    }
}
