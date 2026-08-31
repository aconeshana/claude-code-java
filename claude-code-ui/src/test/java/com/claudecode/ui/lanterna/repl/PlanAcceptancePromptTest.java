package com.claudecode.ui.lanterna.repl;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlanAcceptancePromptTest {

    @Test
    void clearedContextPromptRetainsAPathBackToThePreviousTranscript() {
        assertEquals("""
                Implement the following plan:
                # Plan
                - update parser
                If you need specific details from before exiting plan mode (like exact code snippets, error messages, or content you generated), read the full transcript at: /tmp/session.jsonl""",
            LanternaReplScreen.buildClearedContextPlanPrompt(
                "# Plan\n- update parser", Path.of("/tmp/session.jsonl")));
    }

    @Test
    void clearedContextPromptCarriesTeamHintAndApprovalFeedback() {
        assertEquals("""
                Implement the following plan:
                Implement parser
                If you need specific details from before exiting plan mode (like exact code snippets, error messages, or content you generated), read the full transcript at: /tmp/session.jsonl
                If this plan can be broken down into multiple independent tasks, consider spawning named teammates with the Agent tool (pass a `name`) to parallelize the work.
                User feedback on this plan: keep the public API compatible""",
            LanternaReplScreen.buildClearedContextPlanPrompt(
                "Implement parser", Path.of("/tmp/session.jsonl"),
                true, "  keep the public API compatible  "));
    }
}
