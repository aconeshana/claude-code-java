package com.claudecode.ui.lanterna.slash;

import com.claudecode.commands.Command;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandRegistry;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.CommandOutputChannel;
import com.claudecode.commands.impl.config.ConfigCommand;
import com.claudecode.commands.impl.config.ModelCommand;
import com.claudecode.commands.parsing.SlashCommandParser;
import com.claudecode.commands.prompt.PromptInvocation;
import com.claudecode.core.message.PastedContent;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.prompt.ArgumentSubstitutor;
import com.claudecode.core.queue.QueuedCommand;
import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.PermissionRule;
import com.claudecode.permissions.RuleSource;
import com.claudecode.tools.skills.BundledSkillPromptRenderer;
import com.claudecode.tools.skills.Skill;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Slash-command dispatch and user-defined skill loading.
 */
public final class SlashCommandDispatcher {

    private final SlashHost       host;
    private final ReplRefs        refs;
    private final CommandRegistry commandRegistry;
    private final CommandContext  commandContext;
    private final Supplier<List<Skill>> skillsSupplier;
    private final Consumer<String> skillHookRegistrar;

    public SlashCommandDispatcher(SlashHost host,
                                  ReplRefs refs,
                                  CommandRegistry commandRegistry,
                                  CommandContext commandContext) {
        this(host, refs, commandRegistry, commandContext, List::of, _ -> {});
    }

    public SlashCommandDispatcher(SlashHost host,
                                  ReplRefs refs,
                                  CommandRegistry commandRegistry,
                                  CommandContext commandContext,
                                  Supplier<List<Skill>> skillsSupplier) {
        this(host, refs, commandRegistry, commandContext, skillsSupplier, _ -> {});
    }

    public SlashCommandDispatcher(SlashHost host,
                                  ReplRefs refs,
                                  CommandRegistry commandRegistry,
                                  CommandContext commandContext,
                                  Supplier<List<Skill>> skillsSupplier,
                                  Consumer<String> skillHookRegistrar) {
        this.host            = host;
        this.refs            = refs;
        this.commandRegistry = commandRegistry;
        this.commandContext  = commandContext;
        this.skillsSupplier  = skillsSupplier != null ? skillsSupplier : List::of;
        this.skillHookRegistrar = skillHookRegistrar != null ? skillHookRegistrar : _ -> {};
    }

    /**
     * Dispatch a slash-prefixed input line.
     */
    public void dispatch(String input) {
        String[] parts = input.substring(1).split("\\s+", 2);
        String cmdName = parts[0].toLowerCase(Locale.ROOT);
        String cmdArgs = parts.length > 1 ? parts[1].trim() : "";

        // looksLikeCommand: if cmdName contains path-like chars, treat the
        // whole input as a normal user query (not a slash command).
        if (!SlashCommandParser.looksLikeCommand(cmdName)) {
            host.handleQuery(input);
            return;
        }

        if ((Strings.CS.equals(cmdName, "resume") || Strings.CS.equals(cmdName, "continue"))
                && cmdArgs.isEmpty()) {
            host.showSessionPicker();
            return;
        }

        if (Strings.CS.equals("fast", cmdName)) {
            host.toggleFastMode();
            return;
        }

        Command resolved = commandRegistry.find(cmdName).orElse(null);
        if (shouldQueueBeforeDispatch(resolved, host.isTurnInFlight())) {
            var pasted = refs.inputPanel().getPastedContents();
            host.renderAndQueue(new QueuedCommand(input, pasted), input);
            refs.inputPanel().setQueuedHint(true);
            return;
        }
        if (openNativeDialogDirectly(resolved, cmdArgs)) return;

        // Every command dispatch leaves Lanterna's GUI thread. Even commands
        // considered "fast" can hit settings, session, skill, hook, or plugin



        if (resolved != null && resolved.isLongRunning()) {
            host.prepareLongRunningCommandTranscript();
            host.longRunningCommandStarted();
        }
        boolean longRunning = resolved != null && resolved.isLongRunning();
        Thread.ofVirtual().name("slash-cmd-" + cmdName).start(() -> {
            CommandResult result;
            try {
                result = commandRegistry.dispatch(input, commandContext);
            } catch (RuntimeException failure) {
                result = CommandResult.of("Error: " + (failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage()));
            }
            CommandResult completed = result;
            SkillDispatch skillDispatch = prepareSkillDispatch(completed, cmdName, cmdArgs);
            refs.gui().getGUIThread().invokeLater(() -> {
                try {
                    handleDispatchResult(completed, input, cmdName, cmdArgs, skillDispatch);
                } finally {
                    if (longRunning) host.longRunningCommandFinished();
                }
            });
        });
    }

    /**
     * Executes the native manual compact command on behalf of a semantic
     * Session Host endpoint while preserving the same terminal transcript,
     * progress UI, and long-running input queue used by a locally typed
     * {@code /compact}.
     */
    public CompletionStage<CommandResult> dispatchSessionHostCompact(String args) {
        CompletableFuture<CommandResult> resultFuture = new CompletableFuture<>();
        refs.gui().getGUIThread().invokeLater(() -> {
            if (host.isTurnInFlight() || host.isLongRunningCommandInFlight()) {
                resultFuture.completeExceptionally(
                    new IllegalStateException("session is busy"));
                return;
            }
            Command compact = commandRegistry.find("compact").orElse(null);
            if (compact == null || !compact.isLongRunning()) {
                resultFuture.completeExceptionally(
                    new UnsupportedOperationException("compaction is not available"));
                return;
            }
            String normalizedArgs = args == null ? "" : args.trim();
            String input = normalizedArgs.isEmpty() ? "/compact" : "/compact " + normalizedArgs;
            host.prepareLongRunningCommandTranscript();
            host.longRunningCommandStarted();
            Thread.ofVirtual().name("session-host-compact").start(() -> {
                CommandResult completed;
                try {
                    completed = commandRegistry.dispatch(input, commandContext);
                } catch (RuntimeException failure) {
                    completed = CommandResult.localError("Error: " +
                        (failure.getMessage() == null
                            ? failure.getClass().getSimpleName() : failure.getMessage()));
                }
                CommandResult rendered = completed;
                refs.gui().getGUIThread().invokeLater(() -> {
                    try {
                        handleDispatchResult(rendered, input, "compact", normalizedArgs, null);
                        if (rendered.outputChannel() == CommandOutputChannel.STDERR) {
                            resultFuture.completeExceptionally(
                                new IllegalStateException(rendered.output()));
                        } else {
                            resultFuture.complete(rendered);
                        }
                    } catch (RuntimeException failure) {
                        resultFuture.completeExceptionally(failure);
                    } finally {
                        host.longRunningCommandFinished();
                    }
                });
            });
        });
        return resultFuture;
    }


    private boolean openNativeDialogDirectly(Command resolved, String cmdArgs) {
        if (resolved instanceof ModelCommand
                && cmdArgs.isEmpty()
                && commandContext.presentation().modelDialogLauncher() != null) {
            commandContext.presentation().modelDialogLauncher().run();
            return true;
        }
        if (resolved instanceof ConfigCommand
                && commandContext.presentation().configDialogLauncher() != null) {
            commandContext.presentation().configDialogLauncher().run();
            return true;
        }
        return false;
    }

    /**
     * Tail of {@link #dispatch(String)} — everything that happens once a
     * {@link CommandResult} is in hand. Extracted so the long-running path
     * above can run it inside {@code invokeLater} on the GUI thread after
     * finishing {@link CommandRegistry#dispatch} and any skill file expansion
     * on a background thread.
     */
    private void handleDispatchResult(CommandResult result, String input, String cmdName, String cmdArgs,
                                      SkillDispatch skillDispatch) {
        synchronizePermissionMode();
        if (result.shouldQuery()) {


            // processSlashCommand's two-message shape (command metadata
            // message + isMeta prompt message). Same display/content split
            // the skill path below uses; a prior version routed the prompt
            // through handleQuery, echoing the entire prompt text into the
            // message area as if the user had typed it.
            var pasted = refs.inputPanel().getPastedContents();
            if (host.isTurnInFlight()) {
                host.renderAndQueue(new QueuedCommand(input, pasted), input);
                refs.inputPanel().setQueuedHint(true);
            } else {
                submitPromptResult(host, input, result, pasted);
            }
            return;
        }
        // /rename: update the prompt-bar banner with the new session name.
        if (result.newSessionName() != null) {
            refs.gui().getGUIThread().invokeLater(
                () -> refs.inputPanel().setAgentName(result.newSessionName()));
        }

        // Silent results, including /btw answers, do not enter the main transcript.
        if (result.silent()) {
            if (result.shouldExit()) {
                host.requestShutdown(safeReason(result));
            }
            return;
        }
        // Unknown command — skill discovery and file expansion were prepared
        // on the command virtual thread before this GUI callback.
        if (skillDispatch != null) {
            var pasted = refs.inputPanel().getPastedContents();
            applySkillRules(skillDispatch.meta());
            applySkillHooks(skillDispatch.meta());
            if (host.isTurnInFlight()) {
                host.renderAndQueue(new QueuedCommand(input, pasted), skillDispatch.displayText());
                refs.inputPanel().setQueuedHint(true);
            } else {
                host.executeQuery(skillDispatch.displayText(), skillDispatch.query(), pasted, true);
            }
            return;
        }
        for (SDKMessage message : LocalCommandCompletionAdapter.toMessages(
                cmdName, cmdArgs, result)) {
            refs.dispatcher().dispatch(message, refs.messagePanel());
            if (result.persist()) {
                refs.messageHistory().record(message);
            }
        }
        if (result.shouldExit()) {
            host.requestShutdown(safeReason(result));
        }
    }

    /** Local UI commands mutate application state before completing. */
    private void synchronizePermissionMode() {
        if (refs.permissionGate() == null || refs.inputPanel() == null
                || refs.permissionGate().currentMode() == null) return;
        String mode = refs.permissionGate().currentMode().external();
        if (Strings.CS.equals(mode, refs.inputPanel().getPermissionMode())) return;
        refs.inputPanel().setPermissionMode(mode);
        host.permissionModeSynchronized(mode);
    }

    /**
     * Preserve the full prompt envelope at the UI port. The compatibility
     * fallback handles legacy callers that still construct a query result
     * without {@link CommandResult#promptInvocation}.
     */
    static void submitPromptResult(SlashHost host, String displayText,
                                   CommandResult result,
                                   Map<Integer, PastedContent> pasted) {
        PromptInvocation invocation = result.promptInvocation() != null
            ? result.promptInvocation()
            : PromptInvocation.text(result.output());
        host.executePrompt(displayText, invocation, pasted);
    }

    /**
     * Queue ordinary and dynamically-resolved slash input before dispatch while a model turn is active.
     */
    static boolean shouldQueueBeforeDispatch(Command command, boolean turnInFlight) {
        return turnInFlight && (command == null || !command.isImmediate());
    }


    private static String safeReason(CommandResult result) {
        String r = result.exitReason();
        return StringUtils.isBlank(r) ? "other" : r;
    }

    /**
     * Resolve an unknown slash command as a skill entirely off the GUI
     * thread. This includes supplier discovery, SKILL.md/frontmatter reads,
     * argument substitution, and @file attachment expansion.
     */
    private SkillDispatch prepareSkillDispatch(CommandResult result, String skillName, String args) {
        if (result.output() == null
                || !Strings.CS.startsWith(result.output(), "Unknown command:")) {
            return null;
        }
        Skill skill = findSkill(skillName);
        Path skillMd = skill != null ? skill.sourceFile() : null;
        SkillMeta meta = skillMd != null && Files.isRegularFile(skillMd) ? parseSkillMeta(skillMd) : null;
        String skillQuery = loadSkillAsQuery(skill, args, meta);
        if (skillQuery == null) return null;
        String displayText = "/" + skillName + (args.isEmpty() ? "" : " " + args);
        return new SkillDispatch(displayText, skillQuery, meta);
    }

    private record SkillDispatch(String displayText, String query, SkillMeta meta) {}

    // ── Skill loading ─────────────────────────────────────────────────────

    /**
     * Load discovered skill content for
     * query injection. Accepts a pre-parsed {@link SkillMeta} to avoid
     * re-parsing frontmatter; pass {@code null} to have it parsed internally.
     */
    private String loadSkillAsQuery(Skill skill, String args, SkillMeta meta) {
        if (skill == null) return null;
        Path skillMd = skill.sourceFile();
        if (skillMd == null && skill.source() != Skill.SkillSource.BUNDLED) return null;
        try {
            String rawContent = skillMd != null && Files.isRegularFile(skillMd)
                ? Files.readString(skillMd) : skill.content();
            if (meta == null && skillMd != null) meta = parseSkillMeta(skillMd);
            List<String> argumentNames = meta != null ? meta.argumentNames() : List.of();
            // Strip YAML frontmatter (--- ... ---)
            String content = rawContent;
            if (Strings.CS.startsWith(content, "---")) {
                int end = content.indexOf("---", 3);
                if (end >= 0) content = content.substring(end + 3).stripLeading();
            }
            String expanded;
            if (skill.source() == Skill.SkillSource.BUNDLED
                    && BundledSkillPromptRenderer.handles(skill.name())) {
                expanded = BundledSkillPromptRenderer.render(
                    skill.name(), content, args.isEmpty() ? null : args);
            } else {
                expanded = ArgumentSubstitutor.substitute(
                    content, args.isEmpty() ? null : args, argumentNames, true);
            }
            return resolveAtMentions(expanded, skillMd != null ? skillMd.getParent() : null);
        } catch (Exception _) {
            return null;
        }
    }

    private Skill findSkill(String name) {
        try {
            return skillsSupplier.get().stream()
                .filter(skill -> skill.name().equalsIgnoreCase(name))
                .findFirst().orElse(null);
        } catch (Exception _) {
            return null;
        }
    }

    /**
     * Replace {@code @filepath} tokens in skill content with file contents.
     */
    private String resolveAtMentions(String content, Path skillRoot) {
        if (content == null || !Strings.CS.contains(content, "@")) return content;
        Pattern p = Pattern.compile("(?<![\\w.])@([\\w./~-]+)");
        Matcher m = p.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String ref = m.group(1);
            if (Strings.CS.contains(ref, ":")) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                continue;
            }
            Path resolved = resolveAtPath(ref, skillRoot);
            if (Files.isRegularFile(resolved)) {
                try {
                    String fileContent = Files.readString(resolved);
                    String replacement = "\n<file path=\"" + ref + "\">\n" + fileContent + "\n</file>\n";
                    m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                } catch (Exception _) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                }
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private Path resolveAtPath(String ref, Path skillRoot) {
        String home = System.getProperty("user.home", "");
        String expanded = Strings.CS.startsWith(ref, "~/") ? home + ref.substring(1) : ref;
        if (skillRoot != null) {
            Path candidate = skillRoot.resolve(expanded);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        Path cwd = Path.of(System.getProperty("user.dir"));
        return cwd.resolve(expanded);
    }

    /**
     * Apply allowed-tools from skill frontmatter as {@link RuleSource#SKILL} rules on the current
     * {@link PermissionGate}.
     */
    private void applySkillRules(SkillMeta meta) {
        if (meta == null || meta.allowedTools().isEmpty()) return;
        PermissionGate gate = refs.permissionGate();
        if (gate == null) return;
        List<PermissionRule> rules = new ArrayList<>();
        for (String toolSpec : meta.allowedTools()) {
            int paren = toolSpec.indexOf('(');
            if (paren > 0 && Strings.CS.endsWith(toolSpec, ")")) {
                String toolName = toolSpec.substring(0, paren).trim();
                String pattern  = toolSpec.substring(paren + 1, toolSpec.length() - 1).trim();
                rules.add(PermissionRule.withPattern(
                    toolName, PermissionBehavior.ALLOW,
                    RuleSource.SKILL, pattern));
            } else {
                rules.add(PermissionRule.of(
                    toolSpec.trim(),
                    PermissionBehavior.ALLOW,
                    RuleSource.SKILL));
            }
        }
        gate.addRules(rules);
    }

    /**
     * Submit skill hook frontmatter to the application-owned registrar.
     */
    private void applySkillHooks(SkillMeta meta) {
        if (meta == null || meta.hooksYaml() == null || StringUtils.isBlank(meta.hooksYaml())) return;
        skillHookRegistrar.accept(meta.hooksYaml());
    }

    // ── Frontmatter parsing ───────────────────────────────────────────────

    /**
     * Parse name + description + argumentNames + argumentHint + allowedTools
     * + hooks from a SKILL.md YAML frontmatter. Public so the REPL's skill
     * autocomplete builder can reuse it without duplicating the parser.
     */
    public SkillMeta parseSkillMeta(Path skillMd) {
        try {
            String content = Files.readString(skillMd);
            String name = null, description = null, argumentHintRaw = null;
            Object argumentsRaw = null;
            List<String> allowedToolsList = new ArrayList<>();
            StringBuilder hooksYaml = new StringBuilder();
            boolean inFrontmatter = false, inDesc = false, inArgList = false;
            boolean inAllowedTools = false, inHooksBlock = false;
            for (String line : content.split("\n")) {
                if (Strings.CS.equals(line, "---")) {
                    if (!inFrontmatter) { inFrontmatter = true; continue; }
                    else break;
                }
                if (!inFrontmatter) continue;
                if (inHooksBlock) {
                    if (line.isEmpty() || Strings.CS.startsWith(line, " ") || Strings.CS.startsWith(line, "\t")) {
                        hooksYaml.append(line).append("\n");
                        continue;
                    }
                    inHooksBlock = false;
                }
                if (Strings.CS.startsWith(line, "name:")) {
                    name = line.substring(5).trim().replaceAll("^[\"']|[\"']$", "");
                    inDesc = false; inArgList = false; inAllowedTools = false;
                } else if (Strings.CS.startsWith(line, "description:")) {
                    String val = line.substring(12).trim();
                    if (Strings.CS.equals(val, "|") || Strings.CS.equals(val, ">") || val.isEmpty()) {
                        inDesc = true;
                    } else {
                        description = val.replaceAll("^[\"']|[\"']$", "");
                        inDesc = false;
                    }
                    inArgList = false; inAllowedTools = false;
                } else if (Strings.CS.startsWith(line, "arguments:")) {
                    String val = line.substring(10).trim().replaceAll("^[\"']|[\"']$", "");
                    inDesc = false; inAllowedTools = false;
                    if (Strings.CS.startsWith(val, "[") && Strings.CS.endsWith(val, "]")) {
                        String inner = val.substring(1, val.length() - 1);
                        List<String> parsed = new ArrayList<>();
                        for (String item : inner.split(",")) {
                            String s = item.trim().replaceAll("^[\"']|[\"']$", "");
                            if (!StringUtils.isBlank(s)) parsed.add(s);
                        }
                        argumentsRaw = parsed;
                        inArgList = false;
                    } else if (val.isEmpty()) {
                        argumentsRaw = new ArrayList<String>();
                        inArgList = true;
                    } else {
                        argumentsRaw = val;
                        inArgList = false;
                    }
                } else if (Strings.CS.startsWith(line, "argument-hint:")) {
                    argumentHintRaw = line.substring(14).trim().replaceAll("^[\"']|[\"']$", "");
                    inDesc = false; inArgList = false; inAllowedTools = false;
                } else if (Strings.CS.startsWith(line, "allowed-tools:")) {
                    String val = line.substring(14).trim();
                    inDesc = false; inArgList = false; inAllowedTools = false;
                    if (Strings.CS.startsWith(val, "[") && Strings.CS.endsWith(val, "]")) {
                        String inner = val.substring(1, val.length() - 1);
                        for (String item : inner.split(",")) {
                            String s = item.trim().replaceAll("^[\"']|[\"']$", "");
                            if (!StringUtils.isBlank(s)) allowedToolsList.add(s);
                        }
                    } else if (val.isEmpty()) {
                        inAllowedTools = true;
                    } else {
                        String s = val.replaceAll("^[\"']|[\"']$", "");
                        if (!StringUtils.isBlank(s)) allowedToolsList.add(s);
                    }
                } else if (Strings.CS.startsWith(line, "hooks:")) {
                    inDesc = false; inArgList = false; inAllowedTools = false;
                    inHooksBlock = true;
                    hooksYaml.setLength(0);
                } else if (inDesc && (Strings.CS.startsWith(line, "  ") || Strings.CS.startsWith(line, "\t"))) {
                    String dl = line.trim();
                    if (!dl.isEmpty()) { description = dl; inDesc = false; }
                } else if (inArgList && Strings.CS.startsWith(line, "  - ")) {
                    String item = line.substring(4).trim().replaceAll("^[\"']|[\"']$", "");
                    if (!StringUtils.isBlank(item) && argumentsRaw instanceof List<?> lst) {
                        @SuppressWarnings("unchecked")
                        List<String> list = (List<String>) lst;
                        list.add(item);
                    }
                } else if (inAllowedTools && Strings.CS.startsWith(line, "  - ")) {
                    String item = line.substring(4).trim().replaceAll("^[\"']|[\"']$", "");
                    if (!StringUtils.isBlank(item)) allowedToolsList.add(item);
                } else if (inDesc) {
                    inDesc = false;
                } else if ((inArgList || inAllowedTools) && !Strings.CS.startsWith(line, " ") && !Strings.CS.startsWith(line, "\t")) {
                    inArgList = false; inAllowedTools = false;
                }
            }
            if (name == null) return null;
            List<String> argNames = ArgumentSubstitutor.parseArgumentNames(argumentsRaw);
            String argHint = (StringUtils.isNotBlank(argumentHintRaw)) ? argumentHintRaw : null;
            return new SkillMeta(name, description != null ? description : "", argNames, argHint,
                                 List.copyOf(allowedToolsList), hooksYaml.toString());
        } catch (Exception _) { return null; }
    }


    public record SkillMeta(String name, String description,
                            List<String> argumentNames, String argumentHint,
                            List<String> allowedTools,
                            String hooksYaml) {}
}
