package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persistent task shape carried by a {@code task_reminder} attachment. */
public record TaskReminderItem(
    String id,
    String subject,
    String description,
    @JsonInclude(JsonInclude.Include.NON_NULL) String activeForm,
    @JsonInclude(JsonInclude.Include.NON_NULL) String owner,
    String status,
    List<String> blocks,
    List<String> blockedBy,
    @JsonInclude(value = JsonInclude.Include.CUSTOM,
        valueFilter = AbsentMetadataFilter.class) Map<String, Object> metadata
) {

    private static final Map<String, Object> ABSENT_METADATA =
        Collections.unmodifiableMap(new LinkedHashMap<>());

    /** Keeps absent metadata distinct from an explicitly supplied empty JSON object. */
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

    public TaskReminderItem {
        blocks = List.copyOf(blocks == null ? List.of() : blocks);
        blockedBy = List.copyOf(blockedBy == null ? List.of() : blockedBy);
        metadata = metadata == null || metadata == ABSENT_METADATA
            ? ABSENT_METADATA
            : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
