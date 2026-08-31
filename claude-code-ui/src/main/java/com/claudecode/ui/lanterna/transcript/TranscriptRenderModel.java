package com.claudecode.ui.lanterna.transcript;

import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.ProgressMessage;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.ToolResultBlock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.Strings;

/**
 * Immutable lookup state used by the Ctrl+O transcript renderer.
 */
final class TranscriptRenderModel {

    private final List<SDKMessage> events;
    private final Map<String, List<ProgressMessage>> agentProgressByToolUseId;
    private final Set<String> resolvedToolUseIds;

    private TranscriptRenderModel(List<SDKMessage> events,
            Map<String, List<ProgressMessage>> agentProgressByToolUseId,
            Set<String> resolvedToolUseIds) {
        this.events = List.copyOf(events);
        Map<String, List<ProgressMessage>> progress = new LinkedHashMap<>();
        agentProgressByToolUseId.forEach((toolUseId, messages) ->
            progress.put(toolUseId, List.copyOf(messages)));
        this.agentProgressByToolUseId = Map.copyOf(progress);
        this.resolvedToolUseIds = Set.copyOf(resolvedToolUseIds);
    }

    static TranscriptRenderModel from(List<SDKMessage> source) {
        if (source == null || source.isEmpty()) {
            return new TranscriptRenderModel(List.of(), Map.of(), Set.of());
        }
        List<SDKMessage> events = new ArrayList<>(source.size());
        Map<String, List<ProgressMessage>> progress = new LinkedHashMap<>();
        Set<String> resolved = new LinkedHashSet<>();
        for (SDKMessage event : source) {
            if (event instanceof SDKMessage.Progress(var message)
                    && isAgentProgress(message)) {
                if (message.toolUseId() != null) {
                    progress.computeIfAbsent(message.toolUseId(), _ -> new ArrayList<>())
                        .add(message);
                }
                continue;
            }
            events.add(event);
            if (event instanceof SDKMessage.User user
                    && user.message() != null
                    && user.message().message() != null
                    && user.message().message().blocks() != null) {
                for (ContentBlock block : user.message().message().blocks()) {
                    if (block instanceof ToolResultBlock result && result.toolUseId() != null) {
                        resolved.add(result.toolUseId());
                    }
                }
            }
        }
        return new TranscriptRenderModel(events, progress, resolved);
    }

    private static boolean isAgentProgress(ProgressMessage message) {
        return message != null && message.data() != null
            && Strings.CS.equals("agent_progress", message.data().type());
    }

    List<SDKMessage> events() {
        return events;
    }

    Map<String, List<ProgressMessage>> agentProgressByToolUseId() {
        return agentProgressByToolUseId;
    }

    Set<String> resolvedToolUseIds() {
        return resolvedToolUseIds;
    }
}
