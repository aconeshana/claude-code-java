package com.claudecode.commands;

import com.claudecode.commands.parsing.SlashCommandParser;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Registry for slash commands.
 */
public class CommandRegistry {

    /** Immutable, revisioned view published after an atomic registry mutation. */
    public record Snapshot(long revision, List<Command> commands) {
        public Snapshot {
            commands = List.copyOf(commands);
        }
    }

    private final Object lock = new Object();
    private final Map<String, Command> commandsByName = new LinkedHashMap<>();
    private final Map<String, Command> aliasMap = new HashMap<>();
    private final Set<String> builtInCommandNames = new HashSet<>();
    private final CopyOnWriteArrayList<Consumer<Snapshot>> subscribers =
        new CopyOnWriteArrayList<>();
    private long revision;

    /**
     * Register a command. Overwrites any existing command with the same name.
     */
    public void register(Command cmd) {
        Objects.requireNonNull(cmd, "command must not be null");
        Snapshot published;
        synchronized (lock) {
            registerLocked(cmd);
            rebuildAliasesLocked();
            published = advanceRevisionLocked();
        }
        publish(published);
    }

    /** Register one generation of commands and publish a single revision. */
    public void registerAll(Collection<? extends Command> commands) {
        List<? extends Command> replacements = validatedCommands(commands);
        if (replacements.isEmpty()) return;
        Snapshot published;
        synchronized (lock) {
            replacements.forEach(this::registerLocked);
            rebuildAliasesLocked();
            published = advanceRevisionLocked();
        }
        publish(published);
    }

    /**
     * Register a command and add its primary name plus aliases to the stable built-in name catalogue
     * used by {@code /help}.
     */
    public void registerBuiltIn(Command cmd) {
        Objects.requireNonNull(cmd, "command must not be null");
        Snapshot published;
        synchronized (lock) {
            registerLocked(cmd);
            rebuildAliasesLocked();
            addBuiltInNamesLocked(cmd);
            published = advanceRevisionLocked();
        }
        publish(published);
    }

    
    public void markAllRegisteredAsBuiltIn() {
        synchronized (lock) {
            commandsByName.values().forEach(this::addBuiltInNamesLocked);
        }
    }


    public boolean isBuiltInCommandName(String name) {
        if (name == null) return false;
        synchronized (lock) {
            return builtInCommandNames.contains(name.toLowerCase(Locale.ROOT));
        }
    }

    private void registerLocked(Command cmd) {
        commandsByName.put(cmd.name().toLowerCase(Locale.ROOT), cmd);
    }

    private void rebuildAliasesLocked() {
        aliasMap.clear();
        for (Command command : commandsByName.values()) {
            for (String alias : command.aliases()) {
                aliasMap.put(alias.toLowerCase(Locale.ROOT), command);
            }
        }
    }

    private void addBuiltInNamesLocked(Command cmd) {
        builtInCommandNames.add(cmd.name().toLowerCase(Locale.ROOT));
        for (String alias : cmd.aliases()) {
            builtInCommandNames.add(alias.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Removes every command whose (lowercased) name matches {@code predicate}.
     * Returns the number of commands removed. Aliases of removed commands are
     * cleaned up automatically. Used by the MCP layer to strip
     * {@code mcp__<server>__*} entries after a Clear-Auth or a server
     * disconnect, so the model's slash-command menu stays in sync with what
     * the server actually still exposes.
     *
     * <p>matches {@link com.claudecode.tools.ToolRegistry#unregisterMatching}
     * so both catalogues have the same eviction ergonomics.
     */
    public int unregisterMatching(Predicate<String> predicate) {
        Objects.requireNonNull(predicate, "predicate must not be null");
        int removed;
        Snapshot published = null;
        synchronized (lock) {
            removed = 0;
            var iter = commandsByName.entrySet().iterator();
            while (iter.hasNext()) {
                var e = iter.next();
                if (predicate.test(e.getKey())) {
                    iter.remove();
                    removed++;
                }
            }
            if (removed > 0) {
                rebuildAliasesLocked();
                published = advanceRevisionLocked();
            }
        }
        if (published != null) publish(published);
        return removed;
    }

    /**
     * Atomically replaces all matching commands with one complete generation.
     * Readers observe either the previous or replacement snapshot, and
     * subscribers receive exactly one revision notification.
     */
    public void replaceMatching(Predicate<String> predicate,
                                Collection<? extends Command> replacements) {
        Objects.requireNonNull(predicate, "predicate must not be null");
        List<? extends Command> validated = validatedCommands(replacements);
        Snapshot published;
        synchronized (lock) {
            commandsByName.entrySet().removeIf(entry -> predicate.test(entry.getKey()));
            validated.forEach(this::registerLocked);
            rebuildAliasesLocked();
            published = advanceRevisionLocked();
        }
        publish(published);
    }

    /** Current immutable command generation and its monotonic revision. */
    public Snapshot snapshot() {
        synchronized (lock) {
            return snapshotLocked();
        }
    }

    /**
     * Subscribes to future revisions. The callback is always invoked outside
     * the registry lock and may safely perform registry reads or mutations.
     */
    public AutoCloseable subscribe(Consumer<Snapshot> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber must not be null");
        subscribers.add(subscriber);
        return () -> subscribers.remove(subscriber);
    }

    /**
     * Find a command by name or alias.
     */
    public Optional<Command> find(String name) {
        if (StringUtils.isBlank(name)) {
            return Optional.empty();
        }
        String key = name.toLowerCase(Locale.ROOT);
        synchronized (lock) {
            Command cmd = commandsByName.get(key);
            if (cmd != null) return Optional.of(cmd);
            return Optional.ofNullable(aliasMap.get(key));
        }
    }

    /**
     * Get all registered commands (primary names only, no alias duplicates).
     */
    public List<Command> getAll() {
        return snapshot().commands();
    }

    /**
     * Get all commands available in the given context.
     */
    public List<Command> getAvailable(CommandContext context) {
        List<Command> snapshot;
        synchronized (lock) {
            snapshot = List.copyOf(commandsByName.values());
        }
        // isAvailable may consult context state — evaluate outside the lock.
        return snapshot.stream()
            .filter(c -> c.isAvailable(context))
            .toList();
    }

    /**
     * Parse and dispatch a raw slash command input string.
     * Input format: "/command arg1 arg2 ..."
     *
     * @return the command result, or a result indicating unknown command
     */
    public CommandResult dispatch(String input, CommandContext context) {
        if (StringUtils.isBlank(input)) {
            return CommandResult.of("Empty command.");
        }

        String trimmed = input.trim();
        if (!Strings.CS.startsWith(trimmed, "/")) {
            return CommandResult.of("Not a command: " + trimmed);
        }

        ParsedCommand parsed = parseInput(trimmed);
        Optional<Command> cmd = find(parsed.name());

        if (cmd.isEmpty()) {
            return CommandResult.of("Unknown command: /" + parsed.name()
                + ". Type /help for available commands.");
        }

        Command command = cmd.get();
        if (!command.isAvailable(context)) {
            return CommandResult.of("Command /" + command.name()
                + " is not available in the current context.");
        }

        return command.execute(context, parsed.args());
    }


    public Optional<CommandResult> dispatchNonInteractive(
            String input, CommandContext context) {
        if (input == null || !Strings.CS.startsWith(input.stripLeading(), "/")) {
            return Optional.empty();
        }

        ParsedCommand parsed = parseInput(input.stripLeading());
        Command command = find(parsed.name()).orElse(null);
        if (command == null
                || !command.supportsNonInteractive()
                || !command.isAvailable(context)) {
            return Optional.of(CommandResult.of("Unknown skill: " + parsed.name()));
        }
        return Optional.of(command.execute(context, parsed.args()));
    }

    /**
     * Parse a slash command input into command name and args.
     *
     * <p>Delegates to {@link SlashCommandParser#parse} for the actual parsing logic.
     * The resulting name is lower-cased to allow case-insensitive dispatch; callers
     * that need original case should use {@link SlashCommandParser} directly.
     */
    static ParsedCommand parseInput(String input) {
        return SlashCommandParser.parse(input)
            .map(p -> new ParsedCommand(p.commandName().toLowerCase(Locale.ROOT), p.args().trim()))
            .orElseGet(() -> new ParsedCommand("", ""));
    }

    /**
     * Parsed slash command: name (without slash) and argument string.
     */
    record ParsedCommand(String name, String args) {}

    private Snapshot advanceRevisionLocked() {
        revision++;
        return snapshotLocked();
    }

    private Snapshot snapshotLocked() {
        return new Snapshot(revision, List.copyOf(commandsByName.values()));
    }

    private void publish(Snapshot snapshot) {
        for (Consumer<Snapshot> subscriber : subscribers) {
            try {
                subscriber.accept(snapshot);
            } catch (RuntimeException _) {
                // A diagnostic/UI listener must not roll back a committed generation.
            }
        }
    }

    private static List<? extends Command> validatedCommands(
            Collection<? extends Command> commands) {
        Objects.requireNonNull(commands, "commands must not be null");
        List<? extends Command> copy = List.copyOf(commands);
        copy.forEach(command -> Objects.requireNonNull(command, "command must not be null"));
        return copy;
    }
}
