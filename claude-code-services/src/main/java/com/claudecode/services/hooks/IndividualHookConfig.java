package com.claudecode.services.hooks;

/**
 * A single fully-resolved hook entry with its provenance (source, matcher, event).
 */
public record IndividualHookConfig(
    HookEvent event,
    HookCommand command,
    String matcher,
    HookSource source,
    String pluginName
) {}
