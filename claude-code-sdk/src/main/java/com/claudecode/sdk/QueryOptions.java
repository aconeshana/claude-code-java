package com.claudecode.sdk;

import com.claudecode.core.engine.AbortController;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Public options for launching and controlling an out-of-process query.
public and internal query options.</li></ul>
 */
public final class QueryOptions {
    Path cwd;
    String model;
    String apiKey;
    String baseUrl;
    String fallbackModel;
    String effort;
    String thinking;
    String thinkingDisplay;
    Integer maxThinkingTokens;
    Integer maxTurns;
    Double maxBudgetUsd;
    Integer taskBudget;
    String agent;
    List<String> betas = List.of();
    JsonNode outputSchema;
    boolean debug;
    Path debugFile;
    String permissionMode = "default";
    boolean allowDangerouslySkipPermissions;
    String permissionPromptToolName;
    List<String> allowedTools = List.of();
    List<String> disallowedTools = List.of();
    List<String> tools;
    Map<String, Object> mcpServers = Map.of();
    boolean strictMcpConfig;
    boolean continueConversation;
    String resume;
    boolean forkSession;
    String resumeSessionAt;
    String sessionId;
    boolean persistSession = true;
    boolean includePartialMessages;
    boolean includeHookEvents;
    List<Path> additionalDirectories = List.of();
    List<Path> plugins = List.of();
    List<Path> pluginsNoMcp = List.of();
    Settings settings;
    JsonNode sandbox;
    List<String> settingSources = List.of();
    String managedSettings;
    Map<String, String> env = Map.of();
    List<String> extraArguments = List.of();
    Path executable;
    List<String> executableArguments = List.of();
    ProcessSpawner processSpawner;
    AbortController abortController;
    QueryCallbacks.CanUseTool canUseTool;
    Map<String, QueryCallbacks.JsonCallback> hooks = Map.of();
    QueryCallbacks.JsonCallback onElicitation;
    QueryCallbacks.JsonCallback onUserDialog;
    QueryCallbacks.JsonCallback getOAuthToken;
    QueryCallbacks.JsonCallback getHostAuthToken;
    List<String> supportedDialogKinds = List.of();
    Map<String, McpSdkServerConfigWithInstance> sdkMcpServers = Map.of();
    SessionStore sessionStore;
    boolean sessionStoreEager;
    Duration loadTimeout = Duration.ofSeconds(60);
    String systemPrompt;
    String appendSystemPrompt;
    String title;
    JsonNode agents;
    List<String> skills = List.of();
    JsonNode promptConfig;

    private QueryOptions() {}
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final QueryOptions value = new QueryOptions();
        public Builder cwd(Path v) { value.cwd = v; return this; }
        public Builder model(String v) { value.model = v; return this; }
        public Builder apiKey(String v) { value.apiKey = v; return this; }
        public Builder baseUrl(String v) { value.baseUrl = v; return this; }
        public Builder fallbackModel(String v) { value.fallbackModel = v; return this; }
        public Builder effort(String v) { value.effort = v; return this; }
        public Builder thinking(String v) { value.thinking = v; return this; }
        public Builder thinkingDisplay(String v) { value.thinkingDisplay = v; return this; }
        public Builder maxThinkingTokens(Integer v) { value.maxThinkingTokens = v; return this; }
        public Builder maxTurns(Integer v) { value.maxTurns = v; return this; }
        public Builder maxBudgetUsd(Double v) { value.maxBudgetUsd = v; return this; }
        public Builder taskBudget(Integer v) { value.taskBudget = v; return this; }
        public Builder agent(String v) { value.agent = v; return this; }
        public Builder betas(List<String> v) { value.betas = copy(v); return this; }
        public Builder outputSchema(JsonNode v) { value.outputSchema = v; return this; }
        public Builder debug(boolean v) { value.debug = v; return this; }
        public Builder debugFile(Path v) { value.debugFile = v; return this; }
        public Builder permissionMode(String v) { value.permissionMode = v; return this; }
        public Builder allowDangerouslySkipPermissions(boolean v) { value.allowDangerouslySkipPermissions = v; return this; }
        public Builder permissionPromptToolName(String v) { value.permissionPromptToolName = v; return this; }
        public Builder allowedTools(List<String> v) { value.allowedTools = copy(v); return this; }
        public Builder disallowedTools(List<String> v) { value.disallowedTools = copy(v); return this; }
        public Builder tools(List<String> v) { value.tools = v == null ? null : List.copyOf(v); return this; }
        public Builder mcpServers(Map<String, Object> v) { value.mcpServers = map(v); return this; }
        public Builder strictMcpConfig(boolean v) { value.strictMcpConfig = v; return this; }
        public Builder continueConversation(boolean v) { value.continueConversation = v; return this; }
        public Builder resume(String v) { value.resume = v; return this; }
        public Builder forkSession(boolean v) { value.forkSession = v; return this; }
        public Builder resumeSessionAt(String v) { value.resumeSessionAt = v; return this; }
        public Builder sessionId(String v) { value.sessionId = v; return this; }
        public Builder persistSession(boolean v) { value.persistSession = v; return this; }
        public Builder includePartialMessages(boolean v) { value.includePartialMessages = v; return this; }
        public Builder includeHookEvents(boolean v) { value.includeHookEvents = v; return this; }
        public Builder additionalDirectories(List<Path> v) { value.additionalDirectories = copy(v); return this; }
        public Builder plugins(List<Path> v) { value.plugins = copy(v); return this; }
        public Builder pluginsNoMcp(List<Path> v) { value.pluginsNoMcp = copy(v); return this; }
        public Builder settings(Settings v) { value.settings = v; return this; }
        public Builder sandbox(JsonNode v) { value.sandbox = v == null ? null : v.deepCopy(); return this; }
        public Builder settingSources(List<String> v) { value.settingSources = copy(v); return this; }
        public Builder managedSettings(String v) { value.managedSettings = v; return this; }
        public Builder env(Map<String, String> v) { value.env = map(v); return this; }
        public Builder extraArguments(List<String> v) { value.extraArguments = copy(v); return this; }
        public Builder executable(Path v) { value.executable = v; return this; }
        public Builder executableArguments(List<String> v) { value.executableArguments = copy(v); return this; }
        public Builder processSpawner(ProcessSpawner v) { value.processSpawner = v; return this; }
        public Builder abortController(AbortController v) { value.abortController = v; return this; }
        public Builder canUseTool(QueryCallbacks.CanUseTool v) { value.canUseTool = v; return this; }
        public Builder hooks(Map<String, QueryCallbacks.JsonCallback> v) { value.hooks = map(v); return this; }
        public Builder onElicitation(QueryCallbacks.JsonCallback v) { value.onElicitation = v; return this; }
        public Builder onUserDialog(QueryCallbacks.JsonCallback v) { value.onUserDialog = v; return this; }
        public Builder getOAuthToken(QueryCallbacks.JsonCallback v) { value.getOAuthToken = v; return this; }
        public Builder getHostAuthToken(QueryCallbacks.JsonCallback v) { value.getHostAuthToken = v; return this; }
        public Builder supportedDialogKinds(List<String> v) { value.supportedDialogKinds = copy(v); return this; }
        public Builder sdkMcpServers(Map<String, McpSdkServerConfigWithInstance> v) { value.sdkMcpServers = map(v); return this; }
        public Builder sessionStore(SessionStore v) { value.sessionStore = v; return this; }
        public Builder sessionStoreEager(boolean v) { value.sessionStoreEager = v; return this; }
        public Builder loadTimeout(Duration v) { value.loadTimeout = v; return this; }
        public Builder systemPrompt(String v) { value.systemPrompt = v; return this; }
        public Builder appendSystemPrompt(String v) { value.appendSystemPrompt = v; return this; }
        public Builder title(String v) { value.title = v; return this; }
        public Builder agents(JsonNode v) { value.agents = v == null ? null : v.deepCopy(); return this; }
        public Builder skills(List<String> v) { value.skills = copy(v); return this; }
        public Builder promptConfig(JsonNode v) { value.promptConfig = v == null ? null : v.deepCopy(); return this; }
        public QueryOptions build() { return value; }
        private static <T> List<T> copy(List<T> value) { return value == null ? List.of() : List.copyOf(value); }
        private static <K, V> Map<K, V> map(Map<K, V> value) { return value == null ? Map.of() : Map.copyOf(value); }
    }
}
