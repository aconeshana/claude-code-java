package com.claudecode.core.paste;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.io.FileUtils;
import com.claudecode.core.util.HashUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;


public final class PasteStore {

    private static final int MAX_MEMORY_FALLBACK_CHARS = 10_000_000;

    private static final Map<String, String> pendingWrites = new ConcurrentHashMap<>();

    private static final LinkedHashMap<String, String> memoryFallback = new LinkedHashMap<>();
    private static int memoryFallbackChars;

    private PasteStore() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Computes the storage key for a paste.
     */
    public static String hashPastedText(String content) {
        return HashUtils.hashContent(content).substring(0, 16);
    }

    /**
     * Stores content at {@code paste-cache/{hash}.txt}.
     */
    public static void storePastedText(String hash, String content) {
        pendingWrites.put(hash, content);
        writeStagedPastedText(hash, content, getPasteStoreDir());
    }

    /**
     * Stages content synchronously, then writes it on a virtual thread.
     */
    public static void storePastedTextAsync(String hash, String content) {
        storePastedTextAsync(hash, content, getPasteStoreDir(), command ->
            Thread.ofVirtual().name("paste-store-write").start(command));
    }

    static void storePastedTextAsync(
            String hash, String content, Path directory, Executor executor) {
        pendingWrites.put(hash, content);
        executor.execute(() -> writeStagedPastedText(hash, content, directory));
    }

    private static void writeStagedPastedText(String hash, String content, Path directory) {
        try {
            Path path = directory.resolve(hash + ".txt");
            FileUtils.writeString(path, content, StandardCharsets.UTF_8);
            try {
                Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
                Files.setPosixFilePermissions(path, perms);
            } catch (UnsupportedOperationException _) { /* Windows */ }
            pendingWrites.remove(hash, content);
            removeMemoryFallback(hash);
        } catch (Exception _) {
            pendingWrites.remove(hash, content);
            addMemoryFallback(hash, content);
        }
    }

    /**
     * Retrieves stored content by hash.
     */
    public static String retrievePastedText(String hash) {
        return retrievePastedText(hash, getPasteStoreDir());
    }

    static String retrievePastedText(String hash, Path directory) {
        String pending = pendingWrites.get(hash);
        if (pending != null) return pending;
        synchronized (memoryFallback) {
            String cached = memoryFallback.get(hash);
            if (cached != null) return cached;
        }
        try {
            Path path = directory.resolve(hash + ".txt");
            if (!Files.isRegularFile(path)) return null;
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException _) {
            return null;
        }
    }

    private static void addMemoryFallback(String hash, String content) {
        synchronized (memoryFallback) {
            String previous = memoryFallback.remove(hash);
            if (previous != null) memoryFallbackChars -= previous.length();
            if (content.length() > MAX_MEMORY_FALLBACK_CHARS) return;
            memoryFallback.put(hash, content);
            memoryFallbackChars += content.length();
            while (memoryFallbackChars > MAX_MEMORY_FALLBACK_CHARS) {
                var iterator = memoryFallback.entrySet().iterator();
                if (!iterator.hasNext()) break;
                Map.Entry<String, String> eldest = iterator.next();
                memoryFallbackChars -= eldest.getValue().length();
                iterator.remove();
            }
        }
    }

    private static void removeMemoryFallback(String hash) {
        synchronized (memoryFallback) {
            String previous = memoryFallback.remove(hash);
            if (previous != null) memoryFallbackChars -= previous.length();
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private static Path getPasteStoreDir() {
        return ClaudePaths.PASTE_STORE_DIR;
    }
}
