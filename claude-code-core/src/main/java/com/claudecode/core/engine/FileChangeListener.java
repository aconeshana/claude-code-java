package com.claudecode.core.engine;

import java.nio.file.Path;

/**
 * Callback fired after a file-mutating tool (Write/Edit) completes
 * successfully. Lets external subsystems (e.g. LSP passive diagnostics)
 * react to file changes without {@code claude-code-tools} needing to know
 * they exist — {@link ConcurrentToolRunner} invokes this by tool name, not by
 * type, so the tool implementations stay decoupled from any listener.
 */
@FunctionalInterface
public interface FileChangeListener {
    /**
     * @param filePath the file that was written or edited
     * @param toolName the tool that produced the change ({@code "Write"} or {@code "Edit"})
     */
    void onFileChanged(Path filePath, String toolName);
}
