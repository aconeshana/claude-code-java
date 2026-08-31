package com.claudecode.cli;

import com.claudecode.core.util.UuidUtils;
import com.claudecode.session.SessionSearch;
import com.claudecode.session.SessionSearch.LocatedSession;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.commons.lang3.Strings;

/**
 * Turns the raw {@code -r}/{@code --resume} value into the restoration target it names.
 */
final class CliResumeTargetResolver {

    /** Matches the {@code sessionId} field of one transcript entry. */
    private static final Pattern SESSION_ID_FIELD =
        Pattern.compile("\"sessionId\"\\s*:\\s*\"([^\"]+)\"");

    private CliResumeTargetResolver() {}

    /**
     * Which of the two transcript-file rules.
     */
    enum Mode {

/**: an absolute, case-sensitive  suffix. */
        INTERACTIVE,

/**: any path with a case-insensitive  suffix. */
        PRINT
    }

    /** Everything the resume value can denote, including the ways it can fail to denote one. */
    sealed interface Target {

        /** The flag was absent — {@code null} rather than an empty value. */
        record Absent() implements Target {}


        record Valueless() implements Target {}

        /**
         * A located session log. {@code transcript} is the matched log's own path, which may sit
         * under a sibling worktree's project directory rather than the launch cwd.
         */
        record Session(String sessionId, Path transcript) implements Target {}

        /**
         * A transcript named directly.
         */
        record TranscriptFile(Path transcript, String sessionId, String rawValue)
            implements Target {}

/** A  path that could not be read. */
        record UnreadableTranscriptFile(String rawValue) implements Target {}

        /** A well-formed UUID with no log behind it. */
        record MissingSessionId(String sessionId) implements Target {}

        /** A title several sessions share; only the headless branch can report the candidates. */
        record AmbiguousTitle(String query, List<TitleMatch> matches) implements Target {}

        /** A value that is neither a UUID nor any session's title. */
        record UnknownTitle(String query) implements Target {}
    }

    /** One candidate of an ambiguous title, in the shape the disambiguation listing needs. */
    record TitleMatch(String sessionId, Instant modified) {}

    /**
     * @param rawValue the parsed option value: {@code null} when the flag was absent, empty when
     *                 it was passed without one
     * @param search   worktree-aware lookup; cheap to construct because it resolves its session
     *                 managers lazily, so callers may build one per launch
     * @param mode     selects the transcript-file rule of the branch this launch will take
     */
    static Target resolve(String rawValue, SessionSearch search, Mode mode) {
        if (rawValue == null) return new Target.Absent();
        String query = rawValue.trim();
        if (query.isEmpty()) return new Target.Valueless();

        Path transcriptFile = transcriptFilePath(query, mode);
        if (transcriptFile != null) return resolveTranscriptFile(transcriptFile, query, mode);

        if (UuidUtils.isValid(query)) {
            Optional<LocatedSession> found = search.findExactSessionId(query);
            return found.isPresent()
                ? new Target.Session(found.get().id(), found.get().sessionFile())
                : new Target.MissingSessionId(query);
        }

        List<LocatedSession> matches = search.searchExactCustomTitle(query);
        if (matches.size() == 1) {
            LocatedSession only = matches.getFirst();
            return new Target.Session(only.id(), only.sessionFile());
        }
        if (matches.isEmpty()) return new Target.UnknownTitle(query);
        return new Target.AmbiguousTitle(query, matches.stream()
            .map(match -> new TitleMatch(match.id(), Instant.ofEpochMilli(match.lastModified())))
            .toList());
    }

    /**
     * The transcript-file guard, which.
     */
    private static Path transcriptFilePath(String query, Mode mode) {
        boolean named = mode == Mode.INTERACTIVE
            ? Strings.CS.endsWith(query, ".jsonl")
            : Strings.CI.endsWith(query, ".jsonl");
        if (!named) return null;
        try {
            Path path = Path.of(query);
            return mode == Mode.INTERACTIVE && !path.isAbsolute() ? null : path;
        } catch (InvalidPathException _) {
            return null;
        }
    }

    private static Target resolveTranscriptFile(Path transcript, String rawValue, Mode mode) {
        if (!Files.isRegularFile(transcript) || !Files.isReadable(transcript)) {
            return new Target.UnreadableTranscriptFile(rawValue);
        }
        return new Target.TranscriptFile(
            transcript, transcriptSessionId(transcript, mode), rawValue);
    }

    /**
     * The identity an explicit transcript hands the launch, which the two branches source
     * differently. Interactive startup passes  as its
     * {@code sessionIdOverride}, so the file name decides. The print branch instead takes the id
     * off the transcript's own last entry, falling back to a fresh identity — a renamed or
     * exported log therefore keeps its recorded identity there rather than losing it to the name
     * on disk.
     */
    private static String transcriptSessionId(Path transcript, Mode mode) {
        String name = transcript.getFileName().toString();
        String basenameId =
            UuidUtils.validate(name.substring(0, name.length() - ".jsonl".length()));
        if (mode == Mode.INTERACTIVE) return basenameId;
        String recorded = lastRecordedSessionId(transcript);
        return recorded != null ? recorded : basenameId;
    }

    /**
     * The {@code sessionId} of the transcript's last entry that carries one. Read as text rather
     * than through {@code SessionStorage} because the whole point is to identify a file that may
     * not live under any project directory, and because an unparsable line must not be fatal.
     */
    private static String lastRecordedSessionId(Path transcript) {
        try (Stream<String> lines = Files.lines(transcript, StandardCharsets.UTF_8)) {
            return lines.map(CliResumeTargetResolver::sessionIdField)
                .filter(Objects::nonNull)
                .reduce((_, later) -> later)
                .orElse(null);
        } catch (IOException | UncheckedIOException _) {
            return null;
        }
    }

    private static String sessionIdField(String line) {
        Matcher matcher = SESSION_ID_FIELD.matcher(line);
        return matcher.find() ? UuidUtils.validate(matcher.group(1)) : null;
    }
}
