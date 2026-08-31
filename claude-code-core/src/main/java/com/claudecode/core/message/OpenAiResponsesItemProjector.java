package com.claudecode.core.message;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Projects shared conversation messages onto OpenAI Responses input items.
 * The projection is shared by the wire client and the compatible context
 * estimator.
 */
@Explanation("Shared OpenAI Responses input-item projection")
public final class OpenAiResponsesItemProjector {

    private OpenAiResponsesItemProjector() {}

    /** Projects a core message, including attachment expansion, into Responses items. */
    public static List<ObjectNode> projectMessage(Message message) {
        if (message instanceof AssistantMessage assistant) {
            if (assistant.message() == null || assistant.message().content() == null) return List.of();
            return project("assistant", assistant.message().content());
        }
        if (message instanceof UserMessage user) {
            if (user.message() == null) return List.of();
            if (user.message().isText()) return project("user", user.message().text());
            return project("user", user.message().blocks());
        }
        if (message instanceof AttachmentMessage attachment && attachment.payload() != null) {
            List<ObjectNode> items = new ArrayList<>();
            for (UserMessage rendered : AttachmentRenderer.render(attachment.payload())) {
                items.addAll(projectMessage(rendered));
            }
            return List.copyOf(items);
        }
        return List.of();
    }

    /** Projects one role/content request turn into Responses input items. */
    public static List<ObjectNode> project(String role, Object content) {
        return project(role, content, OpenAiResponsesItemProjector::imageUrl);
    }

    /** Projects with a caller-provided image resolver/validator for the wire client. */
    public static List<ObjectNode> project(
            String role, Object content, Function<JsonNode, String> imageUrlResolver) {
        Function<JsonNode, String> resolvedImageUrlResolver = imageUrlResolver != null
            ? imageUrlResolver : OpenAiResponsesItemProjector::imageUrl;
        return project(role, content, resolvedImageUrlResolver,
            blocks -> responsesToolContent(blocks, resolvedImageUrlResolver));
    }

    /** Projects with caller-provided image and tool-result content lowering for the wire client. */
    public static List<ObjectNode> project(
            String role, Object content, Function<JsonNode, String> imageUrlResolver,
            Function<List<ContentBlock>, ArrayNode> toolContentProjector) {
        Function<JsonNode, String> resolvedImageUrlResolver = imageUrlResolver != null
            ? imageUrlResolver : OpenAiResponsesItemProjector::imageUrl;
        Function<List<ContentBlock>, ArrayNode> resolvedToolContentProjector =
            toolContentProjector != null ? toolContentProjector
                : blocks -> responsesToolContent(blocks, resolvedImageUrlResolver);
        ArrayNode input = JsonUtils.getMapper().createArrayNode();
        appendInputItems(input, role, content,
            resolvedImageUrlResolver, resolvedToolContentProjector);
        List<ObjectNode> items = new ArrayList<>(input.size());
        for (JsonNode item : input) {
            if (item instanceof ObjectNode object) items.add(object);
        }
        return List.copyOf(items);
    }

    private static void appendInputItems(
            ArrayNode input, String role, Object content,
            Function<JsonNode, String> imageUrlResolver,
            Function<List<ContentBlock>, ArrayNode> toolContentProjector) {
        if (content instanceof String text) {
            appendMessage(input, role, List.of(responsesText(textType(role), text)));
            return;
        }
        if (!(content instanceof List<?> items)) {
            appendMessage(input, role, List.of(
                responsesText(textType(role), String.valueOf(content))));
            return;
        }

        List<JsonNode> messageBlocks = new ArrayList<>();
        for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
            Object item = items.get(itemIndex);
            JsonNode node = JsonUtils.getMapper().valueToTree(item);
            String type = node.path("type").asText("");
            if (Strings.CS.equals("thinking", type) || item instanceof ThinkingBlock) {
                flushMessage(input, role, messageBlocks);
                ArrayNode summaries = JsonUtils.getMapper().createArrayNode();
                String encrypted = "";
                int end = itemIndex;
                while (end < items.size()) {
                    Object candidate = items.get(end);
                    JsonNode thinking = JsonUtils.getMapper().valueToTree(candidate);
                    if (!Strings.CS.equals("thinking", thinking.path("type").asText(""))
                            && !(candidate instanceof ThinkingBlock)) break;
                    String text = candidate instanceof ThinkingBlock block
                        ? block.thinking() : thinking.path("thinking").asText("");
                    String signature = candidate instanceof ThinkingBlock block
                        ? block.signature() : thinking.path("signature").asText("");
                    summaries.addObject().put("type", "summary_text").put("text", text);
                    if (StringUtils.isNotBlank(signature)) encrypted = signature;
                    end++;
                }
                if (!StringUtils.isBlank(encrypted)) {
                    ObjectNode reasoning = input.addObject();
                    reasoning.put("type", "reasoning");
                    reasoning.set("summary", summaries);
                    reasoning.put("encrypted_content", encrypted);
                }
                itemIndex = end - 1;
                continue;
            }
            switch (type) {
                case "text" -> messageBlocks.add(responsesText(
                    textType(role), node.path("text").asText("")));
                case "image" -> {
                    if (Strings.CS.equals("assistant", role)) {
                        throw new IllegalArgumentException(
                            "OpenAI Responses assistant messages do not support image content");
                    }
                    messageBlocks.add(responsesImage(node.get("source"), imageUrlResolver));
                }
                case "tool_use" -> {
                    flushMessage(input, role, messageBlocks);
                    ObjectNode call = input.addObject();
                    call.put("type", "function_call");
                    call.put("call_id", node.path("id").asText("call_" + UUID.randomUUID()));
                    call.put("name", node.path("name").asText());
                    call.put("arguments", node.path("input").isMissingNode()
                        ? "{}" : node.path("input").toString());
                }
                case "tool_result" -> {
                    flushMessage(input, role, messageBlocks);
                    appendToolOutput(input, item, node, toolContentProjector);
                }
                default -> appendTypedBlock(
                    input, role, messageBlocks, item, node, imageUrlResolver,
                    toolContentProjector);
            }
        }
        flushMessage(input, role, messageBlocks);
    }

    private static void appendTypedBlock(
            ArrayNode input, String role, List<JsonNode> messageBlocks,
            Object item, JsonNode node, Function<JsonNode, String> imageUrlResolver,
            Function<List<ContentBlock>, ArrayNode> toolContentProjector) {
        if (item instanceof TextBlock(String text)) {
            messageBlocks.add(responsesText(textType(role), text));
        } else if (item instanceof ImageBlock(JsonNode source)) {
            messageBlocks.add(responsesImage(source, imageUrlResolver));
        } else if (item instanceof ToolUseBlock toolUse) {
            flushMessage(input, role, messageBlocks);
            ObjectNode call = input.addObject();
            call.put("type", "function_call");
            call.put("call_id", toolUse.id());
            call.put("name", toolUse.name());
            call.put("arguments", toolUse.input() == null ? "{}" : toolUse.input().toString());
        } else if (item instanceof ToolResultBlock result) {
            flushMessage(input, role, messageBlocks);
            appendToolOutput(input, result, node, toolContentProjector);
        }
    }

    private static void appendMessage(ArrayNode input, String role, List<JsonNode> blocks) {
        List<JsonNode> mutable = new ArrayList<>(blocks);
        flushMessage(input, role, mutable);
    }

    private static void flushMessage(ArrayNode input, String role, List<JsonNode> blocks) {
        if (blocks.isEmpty()) return;
        ObjectNode message = input.addObject();
        message.put("role", role);
        message.put("type", "message");
        ArrayNode content = message.putArray("content");
        blocks.forEach(content::add);
        blocks.clear();
    }

    private static String textType(String role) {
        return Strings.CS.equals("assistant", role) ? "output_text" : "input_text";
    }

    private static void appendToolOutput(
            ArrayNode input, Object original, JsonNode node,
            Function<List<ContentBlock>, ArrayNode> toolContentProjector) {
        ObjectNode output = input.addObject();
        output.put("type", "function_call_output");
        output.put("call_id", node.path("tool_use_id").asText());
        if (original instanceof ToolResultBlock result
                && (result.preserveContentBlocks()
                    || result.content() != null
                    && result.content().stream().anyMatch(ImageBlock.class::isInstance))) {
            output.set("output", toolContentProjector.apply(result.content()));
        } else {
            output.put("output", textContent(node.get("content")));
        }
    }

    private static ObjectNode responsesText(String type, String text) {
        return JsonUtils.getMapper().createObjectNode().put("type", type).put("text", text);
    }

    private static ObjectNode responsesImage(
            JsonNode source, Function<JsonNode, String> imageUrlResolver) {
        return JsonUtils.getMapper().createObjectNode()
            .put("type", "input_image")
            .put("image_url", imageUrlResolver.apply(source));
    }

    private static ArrayNode responsesToolContent(
            List<ContentBlock> content, Function<JsonNode, String> imageUrlResolver) {
        ArrayNode output = JsonUtils.getMapper().createArrayNode();
        if (content == null) return output;
        for (ContentBlock block : content) {
            if (block instanceof TextBlock(String text1)) {
                output.add(responsesText("input_text", text1));
            } else if (block instanceof ImageBlock(JsonNode source)) {
                output.add(responsesImage(source, imageUrlResolver));
            }
        }
        return output;
    }

    private static String imageUrl(JsonNode source) {
        if (source == null || source.isNull()) return "";
        if (Strings.CS.equals("url", source.path("type").asText(""))) {
            return source.path("url").asText("");
        }
        if (Strings.CS.equals("base64", source.path("type").asText(""))) {
            String data = source.path("data").asText("");
            if (Strings.CS.startsWith(data, "data:")) return data;
            return "data:" + source.path("media_type").asText("application/octet-stream")
                + ";base64," + data;
        }
        return "";
    }

    private static String textContent(JsonNode content) {
        if (content == null || content.isNull()) return "";
        if (content.isTextual()) return content.asText();
        if (!content.isArray()) return content.toString();
        StringBuilder text = new StringBuilder();
        for (JsonNode part : content) {
            if (!text.isEmpty()) text.append('\n');
            text.append(part.path("text").asText(part.toString()));
        }
        return text.toString();
    }
}
