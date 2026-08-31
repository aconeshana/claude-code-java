package com.claudecode.permissions;


public enum PermissionBehavior {
    /** Allow the tool execution. */
    ALLOW,
    /** Deny the tool execution. */
    DENY,
    /** Ask the user for permission. */
    ASK,
    /** Pass through to the next rule or mode-based decision. */
    PASSTHROUGH
}
