package com.claudecode.commands.workflows;

import com.claudecode.commands.CommandRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Replaces the dynamic workflow slash-command generation after startup or a workflow/plugin reload.
 */
public final class WorkflowCommandSync {

    private final Set<String> registered = new HashSet<>();

    public synchronized int sync(CommandRegistry registry, List<WorkflowCommandDefinition> definitions) {
        List<WorkflowCommandDefinition> visible =
            (definitions == null ? List.<WorkflowCommandDefinition>of() : definitions).stream()
                .filter(definition -> !definition.hidden())
                .toList();
        List<WorkflowPromptCommand> commands = visible.stream()
            .map(WorkflowPromptCommand::new)
            .toList();
        Set<String> previous = Set.copyOf(registered);
        registry.replaceMatching(previous::contains, commands);
        registered.clear();
        visible.forEach(definition ->
            registered.add(definition.name().toLowerCase(Locale.ROOT)));
        return visible.size();
    }
}
