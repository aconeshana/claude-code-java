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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgentFileWriter}.
 */
class AgentFileWriterTest {

    @Test
    void formatAsMarkdown_escapesQuotesBackslashesNewlines() {
        String md = AgentFileWriter.formatAsMarkdown(
            "my-agent", "Use when \"quoted\" and has\\backslash and\nnewline",
            List.of("Read"), "prompt body", null, null, null);
        assertTrue(Strings.CS.contains(md, "description: \"Use when \\\"quoted\\\" and has\\\\backslash and\\nnewline\""), md);
    }

    @Test
    void formatAsMarkdown_omitsToolsLineForWildcardOrNull() {
        String withNull = AgentFileWriter.formatAsMarkdown("a", "desc", null, "body", null, null, null);
        assertFalse(Strings.CS.contains(withNull, "tools:"), withNull);

        String withWildcard = AgentFileWriter.formatAsMarkdown("a", "desc", List.of("*"), "body", null, null, null);
        assertFalse(Strings.CS.contains(withWildcard, "tools:"), withWildcard);

        String withList = AgentFileWriter.formatAsMarkdown("a", "desc", List.of("Read", "Bash"), "body", null, null, null);
        assertTrue(Strings.CS.contains(withList, "tools: Read, Bash"), withList);
    }

    @Test
    void formatAsMarkdown_omitsBlankOptionalLines() {
        String md = AgentFileWriter.formatAsMarkdown("a", "desc", List.of("*"), "body", null, null, null);
        assertFalse(Strings.CS.contains(md, "model:"));
        assertFalse(Strings.CS.contains(md, "color:"));
        assertFalse(Strings.CS.contains(md, "memory:"));
    }

    @Test
    void formatAsMarkdown_includesAllFieldsWhenSet() {
        String md = AgentFileWriter.formatAsMarkdown("a", "desc", List.of("Read"), "body", "cyan", "opus", "project");
        assertTrue(Strings.CS.contains(md, "model: opus"), md);
        assertTrue(Strings.CS.contains(md, "color: cyan"), md);
        assertTrue(Strings.CS.contains(md, "memory: project"), md);
        assertTrue(Strings.CS.endsWith(md, "body\n"), md);
    }

    @Test
    void save_createsDirectoryIfMissing_andWritesFile(@TempDir Path tmp) throws IOException {
        AgentFileWriter.save(AgentSource.PROJECT, tmp.toString(), "new-agent", "desc",
            List.of("Read"), "prompt body", null, null, null, true);

        Path expected = tmp.resolve(".claude/agents/new-agent.md");
        assertTrue(Files.isReadable(expected));
        assertTrue(Strings.CS.contains(Files.readString(expected), "name: new-agent"));
    }

    @Test
    void save_checkExistsTrue_throwsOnDuplicate(@TempDir Path tmp) throws IOException {
        AgentFileWriter.save(AgentSource.PROJECT, tmp.toString(), "dup", "desc",
            List.of("Read"), "body", null, null, null, true);

        assertThrows(AgentFileWriter.AgentFileException.class, () ->
            AgentFileWriter.save(AgentSource.PROJECT, tmp.toString(), "dup", "desc2",
                List.of("Read"), "body2", null, null, null, true));
    }

    @Test
    void save_checkExistsFalse_overwrites(@TempDir Path tmp) throws IOException {
        AgentFileWriter.save(AgentSource.PROJECT, tmp.toString(), "over", "first",
            List.of("Read"), "body", null, null, null, true);
        AgentFileWriter.save(AgentSource.PROJECT, tmp.toString(), "over", "second",
            List.of("Read"), "body", null, null, null, false);

        Path f = tmp.resolve(".claude/agents/over.md");
        assertTrue(Strings.CS.contains(Files.readString(f), "second"));
    }

    @Test
    void save_rejectsBuiltIn(@TempDir Path tmp) {
        assertThrows(AgentFileWriter.AgentFileException.class, () ->
            AgentFileWriter.save(AgentSource.BUILT_IN, tmp.toString(), "x", "desc",
                List.of("*"), "body", null, null, null, true));
    }

    @Test
    void update_overwritesExistingFile(@TempDir Path tmp) throws IOException {
        Path agentsDir = tmp.resolve(".claude/agents");
        Files.createDirectories(agentsDir);
        Path f = agentsDir.resolve("editable.md");
        Files.writeString(f, "---\nname: editable\ndescription: \"old\"\n---\n\nold body\n");
        var def = BuiltInAgentDefinitions.AgentDefinition.builder("editable", "old")
            .tools(List.of("*")).systemPrompt("old body")
            .source(AgentSource.PROJECT).filePath(f).build();

        AgentFileWriter.update(def, "new desc", List.of("Bash"), "new body", "red", "opus", "user");

        String content = Files.readString(f);
        assertTrue(Strings.CS.contains(content, "new desc"), content);
        assertTrue(Strings.CS.contains(content, "new body"), content);
        assertTrue(Strings.CS.contains(content, "tools: Bash"), content);
    }

    @Test
    void update_rejectsBuiltIn() {
        var def = BuiltInAgentDefinitions.AgentDefinition.builder("x", "desc")
            .tools(List.of("*")).build();
        assertThrows(AgentFileWriter.AgentFileException.class, () ->
            AgentFileWriter.update(def, "d", List.of("*"), "b", null, null, null));
    }

    @Test
    void delete_removesFile(@TempDir Path tmp) throws IOException {
        Path agentsDir = tmp.resolve(".claude/agents");
        Files.createDirectories(agentsDir);
        Path f = agentsDir.resolve("gone.md");
        Files.writeString(f, "content");
        var def = BuiltInAgentDefinitions.AgentDefinition.builder("gone", "desc")
            .tools(List.of("*")).source(AgentSource.PROJECT).filePath(f).build();

        AgentFileWriter.delete(def);
        assertFalse(Files.exists(f));
    }

    @Test
    void delete_ignoresMissingFile(@TempDir Path tmp) {
        Path f = tmp.resolve(".claude/agents/missing.md");
        var def = BuiltInAgentDefinitions.AgentDefinition.builder("missing", "desc")
            .tools(List.of("*")).source(AgentSource.PROJECT).filePath(f).build();

        assertDoesNotThrow(() -> AgentFileWriter.delete(def));
    }

    @Test
    void delete_rejectsBuiltIn() {
        var def = BuiltInAgentDefinitions.AgentDefinition.builder("x", "desc")
            .tools(List.of("*")).build();
        assertThrows(AgentFileWriter.AgentFileException.class, () -> AgentFileWriter.delete(def));
    }

    @Test
    void directoryFor_userAndProject(@TempDir Path tmp) {
        assertEquals(ClaudePaths.AGENTS_DIR, AgentFileWriter.directoryFor(AgentSource.USER, tmp.toString()));
        assertEquals(tmp.resolve(".claude").resolve("agents"), AgentFileWriter.directoryFor(AgentSource.PROJECT, tmp.toString()));
        assertThrows(IllegalArgumentException.class, () -> AgentFileWriter.directoryFor(AgentSource.BUILT_IN, tmp.toString()));
    }
}
