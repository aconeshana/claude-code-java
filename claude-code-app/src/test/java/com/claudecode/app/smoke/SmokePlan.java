package com.claudecode.app.smoke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The smoke plan as read from, joined to the flag matrix.
 */
final class SmokePlan {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<Template> templates;
    private final Map<String, String> notSmoked;
    private final List<String> prompt;
    private final String seedPrompt;

    private SmokePlan(
            List<Template> templates,
            Map<String, String> notSmoked,
            List<String> prompt,
            String seedPrompt) {
        this.templates = templates;
        this.notSmoked = notSmoked;
        this.prompt = prompt;
        this.seedPrompt = seedPrompt;
    }

    /**
     * One planned case before its placeholders are known.
     *
     * @param argv  the flag arguments inherited from the matrix or overridden here, before
     *              substitution and before the prompt is appended
     * @param files scratch files this case's arguments point at, content by relative path
     * @param dirs  scratch directories this case's arguments point at
     */
    record Template(
            String entryId,
            List<String> argv,
            List<String> prompt,
            int expectExit,
            String expectStdout,
            String expectStderr,
            List<TranscriptExpectation> transcriptExpectations,
            Map<String, String> files,
            List<String> dirs,
            String note) {}

    record TranscriptExpectation(String type, String field, String value) {}

    static SmokePlan load(Path planFile, Path matrixFile) {
        JsonNode plan = read(planFile);
        Map<String, List<String>> matrixArgv = matrixArgv(read(matrixFile));
        List<String> prompt = strings(plan.path("prompt"));

        List<Template> templates = new ArrayList<>();
        for (JsonNode node : plan.path("cases")) {
            String entryId = node.path("entry").asText();
            List<String> declared = node.has("argv")
                ? strings(node.get("argv"))
                : Optional.ofNullable(matrixArgv.get(entryId)).orElseThrow(() ->
                    new IllegalStateException("smoke case \"" + entryId
                        + "\" names no argv and matches no flag-matrix entry that carries one"));
            List<String> casePrompt = node.has("prompt") ? strings(node.get("prompt")) : prompt;
            int expectExit = node.path("expectExit").asInt(0);
            String expectStdout = node.has("expectStdout")
                ? text(node.get("expectStdout"))
                : casePrompt.isEmpty() ? null : FakeAnthropicServer.MARKER;
            if (expectStdout == null && expectExit == 0 && text(node.get("expectStderr")) == null) {
                throw new IllegalStateException("smoke case \"" + entryId
                    + "\" asserts nothing: an exit code of 0 alone would pass on a silent process");
            }
            templates.add(new Template(
                entryId, declared, casePrompt, expectExit, expectStdout,
                text(node.get("expectStderr")),
                transcriptExpectations(node.path("expectTranscriptEntries")),
                map(node.path("files")),
                strings(node.path("dirs")), text(node.get("note"))));
        }
        return new SmokePlan(
            List.copyOf(templates), map(plan.path("notSmoked")), prompt,
            plan.path("seedPrompt").asText("seed"));
    }

    List<Template> templates() {
        return templates;
    }

    /** Matrix entry ids this plan deliberately leaves out, each with its stated reason. */
    Map<String, String> notSmoked() {
        return notSmoked;
    }

    /** The prompt appended to a case that did not override it, for the seed launch to reuse. */
    List<String> seedArgv() {
        List<String> argv = new ArrayList<>(prompt);
        if (!argv.isEmpty()) {
            argv.set(argv.size() - 1, seedPrompt);
        }
        return List.copyOf(argv);
    }

    /** Every matrix entry carrying an argv, which is the population a smoke plan may draw from. */
    static Map<String, List<String>> matrixArgv(JsonNode matrix) {
        Map<String, List<String>> byId = new LinkedHashMap<>();
        for (JsonNode entry : matrix.path("entries")) {
            if (entry.has("argv") && !entry.path("argv").isEmpty()) {
                byId.put(entry.path("id").asText(), strings(entry.get("argv")));
            }
        }
        return byId;
    }

    static JsonNode read(Path file) {
        try {
            return MAPPER.readTree(Files.readString(file));
        } catch (IOException cause) {
            throw new UncheckedIOException("cannot read " + file, cause);
        }
    }

    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.forEach(element -> values.add(element.asText()));
        return List.copyOf(values);
    }

    private static Map<String, String> map(JsonNode node) {
        Map<String, String> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(field -> values.put(field.getKey(), field.getValue().asText()));
        return Map.copyOf(values);
    }

    private static List<TranscriptExpectation> transcriptExpectations(JsonNode node) {
        List<TranscriptExpectation> expectations = new ArrayList<>();
        for (JsonNode expectation : node) {
            String type = text(expectation.get("type"));
            String field = text(expectation.get("field"));
            String value = text(expectation.get("value"));
            if (type == null || field == null || value == null) {
                throw new IllegalStateException(
                    "expectTranscriptEntries rows require type, field, and value");
            }
            expectations.add(new TranscriptExpectation(type, field, value));
        }
        return List.copyOf(expectations);
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }
}
