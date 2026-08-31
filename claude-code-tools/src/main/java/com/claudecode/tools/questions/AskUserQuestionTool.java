package com.claudecode.tools.questions;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.engine.PermissionAskCallback;
import com.claudecode.core.engine.PermissionAskContext;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.InteractiveChannelGate;
import com.claudecode.tools.ToolTexts;
import com.claudecode.tools.ToolCallResult;
import com.claudecode.tools.ValidationResult;

/**
 * AskUserQuestion — present 1-4 multiple-choice questions and return the user's answers to the
 * model.
 */
@BuiltInTool(
    name = "AskUserQuestion",
    shouldDefer = true,
    readOnly = true,
    concurrencySafe = true
)
public class AskUserQuestionTool extends AnnotatedTool<JsonNode, String> {


    @Override
    public String searchHint() {
        return "prompt the user with a multiple-choice question";
    }

    private static final JsonNode SCHEMA = buildSchema();
    static final int MAX_QUESTIONS = 4;
    static final int MIN_OPTIONS = 2;
    static final int MAX_OPTIONS = 4;


    @Explanation("Accepts a layered preview-format setting while keeping SDK-undefined and CLI-markdown states distinct.")
    private static volatile String previewFormat = null;

    public static void setPreviewFormat(String format) {
        previewFormat = format;
    }

    private static final String UNIQUENESS_MESSAGE =
        "Question texts must be unique, option labels must be unique within each question";


    private static final Pattern HTML_DOC_TAG =
        Pattern.compile("<\\s*(html|body|!doctype)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_SCRIPT_STYLE =
        Pattern.compile("<\\s*(script|style)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_HAS_TAG =
        Pattern.compile("<[a-z][^>]*>", Pattern.CASE_INSENSITIVE);

    public AskUserQuestionTool() {}

    @Override
    public String description() {
        return ToolTexts.description("AskUserQuestion");
    }


    @Override
    public String prompt(ToolExecutionContext context) {
        if (previewFormat == null) return description();
        return switch (previewFormat) {
            case "markdown" -> description()
                + ToolTexts.prompt("AskUserQuestion", "markdown-preview");
            case "html" -> description()
                + ToolTexts.prompt("AskUserQuestion", "html-preview");
            default -> description();
        };
    }

    @Override
    public JsonNode inputSchema() { return SCHEMA; }


    @Override
    public Object toAutoClassifierInput(JsonNode input) {
        if (input == null || !input.path("questions").isArray()) return "";
        List<String> questions = new ArrayList<>();
        input.path("questions").forEach(question -> {
            String text = question.path("question").asText("");
            if (!StringUtils.isBlank(text)) questions.add(text);
        });
        return String.join(" | ", questions);
    }




    @Override
    public boolean isEnabled() { return InteractiveChannelGate.terminalInteractionAvailable(); }


    @Override
    public boolean requiresUserInteraction() { return true; }


    /**
     * Semantic pre-execution check.
     */
    @Override
    public ValidationResult validateInput(JsonNode input, ToolExecutionContext context) {
        // ① UNIQUENESS_REFINE — question-text part. {@code parseQuestions}
        // collapses a duplicate question text to null (treated as malformed),

        // we check it independently of the structural parse below.
        JsonNode qsRaw = input.get("questions");
        if (qsRaw != null && qsRaw.isArray()) {
            Set<String> seenQuestions = new LinkedHashSet<>();
            for (JsonNode q : qsRaw) {
                if (!seenQuestions.add(q.path("question").asText(""))) {
                    return ValidationResult.invalid(UNIQUENESS_MESSAGE);
                }
            }
        }
        List<QuestionPresenter.Question> qs = parseQuestions(input);
        if (qs == null) {
// Malformed input is reported by call/the structural gate; the
            // uniqueness/html checks below only apply to well-formed inputs.
            return ValidationResult.valid();
        }
        // ① UNIQUENESS_REFINE — option labels unique within each question.
        for (QuestionPresenter.Question q : qs) {
            Set<String> seenLabels = new LinkedHashSet<>();
            for (QuestionPresenter.Option o : q.options()) {
                if (!seenLabels.add(o.label())) {
                    return ValidationResult.invalid(UNIQUENESS_MESSAGE);
                }
            }
        }
        // ④ html preview validation (only when the preview format is 'html').
        if (Strings.CS.equals("html", previewFormat)) {
            for (QuestionPresenter.Question q : qs) {
                for (QuestionPresenter.Option o : q.options()) {
                    String err = validateHtmlPreview(o.preview());
                    if (err != null) {
                        return ValidationResult.invalid(
                            "Option \"" + o.label() + "\" in question \"" + q.question() + "\": " + err);
                    }
                }
            }
        }
        return ValidationResult.valid();
    }

    @Override
    public String call(JsonNode input, ToolExecutionContext context) {
        return callWithResult(input, context).rawResult();
    }

    @Override
    public ToolCallResult<String> callWithResult(JsonNode input, ToolExecutionContext context) {
        List<QuestionPresenter.Question> questions = parseQuestions(input);
        if (questions == null) {
            return mapped(input, "Error: questions array is required (1-" + MAX_QUESTIONS
                + " items, each with question, header, options, multiSelect)", null);
        }

        // Answers may arrive pre-supplied (SDK callers / replays) or, on the
        // production path, rewritten into the input by the permission callback
        // (buildAnswerInput → updatedInput). Either way, format and return.
        JsonNode answersNode = input.get("answers");
        if (answersNode != null && answersNode.isObject()) {
            if (!answersNode.isEmpty()) {
                AskInvocation invocation = new AskInvocation(input.get("questions").deepCopy(),
                    answersNode.deepCopy(), input.get("annotations") == null
                        ? null : input.get("annotations").deepCopy());
                return mapped(input, formatResult(answersNode, input.get("annotations")), invocation);
            }
// Empty answers object = the user declined in the permission prompt.
            return mapped(input, "User declined to answer questions", null);
        }

        // No answers supplied — direct tool/SDK callers still use the same
        // typed interaction callback as the ordinary permission path.
        PermissionAskCallback callback = context == null
            ? null : context.permissionAskCallback();
        if (callback == null) {
            return mapped(input, "Error: AskUserQuestion requires an interactive session "
                + "(no question UI is available)", null);
        }
        PermissionAskCallback.Result response = callback.ask(
            PermissionAskContext.simple("AskUserQuestion", input, context.toolUseId()));
        if (!response.allowed()) {
            return mapped(input, "User declined to answer questions", null);
        }
        JsonNode rewritten = response.updatedInput();
        if (rewritten == null || !rewritten.path("answers").isObject()) {
            return mapped(input, "Error: AskUserQuestion requires an interactive session "
                + "(no question UI is available)", null);
        }
        return callWithResult(rewritten, context);
    }

    @Override
    public PermissionDecision checkPermissions(JsonNode input, ToolPermissionContext context) {

        // can replace it with the collected answers without mutating the API
        // request's original tool-use block.
        return new PermissionDecision.Ask(null, input, "Answer questions?", null, null, List.of());
    }

    private ToolCallResult<String> mapped(JsonNode input, String text, AskInvocation supplied) {
        AskInvocation invocation = supplied;
        if (invocation == null) {
            JsonNode questions = input == null ? mapper().createArrayNode() : input.get("questions");
            JsonNode answers = input == null ? mapper().createObjectNode() : input.path("answers");
            invocation = new AskInvocation(questions == null ? mapper().createArrayNode() : questions.deepCopy(),
                answers.deepCopy(), input == null ? null : input.get("annotations"));
        }
        ObjectNode data = mapper().createObjectNode();
        data.set("questions", invocation.questions());
        data.set("answers", invocation.answers());
        if (invocation.annotations() != null) data.set("annotations", invocation.annotations());
        return new ToolCallResult<>(text, ToolResult.success(text).withToolUseResult(data));
    }



    /** Input-supplied variant ({@code answers} + optional {@code annotations} nodes). */
    private static String formatResult(JsonNode answers, JsonNode annotations) {
        List<String> entries = new ArrayList<>();
        answers.fields().forEachRemaining(e -> {
            String q = e.getKey();
            JsonNode ann = annotations != null ? annotations.get(q) : null;
            entries.add(formatEntry(q, e.getValue().asText(""),
                ann != null ? ann.path("preview").asText(null) : null,
                ann != null ? ann.path("notes").asText(null) : null));
        });
        return "User has answered your questions: " + String.join(", ", entries)
            + ". You can now continue with the user's answers in mind.";
    }


    private static String formatEntry(String question, String answer,
                                      String preview, String notes) {
        List<String> parts = new ArrayList<>();
        parts.add("\"" + question + "\"=\"" + answer + "\"");
        if (StringUtils.isNotBlank(preview)) {
            parts.add("selected preview:\n" + preview);
        }
        if (StringUtils.isNotBlank(notes)) {
            parts.add("user notes: " + notes);
        }
        return String.join(" ", parts);
    }

    // ── input parsing ───────────────────────────────────────────────────────


    public static List<QuestionPresenter.Question> parseQuestions(JsonNode input) {
        JsonNode arr = input.get("questions");
        if (arr == null || !arr.isArray() || arr.isEmpty() || arr.size() > MAX_QUESTIONS) {
            return null;
        }
        List<QuestionPresenter.Question> out = new ArrayList<>(arr.size());
        for (JsonNode q : arr) {
            String text = q.path("question").asText("");
            String header = q.path("header").asText("");
            boolean multiSelect = q.path("multiSelect").asBoolean(false);
            JsonNode optsNode = q.get("options");
            if (StringUtils.isBlank(text) || optsNode == null || !optsNode.isArray()
                    || optsNode.size() < MIN_OPTIONS || optsNode.size() > MAX_OPTIONS) {
                return null;
            }
            List<QuestionPresenter.Option> opts = new ArrayList<>(optsNode.size());
            for (JsonNode o : optsNode) {
                String label = o.path("label").asText("");
                if (StringUtils.isBlank(label)) return null;
                opts.add(new QuestionPresenter.Option(
                    label,
                    o.path("description").asText(""),
                    o.hasNonNull("preview") ? o.get("preview").asText() : null));
            }
            out.add(new QuestionPresenter.Question(text, header, opts, multiSelect));
        }
        // Answers are keyed by question text — duplicates would collide.
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (var q : out) {
            if (seen.put(q.question(), Boolean.TRUE) != null) return null;
        }
        return out;
    }

    /**
     * Folds collected answers into a rewritten tool input, to be handed back through the permission
     * callback's {@code updatedInput}.
     */
    public static JsonNode buildAnswerInput(JsonNode originalInput,
                                             Map<String, QuestionPresenter.Answer> answers) {
        ObjectNode copy = (ObjectNode) originalInput.deepCopy();
        ObjectNode answersNode = copy.putObject("answers");
        ObjectNode annNode = copy.putObject("annotations");
        if (answers != null) {
            for (Map.Entry<String, QuestionPresenter.Answer> e : answers.entrySet()) {
                String q = e.getKey();
                QuestionPresenter.Answer a = e.getValue();
                if (a == null) continue;
                answersNode.put(q, a.answer());
                boolean hasPreview = StringUtils.isNotBlank(a.preview());
                boolean hasNotes = StringUtils.isNotBlank(a.notes());
                if (hasPreview || hasNotes) {
                    ObjectNode ann = annNode.putObject(q);
                    if (hasPreview) ann.put("preview", a.preview());
                    if (hasNotes) ann.put("notes", a.notes());
                }
            }
        }

        if (annNode.isEmpty()) {
            copy.remove("annotations");
        }
        return copy;
    }


    static String validateHtmlPreview(String preview) {
        if (preview == null) return null;
        if (HTML_DOC_TAG.matcher(preview).find()) {
            return "preview must be an HTML fragment, not a full document (no <html>, <body>, or <!DOCTYPE>)";
        }
        // SDK consumers typically set this via innerHTML — disallow executable/
        // style tags so a preview can't run code or restyle the host page.
        if (HTML_SCRIPT_STYLE.matcher(preview).find()) {
            return "preview must not contain <script> or <style> tags. Use inline styles via the style attribute if needed.";
        }
        if (!HTML_HAS_TAG.matcher(preview).find()) {
            return "preview must contain HTML (previewFormat is set to \"html\"). Wrap content in a tag like <div> or <pre>.";
        }
        return null;
    }



    private static JsonNode buildSchema() {
        ObjectNode schema = mapper().createObjectNode();
        schema.put("type", "object");

        // nested objects below intentionally stay permissive (plain z.object).
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");

        ObjectNode questions = properties.putObject("questions");
        questions.put("description", "Questions to ask the user (1-4 questions)");
        questions.put("minItems", 1);
        questions.put("maxItems", 4);
        questions.put("type", "array");
        ObjectNode qItem = questions.putObject("items");
        qItem.put("type", "object");
        ObjectNode qProps = qItem.putObject("properties");

        ObjectNode questionProp = qProps.putObject("question");
        questionProp.put("description",
            "The complete question to ask the user. Should be clear, specific, and "
            + "end with a question mark. Example: \"Which library should we use for "
            + "date formatting?\" If multiSelect is true, phrase it accordingly, "
            + "e.g. \"Which features do you want to enable?\"");
        questionProp.put("type", "string");

        ObjectNode headerProp = qProps.putObject("header");
        headerProp.put("description",
            "Very short label displayed as a chip/tag (max 12 chars). Examples: "
            + "\"Auth method\", \"Library\", \"Approach\".");
        headerProp.put("type", "string");

        ObjectNode optionsProp = qProps.putObject("options");
        optionsProp.put("description",
            "The available choices for this question. Must have 2-4 options. Each "
            + "option should be a distinct, mutually exclusive choice (unless "
            + "multiSelect is enabled). There should be no 'Other' option, that "
            + "will be provided automatically.");
        optionsProp.put("minItems", 2);
        optionsProp.put("maxItems", 4);
        optionsProp.put("type", "array");
        ObjectNode oItem = optionsProp.putObject("items");
        oItem.put("type", "object");
        ObjectNode oProps = oItem.putObject("properties");
        ObjectNode labelProp = oProps.putObject("label");
        labelProp.put("description",
            "The display text for this option that the user will see and select. "
            + "Should be concise (1-5 words) and clearly describe the choice.");
        labelProp.put("type", "string");
        ObjectNode descProp = oProps.putObject("description");
        descProp.put("description",
            "Explanation of what this option means or what will happen if chosen. "
            + "Useful for providing context about trade-offs or implications.");
        descProp.put("type", "string");
        ObjectNode previewProp = oProps.putObject("preview");
        previewProp.put("description",
            "Optional preview content rendered when this option is focused. Use "
            + "for mockups, code snippets, or visual comparisons that help users "
            + "compare options. See the tool description for the expected content "
            + "format.");
        previewProp.put("type", "string");
        ArrayNode oRequired = oItem.putArray("required");
        oRequired.add("label");
        oRequired.add("description");

        // must stay permissive, not additionalProperties:false.

        ObjectNode multiProp = qProps.putObject("multiSelect");
        multiProp.put("description",
            "Set to true to allow the user to select multiple options instead of "
            + "just one. Use when choices are not mutually exclusive.");
        multiProp.put("default", false);
        multiProp.put("type", "boolean");

        ArrayNode qRequired = qItem.putArray("required");
        qRequired.add("question");
        qRequired.add("header");
        qRequired.add("options");
        qRequired.add("multiSelect");

        // stay permissive, not additionalProperties:false.

        ObjectNode answersProp = properties.putObject("answers");
        answersProp.put("description", "User answers collected by the permission component");
        answersProp.put("type", "object");
        answersProp.putObject("propertyNames").put("type", "string");
        answersProp.putObject("additionalProperties").put("type", "string");

        ObjectNode annotationsProp = properties.putObject("annotations");
        annotationsProp.put("description",
            "Optional per-question annotations from the user (e.g., notes on "
            + "preview selections). Keyed by question text.");
        annotationsProp.put("type", "object");
        annotationsProp.putObject("propertyNames").put("type", "string");
        ObjectNode annValue = annotationsProp.putObject("additionalProperties");
        annValue.put("type", "object");
        ObjectNode annProps = annValue.putObject("properties");
        annProps.putObject("preview")
            .put("description",
                "The preview content of the selected option, if the question used previews.")
            .put("type", "string");
        annProps.putObject("notes")
            .put("description", "Free-text notes the user added to their selection.")
            .put("type", "string");

        // stays permissive, not additionalProperties:false.

        ObjectNode metadataProp = properties.putObject("metadata");
        metadataProp.put("description",
            "Optional metadata for tracking and analytics purposes. Not displayed to user.");
        metadataProp.put("type", "object");
        ObjectNode mdProps = metadataProp.putObject("properties");
        mdProps.putObject("source")
            .put("description",
                "Optional identifier for the source of this question (e.g., "
                + "\"remember\" for /remember command). Used for analytics tracking.")
            .put("type", "string");

        // permissive, not additionalProperties:false.

        ArrayNode required = schema.putArray("required");
        required.add("questions");

        return schema;
    }

    private record AskInvocation(JsonNode questions, JsonNode answers, JsonNode annotations) {}
}
