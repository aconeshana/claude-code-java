package com.claudecode.core.engine;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Payload carried by a {@link com.claudecode.core.queue.QueuedCommand} with mode {@code
 * "orphaned-permission"}.
 */
public record OrphanedPermission(
    /** The tool_use ID whose permission was answered out-of-band. */
    String toolUseId,
    /**
     * Raw permission result, matching the {@code control_response.response} shape:
     * {@code behavior} ("allow"|"deny"), optional {@code updatedInput} (allow),
     * optional {@code message} (deny). Parsed lazily by {@link OrphanedPermissionExecutor}.
     */
    JsonNode permissionResult
) {}
