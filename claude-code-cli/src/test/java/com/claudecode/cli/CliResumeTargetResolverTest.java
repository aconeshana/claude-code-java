package com.claudecode.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.claudecode.cli.CliResumeTargetResolver.Mode;
import com.claudecode.cli.CliResumeTargetResolver.Target;
import com.claudecode.session.SessionManager;
import com.claudecode.session.SessionSearch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;


class CliResumeTargetResolverTest {

    private static final String ALPHA = "11111111-1111-4111-8111-111111111111";
    private static final String BETA = "22222222-2222-4222-8222-222222222222";
    private static final String GAMMA = "33333333-3333-4333-8333-333333333333";
    private static final String ABSENT_ID = "44444444-4444-4444-4444-444444444444";

    @TempDir
    Path home;

    @Test
    void anAbsentFlagResolvesToNothingWhileABareOneStillHasToChoose() {
        SessionSearch search = search("/repo");

        assertInstanceOf(Target.Absent.class,
            CliResumeTargetResolver.resolve(null, search, Mode.INTERACTIVE));
        for (String valueless : List.of("", "   ")) {
            assertInstanceOf(Target.Valueless.class,
                CliResumeTargetResolver.resolve(valueless, search, Mode.INTERACTIVE),
                () -> "expected a valueless target for \"" + valueless + "\"");
        }
    }

    @Test
    void aUuidResolvesToItsOwnLogAndAMissOneIsReportedRatherThanIgnored() throws IOException {
        Path log = writeSession("/repo", ALPHA, "Refactor the parser", 1_000L);

        Target hit = CliResumeTargetResolver.resolve(ALPHA, search("/repo"), Mode.INTERACTIVE);
        assertEquals(new Target.Session(ALPHA, log), hit);

        Target miss = CliResumeTargetResolver.resolve(ABSENT_ID, search("/repo"), Mode.INTERACTIVE);
        assertEquals(new Target.MissingSessionId(ABSENT_ID), miss);
    }

    /**
     * {@code searchSessionsByCustomTitle} lower-cases and trims both sides, so the value a user
     * copies out of the picker resolves regardless of the casing and padding it arrives with.
     */
    @Test
    void aSingleTitleMatchIsExactButNeitherCaseNorWhitespaceSensitive() throws IOException {
        Path log = writeSession("/repo", ALPHA, "Refactor the parser", 1_000L);
        writeSession("/repo", BETA, "Refactor the tokenizer", 2_000L);

        for (String value : List.of("Refactor the parser", "REFACTOR THE PARSER",
                "  refactor the parser  ")) {
            assertEquals(new Target.Session(ALPHA, log),
                CliResumeTargetResolver.resolve(value, search("/repo"), Mode.INTERACTIVE),
                () -> "expected a title match for \"" + value + "\"");
        }

        assertEquals(new Target.UnknownTitle("Refactor"),
            CliResumeTargetResolver.resolve("Refactor", search("/repo"), Mode.INTERACTIVE),
            "the title search is exact, never a prefix or substring");
    }

    /**
     * The matched log's own path is what gets resumed. Deriving it from the launch cwd instead
     * would resolve a sibling worktree's title to a file that does not exist there.
     */
    @Test
    void aTitleMayResolveToASiblingWorktreesLog() throws IOException {
        writeSession("/repo/main", ALPHA, "Main branch work", 1_000L);
        Path sibling = writeSession("/repo/feature", BETA, "Feature branch work", 2_000L);

        Target target = CliResumeTargetResolver.resolve("Feature branch work",
            new SessionSearch(home, "/repo/main", () -> List.of("/repo/main", "/repo/feature")),
            Mode.INTERACTIVE);

        assertEquals(new Target.Session(BETA, sibling), target);
        assertNotEquals(
            new SessionManager(home, "/repo/main").getSessionFile(BETA), sibling,
            "the resumed log must not be rewritten into the launch cwd's project directory");
    }

    /** Newest first, because the disambiguation listing is what the user picks from. */
    @Test
    void severalSessionsSharingATitleBecomeAnAmbiguousTargetNewestFirst() throws IOException {
        writeSession("/repo", ALPHA, "Nightly run", 1_000L);
        writeSession("/repo", BETA, "nightly run", 3_000L);
        writeSession("/repo", GAMMA, "NIGHTLY RUN", 2_000L);

        Target target = CliResumeTargetResolver.resolve("Nightly run", search("/repo"), Mode.INTERACTIVE);

        Target.AmbiguousTitle ambiguous = assertInstanceOf(Target.AmbiguousTitle.class, target);
        assertEquals("Nightly run", ambiguous.query());
        assertEquals(List.of(BETA, GAMMA, ALPHA),
            ambiguous.matches().stream().map(CliResumeTargetResolver.TitleMatch::sessionId).toList());
        assertEquals(3_000L, ambiguous.matches().getFirst().modified().toEpochMilli());
    }

    @Test
    void anEmptyProjectTurnsEveryNonUuidValueIntoAnUnknownTitle() {
        assertEquals(new Target.UnknownTitle("anything"),
            CliResumeTargetResolver.resolve("anything", search("/repo"), Mode.INTERACTIVE));
    }

    @Test
    void anAbsoluteTranscriptPathAdoptsAUuidFileNameAsTheSessionIdentity() throws IOException {
        Path named = home.resolve(ALPHA + ".jsonl");
        Files.writeString(named, "{}\n");
        Path unnamed = home.resolve("exported-transcript.jsonl");
        Files.writeString(unnamed, "{}\n");

        assertEquals(new Target.TranscriptFile(named, ALPHA, named.toString()),
            CliResumeTargetResolver.resolve(named.toString(), search("/repo"), Mode.INTERACTIVE));

        Target anonymous = CliResumeTargetResolver.resolve(unnamed.toString(), search("/repo"), Mode.INTERACTIVE);
        Target.TranscriptFile file = assertInstanceOf(Target.TranscriptFile.class, anonymous);
        assertNull(file.sessionId(),
            "a non-UUID file name supplies no session id override, so the launch keeps its own");
    }

    @Test
    void anAbsoluteTranscriptPathWithNoFileBehindItIsUnreadableRatherThanATitle() {
        String missing = home.resolve("gone.jsonl").toString();

        assertEquals(new Target.UnreadableTranscriptFile(missing),
            CliResumeTargetResolver.resolve(missing, search("/repo"), Mode.INTERACTIVE));
    }


    @Test
    void aRelativeJsonlNameFallsThroughToTheTitleSearch() throws IOException {
        Path log = writeSession("/repo", ALPHA, "notes.jsonl", 1_000L);

        assertEquals(new Target.Session(ALPHA, log),
            CliResumeTargetResolver.resolve("notes.jsonl", search("/repo"), Mode.INTERACTIVE));
        assertEquals(new Target.UnknownTitle("other.jsonl"),
            CliResumeTargetResolver.resolve("other.jsonl", search("/repo"), Mode.INTERACTIVE));
    }

    /**
     * The print entry point's {@code parseSessionIdentifier} asks only for a case-insensitive
     *  suffix, dropping both the absoluteness requirement and the exact casing. The
     * very same argument is therefore a transcript there and a title candidate interactively.
     */
    @Test
    void printModeReadsALooserTranscriptSuffixThanInteractiveStartup() throws IOException {
        writeSession("/repo", ALPHA, "notes.jsonl", 1_000L);
        Path shouting = home.resolve(GAMMA + ".JSONL");
        Files.writeString(shouting, "{}\n");

        assertEquals(new Target.UnreadableTranscriptFile("notes.jsonl"),
            CliResumeTargetResolver.resolve("notes.jsonl", search("/repo"), Mode.PRINT),
            "a relative name is a transcript under --print, never the session titled that");
        assertEquals(new Target.TranscriptFile(shouting, GAMMA, shouting.toString()),
            CliResumeTargetResolver.resolve(shouting.toString(), search("/repo"), Mode.PRINT));
        assertEquals(new Target.UnknownTitle(shouting.toString()),
            CliResumeTargetResolver.resolve(shouting.toString(), search("/repo"), Mode.INTERACTIVE),
            "interactive startup matches the suffix case-sensitively");
    }

    /**
     * Interactive startup passes  as its session id
     * override, while the print branch reads the id off the transcript's own last entry. A log
     * that was renamed or exported keeps its recorded identity under {@code --print} and loses it
     * to the file name interactively.
     */
    @Test
    void printModeTakesTheSessionIdFromTheTranscriptRatherThanItsFileName() throws IOException {
        Path exported = home.resolve("exported.jsonl");
        Files.writeString(exported, """
            {"type":"user","sessionId":"%s","message":{"role":"user","content":"first"}}
            {"type":"assistant","sessionId":"%s","message":{"role":"assistant","content":"last"}}
            """.formatted(ALPHA, BETA));

        assertEquals(new Target.TranscriptFile(exported, BETA, exported.toString()),
            CliResumeTargetResolver.resolve(exported.toString(), search("/repo"), Mode.PRINT),
            "the last recorded id wins, matching messages.at(-1).sessionId");

        Target interactive =
            CliResumeTargetResolver.resolve(exported.toString(), search("/repo"), Mode.INTERACTIVE);
        assertNull(assertInstanceOf(Target.TranscriptFile.class, interactive).sessionId(),
            "a non-UUID file name supplies no override, whatever the file records");
    }

    /** Nothing recorded, so the file name is all that is left to identify the transcript by. */
    @Test
    void printModeFallsBackToTheFileNameWhenTheTranscriptRecordsNoSessionId() throws IOException {
        Path named = home.resolve(ALPHA + ".jsonl");
        Files.writeString(named, "{\"type\":\"summary\",\"summary\":\"no ids here\"}\n");

        assertEquals(new Target.TranscriptFile(named, ALPHA, named.toString()),
            CliResumeTargetResolver.resolve(named.toString(), search("/repo"), Mode.PRINT));
    }

    private SessionSearch search(String cwd) {
        return new SessionSearch(home, cwd, () -> List.of(cwd));
    }

    private Path writeSession(String cwd, String sessionId, String customTitle, long modified)
            throws IOException {
        Path file = new SessionManager(home, cwd).getSessionFile(sessionId);
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
            {"type":"user","timestamp":"2026-01-01T00:00:00Z","cwd":"%s",\
            "message":{"role":"user","content":"hello"}}
            {"type":"custom-title","customTitle":"%s"}
            """.formatted(cwd, customTitle));
        Files.setLastModifiedTime(file, FileTime.fromMillis(modified));
        return file;
    }
}
