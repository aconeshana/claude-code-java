package com.claudecode.core.state;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-scoped registry mapping agent type names to their assigned color names.
 * Stores plain strings (e.g. "red", "blue") with no UI dependency.
 * <ul>
 *   <li>module-level {@code agentColorMap}</li>
 * </ul>
 * UI layer ({@code LanternaTheme#agentColor}) converts color names to {@code TextColor} on read.
 * {@code AgentTool} writes here when it resolves an agent definition's {@code color} field.
 */
public final class AgentColorStore {

    private static final Map<String, String> map = new ConcurrentHashMap<>();

    private AgentColorStore() {}


    public static void set(String agentType, String colorName) {
        if (agentType == null) return;
        if (colorName == null) { map.remove(agentType); return; }
        map.put(agentType, colorName);
    }

    /** Returns the color name for {@code agentType}, or {@code null} if unassigned. */
    public static String get(String agentType) {
        if (agentType == null) return null;
        return map.get(agentType);
    }

    /** Clears all assignments — used on session reset and in tests. */
    public static void resetAll() {
        map.clear();
    }
}
