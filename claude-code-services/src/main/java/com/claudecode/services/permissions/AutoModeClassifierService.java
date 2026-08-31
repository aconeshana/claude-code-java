package com.claudecode.services.permissions;

import com.claudecode.api.CreateMessageRequest;
import com.claudecode.permissions.AutoModeClassifier;
import com.claudecode.services.model.SideQuery;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.Strings;


public final class AutoModeClassifierService implements AutoModeClassifier {

    static final String STAGE_ONE_SUFFIX =
        """

        Err on the side of blocking. Stage 1 does NOT apply user intent or ALLOW exceptions — \
        stage 2 will handle those. Judge the action by its full effect — what it runs, sends, \
        publishes, or enables — not its surface form. Block if ANY rule could apply. \
        <block> immediately.""";
    static final String STAGE_TWO_SUFFIX =
        """

        Review the classification process and follow it carefully, making sure you deny actions \
        that should be blocked. As a reminder, explicit (not suggestive or implicit) user \
        confirmation is required to override blocks. Use <thinking> before responding with \
        <block>. Think longer on ambiguous or borderline actions; keep reasoning brief for \
        clear-cut ones.""";
    static final String UNPARSEABLE_REASON =
        "Auto mode could not evaluate this action and is blocking it for safety — run with --debug for details";

    private static final String PROMPT_RESOURCE =
        "/prompts/auto-mode-classifier-system-2.1.197.txt";
    private static final int MAX_PARSE_ATTEMPTS = 5;
    private static final Pattern THINKING =
        Pattern.compile("<thinking>[\\s\\S]*?</thinking>|<thinking>[\\s\\S]*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BLOCK =
        Pattern.compile("<block>(yes|no)\\b(?:</block>)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern REASON =
        Pattern.compile("<reason>([\\s\\S]*?)</reason>");

    private final SideQuery sideQuery;
    private final Function<String, JsonNode> metadataFactory;
    private final String systemPrompt;

    public AutoModeClassifierService(
            SideQuery sideQuery, Function<String, JsonNode> metadataFactory) {
        this.sideQuery = Objects.requireNonNull(sideQuery, "sideQuery");
        this.metadataFactory = metadataFactory != null ? metadataFactory : _ -> null;
        this.systemPrompt = loadReleased197SystemPrompt();
    }

    @Override
    public Decision classify(Request request) {
        if (request == null || request.compactTranscriptBlocks().isEmpty()) {
            return Decision.allow("Tool declares no classifier-relevant input");
        }

        Boolean stageOne = null;
        try {
            for (int attempt = 0; attempt < MAX_PARSE_ATTEMPTS; attempt++) {
                String response = query(request, 64, STAGE_ONE_SUFFIX, List.of("</block>"));
                stageOne = parseBlock(response);
                if (stageOne != null) break;
            }
        } catch (RuntimeException _) {
            return Decision.unavailable("Classifier unavailable - blocking for safety");
        }
        if (Boolean.FALSE.equals(stageOne)) {
            return Decision.allow("Allowed by fast classifier");
        }

        try {
            for (int attempt = 0; attempt < MAX_PARSE_ATTEMPTS; attempt++) {
                String response = query(request, 8192, STAGE_TWO_SUFFIX, null);
                Boolean stageTwo = parseBlock(response);
                if (stageTwo == null) continue;
                String reason = parseReason(response);
                return stageTwo
                    ? Decision.block(reason != null ? reason : "No reason provided")
                    : Decision.allow(reason != null ? reason : "No reason provided");
            }
        } catch (RuntimeException _) {
            return stageOne == null
                ? Decision.unavailable("Classifier unavailable - blocking for safety")
                : Decision.block("Stage 2 classifier error - blocking based on stage 1 assessment");
        }
        return Decision.block(UNPARSEABLE_REASON);
    }

    private String query(Request request, int maxTokens, String suffix,
                         List<String> stopSequences) {
        return sideQuery.queryTextOrThrow(new SideQuery.Request()
            .model(request.model())
            .systemPrompt(systemPrompt)
            .systemPromptUncachedSuffix(sessionContext())
            .messages(List.of(new CreateMessageRequest.RequestMessage(
                "user", contentBlocks(request.compactTranscriptBlocks(), suffix))))
            .maxTokens(maxTokens)
            .temperature(1.0)
            .thinking(CreateMessageRequest.ThinkingConfig.disabled())
            .metadata(metadataFactory.apply(request.sessionId()))
            .stopSequences(stopSequences)
            .querySource("auto_mode"));
    }

    private static List<Map<String, Object>> contentBlocks(
            List<String> compactBlocks, String suffix) {
        List<Map<String, Object>> blocks = new ArrayList<>(compactBlocks.size() + 3);
        blocks.add(textBlock("<transcript>\n", false));
        for (int i = 0; i < compactBlocks.size(); i++) {
            blocks.add(textBlock(compactBlocks.get(i), i == compactBlocks.size() - 1));
        }
        blocks.add(textBlock("</transcript>\n", false));
        blocks.add(textBlock(suffix, false));
        return List.copyOf(blocks);
    }

    private static Map<String, Object> textBlock(String text, boolean cache) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        if (cache) block.put("cache_control", Map.of("type", "ephemeral"));
        return block;
    }

    private static Boolean parseBlock(String raw) {
        if (raw == null) return null;
        Matcher match = BLOCK.matcher(stripThinking(raw));
        if (!match.find()) return null;
        return Strings.CI.equals( "yes", match.group(1));
    }

    private static String parseReason(String raw) {
        if (raw == null) return null;
        Matcher match = REASON.matcher(stripThinking(raw));
        return match.find() ? match.group(1).trim() : null;
    }

    private static String stripThinking(String raw) {
        return THINKING.matcher(raw).replaceAll("");
    }

    private static String sessionContext() {
        String user = System.getProperty("user.name", "");
        return "\n\n## Session Context\n\n- **User identity**: `" + user
            + "`. The `$USER/...` pattern in the rules above resolves to `" + user
            + "/...`. Branches whose first path segment is a different person's name "
            + "(`<other-user>/...`) are NOT this user's personal branches.";
    }

    static String loadReleased197SystemPrompt() {
        try (InputStream stream = AutoModeClassifierService.class
                .getResourceAsStream(PROMPT_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing classifier prompt resource: " + PROMPT_RESOURCE);
            }
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return Strings.CS.endsWith( text, "\n") ? text.substring(0, text.length() - 1) : text;
        } catch (IOException error) {
            throw new IllegalStateException("Unable to load classifier prompt resource", error);
        }
    }
}
