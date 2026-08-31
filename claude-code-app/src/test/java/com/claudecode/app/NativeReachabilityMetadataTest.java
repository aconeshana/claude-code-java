package com.claudecode.app;

import org.apache.commons.lang3.Strings;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Executable guard against drift between the committed GraalVM reachability
 * metadata and the Jackson type closure the native image must reflect over.
 *
 * <p>The metadata file is a hand-maintained snapshot, so a record that gains a
 * component silently invalidates the constructor signature recorded for it. That
 * failure is invisible on the JVM and only surfaces as a
 * {@code MissingReflectionRegistrationError} once the native binary reaches the
 * affected payload — {@code AssistantContent} gaining {@code stop_details} broke
 * {@code --continue} exactly this way. This test recomputes the closure from the
 * live classes and requires every type in it to be registered with blanket
 * constructor access, so arity changes can no longer outrun the metadata.
 *
 * <p>Blanket registration is the invariant rather than an enumerated signature
 * list: Jackson binds records through their canonical constructor, and pinning
 * one arity buys nothing while guaranteeing drift.
 */
class NativeReachabilityMetadataTest {

    /**
     * Entry points Jackson binds reflectively: transcript rows restored by
     * {@code --continue}/{@code --resume}, the request/response envelopes on the
     * API wire, and the on-disk documents read during startup. Every
     * {@code readValue}/{@code treeToValue} target in production code should be
     * reachable from one of these.
     */
    private static final List<String> ROOTS = List.of(
        "com.claudecode.core.message.Message",
        "com.claudecode.api.CreateMessageRequest",
        "com.claudecode.api.ApiMessage",
        "com.claudecode.api.MessageDeltaData",
        "com.claudecode.core.diff.FileChangeResult",
        "com.claudecode.core.diff.StructuredPatchHunk",
        "com.claudecode.runtime.plugins.PluginMarketplacePort$MarketplaceManifest",
        "com.claudecode.services.plugins.marketplace.MarketplaceSource",
        "com.claudecode.services.plugins.marketplace.PluginManifest",
        "com.claudecode.services.insights.SessionFacets",
        "com.claudecode.tools.plan.PlanCatalogStore$Catalog",
        // On-disk entities for the to-do list / background-task persistence,
        // restored via TaskPersistence on launch and written by TaskStore.save().
        "com.claudecode.tools.tasks.Task",
        "com.claudecode.tools.tasks.TaskState");

    private static final String METADATA_PATH =
        "claude-code-app/src/main/resources/META-INF/native-image"
            + "/com.claudecode/claude-code-app/reachability-metadata.json";

    @Test
    void everyJacksonReachableTypeAllowsReflectiveConstruction() throws IOException {
        Set<String> registered = typesWithBlanketConstructorAccess();
        List<String> offenders = new ArrayList<>();
        for (Class<?> type : jacksonClosure()) {
            if (!registered.contains(type.getName())) {
                offenders.add(type.getName());
            }
        }
        assertTrue(offenders.isEmpty(), () -> """
            reachability metadata is missing blanket reflection registration for \
            Jackson-reachable types; the native image will fail at runtime with \
            MissingReflectionRegistrationError.

            Add an entry to %s for each type below:
              {"type": "<name>", "allDeclaredFields": true, \
            "allPublicMethods": true, "allDeclaredConstructors": true}

            %s""".formatted(METADATA_PATH, String.join("\n", offenders)));
    }

    /**
     * Collects the types the metadata grants unconditional constructor access.
     * Entries that enumerate individual {@code <init>} signatures are deliberately
     * not accepted — that is the shape this guard exists to eliminate.
     */
    private static Set<String> typesWithBlanketConstructorAccess() throws IOException {
        JsonNode root;
        try (var reader = Files.newBufferedReader(repositoryRoot().resolve(METADATA_PATH))) {
            root = new ObjectMapper().readTree(reader);
        }
        Set<String> registered = new LinkedHashSet<>();
        for (JsonNode entry : root.path("reflection")) {
            JsonNode type = entry.get("type");
            if (type != null && type.isTextual()
                && entry.path("allDeclaredConstructors").asBoolean(false)) {
                registered.add(type.asText());
            }
        }
        return registered;
    }

    /** Walks subtypes and serialized members from {@link #ROOTS}, keeping our own types. */
    private static Set<Class<?>> jacksonClosure() {
        Set<Class<?>> visited = new LinkedHashSet<>();
        for (String root : ROOTS) {
            try {
                visit(Class.forName(root), visited);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("closure root not on the test classpath: " + root, e);
            }
        }
        Set<Class<?>> owned = new LinkedHashSet<>();
        for (Class<?> type : visited) {
            if (Strings.CS.startsWith(type.getName(), "com.claudecode.")) {
                owned.add(type);
            }
        }
        return owned;
    }

    private static void visit(Class<?> type, Set<Class<?>> visited) {
        if (type == null || type.isPrimitive() || !visited.add(type)) {
            return;
        }
        String name = type.getName();
        // Recurse through our own types and through collection/map element types;
        // anything else (JDK leaves, third-party) contributes no further members.
        if (!Strings.CS.startsWithAny(name, "com.claudecode.", "java.util.")) {
            return;
        }
        JsonSubTypes subTypes = type.getAnnotation(JsonSubTypes.class);
        if (subTypes != null) {
            for (JsonSubTypes.Type subType : subTypes.value()) {
                visit(subType.value(), visited);
            }
        }
        JsonSerialize serialize = type.getAnnotation(JsonSerialize.class);
        if (serialize != null && serialize.using() != JsonSerializer.None.class) {
            visit(serialize.using(), visited);
        }
        JsonDeserialize deserialize = type.getAnnotation(JsonDeserialize.class);
        if (deserialize != null && deserialize.using() != JsonDeserializer.None.class) {
            visit(deserialize.using(), visited);
        }
        Class<?>[] permitted = type.getPermittedSubclasses();
        if (permitted != null) {
            for (Class<?> subclass : permitted) {
                visit(subclass, visited);
            }
        }
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                if (!isIgnored(type, component)) {
                    visitType(component.getGenericType(), visited);
                }
            }
        } else {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                    && field.getAnnotation(JsonIgnore.class) == null) {
                    visitType(field.getGenericType(), visited);
                }
            }
        }
    }

    private static void visitType(Type type, Set<Class<?>> visited) {
        switch (type) {
            case Class<?> raw when raw.isArray() -> visitType(raw.getComponentType(), visited);
            case Class<?> raw -> visit(raw, visited);
            case ParameterizedType parameterized -> {
                visitType(parameterized.getRawType(), visited);
                for (Type argument : parameterized.getActualTypeArguments()) {
                    visitType(argument, visited);
                }
            }
            case WildcardType wildcard -> {
                for (Type bound : wildcard.getUpperBounds()) {
                    visitType(bound, visited);
                }
            }
            default -> { /* type variables carry no additional binding target */ }
        }
    }

    /**
     * {@code @JsonIgnore} cannot target {@code RECORD_COMPONENT}, so a record-header
     * annotation lands on the backing field and accessor instead. All three carriers
     * must be checked or internal-only components look serialized.
     */
    private static boolean isIgnored(Class<?> owner, RecordComponent component) {
        if (component.getAnnotation(JsonIgnore.class) != null) {
            return true;
        }
        if (component.getAccessor() != null
            && component.getAccessor().getAnnotation(JsonIgnore.class) != null) {
            return true;
        }
        try {
            return owner.getDeclaredField(component.getName()).getAnnotation(JsonIgnore.class) != null;
        } catch (NoSuchFieldException _) {
            return false;
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts"))
                && Files.isDirectory(current.resolve("claude-code-core"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException(
            "cannot locate repository root from " + Path.of("").toAbsolutePath());
    }
}
