package com.claudecode.core.prompt;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.util.ArrayList;

import com.claudecode.core.process.SubprocessEnvironment;
import java.util.List;
import java.util.Set;

/**
 * Environment / model info sections.
 */
public final class EnvInfoSection {

    private EnvInfoSection() {}

    // ── OS / shell probing ─────────────────────────────────────────────────

    public static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (Strings.CS.contains(os, "mac") || Strings.CS.contains(os, "darwin")) return "darwin";
        if (Strings.CS.contains(os, "win")) return "win32";
        if (Strings.CS.contains(os, "linux")) return "linux";
        return os;
    }

    /**
     * Kernel identity string — {@code Darwin}, {@code Linux}, {@code Windows_NT}.
     */
    private static String osType() {
        return switch (platform()) {
            case "darwin" -> "Darwin";
            case "linux" -> "Linux";
            case "win32" -> "Windows_NT";
            default -> System.getProperty("os.name", "unknown");
        };
    }


    public static String getUnameSR() {
        String release = kernelRelease();
        if (Strings.CS.equals("win32", platform())) {
            return System.getProperty("os.name", "Windows") + " " + release;
        }
        return osType() + " " + release;
    }


    private static String kernelRelease() {
        if (Strings.CS.equals("win32", platform())) {
            return System.getProperty("os.version", "unknown");
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("uname", "-r");
            pb.redirectErrorStream(false);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            int exit = p.waitFor();
            if (exit == 0 && !out.isEmpty()) return out;
        } catch (Exception _) {
            // fall through to os.version
        }
        return System.getProperty("os.version", "unknown");
    }


    public static String getShellInfoLine() {
        String shell = SubprocessEnvironment.get("SHELL");
        if (StringUtils.isBlank(shell)) shell = "unknown";
        String shellName;
        if (Strings.CS.contains(shell, "zsh")) shellName = "zsh";
        else if (Strings.CS.contains(shell, "bash")) shellName = "bash";
        else shellName = shell;
        if (Strings.CS.equals("win32", platform())) {
            return "Shell: " + shellName + " (use Unix shell syntax, not Windows — "
                + "e.g., /dev/null not NUL, forward slashes in paths)";
        }
        return "Shell: " + shellName;
    }

    // ── Model lookups ──────────────────────────────────────────────────────


    public static String getMarketingNameForModel(String modelId) {
        if (modelId == null) return null;
        boolean has1m = Strings.CI.contains(modelId, "[1m]");
        String canonical = modelId.toLowerCase(Locale.ROOT);
        if (Strings.CS.contains(canonical, "claude-fable-5")) return "Fable 5";
        if (Strings.CS.contains(canonical, "claude-mythos-5")) return "Mythos 5";
        if (Strings.CS.contains(canonical, "claude-opus-5")) return "Opus 5";
        if (Strings.CS.contains(canonical, "claude-opus-4-8")) return "Opus 4.8";
        if (Strings.CS.contains(canonical, "claude-opus-4-6"))
            return has1m ? "Opus 4.6 (with 1M context)" : "Opus 4.6";
        if (Strings.CS.contains(canonical, "claude-opus-4-5")) return "Opus 4.5";
        if (Strings.CS.contains(canonical, "claude-opus-4-1")) return "Opus 4.1";
        if (Strings.CS.contains(canonical, "claude-opus-4")) return "Opus 4";
        if (Strings.CS.contains(canonical, "claude-sonnet-4-6"))
            return has1m ? "Sonnet 4.6 (with 1M context)" : "Sonnet 4.6";
        if (Strings.CS.contains(canonical, "claude-sonnet-4-5"))
            return has1m ? "Sonnet 4.5 (with 1M context)" : "Sonnet 4.5";
        if (Strings.CS.contains(canonical, "claude-sonnet-4"))
            return has1m ? "Sonnet 4 (with 1M context)" : "Sonnet 4";
        if (Strings.CS.contains(canonical, "claude-3-7-sonnet")) return "Claude 3.7 Sonnet";
        if (Strings.CS.contains(canonical, "claude-3-5-sonnet")) return "Claude 3.5 Sonnet";
        if (Strings.CS.contains(canonical, "claude-haiku-4-5")) return "Haiku 4.5";
        if (Strings.CS.contains(canonical, "claude-3-5-haiku")) return "Claude 3.5 Haiku";
        return null;
    }


    public static String getKnowledgeCutoff(String modelId) {
        if (modelId == null) return null;
        String canonical = modelId.toLowerCase(Locale.ROOT);
        if (Strings.CS.contains(canonical, "claude-opus-5")) return "May 2026";
        if (Strings.CS.contains(canonical, "claude-fable-5")
                || Strings.CS.contains(canonical, "claude-mythos-5")
                || Strings.CS.contains(canonical, "claude-opus-4-8")) {
            return "January 2026";
        }
        if (Strings.CS.contains(canonical, "claude-sonnet-4-6")) return "August 2025";
        if (Strings.CS.contains(canonical, "claude-opus-4-6")) return "May 2025";
        if (Strings.CS.contains(canonical, "claude-opus-4-5")) return "May 2025";
        if (Strings.CS.contains(canonical, "claude-haiku-4")) return "February 2025";
        if (Strings.CS.contains(canonical, "claude-opus-4")
            || Strings.CS.contains(canonical, "claude-sonnet-4")) return "January 2025";
        return null;
    }

    // ── Env info blocks ────────────────────────────────────────────────────


    public static String computeEnvInfo(
            String modelId,
            String cwd,
            boolean isGit,
            List<String> additionalWorkingDirectories) {
        String modelDescription = "";
        if (modelId != null) {
            String marketingName = getMarketingNameForModel(modelId);
            modelDescription = marketingName != null
                ? "You are powered by the model named " + marketingName
                    + ". The exact model ID is " + modelId + "."
                : "You are powered by the model " + modelId + ".";
        }
        String additionalDirsInfo =
            additionalWorkingDirectories != null && !additionalWorkingDirectories.isEmpty()
                ? "Additional working directories: "
                    + String.join(", ", additionalWorkingDirectories) + "\n"
                : "";
        String cutoff = getKnowledgeCutoff(modelId);
        String knowledgeCutoffMessage = cutoff != null
            ? "\n\nAssistant knowledge cutoff is " + cutoff + "."
            : "";

        return "Here is useful information about the environment you are running in:\n"
            + "<env>\n"
            + "Working directory: " + cwd + "\n"
            + "Is directory a git repo: " + (isGit ? "Yes" : "No") + "\n"
            + additionalDirsInfo
            + "Platform: " + platform() + "\n"
            + getShellInfoLine() + "\n"
            + "OS Version: " + getUnameSR() + "\n"
            + "</env>\n"
            + modelDescription + knowledgeCutoffMessage;
    }


    public static String computeSimpleEnvInfo(
            String modelId,
            String cwd,
            boolean isGit,
            boolean isWorktree,
            List<String> additionalWorkingDirectories) {

        String modelDescription = null;
        if (modelId != null) {
            String marketingName = getMarketingNameForModel(modelId);
            modelDescription = marketingName != null
                ? "You are powered by the model named " + marketingName
                    + ". The exact model ID is " + modelId + "."
                : "You are powered by the model " + modelId + ".";
        }
        String cutoff = getKnowledgeCutoff(modelId);
        String knowledgeCutoffMessage = cutoff != null
            ? "Assistant knowledge cutoff is " + cutoff + "."
            : null;


        List<Object> items = new ArrayList<>();
        items.add("Primary working directory: " + cwd);
        if (isWorktree) {
            items.add("This is a git worktree — an isolated copy of the repository. "
                + "Run all commands from this directory. Do NOT `cd` to the original "
                + "repository root.");
        }
        items.add("Is a git repository: " + isGit);
        if (additionalWorkingDirectories != null && !additionalWorkingDirectories.isEmpty()) {
            items.add("Additional working directories:");
            items.add(new ArrayList<>(additionalWorkingDirectories));
        }
        items.add("Platform: " + platform());
        items.add(getShellInfoLine());
        items.add("OS Version: " + getUnameSR());
        if (modelDescription != null) items.add(modelDescription);
        if (knowledgeCutoffMessage != null) items.add(knowledgeCutoffMessage);
        if (modelId != null) {
            items.add("The most recent Claude models are the Claude 5 family and Haiku 4.5. "
                + "Model IDs — Fable 5: 'claude-fable-5', Opus 5: 'claude-opus-5', "
                + "Sonnet 5: 'claude-sonnet-5', Haiku 4.5: "
                + "'claude-haiku-4-5-20251001'. When building AI applications, default "
                + "to the latest and most capable Claude models.");
            items.add("Claude Code is available as a CLI in the terminal, desktop app "
                + "(Mac/Windows), web app (claude.ai/code), and IDE extensions (VS Code, "
                + "JetBrains).");
            items.add("Fast mode for Claude Code uses Claude Opus with faster output "
                + "(it does not downgrade to a smaller model). It can be toggled with "
                + "/fast and is available on Opus 5/4.8/4.7.");
        }

        List<String> lines = new ArrayList<>();
        lines.add("# Environment");
        lines.add("You have been invoked in the following environment: ");
        lines.addAll(SystemPromptSections.prependBullets(items));
        return String.join("\n", lines);
    }


    @SuppressWarnings("unused")
    public static List<String> enhanceSystemPromptWithEnvDetails(
            List<String> existingSystemPrompt,
            String modelId,
            String cwd,
            boolean isGit,
            List<String> additionalWorkingDirectories,
            Set<String> enabledToolNames) {
        String notes = agentNotes();
        String envInfo = computeEnvInfo(modelId, cwd, isGit, additionalWorkingDirectories);
        List<String> out = new ArrayList<>(existingSystemPrompt);
        out.add(notes);
        out.add(envInfo);
        return out;
    }


    public static String agentNotes() {
        return """
            Notes:
            - Agent threads always have their cwd reset between bash calls, as a \
            result please only use absolute file paths.
            - In your final response, share file paths (always absolute, never \
            relative) that are relevant to the task. Include code snippets only \
            when the exact text is load-bearing (e.g., a bug you found, a function \
            signature the caller asked for) — do not recap code you merely read.
            - For clear communication with the user the assistant MUST avoid using \
            emojis.
            - Do not use a colon before tool calls. Text like "Let me read the \
            file:" followed by a read tool call should just be "Let me read the \
            file." with a period.
            - Do NOT Write report/summary/findings/analysis .md files. Return findings \
            directly as your final assistant message — the parent agent reads your \
            text output, not files you create. (Files written as input to another \
            tool are fine; this note is about report files.)""";
    }
}
