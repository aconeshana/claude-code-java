package com.claudecode.ui.lanterna.input;

import com.claudecode.core.message.PastedContent;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Non-owning view of {@link PromptHistory}: the read/write surface a feature needs to record,
 * undo, search, and navigate prompt history, without {@link PromptHistory#close()} — only the
 * REPL screen that owns the backing history file may shut it down. Mirrors the
 * {@code McpConnection}/{@code McpConnectionView} split in {@code claude-code-mcp}.
 */
public interface PromptHistoryView {

    void addEntry(String text, String sessionId, String cwd, String project,
                  Map<Integer, PastedContent> pastedContents);

    void removeLastEntry();

    PromptHistory.HistoryReader openGlobalHistoryReader();

    CompletableFuture<Integer> countEntriesAsync(String project, String modeFilter);

    CompletableFuture<List<PromptHistory.Entry>> getEntriesWithPastedAsync(
        int limit, String project, String sessionId, String modeFilter);
}
