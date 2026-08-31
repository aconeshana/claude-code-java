package com.claudecode.core.message;

/**
 * A plain-text system-reminder attachment (no file backing).
 */
public record TextReminderAttachment(String text) implements AttachmentPayload {
}
