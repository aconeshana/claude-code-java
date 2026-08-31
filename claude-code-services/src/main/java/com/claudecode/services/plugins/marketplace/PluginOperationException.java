package com.claudecode.services.plugins.marketplace;

/**
 * Unchecked failure of a marketplace/plugin operation carrying a user-facing message.
 */
public class PluginOperationException extends RuntimeException {

    public PluginOperationException(String message) {
        super(message);
    }

    public PluginOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
