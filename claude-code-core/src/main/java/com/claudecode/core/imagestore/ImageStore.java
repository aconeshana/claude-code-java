package com.claudecode.core.imagestore;

import com.claudecode.core.message.PastedContent;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.io.FileUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;




public final class ImageStore {

    private static final String IMAGE_STORE_DIR = "image-cache";
    private static final int   MAX_STORED_IMAGE_PATHS = 200;

    private static final Map<Integer, String> STORED_PATHS = new LinkedHashMap<>();

    private ImageStore() {}

    /** Resolve {@code ~/.claude} (or {@code $CLAUDE_CONFIG_DIR}). */
    public static Path claudeConfigHome() {
        return ClaudePaths.CLAUDE_HOME;
    }

    /** {@code ~/.claude/image-cache/<sessionId>}. */
    public static Path imageStoreDir(String sessionId) {
        return claudeConfigHome().resolve(IMAGE_STORE_DIR).resolve(sessionId);
    }

    /** {@code <dir>/<id>.<ext>} — ext derived from media type, default png. */
    public static Path imagePath(String sessionId, int id, String mediaType) {
        String ext = "png";
        if (mediaType != null) {
            int slash = mediaType.indexOf('/');
            if (slash >= 0 && slash + 1 < mediaType.length()) {
                ext = mediaType.substring(slash + 1);
            }
        }
        return imageStoreDir(sessionId).resolve(id + "." + ext);
    }


    public static String cacheImagePath(PastedContent content, String sessionId) {
        if (!content.isImage()) return null;
        String path = imagePath(sessionId, content.id(),
            content.mediaType() != null ? content.mediaType() : "image/png").toString();
        synchronized (STORED_PATHS) {
            if (!STORED_PATHS.containsKey(content.id())) evictOldestIfAtCap();
            STORED_PATHS.put(content.id(), path);
        }
        return path;
    }


    private static void storeImage(PastedContent content, String sessionId) {
        if (!content.isImage()) return;
        try {
            Path path = imagePath(sessionId, content.id(),
                content.mediaType() != null ? content.mediaType() : "image/png");
            byte[] bytes = Base64.getDecoder().decode(content.content());
            FileUtils.writeBytes(path, bytes,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
            synchronized (STORED_PATHS) {
                if (!STORED_PATHS.containsKey(content.id())) evictOldestIfAtCap();
                STORED_PATHS.put(content.id(), path.toString());
            }
        } catch (IOException | IllegalArgumentException _) {
            // The image block is already retained in memory for the API request;
            // cache persistence is best effort and must not fail the turn.
        }
    }

    /**
     * Stores an image outside the prompt/request critical path.
     */
    public static void storeImageAsync(
        PastedContent content,
        String sessionId
    ) {
        cacheImagePath(content, sessionId);
        startImageStore(content, sessionId);
    }

    /**
     * Starts all image-cache writes and returns their registered paths without
     * waiting for filesystem I/O.
     */
    public static Map<Integer, String> storeImagesAsync(
        Map<Integer, PastedContent> pastedContents,
        String sessionId
    ) {
        Map<Integer, String> pathMap = new LinkedHashMap<>();
        if (pastedContents == null) return pathMap;
        for (Map.Entry<Integer, PastedContent> entry : pastedContents.entrySet()) {
            PastedContent content = entry.getValue();
            if (content != null && content.isImage()) {
                storeImageAsync(content, sessionId);
                String path = getStoredImagePath(content.id());
                if (path != null) pathMap.put(entry.getKey(), path);
            }
        }
        return pathMap;
    }

    private static void startImageStore(
        PastedContent content,
        String sessionId
    ) {
        Thread.ofVirtual().name("image-cache-write").start(() ->
            storeImage(content, sessionId));
    }

/**
     * Look up a previously stored path.
     */
    public static String getStoredImagePath(int imageId) {
        synchronized (STORED_PATHS) {
            return STORED_PATHS.get(imageId);
        }
    }

    /**
     * Clear the in-memory image-path cache.
     */
    public static void clearStoredImagePaths() {
        synchronized (STORED_PATHS) {
            STORED_PATHS.clear();
        }
    }


    public static void cleanupOldImageCaches(String currentSessionId) {
        Path base = claudeConfigHome().resolve(IMAGE_STORE_DIR);
        if (!Files.isDirectory(base)) return;
        try (var stream = Files.list(base)) {
            stream.filter(Files::isDirectory).forEach(p -> {
                String name = p.getFileName().toString();
                if (Objects.equals(name, currentSessionId)) return;
                FileUtils.deleteRecursively(p);
            });
            // Remove base dir if empty
            try (var again = Files.list(base)) {
                if (again.findAny().isEmpty()) Files.deleteIfExists(base);
            } catch (IOException _) {}
        } catch (IOException _) {}
    }

    private static void evictOldestIfAtCap() {
        while (STORED_PATHS.size() >= MAX_STORED_IMAGE_PATHS) {
            // LinkedHashMap iteration is insertion-ordered; oldest first.
            Integer oldest = STORED_PATHS.keySet().iterator().next();
            if (oldest == null) break;
            STORED_PATHS.remove(oldest);
        }
    }
}
