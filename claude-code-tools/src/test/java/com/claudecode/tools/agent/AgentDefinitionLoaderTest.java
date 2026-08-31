package com.claudecode.tools.agent;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.agent.BuiltInAgentDefinitions;
import com.claudecode.core.agent.AgentSource;

import com.claudecode.core.config.ClaudePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgentDefinitionLoader} — frontmatter parsing and agent loading.
 */
class AgentDefinitionLoaderTest {

    // ── extractFrontmatter ────────────────────────────────────────────────────

    @Test
    void extractFrontmatter_simpleScalars() {
        String md = """
            ---
            name: my-agent
            description: Does something useful
            color: blue
            ---
            Body content here.
            """;
        Map<String, Object> fm = AgentDefinitionLoader.extractFrontmatter(md);
        assertNotNull(fm);
        assertEquals("my-agent", fm.get("name"));
        assertEquals("Does something useful", fm.get("description"));
        assertEquals("blue", fm.get("color"));
    }

    @Test
    void extractFrontmatter_quotedValues() {
        String md = """
            ---
            name: "quoted-agent"
            description: 'single quoted description'
            ---
            """;
        Map<String, Object> fm = AgentDefinitionLoader.extractFrontmatter(md);
        assertNotNull(fm);
        assertEquals("quoted-agent", fm.get("name"));
        assertEquals("single quoted description", fm.get("description"));
    }

    @Test
    void extractFrontmatter_doubleQuotedScalar_unescapesBackslashAndQuote() {
        String md = """
            ---
            name: my-agent
            description: "Say \\"hi\\" then cd C:\\\\temp"
            ---
            """;
        Map<String, Object> fm = AgentDefinitionLoader.extractFrontmatter(md);
        assertNotNull(fm);
        assertEquals("Say \"hi\" then cd C:\\temp", fm.get("description"));
    }

    @Test
    void extractFrontmatter_inlineArrayTools() {
        String md = """
            ---
            name: reader
            description: Reads stuff
            tools: [Read, Grep, Glob]
            ---
            """;
        Map<String, Object> fm = AgentDefinitionLoader.extractFrontmatter(md);
        assertNotNull(fm);
        Object tools = fm.get("tools");
        assertInstanceOf(List.class, tools);
        @SuppressWarnings("unchecked")
        List<String> toolList = (List<String>) tools;
        assertEquals(List.of("Read", "Grep", "Glob"), toolList);
    }

    @Test
    void extractFrontmatter_yamlSequenceTools() {
        String md = """
            ---
            name: seq-agent
            description: Uses sequence tools
            tools:
              - Bash
              - Read
              - Write
            ---
            """;
        Map<String, Object> fm = AgentDefinitionLoader.extractFrontmatter(md);
        assertNotNull(fm);
        Object tools = fm.get("tools");
        assertInstanceOf(List.class, tools);
        @SuppressWarnings("unchecked")
        List<String> toolList = (List<String>) tools;
        assertEquals(List.of("Bash", "Read", "Write"), toolList);
    }

    @Test
    void extractFrontmatter_missingFrontmatter_returnsNull() {
        String md = "No frontmatter here\nJust body content.";
        assertNull(AgentDefinitionLoader.extractFrontmatter(md));
    }

    @Test
    void extractFrontmatter_unclosedBlock_returnsNull() {
        String md = "---\nname: test\n";
        assertNull(AgentDefinitionLoader.extractFrontmatter(md));
    }

    // ── parseAgentMarkdown ────────────────────────────────────────────────────

    @Test
    void parseAgentMarkdown_validFile(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("my-agent.md");
        Files.writeString(f, """
            ---
            name: my-agent
            description: Does something useful
            color: red
            tools: [Read, Bash]
            ---
            System prompt body here.
            """);
        BuiltInAgentDefinitions.AgentDefinition def = AgentDefinitionLoader.parseAgentMarkdown(f);
        assertNotNull(def);
        assertEquals("my-agent", def.agentType());
        assertEquals("Does something useful", def.whenToUse());
        assertEquals("red", def.color());
        assertEquals(List.of("Read", "Bash"), def.tools());
    }

    @Test
    void parseAgentMarkdown_capturesPositiveMaxTurns(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("limited.md");
        Files.writeString(f, """
            ---
            name: limited
            description: Stops after a bounded number of turns
            maxTurns: 7
            ---
            Work carefully.
            """);

        BuiltInAgentDefinitions.AgentDefinition def = AgentDefinitionLoader.parseAgentMarkdown(f);

        assertNotNull(def);
        assertEquals(7, def.maxTurns());
    }

    @Test
    void parseAgentMarkdown_ignoresNonPositiveMaxTurns(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("unlimited.md");
        Files.writeString(f, """
            ---
            name: unlimited
            description: Has an invalid turn limit
            maxTurns: 0
            ---
            Keep going until complete.
            """);

        BuiltInAgentDefinitions.AgentDefinition def = AgentDefinitionLoader.parseAgentMarkdown(f);

        assertNotNull(def);
        assertNull(def.maxTurns());
    }

    @Test
    void parseAgentMarkdown_missingName_returnsNull(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("bad.md");
        Files.writeString(f, """
            ---
            description: Has no name
            ---
            """);
        assertNull(AgentDefinitionLoader.parseAgentMarkdown(f));
    }

    @Test
    void parseAgentMarkdown_missingDescription_returnsNull(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("bad2.md");
        Files.writeString(f, """
            ---
            name: no-desc
            ---
            """);
        assertNull(AgentDefinitionLoader.parseAgentMarkdown(f));
    }

    @Test
    void parseAgentMarkdown_escapedNewlineInDescription(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("multiline.md");
        Files.writeString(f, """
            ---
            name: my-agent
            description: Line one\\nLine two
            ---
            """);
        BuiltInAgentDefinitions.AgentDefinition def = AgentDefinitionLoader.parseAgentMarkdown(f);
        assertNotNull(def);
        assertEquals("Line one\nLine two", def.whenToUse());
    }

    @Test
    void parseAgentMarkdown_quotedScalar_unescapesBackslashesAndQuotes(@TempDir Path tmp) throws IOException {
        // Regression: parseYamlScalar previously stripped only the surrounding
        // quotes with no unescaping at all, so a description written by
        // AgentFileWriter.formatAsMarkdown (which escapes \ -> \\ and " -> \")
        // came back out on the next read still containing the raw escapes.
        Path f = tmp.resolve("quoted.md");
        Files.writeString(f, """
            ---
            name: my-agent
            description: "Use when the user says \\"hello\\" or writes C:\\\\temp\\\\file"
            ---
            """);
        BuiltInAgentDefinitions.AgentDefinition def = AgentDefinitionLoader.parseAgentMarkdown(f);
        assertNotNull(def);
        assertEquals("Use when the user says \"hello\" or writes C:\\temp\\file", def.whenToUse());
    }

    @Test
    void agentFileWriter_writeThenParse_roundTripsQuotesBackslashesAndNewlines(@TempDir Path tmp) throws IOException {
        Path agentsDir = tmp.resolve(".claude/agents");
        Files.createDirectories(agentsDir);
        String original = "Use when the user says \"hello\" or writes a path like C:\\temp\\file\nAnd a second line";
        String md = AgentFileWriter.formatAsMarkdown(
            "round-trip-agent", original, List.of("Read"), "body", null, null, null);
        Path f = agentsDir.resolve("round-trip-agent.md");
        Files.writeString(f, md);

        BuiltInAgentDefinitions.AgentDefinition def = AgentDefinitionLoader.parseAgentMarkdown(f);
        assertNotNull(def);
        assertEquals(original, def.whenToUse());
    }

    @Test
    void parseAgentMarkdown_noToolsField_usesEmptyAllowListForAllTools(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("notools.md");
        Files.writeString(f, """
            ---
            name: no-tools-agent
            description: An agent with no tools field
            ---
            """);
        BuiltInAgentDefinitions.AgentDefinition def = AgentDefinitionLoader.parseAgentMarkdown(f);
        assertNotNull(def);
        assertEquals(List.of(), def.tools());
    }

    @Test
    void parseAgentMarkdown_capturesDisallowedTools(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("readonly.md");
        Files.writeString(f, """
            ---
            name: readonly
            description: Read-only agent
            disallowedTools: [Edit, Write, NotebookEdit]
            ---
            Inspect only.
            """);
        BuiltInAgentDefinitions.AgentDefinition def = AgentDefinitionLoader.parseAgentMarkdown(f);
        assertNotNull(def);
        assertEquals(List.of("Edit", "Write", "NotebookEdit"), def.disallowedTools());
    }

    @Test
    void parseAgentMarkdown_noFrontmatter_returnsNull(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("nodoc.md");
        Files.writeString(f, "# Reference doc\n\nThis is just content, no frontmatter.\n");
        assertNull(AgentDefinitionLoader.parseAgentMarkdown(f));
    }

    // ── getAll / loadCustomAgents integration ─────────────────────────────────

    @Test
    void getAll_includesBuiltIns() {
        AgentDefinitionLoader.clearCache();
        List<BuiltInAgentDefinitions.AgentDefinition> all = AgentDefinitionLoader.getAll("/tmp/nonexistent-cwd");
        // Must include built-in agents even when custom dir doesn't exist
        assertTrue(all.stream().anyMatch(a -> Strings.CS.equals(a.agentType(), "general-purpose")));
        assertTrue(all.stream().anyMatch(a -> Strings.CS.equals(a.agentType(), "Explore")));
    }

    @Test
    void getAll_mergesCustomAgents(@TempDir Path tmp) throws IOException {
        // Simulate ~/.claude/agents/ by using cwd/.claude/agents/
        Path agentsDir = tmp.resolve(".claude/agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("custom.md"), """
            ---
            name: my-custom-agent
            description: A custom agent loaded from disk
            color: cyan
            ---
            """);

        AgentDefinitionLoader.clearCache();
        List<BuiltInAgentDefinitions.AgentDefinition> all = AgentDefinitionLoader.getAll(tmp.toString());

        boolean hasCustom = all.stream().anyMatch(a -> Strings.CS.equals(a.agentType(), "my-custom-agent"));
        assertTrue(hasCustom, "custom agent should be loaded from <cwd>/.claude/agents/");

        BuiltInAgentDefinitions.AgentDefinition customDef = all.stream()
            .filter(a -> Strings.CS.equals(a.agentType(), "my-custom-agent"))
            .findFirst().orElseThrow();
        assertEquals("A custom agent loaded from disk", customDef.whenToUse());
        assertEquals("cyan", customDef.color());
    }

    @Test
    void getAll_scansProjectAgentDirectoriesUpToGitRoot(@TempDir Path tmp) throws IOException {
        Path repo = tmp.resolve("repo");
        Path cwd = repo.resolve("packages/app");
        Files.createDirectories(repo.resolve(".git"));
        Path agentsDir = repo.resolve(".claude/agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("root-agent.md"), """
            ---
            name: root-agent
            description: Loaded from repository root
            ---
            """);

        AgentDefinitionLoader.clearCache();

        assertTrue(AgentDefinitionLoader.getAll(cwd.toString()).stream()
            .anyMatch(a -> Strings.CS.equals(a.agentType(), "root-agent")));
    }

    @Test
    void getAll_deduplicatesSamePhysicalAgentThroughSymlinkedAncestor(@TempDir Path tmp)
            throws IOException {
        Path repo = tmp.resolve("repo");
        Path cwd = repo.resolve("packages/app");
        Files.createDirectories(repo.resolve(".git"));
        Path rootAgents = repo.resolve(".claude/agents");
        Files.createDirectories(rootAgents);
        Files.writeString(rootAgents.resolve("shared.md"), """
            ---
            name: shared-physical-agent
            description: Loaded once
            ---
            """);
        Files.createDirectories(cwd.resolve(".claude"));
        try {
            Files.createSymbolicLink(cwd.resolve(".claude/agents"), rootAgents);
        } catch (UnsupportedOperationException | IOException _) {
            return;
        }

        AgentDefinitionLoader.clearCache();
        long count = AgentDefinitionLoader.getAll(cwd.toString()).stream()
            .filter(a -> Strings.CS.equals(a.agentType(), "shared-physical-agent"))
            .count();

        assertEquals(1, count);
    }

    @Test
    void activeFrom_usesOriginalSourcePrecedenceWithManagedHighest() {
        List<BuiltInAgentDefinitions.AgentDefinition> active =
            AgentDefinitionLoader.activeFrom(List.of(
                definition("shared", AgentSource.MANAGED, "managed"),
                definition("shared", AgentSource.USER, "user"),
                definition("shared", AgentSource.PROJECT, "project-near"),
                definition("shared", AgentSource.PROJECT, "project-root"),
                definition("shared", AgentSource.PLUGIN, "plugin"),
                definition("shared", AgentSource.BUILT_IN, "built-in")
            ));

        assertEquals(1, active.size());
        assertEquals(AgentSource.MANAGED, active.getFirst().source());
        assertEquals("managed", active.getFirst().whenToUse());
    }

    private static BuiltInAgentDefinitions.AgentDefinition definition(
            String name, AgentSource source, String marker) {
        return BuiltInAgentDefinitions.AgentDefinition.builder(name, marker)
            .tools(List.of("*")).source(source).build();
    }



    @Test
    void getParseErrors_collectsNamedFileMissingDescription_andStillLoadsRest(@TempDir Path tmp)
            throws IOException {
        Path agentsDir = tmp.resolve(".claude/agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("good.md"), """
            ---
            name: good-agent
            description: Parses fine
            ---
            """);
        Files.writeString(agentsDir.resolve("bad.md"), """
            ---
            name: bad-agent
            ---
            Body without the required description.
            """);
        AgentDefinitionLoader.clearCache();

        List<BuiltInAgentDefinitions.AgentDefinition> all = AgentDefinitionLoader.getAll(tmp.toString());
        // The failing file must not prevent the healthy agent from loading.
        assertTrue(all.stream().anyMatch(a -> Strings.CS.equals(a.agentType(), "good-agent")));
        assertTrue(all.stream().noneMatch(a -> Strings.CS.equals(a.agentType(), "bad-agent")));

        // Filter by tmp prefix so real ~/.claude/agents parse errors don't skew counts.
        List<AgentDefinitionLoader.ParseError> errors =
            AgentDefinitionLoader.getParseErrors(tmp.toString()).stream()
                .filter(e -> Strings.CS.startsWith(e.path(), tmp.toString()))
                .toList();
        assertEquals(1, errors.size(), errors.toString());
        assertEquals(agentsDir.resolve("bad.md").toString(), errors.getFirst().path());

        assertEquals("Missing required \"description\" field in frontmatter", errors.getFirst().error());
    }

    @Test
    void getParseErrors_fileWithoutName_isSilentlySkippedNotReported(@TempDir Path tmp)
            throws IOException {
        Path agentsDir = tmp.resolve(".claude/agents");
        Files.createDirectories(agentsDir);

        Files.writeString(agentsDir.resolve("reference.md"), """
            ---
            description: Just a doc, not an agent
            ---
            Notes.
            """);
        // No frontmatter at all → also silent.
        Files.writeString(agentsDir.resolve("plain.md"), "Plain markdown, no frontmatter.\n");
        AgentDefinitionLoader.clearCache();

        AgentDefinitionLoader.getAll(tmp.toString());
        List<AgentDefinitionLoader.ParseError> errors =
            AgentDefinitionLoader.getParseErrors(tmp.toString()).stream()
                .filter(e -> Strings.CS.startsWith(e.path(), tmp.toString()))
                .toList();
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void getParseErrors_allValid_isEmptyForCwd(@TempDir Path tmp) throws IOException {
        Path agentsDir = tmp.resolve(".claude/agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("fine.md"), """
            ---
            name: fine-agent
            description: All good
            ---
            """);
        AgentDefinitionLoader.clearCache();

        List<AgentDefinitionLoader.ParseError> errors =
            AgentDefinitionLoader.getParseErrors(tmp.toString()).stream()
                .filter(e -> Strings.CS.startsWith(e.path(), tmp.toString()))
                .toList();
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void getParseErrors_sharesCacheLifecycleWithGetAll(@TempDir Path tmp) throws IOException {
        Path agentsDir = tmp.resolve(".claude/agents");
        Files.createDirectories(agentsDir);
        Path bad = agentsDir.resolve("broken.md");
        Files.writeString(bad, """
            ---
            name: broken-agent
            ---
            """);
        AgentDefinitionLoader.clearCache();

        assertTrue(AgentDefinitionLoader.getParseErrors(tmp.toString()).stream()
            .anyMatch(e -> e.path().equals(bad.toString())));

        // Fix the file; without invalidation the cached error must persist...
        Files.writeString(bad, """
            ---
            name: broken-agent
            description: Now fixed
            ---
            """);
        assertTrue(AgentDefinitionLoader.getParseErrors(tmp.toString()).stream()
            .anyMatch(e -> e.path().equals(bad.toString())), "cached errors follow getAll's cache");

//...and clearCache must drop it, same lifecycle as the agent list.
        AgentDefinitionLoader.clearCache();
        assertTrue(AgentDefinitionLoader.getParseErrors(tmp.toString()).stream()
            .noneMatch(e -> e.path().equals(bad.toString())));
        assertTrue(AgentDefinitionLoader.getAll(tmp.toString()).stream()
            .anyMatch(a -> Strings.CS.equals(a.agentType(), "broken-agent")));
    }

    // ── systemPrompt / filePath / source / model capture ────────────────────

    @Test
    void parseAgentMarkdown_capturesSystemPromptBody(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("prompted.md");
        Files.writeString(f, """
            ---
            name: prompted
            description: Has a real system prompt
            ---

            You are a very specific assistant.
            Follow these exact rules.
            """);
        BuiltInAgentDefinitions.AgentDefinition def = AgentDefinitionLoader.parseAgentMarkdown(f);
        assertNotNull(def);
        assertEquals("You are a very specific assistant.\nFollow these exact rules.", def.systemPrompt());
    }

    @Test
    void parseAgentMarkdown_noBody_systemPromptIsNull(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("nobody.md");
        Files.writeString(f, """
            ---
            name: nobody
            description: No body at all
            ---
            """);
        BuiltInAgentDefinitions.AgentDefinition def = AgentDefinitionLoader.parseAgentMarkdown(f);
        assertNotNull(def);
        assertNull(def.systemPrompt());
    }

    @Test
    void parseAgentMarkdown_capturesFilePath(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("pathed.md");
        Files.writeString(f, """
            ---
            name: pathed
            description: Tracks its own file path
            ---
            """);
        BuiltInAgentDefinitions.AgentDefinition def = AgentDefinitionLoader.parseAgentMarkdown(f);
        assertNotNull(def);
        assertEquals(f, def.filePath());
    }

    @Test
    void parseAgentMarkdown_capturesModelField(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("modeled.md");
        Files.writeString(f, """
            ---
            name: modeled
            description: Declares a model
            model: opus
            ---
            """);
        BuiltInAgentDefinitions.AgentDefinition def = AgentDefinitionLoader.parseAgentMarkdown(f);
        assertNotNull(def);
        assertEquals("opus", def.model());
    }

    @Test
    void deriveSource_underUserAgentsDir_isUser() {
        assertEquals(AgentSource.USER,
            AgentDefinitionLoader.deriveSource(ClaudePaths.AGENTS_DIR.resolve("foo.md")));
    }

    @Test
    void deriveSource_elsewhere_isProject(@TempDir Path tmp) {
        assertEquals(AgentSource.PROJECT,
            AgentDefinitionLoader.deriveSource(tmp.resolve(".claude/agents/foo.md")));
    }

    @Test
    void builtInAgents_haveBuiltInSourceAndNoFilePath() {
        for (BuiltInAgentDefinitions.AgentDefinition def : BuiltInAgentDefinitions.getBuiltInAgents()) {
            assertNull(def.filePath(), def.agentType());
            assertEquals(AgentSource.BUILT_IN, def.source(), def.agentType());
            assertTrue(def.isBuiltIn(), def.agentType());
        }
    }

    @Test
    void getActive_projectOverridesUserOverridesBuiltIn(@TempDir Path tmp) throws IOException {
        // "Explore" is a built-in — a same-named project agent must win.
        Path agentsDir = tmp.resolve(".claude/agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("Explore.md"), """
            ---
            name: Explore
            description: A project-level override of the built-in Explore agent
            ---
            """);
        AgentDefinitionLoader.clearCache();

        List<BuiltInAgentDefinitions.AgentDefinition> active = AgentDefinitionLoader.getActive(tmp.toString());
        BuiltInAgentDefinitions.AgentDefinition explore = active.stream()
            .filter(a -> Strings.CS.equals(a.agentType(), "Explore"))
            .findFirst().orElseThrow();
        assertEquals(AgentSource.PROJECT, explore.source());
        assertEquals("A project-level override of the built-in Explore agent", explore.whenToUse());
    }

    @Test
    void parseCliAgents_preservesPromptAndFlagPrecedence(@TempDir Path tmp) {
        AgentDefinitionLoader.setCliAgentsProvider(() -> AgentDefinitionLoader.parseCliAgents(
            "{\"reviewer\":{\"description\":\"CLI reviewer\","
                + "\"prompt\":\"Review every changed file.\",\"tools\":[\"Read\"],"
                + "\"model\":\"haiku\",\"maxTurns\":3}}"));
        try {
            AgentDefinitionLoader.clearCache();
            var reviewer = AgentDefinitionLoader.getActive(tmp.toString()).stream()
                .filter(a -> Strings.CS.equals(a.agentType(), "reviewer"))
                .findFirst().orElseThrow();
            assertEquals(AgentSource.FLAG_SETTINGS, reviewer.source());
            assertEquals("Review every changed file.", reviewer.systemPrompt());
            assertEquals(List.of("Read"), reviewer.tools());
            assertEquals("haiku", reviewer.model());
            assertEquals(3, reviewer.maxTurns());
        } finally {
            AgentDefinitionLoader.setCliAgentsProvider(null);
        }
    }

    @Test
    void parseCliAgents_invalidEntriesAreIgnored() {
        assertTrue(AgentDefinitionLoader.parseCliAgents(
            "{\"missing\":{\"description\":\"only description\"},"
                + "\"valid\":{\"description\":\"d\",\"prompt\":\"p\"}}")
            .stream().anyMatch(a -> Strings.CS.equals(a.agentType(), "valid")));
        assertTrue(AgentDefinitionLoader.parseCliAgents("[]").isEmpty());
    }

    @Test
    void parseCliAgents_preservesWildcardAndDisallowedTools() {
        var agents = AgentDefinitionLoader.parseCliAgents(
            "{\"reviewer\":{\"description\":\"d\",\"prompt\":\"p\"," 
                + "\"tools\":[\"*\"],\"disallowedTools\":[\"Bash\"]}}");
        assertEquals(List.of("*"), agents.getFirst().tools());
        assertEquals(List.of("Bash"), agents.getFirst().disallowedTools());
    }

    @Test
    void parseCliAgents_preservesAgentExtensionFields() {
        var agents = AgentDefinitionLoader.parseCliAgents(
            "{\"extension\":{\"description\":\"d\",\"prompt\":\"p\","
                + "\"effort\":\"low\",\"permissionMode\":\"plan\","
                + "\"memory\":\"project\",\"background\":true,"
                + "\"skills\":[\"keybindings-help\"],"
                + "\"initialPrompt\":\"start\",\"isolation\":\"worktree\","
                + "\"hooks\":{\"SubagentStart\":[]}}}");
        var agent = agents.getFirst();
        assertEquals("low", agent.effort());
        assertEquals("plan", agent.permissionMode());
        assertEquals("project", agent.memory());
        assertTrue(agent.background());
        assertEquals(List.of("keybindings-help"), agent.skills());
        assertEquals("start", agent.initialPrompt());
        assertEquals("worktree", agent.isolation());
        assertNotNull(agent.hooks());
    }
}
