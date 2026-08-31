package com.claudecode.tools;

import com.claudecode.core.error.ErrorUtils;

/**
 * Tool error message formatting.
 *
 * <p>matches  — {@code formatError}: extracts the
 * exception message and middle-truncates it to 10k chars. (That module also
 * exports {@code formatZodValidationError}, but in Java its role — producing the
 * input-validation error body — is owned by {@link ToolInputValidation}, see
 * that class for the mapping.)
 */
public final class ToolErrors {

    private ToolErrors() {}

    private static final int MAX_ERROR_LENGTH = 10_000;
    private static final int HALF_LENGTH = 5_000;


    public static String formatError(Throwable error) {
        String fullMessage = ErrorUtils.message(error).trim();
        if (fullMessage.isEmpty()) {
            fullMessage = "Command failed with no output";
        }
        if (fullMessage.length() <= MAX_ERROR_LENGTH) {
            return fullMessage;
        }
        String start = fullMessage.substring(0, HALF_LENGTH);
        String end = fullMessage.substring(fullMessage.length() - HALF_LENGTH);
        return start + "\n\n... [" + (fullMessage.length() - MAX_ERROR_LENGTH)
                + " characters truncated] ...\n\n" + end;
    }
}
