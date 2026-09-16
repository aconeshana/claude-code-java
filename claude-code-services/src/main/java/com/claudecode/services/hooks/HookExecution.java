package com.claudecode.services.hooks;

/**
 * One executed hook command paired with its result and the output-driven
 * async handoff flags the compact aggregator needs.
 *
 * <ul>
 *   <li>{@code src/utils/hooks.ts} — the per-hook execution record yielded by
 *       {@code executeHooks} before aggregation.</li>
 * </ul>
 */
record HookExecution(HookCommand command, HookResult result,
                     boolean backgrounded, String initialOutput) {}
