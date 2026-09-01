package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    Optional<Map<String, Object>> metadata
) {

    /**
     * Absent metadata is {@link Optional#empty()} so the wire form keeps an omitted {@code
     * metadata} field distinct from an explicit empty JSON object. Uses NON_ABSENT + the
     * Jdk8Module serializer rather than a CUSTOM valueFilter: Jackson instantiates valueFilter
     * classes reflectively, which breaks under GraalVM native image unless each filter class is
     * registered in reachability-metadata.json.
     */
    public TaskReminderItem {
        blocks = List.copyOf(blocks == null ? List.of() : blocks);
        blockedBy = List.copyOf(blockedBy == null ? List.of() : blockedBy);
        metadata = metadata == null
            ? Optional.empty()
            : metadata.map(value -> Collections.unmodifiableMap(new LinkedHashMap<>(value)));
    }
}
