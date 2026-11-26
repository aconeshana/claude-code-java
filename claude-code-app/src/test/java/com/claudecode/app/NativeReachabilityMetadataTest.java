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
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 *
 * <p>Two invariants are checked, because registering a type does not register
 * its array class. Jackson materialises {@code X[]} while resolving generic
 * container members — {@code ArrayType.construct} calls
 * {@code Array.newInstance(X, 0)} — so a {@code List<X>} needs both {@code X}
 * and {@code X[]}. That second half was maintained by hand until a
 * {@code List<TodoItem>} reached the context-timeline fold in a native binary
 * and threw {@code MissingReflectionRegistrationError} for
 * {@code TodoItem[]}; the four array entries that predate this guard are
 * exactly the ones someone had already tripped over.
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
        "com.claudecode.api.Delta",
        "com.claudecode.api.MessageDeltaData",
        "com.claudecode.core.diff.FileChangeResult",
        "com.claudecode.core.diff.StructuredPatchHunk",
        "com.claudecode.runtime.plugins.PluginMarketplacePort$MarketplaceManifest",
        "com.claudecode.services.plugins.marketplace.MarketplaceSource",
        "com.claudecode.services.plugins.marketplace.PluginManifest",
        "com.claudecode.services.insights.SessionFacets",
        "com.claudecode.tools.plan.PlanCatalogStore$Catalog",
        // The two on-disk records behind `claude web`: the gateway publishes its
        // own endpoint in WebGatewayRecord, and each interactive process publishes
        // a WebWorkerRegistration for the supervisor to discover. Both are read and
        // written through JsonUtils, and both were missing here — which is why
        // `claude web -d` failed in every published native build even though the
        // JVM suite was green. everyDeserializationTargetIsReachableFromARoot now
        // derives this requirement from the call sites instead of trusting the list.
        "com.claudecode.cli.WebGatewayRecord",
        "com.claudecode.runtime.webworker.WebWorkerRegistration",
        // On-disk entities for the to-do list / background-task persistence,
        // restored via TaskPersistence on launch and written by TaskStore.save().
        "com.claudecode.tools.tasks.Task",
        "com.claudecode.tools.tasks.TaskState",
        // ObjectMapper.readValue targets across custom models, plugins, insights,
        // session caches, and bundled resources.
        "com.claudecode.api.CustomModelJsonStore$CatalogDocument",
        "com.claudecode.services.insights.SessionMeta",
        "com.claudecode.services.plugins.marketplace.FlaggedPluginStore$FileData",
        "com.claudecode.services.plugins.marketplace.InstalledPlugins",
        "com.claudecode.services.plugins.marketplace.KnownMarketplaces",
        "com.claudecode.services.plugins.marketplace.MarketplaceManifest",
        "com.claudecode.services.plugins.runtime.McpbBundleLoader$CacheMetadata",
        "com.claudecode.session.ProjectIndexSnapshot",
        "com.claudecode.session.stats.StatsCacheStore$PersistedStatsCache",
        "com.claudecode.tools.bundled.BundledResourceCatalog$Manifest");

    private static final String METADATA_PATH =
        "claude-code-app/src/main/resources/META-INF/native-image"
            + "/com.claudecode/claude-code-app/reachability-metadata.json";

    @Test
    void everyJacksonReachableTypeAllowsReflectiveConstruction() throws IOException {
        Set<String> registered = typesWithBlanketReflectionAccess();
        List<String> offenders = new ArrayList<>();
        for (Class<?> type : jacksonClosure().types()) {
            if (!registered.contains(type.getName())) {
                offenders.add(type.getName());
            }
        }
        assertTrue(offenders.isEmpty(), () -> """
            reachability metadata is missing blanket reflection registration \
            (allDeclaredConstructors + allPublicMethods) for Jackson-reachable \
            types; the native image will fail at runtime with \
            MissingReflectionRegistrationError.

            Add an entry to %s for each type below:
              {"type": "<name>", "allDeclaredFields": true, \
            "allPublicMethods": true, "allDeclaredConstructors": true}

            %s""".formatted(METADATA_PATH, String.join("\n", offenders)));
    }

    /**
     * Requires the array class of every collection element and array component
     * in the closure. Only the {@code type} entry is needed — an array class has
     * no constructors to reflect over, and {@code Array.newInstance} is
     * permitted by the registration alone.
     */
    @Test
    void everyCollectionElementTypeAllowsReflectiveArrayCreation() throws IOException {
        Set<String> registered = registeredTypeNames();
        List<String> offenders = new ArrayList<>();
        for (Class<?> element : jacksonClosure().elements()) {
            String arrayType = element.getName() + "[]";
            if (!registered.contains(arrayType)) {
                offenders.add(arrayType);
            }
        }
        assertTrue(offenders.isEmpty(), () -> """
            reachability metadata is missing the array class of a Jackson \
            collection element; the native image will fail at runtime with \
            MissingReflectionRegistrationError the first time Jackson resolves \
            the enclosing member.

            Add an entry to %s for each type below:
              {"type": "<name>"}

            %s""".formatted(METADATA_PATH, String.join("\n", offenders)));
    }

    /**
     * Requires {@link #ROOTS} to keep up with the production code that deserializes
     * into a concrete type.
     *
     * <p>Every other test here walks the closure hanging off {@code ROOTS}, so a type
     * that is neither a root nor reachable from one is simply never visited — the
     * guards pass while the native binary throws
     * {@code MissingReflectionRegistrationError} in production. That is exactly how
     * {@code WebGatewayRecord} escaped: {@code claude web -d} serializes it while
     * publishing the gateway record, and it was in no root's closure, so
     * {@code claude web -d} failed in every published native build while the full
     * JVM suite stayed green.
     *
     * <p>The scan reads the call sites rather than trusting the list, because the
     * list is what drifted. A shape it does not understand is reported as
     * unverifiable instead of silently skipped, so the check cannot quietly lose
     * coverage the way the hand-maintained list did.
     */
    @Test
    void everyDeserializationTargetIsReachableFromARoot() throws IOException {
        Set<Class<?>> closure = jacksonClosure().types();
        List<String> unreachable = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (DeserializationSite site : deserializationSites()) {
            Class<?> target = site.target();
            if (target == null) {
                unresolved.add(site.describe());
            } else if (Strings.CS.startsWith(target.getName(), "com.claudecode.")
                    && !closure.contains(target)) {
                unreachable.add(site.describe());
            }
        }
        assertTrue(unresolved.isEmpty(), () -> """
            could not resolve the target type of these deserialization call sites, \
            so their native reachability is unverified. Resolve them or extend the \
            scan in %s; leaving them unread would be the same blind spot as a \
            hand-maintained root list.

            %s""".formatted(NativeReachabilityMetadataTest.class.getSimpleName(),
                String.join("\n", unresolved)));
        assertTrue(unreachable.isEmpty(), () -> """
            these types are deserialized in production but cannot be reached from \
            the Jackson closure in %s, so their native reflection metadata is never \
            checked. Add each to ROOTS (and register it), or the native image will \
            fail at runtime with MissingReflectionRegistrationError.

            %s""".formatted("ROOTS", String.join("\n", unreachable)));
    }

    /** One {@code readJson(file, X.class)} / {@code fromJson(text, X.class)} call site. */
    private record DeserializationSite(Path source, int line, String rawType, Class<?> target) {
        String describe() {
            String where = repositoryRoot().relativize(source.toAbsolutePath()) + ":" + line;
            return target == null ? where + " -> " + rawType : target.getName() + " (" + where + ")";
        }
    }

    /**
     * Matches the deserializing overloads of {@code JsonUtils} and {@code ObjectMapper}
     * and captures their type literal. The target is written as {@code Something.class}
     * at the call site, which is what makes this statically extractable (including
     * multi-line {@code readValue(...,\n Something.class)} calls).
     */
    private static final Pattern DESERIALIZE_CALL = Pattern.compile(
        "\\b(?:readJson|fromJson|readJsonList|treeToValue|readValue)\\s*\\([^;]*?,\\s*([A-Za-z_$][\\w.$]*)\\.class\\s*\\)");

    private static List<DeserializationSite> deserializationSites() throws IOException {
        List<DeserializationSite> sites = new ArrayList<>();
        Path root = repositoryRoot();
        for (Path module : moduleSourceRoots(root)) {
            try (var paths = Files.walk(module)) {
                for (Path source : paths.filter(Files::isRegularFile)
                        .filter(path -> Strings.CS.endsWith(path.toString(), ".java"))
                        .toList()) {
                    collectSites(source, sites);
                }
            }
        }
        return sites;
    }

    private static void collectSites(Path source, List<DeserializationSite> sites) throws IOException {
        String body = Files.readString(source);
        Matcher matcher = DESERIALIZE_CALL.matcher(body);
        while (matcher.find()) {
            String raw = matcher.group(1);
            int line = lineNumberAt(body, matcher.start());
            sites.add(new DeserializationSite(source, line, raw, resolveType(source, body, raw)));
        }
    }

    private static int lineNumberAt(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }

    /**
     * Resolves a source-level type name against the file's imports, enclosing class, and
     * package. Only names that resolve to a class on the test classpath are returned;
     * anything else comes back null so the caller can report it rather than assume it is fine.
     */
    private static Class<?> resolveType(Path source, String body, String raw) {
        try {
            if (Strings.CS.contains(raw, ".")) {
                try {
                    return Class.forName(raw);
                } catch (ClassNotFoundException _) {
                    // Could be Outer.Inner imported or in the same package.
                }
            }
            if (Strings.CS.equals("Object", raw)) {
                return Object.class;
            }
            String pkg = Pattern.compile("(?m)^package\\s+([\\w.]+);")
                .matcher(body).results().findFirst()
                .map(match -> match.group(1)).orElse(null);
            String fileClass = Strings.CS.removeEnd(source.getFileName().toString(), ".java");
            if (pkg != null) {
                // Nested type declared inside the current source file (e.g. CatalogDocument inside CustomModelJsonStore).
                try {
                    return Class.forName(pkg + "." + fileClass + "$" + raw.replace('.', '$'));
                } catch (ClassNotFoundException _) {
                    // Fall through to same-package lookup.
                }
                try {
                    return Class.forName(pkg + "." + raw.replace('.', '$'));
                } catch (ClassNotFoundException _) {
                    // Fall through to the import scan.
                }
            }
            String head = Strings.CS.contains(raw, ".") ? raw.substring(0, raw.indexOf('.')) : raw;
            String tail = Strings.CS.contains(raw, ".") ? "$" + raw.substring(raw.indexOf('.') + 1).replace('.', '$') : "";
            var importPattern = Pattern.compile(
                "(?m)^import\\s+(?:static\\s+)?[\\w.]*\\b" + Pattern.quote(head) + ";");
            var imported = importPattern.matcher(body).results().findFirst();
            if (imported.isPresent()) {
                String fqn = imported.get().group().replaceFirst("^import\\s+(?:static\\s+)?", "")
                    .replace(";", "").trim();
                return Class.forName(fqn + tail);
            }
            return null;
        } catch (ClassNotFoundException _) {
            return null;
        }
    }

    /** Every product module's {@code src/main/java}, so the scan cannot be narrowed by accident. */
    private static List<Path> moduleSourceRoots(Path root) throws IOException {
        List<Path> roots = new ArrayList<>();
        try (var modules = Files.list(root)) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                Path sources = module.resolve("src/main/java");
                if (Files.isDirectory(sources)) roots.add(sources);
            }
        }
        return roots;
    }

    /**
     * Collects the types the metadata grants unconditional constructor and public
     * method access. Entries that enumerate individual {@code <init>} or method
     * signatures (such as an enum registering {@code fromWire} but omitting
     * {@code @JsonValue wire()}) are deliberately not accepted — that is the shape
     * this guard exists to eliminate.
     */
    private static Set<String> typesWithBlanketReflectionAccess() throws IOException {
        Set<String> registered = new LinkedHashSet<>();
        for (JsonNode entry : reflectionEntries()) {
            JsonNode type = entry.get("type");
            if (type != null && type.isTextual()
                && entry.path("allDeclaredConstructors").asBoolean(false)
                && entry.path("allPublicMethods").asBoolean(false)) {
                registered.add(type.asText());
            }
        }
        return registered;
    }

    /** Every registered type name, whatever access the entry grants. */
    private static Set<String> registeredTypeNames() throws IOException {
        Set<String> registered = new LinkedHashSet<>();
        for (JsonNode entry : reflectionEntries()) {
            JsonNode type = entry.get("type");
            if (type != null && type.isTextual()) {
                registered.add(type.asText());
            }
        }
        return registered;
    }

    private static JsonNode reflectionEntries() throws IOException {
        JsonNode root;
        try (var reader = Files.newBufferedReader(repositoryRoot().resolve(METADATA_PATH))) {
            root = new ObjectMapper().readTree(reader);
        }
        return root.path("reflection");
    }

    /**
     * The walked type closure. {@code elements} is the subset that Jackson also
     * needs an array class for: types reached through a collection or map type
     * argument, or as an array component.
     */
    private record Closure(Set<Class<?>> types, Set<Class<?>> elements) {
        static Closure empty() {
            return new Closure(new LinkedHashSet<>(), new LinkedHashSet<>());
        }

        /** The same closure narrowed to our own types; JDK and third-party leaves are theirs to register. */
        Closure owned() {
            return new Closure(owned(types), owned(elements));
        }

        private static Set<Class<?>> owned(Set<Class<?>> all) {
            Set<Class<?>> kept = new LinkedHashSet<>();
            for (Class<?> type : all) {
                if (Strings.CS.startsWith(type.getName(), "com.claudecode.")) {
                    kept.add(type);
                }
            }
            return kept;
        }
    }

    /** Walks subtypes and serialized members from {@link #ROOTS}, keeping our own types. */
    private static Closure jacksonClosure() {
        Closure closure = Closure.empty();
        for (String root : ROOTS) {
            try {
                visit(Class.forName(root), closure);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("closure root not on the test classpath: " + root, e);
            }
        }
        return closure.owned();
    }

    private static void visit(Class<?> type, Closure closure) {
        if (type == null || type.isPrimitive() || !closure.types().add(type)) {
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
                visit(subType.value(), closure);
            }
        }
        JsonSerialize serialize = type.getAnnotation(JsonSerialize.class);
        if (serialize != null && serialize.using() != JsonSerializer.None.class) {
            visit(serialize.using(), closure);
        }
        JsonDeserialize deserialize = type.getAnnotation(JsonDeserialize.class);
        if (deserialize != null && deserialize.using() != JsonDeserializer.None.class) {
            visit(deserialize.using(), closure);
        }
        Class<?>[] permitted = type.getPermittedSubclasses();
        if (permitted != null) {
            for (Class<?> subclass : permitted) {
                visit(subclass, closure);
            }
        }
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                if (!isIgnored(type, component)) {
                    visitType(component.getGenericType(), closure);
                }
            }
        } else {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                    && field.getAnnotation(JsonIgnore.class) == null) {
                    visitType(field.getGenericType(), closure);
                }
            }
        }
    }

    private static void visitType(Type type, Closure closure) {
        switch (type) {
            case Class<?> raw when raw.isArray() -> {
                recordElement(raw.getComponentType(), closure);
                visitType(raw.getComponentType(), closure);
            }
            case Class<?> raw -> visit(raw, closure);
            case ParameterizedType parameterized -> {
                visitType(parameterized.getRawType(), closure);
                boolean container = parameterized.getRawType() instanceof Class<?> raw
                    && (Collection.class.isAssignableFrom(raw) || Map.class.isAssignableFrom(raw));
                for (Type argument : parameterized.getActualTypeArguments()) {
                    if (container) {
                        recordElement(argument, closure);
                    }
                    visitType(argument, closure);
                }
            }
            case WildcardType wildcard -> {
                for (Type bound : wildcard.getUpperBounds()) {
                    visitType(bound, closure);
                }
            }
            default -> { /* type variables carry no additional binding target */ }
        }
    }

    /**
     * Notes that Jackson will need {@code type[]}. A nested container
     * ({@code List<List<X>>}) contributes its raw type here and its own
     * argument on the recursive visit.
     */
    private static void recordElement(Type type, Closure closure) {
        switch (type) {
            case Class<?> raw when !raw.isPrimitive() -> closure.elements().add(raw);
            case ParameterizedType parameterized -> recordElement(parameterized.getRawType(), closure);
            case WildcardType wildcard -> {
                for (Type bound : wildcard.getUpperBounds()) {
                    recordElement(bound, closure);
                }
            }
            default -> { /* primitives and type variables need no array class */ }
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
