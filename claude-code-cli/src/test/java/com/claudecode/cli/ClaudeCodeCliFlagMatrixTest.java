package com.claudecode.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import picocli.CommandLine;

class ClaudeCodeCliFlagMatrixTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MATRIX_PATH = "gradle/cli-flag-matrix.json";

    private static final String SMOKE_PLAN_PATH = "gradle/cli-flag-smoke.json";

    /** Options Picocli synthesises from {@code mixinStandardHelpOptions}. */
    private static final Set<String> MIXIN_OPTIONS = Set.of("--help", "--version");

    @TestFactory
    Stream<DynamicTest> everyMatrixCaseParsesAsRecorded() {
        Map<String, Object> baseline = flatten(parse().snapshot());
        return executableEntries()
            .map(entry -> dynamicTest(caseName(entry), () -> verify(entry, baseline)));
    }

    private static void verify(JsonNode entry, Map<String, Object> baseline) {
        List<String> argv = strings(entry.path("argv"));
        String expectedThrow = text(entry.path("expectThrows"));
        if (expectedThrow != null) {
            Exception thrown = assertThrows(Exception.class, () -> parse(argv),
                () -> "expected " + expectedThrow + " for " + argv + explain(entry));
            assertEquals(expectedThrow, thrown.getClass().getSimpleName(),
                () -> "wrong failure type for " + argv + explain(entry));
            return;
        }

        Parsed parsed = parse(argv);
        String parseFlag = text(entry.path("expectParseFlag"));
        if (parseFlag != null) {
            assertTrue(requestedFlag(parsed.result(), parseFlag),
                () -> "expected " + parseFlag + " to be requested by " + argv + explain(entry));
        }

        assertEquals(entry.path("expect"), diff(baseline, flatten(parsed.snapshot())),
            () -> "launch-request delta does not match the matrix for " + argv + explain(entry));
        assertEquals(strings(entry.path("expectResidual")), residual(parsed.result()),
            () -> "unconsumed arguments do not match the matrix for " + argv + explain(entry));
    }

    /**
     * Guards the matrix itself: an option added to {@link ClaudeCodeCli} without a matrix
     * entry would otherwise be covered by nothing at all, and a renamed option would leave a
     * stale entry silently asserting a flag that no longer exists.
     */
    @Test
    void matrixLogsExactlyTheDeclaredOptions() {
        Set<String> declared = new LinkedHashSet<>();
        for (CommandLine.Model.OptionSpec option
                : ClaudeCodeCli.commandLine(new ClaudeCodeCli()).getCommandSpec().options()) {
            primaryName(option).ifPresent(declared::add);
        }
        Set<String> logged = new LinkedHashSet<>();
        for (JsonNode entry : matrix().path("entries")) {
            String option = text(entry.path("javaOption"));
            if (option != null) {
                logged.add(option);
            }
        }
        assertEquals(declared, logged,
            "gradle/cli-flag-matrix.json must log every @Option declared by ClaudeCodeCli "
                + "(including the --help/--version pair supplied by mixinStandardHelpOptions) "
                + "and nothing else");
    }


    @Test
    void officialInventoryIsLoggedInFull() {
        JsonNode matrix = matrix();
        Set<String> specs = new LinkedHashSet<>();
        for (JsonNode entry : matrix.path("entries")) {
            String spec = text(entry.path("officialSpec"));
            if (spec != null) {
                specs.add(spec);
            }
        }
        assertEquals(matrix.path("official").path("rootOptionCount").asInt(), specs.size(),
            "every 2.1.197 root option must appear in the matrix, either mapped to a Java "
                + "option or recorded as MISSING");
    }

    @Test
    void matrixEntryIdsAreUnique() {
        List<String> ids = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (JsonNode entry : matrix().path("entries")) {
            String id = entry.path("id").asText();
            ids.add(id);
            unique.add(id);
        }
        assertEquals(ids.size(), unique.size(), "matrix entry ids must be unique");
    }

    @Test
    void officialNameCannotRegressToALedgerOnlyMissingEntry() {
        JsonNode officialName = null;
        for (JsonNode entry : matrix().path("entries")) {
            if (Strings.CS.equals("official-name", entry.path("id").asText())) {
                officialName = entry;
                break;
            }
        }
        assertTrue(officialName != null, "official-name must remain in the official inventory");

        assertEquals("ALIGNED", officialName.path("status").asText());
        assertEquals("--name", officialName.path("javaOption").asText());
        assertEquals(List.of("--name", "Named session"), strings(officialName.path("argv")),
            "official-name must remain executable instead of returning to a null-argv ledger row");
    }

    @Test
    void officialSessionIdCannotRegressToALedgerOnlyMissingEntry() {
        JsonNode entry = null;
        for (JsonNode candidate : matrix().path("entries")) {
            if (Strings.CS.equals("official-session-id", candidate.path("id").asText())) {
                entry = candidate;
                break;
            }
        }
        assertTrue(entry != null, "official-session-id must remain in the official inventory");
        assertEquals("ALIGNED", entry.path("status").asText());
        assertEquals("--session-id", entry.path("javaOption").asText());
        assertEquals(List.of("--session-id", "11111111-2222-4333-8444-555555555555"),
            strings(entry.path("argv")),
            "official-session-id must remain executable instead of returning to null argv");
    }

    // -- smoke-plan drift ---------------------------------------------------

    /**
     * Joins the process-level smoke plan back to the matrix it draws from. The plan names entry
     * ids instead of restating argv, which buys a spelling guarantee only if the names still
     * resolve — a renamed or deleted entry would otherwise leave the plan referring to nothing.
     *
     * <p>Asserted here rather than in the smoke harness itself because that harness is not part
     * of {@code check}: it needs a real process per case and a native binary this module cannot
     * build. Drift has to be caught by a test that actually runs on every build.
     */
    @Test
    void smokePlanNamesOnlyEntriesTheMatrixStillHas() {
        Set<String> executable = new LinkedHashSet<>();
        executableEntries().forEach(entry -> executable.add(entry.path("id").asText()));
        Set<String> named = new LinkedHashSet<>();
        for (JsonNode smoked : smokePlan().path("cases")) {
            named.add(smoked.path("entry").asText());
        }
        named.addAll(fieldNames(smokePlan().path("notSmoked")));

        Set<String> unknown = new LinkedHashSet<>(named);
        unknown.removeAll(executable);
        assertEquals(Set.of(), unknown,
            SMOKE_PLAN_PATH + " names matrix entries that no longer carry an argv (renamed, "
                + "deleted, or never executable): a plan case joins to the matrix by id, so a "
                + "stale name silently smokes nothing");
    }

    /**
     * The other direction: an entry the matrix can execute must either be smoked as a process or
     * carry a written reason not to be. Without this, adding a flag to the matrix would quietly
     * leave it covered at the parse layer only — which is exactly the gap that let a
     * {@code -c} reflection abort ship in a native binary.
     */
    @Test
    void everyExecutableEntryIsSmokedOrExcused() {
        Set<String> accounted = new LinkedHashSet<>();
        for (JsonNode smoked : smokePlan().path("cases")) {
            accounted.add(smoked.path("entry").asText());
        }
        accounted.addAll(fieldNames(smokePlan().path("notSmoked")));

        Set<String> unaccounted = new LinkedHashSet<>();
        executableEntries().forEach(entry -> {
            String id = entry.path("id").asText();
            if (!accounted.contains(id)) {
                unaccounted.add(id);
            }
        });
        assertEquals(Set.of(), unaccounted,
            "every matrix entry carrying an argv must appear in " + SMOKE_PLAN_PATH + ", either "
                + "as a case or under notSmoked with a written reason. Parse-level coverage does "
                + "not prove a flag survives startup");
    }

    /** A reason that says nothing is worse than no entry at all: it reads as a decision. */
    @Test
    void everyNotSmokedEntryStatesWhyNot() {
        smokePlan().path("notSmoked").fields().forEachRemaining(excused ->
            assertTrue(excused.getValue().asText().length() > 20,
                () -> "notSmoked[\"" + excused.getKey() + "\"] in " + SMOKE_PLAN_PATH
                    + " must say why the flag is not smoked as a process"));
    }

    // -- parsing ------------------------------------------------------------

    private record Parsed(ClaudeCodeCli cli, CommandLine.ParseResult result) {
        CliLaunchRequest snapshot() {
            return cli.snapshotLaunchRequest();
        }
    }

    private static Parsed parse(String... argv) {
        return parse(List.of(argv));
    }

    private static Parsed parse(List<String> argv) {
        ClaudeCodeCli cli = new ClaudeCodeCli();
        CommandLine.ParseResult result =
            ClaudeCodeCli.commandLine(cli).parseArgs(argv.toArray(String[]::new));
        return new Parsed(cli, result);
    }

    private static boolean requestedFlag(CommandLine.ParseResult result, String flag) {
        return switch (flag) {
            case "usageHelp" -> result.isUsageHelpRequested();
            case "versionHelp" -> result.isVersionHelpRequested();
            default -> throw new IllegalArgumentException("unknown expectParseFlag: " + flag);
        };
    }

    /** Tokens Picocli could not attach to the flag under test, in a stable rendering. */
    private static List<String> residual(CommandLine.ParseResult result) {
        List<String> residual = new ArrayList<>();
        for (CommandLine.Model.PositionalParamSpec positional : result.matchedPositionals()) {
            residual.add("positional:" + positional.stringValues());
        }
        for (String unmatched : result.unmatched()) {
            residual.add("unmatched:" + unmatched);
        }
        return residual;
    }

    // -- launch-request flattening -----------------------------------------

    /**
     * Flattens the launch request to {@code group.field} paths. {@code testOverrides} is
     * skipped: it carries injected collaborators rather than parsed flags.
     */
    private static Map<String, Object> flatten(CliLaunchRequest request) {
        Map<String, Object> flat = new LinkedHashMap<>();
        for (RecordComponent group : CliLaunchRequest.class.getRecordComponents()) {
            if (Strings.CS.equals("testOverrides", group.getName())) {
                continue;
            }
            Object value = read(group, request);
            if (value != null && value.getClass().isRecord()) {
                for (RecordComponent leaf : value.getClass().getRecordComponents()) {
                    flat.put(group.getName() + "." + leaf.getName(), read(leaf, value));
                }
            } else {
                flat.put(group.getName(), value);
            }
        }
        return flat;
    }

    private static ObjectNode diff(Map<String, Object> baseline, Map<String, Object> actual) {
        ObjectNode delta = MAPPER.createObjectNode();
        actual.forEach((path, value) -> {
            if (!Objects.equals(baseline.get(path), value)) {
                delta.set(path, MAPPER.valueToTree(value));
            }
        });
        return delta;
    }

    private static Object read(RecordComponent component, Object owner) {
        try {
            component.getAccessor().setAccessible(true);
            return component.getAccessor().invoke(owner);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read " + component, e);
        }
    }

    // -- matrix access ------------------------------------------------------

    private static Stream<JsonNode> executableEntries() {
        List<JsonNode> entries = new ArrayList<>();
        for (JsonNode entry : matrix().path("entries")) {
            if (!entry.path("argv").isMissingNode() && !entry.path("argv").isNull()) {
                entries.add(entry);
            }
        }
        return entries.stream();
    }

    private static JsonNode matrix() {
        return readJson(MATRIX_PATH);
    }

    /**
     * The process-level smoke plan. Read from the source tree rather than the classpath so the two
     * ledgers stay editable side by side; {@code claude-code-cli}'s test task declares both as
     * inputs, which is what makes an edit to either re-run these guards.
     */
    private static JsonNode smokePlan() {
        return readJson(SMOKE_PLAN_PATH);
    }

    private static JsonNode readJson(String relativePath) {
        Path path = repositoryRoot().resolve(relativePath);
        try (var reader = Files.newBufferedReader(path)) {
            return MAPPER.readTree(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }

    private static Set<String> fieldNames(JsonNode object) {
        Set<String> names = new LinkedHashSet<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String caseName(JsonNode entry) {
        return entry.path("id").asText() + "  [" + entry.path("status").asText() + "]  "
            + String.join(" ", strings(entry.path("argv")));
    }

    /** Surfaces the matrix rationale in the failure message so the fix path is obvious. */
    private static String explain(JsonNode entry) {
        StringBuilder detail = new StringBuilder("\n  matrix id : ")
            .append(entry.path("id").asText())
            .append("\n  status    : ").append(entry.path("status").asText());
        String officialSpec = text(entry.path("officialSpec"));
        if (officialSpec != null) {
            detail.append("\n  2.1.197   : ").append(officialSpec)
                .append(" (").append(entry.path("officialVisibility").asText()).append(')');
        }
        String note = text(entry.path("note"));
        if (note != null) {
            detail.append("\n  note      : ").append(note);
        }
        return detail.append("\n  matrix    : ").append(MATRIX_PATH).append('\n').toString();
    }

    private static Optional<String> primaryName(CommandLine.Model.OptionSpec option) {
        for (String name : option.names()) {
            if (Strings.CS.startsWith(name, "--")) {
                return Optional.of(name);
            }
        }
        return Optional.empty();
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode element : array) {
            values.add(element.asText());
        }
        return values;
    }

    private static String text(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asText();
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
