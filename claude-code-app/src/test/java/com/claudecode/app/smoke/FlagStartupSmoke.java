package com.claudecode.app.smoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.apache.commons.lang3.Strings;

/**
 * Process-level startup smoke over every flag the plan opts in, for each packaging.
 */
class FlagStartupSmoke {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MATRIX = "gradle/cli-flag-matrix.json";
    private static final String PLAN = "gradle/cli-flag-smoke.json";

    /**
     * Every planned case against every packaging that was built.
     *
     * <p>Cases of one target share a home directory and a session, so they must run in sequence.
     * The task disables parallel execution for that reason; nothing here can enforce it.
     */
    @TestFactory
    Stream<DynamicTest> everyPlannedFlagStartsUp() throws IOException {
        SmokePlan plan = SmokePlan.load(
            repositoryRoot().resolve(PLAN), repositoryRoot().resolve(MATRIX));
        Path smokeRoot = smokeRoot();
        Files.createDirectories(smokeRoot);
        List<SmokeTarget> targets = SmokeTarget.discover(jarUnderTest(), buildDirectory());

        Stream<DynamicTest> perTarget = targets.stream().flatMap(target ->
            casesFor(target, plan, smokeRoot.resolve(target.name())));
        return Stream.concat(
            Stream.of(reportCoverage(plan, targets, smokeRoot)), perTarget);
    }

    /**
     * Where the isolated homes and working directories live — deliberately outside the repository
     * rather than under {@code build/}.
     *
     * <p>Being inside a git repository is not a cosmetic detail here: it silently changes what
     * three flags do. {@code SessionSearch} resolves a launch cwd through the enclosing
     * repository's worktree list, so a workspace under {@code build/} sends
     * {@code --resume}/{@code --continue} looking for transcripts under the <em>repository's</em>
     * project directory instead of the workspace's — {@code --resume} then cannot find the session
     * the harness just recorded, and {@code --continue} silently starts a fresh one, passing
     * without ever deserializing the recorded assistant message this harness was built to cover.
     * {@code --worktree} is worse than misleading: it finds the enclosing repository and creates a
     * real worktree and branch in the developer's checkout.
     *
     * <p>{@code smoke.root} overrides the location; the enclosing-repository check below applies
     * either way, because an override that reintroduced the problem would be silent again.
     */
    private static Path smokeRoot() {
        String configured = System.getProperty("smoke.root");
        Path root = (configured != null
            ? Path.of(configured)
            : Path.of(System.getProperty("java.io.tmpdir")).resolve("claude-code-flag-smoke"))
            .toAbsolutePath().normalize();
        for (Path parent = root; parent != null; parent = parent.getParent()) {
            if (Files.exists(parent.resolve(".git"))) {
                throw new IllegalStateException("the smoke root " + root + " is inside the git "
                    + "repository at " + parent + ". Session lookup resolves a launch cwd through "
                    + "the enclosing repository's worktrees and --worktree would mutate that "
                    + "checkout, so the workspace has to sit outside one. Point smoke.root "
                    + "somewhere else.");
            }
        }
        return root;
    }

    /**
     * One target's cases, bracketed by the launch that records its session and the one that shuts
     * its server down. The session is opened inside the first test rather than while enumerating,
     * so a target whose seed launch fails reports that once instead of once per case: the cases
     * that follow are skipped, since a missing session says nothing about the flags they cover.
     */
    private Stream<DynamicTest> casesFor(SmokeTarget target, SmokePlan plan, Path root) {
        Session[] session = new Session[1];
        Stream<DynamicTest> open = Stream.of(dynamicTest(
            target.name() + " › record a session to resume from",
            () -> session[0] = Session.open(target, plan, root)));
        Stream<DynamicTest> cases = plan.templates().stream().map(template -> dynamicTest(
            target.name() + " › " + template.entryId(),
            () -> {
                assumeTrue(session[0] != null,
                    "this target recorded no session to resume from; see the failure above");
                assertStartsUp(target, session[0], template);
            }));
        Stream<DynamicTest> close = Stream.of(dynamicTest(
            target.name() + " › release the fake server",
            () -> {
                if (session[0] != null) session[0].close();
            }));
        return Stream.concat(open, Stream.concat(cases, close));
    }

    private void assertStartsUp(SmokeTarget target, Session session, SmokePlan.Template template) {
        SmokeCase smoked = resolve(session, template);
        Set<Path> transcriptsBefore = smoked.transcriptExpectations().isEmpty()
            ? Set.of() : session.workspace().transcriptFiles();
        SmokeOutcome outcome = session.launch().run(
            target.commandFor(smoked.argv()), session.workspace().workingDirectory(),
            session.workspace().home(), session.server().baseUrl());
        String detail = explain(target, smoked, outcome);

        assertEquals(List.of(), outcome.crashSignatures(),
            () -> "the process died on the way up rather than acting on the flag." + detail);
        assertFalse(outcome.timedOut(),
            () -> "the process never exited, which no expectation distinguishes from a hang."
                + detail);
        assertEquals(smoked.expectExit(), outcome.exitCode(), () -> "unexpected exit code." + detail);
        if (smoked.expectStdout() != null) {
            assertTrue(Strings.CS.contains(outcome.stdout(), smoked.expectStdout()),
                () -> "stdout is missing \"" + smoked.expectStdout() + "\"." + detail);
        }
        if (smoked.expectStderr() != null) {
            assertTrue(Strings.CS.contains(outcome.stderr(), smoked.expectStderr()),
                () -> "stderr is missing \"" + smoked.expectStderr() + "\"." + detail);
        }
        assertTranscriptExpectations(
            session.workspace(), transcriptsBefore, smoked, detail);
    }

    /** Writes the case's fixtures, substitutes its placeholders, and appends the prompt. */
    private static SmokeCase resolve(Session session, SmokePlan.Template template) {
        Path scratch = session.workspace().scratchFor(template.entryId());
        SmokeWorkspace.materialize(scratch, template);
        List<String> argv = new ArrayList<>();
        for (String argument : template.argv()) {
            argv.add(argument
                .replace("{{sessionId}}", session.workspace().seededSessionId())
                .replace("{{scratch}}", scratch.toString())
                .replace("{{baseUrl}}", session.server().baseUrl()));
        }
        argv.addAll(template.prompt());
        List<SmokePlan.TranscriptExpectation> transcriptExpectations =
            template.transcriptExpectations().stream()
                .map(expectation -> new SmokePlan.TranscriptExpectation(
                    expectation.type(), expectation.field(), expectation.value()
                        .replace("{{sessionId}}", session.workspace().seededSessionId())
                        .replace("{{scratch}}", scratch.toString())
                        .replace("{{baseUrl}}", session.server().baseUrl())))
                .toList();
        return new SmokeCase(
            template.entryId(), List.copyOf(argv), template.expectExit(),
            template.expectStdout(), template.expectStderr(),
            transcriptExpectations, template.note());
    }

    private static void assertTranscriptExpectations(
            SmokeWorkspace workspace,
            Set<Path> before,
            SmokeCase smoked,
            String detail) {
        if (smoked.transcriptExpectations().isEmpty()) return;
        List<Path> created = workspace.transcriptFiles().stream()
            .filter(path -> !before.contains(path))
            .toList();
        assertEquals(1, created.size(),
            () -> "expected exactly one newly-created transcript, got " + created + '.' + detail);

        List<JsonNode> entries = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(created.getFirst())) {
                try {
                    JsonNode entry = MAPPER.readTree(line);
                    if (entry != null) entries.add(entry);
                } catch (Exception _) {
                    // Transcript readers are malformed-line tolerant; the smoke assertion is too.
                }
            }
        } catch (IOException cause) {
            throw new UncheckedIOException(
                "cannot inspect named-session transcript " + created.getFirst(), cause);
        }

        for (SmokePlan.TranscriptExpectation expectation : smoked.transcriptExpectations()) {
            assertTrue(entries.stream().anyMatch(entry ->
                    Strings.CS.equals(expectation.type(), entry.path("type").asText(null))
                        && Strings.CS.equals(
                            expectation.value(), entry.path(expectation.field()).asText(null))),
                () -> "new transcript " + created.getFirst() + " is missing "
                    + expectation.type() + '.' + expectation.field() + "="
                    + expectation.value() + '.' + detail);
        }
    }

    /**
     * Reports which targets ran and fails when the plan has fallen behind the matrix. A skipped
     * native target is the normal state of a developer machine, so it is printed rather than passed
     * over: a run that covered only the jar must not read like a full one.
     */
    private DynamicTest reportCoverage(SmokePlan plan, List<SmokeTarget> targets, Path smokeRoot) {
        return dynamicTest("targets and coverage", () -> {
            System.out.println("flagStartupSmoke: " + plan.templates().size() + " cases × "
                + targets.size() + " target(s)");
            System.out.println("  workspaces: " + smokeRoot);
            for (SmokeTarget target : targets) {
                System.out.println("  target " + target.name() + ": " + describe(target));
            }
            for (String missing : SmokeTarget.missingNativeFlavours(targets)) {
                System.out.println("  target " + missing + ": NOT BUILT, skipped — build it with "
                    + "./gradlew :claude-code-app:" + missing + " (needs GRAALVM_HOME)");
            }
            System.out.println("  not smoked: " + plan.notSmoked().keySet());
            if (!SmokeTarget.requestedNames().isEmpty()) {
                System.out.println("  NOTE: smoke.targets narrowed this run to "
                    + SmokeTarget.requestedNames() + " — it covered less than a full one");
            }

            assertFalse(targets.isEmpty(),
                "no target was discovered; the jar is built by this task, so run it through Gradle "
                    + "and check that smoke.targets does not exclude everything");
            if (SmokeTarget.requestedNames().isEmpty()) {
                assertTrue(targets.stream().anyMatch(target -> Strings.CS.equals("jar", target.name())),
                    "the fat jar is missing; flagStartupSmoke builds it, so run this through Gradle");
            }
            if (Boolean.getBoolean("smoke.requireNative")) {
                assertEquals(List.of(), SmokeTarget.missingNativeFlavours(targets),
                    "smoke.requireNative was set but no native image was built");
            }
        });
    }

    /**
     * A stale binary is the most likely cause of an inexplicable native failure — it may predate
     * the flag under test entirely — so the build time is part of the target's identity here.
     */
    private static String describe(SmokeTarget target) {
        Path binary = Path.of(target.launch().getLast());
        try {
            return binary + " (built " + Files.getLastModifiedTime(binary).toInstant() + ')';
        } catch (IOException _) {
            return binary.toString();
        }
    }

    private static String explain(SmokeTarget target, SmokeCase smoked, SmokeOutcome outcome) {
        StringBuilder detail = new StringBuilder("\n  target : ").append(target.name())
            .append("\n  entry  : ").append(smoked.entryId())
            .append("\n  command: ").append(String.join(" ", target.commandFor(smoked.argv())));
        if (smoked.note() != null) {
            detail.append("\n  note   : ").append(smoked.note());
        }
        return detail + outcome.transcript();
    }

    /** One target's server, workspace and launcher, opened once and closed by its last case. */
    private record Session(
            FakeAnthropicServer server, SmokeWorkspace workspace, SmokeLaunch launch) {

        static Session open(SmokeTarget target, SmokePlan plan, Path root) throws IOException {
            FakeAnthropicServer server = FakeAnthropicServer.start();
            SmokeLaunch launch = new SmokeLaunch(root.resolve("launch"));
            SmokeWorkspace workspace = SmokeWorkspace.create(root, (workingDirectory, home) ->
                launch.run(
                    target.commandFor(plan.seedArgv()), workingDirectory, home, server.baseUrl()));
            return new Session(server, workspace, launch);
        }

        void close() {
            if (!server.unexpectedPaths().isEmpty()) {
                System.out.println("  note: unplanned request paths reached the fake server: "
                    + server.unexpectedPaths());
            }
            server.close();
        }
    }

    private static Path buildDirectory() {
        String configured = System.getProperty("smoke.buildDir");
        return configured != null
            ? Path.of(configured)
            : Path.of("").toAbsolutePath().resolve("build");
    }

    /**
     * @return the jar the task built, or the conventional shadowJar path so the harness can also be
     *     started from an IDE
     */
    private static Path jarUnderTest() {
        String configured = System.getProperty("smoke.jar");
        if (configured != null) return Path.of(configured);
        try (Stream<Path> jars = Files.list(buildDirectory().resolve("libs"))) {
            return jars.filter(path -> Strings.CS.endsWith(path.getFileName().toString(), ".jar"))
                .findFirst().orElse(null);
        } catch (IOException _) {
            return null;
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
