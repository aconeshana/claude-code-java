package com.claudecode.core.message;

import java.util.List;

/**
 * Model-visible additional context produced by a lifecycle or tool hook.
 */
public record HookAdditionalContextAttachment(
    List<String> content,
    String hookName,
    String toolUseID,
    String hookEvent
) implements AttachmentPayload {
    public HookAdditionalContextAttachment {
        content = content == null ? List.of() : List.copyOf(content);
    }
}
