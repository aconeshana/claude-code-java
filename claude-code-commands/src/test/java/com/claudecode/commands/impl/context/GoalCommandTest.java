package com.claudecode.commands.impl.context;


import org.apache.commons.lang3.Strings;

import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.GoalStatusAttachment;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.Usage;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class GoalCommandTest {

    private final GoalCommand command = new GoalCommand();

    @Test
    void metadataMatchesReleased197Command() {
        assertEquals("goal", command.name());
        assertEquals("Set a goal Claude checks before stopping", command.description());
        assertEquals("[<condition> | clear]", command.argumentHint());
        assertTrue(command.isImmediate());
        assertTrue(command.supportsNonInteractive());
    }

    @Test
    void settingGoalInstallsStopConditionRecordsSentinelAndQueriesModel() {
        RecordingGoalHooks hooks = new RecordingGoalHooks();
        List<Message> messages = new ArrayList<>();
        CommandContext context = context(hooks, messages);

        CommandResult result = command.execute(context, "all tests pass");

        assertTrue(result.shouldQuery());
        assertEquals("Goal set: all tests pass", result.output());
        assertNotNull(result.promptInvocation());
        assertTrue(result.promptInvocation().scalarTextContent());
        assertTrue(result.promptInvocation().suppressInitialAttachments());
        assertTrue(result.promptInvocation().suppressCommandPermissions());
        assertTrue(result.promptInvocation().suppressSkillAttribution());
        assertTrue(result.promptInvocation().suppressLastPrompt());
        assertTrue(Strings.CS.startsWith(result.promptInvocation().textContent(), "A session-scoped Stop hook is now active with condition: \"all tests pass\"."));
        assertEquals(List.of(
            """
            <command-name>/goal</command-name>
                        <command-message>goal</command-message>
                        <command-args>all tests pass</command-args>""",
            "<local-command-stdout>Goal set: all tests pass</local-command-stdout>"),
            result.promptInvocation().precedingUserMessages().stream()
                .map(MessageContent::text)
                .toList());
        assertEquals("all tests pass", hooks.activeGoal().orElseThrow().condition());
        assertEquals(42L, hooks.activeGoal().orElseThrow().tokensAtStart());
        assertEquals(1, messages.size());
        GoalStatusAttachment sentinel = (GoalStatusAttachment)
            ((AttachmentMessage) messages.getFirst()).payload();
        assertTrue(sentinel.sentinel());
        assertFalse(sentinel.met());
    }

    @Test
    void headlessModeIsImplicitlyTrustedWithoutAnInteractiveTrustRecord() {
        RecordingGoalHooks hooks = new RecordingGoalHooks();
        List<Message> messages = new ArrayList<>();
        CommandContext context = CommandContext.builder(
                "model", () -> List.copyOf(messages), () -> {}, _ -> {},
                () -> new Usage(0, 0, 0, 0), _ -> 0,
                "/definitely/not/a/trusted/project", false)
            .hookDispatcher(hooks)
            .messageAppender(messages::add)
            .nonInteractive(true)
            .build();

        CommandResult result = command.execute(context, "headless goal");

        assertEquals("Goal set: headless goal", result.output());
        assertTrue(result.shouldQuery());
        assertEquals("headless goal", hooks.activeGoal().orElseThrow().condition());
    }

    @Test
    void clearAliasesRemoveGoalAndRecordMetSentinel() {
        for (String alias : List.of("clear", "stop", "off", "reset", "none", "cancel")) {
            RecordingGoalHooks hooks = new RecordingGoalHooks();
            hooks.setGoal("ship it", 0);
            List<Message> messages = new ArrayList<>();

            CommandResult result = command.execute(context(hooks, messages), alias);

            assertEquals("Goal cleared: ship it", result.output(), alias);
            assertTrue(hooks.activeGoal().isEmpty(), alias);
            GoalStatusAttachment sentinel = (GoalStatusAttachment)
                ((AttachmentMessage) messages.getFirst()).payload();
            assertTrue(sentinel.sentinel(), alias);
            assertTrue(sentinel.met(), alias);
        }
    }

    @Test
    void emptyHeadlessInvocationReportsCurrentState() {
        RecordingGoalHooks hooks = new RecordingGoalHooks();
        hooks.setGoal("release", 0);
        hooks.goal = new HookDispatcher.ActiveGoal("release", 2, 1L, 0L, "tests still failing");

        CommandResult result = command.execute(context(hooks, new ArrayList<>()), "");

        assertFalse(result.shouldQuery());
        assertEquals("Goal active: release (2 turns)\nLast check: tests still failing\n",
            result.output());
    }

    @Test
    void rejectsConditionsLongerThanFourThousandCharacters() {
        RecordingGoalHooks hooks = new RecordingGoalHooks();
        CommandResult result = command.execute(context(hooks, new ArrayList<>()), "x".repeat(4001));
        assertEquals("Goal condition is limited to 4000 characters (got 4001)", result.output());
        assertTrue(hooks.activeGoal().isEmpty());
    }

    private static CommandContext context(RecordingGoalHooks hooks, List<Message> messages) {
        return CommandContext.builder(
                "model", () -> List.copyOf(messages), () -> {}, _ -> {},
                () -> new Usage(20, 22, 0, 0), _ -> 0, ".", false)
            .hookDispatcher(hooks)
            .messageAppender(messages::add)
            .goalGate(() -> null)
            .nonInteractive(true)
            .build();
    }

    private static final class RecordingGoalHooks implements HookDispatcher {
        private ActiveGoal goal;

        @Override public boolean setGoal(String condition, long tokensAtStart) {
            goal = new ActiveGoal(condition, 0, System.currentTimeMillis(), tokensAtStart, null);
            return true;
        }

        @Override public String clearGoal() {
            if (goal == null) return null;
            String condition = goal.condition();
            goal = null;
            return condition;
        }

        @Override public Optional<ActiveGoal> activeGoal() { return Optional.ofNullable(goal); }

        @Override public boolean dispatchPreToolUse(String a, JsonNode b, String c) { return true; }
        @Override public void dispatchPostToolUse(String a, JsonNode b,
                                                   JsonNode c, String d) { }
        @Override public void dispatchUserPromptSubmit(String prompt) { }
        @Override public void dispatchSessionStart(String trigger) { }
        @Override public void dispatchStop(String reason) { }
    }
}
