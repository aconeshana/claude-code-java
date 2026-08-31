package com.claudecode.cli;

/**
 * Launch values resolved after settings, agent selection, and stdin processing.
 *
 * <ul>
 *   <li>combines argv and text stdin
 *       before startup continues.</li>
 *   <li>applies selected
 *       agent model, system prompt, and initial prompt without mutating argv.</li>
 *   <li>resolves the
 *       nullable startup preference separately from the concrete model used
 *       for requests after trusted settings environment overlays.</li>
 * </ul>
 */
record CliResolvedLaunch(
        CliLaunchRequest request,
        String model,
        String modelPreference,
        boolean modelPinned,
        String systemPrompt,
        String appendSystemPrompt,
        String initialPrompt) {
}
