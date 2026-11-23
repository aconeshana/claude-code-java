package com.claudecode.api;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Encodes and decodes Google Gemini {@code thoughtSignature} tokens inside tool call IDs
 * ({@code <id>__ts<len>__<base64url>}) and OpenAI-compatible {@code extra_content.google.thought_signature}
 * envelopes, with length-checked corruption protection when clients truncate IDs.
 *
 * <p>Replicated from {@code yetone/magpie} ({@code internal/gateway/gemini_signature.go}).
 */
@Explanation("Gemini thoughtSignature round-trip encoding in tool IDs and extra_content")
final class GeminiThoughtSignature {

    static final String SKIP_SIGNATURE = "skip_thought_signature_validator";
    private static final String SIG_MARK = "__ts";
    private static final String SIG_RAW = "r";
    private static final String SIG_END = "__";

    private GeminiThoughtSignature() {}

    record UnsignedToolId(String id, String signature) {}

    static String signToolId(String id, String signature) {
        if (StringUtils.isEmpty(signature)) {
            return id;
        }
        String baseId = StringUtils.isEmpty(id) ? "call_" + UUID.randomUUID().toString().substring(0, 8) : id;
        String kind = SIG_RAW;
        String encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(signature.getBytes(StandardCharsets.UTF_8));
        try {
            byte[] decoded = Base64.getDecoder().decode(signature);
            if (Strings.CS.equals(Base64.getEncoder().encodeToString(decoded), signature)) {
                kind = "";
                encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(decoded);
            }
        } catch (IllegalArgumentException _) {
            // Non-canonical base64 signature stays in raw UTF-8 mode (__tsr<len>__).
        }
        return baseId + SIG_MARK + kind + encoded.length() + SIG_END + encoded;
    }

    static UnsignedToolId unsignToolId(String id) {
        if (StringUtils.isEmpty(id)) {
            return new UnsignedToolId(id == null ? "" : id, "");
        }
        int at = 1;
        while (at < id.length()) {
            int rel = id.substring(at).indexOf(SIG_MARK);
            if (rel < 0) break;
            int idx = at + rel;
            at = idx + 1;
            String rest = id.substring(idx + SIG_MARK.length());
            boolean raw = Strings.CS.startsWith(rest, SIG_RAW);
            if (raw) {
                rest = rest.substring(SIG_RAW.length());
            }
            int digits = 0;
            while (digits < rest.length() && rest.charAt(digits) >= '0' && rest.charAt(digits) <= '9') {
                digits++;
            }
            String afterDigits = rest.substring(digits);
            if (!Strings.CS.startsWith(afterDigits, SIG_END)) {
                continue;
            }
            String enc = afterDigits.substring(SIG_END.length());
            if (digits > 0 && !Strings.CS.equals(rest.substring(0, digits), Integer.toString(enc.length()))) {
                // Client truncated the ID; a partial signature is rejected by Gemini with 400.
                return new UnsignedToolId(id, "");
            }
            byte[] decoded;
            try {
                decoded = Base64.getUrlDecoder().decode(enc);
            } catch (IllegalArgumentException _) {
                return new UnsignedToolId(id, "");
            }
            if (decoded.length == 0) {
                return new UnsignedToolId(id, "");
            }
            String ownId = id.substring(0, idx);
            String sig = raw
                ? new String(decoded, StandardCharsets.UTF_8)
                : Base64.getEncoder().encodeToString(decoded);
            return new UnsignedToolId(ownId, sig);
        }
        return new UnsignedToolId(id, "");
    }

    static String extractExtraSignature(JsonNode extraContent) {
        if (extraContent == null || extraContent.isNull()) return "";
        JsonNode sig = extraContent.path("google").path("thought_signature");
        if (sig.isTextual() && StringUtils.isNotEmpty(sig.asText())) {
            return sig.asText();
        }
        JsonNode camelSig = extraContent.path("google").path("thoughtSignature");
        return camelSig.isTextual() ? camelSig.asText("") : "";
    }

    static ObjectNode buildExtraSignature(String signature) {
        ObjectNode extra = JsonUtils.getMapper().createObjectNode();
        extra.putObject("google").put("thought_signature", signature);
        return extra;
    }

    static boolean isGeminiNamedModel(String model) {
        if (StringUtils.isBlank(model)) return false;
        int slash = model.lastIndexOf('/');
        String leaf = slash >= 0 ? model.substring(slash + 1) : model;
        return Strings.CI.startsWith(leaf, "gemini");
    }

    static boolean isGeminiCompatEndpoint(String baseUrl, String model) {
        if (isGeminiNamedModel(model)) return true;
        if (StringUtils.isBlank(baseUrl)) return false;
        String lower = baseUrl.toLowerCase(Locale.ROOT);
        return Strings.CS.contains(lower, "generativelanguage.googleapis.com")
            || Strings.CS.contains(lower, "aiplatform.googleapis.com");
    }
}
