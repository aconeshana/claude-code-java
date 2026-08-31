package com.claudecode.services.tips;

import com.claudecode.services.config.GlobalConfigStore;
import com.claudecode.core.config.ClaudePaths;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Curated list of external tips shown beneath the spinner during long operations.
 */
public final class ExternalTips {


    public static final List<String> TIPS = List.of(
        // Core workflow
        "Start with small features or bug fixes, tell Claude to propose a plan, and verify its suggested edits",
        "Use Plan Mode to prepare for a complex request before making changes. Press Shift+Tab twice to enable.",
        "Use /config to change your default permission mode (including Plan Mode)",
        "Ask Claude to create a todo list when working on complex tasks to track progress and remain on track",
        "Double-tap Esc to rewind the conversation to a previous point in time",
        "Run claude --continue or claude --resume to resume a conversation",
        "Name your conversations with /rename to find them easily in /resume later",

        // Parallel sessions
        "Use git worktrees to run multiple Claude sessions in parallel.",
        "Running multiple Claude sessions? Use /color and /rename to tell them apart at a glance.",

        // Input & interaction
        "Press Shift+Enter to send a multi-line message",
        "Hit Enter to queue up additional messages while Claude is working.",
        "Send messages to Claude while it works to steer Claude in real-time",
        "Did you know you can drag and drop image files into your terminal?",
        "Paste images into Claude Code using Ctrl+V",

        // Configuration
        "Use /permissions to pre-approve and pre-deny bash, edit, and MCP tools",
        "Use /statusline to set up a custom status line that will display beneath the input box",
        "Try setting environment variable COLORTERM=truecolor for richer colors",

        // Memory & context
        "Create a CLAUDE.md file in your project root to give Claude persistent instructions",
        "Use /memory to view and edit Claude's persistent memory for this project",
        "Create skills by adding .md files to .claude/skills/ in your project or ~/.claude/skills/ for global skills",
        "Use /compact to summarize and compress long conversations when context gets full",

        // MCP & tools
        "Use /mcp to connect external tools and data sources via Model Context Protocol",
        "Use /hooks to run custom scripts before/after tool use (git pre-commit, formatting, etc.)",
        "Run /doctor if Claude seems slow or you hit errors — it checks your setup",

        // Advanced
        "Use --agent <agent_name> to directly start a conversation with a subagent"
    );

    private ExternalTips() {}

    /**
     * Returns the next tip, choosing the one not shown for the longest time.
     */
    public static String getNextTip() {
        int seq = GlobalConfigStore.getInt("tipsSeq", 0) + 1;
        Map<String, Integer> history = readTipsHistory();
        int bestIdx = 0;
        int bestGap = Integer.MIN_VALUE;
        for (int i = 0; i < TIPS.size(); i++) {
            int last = history.getOrDefault(String.valueOf(i), -1);
            int gap = seq - last; // never-shown tips get the largest gap (seq + 1)
            if (gap > bestGap) {
                bestGap = gap;
                bestIdx = i;
            }
        }
        GlobalConfigStore.set("tipsSeq", seq);
        history.put(String.valueOf(bestIdx), seq);
        GlobalConfigStore.set("tipsHistory", history);
        return TIPS.get(bestIdx);
    }

    private static Map<String, Integer> readTipsHistory() {
        Map<String, Integer> history = new LinkedHashMap<>();
        JsonNode node = GlobalConfigStore.getNode(ClaudePaths.GLOBAL_JSON, "tipsHistory");
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(e -> {
                if (e.getValue().isInt()) history.put(e.getKey(), e.getValue().asInt());
            });
        }
        return history;
    }

    /**
     * Returns a tip based on an external session counter (deterministic rotation,
     * retained for callers that supply their own sequence).
     * @param sessionCount total number of sessions so far
     */
    public static String getTipForSession(int sessionCount) {
        return TIPS.get(sessionCount % TIPS.size());
    }
}
