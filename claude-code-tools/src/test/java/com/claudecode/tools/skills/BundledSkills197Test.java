package com.claudecode.tools.skills;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.FileStateCache;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.tools.bundled.BundledResourceCatalog;
import com.claudecode.tools.loop.LoopFeatureGate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Wire-level regression coverage for bundled skill resources.
 */
class BundledSkills197Test {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void registryMatchesOfficial197ListingOrderAndDescriptions() {
        List<Skill> skills = BundledSkillCatalog.load();

        assertEquals(List.of(
                "deep-research",
                "update-config",
                "keybindings-help",
                "verify",
                "code-review",
                "simplify",
                "fewer-permission-prompts",
                "loop",
                "claude-api",
                "run",
                "init",
                "review",
                "security-review"),
            skills.stream().map(Skill::name).toList());
        assertTrue(skills.subList(0, 10).stream()
            .allMatch(s -> s.source() == Skill.SkillSource.BUNDLED));
        assertTrue(skills.subList(10, 13).stream()
            .allMatch(s -> s.source() == Skill.SkillSource.BUILTIN));
        assertEquals(
            "Verify that a code change actually does what it's supposed to by running the app and observing behavior. Use when asked to verify a PR, confirm a fix works, test a change manually, check that a feature works, or validate local changes before pushing.",
            skills.stream().filter(s -> Strings.CS.equals(s.name(), "verify")).findFirst().orElseThrow().description());
        assertEquals(
            "Review the current diff for correctness bugs and reuse/simplification/efficiency cleanups at the given effort level (low/medium: fewer, high-confidence findings; high→max: broader coverage, may include uncertain findings). Pass --comment to post findings as inline PR comments, or --fix to apply the findings to the working tree after the review.",
            skills.stream().filter(s -> Strings.CS.equals(s.name(), "code-review")).findFirst().orElseThrow().description());
        assertEquals(
            "Review the changed code for reuse, simplification, efficiency, and altitude cleanups, then apply the fixes. Quality only — it does not hunt for bugs; use /code-review for that.",
            skills.stream().filter(s -> Strings.CS.equals(s.name(), "simplify")).findFirst().orElseThrow().description());
        assertEquals(
            "76f94a0a666549bd4e41b279079c50412372b80f8591bc94e0b05ed9d5ec801f",
            sha256(skills.stream().filter(s -> Strings.CS.equals(s.name(), "claude-api"))
                .findFirst().orElseThrow().description()));
        assertEquals(
            "Initialize a new CLAUDE.md file with codebase documentation",
            skills.stream().filter(s -> Strings.CS.equals(s.name(), "init")).findFirst().orElseThrow().description());
        assertEquals(
            "Review a GitHub pull request; for your working diff use /code-review",
            skills.stream().filter(s -> Strings.CS.equals(s.name(), "review")).findFirst().orElseThrow().description());
        assertEquals(
            "Complete a security review of the pending changes on the current branch",
            skills.stream().filter(s -> Strings.CS.equals(s.name(), "security-review")).findFirst().orElseThrow().description());
        assertTrue(skills.stream().allMatch(s -> StringUtils.isNotBlank(s.content())));
    }

    @Test
    void stableSkillLoaderAcceptsAnExplicitBundledRelease() {
        List<Skill> skills = BundledSkillCatalog.load(
            BundledResourceCatalog.forVersion("9.9.9"));

        assertEquals(List.of("test-skill"), skills.stream().map(Skill::name).toList());
        assertEquals("TEST SKILL\n", skills.getFirst().content());
    }

    @Test
    void loaderPlacesDeepResearchBeforePluginsAndOtherBundledSkillsAfterPlugins() throws IOException {
        Path userRoot = tempDir.resolve("user");
        Files.createDirectories(userRoot.resolve("security-review"));
        Files.writeString(userRoot.resolve("security-review/SKILL.md"), """
            ---
            description: user security review
            ---
            USER BODY
            """);
        Path pluginRoot = tempDir.resolve("plugin-skill");
        Files.createDirectories(pluginRoot);
        Files.writeString(pluginRoot.resolve("SKILL.md"), """
            ---
            description: plugin description
            ---
            PLUGIN BODY
            """);

        SkillLoader loader = new SkillLoader();
        loader.addSource(Skill.SkillSource.USER, userRoot);
        loader.setBundledSkillsBeforePlugins(BundledSkillCatalog.loadBeforePlugins());
        loader.setPluginSkillRoots(List.of(
            new SkillLoader.PluginSkillRoot("demo", pluginRoot, "plugin-skill")));
        loader.setBundledSkills(BundledSkillCatalog.loadAfterPlugins());

        List<Skill> skills = loader.loadAll();
        assertEquals("user security review", skills.getFirst().description());
        assertEquals("deep-research", skills.get(1).name());
        assertEquals("demo:plugin-skill", skills.get(2).name());
        assertEquals("update-config", skills.get(3).name());
        assertEquals("security-review", skills.getLast().name());
    }

    @Test
    void bundledSkillInvocationUsesOfficialPromptTemplateAndSubstitutesArgs() {
        SkillLoader loader = new SkillLoader();
        loader.setBundledSkills(BundledSkillCatalog.load());
        SkillTool tool = new SkillTool(loader, new ShellVariableInjector());
        ObjectNode input = MAPPER.createObjectNode();
        input.put("skill", "deep-research");
        input.put("args", "cache behavior");

        ToolResult result = tool.call(
            input, ToolExecutionContext.of(new AbortController(), "test-session"));

        assertFalse(result.isError());
        assertEquals("Launching skill: deep-research",
            ((TextBlock) result.content().getFirst()).text());
        UserMessage injected = assertInstanceOf(UserMessage.class, result.newMessages().getFirst());
        List<ContentBlock> blocks = injected.message().blocks();
        String body = ((TextBlock) blocks.getFirst()).text();
        assertTrue(Strings.CS.startsWith(body, "Run the \"deep-research\" workflow."), body);
        assertTrue(Strings.CS.endsWith(body, "Invoke: Workflow({ name: \"deep-research\", args: \"cache behavior\" })"), body);
    }

    @Test
    void simplifyInvocationMatchesOfficial197ExpandedPrompt() {
        SkillLoader loader = new SkillLoader();
        loader.setBundledSkills(BundledSkillCatalog.load());
        SkillTool tool = new SkillTool(loader, new ShellVariableInjector());
        ObjectNode input = MAPPER.createObjectNode();
        input.put("skill", "simplify");
        input.put("args", "probe");

        ToolResult result = tool.call(
            input, ToolExecutionContext.of(new AbortController(), "test-session"));

        UserMessage injected = assertInstanceOf(UserMessage.class, result.newMessages().getFirst());
        String body = ((TextBlock) injected.message().blocks().getFirst()).text();
        assertEquals("6aca880c8eb8c5901334811cfcf3099e4df17280deaf2fc0c35d4e4440129782",
            sha256(body));
        assertTrue(Strings.CS.startsWith(body, "Review target: `probe`\n\n"), body);
        assertFalse(Strings.CS.contains(body, "ARGUMENTS:"), body);
    }

    @Test
    void verifyInvocationMatchesOfficial197WithAndWithoutArgs() {
        SkillTool tool = toolWithBundledSkills();
        ToolExecutionContext context = ToolExecutionContext.of(
            new AbortController(), "test-session");

        ObjectNode withArgs = MAPPER.createObjectNode();
        withArgs.put("skill", "verify");
        withArgs.put("args", "probe");
        String argsBody = injectedBody(tool.call(withArgs, context));
        assertEquals("eff78d38a0423287a1eab8e45bed4bfe61dc02e86f9140c6c1991c10083f3ead",
            sha256(normalizeBundledBase(argsBody, "verify")));
        assertTrue(Strings.CS.endsWith(argsBody, "\n\n\n## User Request\n\nprobe"), argsBody);

        ObjectNode withoutArgs = MAPPER.createObjectNode();
        withoutArgs.put("skill", "verify");
        String noArgsBody = injectedBody(tool.call(withoutArgs, context));
        assertEquals("bd0367ffac604b4215d3bf3aff079918e6905922c8a809a9b9834255e84d9f3b",
            sha256(normalizeBundledBase(noArgsBody, "verify")));
        assertFalse(Strings.CS.contains(noArgsBody, "## User Request"), noArgsBody);
    }

    @Test
    void codeReviewInvocationMatchesOfficial197WithAndWithoutArgs() {
        SkillTool tool = toolWithBundledSkills();
        ToolExecutionContext context = ToolExecutionContext.of(
            new AbortController(), "test-session");

        ObjectNode withArgs = MAPPER.createObjectNode();
        withArgs.put("skill", "code-review");
        withArgs.put("args", "probe");
        String argsBody = injectedBody(tool.call(withArgs, context));
        assertEquals("29b07b9872390fecbd0bde5df08774e4a14b6f0d42b254170aec2c150c435adb",
            sha256(argsBody));

        ObjectNode withoutArgs = MAPPER.createObjectNode();
        withoutArgs.put("skill", "code-review");
        String noArgsBody = injectedBody(tool.call(withoutArgs, context));
        assertEquals("29b07b9872390fecbd0bde5df08774e4a14b6f0d42b254170aec2c150c435adb",
            sha256(noArgsBody));
        assertEquals(noArgsBody, argsBody);
        assertTrue(Strings.CS.startsWith(noArgsBody, "`high effort"), noArgsBody);
    }

    @Test
    void claudeApiPromptVariantsMatchSeparateOfficial197Baselines() throws IOException {
        String content = BundledSkillCatalog.load().stream()
            .filter(skill -> Strings.CS.equals(skill.name(), "claude-api"))
            .findFirst().orElseThrow().content();
        JsonNode variants;
        try (var input = BundledSkills197Test.class.getResourceAsStream(
                "/baselines/claude-api-prompt-baselines-2.1.197.json")) {
            variants = MAPPER.readTree(input).path("variants");
        }

        var fields = variants.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String variant = entry.getKey();
            JsonNode baseline = entry.getValue();
            Path project = tempDir.resolve(variant);
            Files.createDirectories(project);
            if (!baseline.path("indicator").isNull()) {
                Files.writeString(project.resolve(baseline.path("indicator").asText()), "probe\n");
            }

            String rendered = BundledSkillPromptRenderer.render(
                "claude-api", content, "probe", project);
            String normalized = normalizeBundledBase(rendered, "claude-api");

            assertEquals(baseline.path("javaStringLength").asInt(), normalized.length(), variant);
            assertEquals(baseline.path("sha256").asText(), sha256(normalized), variant);
        }
    }

    @Test
    void claudeApiFullAndJavaBaselinesHaveDistinctSemantics() throws IOException {
        String content = BundledSkillCatalog.load().stream()
            .filter(skill -> Strings.CS.equals(skill.name(), "claude-api"))
            .findFirst().orElseThrow().content();
        Path javaProject = tempDir.resolve("java-project");
        Files.createDirectories(javaProject);
        Files.writeString(javaProject.resolve("pom.xml"), "<project/>\n");

        String allDocs = BundledSkillPromptRenderer.render(
            "claude-api", content, "probe", tempDir.resolve("empty-project"));
        String javaOnly = BundledSkillPromptRenderer.render(
            "claude-api", content, "probe", javaProject);

        assertTrue(Strings.CS.contains(allDocs, "<doc path=\"python/"));
        assertTrue(Strings.CS.contains(allDocs, "No project language was auto-detected"));
        assertTrue(Strings.CS.contains(javaOnly, "<doc path=\"java/claude-api/README.md\">"));
        assertTrue(Strings.CS.contains(javaOnly, "<doc path=\"shared/models.md\">"));
        assertFalse(Strings.CS.contains(javaOnly, "<doc path=\"python/"));
        assertFalse(Strings.CS.contains(javaOnly, "No project language was auto-detected"));
        assertTrue(allDocs.length() > javaOnly.length());
    }

    @Test
    void gptModelCannotAutoInvokeBundledClaudeApiSkill() {
        SkillTool tool = toolWithBundledSkills();
        ObjectNode input = MAPPER.createObjectNode();
        input.put("skill", "claude-api");
        ToolExecutionContext context = ToolExecutionContext
            .builder(new AbortController(), "test-session")
            .workingDirectory(tempDir.toString())
            .fileStateCache(new FileStateCache())
            .nestedMemoryAttachmentTriggers(Set.of())
            .loadedNestedMemoryPaths(Set.of())
            .currentModel("gpt-5.6-sol")
            .build();

        ToolResult result = tool.call(input, context);

        assertTrue(result.isError());
        assertTrue(Strings.CS.contains(
            ((TextBlock) result.content().getFirst()).text(), "invoke /claude-api explicitly"));
        assertTrue(result.newMessages() == null || result.newMessages().isEmpty());
    }

    @Test
    void dynamicLoopPromptUsesScheduleWakeupAndPreservesOriginalInput() {
        String body = BundledSkillPromptRenderer.renderLoop(
            "check the deploy", true, false, null);

        assertTrue(Strings.CS.startsWith(body, "# /loop — schedule a recurring or self-paced prompt\n"), body);
        assertTrue(Strings.CS.contains(body, "## Dynamic mode (rule 3 — no interval)"), body);
        assertTrue(Strings.CS.contains(body, "call ScheduleWakeup"), body);
        assertTrue(Strings.CS.contains(body, "prefixed with `/loop `"), body);
        assertTrue(Strings.CS.endsWith(body, "## Input\n\ncheck the deploy"), body);
        assertFalse(Strings.CS.contains(body, "## Offer cloud first"), body);
    }

    @Test
    void dynamicLoopListingRetainsOfficialWhenToUseSuffix() {
        assertEquals(
            "Run a prompt or slash command on a recurring interval (e.g. /loop 5m /foo). Omit the interval to let the model self-pace. - When the user wants to set up a recurring task, poll for status, or run something repeatedly on an interval (e.g. \"check the deploy every 5 minutes\", \"keep running /babysit-prs\"). Do NOT invoke for one-off tasks.",
            BundledSkillCatalog.descriptionFor("loop", true));
    }

    @Test
    void loopCarriesTheReleasedUserInvocableCommandMetadata() {
        Skill loop = BundledSkillCatalog.load().stream()
            .filter(skill -> Strings.CS.equals("loop", skill.name()))
            .findFirst().orElseThrow();

        assertEquals(Skill.SkillSource.BUNDLED, loop.source());
        assertTrue(loop.userInvocable());
        assertEquals(List.of("proactive"), loop.aliases());
        assertEquals("Repeat a prompt or command on an interval (e.g. /loop 5m /foo)",
            loop.menuDescription());
        assertEquals(
            LoopFeatureGate.system().defaultPromptEnabled()
                ? "[interval] [prompt]" : "[interval] <prompt>",
            loop.argumentHint());
        assertEquals(1, BundledSkillCatalog.load().stream()
            .filter(skill -> Strings.CS.equals("loop", skill.name()))
            .count());
    }

    @Test
    void localCronDisableRemovesLoopFromTheBundledInventory() {
        assertFalse(BundledSkillCatalog.load(BundledResourceCatalog.current(), false).stream()
            .anyMatch(skill -> Strings.CS.equals("loop", skill.name())));
    }

    @Test
    void dynamicLoopWithoutInputShowsOfficialUsageWhenAutonomousPromptGateIsOff() {
        String body = BundledSkillPromptRenderer.renderLoop("", true, false, null);

        assertTrue(Strings.CS.startsWith(body, "Usage: /loop [interval] <prompt>"), body);
        assertTrue(Strings.CS.contains(body, "model picks a delay between iterations"), body);
    }

    @Test
    void securityReviewOutsideGitMatchesOfficial197Guidance() {
        SkillLoader loader = new SkillLoader();
        loader.setBundledSkills(BundledSkillCatalog.load());
        SkillTool tool = new SkillTool(loader, new ShellVariableInjector());
        ObjectNode input = MAPPER.createObjectNode();
        input.put("skill", "security-review");
        input.put("args", "probe");
        ToolExecutionContext context = ToolExecutionContext.builder(new AbortController(), "test-session").workingDirectory(tempDir.toString()).build();

        ToolResult result = tool.call(input, context);

        UserMessage injected = assertInstanceOf(UserMessage.class, result.newMessages().getFirst());
        String body = ((TextBlock) injected.message().blocks().getFirst()).text();
        assertEquals("""
            Tell the user: /security-review needs to run inside a git repository, but the current working directory (`%s`) is not one.

            If the repository is in a subdirectory, `cd` into it first and then re-run /security-review.

            If this is a self-hosted runner session created without a `git_repository` source, either add one at session creation so the runner clones it and sets the working directory, or `cd` into the cloned repo before running the review.""".formatted(tempDir), body);
    }

    @Test
    void securityReviewInsideGitExpandsOfficial197GitCommands() throws IOException, InterruptedException {
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo);
        runGit(repo, "init", "-b", "main");
        runGit(repo, "config", "user.email", "wire@example.com");
        runGit(repo, "config", "user.name", "Wire Test");
        Files.writeString(repo.resolve("tracked.txt"), "before\n");
        runGit(repo, "add", "tracked.txt");
        runGit(repo, "commit", "-m", "base");
        runGit(repo, "update-ref", "refs/remotes/origin/main", "HEAD");
        runGit(repo, "symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main");
        Files.writeString(repo.resolve("tracked.txt"), "after\n");
        runGit(repo, "add", "tracked.txt");
        runGit(repo, "commit", "-m", "security change");

        SkillLoader loader = new SkillLoader();
        loader.setBundledSkills(BundledSkillCatalog.load());
        SkillTool tool = new SkillTool(loader, new ShellVariableInjector());
        ObjectNode input = MAPPER.createObjectNode();
        input.put("skill", "security-review");
        ToolExecutionContext context = ToolExecutionContext.builder(new AbortController(), "test-session").workingDirectory(repo.toString()).build();

        ToolResult result = tool.call(input, context);

        UserMessage injected = assertInstanceOf(UserMessage.class, result.newMessages().getFirst());
        String body = ((TextBlock) injected.message().blocks().getFirst()).text();
        assertTrue(Strings.CS.startsWith(body, "You are a senior security engineer conducting a focused security review of the changes on this branch.\n\nGIT STATUS:\n\n```\n"), body);
        assertTrue(Strings.CS.contains(body, "FILES MODIFIED:\n\n```\ntracked.txt\n```"), body);
        assertTrue(Strings.CS.contains(body, "security change"), body);
        assertTrue(Strings.CS.contains(body, "-before\n+after"), body);
        assertFalse(Strings.CS.contains(body, "!`git "), body);
        assertTrue(Strings.CS.endsWith(body, "Your final reply must contain the markdown report and nothing else."), body);
    }

    private static void runGit(Path cwd, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
            .directory(cwd.toFile())
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }

    private static SkillTool toolWithBundledSkills() {
        SkillLoader loader = new SkillLoader();
        loader.setBundledSkills(BundledSkillCatalog.load());
        return new SkillTool(loader, new ShellVariableInjector());
    }

    private static String injectedBody(ToolResult result) {
        UserMessage injected = assertInstanceOf(
            UserMessage.class, result.newMessages().getFirst());
        return ((TextBlock) injected.message().blocks().getFirst()).text();
    }

    private static String normalizeBundledBase(String body, String skill) {
        return body.replaceFirst(
            "(?m)^Base directory for this skill: .*/" + skill + "$",
            "Base directory for this skill: <BASE>");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
