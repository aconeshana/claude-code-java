package com.claudecode.core.engine;

import java.nio.file.Path;
import java.util.List;

/**
 * Application-owned effects emitted by successful hook JSON output.
 */
public interface HookEffectSink {

    HookEffectSink NOOP = new HookEffectSink() { };

    default void showSystemMessage(String event, String hookName, String message) { }

    default void showSuccessOutput(String event, String hookName, String output) { }

    default void emitTerminalSequence(String sequence) { }

    default void applySessionTitle(String title) { }

    default void reloadSkills() { }

    default void replaceWatchPaths(List<Path> paths) { }

    /** Rebase cwd-relative static FileChanged matchers after CwdChanged completes. */
    default void cwdChanged(Path oldCwd, Path newCwd) { }
}
