package com.claudecode.services.summary;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.api.LlmClient;
import com.claudecode.core.engine.ToolBatchSummarizer;
import com.claudecode.core.engine.ToolCallInfo;
import com.claudecode.services.model.SideQuery;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.core.serialization.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Generates a Haiku-produced, one-line summary of a just-completed tool batch for SDK/mobile-client
 * progress display.
 */
public class ToolUseSummaryGenerator implements ToolBatchSummarizer {

    private static final Logger log = LoggerFactory.getLogger(ToolUseSummaryGenerator.class);

    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private static final int MAX_FIELD_LENGTH = 300;
    private static final int MAX_ASSISTANT_TEXT_LENGTH = 200;

    private static final String SYSTEM_PROMPT = """
        Write a short summary label describing what these tool calls accomplished. It appears as a single-line row in a mobile app and truncates around 30 characters, so think git-commit-subject, not sentence.

        Keep the verb in past tense and the most distinctive noun. Drop articles, connectors, and long location context first.

        Examples:
        - Searched in auth/
        - Fixed NPE in UserService
        - Created signup endpoint
        - Read config.json
        - Ran failing tests""";

    private final SideQuery sideQuery;

    public ToolUseSummaryGenerator(LlmClient llmClient) {
        this.sideQuery = new SideQuery(llmClient);
    }

    @Override
    public CompletableFuture<String> summarizeAsync(List<ToolCallInfo> tools,
                                                      String lastAssistantText,
                                                      boolean isNonInteractiveSession) {
        if (tools == null || tools.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                String toolSummaries = tools.stream()
                    .map(t -> "Tool: " + t.name()
                        + "\nInput: " + truncateJson(t.input())
                        + "\nOutput: " + truncateJson(t.output()))
                    .reduce((a, b) -> a + "\n\n" + b)
                    .orElse("");

                String contextPrefix = (StringUtils.isNotBlank(lastAssistantText))
                    ? "User's intent (from assistant's last message): "
                        + FormatUtils.truncateNoEllipsis(lastAssistantText, MAX_ASSISTANT_TEXT_LENGTH) + "\n\n"
                    : "";

                String userPrompt = contextPrefix + "Tools completed:\n\n" + toolSummaries + "\n\nLabel:";
                String response = sideQuery.queryHaiku(SYSTEM_PROMPT, userPrompt);
                String summary = response != null ? response.trim() : "";
                return summary.isEmpty() ? null : summary;
            } catch (Exception e) {

                log.debug("Tool use summary generation failed: {}", e.getMessage());
                return null;
            }
        }, EXECUTOR);
    }


    private static String truncateJson(Object value) {
        try {
            String str = JsonUtils.toJson(value);
            if (str.length() <= MAX_FIELD_LENGTH) {
                return str;
            }
            return str.substring(0, MAX_FIELD_LENGTH - 3) + "...";
        } catch (Exception _) {
            return "[unable to serialize]";
        }
    }
}
