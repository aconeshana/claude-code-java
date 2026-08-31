package com.claudecode.ui.lanterna.suggest;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.claudecode.commands.Command;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandRegistry;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.slash.SlashCommandDispatcher;
import com.claudecode.tools.skills.Skill;
import com.claudecode.core.prompt.ArgumentSubstitutor;
import com.claudecode.ui.lanterna.features.settings.UiSettings;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.time.Duration;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drives the input typeahead: on every keystroke it decides whether to show slash-command
 * suggestions, {@code @}-file / directory completions, or a progressive argument hint, and pushes
 * the result into {@link InputPanel}.
 */
public final class SuggestionController implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SuggestionController.class);
    private static final long METADATA_CACHE_TTL_MS = 60_000;


    private static final Pattern AT_TOKEN_RE = Pattern.compile(
        "(?:^|(?<=\\s))@((?:[\\w\\p{L}\\p{N}\\p{M}_\\-./\\\\()\\[\\]~:]|\"[^\"]*\"?)*)$");

    private final WindowBasedTextGUI gui;
    private final InputPanel inputPanel;
    private final CommandRegistry commandRegistry;
    private final FileSuggestionService fileSuggestionService;
    private final DirectorySuggestionService directorySuggestionService;
    private final Supplier<List<Skill>> skillsSupplier;
    private final Object commandIndexLock = new Object();
    private final AtomicBoolean metadataLoading = new AtomicBoolean();
    private final AtomicLong metadataGeneration = new AtomicLong();
    private final LatestTaskRunner pathSuggestions =
        new LatestTaskRunner("path-suggest", Duration.ofMillis(10));
    private final AtomicLong pathSuggestionGeneration = new AtomicLong();
    private volatile SuggestionMetadata suggestionMetadata = SuggestionMetadata.EMPTY;
    private volatile long metadataLoadedAtMs;
    /** Full-universe name width; recomputed only when metadata refreshes. */
    private volatile CommandIndex commandIndex = CommandIndex.EMPTY;
    /** Guarded by {@link #commandIndexLock}; rejects out-of-order revision callbacks. */
    private long indexedRegistryRevision = -1;
    private final AutoCloseable registrySubscription;
    /**
     * Terminal width used to lay out the suggestion dropdown. Snapshot at
     * construction — matches the pre-extraction behaviour where {@code termWRef}
     * was captured once in {@code buildLayout} and never updated on resize.
     */
    private final int termWidth;

    /** Last active suggestion kind so we can clear stale results: "command" | "file" | "none". */
    private String lastSuggestionKind = "none";

    private record SuggestionMetadata(List<Command> availableCommands,
                                      List<SkillEntry> skills,
                                      Map<String, Double> usageScores) {
        private static final SuggestionMetadata EMPTY =
            new SuggestionMetadata(List.of(), List.of(), Map.of());

        private SuggestionMetadata {
            availableCommands = List.copyOf(
                availableCommands == null ? List.of() : availableCommands);
            skills = List.copyOf(skills == null ? List.of() : skills);
            usageScores = Map.copyOf(usageScores == null ? Map.of() : usageScores);
        }
    }

    private record CommandSearchEntry(String name, String nameLower, String description,
                                      List<String> aliases, boolean skill) {}

    private record CommandIndex(
            Map<String, Command> commandByName,
            List<CommandSearchEntry> searchEntries,
            int columnWidth,
            List<SuggestionPanel.Suggestion> emptySuggestions) {
        private static final CommandIndex EMPTY =
            new CommandIndex(Map.of(), List.of(), -1, List.of());

        private CommandIndex {
            commandByName = Map.copyOf(commandByName);
            searchEntries = List.copyOf(searchEntries);
            emptySuggestions = List.copyOf(emptySuggestions);
        }
    }

    public SuggestionController(WindowBasedTextGUI gui,
                                InputPanel inputPanel,
                                CommandRegistry commandRegistry,
                                SlashCommandDispatcher slashDispatcher,
                                FileSuggestionService fileSuggestionService,
                                DirectorySuggestionService directorySuggestionService,
                                int termWidth) {
        this(gui, inputPanel, commandRegistry, slashDispatcher, fileSuggestionService,
            directorySuggestionService, termWidth, List::of);
    }

    public SuggestionController(WindowBasedTextGUI gui,
                                InputPanel inputPanel,
                                CommandRegistry commandRegistry,
                                SlashCommandDispatcher slashDispatcher,
                                FileSuggestionService fileSuggestionService,
                                DirectorySuggestionService directorySuggestionService,
                                int termWidth,
                                Supplier<List<Skill>> skillsSupplier) {
        this.gui = gui;
        this.inputPanel = inputPanel;
        this.commandRegistry = commandRegistry;
        this.fileSuggestionService = fileSuggestionService;
        this.directorySuggestionService = directorySuggestionService;
        this.termWidth = termWidth;
        this.skillsSupplier = skillsSupplier != null ? skillsSupplier : List::of;
        CommandRegistry.Snapshot initialRegistry = commandRegistry.snapshot();
        applyRegistrySnapshot(initialRegistry);
        this.registrySubscription = commandRegistry.subscribe(this::registryChanged);
        // Warm every disk/settings-dependent input off the GUI thread during
        // layout construction; the first '/' still has an in-memory fallback.
        if (gui != null && inputPanel != null) {
            ensureMetadataLoading();
            if (fileSuggestionService != null) fileSuggestionService.warmUp();
        }
    }

    /**
     * InputPanel query-change callback.
     */
    public void onQueryChange(String text, int cursorOffset) {
        String query = text == null ? "" : text;
        // While navigating history / reverse-i-searching, keep the dropdown closed so a recalled


        if (inputPanel.isSuppressingSuggestions()) {
            inputPanel.hideSuggestions();
            lastSuggestionKind = "none";
            return;
        }
        boolean bashMode = inputPanel.isBashMode();
        // Plain prompt text cannot match either Java suggestion surface: slash
        // commands require column-zero '/', and file completion requires an
        // '@' token. When the controller is already idle, return before
        // substring allocation, regex construction, cancellation atomics, or
        // redundant component mutation. The original useTypeahead effect also
        // leaves an already-empty suggestion state unchanged for this case.
        if (Strings.CS.equals("none", lastSuggestionKind)
                && !bashMode
                && !query.isEmpty()
                && query.charAt(0) != '/'
                && query.indexOf('@') < 0) {
            return;
        }
// Work on the text before the cursor.
        String beforeCursor = cursorOffset <= query.length()
            ? query.substring(0, cursorOffset)
            : query;


        // take the token after the final space and complete it automatically
        // when it is path-like or contains a slash. Bash input is stored
        // without the leading mode marker, so the mode must come from InputPanel.
        if (bashMode) {
            String token = beforeCursor.substring(beforeCursor.lastIndexOf(' ') + 1);
            if (!token.isEmpty() && (isPathLike(token) || Strings.CS.contains(token, "/"))) {
                lastSuggestionKind = "bash-path";
                scheduleBashPathSuggestions(token);
                return;
            }
            if (Strings.CS.equals("bash-path", lastSuggestionKind)) {
                cancelPathSuggestions();
                lastSuggestionKind = "none";
                inputPanel.hideSuggestions();
                inputPanel.setArgumentHint(null);
            }
            return;
        }
        if (Strings.CS.equals("bash-path", lastSuggestionKind)) {
            cancelPathSuggestions();
            lastSuggestionKind = "none";
            inputPanel.hideSuggestions();
            inputPanel.setArgumentHint(null);
        }

        if (Strings.CS.startsWith(beforeCursor, "/")) {
            if (Strings.CS.equals("file", lastSuggestionKind)) cancelPathSuggestions();
            // ── Slash commands ────────────────────────────────────────
            ensureMetadataLoading();
            lastSuggestionKind = "command";
            String afterSlash = beforeCursor.substring(1);
            int spaceIdx = afterSlash.indexOf(' ');
            if (spaceIdx >= 0) {
                // User typed a space after the command name.

                //   Priority 1: static argumentHint (only on first trailing space, backwards compat)
                //   Priority 2: progressive argNames hint (whenever input ends with space)
                String cmdName = afterSlash.substring(0, spaceIdx).toLowerCase(Locale.ROOT);
                boolean hasExactlyOneTrailingSpace =
                    Strings.CS.endsWith(beforeCursor, " ")
                    && beforeCursor.indexOf(' ') == beforeCursor.length() - 1;
                boolean endsWithSpace = Strings.CS.endsWith(beforeCursor, " ");

                // Find matching built-in command
                Command builtinMatch = commandIndex.commandByName().get(cmdName);
                // Find matching skill (lazy load)
                SkillEntry skillMatch = builtinMatch == null
                    ? suggestionMetadata.skills().stream()
                        .filter(s -> s.name().equalsIgnoreCase(cmdName))
                        .findFirst().orElse(null)
                    : null;
                boolean exactMatch = builtinMatch != null || skillMatch != null;

                if (exactMatch || hasExactlyOneTrailingSpace) {
                    String hint = null;
                    // Priority 1: static argumentHint on first trailing space
                    if (hasExactlyOneTrailingSpace) {
                        hint = builtinMatch != null ? builtinMatch.argumentHint()
                            : (skillMatch != null ? skillMatch.argumentHint() : null);
                    }
                    // Priority 2: progressive hint from argNames whenever trailing space
                    if (hint == null && endsWithSpace) {
                        List<String> argNames = builtinMatch != null ? builtinMatch.argumentNames()
                            : (skillMatch != null ? skillMatch.argumentNames() : List.of());
                        if (!argNames.isEmpty()) {
                            String argsText = afterSlash.substring(spaceIdx + 1);
                            List<String> typedArgs = ArgumentSubstitutor.parseArguments(argsText);
                            hint = ArgumentSubstitutor.generateProgressiveArgumentHint(argNames, typedArgs)
                                .orElse(null);
                        }
                    }
                    final String finalHint = hint;
                    inputPanel.hideSuggestions();
                    inputPanel.setArgumentHint(finalHint);
                } else {
                    // No known command — fall through to show filtered suggestions
                    String filter = afterSlash.toLowerCase(Locale.ROOT);
                    List<SuggestionPanel.Suggestion> items = buildCommandSuggestions(filter);
                    inputPanel.setArgumentHint(null);
                    inputPanel.showSuggestions(items, termWidth, computeCommandColumnWidth());
                }
            } else {
                String filter = afterSlash.toLowerCase(Locale.ROOT);
                List<SuggestionPanel.Suggestion> items = buildCommandSuggestions(filter);
                inputPanel.setArgumentHint(null);
                inputPanel.showSuggestions(items, termWidth, computeCommandColumnWidth());
            }
        } else {
            // ── @ file / directory suggestions ────────────────────────
            // If we just left the command branch, hide stale command list.
            if (Strings.CS.equals("command", lastSuggestionKind)) {
                inputPanel.hideSuggestions();
                inputPanel.setArgumentHint(null);
            }

            Matcher atMatcher = AT_TOKEN_RE.matcher(beforeCursor);
            if (atMatcher.find()) {
                lastSuggestionKind = "file";
                // group(1) = the part after @  (may be quoted or plain)
                String rawToken = atMatcher.group(1);
                // Strip surrounding quotes for quoted paths: @"my file" → my file
                String searchToken = Strings.CS.startsWith(rawToken, "\"")
                    ? rawToken.substring(1).replaceAll("\"$", "")
                    : rawToken;

                // Path-like token (@~/…, @/…, @./…, @../…) → directory traversal
                // Both branches run on a Virtual Thread to avoid blocking the GUI thread.
                // Generation counter prevents a stale (slower) thread from overwriting
                // a fresher result pushed by a later keystroke.
                schedulePathSuggestions(searchToken);
            } else {
                // No active @ token → clear file suggestions
                if (Strings.CS.equals("file", lastSuggestionKind)) {
                    cancelPathSuggestions();
                    lastSuggestionKind = "none";
                    inputPanel.hideSuggestions();
                    inputPanel.setArgumentHint(null);
                }
            }
        }
    }

    private void cancelPathSuggestions() {
        pathSuggestionGeneration.incrementAndGet();
        pathSuggestions.cancel();
        if (fileSuggestionService != null) fileSuggestionService.nextGen();
    }

    private void schedulePathSuggestions(String searchToken) {
        if (fileSuggestionService == null || gui == null || inputPanel == null) return;
        final long requestGen = pathSuggestionGeneration.incrementAndGet();
        final long gen = fileSuggestionService.nextGen();
        pathSuggestions.submit(cancelled -> {
            List<SuggestionPanel.Suggestion> items = isPathLike(searchToken)
                ? directorySuggestionService.build(searchToken)
                : fileSuggestionService.build(searchToken.toLowerCase(Locale.ROOT), cancelled);
            if (cancelled.getAsBoolean()) return;
            gui.getGUIThread().invokeLater(() -> {
                if (pathSuggestionGeneration.get() == requestGen
                        && fileSuggestionService.currentGen() == gen)
                    inputPanel.showSuggestions(items, termWidth);
            });
        });
    }

    private void scheduleBashPathSuggestions(String searchToken) {
        if (directorySuggestionService == null || gui == null || inputPanel == null) return;
        final long requestGen = pathSuggestionGeneration.incrementAndGet();
        if (fileSuggestionService != null) fileSuggestionService.nextGen();
        pathSuggestions.submit(cancelled -> {
            List<SuggestionPanel.Suggestion> items = directorySuggestionService.build(searchToken);
            if (cancelled.getAsBoolean()) return;
            gui.getGUIThread().invokeLater(() -> {
                if (pathSuggestionGeneration.get() == requestGen)
                    inputPanel.showBashPathSuggestions(items, termWidth);
            });
        });
    }

    @Override public void close() {
        pathSuggestions.close();
        try {
            registrySubscription.close();
        } catch (Exception _) {
            // Closing an in-process listener is best effort.
        }
    }


    private static boolean isPathLike(String t) {
        return Strings.CS.startsWith(t, "~/") || Strings.CS.startsWith(t, "/") || Strings.CS.startsWith(t, "./")
            || Strings.CS.startsWith(t, "../") || Strings.CS.equals(t, "~") || Strings.CS.equals(t, ".") || Strings.CS.equals(t, "..");
    }

    // ── Command / skill suggestion building ─────────────────────────────────

    /**
     * Build command suggestions filtered by {@code filter} (text after '/').
     */
    List<SuggestionPanel.Suggestion> buildCommandSuggestions(String filter) {
        CommandIndex currentIndex = commandIndex;
        if (StringUtils.isBlank(filter)) return currentIndex.emptySuggestions();
        List<SuggestionPanel.Suggestion> result = new ArrayList<>();
        {


            final int TIER_EXACT_NAME   = 5;
            final int TIER_EXACT_ALIAS  = 4;
            final int TIER_PREFIX_NAME  = 3;
            final int TIER_PREFIX_ALIAS = 2;
            final int TIER_FUZZY        = 1;

            record Scored(SuggestionPanel.Suggestion item, int tier, int subScore, int tieBreakLen) {}
            List<Scored> all = new ArrayList<>();
            String fl = filter.toLowerCase(Locale.ROOT);

            for (CommandSearchEntry entry : currentIndex.searchEntries()) {
                    String nameLower = entry.nameLower();
                    int tier; int tieBreakLen; int subScore = 0; String matchedAlias = null;
                    if (nameLower.equals(fl)) {
                        tier = TIER_EXACT_NAME; tieBreakLen = nameLower.length();
                    } else if (!entry.skill() && (matchedAlias = exactAlias(entry.aliases(), fl)) != null) {
                        tier = TIER_EXACT_ALIAS; tieBreakLen = matchedAlias.length();
                    } else if (Strings.CS.startsWith(nameLower, fl)) {
                        tier = TIER_PREFIX_NAME; tieBreakLen = nameLower.length();
                    } else if (!entry.skill()
                            && (matchedAlias = shortestPrefixAlias(entry.aliases(), fl)) != null) {
                        tier = TIER_PREFIX_ALIAS; tieBreakLen = matchedAlias.length();
                    } else {
                        int s = matchScore(entry.name(), entry.description(), filter);
                        if (s == 0) continue;
                        tier = TIER_FUZZY; subScore = s; tieBreakLen = nameLower.length();
                    }
                    String desc = entry.description();
                    if (matchedAlias != null) desc = desc + " (" + matchedAlias + ")";
                    String prefix = entry.skill() && Strings.CS.startsWith(entry.name(), "agent-")
                        ? "* /" : "/";
                    all.add(new Scored(new SuggestionPanel.Suggestion(prefix + entry.name(), desc),
                        tier, subScore, tieBreakLen));
            }

            all.stream()
                .sorted(Comparator.comparingInt(Scored::tier).reversed()
                    .thenComparing(Comparator.comparingInt(Scored::subScore).reversed())
                    .thenComparingInt(Scored::tieBreakLen))
                .forEach(s -> result.add(s.item()));
        }
        return result;
    }

    private void rebuildCommandIndex(List<Command> commands, List<SkillEntry> skills,
                                     Map<String, Double> usageScores) {
        Map<String, Command> byName = new HashMap<>();
        List<CommandSearchEntry> index = new ArrayList<>(commands.size() + skills.size());
        for (Command command : commands) {
            byName.put(command.name().toLowerCase(Locale.ROOT), command);
            index.add(new CommandSearchEntry(command.name(),
                command.name().toLowerCase(Locale.ROOT),
                command.menuDescription() == null ? "" : command.menuDescription(),
                List.copyOf(command.aliases()), false));
        }
        List<SkillEntry> visibleSkills = skills.stream()
            .filter(skill -> !(skill.commandProjection()
                && byName.containsKey(skill.name().toLowerCase(Locale.ROOT))))
            .toList();
        for (SkillEntry skill : visibleSkills) {
            index.add(new CommandSearchEntry(skill.name(),
                skill.name().toLowerCase(Locale.ROOT), skill.description(), List.of(), true));
        }
        commandIndex = new CommandIndex(byName, index,
            computeCommandColumnWidth(commands, visibleSkills),
            buildEmptySuggestions(commands, visibleSkills, usageScores));
    }

    private static List<SuggestionPanel.Suggestion> buildEmptySuggestions(
            List<Command> commands, List<SkillEntry> skills, Map<String, Double> usageScores) {
        List<SuggestionPanel.Suggestion> result = new ArrayList<>();
        List<SkillEntry> recent = skills.stream()
            .filter(skill -> usageScores.getOrDefault(skill.name(), 0.0) > 0)
            .sorted((a, b) -> Double.compare(usageScores.getOrDefault(b.name(), 0.0),
                usageScores.getOrDefault(a.name(), 0.0)))
            .limit(5).toList();
        Set<String> recentNames = new HashSet<>();
        recent.forEach(skill -> {
            recentNames.add(skill.name());
            result.add(toSuggestion(skill));
        });
        commands.stream().sorted(Comparator.comparing(Command::name))
            .forEach(command -> result.add(new SuggestionPanel.Suggestion("/" + command.name(),
                command.menuDescription() == null ? "" : command.menuDescription())));
        skills.stream().filter(skill -> !recentNames.contains(skill.name()))
            .sorted(Comparator.comparing(SkillEntry::name))
            .forEach(skill -> result.add(toSuggestion(skill)));
        return List.copyOf(result);
    }

    /**
     * Name-column width for the command dropdown, sized off the longest name across ALL currently known
     * commands + skills — not the filtered {@code items} passed to {@link #buildCommandSuggestions}.
     */
    private int computeCommandColumnWidth() {
        return commandIndex.columnWidth();
    }

    private static int computeCommandColumnWidth(List<Command> commands,
                                                 List<SkillEntry> skills) {
        int maxLen = 0;
        for (Command c : commands) maxLen = Math.max(maxLen, c.name().length());
        for (SkillEntry s : skills) maxLen = Math.max(maxLen, s.name().length());
        return maxLen == 0 ? -1 : maxLen + 6; // +1 for "/" prefix, +5 padding
    }

    /** Match score: 4=exact, 3=prefix, 2=contains-name, 1=description, 0=no match. */
    private static int matchScore(String name, String desc, String filter) {
        String nl = name.toLowerCase(Locale.ROOT);
        String fl = filter.toLowerCase(Locale.ROOT);
        if (nl.equals(fl)) return 4;
        if (Strings.CS.startsWith(nl, fl)) return 3;
        if (Strings.CS.contains(nl, fl)) return 2;
        if (desc != null && Strings.CI.contains(desc, fl)) return 1;
        return 0;
    }

    /** Returns the alias exactly equal to {@code filterLower} (already lower-cased), or null. */
    private static String exactAlias(List<String> aliases, String filterLower) {
        for (String alias : aliases) {
            if (alias.equalsIgnoreCase(filterLower)) return alias;
        }
        return null;
    }


    private static String shortestPrefixAlias(List<String> aliases, String filterLower) {
        String best = null;
        for (String alias : aliases) {
            if (Strings.CI.startsWith(alias, filterLower) && (best == null || alias.length() < best.length())) {
                best = alias;
            }
        }
        return best;
    }

/**
     * Convert a SkillEntry to a Suggestion.
     */
    private static SuggestionPanel.Suggestion toSuggestion(SkillEntry skill) {
        String prefix = Strings.CS.startsWith(skill.name(), "agent-") ? "* /" : "/";
        return new SuggestionPanel.Suggestion(prefix + skill.name(), skill.description());
    }

    /**
     * A registry revision is already an immutable, complete generation. Project
     * its command rows into the hot index synchronously before scheduling the
     * slower Skill/usage merge, so startup metadata I/O can never hide a command
     * that readiness has already published.
     */
    private void registryChanged(CommandRegistry.Snapshot snapshot) {
        metadataGeneration.incrementAndGet();
        metadataLoadedAtMs = 0;
        applyRegistrySnapshot(snapshot);
        ensureMetadataLoading();
        refreshLiveQuery();
    }

    private void applyRegistrySnapshot(CommandRegistry.Snapshot snapshot) {
        List<Command> commands = visibleCommands(snapshot.commands());
        synchronized (commandIndexLock) {
            if (snapshot.revision() <= indexedRegistryRevision) return;
            SuggestionMetadata current = suggestionMetadata;
            suggestionMetadata = new SuggestionMetadata(
                commands, current.skills(), current.usageScores());
            rebuildCommandIndex(commands, current.skills(), current.usageScores());
            indexedRegistryRevision = snapshot.revision();
        }
    }

    private static List<Command> visibleCommands(List<Command> commands) {
        return commands.stream()
            .filter(command -> !StringUtils.isBlank(command.name()))
            .filter(command -> !command.isHidden())
            .toList();
    }

    private void refreshLiveQuery() {
        if (gui != null && inputPanel != null) {
            gui.getGUIThread().invokeLater(inputPanel::triggerQueryChange);
        }
    }

    /**
     * Starts a refresh without making the current key wait. Until it completes,
     * slash suggestions still show built-in commands; completion re-fires the
     * live query on Lanterna's GUI thread so skills appear immediately.
     */
    private void ensureMetadataLoading() {
        long now = System.currentTimeMillis();
        if (metadataLoadedAtMs != 0 && now - metadataLoadedAtMs < METADATA_CACHE_TTL_MS) return;
        if (!metadataLoading.compareAndSet(false, true)) return;
        long generation = metadataGeneration.get();
        Thread.ofVirtual().name("suggestion-metadata").start(() -> {
            try {
                CommandRegistry.Snapshot registrySnapshot = commandRegistry.snapshot();
                List<Command> commands = readAvailableCommands(registrySnapshot.commands());
                List<SkillEntry> skills = readUserSkillsFromSource();
                Map<String, Double> usageScores = readSkillUsageScores();
                synchronized (commandIndexLock) {
                    if (generation == metadataGeneration.get()
                            && registrySnapshot.revision() >= indexedRegistryRevision) {
                        suggestionMetadata = new SuggestionMetadata(
                            commands, skills, usageScores);
                        rebuildCommandIndex(commands, skills, usageScores);
                        indexedRegistryRevision = registrySnapshot.revision();
                        metadataLoadedAtMs = System.currentTimeMillis();
                    }
                }
            } catch (RuntimeException failure) {
                // Commands are already available from the revision snapshot;
                // retain that successful subset and avoid a hot retry loop.
                if (generation == metadataGeneration.get()) {
                    metadataLoadedAtMs = System.currentTimeMillis();
                }
                log.warn("Unable to refresh optional suggestion metadata; retaining command snapshot",
                    failure);
            } finally {
                metadataLoading.set(false);
            }
            if (generation != metadataGeneration.get()) {
                ensureMetadataLoading();
                return;
            }
            refreshLiveQuery();
        });
    }

    private static List<Command> readAvailableCommands(List<Command> commands) {
        CommandContext context = CommandContext.minimal();
        return commands.stream()
            .filter(command -> !StringUtils.isBlank(command.name()))
            .filter(command -> !command.isHidden())
            .filter(command -> isAvailable(command, context))
            .toList();
    }

    private static boolean isAvailable(Command command, CommandContext context) {
        try {
            return command.isAvailable(context);
        } catch (RuntimeException failure) {
            log.debug("Ignoring command with failing availability check: {}", command.name(), failure);
            return false;
        }
    }

    /** Convert the shared discovered-skill snapshot into slash suggestions off the GUI thread. */
    private List<SkillEntry> readUserSkillsFromSource() {
        List<SkillEntry> result = new ArrayList<>();
        try {
            for (Skill skill : skillsSupplier.get()) {
                result.add(new SkillEntry(skill.name(),
                    skill.description() != null ? skill.description() : "",
                    skill.argumentNames(), skill.argumentHint(),
                    skill.commandProjection()));
            }
        } catch (Exception _) {}
        return result;
    }


    private Map<String, Double> readSkillUsageScores() {
        try {
            return UiSettings.readSkillUsageScores();
        } catch (Exception _) {}
        return Map.of();
    }

    /** One user-installed skill parsed from {@code ~/.claude/skills/<name>/SKILL.md}. */
    private record SkillEntry(String name, String description,
                              List<String> argumentNames, String argumentHint,
                              boolean commandProjection) {}
}
