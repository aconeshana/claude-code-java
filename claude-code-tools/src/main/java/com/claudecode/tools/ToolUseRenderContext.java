package com.claudecode.tools;

import com.claudecode.core.message.ProgressMessage;
import java.util.List;

/**
 * Per-call state available to {@link Tool#renderToolUseTag}.
 */
public record ToolUseRenderContext(
    String toolUseId,
    Object toolUseResult,
    List<ProgressMessage> progressMessages,
    String mainModel
) {
    public ToolUseRenderContext {
        progressMessages = progressMessages == null ? List.of() : List.copyOf(progressMessages);
    }

    public static ToolUseRenderContext empty() {
        return new ToolUseRenderContext(null, null, List.of(), null);
    }
}
