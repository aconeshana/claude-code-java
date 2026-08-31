package com.claudecode.ui.lanterna.statusline;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.effort.EffortHelpers;
import com.claudecode.core.message.TokenEstimator;
import com.claudecode.core.message.Usage;
import com.claudecode.core.metrics.SessionMetricsFormat;
import com.claudecode.core.metrics.SessionMetricsSnapshot;
import com.claudecode.core.process.ExecutableFinder;
import com.claudecode.core.process.ProcessResult;
import com.claudecode.core.process.ProcessRunner;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import com.googlecode.lanterna.TerminalTextUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Built-in two-line status HUD showing model, effort, project, Git state, and
 * an adaptive context-usage bar.
 *
 * <p>Git probing is explicitly enabled, bounded to one second, and cached for
 * one second across refreshes. Subscriber usage is omitted because no
 * subscriber rate-limit feed is available.
 */
final class BuiltInClaudeHudRenderer {

    private static final String RESET = "\u001b[0m";
    private static final String DIM = "\u001b[2m";
    private static final String RED = "\u001b[31m";
    private static final String GREEN = "\u001b[32m";
    private static final String YELLOW = "\u001b[33m";
    private static final String MAGENTA = "\u001b[35m";
    private static final String CYAN = "\u001b[36m";

    private static final int WARNING_PERCENT = 70;
    private static final int CRITICAL_PERCENT = 85;
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(1);
    private static final long GIT_CACHE_MS = 1_000;
    private static final ConcurrentHashMap<String, CachedGitStatus> GIT_STATUS_CACHE =
        new ConcurrentHashMap<>();

    private BuiltInClaudeHudRenderer() {}

    /** Render the expanded two-line surface for one status snapshot. */
    static String render(StatusLineInput input, int terminalWidth) {
        return render(input, terminalWidth, null);
    }

    @Explanation("Shows the effective reasoning effort in the opt-in built-in HUD")
    static String render(StatusLineInput input, int terminalWidth, String effortLevel) {
        List<String> lines = new ArrayList<>(2);
        lines.add(renderProjectLine(input, effortLevel));
        String context = renderContextLine(input, adaptiveBarWidth(terminalWidth));
        lines.add(truncateAnsi(appendMetrics(context, input.sessionMetrics()), terminalWidth));
        return String.join("\n", lines);
    }

    static String renderProjectLine(StatusLineInput input) {
        return renderProjectLine(input, null);
    }

    static String renderProjectLine(StatusLineInput input, String effortLevel) {
        List<String> parts = new ArrayList<>();
        String model = color("[" + safe(input.modelDisplayName(), "Unknown") + "]", CYAN);
        if (StringUtils.isNotBlank(effortLevel)) {
            String effort = Strings.CS.equals("auto", effortLevel)
                ? "effort:auto"
                : EffortHelpers.effortLevelToSymbol(effortLevel) + " " + effortLevel;
            model += color(" " + effort, DIM);
        }
        parts.add(model);

        List<String> projectParts = new ArrayList<>();
        projectParts.add(color(projectName(input.cwd()), YELLOW));
        for (String addedDir : input.addedDirs().stream().limit(5).toList()) {
            projectParts.add(color("+" + projectName(addedDir), DIM));
        }
        if (input.addedDirs().size() > 5) {
            projectParts.add(color("+" + (input.addedDirs().size() - 5) + " more", DIM));
        }

        GitStatus git = readGitStatus(input.cwd());
        if (git != null) {
            String branch = git.branch() + (git.dirty() ? "*" : "");
            projectParts.add(color("git:(", MAGENTA)
                + color(branch, CYAN)
                + color(")", MAGENTA));
        }
        parts.add(String.join(" ", projectParts));
        return RESET + String.join(" │ ", parts);
    }

    static String renderContextLine(StatusLineInput input, int barWidth) {
        int percent = input.usedPercentage() == null
            ? contextPercent(input.currentUsage(), input.contextWindowSize(), input.modelId())
            : clamp(input.usedPercentage());
        String thresholdColor = contextColor(percent);
        int filled = (int) Math.round(percent / 100.0 * barWidth);
        String bar = thresholdColor + "█".repeat(filled)
            + DIM + "░".repeat(Math.max(0, barWidth - filled)) + RESET;
        StringBuilder line = new StringBuilder(RESET)
            .append(color("Context", DIM)).append(' ')
            .append(bar).append(' ')
            .append(thresholdColor).append(percent).append('%').append(RESET);

        if (percent >= CRITICAL_PERCENT && input.currentUsage() != null) {
            Usage usage = input.currentUsage();
            long cache = usage.cacheCreationInputTokens() + usage.cacheReadInputTokens();
            line.append(color(" (in: " + formatTokens(usage.inputTokens())
                + ", cache: " + formatTokens(cache) + ")", DIM));
        }
        return line.toString();
    }

    static int adaptiveBarWidth(int terminalWidth) {
        if (terminalWidth > 0 && terminalWidth < 60) return 4;
        if (terminalWidth > 0 && terminalWidth < 100) return 6;
        return 10;
    }

    private static String appendMetrics(String context, SessionMetricsSnapshot metrics) {
        if (metrics == null || !metrics.complete()) return context;
        List<String> groups = new ArrayList<>();
        if (metrics.steps() > 0) {
            groups.add(metrics.turns() + " turns · " + metrics.steps() + " steps");
            List<String> durations = new ArrayList<>();
            if (metrics.llmMs() > 0) {
                durations.add("LLM " + SessionMetricsFormat.formatDuration(metrics.llmMs()));
            }
            if (metrics.toolMs() > 0) {
                durations.add("Tool call " + SessionMetricsFormat.formatDuration(metrics.toolMs()));
            }
            if (!durations.isEmpty()) groups.add(String.join(" · ", durations));

            List<String> speed = new ArrayList<>();
            if (metrics.ttftAverageMs() != null) {
                speed.add("TTFT avg "
                    + SessionMetricsFormat.formatDuration(metrics.ttftAverageMs()));
            }
            if (metrics.tokensPerSecond() != null) {
                speed.add(SessionMetricsFormat.formatTokensPerSecond(metrics.tokensPerSecond())
                    + " tok/s");
            }
            if (!speed.isEmpty()) groups.add(String.join(" · ", speed));
        }
        if (metrics.billedInputTokens() > 0 || metrics.outputTokens() > 0) {
            String cache = SessionMetricsFormat.cacheHitPercent(metrics);
            if (cache != null) groups.add("Cache hit " + cache + "%");
            groups.add("Input " + SessionMetricsFormat.formatTokens(metrics.billedInputTokens())
                + " tok · Output " + SessionMetricsFormat.formatTokens(metrics.outputTokens())
                + " tok");
        }
        return groups.isEmpty() ? context : context + " | " + String.join(" | ", groups);
    }

    /** ANSI-aware strict left-prefix truncation; terminal columns, not UTF-16 chars. */
    static String truncateAnsi(String value, int maxColumns) {
        if (maxColumns <= 0) return value;
        String plain = value.replaceAll("\\u001B\\[[;\\d]*m", "");
        if (TerminalTextUtils.getColumnWidth(plain) <= maxColumns) return value;
        int target = Math.max(0, maxColumns - 1);
        StringBuilder out = new StringBuilder();
        int columns = 0;
        for (int i = 0; i < value.length();) {
            if (value.charAt(i) == '\u001b') {
                int end = value.indexOf('m', i);
                if (end < 0) break;
                out.append(value, i, end + 1);
                i = end + 1;
                continue;
            }
            int codePoint = value.codePointAt(i);
            String text = new String(Character.toChars(codePoint));
            int width = TerminalTextUtils.getColumnWidth(text);
            if (columns + width > target) break;
            out.append(text);
            columns += width;
            i += Character.charCount(codePoint);
        }
        return out.append(RESET).append('…').toString();
    }

    @Explanation("Uses Codex/OpenAI cached-token semantics for GPT HUD fallback")
    private static int contextPercent(Usage usage, long window, String model) {
        if (usage == null || window <= 0) return 0;
        long tokens = TokenEstimator.contextInputTokens(usage, model);
        return clamp((int) Math.round(tokens * 100.0 / window));
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static String contextColor(int percent) {
        if (percent >= CRITICAL_PERCENT) return RED;
        if (percent >= WARNING_PERCENT) return YELLOW;
        return GREEN;
    }

    private static String color(String text, String ansi) {
        return ansi + text + RESET;
    }

    private static String safe(String value, String fallback) {
        return StringUtils.isBlank(value) ? fallback : sanitize(value);
    }

    private static String projectName(String cwd) {
        if (StringUtils.isBlank(cwd)) return "/";
        String normalized = sanitize(cwd).replace('\\', '/');
        while (normalized.length() > 1 && Strings.CS.endsWith(normalized, "/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return StringUtils.isBlank(name) ? "/" : name;
    }

    /** Drop terminal control bytes from values sourced from settings/processes. */
    private static String sanitize(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch >= 0x20 && ch != 0x7f) out.append(ch);
        }
        return out.toString();
    }

    private static String formatTokens(long tokens) {
        if (tokens >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", tokens / 1_000_000.0);
        if (tokens >= 1_000) return Math.round(tokens / 1_000.0) + "k";
        return Long.toString(tokens);
    }

    private static GitStatus readGitStatus(String cwd) {
        if (StringUtils.isBlank(cwd)) return null;
        long now = System.currentTimeMillis();
        CachedGitStatus cached = GIT_STATUS_CACHE.get(cwd);
        if (cached != null && now - cached.loadedAtMs() < GIT_CACHE_MS) {
            return cached.status();
        }
        Path git = ExecutableFinder.find("git").orElse(Path.of("git"));
        ProcessResult status = ProcessRunner.run(
            List.of(git.toString(), "-c", "core.quotePath=false", "--no-optional-locks",
                "status", "--porcelain=v1", "-uno", "--branch"),
            Path.of(cwd), GIT_TIMEOUT);
        if (!status.succeeded() || status.stdoutLines().isEmpty()) return null;

        String header = status.stdoutLines().getFirst();
        String branch = parseBranch(header);
        if (branch == null) return null;
        boolean dirty = status.stdoutLines().size() > 1;
        if (!dirty) {
            ProcessResult untracked = ProcessRunner.run(
                List.of(git.toString(), "-c", "core.quotePath=false", "--no-optional-locks",
                    "ls-files", "--others", "--exclude-standard", "--directory"),
                Path.of(cwd), GIT_TIMEOUT);
            dirty = untracked.succeeded() && !untracked.stdoutLines().isEmpty();
        }
        GitStatus result = new GitStatus(sanitize(branch), dirty);
        GIT_STATUS_CACHE.put(cwd, new CachedGitStatus(result, now));
        return result;
    }

    private static String parseBranch(String header) {
        if (header == null || !Strings.CS.startsWith(header, "## ")) return null;
        String ref = header.substring(3).trim();
        String unbornPrefix = "No commits yet on ";
        if (Strings.CS.startsWith(ref, unbornPrefix)) return ref.substring(unbornPrefix.length());
        String initialPrefix = "Initial commit on ";
        if (Strings.CS.startsWith(ref, initialPrefix)) return ref.substring(initialPrefix.length());
        if (Strings.CS.startsWith(ref, "HEAD ") || Strings.CS.equals("HEAD", ref)) return "HEAD";
        int upstream = ref.indexOf("...");
        if (upstream >= 0) ref = ref.substring(0, upstream);
        int state = ref.indexOf(' ');
        if (state >= 0) ref = ref.substring(0, state);
        return StringUtils.isBlank(ref) ? null : ref;
    }

    private record GitStatus(String branch, boolean dirty) {}
    private record CachedGitStatus(GitStatus status, long loadedAtMs) {}
}
