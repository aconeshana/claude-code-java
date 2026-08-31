package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonInclude;




@JsonInclude(JsonInclude.Include.NON_NULL)
public record HookSuccessAttachment(
    String content,
    String hookName,
    String toolUseID,
    String hookEvent,
    String stdout,
    String stderr,
    Integer exitCode,
    String command,
    Long durationMs
) implements AttachmentPayload {
}
