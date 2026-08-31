package com.claudecode.tools.skills;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.tools.bundled.BundledResourceCatalog;
import com.claudecode.tools.loop.LoopFeatureGate;
import com.claudecode.tools.loop.LoopPromptResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;




public final class BundledSkillPromptRenderer {

    private static final Pattern INTERVAL_ONLY = Pattern.compile("^\\d+[smhd]$");
    private static final Pattern EVERY_INTERVAL_ONLY = Pattern.compile(
        "^every\\s+(\\d+)\\s*(s|sec|secs|second|seconds|m|min|mins|minute|minutes|h|hr|hrs|hour|hours|d|day|days)\\s*$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern CLAUDE_API_DOC = Pattern.compile(
        "<doc path=\"([^\"]+)\">\\n.*?\\n</doc>", Pattern.DOTALL);
    private static final String INCLUDED_DOCUMENTATION =
        "---\n\n## Included Documentation\n\n";
    private static final String NO_LANGUAGE_NOTICE =
        "No project language was auto-detected. Ask the user which language they are using, then refer to the matching docs below.\n\n";
    private static final Map<String, List<String>> LANGUAGE_INDICATORS = Map.ofEntries(
        Map.entry("python", List.of(".py", "requirements.txt", "pyproject.toml", "setup.py", "Pipfile")),
        Map.entry("typescript", List.of(".ts", ".tsx", "tsconfig.json", "package.json")),
        Map.entry("java", List.of(".java", "pom.xml", "build.gradle")),
        Map.entry("go", List.of(".go", "go.mod")),
        Map.entry("ruby", List.of(".rb", "Gemfile")),
        Map.entry("csharp", List.of(".cs", ".csproj")),
        Map.entry("php", List.of(".php", "composer.json"))
    );
    private static final List<String> LANGUAGE_ORDER = List.of(
        "python", "typescript", "java", "go", "ruby", "csharp", "php");

    private BundledSkillPromptRenderer() {}

    public static boolean handles(String skillName) {
        return Strings.CS.equals("verify", skillName)
            || Strings.CS.equals("loop", skillName) || Strings.CS.equals("claude-api", skillName);
    }

    public static String render(String skillName, String content, String args) {
        return render(skillName, content, args, Path.of(System.getProperty("user.dir")));
    }

    public static String render(String skillName, String content, String args, Path workingDirectory) {
        if (Strings.CS.equals("loop", skillName)) {
            LoopFeatureGate gate = LoopFeatureGate.system();
            return renderLoopInternal(content, args, gate.dynamicEnabled(),
                gate.defaultPromptEnabled(), LoopPromptResolver.global());
        }
        if (Strings.CS.equals("claude-api", skillName)) {
            String rendered = renderClaudeApiForLanguage(content, detectLanguage(workingDirectory));
            int marker = rendered.lastIndexOf("$ARGUMENTS");
            if (marker < 0) return content;
            String value = args == null ? "" : args;
            return rendered.substring(0, marker) + value
                + rendered.substring(marker + "$ARGUMENTS".length());
        }
        if (StringUtils.isEmpty(args)) {
            return content;
        }
        return switch (skillName) {
            case "verify" -> content + "\n\n## User Request\n\n" + args;
            default -> content;
        };
    }

    static String detectLanguage(Path workingDirectory) {
        if (workingDirectory == null) return null;
        List<String> entries;
        try (var stream = Files.list(workingDirectory)) {
            entries = stream.map(path -> path.getFileName().toString()).toList();
        } catch (IOException | SecurityException _) {
            return null;
        }
        for (String language : LANGUAGE_ORDER) {
            for (String indicator : LANGUAGE_INDICATORS.get(language)) {
                boolean found = Strings.CS.startsWith(indicator, ".")
                    ? entries.stream().anyMatch(entry -> Strings.CS.endsWith(entry, indicator))
                    : entries.contains(indicator);
                if (found) return language;
            }
        }
        return null;
    }

    static String renderClaudeApiForLanguage(String content, String language) {
        if (StringUtils.isBlank(language)) return content;
        int guideStart = content.indexOf("## Reference Documentation");
        int includedStart = content.indexOf(INCLUDED_DOCUMENTATION, guideStart);
        if (guideStart < 0 || includedStart < 0) return content;

        String prefix = content.substring(0, includedStart)
            .replace("unknown/", language + "/")
            .replace(NO_LANGUAGE_NOTICE, "");
        int docsStart = includedStart + INCLUDED_DOCUMENTATION.length();
        String remainder = content.substring(docsStart);
        Matcher matcher = CLAUDE_API_DOC.matcher(remainder);
        List<String> selected = new ArrayList<>();
        int lastDocEnd = -1;
        while (matcher.find()) {
            String path = matcher.group(1);
            lastDocEnd = matcher.end();
            if (Strings.CS.startsWith(path, language + "/")
                    || Strings.CS.startsWith(path, "shared/")) {
                selected.add(matcher.group());
            }
        }
        if (lastDocEnd < 0 || selected.isEmpty()) return content;


        return prefix + INCLUDED_DOCUMENTATION
            + String.join("\n\n", selected)
            + remainder.substring(lastDocEnd);
    }


    public static String renderLoop(String args, boolean dynamicEnabled,
                                    boolean defaultPromptEnabled,
                                    LoopPromptResolver resolver) {
        return renderLoopInternal(readResource("loop.md"), args, dynamicEnabled,
            defaultPromptEnabled, resolver != null ? resolver : LoopPromptResolver.global());
    }

    private static String renderLoopInternal(String fixedContent, String args,
                                             boolean dynamicEnabled,
                                             boolean defaultPromptEnabled,
                                             LoopPromptResolver resolver) {
        String input = args == null ? "" : args.trim();
        Matcher trailingInterval = EVERY_INTERVAL_ONLY.matcher(input);
        boolean noInput = input.isEmpty();
        boolean intervalOnly = INTERVAL_ONLY.matcher(input).matches() || trailingInterval.matches();
        if ((noInput || intervalOnly) && defaultPromptEnabled) {
            String interval = trailingInterval.matches()
                ? normalizeInterval(trailingInterval)
                : (noInput ? "10m" : input);
            return autonomousDefaultPrompt(resolver, noInput && dynamicEnabled, interval);
        }
        if (dynamicEnabled) {
            if (noInput) return dynamicUsage();
            return readResource("loop-dynamic.md").stripTrailing().replace("$ARGUMENTS", input);
        }
        return fixedContent.replace("$ARGUMENTS", input);
    }

    private static String normalizeInterval(Matcher matcher) {
        String amount = matcher.group(1);
        String unit = matcher.group(2).toLowerCase(Locale.ROOT);
        if (Strings.CS.startsWith(unit, "s")) return amount + "s";
        if (Strings.CS.startsWith(unit, "h")) return amount + "h";
        if (Strings.CS.startsWith(unit, "d")) return amount + "d";
        return amount + "m";
    }

    private static String dynamicUsage() {
        return """
            Usage: /loop [interval] <prompt>

            Run a prompt or slash command on a recurring interval — or with no interval, let the model self-pace based on the task.

            Intervals: Ns, Nm, Nh, Nd (e.g. 5m, 30m, 2h, 1d). Minimum granularity is 1 minute.
            If no interval is specified, the model picks a delay between iterations based on what it's doing.

            Examples:
              /loop 5m /babysit-prs
              /loop 30m check the deploy
              /loop 1h /standup 1
              /loop check the deploy          (dynamic — model picks delays)
              /loop check the deploy every 20m""";
    }

    private static String autonomousDefaultPrompt(LoopPromptResolver resolver,
                                                   boolean dynamic, String interval) {
        LoopPromptResolver.LoopFile file = resolver.readLoopFile();
        String subject = file != null ? "the loop.md tasks" : "the autonomous check";
        String instructions = file != null ? file.content() : resolver.autonomousPreamble();
        String heading = file != null
            ? "## Loop tasks (from " + file.path() + ")"
            : "## Autonomous-loop instructions (for the immediate execution and every fire)";
        if (dynamic) {
            String sentinel = file != null
                ? LoopPromptResolver.LOOP_FILE_DYNAMIC_SENTINEL
                : LoopPromptResolver.AUTONOMOUS_DYNAMIC_SENTINEL;
            String title = file != null
                ? "# /loop — loop.md tasks with dynamic pacing\n\nThe user invoked `/loop` with no prompt and no interval and has a loop-tasks file at `"
                    + file.path() + "`. Run those tasks now, then self-pace the next iteration via ScheduleWakeup — no cron."
                : "# /loop — autonomous default with dynamic pacing\n\nThe user invoked `/loop` with no prompt and no interval. Run the autonomous check now, then self-pace the next iteration via ScheduleWakeup — no cron.";
            String confirmation = file != null
                ? "that you're running tasks from `" + file.path()
                    + "` in dynamic-pacing mode, that you ran the first tick now"
                : "that this is the autonomous default in dynamic-pacing mode, that you ran the check now";
            return title + "\n\n## Action\n\n"
                + "1. **Run " + subject + " now**, following the instructions inlined below.\n"
                + "2. **If the next tick is gated on an event** (CI finishing, a PR comment, a log line) and no Monitor is already running for it: arm one now with `persistent: true`. Its events wake this loop immediately — you do not wait for the ScheduleWakeup deadline. Arm once; on later ticks call TaskList first and skip if a monitor is already running.\n"
                + "3. **Briefly confirm**: " + confirmation + ", whether a Monitor is the primary wake signal, and what fallback delay you're about to pick. Write this as text *before* calling ScheduleWakeup — the turn ends as soon as that tool returns.\n"
                + "4. **Then, as the last action of this turn, call ScheduleWakeup** with:\n"
                + "   - `delaySeconds`: with a Monitor armed this is the fallback heartbeat (lean 1200–1800s). Without one, pick based on what you observed this turn — quiet branch? wait longer. Lots in flight? wait shorter. Read the tool's own description for cache-aware delay guidance.\n"
                + "   - `reason`: one short sentence on why you picked that delay.\n"
                + "   - `prompt`: the literal string `" + sentinel + "` — the dynamic-mode sentinel expands at fire time to the full instructions (first fire / first fire post-compact / loop.md edited) or a dynamic-pacing-specific short reminder (subsequent fires). Do not pass the full instructions; that is handled automatically.\n"
                + "5. **If woken by a `<task-notification>`** rather than this prompt: handle the event, then call ScheduleWakeup again with `" + sentinel + "` and the same 1200–1800s `delaySeconds` — the Monitor remains the wake signal; this only resets the safety net.\n"
                + "6. **To stop the loop**, omit the ScheduleWakeup call and TaskStop any Monitor you armed (use TaskList to find the task ID if it is no longer in context).\n\n"
                + heading + "\n\n" + instructions;
        }
        String sentinel = file != null
            ? LoopPromptResolver.LOOP_FILE_SENTINEL
            : LoopPromptResolver.AUTONOMOUS_SENTINEL;
        String title = file != null
            ? "# /loop — schedule loop.md tasks\n\nThe user invoked `/loop` with no prompt (input was empty or just the interval `"
                + interval + "`) and has a loop-tasks file at `" + file.path()
                + "`. Schedule a recurring cron that runs those tasks each tick, then run the first tick immediately."
            : "# /loop — schedule the autonomous default\n\nThe user invoked `/loop` with no prompt (input was empty or just the interval `"
                + interval + "`). Schedule the autonomous-loop default and then run the first autonomous check immediately.";
        String expansion = file != null
            ? "it expands at fire time to the full loop.md contents on first delivery (and whenever loop.md has been edited since last fire), and to a short reminder on subsequent unchanged fires. The long instructions stay in the cached message-prefix."
            : "it expands at fire time to the full autonomous-loop instructions on first delivery, and to a short reminder on subsequent fires (the long instructions stay in the cached message-prefix).";
        String confirmation = file != null
            ? "what's scheduled, the cron expression, the human-readable cadence, that it's running tasks from `"
                + file.path() + "`, that recurring tasks auto-expire after 7 days, and that the user can cancel sooner with CronDelete (include the job ID)."
            : "what's scheduled, the cron expression, the human-readable cadence, that recurring tasks auto-expire after 7 days, and that they can cancel sooner with CronDelete (include the job ID). Mention this is the autonomous default and that the autonomous-loop instructions are baked in.";
        return title + "\n\n## Action\n\n"
            + "1. Convert `" + interval + "` to a 5-field cron expression. Supported suffixes: `s` → ceil to nearest minute, `m` (minutes), `h` (hours), `d` (days). Examples: `5m` → `*/5 * * * *`, `1h` → `0 * * * *`, `1d` → `0 0 * * *`. If the interval doesn't cleanly divide its unit, round to the nearest clean interval and tell the user what you rounded to.\n"
            + "2. Call CronCreate with:\n"
            + "   - `cron`: the expression from step 1\n"
            + "   - `prompt`: the literal string `" + sentinel + "` — " + expansion + "\n"
            + "   - `recurring`: `true`\n"
            + "3. Briefly confirm: " + confirmation + "\n"
            + "4. **Then immediately run " + subject + " now**, following the instructions inlined below. Don't wait for the first cron fire.\n\n"
            + heading + "\n\n" + instructions;
    }

    private static String readResource(String name) {
        return BundledResourceCatalog.current().readText("skills/" + name);
    }
}
