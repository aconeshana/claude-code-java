package com.claudecode.ui.lanterna.repl;

import org.apache.commons.lang3.StringUtils;

/**
 * Decides whether a soft-interrupted prompt should be cached for later restoration.
 */
final class InterruptedPromptPolicy {

    private InterruptedPromptPolicy() {}

    static boolean shouldCacheSoftInterruptedPrompt(
            String prompt, boolean interactiveStartupPrompt) {
        return StringUtils.isNotBlank(prompt) && !interactiveStartupPrompt;
    }
}
