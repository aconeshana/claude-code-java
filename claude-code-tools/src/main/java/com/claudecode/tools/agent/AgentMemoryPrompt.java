package com.claudecode.tools.agent;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.memdir.AutoMemoryPrompt;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.config.ClaudePaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the persistent-memory prompt attached to a custom agent.
 *
 * <ul>
 *   <li> —
 *       {@code loadAgentMemoryPrompt} scope guidance and directory creation.</li>
 *   <li>{@code buildMemoryPrompt}: shared typed
 *       memory instructions plus a synchronous, truncated {@code MEMORY.md}
 *       snapshot.</li>
 * </ul>
 */
public final class AgentMemoryPrompt {

    private AgentMemoryPrompt() {}

    /** Build a prompt for an already-resolved per-agent memory directory. */
    public static String build(Path memoryDir, String scope) {
        if (memoryDir == null) return "";
        try {
            Files.createDirectories(memoryDir);
        } catch (Exception _) {
            // FileWriteTool creates parent directories too; prompt construction
            // remains useful even when this eager mkdir is denied.
        }

        String dir = memoryDir.toString();
        if (!Strings.CS.endsWith(dir, "/")) dir += "/";
        List<String> lines = new ArrayList<>();
        lines.add("# Persistent Agent Memory");
        lines.add("");
        lines.add("You have a persistent, file-based memory system at `" + dir + "`. "
            + AutoMemoryPrompt.DIR_EXISTS_GUIDANCE);
        lines.add("");
        lines.add("You should build up this memory system over time so that future conversations can have a complete picture of who the user is, how they'd like to collaborate with you, what behaviors to avoid or repeat, and the context behind the work the user gives you.");
        lines.add("");
        lines.add("If the user explicitly asks you to remember something, save it immediately as whichever type fits best. If they ask you to forget something, find and remove the relevant entry.");
        lines.add("");
        lines.addAll(AutoMemoryPrompt.TYPES_SECTION_INDIVIDUAL);
        lines.addAll(AutoMemoryPrompt.WHAT_NOT_TO_SAVE_SECTION);
        lines.add("");
        lines.add("## How to save memories");
        lines.add("");
        lines.add("Saving a memory is a two-step process:");
        lines.add("");
        lines.add("**Step 1** — write the memory to its own file (e.g., `user_role.md`, `feedback_testing.md`) using this frontmatter format:");
        lines.add("");

        lines.add("```markdown");
        lines.add("---");
        lines.add("name: {{short-kebab-case-slug}}");
        lines.add("description: {{one-line summary — used to decide relevance in future conversations, so be specific}}");
        lines.add("metadata:");
        lines.add("  type: {{user, feedback, project, reference}}");
        lines.add("---");
        lines.add("");
        lines.add("{{memory content — for feedback/project types, structure as: rule/fact, then **Why:** and **How to apply:** lines. Link related memories with [[their-name]].}}");
        lines.add("```");
        lines.add("");
        lines.add("In the body, link to related memories with `[[name]]`, where `name` is the other memory's `name:` slug. Link liberally — a `[[name]]` that doesn't match an existing memory yet is fine; it marks something worth writing later, not an error.");
        lines.add("");
        lines.add("**Step 2** — add a pointer to that file in `" + AutoMemoryPrompt.ENTRYPOINT_NAME
            + "`. `" + AutoMemoryPrompt.ENTRYPOINT_NAME
            + "` is an index, not a memory — each entry should be one line, under ~150 characters: `- [Title](file.md) — one-line hook`. It has no frontmatter. Never write memory content directly into `"
            + AutoMemoryPrompt.ENTRYPOINT_NAME + "`.");
        lines.add("");
        lines.add("- `" + AutoMemoryPrompt.ENTRYPOINT_NAME
            + "` is always loaded into your conversation context — lines after "
            + AutoMemoryPrompt.MAX_ENTRYPOINT_LINES + " will be truncated, so keep the index concise");
        lines.add("- Keep the name, description, and type fields in memory files up-to-date with the content");
        lines.add("- Organize memory semantically by topic, not chronologically");
        lines.add("- Update or remove memories that turn out to be wrong or outdated");
        lines.add("- Do not write duplicate memories. First check if there is an existing memory you can update before writing a new one.");
        lines.add("");
        lines.add("## When to access memories");
        lines.add("- When memories seem relevant, or the user references prior-conversation work.");
        lines.add("- You MUST access memory when the user explicitly asks you to check, recall, or remember.");
        lines.add("- If the user says to *ignore* or *not use* memory: Do not apply remembered facts, cite, compare against, or mention memory content.");
        lines.add(AutoMemoryPrompt.MEMORY_DRIFT_CAVEAT);
        lines.add("");
        lines.addAll(AutoMemoryPrompt.TRUSTING_RECALL_SECTION);
        lines.add("");
        lines.add("## Memory and other forms of persistence");
        lines.add("Memory is one of several persistence mechanisms available to you as you assist the user in a given conversation. The distinction is often that memory can be recalled in future conversations and should not be used for persisting information that is only useful within the scope of the current conversation.");
        lines.add("- When to use or update a plan instead of memory: If you are about to start a non-trivial implementation task and would like to reach alignment with the user on your approach you should use a Plan rather than saving this information to memory. Similarly, if you already have a plan within the conversation and you have changed your approach persist that change by updating the plan rather than saving a memory.");
        lines.add("- When to use or update tasks instead of memory: When you need to break your work in current conversation into discrete steps or keep track of your progress use tasks instead of saving to memory. Tasks are great for persisting information about the work that needs to be done in the current conversation, but memory should be reserved for information that will be useful in future conversations.");
        lines.add("");
        lines.add(scopeGuidance(scope));
        String coworkGuidance = SubprocessEnvironment.get(
            "CLAUDE_COWORK_MEMORY_EXTRA_GUIDELINES");
        if (StringUtils.isNotBlank(coworkGuidance)) lines.add(coworkGuidance);
        lines.add("");

        String entrypoint = readEntrypoint(memoryDir);
        lines.add("## " + AutoMemoryPrompt.ENTRYPOINT_NAME);
        lines.add("");
        lines.add(StringUtils.isBlank(entrypoint)
            ? "Your " + AutoMemoryPrompt.ENTRYPOINT_NAME
                + " is currently empty. When you save new memories, they will appear here."
            : AutoMemoryPrompt.truncateEntrypoint(entrypoint));
        return String.join("\n", lines);
    }


    public static Path resolveDirectory(String agentType, String scope, Path cwd, Path configHome) {
        if (agentType == null || scope == null || cwd == null) return null;
        String safeName = agentType.replace(':', '-');
        return switch (scope.toLowerCase(Locale.ROOT)) {
            case "project" -> cwd.resolve(".claude").resolve("agent-memory").resolve(safeName);
            case "local" -> cwd.resolve(".claude").resolve("agent-memory-local").resolve(safeName);
            case "user" -> (configHome != null ? configHome : ClaudePaths.CLAUDE_HOME)
                .resolve("agent-memory").resolve(safeName);
            default -> null;
        };
    }

    private static String scopeGuidance(String scope) {
        return switch (scope == null ? "" : scope.toLowerCase(Locale.ROOT)) {
            case "user" -> "- Since this memory is user-scope, keep learnings general since they apply across all projects";
            case "project" -> "- Since this memory is project-scope and shared with your team via version control, tailor your memories to this project";
            case "local" -> "- Since this memory is local-scope (not checked into version control), tailor your memories to this project and machine";
            default -> "";
        };
    }

    private static String readEntrypoint(Path memoryDir) {
        try {
            Path path = memoryDir.resolve(AutoMemoryPrompt.ENTRYPOINT_NAME);
            return Files.isReadable(path) ? Files.readString(path) : null;
        } catch (Exception _) {
            return null;
        }
    }
}
