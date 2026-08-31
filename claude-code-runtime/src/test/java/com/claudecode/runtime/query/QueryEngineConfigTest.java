package com.claudecode.runtime.query;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.engine.StreamingClient;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.agent.BuiltInAgentDefinitions;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.SkillListingEntry;
import com.claudecode.core.message.TodoItem;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.model.ModelNames;
import com.claudecode.core.prompt.SystemPromptRuntime;
import com.claudecode.core.queue.MessageQueueManager;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class QueryEngineConfigTest {

    private static final StreamingClient NOOP_CLIENT = new StreamingClient() {
        @Override
        public Iterator<StreamingEvent> createStream(StreamRequest request) {
            return Collections.emptyIterator();
        }

        @Override
        public String getModel() { return "test-model"; }
    };

    @Test
    void builderRequiresLlmClient() {
        assertThrows(IllegalStateException.class, () ->
            QuerySessionSpec.builder().build()
        );
    }

    @Test
    void builderWithDefaults() {
        var config = QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .build();

        assertSame(NOOP_CLIENT, config.llmClient());
        assertEquals(ModelNames.DEFAULT_MAIN_LOOP_MODEL, config.model());
        assertEquals("", config.systemPrompt());
        assertEquals(16384, config.maxTokens());
        assertEquals(0, config.maxTurns(),
            "released maxTurns is undefined unless --max-turns is passed to a --print run");
        assertEquals(-1.0, config.maxBudgetUsd());
        assertTrue(config.initialMessages().isEmpty());
        assertNull(config.abortController());
        assertTrue(config.tools().isEmpty());
        assertTrue(config.readFileCache().isEmpty());
        assertEquals(config.initialWorkingDirectory(), config.gitStatusWorkingDirectory());
    }

    @Test
    void querySessionExposesTheInjectedFastModeControllerAsLiveState() {
        FastModeController controller = new FastModeController(true, false, () -> 0L);
        DefaultQuerySession session = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .model("opus")
            .fastModeController(controller)
            .build());

        assertSame(controller, session.configuration().getFastModeController());
        assertEquals("off", session.configuration().getFastModeState());

        controller.setEnabled(true);
        assertEquals("on", session.configuration().getFastModeState());
    }

    @Test
    void gitStatusWorkingDirectoryCanBeAnchoredSeparatelyFromLiveCwd() {
        var config = QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .workingDirectory("/tmp/worktree")
            .gitStatusWorkingDirectory("/tmp/repository")
            .build();

        assertEquals("/tmp/worktree", config.workingDirectory());
        assertEquals("/tmp/repository", config.gitStatusWorkingDirectory());
    }

    @Test
    void builderWithAllFields() {
        var abort = new AbortController();
        var messages = List.<Message>of(
            new UserMessage("u1", MessageContent.ofText("hello"))
        );
        var tools = List.of("BashTool", "FileReadTool");
        var cache = Map.of("file.txt", "content");

        var config = QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .model("claude-opus-4-20250514")
            .systemPrompt("You are helpful.")
            .maxTokens(8192)
            .maxTurns(50)
            .maxBudgetUsd(5.0)
            .initialMessages(messages)
            .abortController(abort)
            .tools(tools)
            .readFileCache(cache)
            .build();

        assertEquals("claude-opus-4-20250514", config.model());
        assertEquals("You are helpful.", config.systemPrompt());
        assertEquals(8192, config.maxTokens());
        assertEquals(50, config.maxTurns());
        assertEquals(5.0, config.maxBudgetUsd());
        assertEquals(1, config.initialMessages().size());
        assertSame(abort, config.abortController());
        assertEquals(2, config.tools().size());
        assertEquals("content", config.readFileCache().get("file.txt"));
    }

    @Test
    void initialMessagesAreDefensivelyCopied() {
        var messages = new ArrayList<Message>();
        messages.add(new UserMessage("u1", MessageContent.ofText("hello")));

        var config = QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .initialMessages(messages)
            .build();

        messages.add(new UserMessage("u2", MessageContent.ofText("world")));
        assertEquals(1, config.initialMessages().size());
    }

    @Test
    void setUserSpecifiedModelUpdatesModel() {
        var config = QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .model("original-model")
            .build();

        config.setUserSpecifiedModel("new-model");
        assertEquals("new-model", config.model());
    }

    @Test
    void transientMainLoopWriterDoesNotReplaceTheUserModelPreference() {
        var config = QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .model("claude-haiku-4-5-20251001")
            .modelPreference("haiku")
            .build();

        config.setMainLoopModelOverride("claude-opus-5");

        assertEquals("claude-opus-5", config.model());
        assertEquals("haiku", config.modelPreference());

        config.setMainLoopModelOverride(null);
        assertEquals("claude-haiku-4-5-20251001", config.model());
        assertEquals("haiku", config.modelPreference());
    }

    @Test
    void repeatedRefusalFallbacksPreserveTheOriginalWriterForSessionRecovery() {
        var config = QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .model("settings-model")
            .build();
        config.setMainLoopModelOverride("pre-fallback-writer");

        config.activateRefusalFallback("first-fallback");
        config.activateRefusalFallback("second-fallback");
        config.restoreRefusalFallbackForSessionTransition();

        assertEquals("pre-fallback-writer", config.model());
        assertEquals("pre-fallback-writer", config.mainLoopModelOverride());
    }

    @Test
    void setUserSpecifiedModelRecomputesModelDefaultMaxTokensWhenResolverIsConfigured() {
        var config = QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .model("claude-sonnet-4-6")
            .maxTokens(32_000)
            .maxTokensResolver(model -> Strings.CS.contains(model, "opus-4-6") ? 64_000 : 32_000)
            .build();

        config.setUserSpecifiedModel("claude-opus-4-6");

        assertEquals(64_000, config.maxTokens());
    }

    @Test
    void dynamicModelSupplierTracksSettingsUntilAUserOverrideIsPinned() {
        AtomicReference<String> live = new AtomicReference<>("settings-model");
        var config = QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .model("settings-model")
            .dynamicModelSupplier(live::get)
            .build();

        live.set("updated-settings-model");
        assertEquals("updated-settings-model", config.model());

        config.setUserSpecifiedModel("runtime-model");
        live.set("ignored-while-pinned");
        assertEquals("runtime-model", config.model());

        config.clearUserSpecifiedModelOverride();
        assertEquals("ignored-while-pinned", config.model());
    }

    @Test
    void modelPreferenceKeepsDefaultDistinctFromTheResolvedEffectiveModel() {
        AtomicReference<String> liveModel = new AtomicReference<>("claude-sonnet-4-6");
        AtomicReference<String> livePreference = new AtomicReference<>();
        var config = QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .model("claude-sonnet-4-6")
            .modelPreference(null)
            .dynamicModelSupplier(liveModel::get)
            .dynamicModelPreferenceSupplier(livePreference::get)
            .build();

        assertEquals("claude-sonnet-4-6", config.model());
        assertNull(config.modelPreference(),
            "an unconfigured session must open /model on Default, not the resolved Sonnet row");

        config.setUserSpecifiedModel("claude-opus-4-8");
        assertEquals("claude-opus-4-8", config.modelPreference());

        liveModel.set("claude-haiku-4-5-20251001");
        livePreference.set("haiku");
        config.clearUserSpecifiedModelOverride();
        assertEquals("haiku", config.modelPreference());
        assertEquals("claude-haiku-4-5-20251001", config.model());
    }

    @Test
    void dynamicEffortSettingReplacesOnDefinedChangesButPreservesOnClear() {
        AtomicReference<String> setting = new AtomicReference<>();
        var config = QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .dynamicEffortSettingSupplier(setting::get)
            .build();

        config.setEffortValue("high");
        assertEquals("high", config.effortValue());

        setting.set("medium");
        assertEquals("medium", config.effortValue());

        setting.set(null);
        assertEquals("medium", config.effortValue());

        setting.set("low");
        assertEquals("low", config.effortValue());
    }

    @Test
    void initialSettingsObservationDoesNotOverwriteExplicitCliEffort() {
        AtomicReference<String> setting = new AtomicReference<>("high");
        var config = QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .dynamicEffortSettingSupplier(setting::get)
            .build();

        config.setEffortValue("low");
        config.initializeDynamicEffortObservation();
        assertEquals("low", config.effortValue());

        setting.set("medium");
        assertEquals("medium", config.effortValue(),
            "a later settings change still propagates after startup observation");
    }

    @Test
    void thinkingEnabledDefaultsToTrue() {
        var config = QuerySessionSpec.builder().llmClient(NOOP_CLIENT).build();
        assertTrue(config.isThinkingEnabled());
    }

    @Test
    void setThinkingEnabled() {
        var config = QuerySessionSpec.builder().llmClient(NOOP_CLIENT).build();
        config.setThinkingEnabled(false);
        assertFalse(config.isThinkingEnabled());
        config.setThinkingEnabled(true);
        assertTrue(config.isThinkingEnabled());
    }

    @Test
    void toggleThinkingReturnNewState() {
        var config = QuerySessionSpec.builder().llmClient(NOOP_CLIENT).build();
        // default ON → toggle → OFF
        assertFalse(config.toggleThinking());
        assertFalse(config.isThinkingEnabled());
        // OFF → toggle → ON
        assertTrue(config.toggleThinking());
        assertTrue(config.isThinkingEnabled());
    }

    @Test
    void toggleThinkingIsIdempotentOnSameState() {
        var config = QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT).build();
        config.setThinkingEnabled(false);
        boolean after1 = config.toggleThinking(); // → true
        boolean after2 = config.toggleThinking(); // → false
        assertTrue(after1);
        assertFalse(after2);
    }

    @Test
    void cacheSafeForkBuilderInheritsEveryCacheCriticalParam() {
        Supplier<SystemPromptRuntime> promptRuntime = SystemPromptRuntime::empty;
        Supplier<String> claudeMd = () -> "CLAUDE.md tail";
        Supplier<List<BuiltInAgentDefinitions.AgentDefinition>> agents = List::of;
        Supplier<Map<String, String>> mcpInstructions = Map::of;
        Supplier<String> outputStyle = () -> "concise";
        Supplier<List<TodoItem>> todos = List::of;
        Supplier<Set<String>> skillTriggers = Set::of;
        Supplier<List<SkillListingEntry>> skills = List::of;
        BiFunction<String, String, String> mcpResource = (_, _) -> "res";
        Supplier<Boolean> gitInstructions = () -> false;

        var parent = QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .model("claude-opus-4-8")
            .systemPrompt("parent system prompt")
            .maxTokens(64_000)
            .tools(List.of("BashTool", "FileReadTool"))
            .workingDirectory("/tmp/parent-cwd")
            .mcpServers(List.of("srv-a", "srv-b"))
            .gitStatusWorkingDirectory("/tmp/parent-repo")
            .promptRuntimeSupplier(promptRuntime)
            .claudeMdContentSupplier(claudeMd)
            .activeAgentsSupplier(agents)
            .mcpServerInstructionsSupplier(mcpInstructions)
            .outputStyleSupplier(outputStyle)
            .todosSupplier(todos)
            .dynamicSkillDirTriggersSupplier(skillTriggers)
            .skillListingSupplier(skills)
            .mcpResourceReader(mcpResource)
            .includeGitInstructionsSupplier(gitInstructions)
            .build();

        var fork = parent.cacheSafeForkBuilder().llmClient(NOOP_CLIENT).build();

        // Cache-key inputs must be byte-identical to hit the parent's prompt cache.
        assertEquals("claude-opus-4-8", fork.model());
        assertEquals("parent system prompt", fork.systemPrompt());
        assertEquals(64_000, fork.maxTokens());
        assertEquals(List.of("BashTool", "FileReadTool"), fork.tools());
        assertEquals("/tmp/parent-cwd", fork.workingDirectory());
        assertEquals(List.of("srv-a", "srv-b"), fork.mcpServers());
        assertEquals("/tmp/parent-repo", fork.gitStatusWorkingDirectory());
        // Every system-prompt input supplier is carried through by reference.
        assertSame(promptRuntime, fork.promptRuntimeSupplier());
        assertSame(claudeMd, fork.claudeMdContentSupplier());
        assertSame(agents, fork.activeAgentsSupplier());
        assertSame(mcpInstructions, fork.mcpServerInstructionsSupplier());
        assertSame(outputStyle, fork.outputStyleSupplier());
        assertSame(todos, fork.todosSupplier());
        assertSame(skillTriggers, fork.dynamicSkillDirTriggersSupplier());
        assertSame(skills, fork.skillListingSupplier());
        assertSame(mcpResource, fork.mcpResourceReader());
        assertSame(gitInstructions, fork.includeGitInstructionsSupplier());
    }

    @Test
    void cacheSafeForkBuilderIsolatesMutableSessionStateAndTheDreamTrigger() {
        var parentIdentity = SessionIdentity.newRandom();
        var parentQueue = new MessageQueueManager();
        AutoDreamEngine parentDream = _ -> {
            throw new AssertionError("fork must not inherit the auto-dream trigger");
        };

        var parent = QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .sessionIdentity(parentIdentity)
            .messageQueue(parentQueue)
            .autoDreamEngine(parentDream)
            .agentId("parent-agent")
            .build();

        var fork = parent.cacheSafeForkBuilder()
            .llmClient(NOOP_CLIENT)
            .build();

        // A dream/extraction fork must not carry the parent's per-session
        // mutable state — otherwise it would interfere with the parent loop.
        assertNotSame(parentIdentity, fork.sessionIdentity(),
            "fork gets a fresh session identity");
        assertNull(fork.messageQueue(),
            "fork does not share the parent command queue");
        assertNull(fork.agentId(),
            "fork does not inherit the parent agent id");
        // Critically: not inheriting the trigger is what prevents a dream fork
        // from recursively spawning another dream.
        assertNull(fork.autoDreamEngine(),
            "fork must not inherit the auto-dream trigger (no recursive dreams)");
        assertNull(fork.memoryExtractor(),
            "fork must not inherit the memory extractor");
    }
}
