package com.claudecode.tools.questions;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.permissions.PermissionDecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import com.claudecode.tools.ValidationResult;


class AskUserQuestionToolTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final ToolExecutionContext ctx =
        ToolExecutionContext.of(new AbortController(), "test-session");

    @AfterEach
    void clearPresenter() {
        AskUserQuestionTool.setPreviewFormat(null);
    }

    /** Two questions (header "H", 2 options each) with caller-supplied texts/labels. */
    private static ObjectNode twoQuestionInput(String q1, String q2,
                                               String l1a, String l1b, String l2a, String l2b) {
        ObjectNode input = M.createObjectNode();
        ArrayNode qs = input.putArray("questions");
        String[][][] specs = {{{q1}, {l1a}, {l1b}}, {{q2}, {l2a}, {l2b}}};
        for (String[][] spec : specs) {
            ObjectNode qn = qs.addObject();
            qn.put("question", spec[0][0]);
            qn.put("header", "H");
            qn.put("multiSelect", false);
            ArrayNode opts = qn.putArray("options");
            for (int i = 1; i < spec.length; i++) {
                ObjectNode o = opts.addObject();
                o.put("label", spec[i][0]);
                o.put("description", "d");
            }
        }
        return input;
    }

    /** Single-question input (from {@link #questionsInput}) with the first option's preview set. */
    private static ObjectNode inputWithPreview(String preview) {
        ObjectNode input = questionsInput();
        ObjectNode q = (ObjectNode) input.withArray("questions").get(0);
        ObjectNode opt = (ObjectNode) q.withArray("options").get(0);
        opt.put("preview", preview);
        return input;
    }

    private static ObjectNode questionsInput() {
        ObjectNode input = M.createObjectNode();
        ArrayNode qs = input.putArray("questions");
        ObjectNode q = qs.addObject();
        q.put("question", "Which base?");
        q.put("header", "Base");
        q.put("multiSelect", false);
        ArrayNode opts = q.putArray("options");
        ObjectNode a = opts.addObject();
        a.put("label", "197");
        a.put("description", "wire capture baseline");
        a.put("preview", "PREVIEW-197");
        ObjectNode b = opts.addObject();
        b.put("label", "1.0.2");
        b.put("description", "reference source baseline");
        return input;
    }

    @Test
    void checkPermissionsAlwaysReturnsAsk() {
        PermissionDecision d = new AskUserQuestionTool()
            .checkPermissions(questionsInput(), null);
        assertInstanceOf(PermissionDecision.Ask.class, d, "AskUserQuestion must always route through the interactive prompt");
    }

    @Test
    void descriptionMatchesReleased197Base() {
        AskUserQuestionTool.setPreviewFormat(null);

        String description = new AskUserQuestionTool().description();
        assertFalse(Strings.CS.contains(description, "Preview feature:"), description);
        assertEquals(842, description.length());
        String prompt = new AskUserQuestionTool().prompt(null);
        assertEquals(description, prompt);
    }

    @Test
    void promptFollowsPreviewHostCapability() {
        AskUserQuestionTool.setPreviewFormat("markdown");
        String markdown = new AskUserQuestionTool().prompt(null);
        assertEquals(1531, markdown.length());
        assertTrue(Strings.CS.contains(markdown, "Preview content is rendered as markdown"), markdown);

        AskUserQuestionTool.setPreviewFormat("html");
        String html = new AskUserQuestionTool().prompt(null);
        assertNotEquals(markdown, html);
        assertTrue(Strings.CS.contains(html, "self-contained HTML fragment"), html);
        assertFalse(Strings.CS.contains(html, "rendered as markdown"), html);

        AskUserQuestionTool.setPreviewFormat(null);
        assertFalse(Strings.CS.contains(new AskUserQuestionTool().prompt(null), "Preview feature:"));
    }

    @Test
    void requiresUserInteractionIsTrue() {
        assertTrue(new AskUserQuestionTool().requiresUserInteraction());
    }

    @Test
    void parseQuestions_returnsNullOnMalformed() {
        assertNull(AskUserQuestionTool.parseQuestions(M.createObjectNode()),
            "missing questions array");
        ObjectNode bad = questionsInput();
        bad.withArray("questions").removeAll(); // empty array
        assertNull(AskUserQuestionTool.parseQuestions(bad));
    }

    @Test
    void parseQuestions_returnsQuestionsOnValidShape() {
        List<QuestionPresenter.Question> qs = AskUserQuestionTool.parseQuestions(questionsInput());
        assertNotNull(qs);
        assertEquals(1, qs.size());
        assertEquals("Which base?", qs.getFirst().question());
        assertEquals(2, qs.getFirst().options().size());
    }

    @Test
    void buildAnswerInput_foldsSingleSelectAnswer() {
        ObjectNode original = questionsInput();
        Map<String, QuestionPresenter.Answer> answers = new LinkedHashMap<>();
        answers.put("Which base?", new QuestionPresenter.Answer("197", "PREVIEW-197", null));

        JsonNode rewritten = AskUserQuestionTool.buildAnswerInput(original, answers);

        assertEquals("197", rewritten.at("/answers/Which base?").asText());
        assertEquals("PREVIEW-197", rewritten.at("/annotations/Which base?/preview").asText());
        assertFalse(rewritten.at("/annotations/Which base?").has("notes"));
    }

    @Test
    void buildAnswerInput_writesAnEmptyAnnotationsNodeWhenNoQuestionCarriesPreviewOrNotes() {
        ObjectNode original = questionsInput();
        Map<String, QuestionPresenter.Answer> answers = new LinkedHashMap<>();
        answers.put("Which base?", new QuestionPresenter.Answer("197", null, null));

        JsonNode rewritten = AskUserQuestionTool.buildAnswerInput(original, answers);

        assertEquals("197", rewritten.at("/answers/Which base?").asText());
        // zys (and 2.1.197's yCf) spread `annotations` unconditionally — only its entries are
        // conditional, so an answer with neither preview nor notes still leaves an empty object.
        assertTrue(rewritten.get("annotations").isObject());
        assertEquals(0, rewritten.get("annotations").size());
    }

    @Test
    void buildAnswerInput_nullAnswersYieldsEmptyAnswersAndEmptyAnnotations() {
        ObjectNode original = questionsInput();

        JsonNode rewritten = AskUserQuestionTool.buildAnswerInput(original, null);

        assertTrue(rewritten.has("answers"));
        assertEquals(0, rewritten.get("answers").size());
        assertEquals(0, rewritten.get("annotations").size());
    }

    @Test
    void buildAnswerInput_doesNotMutateOriginalInput() {
        ObjectNode original = questionsInput();

        AskUserQuestionTool.buildAnswerInput(original,
            Map.of("Which base?", new QuestionPresenter.Answer("197", null, null)));

        assertFalse(original.has("answers"),
            "the API-bound input must never be mutated (Tool.ts:478 prompt-cache guarantee)");
        assertFalse(original.has("annotations"));
    }

    @Test
    void call_withEmptyAnswersObject_reportsDecline() {

        ObjectNode input = questionsInput();
        input.putObject("answers"); // empty object

        String result = new AskUserQuestionTool().call(input, ctx);

        assertEquals("User declined to answer questions", result);
    }

    @Test
    void buildAnswerInputThenCall_formatsLikeTsWithoutPresenter() {
        ObjectNode input = questionsInput();
        Map<String, QuestionPresenter.Answer> answers = new LinkedHashMap<>();
        answers.put("Which base?",
            new QuestionPresenter.Answer("197", "PREVIEW-197", "prefer wire truth"));

        JsonNode rewritten = AskUserQuestionTool.buildAnswerInput(input, answers);
        String result = new AskUserQuestionTool().call(rewritten, ctx);

        assertTrue(Strings.CS.startsWith(result, "User has answered your questions: "), result);
        assertTrue(Strings.CS.contains(result, "\"Which base?\"=\"197\""), result);
        assertTrue(Strings.CS.contains(result, "selected preview:\nPREVIEW-197"), result);
        assertTrue(Strings.CS.contains(result, "user notes: prefer wire truth"), result);
        assertTrue(Strings.CS.endsWith(result, "You can now continue with the user's answers in mind."), result);
    }

    // ── ① UNIQUENESS_REFINE: question texts + option labels must be unique ──

    @Test
    void validateInput_rejectsDuplicateQuestionText() {
        ObjectNode input = twoQuestionInput("Same?", "Same?", "A", "B", "C", "D");
        ValidationResult r = new AskUserQuestionTool().validateInput(input, ctx);
        assertInstanceOf(ValidationResult.Invalid.class, r, "duplicate question text must be rejected");
        assertTrue(Strings.CS.contains(((ValidationResult.Invalid) r).message(), "unique"),
            "message should mention uniqueness");
    }

    @Test
    void validateInput_rejectsDuplicateOptionLabel() {
        // Distinct question texts, but the first question repeats a label.
        ObjectNode input = twoQuestionInput("Q1?", "Q2?", "A", "A", "C", "D");
        ValidationResult r = new AskUserQuestionTool().validateInput(input, ctx);
        assertInstanceOf(ValidationResult.Invalid.class, r, "duplicate option label within a question must be rejected");
        assertTrue(Strings.CS.contains(((ValidationResult.Invalid) r).message(), "unique"));
    }

    @Test
    void validateInput_allowsDistinctLabelsAcrossQuestions() {
        // Same label "A" in two different questions is fine (uniqueness is per-question).
        ObjectNode input = twoQuestionInput("Q1?", "Q2?", "A", "B", "A", "D");
        ValidationResult r = new AskUserQuestionTool().validateInput(input, ctx);
        assertInstanceOf(ValidationResult.Valid.class, r, "same label in different questions is allowed: " + r);
    }

    // ── ④ html preview validation (only when previewFormat == "html") ──

    @Test
    void validateInput_htmlPreviewRejectedWhenFormatHtml() {
        AskUserQuestionTool.setPreviewFormat("html");
        try {
            ObjectNode input = inputWithPreview("<script>alert(1)</script>");
            ValidationResult r = new AskUserQuestionTool().validateInput(input, ctx);
            assertInstanceOf(ValidationResult.Invalid.class, r, "html preview with <script> must be rejected when format=html");
            assertTrue(Strings.CS.contains(((ValidationResult.Invalid) r).message(), "script"));
        } finally {
            AskUserQuestionTool.setPreviewFormat(null);
        }
    }

    @Test
    void validateInput_validHtmlFragmentPassesWhenFormatHtml() {
        AskUserQuestionTool.setPreviewFormat("html");
        try {
            ObjectNode input = inputWithPreview("<div>hello</div>");
            ValidationResult r = new AskUserQuestionTool().validateInput(input, ctx);
            assertInstanceOf(ValidationResult.Valid.class, r, "well-formed html fragment should pass: " + r);
        } finally {
            AskUserQuestionTool.setPreviewFormat(null);
        }
    }

    @Test
    void validateInput_htmlPreviewSkippedWhenFormatNull() {
        AskUserQuestionTool.setPreviewFormat(null);
        // Default (markdown) mode: a <script> preview is NOT validated.
        ObjectNode input = inputWithPreview("<script>alert(1)</script>");
        ValidationResult r = new AskUserQuestionTool().validateInput(input, ctx);
        assertInstanceOf(ValidationResult.Valid.class, r, "markdown (null) format must NOT validate previews");
    }

    @Test
    void isConcurrencySafe_trueLikeTs() {

        assertTrue(new AskUserQuestionTool().isConcurrencySafe());
    }
}
