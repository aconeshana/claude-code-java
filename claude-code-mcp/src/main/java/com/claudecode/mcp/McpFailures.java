package com.claudecode.mcp;

import org.apache.commons.lang3.Strings;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;

/**
 * Shared MCP connection-failure classification.
 */
public final class McpFailures {

    private McpFailures() {}

    public static boolean isAuthenticationFailure(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current);
                current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) continue;
            String lower = message.toLowerCase(Locale.ROOT);
            if (Strings.CS.contains(lower, "401")
                    || Strings.CS.contains(lower, "unauthorized")
                    || Strings.CS.contains(lower, "needs authentication")
                    || Strings.CS.contains(lower, "authentication required")
                    || Strings.CS.contains(lower, "oauth")) {
                return true;
            }
        }
        return false;
    }
}
