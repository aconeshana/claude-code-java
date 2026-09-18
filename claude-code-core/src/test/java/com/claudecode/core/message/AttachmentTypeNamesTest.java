package com.claudecode.core.message;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the two ways {@link AttachmentTypeNames} can go quietly wrong: a sealed
 * subtype that never received a {@link JsonSubTypes} entry — the lookup would
 * answer with a class name no client recognises, and Jackson could not serialise
 * it either — and a name that disagrees with what Jackson actually writes.
 */
class AttachmentTypeNamesTest {

    private final ObjectMapper mapper = JsonUtils.getMapper();

    @Test
    void everySealedSubtypeHasADiscriminator() {
        Set<Class<?>> permitted = new LinkedHashSet<>(
            List.of(AttachmentPayload.class.getPermittedSubclasses()));
        Set<Class<?>> registered = new LinkedHashSet<>();
        for (JsonSubTypes.Type subType : AttachmentPayload.class
            .getAnnotation(JsonSubTypes.class).value()) {
            registered.add(subType.value());
        }

        Set<Class<?>> unnamed = new LinkedHashSet<>(permitted);
        unnamed.removeAll(registered);
        assertTrue(unnamed.isEmpty(),
            () -> "sealed subtypes without a @JsonSubTypes name: " + unnamed);

        Set<Class<?>> stale = new LinkedHashSet<>(registered);
        stale.removeAll(permitted);
        assertTrue(stale.isEmpty(),
            () -> "@JsonSubTypes entries for types the interface no longer permits: " + stale);
    }

    @Test
    void discriminatorMatchesTheSerializedTypeField() {
        List<AttachmentPayload> samples = List.of(
            new TodoReminderAttachment(List.of(new TodoItem("pending", "write code")), 1),
            new FileContentAttachment("/tmp/a.txt", "hello"),
            new PlanModeExitAttachment("/plan.md", true),
            new SkillListingAttachment("- foo: d", 1, true),
            new TokenUsageAttachment(100, 200, 100));

        for (AttachmentPayload payload : samples) {
            assertEquals(
                mapper.valueToTree(payload).path("type").asText(),
                AttachmentTypeNames.of(payload),
                payload.getClass().getSimpleName());
        }
    }
}
