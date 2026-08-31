package com.claudecode.commands.impl.git;


import org.apache.commons.lang3.Strings;

import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.session.SessionManager;
import com.claudecode.session.SessionSearch;
import com.claudecode.session.SessionStorage;
import com.claudecode.commands.testing.ProviderTestCommandPorts;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BranchCommandTest {

    /** Captures dispatchSessionEnd/dispatchSessionStartWithOutcome calls. */
    private static final class CapturingHooks implements HookDispatcher {
        final List<String> sessionEndReasons = new ArrayList<>();
        final List<String> sessionStartTriggers = new ArrayList<>();
        String additionalContextToReturn;

        @Override public boolean dispatchPreToolUse(String t, JsonNode i, String id) { return true; }
        @Override public void dispatchPostToolUse(String t, JsonNode i, JsonNode o, String id) {}
        @Override public void dispatchUserPromptSubmit(String prompt) {}
        @Override public void dispatchSessionStart(String trigger) { sessionStartTriggers.add(trigger); }
        @Override public void dispatchStop(String reason) {}

        @Override
        public void dispatchSessionEnd(String reason) {
            sessionEndReasons.add(reason);
        }

        @Override
        public HookOutcome dispatchSessionStartWithOutcome(String trigger) {
            sessionStartTriggers.add(trigger);
            return additionalContextToReturn != null
                ? new HookOutcome(true, additionalContextToReturn, List.of())
                : HookOutcome.PROCEED;
        }
    }

    @Test
    void requiresActiveSession(@TempDir Path baseDir) {
        SessionManager manager = new SessionManager(baseDir, "/test/project");
        SessionStorage storage = new SessionStorage();
        CommandResult r = new BranchCommand().execute(
            ProviderTestCommandPorts.withSessions(CommandContext.minimal(), manager, storage), "");
        assertTrue(Strings.CI.contains(r.output(), "no active session"),
            "expected no-session message; got: " + r.output());
    }

    @Test
    void noTranscript_failsWithCleanMessage(@TempDir Path baseDir) {
        SessionManager mgr = new SessionManager(baseDir, "/test/project");
        String id = mgr.createSession();   // dir exists, but no transcript file
        CommandContext ctx = ctxWithSession(baseDir, id);

        CommandResult r = new BranchCommand().execute(
            ProviderTestCommandPorts.withSessions(ctx, mgr, new SessionStorage()), "");
        assertTrue(Strings.CI.contains(r.output(), "no conversation to branch"),
            "expected no-conversation message; got: " + r.output());
    }

    @Test
    void forksTranscriptAndLoadsIntoEngine(@TempDir Path baseDir) {
        SessionManager mgr = new SessionManager(baseDir, "/test/project");
        SessionStorage storage = new SessionStorage();
        String currentId = mgr.createSession();
        storage.appendMessage(mgr.getSessionFile(currentId),
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("hello")));
        storage.appendMessage(mgr.getSessionFile(currentId),
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("world")));

        List<Message> loaded = new ArrayList<>();
        CommandContext ctx = CommandContext.builder(
            "test-model", List::of, () -> {}, _ -> {},
            () -> null, _ -> 0.0, baseDir.toString(), false)
            .loadMessages(loaded::addAll)
            .currentSessionId(() -> currentId)
            .build();

        CommandResult r = new BranchCommand().execute(
            ProviderTestCommandPorts.withSessions(ctx, mgr, storage), "wip experiments");

        assertEquals(2, loaded.size(), "loadMessages should receive the cloned history");
        assertTrue(Strings.CS.contains(r.output(), "Branched conversation \"wip experiments\""),
            "output should echo label; got: " + r.output());
        assertTrue(Strings.CS.contains(r.output(), "Copied 2 messages"));
        // A new session file should exist in the project dir: baseDir/projects/<sanitized>/
        Path projectDir = mgr.getProjectDir();
        long branchFiles;
        try (var stream = Files.list(projectDir).filter(p -> Strings.CS.endsWith(p.toString(), ".jsonl"))) {
            branchFiles = stream.count();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertEquals(2, branchFiles, "should have original + fork session files");
    }

    @Test
    void btwAdditionalMessagesArePersistedAndLoadedIntoTheBranch(@TempDir Path baseDir) {
        SessionManager mgr = new SessionManager(baseDir, "/test/project");
        SessionStorage storage = new SessionStorage();
        String currentId = mgr.createSession();
        storage.appendMessage(mgr.getSessionFile(currentId),
            new UserMessage("parent", MessageContent.ofText("main turn")));
        UserMessage question = new UserMessage("btw-user", MessageContent.ofText("side question"));
        AssistantMessage answer = new AssistantMessage("btw-assistant",
            AssistantContent.of(List.of(new TextBlock("side answer"))));
        List<Message> loaded = new ArrayList<>();
        CommandContext context = CommandContext.builder(
            "test-model", List::of, () -> {}, _ -> {},
            () -> null, _ -> 0.0, baseDir.toString(), false)
            .loadMessages(loaded::addAll)
            .currentSessionId(() -> currentId)
            .build();

        CommandResult result = new BranchCommand().executeWithAdditionalMessages(
            ProviderTestCommandPorts.withSessions(context, mgr, storage),
            "btw: side question", List.of(question, answer));

        assertEquals(List.of("parent", "btw-user", "btw-assistant"),
            loaded.stream().map(Message::uuid).toList());
        assertEquals(List.of("parent", "btw-user", "btw-assistant"),
            storage.readMessages(mgr.getSessionFile(extractForkSessionId(result.output())))
                .stream().map(Message::uuid).toList());
        assertTrue(Strings.CS.contains(result.output(), "Copied 3 messages"));
    }

    @Test
    void withoutLoadMessages_stillForksAndPointsAtResume(@TempDir Path baseDir) {
        SessionManager mgr = new SessionManager(baseDir, "/test/project");
        SessionStorage storage = new SessionStorage();
        String currentId = mgr.createSession();
        storage.appendMessage(mgr.getSessionFile(currentId),
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("hi")));

        CommandContext ctx = CommandContext.builder(
            "test-model", List::of, () -> {}, _ -> {},
            () -> null, _ -> 0.0, baseDir.toString(), false)
            .currentSessionId(() -> currentId)
            .build();

        CommandResult r = new BranchCommand().execute(
            ProviderTestCommandPorts.withSessions(ctx, mgr, storage), "");
        assertTrue(Strings.CS.contains(r.output(), "Resume with: /resume"),
            "without in-place load, output should hint at /resume; got: " + r.output());
    }

    @Test
    void forkRecordsPerMessageLineageWithoutJavaOnlyParentEntry(@TempDir Path baseDir) throws Exception {
        SessionManager mgr = new SessionManager(baseDir, "/test/project");
        SessionStorage storage = new SessionStorage();
        String currentId = mgr.createSession();
        storage.appendMessage(mgr.getSessionFile(currentId),
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("hello")));

        List<Message> loaded = new ArrayList<>();
        CommandContext ctx = CommandContext.builder(
            "test-model", List::of, () -> {}, _ -> {},
            () -> null, _ -> 0.0, baseDir.toString(), false)
            .loadMessages(loaded::addAll)
            .currentSessionId(() -> currentId)
            .build();

        CommandResult r = new BranchCommand().execute(
            ProviderTestCommandPorts.withSessions(ctx, mgr, storage), "");

        String forkId = extractForkSessionId(r.output());
        String forkJsonl = Files.readString(mgr.getSessionFile(forkId));
        assertTrue(Strings.CS.contains(forkJsonl, "\"forkedFrom\":{\"sessionId\":\"" + currentId + "\""),
            "TS records lineage on every cloned transcript message");
        assertFalse(Strings.CS.contains(forkJsonl, "\"type\":\"parent-session\""),
            "branch JSONL must not add the Java-only parent-session metadata row");
    }

    @Test
    void customTitle_usesProvidedLabel_withBranchSuffix(@TempDir Path baseDir) {
        SessionManager mgr = new SessionManager(baseDir, "/test/project");
        SessionStorage storage = new SessionStorage();
        String currentId = mgr.createSession();
        storage.appendMessage(mgr.getSessionFile(currentId),
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("hello")));

        CommandContext ctx = ctxWithSession(baseDir, currentId);
        CommandResult r = new BranchCommand().execute(
            ProviderTestCommandPorts.withSessions(ctx, mgr, storage), "wip experiments");

        String forkId = extractForkSessionId(r.output());
        assertEquals("wip experiments (Branch)", mgr.readCustomTitle(forkId));
    }

    @Test
    void customTitle_derivedFromFirstUserMessage_whenNoLabelGiven(@TempDir Path baseDir) {
        SessionManager mgr = new SessionManager(baseDir, "/test/project");
        SessionStorage storage = new SessionStorage();
        String currentId = mgr.createSession();
        storage.appendMessage(mgr.getSessionFile(currentId),
            new UserMessage(UUID.randomUUID().toString(),
                MessageContent.ofText("  fix   the\nauth   bug  ")));

        CommandContext ctx = ctxWithSession(baseDir, currentId);
        CommandResult r = new BranchCommand().execute(
            ProviderTestCommandPorts.withSessions(ctx, mgr, storage), "");

        String forkId = extractForkSessionId(r.output());
        // Whitespace collapsed (deriveFirstPrompt), then " (Branch)" suffixed.
        assertEquals("fix the auth bug (Branch)", mgr.readCustomTitle(forkId));
    }

    @Test
    void customTitle_collision_getsNumberedSuffix(@TempDir Path baseDir) {
        SessionManager mgr = new SessionManager(baseDir, "/test/project");
        SessionStorage storage = new SessionStorage();
        String currentId = mgr.createSession();
        storage.appendMessage(mgr.getSessionFile(currentId),
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("hello")));

        CommandContext ctx = ctxWithSession(baseDir, currentId);
        BranchCommand cmd = new BranchCommand();
        ctx = ProviderTestCommandPorts.withSessions(ctx, mgr, storage);

        CommandResult first = cmd.execute(ctx, "wip");
        CommandResult second = cmd.execute(ctx, "wip");

        assertEquals("wip (Branch)", mgr.readCustomTitle(extractForkSessionId(first.output())));
        assertEquals("wip (Branch 2)", mgr.readCustomTitle(extractForkSessionId(second.output())));
    }

    @Test
    void sessionIdSwitcher_receivesForkId_whenInPlaceResumeIsSupported(@TempDir Path baseDir) {
        SessionManager mgr = new SessionManager(baseDir, "/test/project");
        SessionStorage storage = new SessionStorage();
        String currentId = mgr.createSession();
        storage.appendMessage(mgr.getSessionFile(currentId),
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("hello")));

        List<String> switchedTo = new ArrayList<>();
        CommandContext ctx = CommandContext.builder(
            "test-model", List::of, () -> {}, _ -> {},
            () -> null, _ -> 0.0, baseDir.toString(), false)
            .loadMessages(_ -> {})
            .currentSessionId(() -> currentId)
            .sessionIdSwitcher(switchedTo::add)
            .build();

        CommandResult r = new BranchCommand().execute(
            ProviderTestCommandPorts.withSessions(ctx, mgr, storage), "");

        assertEquals(List.of(extractForkSessionId(r.output())), switchedTo,
            "the engine's active session must be repointed at the fork, not left on the original");
    }

    @Test
    void sessionIdSwitcher_notInvoked_whenLoadMessagesUnsupported(@TempDir Path baseDir) {
        SessionManager mgr = new SessionManager(baseDir, "/test/project");
        SessionStorage storage = new SessionStorage();
        String currentId = mgr.createSession();
        storage.appendMessage(mgr.getSessionFile(currentId),
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("hello")));

        List<String> switchedTo = new ArrayList<>();
        CommandContext ctx = CommandContext.builder(
            "test-model", List::of, () -> {}, _ -> {},
            () -> null, _ -> 0.0, baseDir.toString(), false)
            .currentSessionId(() -> currentId)
            .sessionIdSwitcher(switchedTo::add)
            .build();

        new BranchCommand().execute(ProviderTestCommandPorts.withSessions(ctx, mgr, storage), "");

        assertTrue(switchedTo.isEmpty(),
            "without in-place resume support there is no live engine session to repoint");
    }

    @Test
    void deriveFirstPrompt_scansBlocksForText_whenFirstMessageIsMixedContent() {
        List<ContentBlock> blocks = List.of(new ImageBlock(null), new TextBlock("  fix   the bug  "));
        UserMessage msg = new UserMessage(UUID.randomUUID().toString(), MessageContent.ofBlocks(blocks));

        assertEquals("fix the bug", BranchCommand.deriveFirstPrompt(List.of(msg)));
    }

    @Test
    void deriveFirstPrompt_fallsBackToDefault_whenBlocksHaveNoText() {
        List<ContentBlock> blocks = List.of(new ImageBlock(null));
        UserMessage msg = new UserMessage(UUID.randomUUID().toString(), MessageContent.ofBlocks(blocks));

        assertEquals("Branched conversation", BranchCommand.deriveFirstPrompt(List.of(msg)));
    }

    @Test
    void hooks_fireSessionEndThenSessionStart_whenInPlaceResumeSupported(@TempDir Path baseDir) {
        SessionManager mgr = new SessionManager(baseDir, "/test/project");
        SessionStorage storage = new SessionStorage();
        String currentId = mgr.createSession();
        storage.appendMessage(mgr.getSessionFile(currentId),
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("hello")));

        CapturingHooks hooks = new CapturingHooks();
        CommandContext ctx = CommandContext.builder(
            "test-model", List::of, () -> {}, _ -> {},
            () -> null, _ -> 0.0, baseDir.toString(), false)
            .loadMessages(_ -> {})
            .currentSessionId(() -> currentId)
            .hookDispatcher(hooks)
            .build();

        new BranchCommand().execute(ProviderTestCommandPorts.withSessions(ctx, mgr, storage), "");

        assertEquals(List.of("resume"), hooks.sessionEndReasons,
            "TS's shared resume() callback fires SessionEnd('resume') for fork too, not just plain /resume");
        assertEquals(List.of("resume"), hooks.sessionStartTriggers);
    }

    @Test
    void hooks_notFired_whenLoadMessagesUnsupported(@TempDir Path baseDir) {
        SessionManager mgr = new SessionManager(baseDir, "/test/project");
        SessionStorage storage = new SessionStorage();
        String currentId = mgr.createSession();
        storage.appendMessage(mgr.getSessionFile(currentId),
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("hello")));

        CapturingHooks hooks = new CapturingHooks();
        CommandContext ctx = CommandContext.builder(
            "test-model", List::of, () -> {}, _ -> {},
            () -> null, _ -> 0.0, baseDir.toString(), false)
            .currentSessionId(() -> currentId)
            .hookDispatcher(hooks)
            .build();

        new BranchCommand().execute(ProviderTestCommandPorts.withSessions(ctx, mgr, storage), "");

        assertTrue(hooks.sessionEndReasons.isEmpty());
        assertTrue(hooks.sessionStartTriggers.isEmpty());
    }

    @Test
    void sessionStartAdditionalContext_isAppendedAsSystemReminderMessage(@TempDir Path baseDir) {
        SessionManager mgr = new SessionManager(baseDir, "/test/project");
        SessionStorage storage = new SessionStorage();
        String currentId = mgr.createSession();
        storage.appendMessage(mgr.getSessionFile(currentId),
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("hello")));

        CapturingHooks hooks = new CapturingHooks();
        hooks.additionalContextToReturn = "project uses Java 21";
        List<Message> loaded = new ArrayList<>();
        CommandContext ctx = CommandContext.builder(
            "test-model", List::of, () -> {}, _ -> {},
            () -> null, _ -> 0.0, baseDir.toString(), false)
            .loadMessages(loaded::addAll)
            .currentSessionId(() -> currentId)
            .hookDispatcher(hooks)
            .build();

        new BranchCommand().execute(ProviderTestCommandPorts.withSessions(ctx, mgr, storage), "");

        assertEquals(2, loaded.size(), "original message + injected system-reminder");
        assertInstanceOf(UserMessage.class, loaded.get(1));
        UserMessage reminder = (UserMessage) loaded.get(1);
        assertTrue(reminder.isMeta(), "hook context must be hidden from the visible transcript");
        assertTrue(Strings.CS.contains(reminder.message().text(), "project uses Java 21"));
    }

    @Test
    void resetSessionCost_isInvoked_whenInPlaceResumeSupported(@TempDir Path baseDir) {
        SessionManager mgr = new SessionManager(baseDir, "/test/project");
        SessionStorage storage = new SessionStorage();
        String currentId = mgr.createSession();
        storage.appendMessage(mgr.getSessionFile(currentId),
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("hello")));

        boolean[] wasReset = {false};
        CommandContext ctx = CommandContext.builder(
            "test-model", List::of, () -> {}, _ -> {},
            () -> null, _ -> 0.0, baseDir.toString(), false)
            .loadMessages(_ -> {})
            .currentSessionId(() -> currentId)
            .resetSessionCost(() -> wasReset[0] = true)
            .build();

        new BranchCommand().execute(ProviderTestCommandPorts.withSessions(ctx, mgr, storage), "");

        assertTrue(wasReset[0],
            "TS resets displayed cost to zero on branch — a fresh session id has no stored cost record");
    }

    @Test
    void branchCopiesTheCurrentPlanIntoTheForkWithoutAffectingResume(@TempDir Path baseDir)
        throws Exception {
        SessionManager mgr = new SessionManager(baseDir, "/test/project");
        SessionStorage storage = new SessionStorage();
        String currentId = mgr.createSession();
        storage.appendMessage(mgr.getSessionFile(currentId),
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("hello")));
        Path plansDir = baseDir.resolve("plans");
        Files.createDirectories(plansDir);
        Files.writeString(plansDir.resolve(currentId + ".md"), "parent plan");

        CommandContext planContext = ProviderTestCommandPorts.withSessions(
            ctxWithSession(baseDir, currentId), mgr, storage);
        planContext = ProviderTestCommandPorts.withTooling(planContext, ProviderTestCommandPorts.plans(plansDir));
        CommandResult result = new BranchCommand().execute(planContext, "");

        String forkId = extractForkSessionId(result.output());
        assertEquals("parent plan", Files.readString(plansDir.resolve(forkId + ".md")));
    }

    @Test
    void customTitle_collision_isCaseInsensitive(@TempDir Path baseDir) {
        SessionManager mgr = new SessionManager(baseDir, "/test/project");
        SessionStorage storage = new SessionStorage();
        String currentId = mgr.createSession();
        storage.appendMessage(mgr.getSessionFile(currentId),
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("hello")));

        CommandContext ctx = ctxWithSession(baseDir, currentId);
        BranchCommand cmd = new BranchCommand();
        ctx = ProviderTestCommandPorts.withSessions(ctx, mgr, storage);

        CommandResult first = cmd.execute(ctx, "WIP");
        CommandResult second = cmd.execute(ctx, "wip");

        assertEquals("WIP (Branch)", mgr.readCustomTitle(extractForkSessionId(first.output())));
        assertEquals("wip (Branch 2)", mgr.readCustomTitle(extractForkSessionId(second.output())),
            "TS's exact-collision check is case-insensitive — 'wip' must collide with 'WIP'");
    }

    @Test
    void customTitle_collisionSearchIncludesSameRepoWorktrees(@TempDir Path baseDir) {
        String main = "/repo/main";
        String worktree = "/repo/worktree-a";
        SessionManager mainManager = new SessionManager(baseDir, main);
        SessionManager worktreeManager = new SessionManager(baseDir, worktree);
        SessionStorage storage = new SessionStorage();

        String existing = worktreeManager.createSession();
        storage.appendMessage(worktreeManager.getSessionFile(existing),
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("existing")));
        var title = JsonUtils.getMapper().createObjectNode();
        title.put("type", "custom-title");
        title.put("customTitle", "wip (Branch)");
        title.put("sessionId", existing);
        storage.appendCustomEntry(worktreeManager.getSessionFile(existing), title);

        String currentId = mainManager.createSession();
        storage.appendMessage(mainManager.getSessionFile(currentId),
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("source")));
        SessionSearch search = new SessionSearch(
            baseDir, main, () -> List.of(main, worktree));
        CommandContext context = CommandContext.builder(
            "test-model", List::of, () -> {}, _ -> {},
            () -> null, _ -> 0.0, baseDir.toString(), false)
            .currentSessionId(() -> currentId)
            .build();

        CommandResult result = new BranchCommand().execute(
            ProviderTestCommandPorts.withSessions(context,
                ProviderTestCommandPorts.sessions(mainManager, storage, search)), "wip");

        assertEquals("wip (Branch 2)",
            mainManager.readCustomTitle(extractForkSessionId(result.output())));
    }

    @Test
    void gitBranch_isPreservedIntoForkedTranscript(@TempDir Path baseDir) throws Exception {
        SessionManager mgr = new SessionManager(baseDir, "/test/project");
        SessionStorage storage = new SessionStorage();
        String currentId = mgr.createSession();
        storage.appendMessage(mgr.getSessionFile(currentId),
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("hello")),
            currentId, "/some/cwd", false, "feature/my-branch");

        CommandContext ctx = ctxWithSession(baseDir, currentId);
        CommandResult r = new BranchCommand().execute(
            ProviderTestCommandPorts.withSessions(ctx, mgr, storage), "");

        String forkId = extractForkSessionId(r.output());
        String forkContent = Files.readString(mgr.getSessionFile(forkId));
        assertTrue(Strings.CS.contains(forkContent, "\"gitBranch\":\"feature/my-branch\""),
            "gitBranch should be re-stamped onto every copied line; got: " + forkContent);
    }

    @Test
    void emptyTranscriptFile_reportsNoConversationToBranch(@TempDir Path baseDir) throws Exception {
        SessionManager mgr = new SessionManager(baseDir, "/test/project");
        String currentId = mgr.createSession();
        Path file = mgr.getSessionFile(currentId);
        Files.createDirectories(file.getParent());
        Files.createFile(file); // 0 bytes, but exists

        CommandContext ctx = ctxWithSession(baseDir, currentId);
        CommandResult r = new BranchCommand().execute(
            ProviderTestCommandPorts.withSessions(ctx, mgr, new SessionStorage()), "");

        assertTrue(Strings.CI.contains(r.output(), "no conversation to branch"),
            "a 0-byte transcript must be classified the same as a missing one; got: " + r.output());
    }

    private static String extractForkSessionId(String output) {
        for (String line : output.split("\n")) {
            if (Strings.CS.startsWith(line, "New session: ")) {
                return line.substring("New session: ".length()).trim();
            }
        }
        throw new AssertionError("no \"New session: \" line in output: " + output);
    }

    private static CommandContext ctxWithSession(Path baseDir, String sessionId) {
        return CommandContext.builder(
            "test-model", List::of, () -> {}, _ -> {},
            () -> null, _ -> 0.0, baseDir.toString(), false)
            .currentSessionId(() -> sessionId)
            .build();
    }
}
