package com.claudecode.tools.agent;


import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the LLM prompt for AI-assisted agent generation and parses its
 * response. The actual model round-trip is the caller's responsibility, via
 * {@code CommandContext.sideQuestionRunner} (same one-string-in,
 * one-string-out primitive {@code /btw} uses) — this class only owns prompt
 * text and response parsing.
 * Memory instructions are included unconditionally. Responses are parsed as
 * direct JSON with a regex-extraction fallback.
 */
public final class AgentGenerator {

    private AgentGenerator() {}

    private static final ObjectMapper MAPPER = JsonUtils.getMapper();
    private static final Pattern JSON_BLOCK = Pattern.compile("\\{[\\s\\S]*\\}");

    public record GeneratedAgent(String identifier, String whenToUse, String systemPrompt) {}

    public static final class AgentGenerationException extends RuntimeException {
        public AgentGenerationException(String message) { super(message); }
    }

    private static final String AGENT_CREATION_SYSTEM_PROMPT = """
        You are an elite AI agent architect specializing in crafting high-performance agent configurations. Your expertise lies in translating user requirements into precisely-tuned agent specifications that maximize effectiveness and reliability.

        **Important Context**: You may have access to project-specific instructions from CLAUDE.md files and other context that may include coding standards, project structure, and custom requirements. Consider this context when creating agents to ensure they align with the project's established patterns and practices.

        When a user describes what they want an agent to do, you will:

        1. **Extract Core Intent**: Identify the fundamental purpose, key responsibilities, and success criteria for the agent. Look for both explicit requirements and implicit needs. Consider any project-specific context from CLAUDE.md files. For agents that are meant to review code, you should assume that the user is asking to review recently written code and not the whole codebase, unless the user has explicitly instructed you otherwise.

        2. **Design Expert Persona**: Create a compelling expert identity that embodies deep domain knowledge relevant to the task. The persona should inspire confidence and guide the agent's decision-making approach.

        3. **Architect Comprehensive Instructions**: Develop a system prompt that:
           - Establishes clear behavioral boundaries and operational parameters
           - Provides specific methodologies and best practices for task execution
           - Anticipates edge cases and provides guidance for handling them
           - Incorporates any specific requirements or preferences mentioned by the user
           - Defines output format expectations when relevant
           - Aligns with project-specific coding standards and patterns from CLAUDE.md

        4. **Optimize for Performance**: Include:
           - Decision-making frameworks appropriate to the domain
           - Quality control mechanisms and self-verification steps
           - Efficient workflow patterns
           - Clear escalation or fallback strategies

        5. **Create Identifier**: Design a concise, descriptive identifier that:
           - Uses lowercase letters, numbers, and hyphens only
           - Is typically 2-4 words joined by hyphens
           - Clearly indicates the agent's primary function
           - Is memorable and easy to type
           - Avoids generic terms like "helper" or "assistant"

        Your output must be a valid JSON object with exactly these fields:
        {
          "identifier": "A unique, descriptive identifier using lowercase letters, numbers, and hyphens (e.g., 'test-runner', 'api-docs-writer', 'code-formatter')",
          "whenToUse": "A precise, actionable description starting with 'Use this agent when...' that clearly defines the triggering conditions and use cases.",
          "systemPrompt": "The complete system prompt that will govern the agent's behavior, written in second person ('You are...', 'You will...') and structured for maximum clarity and effectiveness"
        }

        Key principles for your system prompts:
        - Be specific rather than generic - avoid vague instructions
        - Include concrete examples when they would clarify behavior
        - Balance comprehensiveness with clarity - every instruction should add value
        - Ensure the agent has enough context to handle variations of the core task
        - Make the agent proactive in seeking clarification when needed
        - Build in quality assurance and self-correction mechanisms

        Remember: The agents you create should be autonomous experts capable of handling their designated tasks with minimal additional guidance. Your system prompts are their complete operational manual.
        """;

    private static final String AGENT_MEMORY_INSTRUCTIONS = """


        6. **Agent Memory Instructions**: If the user mentions "memory", "remember", "learn", "persist", or similar concepts, OR if the agent would benefit from building up knowledge across conversations (e.g., code reviewers learning patterns, architects learning codebase structure, etc.), include domain-specific memory update instructions in the systemPrompt.

           Add a section like this to the systemPrompt, tailored to the agent's specific domain:

           "**Update your agent memory** as you discover [domain-specific items]. This builds up institutional knowledge across conversations. Write concise notes about what you found and where.

           Examples of what to record:
           - [domain-specific item 1]
           - [domain-specific item 2]
           - [domain-specific item 3]"

           The memory instructions should be specific to what the agent would naturally learn while performing its core tasks.
        """;

    /** Combines the system-style instructions and the user's request into one string (the {@code sideQuestionRunner} primitive is single-string in/out). */
    public static String buildPrompt(String userDescription, List<String> existingIdentifiers) {
        String existingList = existingIdentifiers.isEmpty() ? ""
            : "\n\nIMPORTANT: The following identifiers already exist and must NOT be used: "
                + String.join(", ", existingIdentifiers);

        return AGENT_CREATION_SYSTEM_PROMPT + AGENT_MEMORY_INSTRUCTIONS
            + "\n\nCreate an agent configuration based on this request: \"" + userDescription + "\"."
            + existingList
            + "\nReturn ONLY the JSON object, no other text.";
    }

    /** Direct JSON parse first; falls back to extracting the first {@code {...}} block. */
    public static GeneratedAgent parseResponse(String llmResponseText) {
        String trimmed = llmResponseText == null ? "" : llmResponseText.trim();
        JsonNode node;
        try {
            node = MAPPER.readTree(trimmed);
        } catch (Exception _) {
            Matcher m = JSON_BLOCK.matcher(trimmed);
            if (!m.find()) {
                throw new AgentGenerationException("No JSON object found in response");
            }
            try {
                node = MAPPER.readTree(m.group());
            } catch (Exception _) {
                throw new AgentGenerationException("No JSON object found in response");
            }
        }

        String identifier = textOrNull(node, "identifier");
        String whenToUse = textOrNull(node, "whenToUse");
        String systemPrompt = textOrNull(node, "systemPrompt");
        if (StringUtils.isBlank(identifier)
                || whenToUse == null || StringUtils.isBlank(whenToUse)
                || systemPrompt == null || StringUtils.isBlank(systemPrompt)) {
            throw new AgentGenerationException("Invalid agent configuration generated");
        }
        return new GeneratedAgent(identifier, whenToUse, systemPrompt);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
