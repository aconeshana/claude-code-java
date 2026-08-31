package com.claudecode.core.engine;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Engine-lifecycle hook for generating a one-line, Haiku-produced summary of a just-completed tool
 * batch.
 */
public interface ToolBatchSummarizer {

    /**
     * @param tools                  the completed tool batch (name + input/output)
     * @param lastAssistantText      the last assistant text block before this
     *                               batch, or {@code null} if none
     * @param isNonInteractiveSession whether this is a non-interactive (print/SDK) session
     * @return a future resolving to the summary text, or {@code null} on
     *         empty input / generation failure — never completes exceptionally
     */
    CompletableFuture<String> summarizeAsync(List<ToolCallInfo> tools,
                                              String lastAssistantText,
                                              boolean isNonInteractiveSession);
}
