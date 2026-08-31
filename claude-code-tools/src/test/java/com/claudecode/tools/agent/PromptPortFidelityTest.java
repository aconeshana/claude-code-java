package com.claudecode.tools.agent;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.StructuredToolOutput;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.process.SubprocessEnvironment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;
import com.claudecode.tools.bash.BashTool;
import com.claudecode.tools.files.FileEditTool;
import com.claudecode.tools.files.FileReadTool;
import com.claudecode.tools.files.GrepTool;
import com.claudecode.tools.powershell.PowerShellTool;
import com.claudecode.tools.sandbox.NoopSandboxBackend;

class PromptPortFidelityTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void agentToolDescription_isPortedFromTs() {
        // Construct via the prompt class directly — AgentTool requires a wired
// LlmClient/registry that we don't care about for prompt.
        String d = AgentToolPrompt.getPrompt(List.of());
        assertTrue(Strings.CS.contains(d, "Launch a new agent to handle complex"), d);
        assertTrue(Strings.CS.contains(d, "Writing the prompt"), d);
        assertTrue(Strings.CS.contains(d, "Never delegate understanding"));
        assertTrue(d.length() > 2000, "AgentTool description should be substantial, got " + d.length());
    }

    @Test
    void agentToolDescription_doesNotRecommendMissingCodeReviewerAgent() {
        String d = AgentToolPrompt.getPrompt(List.of(
            "- Explore: read-only search",
            "- general-purpose: general agent"), true);

        assertFalse(Strings.CS.contains(d, "subagent_type: \"code-reviewer\""), d);
        assertTrue(Strings.CS.contains(d, "subagent_type: \"general-purpose\""), d);
    }

    @Test
    void agentToolDescription_keepsReleasedExampleWhenCodeReviewerExists() {
        String d = AgentToolPrompt.getPrompt(List.of(
            "- code-reviewer: reviews code",
            "- general-purpose: general agent"), true);

        assertTrue(Strings.CS.contains(d, "subagent_type: \"code-reviewer\""), d);
    }

    @Test
    void agentToolDescription_envOverrideFalse_inlinesAgentList() {
        SubprocessEnvironment.updateSettings(Map.of("CLAUDE_CODE_AGENT_LIST_IN_MESSAGES", "false"));
        try {
            String d = AgentToolPrompt.getPrompt(List.of("- my-custom-agent: does things"));
            assertTrue(Strings.CS.contains(d, "my-custom-agent: does things"), d);
            assertFalse(Strings.CS.contains(d, "Available agent types are listed in <system-reminder>"), d);
        } finally {
            SubprocessEnvironment.clearSettings();
        }
    }

    @Test
    void powerShellToolDescription_isPortedFromTs() {
        String d = new PowerShellTool().description();
        assertTrue(Strings.CS.contains(d, "Executes a given PowerShell command"));
        assertTrue(Strings.CS.contains(d, "PowerShell Syntax Notes"));
        assertTrue(Strings.CS.contains(d, "Interactive and blocking commands"));
        assertTrue(Strings.CS.contains(d, "Passing multiline strings"));
        assertTrue(d.length() > 2000, "PowerShell description should be substantial, got " + d.length());
    }

    @Test
    void grepTool_descriptionAndSchemaMatchTs() {
        GrepTool tool = new GrepTool();
        String d = tool.description();
        assertTrue(Strings.CS.contains(d, "powerful search tool built on ripgrep"), d);
        JsonNode schema = tool.inputSchema();
        JsonNode props = schema.get("properties");

        Set<String> required = Set.of(
            "pattern", "path", "glob", "type", "head_limit", "offset",
            "-A", "-B", "-C", "-n", "-i", "output_mode", "multiline");
        for (String field : required) {
            assertTrue(props.has(field), "GrepTool schema missing field: " + field);
        }
    }

    @Test
    void fileEditTool_acceptsNewParamNamesAndReplaceAll(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("hello.txt");
        Files.writeString(file, "foo bar foo baz\n");

        FileEditTool tool = new FileEditTool();
        ToolExecutionContext ctx = ToolExecutionContext.of(new AbortController(), "test");

        // Read-before-write: FileEditTool now enforces the file was read
        // through this same context first.
        ObjectNode readInput = M.createObjectNode();
        readInput.put("file_path", file.toString());
        new FileReadTool().call(readInput, ctx);

        // Without replace_all → multiple matches should produce an error (plain String).
        ObjectNode input = M.createObjectNode();
        input.put("file_path", file.toString());
        input.put("old_string", "foo");
        input.put("new_string", "FOO");
        String r1 = (String) tool.call(input, ctx);

        // but replace_all is false..."
        assertTrue(Strings.CS.contains(r1, "Found 2 matches of the string to replace"),
            "expected uniqueness error; got: " + r1);

        // With replace_all → every occurrence rewritten (StructuredToolOutput success).
        input.put("replace_all", true);
        String r2 = ((StructuredToolOutput) tool.call(input, ctx)).text();
        assertFalse(Strings.CS.startsWith(r2, "Error"), "replace_all path should succeed; got: " + r2);
        assertEquals("FOO bar FOO baz\n", Files.readString(file));
    }

    @Test
    void fileEditTool_schemaPublishesCanonicalNames() {
        JsonNode schema = new FileEditTool().inputSchema();
        JsonNode props = schema.get("properties");
        assertTrue(props.has("old_string"));
        assertTrue(props.has("new_string"));
        assertTrue(props.has("replace_all"));
        boolean hasOldString = StreamSupport.stream(schema.get("required").spliterator(), false)
            .anyMatch(n -> Strings.CS.equals("old_string", n.asText()));
        assertTrue(hasOldString, "old_string should be in required list");
    }

    @Test
    void bashToolPrompt_matches197GitGuidance() {
// Guard representative phrases from the byte-locked.
        String d = new BashTool().description();
        assertTrue(Strings.CS.contains(d, "# Committing changes with git"),
            d.substring(0, Math.min(200, d.length())));
        assertTrue(Strings.CS.contains(d, "Git Safety Protocol:"));
        assertTrue(Strings.CS.contains(d, "# Creating pull requests"));
        assertTrue(Strings.CS.contains(d, "milliseconds"), "197 documents timeout in ms");
    }

    @Test
    void bashToolPrompt_enabledGateIsByteIdenticalToReleasedDefault() {
        BashTool releasedDefault = new BashTool(_ -> null, new NoopSandboxBackend());
        BashTool explicitEnabled = new BashTool(
            _ -> null, new NoopSandboxBackend(), () -> true);
        assertEquals(releasedDefault.description(), explicitEnabled.description());
    }

    @Test
    void bashToolPrompt_disabledGateRemovesOnlyCommitAndPrSection() {
        BashTool disabled = new BashTool(
            _ -> null, new NoopSandboxBackend(), () -> false);
        String description = disabled.description();

        assertFalse(Strings.CS.contains(description, "# Committing changes with git"));
        assertFalse(Strings.CS.contains(description, "Git Safety Protocol:"));
        assertFalse(Strings.CS.contains(description, "# Creating pull requests"));
        assertTrue(Strings.CS.contains(description, " - For git commands:"),
            "general Bash git safety bullets remain outside the optional commit/PR section");
        assertTrue(Strings.CS.contains(description, "Executes a given bash command"));
        assertTrue(Strings.CS.contains(description, "milliseconds"));
    }

    @Test
    void bashToolCommitAttributionTracksTheCurrentPublicModel() {
        BashTool sonnet45 = new BashTool();
        sonnet45.setModelSupplier(() -> "claude-sonnet-4-5-20250929");
        assertTrue(Strings.CS.contains(sonnet45.description(), 
            "Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"));
        assertFalse(Strings.CS.contains(sonnet45.description(), 
            "Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"));

        BashTool haiku45 = new BashTool();
        haiku45.setModelSupplier(() -> "claude-haiku-4-5-20251001");
        assertTrue(Strings.CS.contains(haiku45.description(), 
            "Co-Authored-By: Claude Haiku 4.5 <noreply@anthropic.com>"));
    }

    @Test
    void bashToolPrompt_deprecatedAttributionSettingRemovesCommitAndPrTrailers() {
        BashTool tool = new BashTool();
        tool.setAttributionSettingsSupplier(() ->
            M.createObjectNode().put("includeCoAuthoredBy", false));

        String description = tool.description();

        assertTrue(Strings.CS.contains(description, "Create the commit with a message."));
        assertFalse(Strings.CS.contains(description, "Co-Authored-By:"));
        assertFalse(Strings.CS.contains(description, "Generated with [Claude Code]"));
        assertTrue(Strings.CS.contains(description, "# Creating pull requests"));
    }

    @Test
    void bashToolPrompt_customAttributionTakesPrecedenceOverDeprecatedSetting() {
        BashTool tool = new BashTool();
        var settings = M.createObjectNode();
        settings.put("includeCoAuthoredBy", false);
        settings.putObject("attribution")
            .put("commit", "Signed-Off-By: Wire Test")
            .put("pr", "Built by Wire Test");
        tool.setAttributionSettingsSupplier(() -> settings);

        String description = tool.description();

        assertTrue(Strings.CS.contains(description, "Signed-Off-By: Wire Test"));
        assertTrue(Strings.CS.contains(description, "Built by Wire Test"));
        assertFalse(Strings.CS.contains(description, "Co-Authored-By:"));
    }
}
