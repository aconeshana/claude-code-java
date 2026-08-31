package com.claudecode.core.message;



/**
 * Renders the plan-mode system-reminder text shown when plan mode is entered (via {@code
 * EnterPlanModeTool}) or restored post-compact (via {@link PlanModeReminderAttachment}) — both call
 * sites share this text so there is a single source of truth for "what plan mode instructions look
 * like".
 */
public final class PlanModeInstructions {

    private static volatile String customWorkflow;

    private PlanModeInstructions() {}

    public static void configureCustomWorkflow(String workflow) {
        customWorkflow = workflow == null || workflow.isBlank() ? null : workflow.strip();
    }

    static boolean hasCustomWorkflow() {
        return customWorkflow != null;
    }

    public static String render(boolean isSubAgent, String planFilePath, boolean planExists) {
        return isSubAgent ? subAgent(planFilePath, planExists) : instructions(planFilePath, planExists);
    }

    private static String instructions(String planFilePath, boolean planExists) {

        String planFileInfo = planExists
            ? "A plan file already exists at " + planFilePath + ". You can read it and make incremental "
                + "edits using the Edit tool."
            : "No plan file exists yet. You should create your plan at " + planFilePath
                + " using the Write tool.";

        String rendered = "Plan mode is active. The user indicated that they do not want you to execute yet -- you MUST "
            + "NOT make any edits (with the exception of the plan file mentioned below), run any non-readonly "
            + "tools (including changing configs or making commits), or otherwise make any changes to the "
            + "system. This supercedes any other instructions you have received.\n"
            + "\n"
            + "## Plan File Info:\n"
            + planFileInfo + "\n"
            + "You should build your plan incrementally by writing to or editing this file. NOTE that this is "
            + "the only file you are allowed to edit - other than this you are only allowed to take READ-ONLY "
            + "actions.\n"
            + "\n"
            + "## Plan Workflow\n"
            + "\n"
            + "### Phase 1: Initial Understanding\n"
            + "Goal: Gain a comprehensive understanding of the user's request by reading through code and "
            + "asking them questions. Critical: In this phase you should only use the Explore subagent type.\n"
            + "\n"
            + "1. Focus on understanding the user's request and the code associated with their request. "
            + "Actively search for existing functions, utilities, and patterns that can be reused — avoid "
            + "proposing new code when suitable implementations already exist.\n"
            + "\n"
            + "2. **Launch up to 3 Explore agents IN PARALLEL** (single message, multiple tool calls) to "
            + "efficiently explore the codebase.\n"
            + "   - Use 1 agent when the task is isolated to known files, the user provided specific file "
            + "paths, or you're making a small targeted change.\n"
            + "   - Use multiple agents when: the scope is uncertain, multiple areas of the codebase are "
            + "involved, or you need to understand existing patterns before planning.\n"
            + "   - Quality over quantity - 3 agents maximum, but you should try to use the minimum number "
            + "of agents necessary (usually just 1)\n"
            + "   - If using multiple agents: Provide each agent with a specific search focus or area to "
            + "explore. Example: One agent searches for existing implementations, another explores related "
            + "components, a third investigating testing patterns\n"
            + "\n"
            + "### Phase 2: Design\n"
            + "Goal: Design an implementation approach.\n"
            + "\n"
            + "Launch Plan agent(s) to design the implementation based on the user's intent and your "
            + "exploration results from Phase 1.\n"
            + "\n"
            + "You can launch up to 1 agent(s) in parallel.\n"
            + "\n"
            + "**Guidelines:**\n"
            + "- **Default**: Launch at least 1 Plan agent for most tasks - it helps validate your "
            + "understanding and consider alternatives\n"
            + "- **Skip agents**: Only for truly trivial tasks (typo fixes, single-line changes, simple "
            + "renames)\n"
            + "\n"
            + "In the agent prompt:\n"
            + "- Provide comprehensive background context from Phase 1 exploration including filenames and "
            + "code path traces\n"
            + "- Describe requirements and constraints\n"
            + "- Request a detailed implementation plan\n"
            + "\n"
            + "### Phase 3: Review\n"
            + "Goal: Review the plan(s) from Phase 2 and ensure alignment with the user's intentions.\n"
            + "1. Read the critical files identified by agents to deepen your understanding\n"
            + "2. Ensure that the plans align with the user's original request\n"
            + "3. Use AskUserQuestion to clarify any remaining questions with the user\n"
            + "\n"
            + PLAN_PHASE4_CONTROL + "\n"
            + "\n"
            + "### Phase 5: Call ExitPlanMode\n"
            + "At the very end of your turn, once you have asked the user questions and are happy with your "
            + "final plan file - you should always call ExitPlanMode to indicate to the user that you are "
            + "done planning.\n"
            + "This is critical - your turn should only end with either using the AskUserQuestion tool OR "
            + "calling ExitPlanMode. Do not stop unless it's for these 2 reasons\n"
            + "\n"
            + "**Important:** Use AskUserQuestion ONLY to clarify requirements or choose between approaches. "
            + "Use ExitPlanMode to request plan approval. Do NOT ask about plan approval in any other way - "
            + "no text questions, no AskUserQuestion. Phrases like \"Is this plan okay?\", \"Should I "
            + "proceed?\", \"How does this plan look?\", \"Any changes before we start?\", or similar MUST use "
            + "ExitPlanMode.\n"
            + "\n"
            + "NOTE: At any point in time through this workflow you should feel free to ask the user "
            + "questions or clarifications using the AskUserQuestion tool. Don't make large assumptions about "
            + "user intent. The goal is to present a well researched plan to the user, and tie any loose ends "
            + "before implementation begins.";
        String custom = customWorkflow;
        if (custom == null) return rendered;
        int workflowStart = rendered.indexOf("## Plan Workflow");
        int workflowBodyStart = workflowStart < 0 ? -1
            : workflowStart + "## Plan Workflow".length();
        int footerStart = rendered.indexOf("### Phase 5: Call ExitPlanMode");
        int noteStart = rendered.indexOf("\n\nNOTE: At any point", footerStart);
        if (workflowBodyStart < 0 || footerStart <= workflowBodyStart || noteStart < footerStart) {
            return rendered;
        }
        String footer = rendered.substring(footerStart, noteStart)
            .replaceFirst("### Phase 5: Call ExitPlanMode", "### Call ExitPlanMode");
        return rendered.substring(0, workflowBodyStart)
            + "\n\n" + custom + "\n\n" + footer;
    }

    private static String subAgent(String planFilePath, boolean planExists) {
        String planFileInfo = planExists
            ? "A plan file already exists at " + planFilePath + ". You can read it and make incremental "
                + "edits using the Edit tool if you need to."
            : "No plan file exists yet. You should create your plan at " + planFilePath
                + " using the Write tool if you need to.";

        return "Plan mode is active. The user indicated that they do not want you to execute yet -- you MUST "
            + "NOT make any edits, run any non-readonly tools (including changing configs or making commits), "
            + "or otherwise make any changes to the system. This supercedes any other instructions you have "
            + "received (for example, to make edits). Instead, you should:\n"
            + "\n"
            + "## Plan File Info:\n"
            + planFileInfo + "\n"
            + "You should build your plan incrementally by writing to or editing this file. NOTE that this is "
            + "the only file you are allowed to edit - other than this you are only allowed to take READ-ONLY "
            + "actions.\n"
            + "Answer the user's query comprehensively, using the AskUserQuestion tool if you need to ask the "
            + "user clarifying questions. If you do use the AskUserQuestion, make sure to ask all clarifying "
            + "questions you need to fully understand the user's intent before proceeding.";
    }


    private static final String PLAN_PHASE4_CONTROL = """
        ### Phase 4: Final Plan
        Goal: Write your final plan to the plan file (the only file you can edit).
        - Begin with a **Context** section: explain why this change is being made — the problem or need \
        it addresses, what prompted it, and the intended outcome
        - Include only your recommended approach, not all alternatives
        - Ensure that the plan file is concise enough to scan quickly, but detailed enough to execute \
        effectively
        - Name the critical files to be modified. For changes that repeat a pattern across many files, describe \
        the pattern once and list a few representative paths — do not enumerate every file or line number
        - Reference existing functions and utilities you found that should be reused, with their file \
        paths
        - Include a verification section describing how to test the changes end-to-end (run the code, \
        use MCP tools, run tests)""";
}
