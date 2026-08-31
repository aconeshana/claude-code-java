package com.claudecode.services.compact;

import com.claudecode.core.engine.FileStateCache;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Bundles everything {@link DefaultManualCompactStrategy}'s post-compact attachment producers need
 * after the mutable tool state is frozen into an immutable {@link
 * CompactAttachmentStateProvider.Snapshot}.
 */
record CompactAttachmentContext(
    FileStateCache fileStateCache,
    Supplier<CompactAttachmentStateProvider.Snapshot> stateSupplier,
    String transcriptPath,
    String agentListingMessage,
    Supplier<Map<String, String>> mcpInstructionsSupplier,
    Supplier<List<String>> toolNamesSupplier
) {
    CompactAttachmentContext {
        stateSupplier = stateSupplier == null
            ? () -> CompactAttachmentStateProvider.Snapshot.empty(null, false)
            : stateSupplier;
        mcpInstructionsSupplier = mcpInstructionsSupplier == null ? Map::of : mcpInstructionsSupplier;
        toolNamesSupplier = toolNamesSupplier == null ? List::of : toolNamesSupplier;
    }

    CompactAttachmentContext(
            FileStateCache fileStateCache,
            Supplier<CompactAttachmentStateProvider.Snapshot> stateSupplier,
            String transcriptPath,
            String agentListingMessage,
            Supplier<Map<String, String>> mcpInstructionsSupplier) {
        this(fileStateCache, stateSupplier, transcriptPath, agentListingMessage,
            mcpInstructionsSupplier, List::of);
    }

    CompactAttachmentContext(
            FileStateCache fileStateCache,
            CompactAttachmentStateProvider.Snapshot state,
            String transcriptPath,
            String agentListingMessage) {
        this(fileStateCache,
            () -> state == null
                ? CompactAttachmentStateProvider.Snapshot.empty(null, false)
                : state,
            transcriptPath,
            agentListingMessage,
            Map::of,
            List::of);
    }

    CompactAttachmentStateProvider.Snapshot state() {
        CompactAttachmentStateProvider.Snapshot state = stateSupplier.get();
        return state == null
            ? CompactAttachmentStateProvider.Snapshot.empty(null, false)
            : state;
    }

    Map<String, String> mcpInstructions() {
        Map<String, String> instructions = mcpInstructionsSupplier.get();
        return instructions == null ? Map.of() : instructions;
    }

    List<String> toolNames() {
        List<String> names = toolNamesSupplier.get();
        return names == null ? List.of() : List.copyOf(names);
    }
}
