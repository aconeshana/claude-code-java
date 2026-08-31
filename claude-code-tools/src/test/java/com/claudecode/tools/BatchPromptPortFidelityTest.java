package com.claudecode.tools;

import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.claudecode.tools.cron.CronCreateTool;
import com.claudecode.tools.messaging.SendMessageTool;
import com.claudecode.tools.questions.AskUserQuestionTool;
import com.claudecode.tools.worktree.EnterWorktreeTool;
import com.claudecode.tools.worktree.ExitWorktreeTool;

class BatchPromptPortFidelityTest {

    @Test
    void askUserQuestion_descriptionAndPromptPorted() {
        AskUserQuestionTool tool = new AskUserQuestionTool();
        String d = tool.description();
        String prompt = tool.prompt(null);

        assertEquals(d, prompt);
        assertTrue(Strings.CS.contains(d, "genuinely the user's to make"), d);
        assertTrue(Strings.CS.contains(prompt, "multiSelect: true"));
        assertTrue(Strings.CS.contains(prompt, "(Recommended)"));
        assertTrue(Strings.CS.contains(prompt, "ExitPlanMode"), "plan-mode note must mention ExitPlanMode");
    }

    @Test
    void sendMessageTool_currentTsDescriptionAndPromptPorted() {
        SendMessageTool tool = new SendMessageTool();

        String d = tool.description();
        assertTrue(Strings.CS.startsWith(d, "# SendMessage"), d);
        assertEquals(d, tool.prompt(null), "wire prompt() must equal permission description()");
        assertTrue(Strings.CS.contains(d, "\"researcher\""));
        assertTrue(Strings.CS.contains(d, "\"main\""), "197 advertises the main-conversation recipient");
        assertTrue(Strings.CS.contains(d, "agentId"), "197 points to agentId for resuming a completed agent");
    }

    @Test
    void worktreeTools_descriptionsPorted() {
        String enter = new EnterWorktreeTool().description();
        assertTrue(Strings.CS.contains(enter, "ONLY when explicitly instructed to work in a worktree"));
        assertTrue(Strings.CS.contains(enter, "`.claude/worktrees/`"));
        String exit = new ExitWorktreeTool().description();
        assertTrue(Strings.CS.contains(exit, "This tool ONLY operates on worktrees created by EnterWorktree"));
        assertTrue(Strings.CS.contains(exit, "`discard_changes`"));
    }

    @Test
    void scheduleCronTool_descriptionPorted() {

        // puts the detailed scheduling guidance in the dynamic prompt.
        String p = new CronCreateTool().prompt(null);
        assertTrue(Strings.CS.contains(p, "Schedule a prompt to be enqueued at a future time"));
        assertTrue(Strings.CS.contains(p, "5-field cron"));
        assertTrue(Strings.CS.contains(p, "recurring: false"));
        assertTrue(Strings.CS.contains(p, "Avoid the :00 and :30"),
            "must include the fleet-thundering-herd guidance");
        assertTrue(Strings.CS.contains(p, "auto-expire after 7 days"));
    }

    @Test
    void toolSearchTool_descriptionPorted() {
        String d = new ToolSearchTool(null).description();
        assertTrue(Strings.CS.contains(d, "Fetches full schema definitions for deferred tools"));
        assertTrue(Strings.CS.contains(d, "<available-deferred-tools>"));
        assertTrue(Strings.CS.contains(d, "select:Read,Edit,Grep"));
        assertTrue(Strings.CS.contains(d, "keyword search"));
    }
}
