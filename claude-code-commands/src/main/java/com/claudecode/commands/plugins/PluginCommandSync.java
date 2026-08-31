package com.claudecode.commands.plugins;

import org.apache.commons.lang3.Strings;
import com.claudecode.commands.CommandRegistry;
import com.claudecode.commands.impl.integration.PluginMarkdownCommand;
import com.claudecode.runtime.plugins.PluginCommandDefinition;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Keeps a {@link CommandRegistry} in sync with the current generation of plugin commands: each
 * {@link #sync} atomically unregisters the previous generation (tracked by name — plugin command
 * names always contain {@code ':'}, so built-ins are never touched) and registers the new one.
 */
public final class PluginCommandSync {

    /** Lowercased names of the currently registered generation. */
    private final Set<String> registered = new HashSet<>();

    /**
     * Replaces the registry's plugin commands with {@code defs}.
     *
     * @return the number of commands registered
     */
    public synchronized int sync(CommandRegistry registry, List<PluginCommandDefinition> defs) {
        List<PluginCommandDefinition> generation = defs == null ? List.of() : List.copyOf(defs);
        List<PluginMarkdownCommand> commands = generation.stream()
            .map(PluginMarkdownCommand::new)
            .toList();
        Set<String> previous = Set.copyOf(registered);
        registry.replaceMatching(name ->
            Strings.CS.contains(name, ":") && previous.contains(name), commands);
        registered.clear();
        generation.forEach(def -> registered.add(def.name().toLowerCase(Locale.ROOT)));
        return generation.size();
    }
}
