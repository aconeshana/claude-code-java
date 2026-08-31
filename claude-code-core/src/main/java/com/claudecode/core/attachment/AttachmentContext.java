package com.claudecode.core.attachment;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;

import com.claudecode.core.agent.BuiltInAgentDefinitions.AgentDefinition;
import com.claudecode.core.engine.FileStateCache;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.PlanModeExitInfo;
import com.claudecode.core.message.SkillListingEntry;
import com.claudecode.core.message.TodoItem;
import com.claudecode.core.message.UsageSnapshot;

/**
 * Per-turn inputs handed to every {@link AttachmentProvider}.
 */
public record AttachmentContext(
    /** The session working directory (absolute path string). */
    String workingDirectory,
    /** Full message history for the session (read-only use). */
    List<Message> messages,
    /** The current turn's user input; empty on tool-result-only turns. */
    String input,
/**
     * Session read-before-write cache (LRU 100).
     */
    FileStateCache fileStateCache,

    Predicate<String> fileReadDenied,
    /** Session-scoped de-dup set of already-injected memory file paths. */
    Set<String> loadedNestedMemoryPaths,
    /** This turn's pending trigger paths (cleared after each request build). */
    Set<String> nestedMemoryAttachmentTriggers,
    /** Hook dispatcher for {@code InstructionsLoaded} (null in tests / bridge). */
    HookDispatcher hookDispatcher,
    /** Agent id of the executing engine, or null for the main thread. */
    String agentId,
    /** Query source label (e.g. {@code "main"}, {@code "session_memory"}). */
    String querySource,
    /** Enabled tool names for this session. */
    List<String> toolNames,

    String criticalSystemReminder,

    List<AgentDefinition> activeAgents,

    Map<String, String> mcpServerInstructions,
    /** Enabled tool names from the PREVIOUS turn (the engine snapshots the
     *  current turn's tools after each request build); feeds
     *  {@code deferred_tools_delta}. {@code null} on the first turn. */
    List<String> previousTurnTools,
    /** True once an auto/manual compaction has run this session; feeds
     *  {@code compaction_reminder}. */
    boolean compactionOccurred,
    /** Resolved output style (e.g. "default" / "concise" / "formal"); feeds
     *  {@code output_style}. {@code null} or {@code "default"} suppresses it. */
    String outputStyle,
    /** Current agent's todo items; feeds {@code todo_reminder}. Empty when none. */
    List<TodoItem> todos,
    /** One-time plan-mode-exit signal; feeds {@code plan_mode_exit}. {@code null}
     *  when plan mode was not just exited. */
    PlanModeExitInfo planModeExit,
    /** Dynamic-skill trigger directories detected this turn; feeds {@code
     *  dynamic_skill}. Empty in Java (no such trigger state yet). */
    Set<String> dynamicSkillDirTriggers,
    /** Available skills for the Skill tool; feeds {@code skill_listing}. */
    List<SkillListingEntry> skills,
    /** Reads an MCP resource by {@code (server, uri) -> content}; feeds {@code
     *  mcp_resource}. Returns {@code null} when unavailable. Injected as a
     *  function so core never depends on the MCP module. */
    BiFunction<String, String, String> mcpResourceReader,
    /** Per-session token / USD-budget / output-token usage; feeds {@code
     *  token_usage} / {@code budget_usd} / {@code output_token_usage}. */
    UsageSnapshot usage
) {
    public static Builder builder(String workingDirectory) {
        return new Builder(workingDirectory);
    }

    /** Named construction keeps per-turn optional inputs independent as the context grows. */
    public static final class Builder {
        private final String workingDirectory;
        private List<Message> messages = List.of();
        private String input = "";
        private FileStateCache fileStateCache;
        private Predicate<String> fileReadDenied = _ -> false;
        private Set<String> loadedNestedMemoryPaths = Set.of();
        private Set<String> nestedMemoryAttachmentTriggers = Set.of();
        private HookDispatcher hookDispatcher;
        private String agentId;
        private String querySource = "";
        private List<String> toolNames = List.of();
        private String criticalSystemReminder;
        private List<AgentDefinition> activeAgents = List.of();
        private Map<String, String> mcpServerInstructions = Map.of();
        private List<String> previousTurnTools;
        private boolean compactionOccurred;
        private String outputStyle;
        private List<TodoItem> todos;
        private PlanModeExitInfo planModeExit;
        private Set<String> dynamicSkillDirTriggers = Set.of();
        private List<SkillListingEntry> skills = List.of();
        private BiFunction<String, String, String> mcpResourceReader;
        private UsageSnapshot usage;

        private Builder(String workingDirectory) {
            this.workingDirectory = workingDirectory;
        }

        public Builder messages(List<Message> value) { messages = value; return this; }
        public Builder input(String value) { input = value; return this; }
        public Builder fileStateCache(FileStateCache value) { fileStateCache = value; return this; }
        public Builder fileReadDenied(Predicate<String> value) {
            fileReadDenied = value != null ? value : _ -> false;
            return this;
        }
        public Builder loadedNestedMemoryPaths(Set<String> value) { loadedNestedMemoryPaths = value; return this; }
        public Builder nestedMemoryAttachmentTriggers(Set<String> value) { nestedMemoryAttachmentTriggers = value; return this; }
        public Builder hookDispatcher(HookDispatcher value) { hookDispatcher = value; return this; }
        public Builder agentId(String value) { agentId = value; return this; }
        public Builder querySource(String value) { querySource = value; return this; }
        public Builder toolNames(List<String> value) { toolNames = value; return this; }
        public Builder criticalSystemReminder(String value) { criticalSystemReminder = value; return this; }
        public Builder activeAgents(List<AgentDefinition> value) { activeAgents = value; return this; }
        public Builder mcpServerInstructions(Map<String, String> value) { mcpServerInstructions = value; return this; }
        public Builder previousTurnTools(List<String> value) { previousTurnTools = value; return this; }
        public Builder compactionOccurred(boolean value) { compactionOccurred = value; return this; }
        public Builder outputStyle(String value) { outputStyle = value; return this; }
        public Builder todos(List<TodoItem> value) { todos = value; return this; }
        public Builder planModeExit(PlanModeExitInfo value) { planModeExit = value; return this; }
        public Builder dynamicSkillDirTriggers(Set<String> value) { dynamicSkillDirTriggers = value; return this; }
        public Builder skills(List<SkillListingEntry> value) { skills = value; return this; }
        public Builder mcpResourceReader(BiFunction<String, String, String> value) { mcpResourceReader = value; return this; }
        public Builder usage(UsageSnapshot value) { usage = value; return this; }

        public AttachmentContext build() {
            return new AttachmentContext(workingDirectory, messages, input,
                fileStateCache, fileReadDenied, loadedNestedMemoryPaths,
                nestedMemoryAttachmentTriggers, hookDispatcher, agentId,
                querySource, toolNames, criticalSystemReminder, activeAgents,
                mcpServerInstructions, previousTurnTools, compactionOccurred,
                outputStyle, todos, planModeExit, dynamicSkillDirTriggers,
                skills, mcpResourceReader, usage);
        }
    }
}
