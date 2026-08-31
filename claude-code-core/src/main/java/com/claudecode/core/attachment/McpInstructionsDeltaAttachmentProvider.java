package com.claudecode.core.attachment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.AttachmentPayload;
import com.claudecode.core.message.McpInstructionsDeltaAttachment;
import com.claudecode.core.message.Message;

/**
 * Diffs connected MCP servers that declare {@code instructions} against what has already been
 * announced in prior {@code mcp_instructions_delta} attachments.
 */
public final class McpInstructionsDeltaAttachmentProvider implements AttachmentProvider {

    @Override
    public String name() {
        return "mcp_instructions_delta";
    }

    @Override
    public boolean isEnabled(FeatureFlagRegistry flags) {
        return flags.isEnabled(FeatureFlag.MCP_INSTRUCTIONS_DELTA);
    }

    @Override
    public List<AttachmentPayload> collect(AttachmentContext ctx) {
        Map<String, String> current = ctx.mcpServerInstructions();
        if (current == null) current = Map.of();

        // Reconstruct the announced set from prior deltas in the transcript.
        Set<String> announced = new HashSet<>();
        for (Message m : ctx.messages()) {
            if (!(m instanceof AttachmentMessage am)) continue;
            if (am.payload() instanceof McpInstructionsDeltaAttachment prev) {
                announced.addAll(prev.addedNames());
                prev.removedNames().forEach(announced::remove);
            }
        }

        Set<String> currentNames = new TreeSet<>(current.keySet());
        List<String> addedNames = new ArrayList<>();
        List<String> addedBlocks = new ArrayList<>();
        for (String name : currentNames) {
            if (!announced.contains(name)) {
                addedNames.add(name);
                addedBlocks.add(renderBlock(name, current.get(name)));
            }
        }

        List<String> removedNames = new ArrayList<>();
        for (String name : announced) {
            if (!currentNames.contains(name)) removedNames.add(name);
        }
        removedNames.sort(String::compareToIgnoreCase);

        if (addedNames.isEmpty() && removedNames.isEmpty()) {
            return List.of();
        }

        return List.of(new McpInstructionsDeltaAttachment(addedNames, addedBlocks, removedNames));
    }


    private static String renderBlock(String name, String instructions) {
        return "## " + name + "\n" + (instructions == null ? "" : instructions);
    }
}
