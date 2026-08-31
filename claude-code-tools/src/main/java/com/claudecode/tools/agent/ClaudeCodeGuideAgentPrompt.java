package com.claudecode.tools.agent;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * System-prompt builder for the built-in {@code claude-code-guide} agent.
 */
public final class ClaudeCodeGuideAgentPrompt {

    record Command(String name, String description) {}

    record Agent(String agentType, String whenToUse) {}

    record Context(
            List<Command> customSkills,
            List<Agent> customAgents,
            List<Command> pluginSkills,
            JsonNode settings) {
        Context {
            customSkills = customSkills == null ? List.of() : List.copyOf(customSkills);
            customAgents = customAgents == null ? List.of() : List.copyOf(customAgents);
            pluginSkills = pluginSkills == null ? List.of() : List.copyOf(pluginSkills);
        }

        static Context empty() {
            return new Context(List.of(), List.of(), List.of(), null);
        }
    }


    public static final String CLAUDE_CODE_DOCS_MAP_URL =
        "https://code.claude.com/docs/en/claude_code_docs_map.md";


    public static final String CDP_DOCS_MAP_URL =
        "https://platform.claude.com/llms.txt";


    public static final String AGENT_TYPE = "claude-code-guide";

    private ClaudeCodeGuideAgentPrompt() {}

    /**
     * Base prompt + feedback guideline convenience overload for callers with
     * no user-configuration context. Production guide agents use the context
     * overload below.
     *
     * @param isUsing3PServices true if this session is using
     *                          a non-first-party provider (the {@code /feedback}
     *                          command is disabled → direct users to the
     *                          GitHub issues page instead).
     */
    public static String build(boolean isUsing3PServices) {
        return build(isUsing3PServices, Context.empty());
    }


    static String build(boolean isUsing3PServices, Context context) {
        String basePromptWithFeedback = buildBasePrompt() + "\n"
            + buildFeedbackGuideline(isUsing3PServices);
        Context resolved = context == null ? Context.empty() : context;
        List<String> sections = new ArrayList<>();
        addCommandSection(sections, "**Available custom skills in this project:**",
            resolved.customSkills());
        if (!resolved.customAgents().isEmpty()) {
            StringBuilder agents = new StringBuilder("**Available custom agents configured:**");
            for (Agent agent : resolved.customAgents()) {
                agents.append("\n- ").append(agent.agentType()).append(": ")
                    .append(agent.whenToUse());
            }
            sections.add(agents.toString());
        }
        addCommandSection(sections, "**Available plugin skills:**", resolved.pluginSkills());
        if (resolved.settings() != null && resolved.settings().isObject()
                && !resolved.settings().isEmpty()) {
            sections.add("**User's settings.json:**\n```json\n"
                + stringifyLikeJavaScript(resolved.settings()) + "\n```");
        }
        if (sections.isEmpty()) return basePromptWithFeedback;
        return basePromptWithFeedback
            + "\n\n---\n\n# User's Current Configuration\n\n"
            + "The user has the following custom setup in their environment:\n\n"
            + String.join("\n\n", sections)
            + "\n\nWhen answering questions, consider these configured features and proactively "
            + "suggest them when relevant.";
    }

    private static void addCommandSection(List<String> sections, String heading,
                                          List<Command> commands) {
        if (commands.isEmpty()) return;
        StringBuilder out = new StringBuilder(heading);
        for (Command command : commands) {
            out.append("\n- /").append(command.name()).append(": ")
                .append(command.description());
        }
        sections.add(out.toString());
    }


    static String buildBasePrompt() {
        String localSearchHint = "Read, `find`, and `grep`";
        return "You are the Claude guide agent. Your primary responsibility is helping "
            + "users understand and use Claude Code, the Claude Agent SDK, and the "
            + "Claude API (formerly the Anthropic API) effectively.\n"
            + "\n"
            + "**Your expertise spans three domains:**\n"
            + "\n"
            + "1. **Claude Code** (the CLI tool): Installation, configuration, hooks, "
            + "skills, MCP servers, keyboard shortcuts, IDE integrations, settings, "
            + "and workflows.\n"
            + "\n"
            + "2. **Claude Agent SDK**: A framework for building custom AI agents "
            + "based on Claude Code technology. Available for Node.js/TypeScript and "
            + "Python.\n"
            + "\n"
            + "3. **Claude API**: The Claude API (formerly known as the Anthropic API) "
            + "for direct model interaction, tool use, and integrations.\n"
            + "\n"
            + "**Documentation sources:**\n"
            + "\n"
            + "- **Claude Code docs** (" + CLAUDE_CODE_DOCS_MAP_URL + "): Fetch this "
            + "for questions about the Claude Code CLI tool, including:\n"
            + "  - Installation, setup, and getting started\n"
            + "  - Hooks (pre/post command execution)\n"
            + "  - Custom skills\n"
            + "  - MCP server configuration\n"
            + "  - IDE integrations (VS Code, JetBrains)\n"
            + "  - Settings files and configuration\n"
            + "  - Keyboard shortcuts and hotkeys\n"
            + "  - Subagents and plugins\n"
            + "  - Sandboxing and security\n"
            + "\n"
            + "- **Claude Agent SDK docs** (" + CDP_DOCS_MAP_URL + "): Fetch this for "
            + "questions about building agents with the SDK, including:\n"
            + "  - SDK overview and getting started (Python and TypeScript)\n"
            + "  - Agent configuration + custom tools\n"
            + "  - Session management and permissions\n"
            + "  - MCP integration in agents\n"
            + "  - Hosting and deployment\n"
            + "  - Cost tracking and context management\n"
            + "  Note: Agent SDK docs are part of the Claude API documentation at the "
            + "same URL.\n"
            + "\n"
            + "- **Claude API docs** (" + CDP_DOCS_MAP_URL + "): Fetch this for "
            + "questions about the Claude API (formerly the Anthropic API), including:\n"
            + "  - Messages API and streaming\n"
            + "  - Tool use (function calling) and Anthropic-defined tools (computer "
            + "use, code execution, web search, text editor, bash, programmatic tool "
            + "calling, tool search tool, context editing, Files API, structured "
            + "outputs)\n"
            + "  - Vision, PDF support, and citations\n"
            + "  - Extended thinking and structured outputs\n"
            + "  - MCP connector for remote MCP servers\n"
            + "  - Cloud provider integrations (Bedrock, Vertex AI, Foundry)\n"
            + "\n"
            + "**Approach:**\n"
            + "1. Determine which domain the user's question falls into\n"
            + "2. Use WebFetch to fetch the appropriate docs map\n"
            + "3. Identify the most relevant documentation URLs from the map\n"
            + "4. Fetch the specific documentation pages\n"
            + "5. Provide clear, actionable guidance based on official documentation\n"
            + "6. Use WebSearch if docs don't cover the topic\n"
            + "7. Reference local project files (CLAUDE.md, .claude/ directory) when "
            + "relevant using " + localSearchHint + "\n"
            + "\n"
            + "**Guidelines:**\n"
            + "- Always prioritize official documentation over assumptions\n"
            + "- Your training data about Claude Code commands, flags, and settings may be "
            + "out of date. If WebFetch or WebSearch fail or you cannot reach the "
            + "documentation, do not silently answer from memory: tell the user you could "
            + "not reach the documentation, give the best answer you have, and explicitly "
            + "note it may be out of date with a link to https://code.claude.com/docs.\n"
            + "- Keep responses concise and actionable\n"
            + "- Include specific examples or code snippets when helpful\n"
            + "- Reference exact documentation URLs in your responses\n"
            + "- Help users discover features by proactively suggesting related "
            + "commands, shortcuts, or capabilities\n"
            + "\n"
            + "Complete the user's request by providing accurate, documentation-based "
            + "guidance.";
    }


    static String buildFeedbackGuideline(boolean isUsing3PServices) {
        if (isUsing3PServices) {
            return "- When you cannot find an answer or the feature doesn't exist, "
                + "direct the user to report the issue at "
                + "https://github.com/anthropics/claude-code/issues";
        }
        return "- When you cannot find an answer or the feature doesn't exist, "
            + "direct the user to use /feedback to report a feature request or bug";
    }

    private static String stringifyLikeJavaScript(JsonNode settings) {
        try {
            return JsonUtils.getMapper().writer(new JavaScriptPrettyPrinter())
                .writeValueAsString(settings);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Matches {@code JSON.stringify(value, null, 2)} punctuation and indentation. */
    private static final class JavaScriptPrettyPrinter extends DefaultPrettyPrinter {
        JavaScriptPrettyPrinter() {
            DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
            indentObjectsWith(indenter);
            indentArraysWith(indenter);
        }

        @Override public DefaultPrettyPrinter createInstance() {
            return new JavaScriptPrettyPrinter();
        }

        @Override public void writeObjectFieldValueSeparator(JsonGenerator generator)
                throws IOException {
            generator.writeRaw(": ");
        }

        @Override public void writeEndObject(JsonGenerator generator, int entries)
                throws IOException {
            if (!_objectIndenter.isInline()) --_nesting;
            if (entries > 0) _objectIndenter.writeIndentation(generator, _nesting);
            generator.writeRaw('}');
        }

        @Override public void writeEndArray(JsonGenerator generator, int values)
                throws IOException {
            if (!_arrayIndenter.isInline()) --_nesting;
            if (values > 0) _arrayIndenter.writeIndentation(generator, _nesting);
            generator.writeRaw(']');
        }
    }
}
