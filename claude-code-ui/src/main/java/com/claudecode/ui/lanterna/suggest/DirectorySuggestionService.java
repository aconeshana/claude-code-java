package com.claudecode.ui.lanterna.suggest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.commons.lang3.Strings;

/**
 * Builds directory/path completions for path-like tokens (starts with {@code ~/}, {@code /},
 * {@code./}, or {@code../}).
 */
public final class DirectorySuggestionService {


    private static final int MAX_RESULTS = 10;
    private static final int MAX_CACHE_ENTRIES = 500;
    private static final int MAX_SCANNED_ENTRIES = 100;
    private static final long CACHE_TTL_NANOS = Duration.ofMinutes(5).toNanos();

    private record PathEntry(String name, boolean directory) {}
    private record CachedDirectory(List<PathEntry> entries, long loadedAtNanos) {}

    private final Map<Path, CachedDirectory> pathCache =
        new LinkedHashMap<>(32, 0.75f, true) {
            @Override protected boolean removeEldestEntry(
                    Map.Entry<Path, CachedDirectory> eldest) {
                return size() > MAX_CACHE_ENTRIES;
            }
        };

    public List<SuggestionPanel.Suggestion> build(String pathToken) {
        List<SuggestionPanel.Suggestion> result = new ArrayList<>();
        try {
            String home = System.getProperty("user.home", "");
            boolean homeRelative = Strings.CS.equals(pathToken, "~")
                || Strings.CS.startsWith(pathToken, "~/");
            String resolved = Strings.CS.equals(pathToken, "~")
                ? home
                : Strings.CS.startsWith(pathToken, "~/")
                    ? home + pathToken.substring(1)
                    : pathToken;

            Path base;
            String prefix;
            int lastSep = resolved.lastIndexOf('/');
            if (lastSep >= 0) {
                base = Path.of(resolved.substring(0, lastSep + 1));
                prefix = resolved.substring(lastSep + 1).toLowerCase(Locale.ROOT);
            } else {
                base = Path.of(resolved);
                prefix = "";
            }
            if (!Files.isDirectory(base)) return result;

            Path homePath = Path.of(home);
            scanDirectory(base).stream()
                .filter(entry -> Strings.CS.startsWith(
                    entry.name().toLowerCase(Locale.ROOT), prefix))
                .limit(MAX_RESULTS)
                .forEach(entry -> {
                    Path entryPath = base.resolve(entry.name());
                    String display = homeRelative
                        ? "~/" + homePath.relativize(entryPath)
                        : entryPath.toString();
                    if (entry.directory()) display += "/";
                    result.add(SuggestionPanel.Suggestion.path(display));
                });
        } catch (Exception _) {}
        return result;
    }

    private List<PathEntry> scanDirectory(Path base) {
        Path cacheKey = base.toAbsolutePath().normalize();
        long now = System.nanoTime();
        synchronized (pathCache) {
            CachedDirectory cached = pathCache.get(cacheKey);
            if (cached != null && now - cached.loadedAtNanos() < CACHE_TTL_NANOS) {
                return cached.entries();
            }
            if (cached != null) pathCache.remove(cacheKey);
        }

        List<PathEntry> entries;
        try (Stream<Path> stream = Files.list(base)) {
            entries = stream
                .filter(path -> !Strings.CS.startsWith(path.getFileName().toString(), "."))
                .map(path -> new PathEntry(
                    path.getFileName().toString(), Files.isDirectory(path)))
                .sorted((left, right) -> {
                    if (left.directory() != right.directory()) {
                        return left.directory() ? -1 : 1;
                    }
                    return left.name().compareTo(right.name());
                })
                .limit(MAX_SCANNED_ENTRIES)
                .toList();
        } catch (Exception _) {
            return List.of();
        }

        List<PathEntry> snapshot = List.copyOf(entries);
        synchronized (pathCache) {
            pathCache.put(cacheKey, new CachedDirectory(snapshot, now));
        }
        return snapshot;
    }


    public void clearCache() {
        synchronized (pathCache) {
            pathCache.clear();
        }
    }
}
