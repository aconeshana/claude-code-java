package com.claudecode.tools.questions;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.PermissionAskCallback;
import com.claudecode.core.engine.ToolExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;


class AskUserQuestion197Test {

    private static final ObjectMapper M = new ObjectMapper();
    private final ToolExecutionContext ctx =
        ToolExecutionContext.of(new AbortController(), "test-session");

    private static ObjectNode questionsInput(boolean multiSelect) {
        ObjectNode input = M.createObjectNode();
        ArrayNode qs = input.putArray("questions");
        ObjectNode q = qs.addObject();
        q.put("question", "Which base?");
        q.put("header", "Base");
        q.put("multiSelect", multiSelect);
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
    void schemaMatches197Shape() {
        JsonNode s = new AskUserQuestionTool().inputSchema();
        assertEquals(List.of("questions"),
            List.of(s.get("required").get(0).asText()));
        JsonNode qItem = s.at("/properties/questions/items");
        // Per-question required: question, header, options, multiSelect.
        assertEquals(4, qItem.get("required").size());
        assertTrue(Strings.CS.contains(qItem.get("required").toString(), "multiSelect"));
        assertFalse(Strings.CS.contains(qItem.get("required").toString(), "multi_select"),
            "snake_case param must be gone");
        // Options items require label + description.
        JsonNode oReq = qItem.at("/properties/options/items/required");
        assertEquals(2, oReq.size());
        // Top-level answer-path fields declared.
        assertTrue(s.at("/properties/answers").isObject());
        assertTrue(s.at("/properties/annotations").isObject());
        assertTrue(s.at("/properties/metadata").isObject());
    }

    @Test
    void presenterAnswersFormatLikeTs() {
        ToolExecutionContext interactive = ToolExecutionContext.builder(
                new AbortController(), "test-session")
            .permissionAskCallback(context -> {
            List<QuestionPresenter.Question> questions =
                AskUserQuestionTool.parseQuestions(context.input());
            assertNotNull(questions);
            assertEquals(1, questions.size());
            assertEquals("Base", questions.getFirst().header());
            Map<String, QuestionPresenter.Answer> out = new LinkedHashMap<>();
            out.put("Which base?",
                new QuestionPresenter.Answer("197", "PREVIEW-197", "prefer wire truth"));
            return PermissionAskCallback.Result.allowWithInput(
                AskUserQuestionTool.buildAnswerInput(context.input(), out));
        }).build();
        String result = new AskUserQuestionTool().call(questionsInput(false), interactive);
        assertTrue(Strings.CS.startsWith(result, "User has answered your questions: "), result);
        assertTrue(Strings.CS.contains(result, "\"Which base?\"=\"197\""), result);
        assertTrue(Strings.CS.contains(result, "selected preview:\nPREVIEW-197"), result);
        assertTrue(Strings.CS.contains(result, "user notes: prefer wire truth"), result);
        assertTrue(Strings.CS.endsWith(result, "You can now continue with the user's answers in mind."), result);
    }

    @Test
    void preSuppliedAnswersSkipPresenter() {
        ObjectNode input = questionsInput(false);
        input.putObject("answers").put("Which base?", "1.0.2");
        String result = new AskUserQuestionTool().call(input, ctx);
        assertTrue(Strings.CS.contains(result, "\"Which base?\"=\"1.0.2\""), result);
    }

    @Test
    void cancelReportsDecline() {
        ToolExecutionContext interactive = ToolExecutionContext.builder(
                new AbortController(), "test-session")
            .permissionAskCallback(_ -> PermissionAskCallback.Result.deny())
            .build();
        String result = new AskUserQuestionTool().call(questionsInput(false), interactive);
        assertEquals("User declined to answer questions", result);
    }

    @Test
    void headlessWithoutPresenterErrors() {
        String result = new AskUserQuestionTool().call(questionsInput(false), ctx);
        assertTrue(Strings.CS.startsWith(result, "Error: AskUserQuestion requires an interactive session"), result);
    }

    @Test
    void malformedQuestionsRejected() {
        AskUserQuestionTool tool = new AskUserQuestionTool();
        // No questions array at all (old single-question shape is gone).
        ObjectNode legacy = M.createObjectNode();
        legacy.put("question", "solo?");
        assertTrue(Strings.CS.startsWith(tool.call(legacy, ctx), "Error: questions array is required"));
        // Only one option.
        ObjectNode input = M.createObjectNode();
        ObjectNode q = input.putArray("questions").addObject();
        q.put("question", "q?");
        q.put("header", "H");
        q.putArray("options").addObject().put("label", "only").put("description", "d");
        assertTrue(Strings.CS.startsWith(tool.call(input, ctx), "Error: questions array is required"));
    }
}
