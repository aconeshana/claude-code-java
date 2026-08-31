package com.claudecode.session;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.text.XmlTagUtils;
import com.claudecode.core.util.UuidUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.function.Predicate;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Two-phase, bounded session discovery shared by picker, search and SDK listing. */
final class SessionCatalog {
    private static final Logger log = LoggerFactory.getLogger(SessionCatalog.class);
    private static final int IO_CONCURRENCY = 32;
    private static final Set<String> SDK_ENTRYPOINTS = Set.of("sdk-cli", "sdk-ts", "sdk-py");
    private static final ExecutorService IO = Executors.newVirtualThreadPerTaskExecutor();
    private static final Semaphore IO_PERMITS = new Semaphore(IO_CONCURRENCY);
    private static final ArrayBlockingQueue<byte[]> BUFFERS = new ArrayBlockingQueue<>(IO_CONCURRENCY);
    private static volatile IoObserver ioObserver = IoObserver.NONE;

    static {
        for (int i = 0; i < IO_CONCURRENCY; i++) BUFFERS.add(new byte[LiteSessionReader.LITE_READ_BYTES]);
    }

    private SessionCatalog() {}
    static int ioConcurrencyLimit() { return IO_CONCURRENCY; }
    static AutoCloseable observeIoForTest(IoObserver observer) {
        IoObserver previous = ioObserver;
        ioObserver = observer == null ? IoObserver.NONE : observer;
        return () -> ioObserver = previous;
    }

    static Listing forProject(SessionManager manager, Predicate<String> builtInCommand) {
        return new Listing(deduplicate(candidates(List.of(new Source(
            manager.projectDirectory(), manager.projectPath(), false)))), builtInCommand,
            Visibility.PICKER);
    }

    static Listing forManagers(List<SessionManager> managers, Predicate<String> builtInCommand) {
        List<Source> sources = managerSources(managers);
        List<Candidate> discovered = candidates(sources);
        if (discovered.isEmpty()) discovered = candidates(historicalSources(managers));
        return new Listing(deduplicate(discovered), builtInCommand, Visibility.PICKER);
    }

    static List<Entry> searchEntries(List<SessionManager> managers, Predicate<String> builtInCommand) {
        List<Candidate> discovered = candidates(managerSources(managers));
        if (discovered.isEmpty()) discovered = candidates(historicalSources(managers));
        return parallelMap(discovered, candidate -> read(candidate)
            .flatMap(lite -> enrich(candidate, lite, builtInCommand, Visibility.PICKER)).orElse(null))
            .stream().filter(Objects::nonNull).toList();
    }

    private static List<Source> managerSources(List<SessionManager> managers) {
        List<Source> sources = new ArrayList<>();
        if (managers != null) for (SessionManager manager : managers) {
            if (manager == null) continue;
            for (Path directory : manager.compatibleProjectDirectories()) {
                sources.add(new Source(directory, manager.projectPath(), false));
            }
            for (Path alias : manager.readSessionAliases()) {
                sources.add(new Source(alias, manager.projectPath(), true));
            }
        }
        return sources;
    }

    private static List<Source> historicalSources(List<SessionManager> managers) {
        if (managers == null || managers.isEmpty()) return List.of();
        SessionManager root = managers.getFirst();
        List<String> targets = managers.stream().map(SessionManager::projectPath).toList();
        boolean allowDescendants = targets.size() > 1;
        List<Source> matches = new ArrayList<>();
        for (Source source : allProjectSources(root)) {
            List<Candidate> files = candidates(List.of(source));
            if (files.isEmpty()) continue;
            Candidate newest = files.getFirst();
            Optional<LiteSessionReader.LiteSessionFile> lite = read(newest);
            if (lite.isEmpty()) continue;
            String relocated = lastTypedString(lite.get().tail(), "relocated", "relocatedCwd");
            String recorded = firstNonBlank(relocated, firstString(lite.get().head(), "cwd"));
            if (recorded == null) continue;
            String canonical = SessionManager.canonicalizePath(recorded);
            targets.stream().filter(target -> canonical.equals(target)
                || allowDescendants && Strings.CS.startsWith(canonical, target + File.separator))
                .findFirst().ifPresent(matchedValue -> matches.add(new Source(source.directory(), matchedValue, false)));
        }
        return matches;
    }

    static Listing forAllProjects(SessionManager manager, Predicate<String> builtInCommand) {
        return new Listing(deduplicate(candidates(allProjectSources(manager))), builtInCommand,
            Visibility.PICKER);
    }

    static List<Candidate> sdkCandidates(SessionManager manager, String dir,
                                         boolean includeWorktrees, boolean statFirst,
                                         List<String> worktreePaths) {
        List<Source> sources;
        List<SessionManager> requestedManagers = List.of();
        if (StringUtils.isBlank(dir)) sources = allProjectSources(manager);
        else {
            Path home = manager.projectsRoot().getParent();
            SessionManager requested = new SessionManager(home, dir);
            List<SessionManager> mutableManagers = new ArrayList<>();
            mutableManagers.add(requested);
            if (includeWorktrees) for (String path : worktreePaths == null ? List.<String>of() : worktreePaths) {
                if (!path.equals(requested.projectPath())) mutableManagers.add(new SessionManager(home, path));
            }
            requestedManagers = List.copyOf(mutableManagers);
            List<Source> direct = new ArrayList<>();
            for (SessionManager item : requestedManagers) {
                for (Path directory : item.compatibleProjectDirectories()) {
                    direct.add(new Source(directory, item.projectPath(), false));
                }
                for (Path alias : item.readSessionAliases()) direct.add(new Source(alias, item.projectPath(), true));
            }
            sources = direct;
        }
        List<Candidate> result = statFirst ? candidates(sources) : filenames(sources);
        if (result.isEmpty() && !requestedManagers.isEmpty()) {
            List<Source> fallback = historicalSources(requestedManagers);
            result = statFirst ? candidates(fallback) : filenames(fallback);
        }
        return result;
    }

    static Optional<Entry> enrichSdk(Candidate candidate, Predicate<String> builtIn,
                                     boolean includeProgrammatic) {
        return read(candidate).flatMap(lite -> enrich(candidate, lite, builtIn,
            includeProgrammatic ? Visibility.SDK_ALL : Visibility.SDK_INTERACTIVE));
    }

    static List<Entry> enrichSdkBatch(List<Candidate> candidates, Predicate<String> builtIn,
                                      boolean includeProgrammatic) {
        return parallelMap(candidates, candidate -> enrichSdk(candidate, builtIn,
            includeProgrammatic).orElse(null)).stream().filter(Objects::nonNull).toList();
    }

    private static List<Source> allProjectSources(SessionManager manager) {
        List<Source> result = new ArrayList<>();
        Path root = manager.projectsRoot();
        if (!Files.isDirectory(root)) return result;
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(root, Files::isDirectory)) {
            for (Path directory : directories) result.add(new Source(directory,
                directory.equals(manager.projectDirectory()) ? manager.projectPath() : null, false));
        } catch (IOException _) { return List.of(); }
        return result;
    }

    private static List<Candidate> candidates(List<Source> sources) {
        List<Candidate> result = parallelMap(filenames(sources), candidate -> {
            try {
                BasicFileAttributes attributes = Files.readAttributes(candidate.transcript(), BasicFileAttributes.class);
                return candidate.withStat(attributes.lastModifiedTime().toMillis(),
                    attributes.creationTime().toMillis(), attributes.size());
            } catch (IOException failure) {
                log.debug("Unable to stat session transcript {}", candidate.transcript(), failure);
                return null;
            }
        }).stream().filter(Objects::nonNull).toList();
        List<Candidate> sorted = new ArrayList<>(result);
        sorted.sort(Candidate.ORDER);
        return List.copyOf(sorted);
    }

    private static List<Candidate> filenames(List<Source> sources) {
        List<Candidate> result = new ArrayList<>();
        for (Source source : sources == null ? List.<Source>of() : sources) {
            if (!Files.isDirectory(source.directory())) continue;
            try (DirectoryStream<Path> files = Files.newDirectoryStream(source.directory(), "*.jsonl")) {
                for (Path transcript : files) {
                    String filename = transcript.getFileName().toString();
                    String id = filename.substring(0, filename.length() - ".jsonl".length());
                    if (UuidUtils.isValid(id)) result.add(new Candidate(id, transcript, source.projectPath(),
                        0, 0, 0, source.alias()));
                }
            } catch (IOException failure) {
                log.debug("Unable to enumerate session directory {}", source.directory(), failure);
            }
        }
        return result;
    }

    private static List<Candidate> deduplicate(List<Candidate> candidates) {
        List<Candidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Candidate.ORDER);
        Map<String, Candidate> newest = new LinkedHashMap<>();
        for (Candidate candidate : sorted) newest.putIfAbsent(candidate.sessionId(), candidate);
        return List.copyOf(newest.values());
    }

    record Source(Path directory, String projectPath, boolean alias) {}
    record Entry(SessionInfo info, Path transcript, String projectPath, String aiTitle, boolean alias) {}

    static final class Listing {
        private final List<Candidate> candidates;
        private final Predicate<String> builtInCommand;
        private final Visibility visibility;
        private final ArrayDeque<Entry> ready = new ArrayDeque<>();
        private int nextIndex;

        Listing(List<Candidate> candidates, Predicate<String> builtInCommand, Visibility visibility) {
            this.candidates = List.copyOf(candidates == null ? List.of() : candidates);
            this.builtInCommand = builtInCommand == null ? _ -> false : builtInCommand;
            this.visibility = visibility;
        }

        synchronized List<Entry> loadMore(int count) {
            if (count <= 0) return List.of();
            List<Entry> result = new ArrayList<>(Math.min(count,
                Math.max(0, candidates.size() - nextIndex + ready.size())));
            drainReady(result, count);
            while (result.size() < count && nextIndex < candidates.size()) {
                int end = Math.min(candidates.size(), nextIndex + IO_CONCURRENCY);
                List<Candidate> batch = candidates.subList(nextIndex, end);
                nextIndex = end;
                for (Entry entry : parallelMap(batch, candidate -> read(candidate)
                        .flatMap(lite -> enrich(candidate, lite, builtInCommand, visibility)).orElse(null))) {
                    if (entry != null) ready.addLast(entry);
                }
                drainReady(result, count);
            }
            return List.copyOf(result);
        }

        private void drainReady(List<Entry> result, int count) {
            while (result.size() < count && !ready.isEmpty()) result.add(ready.removeFirst());
        }
        synchronized boolean hasMore() { return !ready.isEmpty() || nextIndex < candidates.size(); }
        synchronized int nextIndex() { return nextIndex; }
    }

    private static Optional<LiteSessionReader.LiteSessionFile> read(Candidate candidate) {
        byte[] buffer = null;
        try {
            buffer = BUFFERS.take();
            Optional<LiteSessionReader.LiteSessionFile> loaded =
                new LiteSessionReader().read(candidate.transcript(), candidate.fileSize(), buffer);
            if (loaded.isEmpty()) {
                log.debug("Unable to read session transcript {} ({} bytes)",
                    candidate.transcript(), candidate.fileSize());
            }
            return loaded;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            log.debug("Session transcript read interrupted for {}", candidate.transcript());
            return Optional.empty();
        } finally {
            if (buffer != null) BUFFERS.offer(buffer);
        }
    }

    private static <T, R> List<R> parallelMap(List<T> input, Function<T, R> mapper) {
        if (input.isEmpty()) return List.of();
        List<R> result = new ArrayList<>(input.size());
        for (int start = 0; start < input.size(); start += IO_CONCURRENCY) {
            int end = Math.min(input.size(), start + IO_CONCURRENCY);
            List<Future<R>> futures = new ArrayList<>(end - start);
            for (T item : input.subList(start, end)) futures.add(IO.submit(() -> {
                boolean acquired = false;
                boolean started = false;
                IoObserver observer = ioObserver;
                try {
                    IO_PERMITS.acquire();
                    acquired = true;
                    observer.started();
                    started = true;
                    return mapper.apply(item);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    return null;
                } finally {
                    if (started) observer.finished();
                    if (acquired) IO_PERMITS.release();
                }
            }));
            for (Future<R> future : futures) {
                try { result.add(future.get()); }
                catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    log.debug("Session catalog join interrupted");
                    result.add(null);
                }
                catch (ExecutionException failure) {
                    log.debug("Session catalog worker failed", failure.getCause());
                    result.add(null);
                }
            }
        }
        return result;
    }

    private static Optional<Entry> enrich(Candidate candidate, LiteSessionReader.LiteSessionFile lite,
                                          Predicate<String> builtInCommand, Visibility visibility) {
        String head = lite.head();
        String tail = lite.tail();
        if (containsBoolean(firstLine(head), "isSidechain", true)) return Optional.empty();
        String sessionKind = firstParentField(head, "sessionKind");
        String entrypoint = firstString(head, "entrypoint");
        if (entrypoint == null) entrypoint = lastString(tail, "entrypoint");
        boolean programmatic = Strings.CS.equalsAny(sessionKind, "daemon", "daemon-worker")
            || entrypoint != null && SDK_ENTRYPOINTS.contains(entrypoint);
        if (visibility == Visibility.PICKER) {
            if (StringUtils.isNotBlank(firstString(head, "teamName")) || programmatic
                    || Strings.CS.contains(head, "<command-name>/loop</command-name>")) return Optional.empty();
        } else if (visibility == Visibility.SDK_INTERACTIVE && programmatic) return Optional.empty();

        String customTitle = firstNonNull(lastString(tail, "customTitle"), lastString(head, "customTitle"));
        String aiTitle = firstNonNull(lastString(tail, "aiTitle"), lastString(head, "aiTitle"));
        String summaryField = lastTypedString(tail, "summary", "summary");
        String lastPrompt = lastString(tail, "lastPrompt");
        String firstPrompt = firstPrompt(head, builtInCommand);
        if (visibility != Visibility.PICKER && firstNonBlank(customTitle, aiTitle, summaryField,
                lastPrompt, firstPrompt) == null) return Optional.empty();
        String summary = firstNonBlank(customTitle, aiTitle, lastPrompt, summaryField, firstPrompt, "(session)");
        String relocatedCwd = lastTypedString(tail, "relocated", "relocatedCwd");
        String cwd = firstNonBlank(relocatedCwd, firstString(head, "cwd"), candidate.projectPath());
        if (visibility == Visibility.PICKER && candidate.projectPath() == null
                && StringUtils.isBlank(cwd)) return Optional.empty();
        String gitBranch = firstNonBlank(lastString(tail, "gitBranch"), firstString(head, "gitBranch"));
        String tag = lastTypedString(tail, "tag", "tag");
        long mtime = candidate.mtime() > 0 ? candidate.mtime() : lite.mtime();
        long ctime = candidate.ctime() > 0 ? candidate.ctime() : lite.ctime();
        Instant createdAt = parseInstant(firstString(head, "timestamp"), ctime);
        SessionInfo info = new SessionInfo(candidate.sessionId(), mtime, createdAt, -1,
            summary, gitBranch, cwd, tag, lite.size(), customTitle, firstPrompt);
        return Optional.of(new Entry(info, candidate.transcript(),
            firstNonBlank(candidate.projectPath(), cwd), aiTitle, candidate.alias()));
    }

    private static String firstPrompt(String head, Predicate<String> builtInCommand) {
        String commandFallback = null;
        boolean proactive = false;
        for (String line : head.split("\n")) {
            if ((!Strings.CS.contains(line, "\"type\":\"user\"")
                    && !Strings.CS.contains(line, "\"type\": \"user\""))
                    || Strings.CS.contains(line, "tool_result")) continue;
            try {
                JsonNode node = JsonUtils.getMapper().readTree(line);
                if (!Strings.CS.equals("user", node.path("type").asText())
                        || node.path("isMeta").asBoolean(false)) continue;
                JsonNode messageContent = node.path("message").path("content");
                JsonNode content = messageContent.isMissingNode() ? node.path("content") : messageContent;
                for (String text : textBlocks(content)) {
                    String flattened = text.replace('\n', ' ').trim();
                    if (flattened.isEmpty()) continue;
                    String commandName = XmlTagUtils.extractTag(flattened, "command-name").map(String::trim).orElse(null);
                    if (commandName != null) {
                        String commandArgs = XmlTagUtils.extractTag(flattened, "command-args").map(String::trim).orElse("");
                        String normalized = commandName.replaceFirst("^/", "").toLowerCase(Locale.ROOT);
                        if (builtInCommand.test(normalized) || commandArgs.isEmpty()) {
                            if (commandFallback == null) commandFallback = commandName;
                            continue;
                        }
                        return commandName + " " + commandArgs;
                    }
                    String bash = XmlTagUtils.extractTag(flattened, "bash-input").map(String::trim).orElse(null);
                    if (bash != null) return "! " + bash;
                    if (Strings.CS.startsWith(flattened, "<system-reminder>")) { proactive = true; continue; }
                    if (Strings.CS.startsWith(flattened, "<")
                            || Strings.CS.startsWith(flattened, "[Request interrupted by user")) continue;
                    return flattened.length() > 200 ? flattened.substring(0, 200).trim() + '\u2026' : flattened;
                }
            } catch (IOException | RuntimeException _) { }
        }
        if (commandFallback != null) return commandFallback;
        return proactive ? "Proactive session" : null;
    }

    private static List<String> textBlocks(JsonNode content) {
        List<String> result = new ArrayList<>();
        if (content.isTextual()) result.add(content.asText());
        else if (content.isArray()) for (JsonNode block : content) {
            if (Strings.CS.equals("text", block.path("type").asText()) && block.path("text").isTextual()) {
                result.add(block.path("text").asText());
            }
        }
        return result;
    }
    private static String firstParentField(String head, String field) {
        for (String line : head.split("\n")) if (Strings.CS.contains(line, "\"parentUuid\"")) return firstString(line, field);
        return firstString(head, field);
    }
    private static String lastTypedString(String text, String type, String field) {
        String result = null;
        for (String line : text.split("\n")) if (Strings.CS.contains(line, "\"type\":\"" + type + "\"")
                || Strings.CS.contains(line, "\"type\": \"" + type + "\"")) {
            String value = firstString(line, field); if (value != null) result = value;
        }
        return result;
    }
    private static String firstLine(String text) { int n = text.indexOf('\n'); return n < 0 ? text : text.substring(0, n); }
    private static boolean containsBoolean(String text, String field, boolean value) {
        return Strings.CS.contains(text, "\"" + field + "\":" + value)
            || Strings.CS.contains(text, "\"" + field + "\": " + value);
    }
    private static String firstString(String text, String field) { return SessionManager.jsonStringField(text, field); }
    private static String lastString(String text, String field) { return SessionManager.lastJsonStringField(text, field); }
    private static String firstNonNull(String... values) { for (String value : values) if (value != null) return value; return null; }
    private static String firstNonBlank(String... values) { for (String value : values) if (StringUtils.isNotBlank(value)) return value; return null; }
    private static Instant parseInstant(String value, long fallback) {
        if (value != null) try { return Instant.parse(value); } catch (RuntimeException _) { }
        return Instant.ofEpochMilli(Math.max(0, fallback));
    }

    enum Visibility { PICKER, SDK_ALL, SDK_INTERACTIVE }
    interface IoObserver {
        IoObserver NONE = new IoObserver() {
            @Override public void started() {}
            @Override public void finished() {}
        };
        void started();
        void finished();
    }
    record Candidate(String sessionId, Path transcript, String projectPath, long mtime, long ctime,
                     long fileSize, boolean alias) {
        private static final Comparator<Candidate> ORDER = Comparator
            .comparingLong(Candidate::mtime).reversed()
            .thenComparing(Candidate::sessionId, Comparator.reverseOrder());
        Candidate withStat(long modified, long created, long size) {
            return new Candidate(sessionId, transcript, projectPath, modified, created, size, alias);
        }
    }
}
