package com.claudecode.services.config;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.io.FileUtils;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Shared strict JSON read-modify-write protocol for editable settings files.
 */
public final class SettingsFileStore {

    private static final ConcurrentHashMap<Path, ReentrantLock> PATH_LOCKS =
        new ConcurrentHashMap<>();

    private SettingsFileStore() {}

    /**
     * Reads {@code path} as a JSON object. A missing path, empty file, or non-object JSON value
     * produces an empty object. A JSON {@code null} root is returned as an empty object for
     * inspection, but the mutation path rejects it: the original updater cannot distinguish JSON
     * {@code null} from a syntax error through {@code safeParseJSON} and therefore refuses to
     * overwrite it.
     */
    public static ObjectNode readRoot(Path path) throws IOException {
        JsonNode existing = readRawRoot(path);
        return existing != null && existing.isObject()
                ? (ObjectNode) existing
                : JsonUtils.getMapper().createObjectNode();
    }


    private static JsonNode readRawRoot(Path path) throws IOException {
        if (!targetExists(path)) {
            return JsonUtils.getMapper().createObjectNode();
        }
// parseSettingsFileUncached treats a blank settings file as an empty
        // object before invoking JSON.parse.  Keep the same read-modify-write
        // behavior here, including a UTF-8 BOM followed only by whitespace;
        // an editor should be able to populate an intentionally empty file.
        String content = Files.readString(path);
        if (StringUtils.isBlank(JsonUtils.stripBom(content))) {
            return JsonUtils.getMapper().createObjectNode();
        }
        return SettingsTreeReader.readJson(path);
    }

    /** Applies {@code edit} and atomically persists the resulting settings object. */
    public static void mutate(Path path, Consumer<ObjectNode> edit) throws IOException {
        ReentrantLock lock = lockFor(path);
        lock.lock();
        try {
        JsonNode existing = readRawRoot(path);
        // lodash.mergeWith keeps an existing array as the merge target when
        // the update object has only string keys; JSON.stringify therefore
        // writes the array back unchanged. Preserve that malformed-root
        // behavior instead of silently converting it into a settings object.
        if (existing.isArray()) {
            writeNode(path, existing);
            return;
        }
        if (existing.isNull()) {
            throw new IOException("Invalid JSON syntax in settings file at " + path);
        }
        ObjectNode root = existing.isObject()
            ? (ObjectNode) existing : JsonUtils.getMapper().createObjectNode();
        ObjectNode before = root.deepCopy();
        edit.accept(root);
        // A model-picker confirm commonly re-applies the already persisted
        // effort level.  Rewriting the whole settings file in that case adds
        // filesystem latency to the UI thread and creates a pointless watcher

        // object updates.
        if (root.equals(before)) {
            return;
        }
            writeNode(path, root);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Mutates an existing readable settings file. Returns true only when the editor reports a
     * change and the updated object has been persisted.
     */
    public static boolean mutateIfExists(Path path, Predicate<ObjectNode> edit) throws IOException {
        ReentrantLock lock = lockFor(path);
        lock.lock();
        try {
        // Missing files are a no-op for removal/update helpers; an existing unreadable target

        if (!targetExists(path)) {
            return false;
        }
        ObjectNode root = readRoot(path);
        if (!edit.test(root)) {
            return false;
        }
            writeNode(path, root);
        return true;
        } finally {
            lock.unlock();
        }
    }

    /** Marks the target as internal and atomically writes pretty JSON. */
    public static void write(Path path, ObjectNode root) throws IOException {
        ReentrantLock lock = lockFor(path);
        lock.lock();
        try {
        writeNode(path, root);
        } finally {
            lock.unlock();
        }
    }

    private static ReentrantLock lockFor(Path path) {
        Path key = path.toAbsolutePath().normalize();
        return PATH_LOCKS.computeIfAbsent(key, _ -> new ReentrantLock());
    }

    private static void writeNode(Path path, JsonNode root) throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent != null) {
// updateSettingsForSource creates the settings directory before
            // the first write.  Keep the shared writer safe for a fresh
            // project/user config as well as for an existing file.
            Files.createDirectories(parent);
        }
        InternalWrites.markInternalWrite(path);

        // the pretty JSON document with a single LF. Keep that byte-level
        // convention for settings files while retaining the shared atomic
        // replacement protocol.
        FileUtils.atomicReplace(path, temp ->
            FileUtils.writeString(temp, JsonUtils.toPrettyJson(root) + "\n"));

        // and diagnostic engine is the single authoritative invalidation boundary.
        SettingsSnapshots.invalidateForReload();
    }

    /** Follows the final link and distinguishes a true ENOENT from other read failures. */
    private static boolean targetExists(Path path) throws IOException {
        try {
            Files.readAttributes(path, BasicFileAttributes.class);
            return true;
        } catch (NoSuchFileException _) {
            return false;
        }
    }
}
