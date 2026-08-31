package com.claudecode.runtime.query;

import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.UserMessage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Buffers the assistant and user rows a single {@code query} invocation has already streamed, so a
 * model fallback can withdraw them before the retry paints its own answer over the top.
 */
final class TombstoneEmitter {

    private final List<AssistantMessage> assistants = new ArrayList<>();
    private final List<UserMessage> users = new ArrayList<>();

    void recordAssistant(AssistantMessage message) {
        if (message != null) assistants.add(message);
    }

    void recordUser(UserMessage message) {
        if (message != null) users.add(message);
    }

    /**
     * The rows a sweep would withdraw right now, in the order it would withdraw them.
     */
    List<Message> pending() {
        List<Message> pending = new ArrayList<>(assistants.size() + users.size());
        pending.addAll(assistants);
        pending.addAll(users);
        return List.copyOf(pending);
    }

    boolean hasAssistantRows() {
        return !assistants.isEmpty();
    }

    


    List<Message> retractAll(DefaultQuerySession engine, Consumer<SDKMessage> emit) {
        if (assistants.isEmpty() && users.isEmpty()) return List.of();
        List<Message> withdrawn = new ArrayList<>(assistants.size() + users.size());
        Set<String> retracted = new HashSet<>();
        for (AssistantMessage message : assistants) {
            retracted.add(message.uuid());
            withdrawn.add(message);
            emit.accept(new SDKMessage.Tombstone(message.uuid()));
            removeTranscriptRow(engine, message.uuid());
        }
        for (UserMessage message : users) {
            retracted.add(message.uuid());
            withdrawn.add(message);
            emit.accept(new SDKMessage.Tombstone(message.uuid()));
            removeTranscriptRow(engine, message.uuid());
        }
        assistants.clear();
        users.clear();
        List<Message> history = engine.getMutableMessages();
        history.removeIf(message -> retracted.contains(message.uuid()));
        return List.copyOf(withdrawn);
    }

    private static void removeTranscriptRow(DefaultQuerySession engine, String uuid) {
        var sink = engine.getTranscriptSink();
        if (sink != null) sink.remove(engine.getSessionId(), uuid);
    }
}
