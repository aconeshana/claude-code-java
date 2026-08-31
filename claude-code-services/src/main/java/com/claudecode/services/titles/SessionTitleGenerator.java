package com.claudecode.services.titles;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.services.model.SideQuery;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;


public final class SessionTitleGenerator {

    private static final Logger log = LoggerFactory.getLogger(SessionTitleGenerator.class);


    private static final String SYSTEM_PROMPT =
        "Generate a short kebab-case name (2-4 words) that captures the main topic of this conversation. "
      + "Use lowercase words separated by hyphens. "
      + "Examples: \"fix-login-bug\", \"add-auth-feature\", \"refactor-api-client\", \"debug-test-failures\". "
      + "Return JSON with a \"name\" field.";


    private static final int MAX_CONVERSATION_TEXT = 1000;

    private final SideQuery sideQuery;

    public SessionTitleGenerator(SideQuery sideQuery) {
        this.sideQuery = sideQuery;
    }

    /**
     * Generate a kebab-case session name from the given messages.
     */
    public String generate(List<Message> messages) {
        String conversationText = extractConversationText(messages);
        if (conversationText.isEmpty()) return null;

        String response = sideQuery.queryHaiku(SYSTEM_PROMPT, conversationText);
        if (response == null) return null;

        return parseName(response);
    }

    /**
     * Flattens a message list to a single user-facing text block, tail-sliced to {@link
     * #MAX_CONVERSATION_TEXT}.
     */
    static String extractConversationText(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return "";

        List<String> parts = new ArrayList<>();
        for (Message m : messages) {
            String text = extractMessageText(m);
            if (StringUtils.isNotBlank(text)) parts.add(text);
        }
        String joined = String.join("\n", parts);
        if (joined.length() > MAX_CONVERSATION_TEXT) {
            return joined.substring(joined.length() - MAX_CONVERSATION_TEXT);
        }
        return joined;
    }

    private static String extractMessageText(Message m) {
        if (m instanceof UserMessage um) {
            if (um.isMeta()) return null;
            MessageContent mc = um.message();
            if (mc == null) return null;
            if (mc.text() != null) return mc.text();
            if (mc.blocks() != null) {
                return joinTextBlocks(mc.blocks());
            }
            return null;
        }
        if (m instanceof AssistantMessage am) {
            AssistantContent ac = am.message();
            if (ac == null || ac.content() == null) return null;
            return joinTextBlocks(ac.content());
        }
        return null;
    }

    private static String joinTextBlocks(List<? extends ContentBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : blocks) {
            if (b instanceof TextBlock tb) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(tb.text());
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /**
     * Tolerant JSON parse: accepts {@code {"name":"..."}} embedded anywhere in the response.
     */
    static String parseName(String text) {
        int braceStart = text.indexOf('{');
        int braceEnd = text.lastIndexOf('}');
        if (braceStart < 0 || braceEnd <= braceStart) return null;
        String json = text.substring(braceStart, braceEnd + 1);
        try {
            JsonNode node = JsonUtils.getMapper().readTree(json);
            JsonNode name = node.get("name");
            if (name != null && name.isTextual()) {
                String trimmed = name.asText().trim();
                return trimmed.isEmpty() ? null : trimmed;
            }
        } catch (Exception e) {
            log.debug("Failed to parse Haiku session-name response: {}", e.getMessage());
        }
        return null;
    }
}
