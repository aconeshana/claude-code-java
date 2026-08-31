package com.claudecode.tools.tasks.teammate;

/** Protocol message type constants exchanged over {@link TeammateMailbox}. */
public final class MailTypes {

    private MailTypes() {}

    public static final String SHUTDOWN_REQUEST = "shutdown_request";
    public static final String SHUTDOWN_RESPONSE = "shutdown_response";
    public static final String PLAN_APPROVAL_REQUEST = "plan_approval_request";
    public static final String PLAN_APPROVAL_RESPONSE = "plan_approval_response";
    public static final String PERMISSION_REQUEST = "permission_request";
    public static final String PERMISSION_RESPONSE = "permission_response";
    public static final String USER_MESSAGE = "user_message";
    /** Teammate → leader: the teammate has finished its turn and is idle/available. */
    public static final String IDLE_NOTIFICATION = "idle_notification";
/**
     * Local-only marker appended to the teammate's message log when a single turn is interrupted (Esc /
     * currentWorkAbortController).
     */
    public static final String INTERRUPT = "interrupt";

    public static final String TASK_ASSIGNMENT = "task_assignment";
}
