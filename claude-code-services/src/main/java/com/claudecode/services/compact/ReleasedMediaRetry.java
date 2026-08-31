package com.claudecode.services.compact;

import com.claudecode.api.ApiException;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.DocumentBlock;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.UserMessage;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.Strings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


final class ReleasedMediaRetry {

    private static final Pattern MEDIA_PATH = Pattern.compile(
        "messages[.\\[]([0-9]+)[\\].]+content[.\\[]([0-9]+)[\\].]+"
            + "(?:tool_result[.\\[]content[.\\[][0-9]+[\\].]+)?(image|document|pdf)");
    private static final Pattern ERROR_PATH_PREFIX = Pattern.compile(
        "(?s).*messages[.\\[][0-9]+[\\].]+content[.\\[][0-9]+[\\].]+\\S*:?\\s*");
    private static final Pattern TRAILING_JSON_DELIMITERS = Pattern.compile("[\"}]+\\s*$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final List<String> IMAGE_ERROR_MARKERS = List.of(
        "could not process image",
        "image exceeds",
        "image dimensions exceed",
        "image does not match the provided media type",
        "image cannot be empty",
        "exceeds api limit",
        "images exceed the api limit",
        "unable to resize image",
        "unable to compress image",
        "image file is empty");
    private static final List<String> DOCUMENT_ERROR_MARKERS = List.of(
        "could not process pdf",
        "pdf pages",
        "the pdf specified was not valid",
        "the pdf specified is password protected",
        "pdf cannot be empty",
        "too much media");

    private ReleasedMediaRetry() {}

    enum Kind {
        IMAGE("image", "Image"),
        DOCUMENT("document", "Document");

        private final String wireName;
        private final String displayName;

        Kind(String wireName, String displayName) {
            this.wireName = wireName;
            this.displayName = displayName;
        }
    }

    record MediaError(Kind kind, Integer messageIndex, Integer contentIndex, ApiException failure) {}

    static MediaError classify(Throwable failure) {
        ApiException apiFailure = findApiFailure(failure);
        if (apiFailure == null || apiFailure.statusCode() != 400) return null;
        String message = apiFailure.getMessage();
        if (message == null) return null;

        Matcher path = MEDIA_PATH.matcher(message);
        if (path.find()) {
            Kind kind = Strings.CS.equals(path.group(3), "image") ? Kind.IMAGE : Kind.DOCUMENT;
            return new MediaError(
                kind,
                Integer.parseInt(path.group(1)),
                Integer.parseInt(path.group(2)),
                apiFailure);
        }

        String normalized = message.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, IMAGE_ERROR_MARKERS)) {
            return new MediaError(Kind.IMAGE, null, null, apiFailure);
        }
        if (containsAny(normalized, DOCUMENT_ERROR_MARKERS)) {
            return new MediaError(Kind.DOCUMENT, null, null, apiFailure);
        }
        return null;
    }

    static List<Message> stripForRetry(List<Message> messages, MediaError error) {
        String replacement = placeholder(error.kind(), sdkStyleError(error.failure()));
        if (error.messageIndex() != null && error.contentIndex() != null) {
            return replaceTargeted(messages, error, replacement);
        }
        return replaceLatestBase64Carrier(messages, error.kind(), replacement);
    }

    static String placeholder(Kind kind, String rawError) {
        String detail = ERROR_PATH_PREFIX.matcher(rawError == null ? "" : rawError).replaceFirst("");
        detail = TRAILING_JSON_DELIMITERS.matcher(detail).replaceFirst("");
        detail = WHITESPACE.matcher(detail).replaceAll(" ").trim();
        detail = truncateUtf16(detail, 200);
        String reason = detail.isEmpty() ? "" : " (" + detail + ")";
        return "[" + kind.displayName + " removed: the API could not process this "
            + kind.wireName + reason
            + ". The file may be unsupported or corrupt; do not retry reading it. "
            + "If you need to inspect it, use a shell command instead.]";
    }

    private static List<Message> replaceTargeted(
            List<Message> messages, MediaError error, String replacement) {
        int messageIndex = error.messageIndex();
        if (messageIndex < 0 || messageIndex >= messages.size()) return messages;
        Message candidate = messages.get(messageIndex);
        if (!(candidate instanceof UserMessage user)
                || user.message() == null || user.message().blocks() == null) {
            return messages;
        }
        int contentIndex = error.contentIndex();
        if (contentIndex < 0 || contentIndex >= user.message().blocks().size()) return messages;

        ContentBlock current = user.message().blocks().get(contentIndex);
        ContentBlock replaced = replaceWithinCarrier(current, error.kind(), replacement, false);
        if (replaced == current) return messages;

        List<ContentBlock> blocks = new ArrayList<>(user.message().blocks());
        blocks.set(contentIndex, replaced);
        return replaceUser(messages, messageIndex, user, blocks);
    }

    private static List<Message> replaceLatestBase64Carrier(
            List<Message> messages, Kind kind, String replacement) {
        for (int messageIndex = messages.size() - 1; messageIndex >= 0; messageIndex--) {
            Message candidate = messages.get(messageIndex);
            if (!(candidate instanceof UserMessage user)
                    || user.message() == null || user.message().blocks() == null) {
                continue;
            }
            boolean changed = false;
            List<ContentBlock> blocks = new ArrayList<>(user.message().blocks().size());
            for (ContentBlock block : user.message().blocks()) {
                ContentBlock replaced = replaceWithinCarrier(block, kind, replacement, true);
                changed |= replaced != block;
                blocks.add(replaced);
            }
            if (changed) return replaceUser(messages, messageIndex, user, blocks);
        }
        return messages;
    }

    private static ContentBlock replaceWithinCarrier(
            ContentBlock block, Kind kind, String replacement, boolean requireBase64) {
        if (matches(block, kind, requireBase64)) return new TextBlock(replacement);
        if (!(block instanceof ToolResultBlock toolResult) || toolResult.content() == null) return block;

        boolean changed = false;
        List<ContentBlock> content = new ArrayList<>(toolResult.content().size());
        for (ContentBlock child : toolResult.content()) {
            if (matches(child, kind, requireBase64)) {
                content.add(new TextBlock(replacement));
                changed = true;
            } else {
                content.add(child);
            }
        }
        if (!changed) return block;
        return new ToolResultBlock(
            toolResult.toolUseId(),
            content,
            toolResult.isError(),
            toolResult.includeIsErrorField(),
            toolResult.preserveContentBlocks());
    }

    private static boolean matches(ContentBlock block, Kind kind, boolean requireBase64) {
        JsonNode source;
        if (kind == Kind.IMAGE && block instanceof ImageBlock image) {
            source = image.source();
        } else if (kind == Kind.DOCUMENT && block instanceof DocumentBlock document) {
            source = document.source();
        } else {
            return false;
        }
        return !requireBase64 || source != null && source.isObject()
            && Strings.CS.equals(source.path("type").asText(), "base64");
    }

    private static List<Message> replaceUser(
            List<Message> messages, int index, UserMessage user, List<ContentBlock> blocks) {
        UserMessage replacement = new UserMessage(
            user.uuid(), MessageContent.ofBlocks(blocks), user.isMeta(), user.isCompactSummary(),
            user.toolUseResult(), user.origin(), user.parentUuidValue(), user.timestampValue(),
            user.imagePasteIds(), user.permissionMode(), user.sessionIdValue(),
            user.sourceToolAssistantUUID(), user.sourceToolUseID(), user.isVirtual(),
            user.mcpMeta(), user.isVisibleInTranscriptOnly(), user.planContent(),
            user.summarizeMetadata());
        List<Message> result = new ArrayList<>(messages);
        result.set(index, replacement);
        return result;
    }

    private static String sdkStyleError(ApiException failure) {
        String message = failure.getMessage();
        if (message == null) return failure.statusCode() > 0 ? Integer.toString(failure.statusCode()) : "";
        if (Strings.CS.startsWith(message, "API request failed: ")) {
            String body = message.substring("API request failed: ".length());
            return failure.statusCode() > 0 ? failure.statusCode() + " " + body : body;
        }
        if (Strings.CS.startsWith(message, "API Error: ")) {
            return message.substring("API Error: ".length());
        }
        if (failure.statusCode() > 0
                && !Strings.CS.startsWith(message, failure.statusCode() + " ")) {
            return failure.statusCode() + " " + message;
        }
        return message;
    }

    private static String truncateUtf16(String value, int maxLength) {
        if (value.length() <= maxLength) return value;
        String truncated = value.substring(0, maxLength);
        if (Character.isHighSurrogate(truncated.charAt(maxLength - 1))) {
            return truncated.substring(0, maxLength - 1);
        }
        return truncated;
    }

    private static ApiException findApiFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof ApiException apiFailure) return apiFailure;
        }
        return null;
    }

    private static boolean containsAny(String value, List<String> candidates) {
        return candidates.stream().anyMatch(value::contains);
    }
}
