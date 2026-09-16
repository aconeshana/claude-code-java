package com.claudecode.services.hooks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes one matched hook command to the executor for its kind. Any executor
 * failure degrades to a Skip so a broken hook never fails the dispatch.
 *
 * <ul>
 *   <li>{@code src/utils/hooks.ts} — per-hook-type dispatch inside
 *       {@code executeHooks} (command / prompt / http / agent / SDK callback).</li>
 * </ul>
 */
final class HookCommandRunner {

    private static final Logger LOG = LoggerFactory.getLogger(HookCommandRunner.class);

    private final BashHookExecutor bash;
    private final HttpHookExecutor http;
    private final PromptHookExecutor prompts;
    private final AgentHookExecutor agents;
    private final HookOutputParser parser;

    HookCommandRunner(BashHookExecutor bash, HttpHookExecutor http, PromptHookExecutor prompts,
                      AgentHookExecutor agents, HookOutputParser parser) {
        this.bash = bash;
        this.http = http;
        this.prompts = prompts;
        this.agents = agents;
        this.parser = parser;
    }

    HookResult execute(HookCommand command, HookInput input, long defaultTimeoutMillis,
                       String callbackToolUseId) {
        try {
            return switch (command) {
                case BashCommandHook cmd -> bash.execute(cmd, input, defaultTimeoutMillis);
                case PromptHook cmd -> prompts.execute(cmd, input, defaultTimeoutMillis);
                case HttpHook cmd -> http.execute(cmd, input, defaultTimeoutMillis);
                case AgentHook cmd -> agents.execute(cmd, input, defaultTimeoutMillis);
                case CallbackHook cmd -> parser.parse(
                    cmd.callback().invoke(input, callbackToolUseId).toString(), input.event());
            };
        } catch (Exception e) {
            LOG.warn("Hook execution failed (single dispatch): {}", e.getMessage());
            return HookResult.skip();
        }
    }
}
