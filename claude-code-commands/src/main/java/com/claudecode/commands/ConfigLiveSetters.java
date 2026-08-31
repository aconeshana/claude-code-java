package com.claudecode.commands;

import java.util.function.Consumer;

/**
 * Bundled live-apply setters for {@code /config} settings that need to affect the running session
 * immediately (not just persist to disk).
 */
public record ConfigLiveSetters(
    Consumer<Boolean> verboseSetter,
    Consumer<String> themeSetter,
    Consumer<Boolean> autoCompactSetter,
    Consumer<Boolean> thinkingEnabledSetter,
    Consumer<Boolean> reducedMotionSetter,
    Consumer<Boolean> claudeHudSetter
) {
    /** Compatibility constructor for existing embedders/tests. */
    public ConfigLiveSetters(
        Consumer<Boolean> verboseSetter,
        Consumer<String> themeSetter,
        Consumer<Boolean> autoCompactSetter,
        Consumer<Boolean> thinkingEnabledSetter,
        Consumer<Boolean> reducedMotionSetter
    ) {
        this(verboseSetter, themeSetter, autoCompactSetter, thinkingEnabledSetter,
            reducedMotionSetter, null);
    }
}
