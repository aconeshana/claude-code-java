package com.claudecode.core.attachment;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.claudecode.core.message.AttachmentPayload;
import com.claudecode.core.message.DeferredToolsDeltaAttachment;

/**
 * Diffs the enabled tool names across turns, announcing the tools that became available /
 * unavailable since the previous turn.
 */
public final class DeferredToolsDeltaAttachmentProvider implements AttachmentProvider {

    @Override
    public String name() {
        return "deferred_tools_delta";
    }

    @Override
    public boolean isEnabled(FeatureFlagRegistry flags) {
        return flags.isEnabled(FeatureFlag.DEFERRED_TOOLS_DELTA);
    }

    @Override
    public List<AttachmentPayload> collect(AttachmentContext ctx) {
        List<String> current = ctx.toolNames();
        if (current == null) current = List.of();
        List<String> previous = ctx.previousTurnTools();

        Set<String> currentSet = new TreeSet<>(current);
        Set<String> previousSet = (previous == null) ? Set.of() : new TreeSet<>(previous);

        List<String> addedNames = new ArrayList<>();
        for (String t : currentSet) {
            if (!previousSet.contains(t)) addedNames.add(t);
        }
        List<String> removedNames = new ArrayList<>();
        for (String t : previousSet) {
            if (!currentSet.contains(t)) removedNames.add(t);
        }

        if (addedNames.isEmpty() && removedNames.isEmpty()) {
            return List.of();
        }

        // Preserve insertion order from the current tool list for the rendered lines

        LinkedHashSet<String> addedOrdered = new LinkedHashSet<>(addedNames);
        List<String> addedLines = new ArrayList<>(addedOrdered);

        return List.of(new DeferredToolsDeltaAttachment(addedNames, addedLines, removedNames));
    }
}
