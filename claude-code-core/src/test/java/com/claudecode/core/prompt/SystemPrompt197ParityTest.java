package com.claudecode.core.prompt;

import org.apache.commons.lang3.Strings;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;


class SystemPrompt197ParityTest {

    private static String fixture(String name) {
        try (InputStream in = SystemPrompt197ParityTest.class
                .getResourceAsStream("/ts197/" + name + ".txt")) {
            assertNotNull(in, "missing 197 fixture: " + name);
            // Section files may carry a single trailing newline from extraction;
            // the section builders return no trailing newline. Normalize one.
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                .replaceAll("\\n$", "");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void contextManagementSectionMatches197Verbatim() {
        assertEquals(fixture("06-Context_management"),
            SystemPromptSections.getContextManagementSection());
    }

    @Test
    void introSectionMatches197Verbatim() {

        String expected = "\nYou are an interactive agent that helps users with "
            + "software engineering tasks. Use the instructions below and the tools "
            + "available to you to assist the user.\n\n" + CyberRiskInstruction.TEXT
            + "\nIMPORTANT: You must NEVER generate or guess URLs for the user unless "
            + "you are confident that the URLs are for helping the user with "
            + "programming. You may use URLs provided by the user in their messages "
            + "or local files.";
        assertEquals(expected, SystemPromptSections.getSimpleIntroSection(null));
    }

    @Test
    void harnessIntroAndSectionMatch197Verbatim() {
        String expectedIntro = "\nYou are an interactive agent that helps users "
            + "with software engineering tasks.\n\n" + CyberRiskInstruction.TEXT;
        assertEquals(expectedIntro, SystemPromptSections.getHarnessIntroSection(null));
        assertEquals(fixture("01-Harness"), SystemPromptSections.getHarnessSection());
        assertFalse(Strings.CS.contains(expectedIntro, "Use the instructions below"));
        assertFalse(Strings.CS.contains(expectedIntro, "NEVER generate or guess URLs"));
    }

    @Test
    void fableCapabilityAddsReleasedMitigationSections() {
        String harness = SystemPromptSections.getHarnessSection("claude-fable-5");
        assertTrue(Strings.CS.contains(harness, "# Communicating with the user"));
        assertTrue(Strings.CS.contains(harness,
            "Everything the user needs from this turn — answers, summaries, findings"));
        assertTrue(Strings.CS.contains(harness,
            "Only write a code comment to state a constraint the code itself can't show"));
        assertFalse(Strings.CS.contains(
            SystemPromptSections.getHarnessSection("claude-opus-4-8"),
            "# Communicating with the user"));
        assertEquals(SystemPromptSections.getFableCommunicationSection(),
            SystemPromptSections.getModelAwareTextOutputSection("claude-fable-5"));
        assertEquals(SystemPromptSections.getReleased197TextOutputSection(),
            SystemPromptSections.getModelAwareTextOutputSection("claude-opus-4-8"));

        assertTrue(Strings.CS.startsWith(
            SystemPromptSections.getFableIdentitySection("claude-fable-5"),
            "This iteration of Claude is Claude Fable 5"));
        assertNull(SystemPromptSections.getFableIdentitySection("claude-mythos-5"));

        String autonomy = SystemPromptSections.getFableAutonomySection("claude-fable-5");
        assertTrue(Strings.CS.startsWith(autonomy, "You are operating autonomously."));
        assertTrue(Strings.CS.contains(autonomy,
            "Before ending your turn, check your last paragraph."));
        assertNotNull(SystemPromptSections.getFableAutonomySection("claude-mythos-5"));
        assertNull(SystemPromptSections.getFableAutonomySection("claude-opus-4-8"));
    }

    @Test
    void newestModelEnvMetadataMatches197() {
        assertEquals("Opus 4.8",
            EnvInfoSection.getMarketingNameForModel("claude-opus-4-8"));
        assertEquals("Fable 5",
            EnvInfoSection.getMarketingNameForModel("claude-fable-5"));
        assertEquals("Mythos 5",
            EnvInfoSection.getMarketingNameForModel("claude-mythos-5"));
        assertEquals("January 2026",
            EnvInfoSection.getKnowledgeCutoff("claude-opus-4-8"));
        assertEquals("January 2026",
            EnvInfoSection.getKnowledgeCutoff("claude-fable-5"));
    }

    @Test
    void opusFiveEnvMetadataUsesCurrentPublicModelFacts() {
        assertEquals("Opus 5",
            EnvInfoSection.getMarketingNameForModel("claude-opus-5"));
        assertEquals("May 2026",
            EnvInfoSection.getKnowledgeCutoff("claude-opus-5"));
    }

    @Test
    void activeOutputStyleChangesOnlyTheOfficialIntroFraming() {
        String intro = SystemPromptSections.getSimpleIntroSection(OutputStylePresets.EXPLANATORY);
        assertTrue(Strings.CS.startsWith(intro, """

            You are an interactive agent that helps users \
            according to your "Output Style" below, which describes how you \
            should respond to user queries. Use the instructions below"""));
        assertFalse(Strings.CS.contains(intro, "helps users with software engineering tasks."));
    }

    @Test
    void releasedDoingTasksSectionMatches197Verbatim() {
        String expected = """
            # Doing tasks
             - The user will primarily request you to perform software engineering tasks. These may include solving bugs, adding new functionality, refactoring code, explaining code, and more. When given an unclear or generic instruction, consider it in the context of these software engineering tasks and the current working directory. For example, if the user asks you to change "methodName" to snake case, do not reply with just "method_name", instead find the method in the code and modify the code.
             - You are highly capable and often allow users to complete ambitious tasks that would otherwise be too complex or take too long. You should defer to user judgement about whether a task is too large to attempt.
             - For exploratory questions ("what could we do about X?", "how should we approach this?", "what do you think?"), respond in 2-3 sentences with a recommendation and the main tradeoff. Present it as something the user can redirect, not a decided plan. Don't implement until the user agrees.
             - Prefer editing existing files to creating new ones.
             - Be careful not to introduce security vulnerabilities such as command injection, XSS, SQL injection, and other OWASP top 10 vulnerabilities. If you notice that you wrote insecure code, immediately fix it. Prioritize writing safe, secure, and correct code.
             - Don't add features, refactor, or introduce abstractions beyond what the task requires. A bug fix doesn't need surrounding cleanup; a one-shot operation doesn't need a helper. Don't design for hypothetical future requirements. Three similar lines is better than a premature abstraction. No half-finished implementations either.
             - Don't add error handling, fallbacks, or validation for scenarios that can't happen. Trust internal code and framework guarantees. Only validate at system boundaries (user input, external APIs). Don't use feature flags or backwards-compatibility shims when you can just change the code.
             - Default to writing no comments. Only add one when the WHY is non-obvious: a hidden constraint, a subtle invariant, a workaround for a specific bug, behavior that would surprise a reader. If removing the comment wouldn't confuse a future reader, don't write it.
             - Don't explain WHAT the code does, since well-named identifiers already do that. Don't reference the current task, fix, or callers ("used by X", "added for the Y flow", "handles the case from issue #123"), since those belong in the PR description and rot as the codebase evolves.
             - For UI or frontend changes, start the dev server and use the feature in a browser before reporting the task as complete. Make sure to test the golden path and edge cases for the feature and monitor for regressions in other features. Type checking and test suites verify code correctness, not feature correctness - if you can't test the UI, say so explicitly rather than claiming success.
             - Avoid backwards-compatibility hacks like renaming unused _vars, re-exporting types, adding // removed comments for removed code, etc. If you are certain that something is unused, you can delete it completely.
             - If the user asks for help or wants to give feedback inform them of the following:
              - /help: Get help with using Claude Code
              - To give feedback, users should report the issue at https://github.com/anthropics/claude-code/issues""";
        assertEquals(expected, SystemPromptSections.getReleased197DoingTasksSection());
    }

    @Test
    void releasedUsingToolsAndToneSectionsMatch197Verbatim() {
        String usingTools = """
            # Using your tools
             - Prefer dedicated tools over Bash when one fits (Read, Edit, Write) — reserve Bash for shell-only operations.
             - Use TaskCreate to plan and track work. Mark each task completed as soon as it's done; don't batch.
             - You can call multiple tools in a single response. If you intend to call multiple tools and there are no dependencies between them, make all independent tool calls in parallel. Maximize use of parallel tool calls where possible to increase efficiency. However, if some tool calls depend on previous calls to inform dependent values, do NOT call these tools in parallel and instead call them sequentially. For instance, if one operation must complete before another starts, run these operations sequentially instead.""";
        assertEquals(usingTools,
            SystemPromptSections.getReleased197UsingYourToolsSection(
                Set.of("Bash", "TaskCreate")));

        String withoutBash = """
            # Using your tools
             - Prefer dedicated tools over PowerShell when one fits (Read, Edit, Write, Glob, Grep) — reserve PowerShell for shell-only operations.
             - You can call multiple tools in a single response. If you intend to call multiple tools and there are no dependencies between them, make all independent tool calls in parallel. Maximize use of parallel tool calls where possible to increase efficiency. However, if some tool calls depend on previous calls to inform dependent values, do NOT call these tools in parallel and instead call them sequentially. For instance, if one operation must complete before another starts, run these operations sequentially instead.""";
        assertEquals(withoutBash,
            SystemPromptSections.getReleased197UsingYourToolsSection(Set.of("Read")));

        String tone = """
            # Tone and style
             - Only use emojis if the user explicitly requests it. Avoid using emojis in all communication unless asked.
             - Your responses should be short and concise.
             - When referencing specific functions or pieces of code include the pattern file_path:line_number to allow the user to easily navigate to the source code location.
             - Do not use a colon before tool calls. Your tool calls may not be shown directly in the output, so text like "Let me read the file:" followed by a read tool call should just be "Let me read the file." with a period.""";
        assertEquals(tone, SystemPromptSections.getReleased197ToneAndStyleSection());
    }

    @Test
    void releasedTextOutputSectionMatches197Verbatim() {
        String expected = """
            # Text output (does not apply to tool calls)
            Assume users can't see most tool calls or thinking — only your text output. Before your first tool call, state in one sentence what you're about to do. While working, give short updates at key moments: when you find something, when you change direction, or when you hit a blocker. Brief is good — silent is not. One sentence per update is almost always enough.

            Don't narrate your internal deliberation. User-facing text should be relevant communication to the user, not a running commentary on your thought process. State results and decisions directly, and focus user-facing text on relevant updates for the user.

            When you do write updates, write so the reader can pick up cold: complete sentences, no unexplained jargon or shorthand from earlier in the session. But keep it tight — a clear sentence is better than a clear paragraph.

            End-of-turn summary: one or two sentences. What changed and what's next. Nothing else.

            Match responses to the task: a simple question gets a direct answer, not headers and sections.

            In code: default to writing no comments. Never write multi-paragraph docstrings or multi-line comment blocks — one short line max. Don't create planning, decision, or analysis documents unless the user asks for them — work from conversation context, not intermediate files.""";
        assertEquals(expected, SystemPromptSections.getReleased197TextOutputSection());
    }

    @Test
    void sessionGuidanceMatchesReleasedSdkCliWithAgentAndSkills() {
        String expected = """
            # Session-specific guidance
             - Use the Agent tool with specialized agents when the task at hand matches the agent's description. Subagents are valuable for parallelizing independent queries or for protecting the main context window from excessive results, but they should not be used excessively when not needed. Importantly, avoid duplicating work that subagents are already doing - if you delegate research to a subagent, do not also perform the same searches yourself.
             - For broad codebase exploration or research that'll take more than 3 queries, spawn Agent with subagent_type=Explore. Otherwise use `find` or `grep` via the Bash tool directly.
             - When the user types `/<skill-name>`, invoke it via Skill. Only use \
            skills listed in the user-invocable skills section — don't guess.""";
        String actual = SystemPromptSections.getSessionSpecificGuidanceSection(
            Set.of("Agent", "Skill"), /* hasSkills */ true,
            /* isNonInteractive */ true);
        assertEquals(expected, actual);
        assertNull(SystemPromptSections.getSessionSpecificGuidanceSection(
            Set.of("Read", "Bash"), /* hasSkills on disk */ true,
            /* isNonInteractive */ true));
    }

    @Test
    void assembledReleasedPromptUsesLongProfileAndOrdering() {
        String prompt = String.join("\n\n", new SystemPromptService().buildSystemPromptParts(
            SystemPromptConfig.builder()
                .modelId("claude-sonnet-4-6")
                .workingDirectory("/tmp/project")
                .enabledTools(Set.of("Agent", "Skill", "TaskCreate"))
                .hasSkills(true)
                .isNonInteractiveSession(true)
                .memoryDir(Path.of("/tmp/config/projects/-tmp-project/memory"))
                .build()));

        assertFalse(Strings.CS.contains(prompt, "# Harness"));
        int system = prompt.indexOf("# System");
        int doing = prompt.indexOf("# Doing tasks");
        int actions = prompt.indexOf("# Executing actions with care");
        int tools = prompt.indexOf("# Using your tools");
        int tone = prompt.indexOf("# Tone and style");
        int textOutput = prompt.indexOf("# Text output (does not apply to tool calls)");
        int boundary = prompt.indexOf(SystemPromptConstants.SYSTEM_PROMPT_DYNAMIC_BOUNDARY);
        int guidance = prompt.indexOf("# Session-specific guidance");
        int memory = prompt.indexOf("# auto memory");
        int environment = prompt.indexOf("# Environment");
        int context = prompt.indexOf("# Context management");
        assertTrue(system >= 0 && doing > system && actions > doing && tools > actions
                && tone > tools && textOutput > tone && boundary > textOutput
                && guidance > boundary && memory > guidance && environment > memory
                && context > environment,
            "released section order mismatch");
    }

    @Test
    void assembledReleasedPromptUsesHarnessProfileAndLeanDynamicSections() {
        String prompt = String.join("\n\n", new SystemPromptService().buildSystemPromptParts(
            SystemPromptConfig.builder()
                .modelId("glm-5.2")
                .apiProvider("firstParty")
                .workingDirectory("/tmp/project")
                .enabledTools(Set.of("Agent", "Skill", "TaskCreate"))
                .hasSkills(true)
                .isNonInteractiveSession(true)
                .memoryDir(Path.of("/tmp/config/projects/-tmp-project/memory"))
                .build()));

        assertTrue(Strings.CS.contains(prompt, "# Harness"));
        assertFalse(Strings.CS.contains(prompt, "# System"));
        assertFalse(Strings.CS.contains(prompt, "# Doing tasks"));
        assertFalse(Strings.CS.contains(prompt, "# Using your tools"));
        assertFalse(Strings.CS.contains(prompt, "NEVER generate or guess URLs"));
        assertFalse(Strings.CS.contains(prompt, "Use the Agent tool with specialized agents"));
        assertFalse(Strings.CS.contains(prompt, "broad codebase exploration"));
        assertTrue(Strings.CS.contains(prompt,
            "When the user types `/<skill-name>`, invoke it via Skill"));
        assertTrue(Strings.CS.contains(prompt, "# Memory"));
        assertFalse(Strings.CS.contains(prompt, "# auto memory"));

        int harness = prompt.indexOf("# Harness");
        int boundary = prompt.indexOf(SystemPromptConstants.SYSTEM_PROMPT_DYNAMIC_BOUNDARY);
        int guidance = prompt.indexOf("# Session-specific guidance");
        int memory = prompt.indexOf("# Memory");
        int environment = prompt.indexOf("# Environment");
        int context = prompt.indexOf("# Context management");
        assertTrue(harness >= 0 && boundary > harness && guidance > boundary
                && memory > guidance && environment > memory && context > environment,
            "Harness section order mismatch");
    }

    @Test
    void assembledFablePromptPlacesIdentityAndAutonomyLike197() {
        String prompt = String.join("\n\n", new SystemPromptService().buildSystemPromptParts(
            SystemPromptConfig.builder()
                .modelId("claude-fable-5")
                .workingDirectory("/tmp/project")
                .isNonInteractiveSession(true)
                .build()));

        int communication = prompt.indexOf("# Communicating with the user");
        int caution = prompt.indexOf("For actions that are hard to reverse");
        int identity = prompt.indexOf("This iteration of Claude is Claude Fable 5");
        int environment = prompt.indexOf("# Environment");
        int context = prompt.indexOf("# Context management");
        int autonomy = prompt.indexOf("You are operating autonomously.");
        assertTrue(communication >= 0 && caution > communication && identity > caution
                && environment > identity && context > environment && autonomy > context,
            "Fable mitigation section order mismatch");
    }

    @Test
    void languageSectionMatches197Verbatim() {
        String expected = """
            # Language
            Always respond in Chinese. Use Chinese for all explanations, comments, \
            and communications with the user. Technical terms and code identifiers \
            should remain in their original form.
            Maintain full orthographic correctness for Chinese, including all \
            required diacritical marks, accents, and special characters. Never \
            substitute accented characters with their ASCII equivalents (e.g., never \
            write "nao" for "não", "fur" for "für", or "loeschen" for \
            "löschen").""";
        assertEquals(expected, SystemPromptSections.getLanguageSection("Chinese"));
        assertTrue(Strings.CS.startsWith(SystemPromptSections.getLanguageSection("  "), "# Language\n"));
    }

    @Test
    void assembled197PromptOmitsPost197ToolResultReminder() {
        String prompt = String.join("\n\n", new SystemPromptService().buildSystemPromptParts(
            SystemPromptConfig.builder()
                .modelId("glm-5.2")
                .workingDirectory("/tmp/project")
                .isGitRepo(true)
                .isNonInteractiveSession(true)
                .build()));
        assertFalse(Strings.CS.contains(prompt, SystemPromptConstants.SUMMARIZE_TOOL_RESULTS_SECTION),
            "2.1.197 wire capture does not contain the later tool-result-clearing reminder");
    }
}
