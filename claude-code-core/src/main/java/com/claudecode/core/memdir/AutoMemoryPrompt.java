package com.claudecode.core.memdir;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.git.GitUtils;
import com.claudecode.core.process.SubprocessEnvironment;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Auto-memory prompt builder.
 */
public final class AutoMemoryPrompt {

/** Filename for the always-loaded index. matches {@code ENTRYPOINT_NAME}. */
    public static final String ENTRYPOINT_NAME = "MEMORY.md";

    /** Hard cap on entrypoint lines before truncation. */
    public static final int MAX_ENTRYPOINT_LINES = 200;

    /** Hard cap on entrypoint bytes before truncation. */
    public static final int MAX_ENTRYPOINT_BYTES = 25_000;

    public static final String DIR_EXISTS_GUIDANCE =
        "This directory already exists — write to it directly with the Write tool (do not run mkdir or check for its existence).";

    public static final List<String> MEMORY_TYPES = List.of(
        "user", "feedback", "project", "reference");



    public static final List<String> TYPES_SECTION_INDIVIDUAL = List.of(
        "## Types of memory",
        "",
        "There are several discrete types of memory that you can store in your memory system:",
        "",
        "<types>",
        "<type>",
        "    <name>user</name>",
        "    <description>Contain information about the user's role, goals, responsibilities, and knowledge. Great user memories help you tailor your future behavior to the user's preferences and perspective. Your goal in reading and writing these memories is to build up an understanding of who the user is and how you can be most helpful to them specifically. For example, you should collaborate with a senior software engineer differently than a student who is coding for the very first time. Keep in mind, that the aim here is to be helpful to the user. Avoid writing memories about the user that could be viewed as a negative judgement or that are not relevant to the work you're trying to accomplish together.</description>",
        "    <when_to_save>When you learn any details about the user's role, preferences, responsibilities, or knowledge</when_to_save>",
        "    <how_to_use>When your work should be informed by the user's profile or perspective. For example, if the user is asking you to explain a part of the code, you should answer that question in a way that is tailored to the specific details that they will find most valuable or that helps them build their mental model in relation to domain knowledge they already have.</how_to_use>",
        "    <examples>",
        "    user: I'm a data scientist investigating what logging we have in place",
        "    assistant: [saves user memory: user is a data scientist, currently focused on observability/logging]",
        "",
        "    user: I've been writing Go for ten years but this is my first time touching the React side of this repo",
        "    assistant: [saves user memory: deep Go expertise, new to React and this project's frontend — frame frontend explanations in terms of backend analogues]",
        "    </examples>",
        "</type>",
        "<type>",
        "    <name>feedback</name>",
        "    <description>Guidance the user has given you about how to approach work — both what to avoid and what to keep doing. These are a very important type of memory to read and write as they allow you to remain coherent and responsive to the way you should approach work in the project. Record from failure AND success: if you only save corrections, you will avoid past mistakes but drift away from approaches the user has already validated, and may grow overly cautious.</description>",
        "    <when_to_save>Any time the user corrects your approach (\"no not that\", \"don't\", \"stop doing X\") OR confirms a non-obvious approach worked (\"yes exactly\", \"perfect, keep doing that\", accepting an unusual choice without pushback). Corrections are easy to notice; confirmations are quieter — watch for them. In both cases, save what is applicable to future conversations, especially if surprising or not obvious from the code. Include *why* so you can judge edge cases later.</when_to_save>",
        "    <how_to_use>Let these memories guide your behavior so that the user does not need to offer the same guidance twice.</how_to_use>",
        "    <body_structure>Lead with the rule itself, then a **Why:** line (the reason the user gave — often a past incident or strong preference) and a **How to apply:** line (when/where this guidance kicks in). Knowing *why* lets you judge edge cases instead of blindly following the rule.</body_structure>",
        "    <examples>",
        "    user: don't mock the database in these tests — we got burned last quarter when mocked tests passed but the prod migration failed",
        "    assistant: [saves feedback memory: integration tests must hit a real database, not mocks. Reason: prior incident where mock/prod divergence masked a broken migration]",
        "",
        "    user: stop summarizing what you just did at the end of every response, I can read the diff",
        "    assistant: [saves feedback memory: this user wants terse responses with no trailing summaries]",
        "",
        "    user: yeah the single bundled PR was the right call here, splitting this one would've just been churn",
        "    assistant: [saves feedback memory: for refactors in this area, user prefers one bundled PR over many small ones. Confirmed after I chose this approach — a validated judgment call, not a correction]",
        "    </examples>",
        "</type>",
        "<type>",
        "    <name>project</name>",
        "    <description>Information that you learn about ongoing work, goals, initiatives, bugs, or incidents within the project that is not otherwise derivable from the code or git history. Project memories help you understand the broader context and motivation behind the work the user is doing within this working directory.</description>",
        "    <when_to_save>When you learn who is doing what, why, or by when. These states change relatively quickly so try to keep your understanding of this up to date. Always convert relative dates in user messages to absolute dates when saving (e.g., \"Thursday\" → \"2026-03-05\"), so the memory remains interpretable after time passes.</when_to_save>",
        "    <how_to_use>Use these memories to more fully understand the details and nuance behind the user's request and make better informed suggestions.</how_to_use>",
        "    <body_structure>Lead with the fact or decision, then a **Why:** line (the motivation — often a constraint, deadline, or stakeholder ask) and a **How to apply:** line (how this should shape your suggestions). Project memories decay fast, so the why helps future-you judge whether the memory is still load-bearing.</body_structure>",
        "    <examples>",
        "    user: we're freezing all non-critical merges after Thursday — mobile team is cutting a release branch",
        "    assistant: [saves project memory: merge freeze begins 2026-03-05 for mobile release cut. Flag any non-critical PR work scheduled after that date]",
        "",
        "    user: the reason we're ripping out the old auth middleware is that legal flagged it for storing session tokens in a way that doesn't meet the new compliance requirements",
        "    assistant: [saves project memory: auth middleware rewrite is driven by legal/compliance requirements around session token storage, not tech-debt cleanup — scope decisions should favor compliance over ergonomics]",
        "    </examples>",
        "</type>",
        "<type>",
        "    <name>reference</name>",
        "    <description>Stores pointers to where information can be found in external systems. These memories allow you to remember where to look to find up-to-date information outside of the project directory.</description>",
        "    <when_to_save>When you learn about resources in external systems and their purpose. For example, that bugs are tracked in a specific project in Linear or that feedback can be found in a specific Slack channel.</when_to_save>",
        "    <how_to_use>When the user references an external system or information that may be in an external system.</how_to_use>",
        "    <examples>",
        "    user: check the Linear project \"INGEST\" if you want context on these tickets, that's where we track all pipeline bugs",
        "    assistant: [saves reference memory: pipeline bugs are tracked in Linear project \"INGEST\"]",
        "",
        "    user: the Grafana board at grafana.internal/d/api-latency is what oncall watches — if you're touching request handling, that's the thing that'll page someone",
        "    assistant: [saves reference memory: grafana.internal/d/api-latency is the oncall latency dashboard — check it when editing request-path code]",
        "    </examples>",
        "</type>",
        "</types>",
        "");

    public static final List<String> WHAT_NOT_TO_SAVE_SECTION = List.of(
        "## What NOT to save in memory",
        "",
        "- Code patterns, conventions, architecture, file paths, or project structure — these can be derived by reading the current project state.",
        "- Git history, recent changes, or who-changed-what — `git log` / `git blame` are authoritative.",
        "- Debugging solutions or fix recipes — the fix is in the code; the commit message has the context.",
        "- Anything already documented in CLAUDE.md files.",
        "- Ephemeral task details: in-progress work, temporary state, current conversation context.",
        "",
        "These exclusions apply even when the user explicitly asks you to save. If they ask you to save a PR list or activity summary, ask what was *surprising* or *non-obvious* about it — that is the part worth keeping.");

    public static final String MEMORY_DRIFT_CAVEAT =
        "- Memory records can become stale over time. Use memory as context for what was true at a given point in time. Before answering the user or building assumptions based solely on information in memory records, verify that the memory is still correct and up-to-date by reading the current state of the files or resources. If a recalled memory conflicts with current information, trust what you observe now — and update or remove the stale memory rather than acting on it.";

    public static final List<String> WHEN_TO_ACCESS_SECTION = List.of(
        "## When to access memories",
        "- When memories seem relevant, or the user references prior-conversation work.",
        "- You MUST access memory when the user explicitly asks you to check, recall, or remember.",
        "- If the user says to *ignore* or *not use* memory: proceed as if MEMORY.md were empty. Do not apply remembered facts, cite, compare against, or mention memory content.",
        MEMORY_DRIFT_CAVEAT);

    public static final List<String> TRUSTING_RECALL_SECTION = List.of(
        "## Before recommending from memory",
        "",
        "A memory that names a specific function, file, or flag is a claim that it existed *when the memory was written*. It may have been renamed, removed, or never merged. Before recommending it:",
        "",
        "- If the memory names a file path: check the file exists.",
        "- If the memory names a function or flag: grep for it.",
        "- If the user is about to act on your recommendation (not just asking about history), verify first.",
        "",
        "\"The memory says X exists\" is not the same as \"X exists now.\"",
        "",
        "A memory that summarizes repo state (activity logs, architecture snapshots) is frozen in time. If the user asks about *recent* or *current* state, prefer `git log` or reading the code over recalling the snapshot.");

    public static final List<String> MEMORY_FRONTMATTER_EXAMPLE = List.of(
        "```markdown",
        "---",
        "name: {{memory name}}",
        "description: {{one-line description — used to decide relevance in future conversations, so be specific}}",
        "type: {{" + String.join(", ", MEMORY_TYPES) + "}}",
        "---",
        "",
        "{{memory content — for feedback/project types, structure as: rule/fact, then **Why:** and **How to apply:** lines}}",
        "```");

    private AutoMemoryPrompt() {}



    /**
     * Memoized (per cwd) auto-memory directory, created on first resolution.
     */
    private static final ConcurrentHashMap<String, Path> ENSURED_DIRS =
        new ConcurrentHashMap<>();

    /** Resolve + create-once the auto-memory dir for {@code workingDirectory}. */
    public static Path ensureAutoMemDir(Path workingDirectory) {
        return ensureAutoMemDir(workingDirectory, null);
    }

    /**
     * Test seam: {@code baseOverride} replaces the {@code ~/.claude} base so
     * tests never create directories under the developer's real config home
     * (static-final-path test-pollution lesson). Production callers pass null.
     */
    public static Path ensureAutoMemDir(Path workingDirectory, Path baseOverride) {
        String key = (baseOverride != null ? baseOverride.toAbsolutePath() + "|" : "")
            + workingDirectory.toAbsolutePath();
        return ENSURED_DIRS.computeIfAbsent(key, _ -> {
            Path dir = baseOverride != null
                ? baseOverride.resolve("projects")
                    .resolve(workingDirectory.toAbsolutePath().toString()
                        .replaceAll("[^a-zA-Z0-9]", "-"))
                    .resolve("memory")
                : resolveAutoMemPath(workingDirectory);
            try {
                Files.createDirectories(dir);
            } catch (Exception _) {
                // Leave the entry cached anyway — the section is still
                // emitted and Write creates parents on demand.
            }
            return dir;
        });
    }




    public static String memorySection197(Path memoryDir) {
        String dirStr = memoryDir.toString();
        if (!Strings.CS.endsWith(dirStr, "/")) dirStr = dirStr + "/";
        return "# Memory\n"
            + "\n"
            + "You have a persistent file-based memory at `" + dirStr + "`. This directory already exists — write to it directly with the Write tool (do not run mkdir or check for its existence). Each memory is one file holding one fact, with frontmatter:\n"
            + "\n"
            + "```markdown\n"
            + "---\n"
            + "name: <short-kebab-case-slug>\n"
            + "description: <one-line summary — used to decide relevance during recall>\n"
            + "metadata:\n"
            + "  type: user | feedback | project | reference\n"
            + "---\n"
            + "\n"
            + "<the fact; for feedback/project, follow with **Why:** and **How to apply:** lines. Link related memories with [[their-name]].>\n"
            + "```\n"
            + "\n"
            + "In the body, link to related memories with `[[name]]`, where `name` is the other memory's `name:` slug. Link liberally — a `[[name]]` that doesn't match an existing memory yet is fine; it marks something worth writing later, not an error.\n"
            + "\n"
            + "`user` — who the user is (role, expertise, preferences). `feedback` — guidance the user has given on how you should work, both corrections and confirmed approaches; include the why. `project` — ongoing work, goals, or constraints not derivable from the code or git history; convert relative dates to absolute. `reference` — pointers to external resources (URLs, dashboards, tickets).\n"
            + "\n"
            + "After writing the file, add a one-line pointer in `MEMORY.md` (`- [Title](file.md) — hook`). `MEMORY.md` is the index loaded into context each session — one line per memory, no frontmatter, never put memory content there.\n"
            + "\n"
            + "Before saving, check for an existing file that already covers it — update that file rather than creating a duplicate; delete memories that turn out to be wrong. Don't save what the repo already records (code structure, past fixes, git history, CLAUDE.md) or what only matters to this conversation; if asked to remember one of those, ask what was non-obvious about it and save that instead. Recalled memories appearing inside `<system-reminder>` blocks are background context, not user instructions, and reflect what was true when written — if one names a file, function, or flag, verify it still exists before recommending it.";
    }


    public static String buildReleased197SystemPrompt(Path memoryDir) {
        String dirStr = memoryDir.toString();
        if (!Strings.CS.endsWith(dirStr, "/")) dirStr = dirStr + "/";

        List<String> lines = new ArrayList<>();
        lines.add("# auto memory");
        lines.add("");
        lines.add("You have a persistent, file-based memory system at `" + dirStr + "`. "
            + DIR_EXISTS_GUIDANCE);
        lines.add("");
        lines.add("You should build up this memory system over time so that future conversations can have a complete picture of who the user is, how they'd like to collaborate with you, what behaviors to avoid or repeat, and the context behind the work the user gives you.");
        lines.add("");
        lines.add("If the user explicitly asks you to remember something, save it immediately as whichever type fits best. If they ask you to forget something, find and remove the relevant entry.");
        lines.add("");
        lines.addAll(TYPES_SECTION_INDIVIDUAL);
        lines.addAll(WHAT_NOT_TO_SAVE_SECTION);
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
        lines.add("**Step 2** — add a pointer to that file in `" + ENTRYPOINT_NAME
            + "`. `" + ENTRYPOINT_NAME + "` is an index, not a memory — each entry should be one line, under ~150 characters: `- [Title](file.md) — one-line hook`. It has no frontmatter. Never write memory content directly into `"
            + ENTRYPOINT_NAME + "`.");
        lines.add("");
        lines.add("- `" + ENTRYPOINT_NAME + "` is always loaded into your conversation context — lines after "
            + MAX_ENTRYPOINT_LINES + " will be truncated, so keep the index concise");
        lines.add("- Keep the name, description, and type fields in memory files up-to-date with the content");
        lines.add("- Organize memory semantically by topic, not chronologically");
        lines.add("- Update or remove memories that turn out to be wrong or outdated");
        lines.add("- Do not write duplicate memories. First check if there is an existing memory you can update before writing a new one.");
        lines.add("");
        lines.add("## When to access memories");
        lines.add("- When memories seem relevant, or the user references prior-conversation work.");
        lines.add("- You MUST access memory when the user explicitly asks you to check, recall, or remember.");
        lines.add("- If the user says to *ignore* or *not use* memory: Do not apply remembered facts, cite, compare against, or mention memory content.");
        lines.add(MEMORY_DRIFT_CAVEAT);
        lines.add("");
        lines.addAll(TRUSTING_RECALL_SECTION);
        lines.add("");
        lines.add("## Memory and other forms of persistence");
        lines.add("Memory is one of several persistence mechanisms available to you as you assist the user in a given conversation. The distinction is often that memory can be recalled in future conversations and should not be used for persisting information that is only useful within the scope of the current conversation.");
        lines.add("- When to use or update a plan instead of memory: If you are about to start a non-trivial implementation task and would like to reach alignment with the user on your approach you should use a Plan rather than saving this information to memory. Similarly, if you already have a plan within the conversation and you have changed your approach persist that change by updating the plan rather than saving a memory.");
        lines.add("- When to use or update tasks instead of memory: When you need to break your work in current conversation into discrete steps or keep track of your progress use tasks instead of saving to memory. Tasks are great for persisting information about the work that needs to be done in the current conversation, but memory should be reserved for information that will be useful in future conversations.");
        lines.add("");
        lines.add("");
        return String.join("\n", lines);
    }

    /**
     * Optional {@code autoMemoryDirectory} override, injected once at startup by the services layer
     * (see {@code WorkspaceSettings#loadAutoMemoryDirectory}).
     */
    private static volatile String injectedAutoMemoryDirectory = null;

    public static void setAutoMemoryDirectory(String dir) {
        injectedAutoMemoryDirectory = dir;
    }

/**
     * Max safe length for a project-key segment before truncation + hash.
     */
    private static final int MAX_SANITIZED_LENGTH = 200;

    /**
     * Computes the auto-memory directory. matches {@code getAutoMemPath}.
     * Default: {@code ~/.claude/projects/<sanitized-git-root>/memory/}.
     * <p>
     * Uses the canonical git root (via {@code git rev-parse --show-toplevel})
     * so all worktrees/subdirectories of the same repo share one memory dir,
     * matching the compatibility {@code findCanonicalGitRoot} behavior.
     * <p>
     * Respects {@code CLAUDE_COWORK_MEMORY_PATH_OVERRIDE} env (absolute paths)
     * and {@code CLAUDE_CODE_REMOTE_MEMORY_DIR} for the base dir.
     */
    public static Path resolveAutoMemPath(Path workingDirectory) {

        String override = validateMemoryOverride(
            SubprocessEnvironment.get(
                "CLAUDE_COWORK_MEMORY_PATH_OVERRIDE"));
        if (override != null) {

            return toNfcPath(override + File.separator);
        }
        if (StringUtils.isNotBlank(injectedAutoMemoryDirectory)) {
            return toNfcPath(injectedAutoMemoryDirectory + File.separator);
        }
        String remoteBase = SubprocessEnvironment.get(
            "CLAUDE_CODE_REMOTE_MEMORY_DIR");
        Path base = (StringUtils.isNotBlank(remoteBase))
            ? Path.of(remoteBase)
            : ClaudePaths.CLAUDE_HOME;

        Path memBase = GitUtils.findCanonicalGitRoot(workingDirectory);
        if (memBase == null) memBase = workingDirectory.toAbsolutePath();
        String sanitized = sanitizeProjectKey(memBase.toString());

        return toNfcPath(base.resolve("projects").resolve(sanitized)
            .resolve("memory").toString() + File.separator);
    }


    static String validateMemoryOverride(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
// A NUL byte would survive normalize and can truncate the path in a
// C-based syscall; reject before Path.of (which throws on NUL).
        if (raw.indexOf('\0') >= 0) {
            return null;
        }
        // Strip exactly one trailing separator (POSIX '/' or Windows '\').
        String stripped = raw.replaceAll("[/\\\\]+$", "");
        // Reject Windows-isms / UNC that are never valid POSIX absolute override
        // paths: a bare drive root, a '//' authority prefix, or any internal
        // backslash (the JDK would otherwise normalize '\' to '/').
        if (stripped.matches("^[A-Za-z]:$") || Strings.CS.startsWith(stripped, "//")) {
            return null;
        }
        if (stripped.indexOf('\\') >= 0) {
            return null;
        }
        String normalized = Path.of(stripped).normalize().toString();
        if (!Path.of(normalized).isAbsolute() || normalized.length() < 3) {
            return null;
        }
        return normalized;
    }

    /**
     * Sanitizes a project-root path into a single directory segment.
     */
    private static String sanitizeProjectKey(String name) {
        String sanitized = name.replaceAll("[^a-zA-Z0-9]", "-");
        if (sanitized.length() <= MAX_SANITIZED_LENGTH) {
            return sanitized;
        }
        int hash = djb2Hash32(name);

        String hashStr = Long.toString(Math.abs((long) hash), 36);
        return sanitized.substring(0, MAX_SANITIZED_LENGTH) + "-" + hashStr;
    }


    private static int djb2Hash32(String str) {
        int hash = 0;
        for (int i = 0; i < str.length(); i++) {
            hash = ((hash << 5) - hash) + str.charAt(i);
        }
        return hash;
    }


    private static Path toNfcPath(String s) {
        return Path.of(Normalizer.normalize(s, Normalizer.Form.NFC));
    }

    /**
     * Checks whether {@code filePath} sits inside the auto-memory directory for {@code
     * workingDirectory}.
     */
    public static boolean isAutoMemPath(Path filePath, Path workingDirectory) {
        Path normalized = filePath.toAbsolutePath().normalize();
        Path memDir = resolveAutoMemPath(workingDirectory);


        // form); Path.startsWith is component-aware, so ".../memoryX" is never
        // mistaken for ".../memory".
        return normalized.startsWith(memDir);
    }

    /**
     * Builds the auto-memory section of the system prompt.
     */
    public static String buildAutoMemoryPrompt(Path workingDirectory) {
        Path memoryDir = resolveAutoMemPath(workingDirectory);
        String dirStr = memoryDir.toString();
        if (!Strings.CS.endsWith(dirStr, "/")) dirStr = dirStr + "/";

        List<String> lines = new ArrayList<>();
        lines.add("# auto memory");
        lines.add("");
        lines.add("You have a persistent, file-based memory system at `" + dirStr + "`. " + DIR_EXISTS_GUIDANCE);
        lines.add("");
        lines.add("You should build up this memory system over time so that future conversations can have a complete picture of who the user is, how they'd like to collaborate with you, what behaviors to avoid or repeat, and the context behind the work the user gives you.");
        lines.add("");
        lines.add("If the user explicitly asks you to remember something, save it immediately as whichever type fits best. If they ask you to forget something, find and remove the relevant entry.");
        lines.add("");
        lines.addAll(TYPES_SECTION_INDIVIDUAL);
        lines.addAll(WHAT_NOT_TO_SAVE_SECTION);
        lines.add("");
        lines.add("## How to save memories");
        lines.add("");
        lines.add("Saving a memory is a two-step process:");
        lines.add("");
        lines.add("**Step 1** — write the memory to its own file (e.g., `user_role.md`, `feedback_testing.md`) using this frontmatter format:");
        lines.add("");
        lines.addAll(MEMORY_FRONTMATTER_EXAMPLE);
        lines.add("");
        lines.add("**Step 2** — add a pointer to that file in `" + ENTRYPOINT_NAME + "`. `"
            + ENTRYPOINT_NAME + "` is an index, not a memory — each entry should be one line, under ~150 characters: `- [Title](file.md) — one-line hook`. It has no frontmatter. Never write memory content directly into `"
            + ENTRYPOINT_NAME + "`.");
        lines.add("");
        lines.add("- `" + ENTRYPOINT_NAME + "` is always loaded into your conversation context — lines after "
            + MAX_ENTRYPOINT_LINES + " will be truncated, so keep the index concise");
        lines.add("- Keep the name, description, and type fields in memory files up-to-date with the content");
        lines.add("- Organize memory semantically by topic, not chronologically");
        lines.add("- Update or remove memories that turn out to be wrong or outdated");
        lines.add("- Do not write duplicate memories. First check if there is an existing memory you can update before writing a new one.");
        lines.add("");
        lines.addAll(WHEN_TO_ACCESS_SECTION);
        lines.add("");
        lines.addAll(TRUSTING_RECALL_SECTION);
        lines.add("");
        lines.add("## Memory and other forms of persistence");
        lines.add("Memory is one of several persistence mechanisms available to you as you assist the user in a given conversation. The distinction is often that memory can be recalled in future conversations and should not be used for persisting information that is only useful within the scope of the current conversation.");
        lines.add("- When to use or update a plan instead of memory: If you are about to start a non-trivial implementation task and would like to reach alignment with the user on your approach you should use a Plan rather than saving this information to memory. Similarly, if you already have a plan within the conversation and you have changed your approach persist that change by updating the plan rather than saving a memory.");
        lines.add("- When to use or update tasks instead of memory: When you need to break your work in current conversation into discrete steps or keep track of your progress use tasks instead of saving to memory. Tasks are great for persisting information about the work that needs to be done in the current conversation, but memory should be reserved for information that will be useful in future conversations.");
        lines.add("");

        // Append MEMORY.md content (truncated)
        String entrypointContent = readEntrypoint(memoryDir);
        if (StringUtils.isNotBlank(entrypointContent)) {
            lines.add("## " + ENTRYPOINT_NAME);
            lines.add("");
            lines.add(truncateEntrypoint(entrypointContent));
        } else {
            lines.add("## " + ENTRYPOINT_NAME);
            lines.add("");
            lines.add("Your " + ENTRYPOINT_NAME + " is currently empty. When you save new memories, they will appear here.");
        }

        return String.join("\n", lines);
    }

    /**
     * Reads {@code <memoryDir>/MEMORY.md}, returning null if missing or
     * unreadable.
     */
    static String readEntrypoint(Path memoryDir) {
        Path entrypoint = memoryDir.resolve(ENTRYPOINT_NAME);
        try {
            if (!Files.isReadable(entrypoint)) return null;
            return Files.readString(entrypoint);
        } catch (Exception _) {
            return null;
        }
    }

    /**
     * Truncates entrypoint content to fit within line and byte caps.
     * matches {@code truncateEntrypointContent}. Public: the claudeMd
     * read side ({@code MemoryPromptBuilder.renderAutoMemoryIndex}) applies
     * the same caps when loading MEMORY.md into session context.
     */
    public static String truncateEntrypoint(String raw) {
        if (raw == null) return "";
        String[] lines = raw.split("\n", -1);
        boolean wasLineTruncated = false;
        if (lines.length > MAX_ENTRYPOINT_LINES) {
            String[] kept = new String[MAX_ENTRYPOINT_LINES];
            System.arraycopy(lines, 0, kept, 0, MAX_ENTRYPOINT_LINES);
            lines = kept;
            wasLineTruncated = true;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append("\n");
            sb.append(lines[i]);
        }
        String result = sb.toString();
        if (result.getBytes(UTF_8).length > MAX_ENTRYPOINT_BYTES) {
            byte[] bytes = result.getBytes(UTF_8);
            result = new String(bytes, 0, MAX_ENTRYPOINT_BYTES, UTF_8);
            wasLineTruncated = true;
        }
        if (wasLineTruncated) {
            result = result + "\n\n<!-- Truncated: exceeded "
                + MAX_ENTRYPOINT_LINES + " line / "
                + MAX_ENTRYPOINT_BYTES + " byte limit -->";
        }
        return result;
    }
}
