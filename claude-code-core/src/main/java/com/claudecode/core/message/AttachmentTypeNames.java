package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The JSON discriminator each {@link AttachmentPayload} serialises under, taken
 * from that interface's {@link JsonSubTypes} table so there is one list of names
 * rather than two that can drift.
 *
 * <p>Exists so that callers who want only the name do not serialise the payload
 * to read it. {@code valueToTree} walks the whole object graph — a
 * {@link FileContentAttachment} can carry tens of kilobytes of file text — and
 * in a native image it also pulls Jackson's reflective type resolution into the
 * caller's failure surface: the context-timeline fold read this name that way
 * and started answering {@code MissingReflectionRegistrationError} for
 * {@code TodoItem[]} once a to-do reminder reached it.
 */
public final class AttachmentTypeNames {

    private static final Map<Class<?>, String> BY_CLASS = index();

    private AttachmentTypeNames() {
    }

    /**
     * The payload's discriminator ({@code plan_mode}, {@code skill_listing}…),
     * or its simple class name if the subtype was never registered — an
     * unregistered subtype cannot be serialised either, so this is a label for
     * a payload that is already broken, not a supported second naming scheme.
     */
    public static String of(AttachmentPayload payload) {
        String name = BY_CLASS.get(payload.getClass());
        return name != null ? name : payload.getClass().getSimpleName();
    }

    private static Map<Class<?>, String> index() {
        Map<Class<?>, String> names = new LinkedHashMap<>();
        JsonSubTypes subTypes = AttachmentPayload.class.getAnnotation(JsonSubTypes.class);
        if (subTypes != null) {
            for (JsonSubTypes.Type subType : subTypes.value()) {
                names.put(subType.value(), subType.name());
            }
        }
        return Map.copyOf(names);
    }
}
