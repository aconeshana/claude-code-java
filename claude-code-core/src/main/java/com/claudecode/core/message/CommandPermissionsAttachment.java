package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Transcript/runtime-only permission changes emitted by prompt commands and
 * inline Skill-tool invocations.
 *
 * <ul>
 *   <li>{@code command_permissions}
 *       attachment shape ({@code allowedTools}, optional {@code model}).</li>
 *   <li>this attachment
 *       renders to no Anthropic request messages.</li>
 * </ul>
 */
public record CommandPermissionsAttachment(
    @JsonProperty("allowedTools") List<String> allowedTools,
    @JsonProperty("model") @JsonInclude(JsonInclude.Include.NON_NULL) String model
) implements AttachmentPayload {
    public CommandPermissionsAttachment {
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
    }
}
