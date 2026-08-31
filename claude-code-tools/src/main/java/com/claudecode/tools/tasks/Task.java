package com.claudecode.tools.tasks;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A to-do list task — the persistent entity managed by the TaskCreate / TaskGet / TaskList /
 * TaskUpdate tools (the model-facing task list, not the background-task system fronted by {@link
 * TaskState}/{@link TaskStore}).
 */
public record Task(
    String id,
    String subject,
    String description,
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    Optional<String> activeForm,
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    Optional<String> owner,
    TodoStatus status,
    List<String> blocks,
    List<String> blockedBy,
    @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = AbsentMetadataFilter.class)
    Map<String, Object> metadata
) {

    private static final Map<String, Object> ABSENT_METADATA =
        Collections.unmodifiableMap(new LinkedHashMap<>());

    /** Jackson filter that distinguishes an omitted metadata field from an explicit empty object. */
    public static final class AbsentMetadataFilter {
        @Override
        public boolean equals(Object other) {
            return other == ABSENT_METADATA;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(ABSENT_METADATA);
        }
    }

    public Task {
        activeForm = activeForm == null ? Optional.empty() : activeForm;
        owner = owner == null ? Optional.empty() : owner;
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        blockedBy = blockedBy == null ? List.of() : List.copyOf(blockedBy);
        metadata = metadata == null || metadata == ABSENT_METADATA
            ? ABSENT_METADATA
            : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    boolean hasMetadata() {
        return metadata != ABSENT_METADATA;
    }

    public Task withId(String newId) {
        return new Task(newId, subject, description, activeForm, owner, status,
            blocks, blockedBy, metadata);
    }

    public Task withStatus(TodoStatus newStatus) {
        return new Task(id, subject, description, activeForm, owner, newStatus, blocks, blockedBy, metadata);
    }

    public Task withSubject(String newSubject) {
        return new Task(id, newSubject, description, activeForm, owner, status, blocks, blockedBy, metadata);
    }

    public Task withDescription(String newDescription) {
        return new Task(id, subject, newDescription, activeForm, owner, status, blocks, blockedBy, metadata);
    }

    public Task withActiveForm(String newActiveForm) {
        return new Task(id, subject, description, Optional.ofNullable(newActiveForm), owner, status, blocks, blockedBy, metadata);
    }

    public Task withOwner(String newOwner) {
        return new Task(id, subject, description, activeForm, Optional.ofNullable(newOwner), status, blocks, blockedBy, metadata);
    }

    public Task withBlocks(List<String> newBlocks) {
        return new Task(id, subject, description, activeForm, owner, status, List.copyOf(newBlocks), blockedBy, metadata);
    }

    public Task withBlockedBy(List<String> newBlockedBy) {
        return new Task(id, subject, description, activeForm, owner, status, blocks, List.copyOf(newBlockedBy), metadata);
    }

    public Task withMetadata(Map<String, Object> newMetadata) {
        return new Task(id, subject, description, activeForm, owner, status, blocks, blockedBy, newMetadata);
    }
}
