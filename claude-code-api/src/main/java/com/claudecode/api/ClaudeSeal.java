package com.claudecode.api;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.RedactedThinkingBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Carries Anthropic signed {@code thinking} and {@code redacted_thinking} blocks
 * through OpenAI Responses {@code encrypted_content} envelopes without data loss,
 * while stripping them before sending to native OpenAI Responses upstreams.
 *
 * <p>Replicated from {@code yetone/magpie} ({@code internal/claudeseal/claudeseal.go}).
 */
@Explanation("Cross-protocol Anthropic reasoning state preservation in Responses encrypted_content")
final class ClaudeSeal {

    static final String MARK = "magpie-claude1:";

    private ClaudeSeal() {}

    record OpenedBlock(JsonNode block, boolean ours) {}

    static String seal(byte[] blockJson) {
        if (blockJson == null || blockJson.length == 0) return "";
        return MARK + Base64.getEncoder().encodeToString(blockJson);
    }

    static String seal(JsonNode block) {
        if (block == null || block.isNull()) return "";
        try {
            return seal(JsonUtils.getMapper().writeValueAsBytes(block));
        } catch (Exception _) {
            return "";
        }
    }

    static String sealThinking(String thinking, String signature) {
        ObjectNode node = JsonUtils.getMapper().createObjectNode();
        node.put("type", "thinking");
        node.put("thinking", thinking == null ? "" : thinking);
        if (StringUtils.isNotEmpty(signature)) {
            node.put("signature", signature);
        }
        return seal(node);
    }

    static String sealRedacted(String data) {
        ObjectNode node = JsonUtils.getMapper().createObjectNode();
        node.put("type", "redacted_thinking");
        node.put("data", data == null ? "" : data);
        return seal(node);
    }

    static boolean isSealed(String encryptedContent) {
        return Strings.CS.startsWith(encryptedContent, MARK);
    }

    static OpenedBlock open(String encryptedContent) {
        if (!Strings.CS.startsWith(encryptedContent, MARK)) {
            return new OpenedBlock(null, false);
        }
        String b64 = encryptedContent.substring(MARK.length());
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException _) {
            return new OpenedBlock(null, true);
        }
        try {
            JsonNode parsed = JsonUtils.getMapper().readTree(new String(decoded, StandardCharsets.UTF_8));
            if (parsed == null || !parsed.isObject()) {
                return new OpenedBlock(null, true);
            }
            return new OpenedBlock(parsed, true);
        } catch (Exception _) {
            return new OpenedBlock(null, true);
        }
    }

    static ContentBlock openToContentBlock(String encryptedContent) {
        OpenedBlock opened = open(encryptedContent);
        if (!opened.ours() || opened.block() == null) return null;
        JsonNode block = opened.block();
        String type = block.path("type").asText("");
        if (Strings.CS.equals("redacted_thinking", type)) {
            return new RedactedThinkingBlock(block.path("data").asText(""));
        }
        if (Strings.CS.equals("thinking", type)) {
            String text = block.path("thinking").asText("");
            String sig = block.path("signature").isTextual() ? block.path("signature").asText() : null;
            return new ThinkingBlock(text, sig);
        }
        return null;
    }
}
