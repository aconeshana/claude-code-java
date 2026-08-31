package com.claudecode.core.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Tests for {@link TokenEstimator}.
 */
class TokenEstimatorTest {

    private final TokenEstimator estimator = TokenEstimator.getInstance();
    private final ObjectMapper mapper = new ObjectMapper();

    // --- Character-based estimation ---

    @Test
    void estimateTokenCountForTextMessages() {
        // 400 chars → round(400/4) = 100 tokens.
        String text = "a".repeat(400);
        UserMessage msg = new UserMessage("u1", MessageContent.ofText(text));

        long tokens = estimator.estimateTokenCount(List.of(msg));

        assertEquals(100, tokens);
    }

    @Test
    void estimateTokenCountForEmptyList() {
        assertEquals(0, estimator.estimateTokenCount(List.of()));
    }

    // --- String overload (doctor diagnostics) ---

    @Test
    void estimateTokenCountForString() {
        assertEquals(100, estimator.estimateTokenCount("a".repeat(400)));
    }

    @Test
    void estimateTokenCountForNullOrEmptyString() {
        assertEquals(0, estimator.estimateTokenCount((String) null));
        assertEquals(0, estimator.estimateTokenCount(""));
    }

    @Test
    void estimateTokenCountForAssistantMessage() {
        // 800 chars in one text block → round(800/4) = 200.
        TextBlock tb = new TextBlock("b".repeat(800));
        AssistantMessage msg = new AssistantMessage("a1", AssistantContent.of(List.of(tb)));

        long tokens = estimator.estimateTokenCount(List.of(msg));

        assertEquals(200, tokens);
    }

    @Test
    void estimateTokenCountForToolUseBlock() {
        ObjectNode input = mapper.createObjectNode();
        input.put("path", "/tmp/test.txt");
        ToolUseBlock tu = new ToolUseBlock("tu-1", "Read", input);
        AssistantMessage msg = new AssistantMessage("a1", AssistantContent.of(List.of(tu)));

        long tokens = estimator.estimateTokenCount(List.of(msg));

        // name "Read" (4 chars) + input JSON string length
        assertTrue(tokens > 0);
    }

    @Test
    void estimateTokenCountForToolResultBlock() {
        TextBlock inner = new TextBlock("c".repeat(1200));
        ToolResultBlock tr = new ToolResultBlock("tu-1", List.of(inner), false);
        UserMessage msg = new UserMessage("u1", MessageContent.ofBlocks(List.of(tr)),
                false, false, null, MessageOrigin.TOOL_RESULT, null, Instant.now(), null, null);

        long tokens = estimator.estimateTokenCount(List.of(msg));

        assertEquals(300, tokens);
    }

    @Test
    void estimateTokenCountMultipleMessages() {

        UserMessage user = new UserMessage("u1", MessageContent.ofText("a".repeat(400)));
        AssistantMessage asst = new AssistantMessage("a1",
                AssistantContent.of(List.of(new TextBlock("b".repeat(400)))));

        long tokens = estimator.estimateTokenCount(List.of(user, asst));

        assertEquals(200, tokens);
    }

    @Test
    void roughEstimateRoundsEachMessageAndNormalizesAttachments() {
        UserMessage tinyUser = new UserMessage("u1", MessageContent.ofText("ab"));
        AssistantMessage tinyAssistant = new AssistantMessage("a1",
            AssistantContent.of(List.of(new TextBlock("cd"))));
        AgentListingDeltaAttachment listing = new AgentListingDeltaAttachment(
            List.of("Explore"),
            List.of("- Explore: Read-only search. (Tools: Read)"),
            List.of(), true, true);
        AttachmentMessage attachment = new AttachmentMessage("att-1", listing);

        long attachmentTokens = AttachmentRenderer.render(listing).stream()
            .mapToLong(message -> Math.round((double) message.message().text().length() / 4))
            .sum();

        assertEquals(1 + 1 + attachmentTokens,
            estimator.estimateTokenCount(List.of(tinyUser, tinyAssistant, attachment)));
    }

    @Test
    void postCompactEstimateUsesInvokedSkillContinuationRendering() {
        InvokedSkillsAttachment invoked = new InvokedSkillsAttachment(List.of(
            new InvokedSkillsAttachment.InvokedSkillEntry(
                "keybindings-help", "bundled:keybindings-help", "skill body")));
        AttachmentMessage attachment = new AttachmentMessage("att-skill", invoked);

        long normal = estimator.estimateTokenCount(List.of(attachment));
        long postCompact = estimator.estimatePostCompactTokenCount(List.of(attachment));

        assertTrue(postCompact > normal,
            "the released post-compact reminder has a longer non-reexecution preamble");
        assertEquals(Math.round((double) MessageConstants.wrapInSystemReminder(
                AttachmentRenderer.renderSystemContent(List.of(invoked))).length() / 4),
            postCompact);
    }

    @Test
    void contextCountUsesLatestApiUsageAndEstimatesOnlyFollowingMessages() {
        AssistantMessage measured = new AssistantMessage("a1",
            AssistantContent.of("msg-api-1", List.of(new TextBlock("OK")),
                new Usage(12_000, 1, 0, 0)));
        UserMessage following = new UserMessage("u2",
            MessageContent.ofText("x".repeat(400)));

        long tokens = estimator.tokenCountWithEstimation(
            List.of(measured, following), 4);

        assertEquals(12_101, tokens,
            "2.1.197 uses exact input/cache/output usage plus an unpadded estimate of later messages");
    }

    @Test
    void providerNormalizedDisjointBucketsProduceTheSameContextTotal() {
        AssistantMessage measured = new AssistantMessage("a1",
            AssistantContent.of("resp-gpt", List.of(new TextBlock("OK")),
                new Usage(8_000, 800, 0, 12_000)));

        assertEquals(20_800, estimator.tokenCountWithEstimation(
            List.of(measured), "gpt-5.6-sol", 4));
        assertEquals(20_800, estimator.tokenCountWithEstimation(
            List.of(measured), "claude-sonnet-4-6", 4));
    }

    @Test
    void gptContextPrefersProviderReportedTotalTokens() {
        Usage usage = new Usage(20_000, 800, 0, 12_000, 20_900L);

        assertEquals(20_900, TokenEstimator.contextTokens(usage, "gpt-5.6-sol"));
        assertEquals(32_800, TokenEstimator.contextTokens(usage, "claude-sonnet-4-6"),
            "the OpenAI-only total snapshot must not change Anthropic accounting");
    }

    @Test
    void modelSwitchKeepsUsageSemanticsOfTheResponseThatReportedIt() {
        AssistantMessage measuredByGpt = new AssistantMessage("a1",
            AssistantContent.apiResponse(
                "resp-gpt", List.of(new TextBlock("OK")),
                new Usage(20_000, 800, 0, 12_000, 20_900L),
                "gpt-5.6-sol", "end_turn", null));

        assertEquals(20_900, estimator.tokenCountWithEstimation(
            List.of(measuredByGpt), "anthropic.claude-sonnet-5", 3),
            "switching the target model must not reinterpret OpenAI cached tokens as additive");
    }

    @Test
    void apiErrorDoesNotReplaceTheLastRealUsageAnchor() {
        AssistantMessage measuredByGpt = new AssistantMessage("a1",
            AssistantContent.apiResponse(
                "resp-gpt", List.of(new TextBlock("OK")),
                new Usage(20_000, 800, 0, 12_000, 20_900L),
                "gpt-5.6-sol", "end_turn", null));
        AssistantMessage localError = MessageFactory.createAssistantAPIErrorMessage(
            "Context limit reached · /compact or /clear to continue");

        long expected = 20_900 + Math.round(
            (double) "Context limit reached · /compact or /clear to continue".length() / 3);
        assertEquals(expected, estimator.tokenCountWithEstimation(
            List.of(measuredByGpt, localError), "anthropic.claude-sonnet-5", 3),
            "a synthetic zero-usage error is estimated as local tail, never used as an API anchor");
    }

    @Test
    void customGptModelUsesCodexResponseItemJsonForUnmeasuredTail() {
        AssistantMessage measured = new AssistantMessage("a1",
            AssistantContent.of("resp-gpt", List.of(new TextBlock("OK")),
                new Usage(100, 0, 0, 0)));
        UserMessage chineseTail = new UserMessage("u2", MessageContent.ofText("你好世界"));

        assertEquals(122, estimator.tokenCountWithEstimation(
            List.of(measured, chineseTail), "my-gpt-5.6-sol-proxy", 3),
            "Codex estimates the complete Responses message item, including its JSON wrapper");
        assertEquals(101, estimator.tokenCountWithEstimation(
            List.of(measured, chineseTail), "claude-sonnet-4-6", 4),
            "the released Claude estimator keeps JavaScript string-length / 4 rounding");
    }

    @Test
    void gptTailIncludesFunctionOutputResponseItemWrapper() {
        AssistantMessage measured = new AssistantMessage("a1",
            AssistantContent.of("resp-gpt", List.of(new TextBlock("OK")),
                new Usage(100, 0, 0, 0)));
        UserMessage result = new UserMessage("u2", MessageContent.ofBlocks(List.of(
            new ToolResultBlock("call_1", List.of(new TextBlock("sunny")), false))));

        assertEquals(117, estimator.tokenCountWithEstimation(
            List.of(measured, result), "gpt-5.6-sol", 4),
            "the function_call_output item is 67 UTF-8 bytes, rounded up to 17 tokens");
    }

    @Test
    void gptTailSkipsSiblingAssistantItemsAlreadyCoveredByUsage() {
        Usage usage = new Usage(100, 0, 0, 0);
        AssistantMessage firstSplit = new AssistantMessage("a1", AssistantContent.of(
            "resp-gpt", List.of(new ToolUseBlock("call_1", "lookup", mapper.createObjectNode())), usage));
        UserMessage firstResult = new UserMessage("u1", MessageContent.ofBlocks(List.of(
            new ToolResultBlock("call_1", List.of(new TextBlock("sunny")), false))));
        AssistantMessage secondSplit = new AssistantMessage("a2", AssistantContent.of(
            "resp-gpt", List.of(new ToolUseBlock("call_2", "lookup", mapper.createObjectNode())), usage));
        UserMessage secondResult = new UserMessage("u2", MessageContent.ofBlocks(List.of(
            new ToolResultBlock("call_2", List.of(new TextBlock("rainy")), false))));

        assertEquals(134, estimator.tokenCountWithEstimation(
            List.of(firstSplit, firstResult, secondSplit, secondResult), "gpt-5.6-sol", 4),
            "both local tool outputs count, but the split model response is already in total_tokens");
    }

    @Test
    void recognizesBuiltInAndCustomGptModelNames() {
        assertTrue(TokenEstimator.isGptModel("gpt-5.6"));
        assertTrue(TokenEstimator.isGptModel("openai/GPT_5_6_SOL"));
        assertTrue(TokenEstimator.isGptModel("company-gpt-proxy"));
        assertFalse(TokenEstimator.isGptModel("claude-sonnet-4-6"));
    }

    @Test
    void contextCountAnchorsAtFirstSplitAssistantWithTheSameApiMessageId() {
        Usage usage = new Usage(1_000, 0, 0, 0);
        AssistantMessage firstSplit = new AssistantMessage("a1",
            AssistantContent.of("msg-shared", List.of(), usage));
        UserMessage firstToolResult = new UserMessage("u1",
            MessageContent.ofText("a".repeat(400)));
        AssistantMessage secondSplit = new AssistantMessage("a2",
            AssistantContent.of("msg-shared", List.of(), usage));
        UserMessage secondToolResult = new UserMessage("u2",
            MessageContent.ofText("b".repeat(400)));

        long tokens = estimator.tokenCountWithEstimation(
            List.of(firstSplit, firstToolResult, secondSplit, secondToolResult), 4);

        assertEquals(1_200, tokens,
            "interleaved tool results after every split assistant record must be counted");
    }

    // --- Exact count from Usage ---

    @Test
    void getExactTokenCountFromUsage() {
        Usage usage = new Usage(1500, 300, 0, 0);
        assertEquals(1500, estimator.getExactTokenCount(usage));
    }

    @Test
    void getExactTokenCountFromNullUsage() {
        assertEquals(0, estimator.getExactTokenCount(null));
    }

    @Test
    void getExactTokenCountFromEmptyUsage() {
        assertEquals(0, estimator.getExactTokenCount(Usage.EMPTY));
    }

    // --- estimateMessageChars ---

    @Test
    void estimateMessageCharsForTextUser() {
        UserMessage msg = new UserMessage("u1", MessageContent.ofText("Hello world"));
        assertEquals(11, estimator.estimateMessageChars(msg));
    }

    @Test
    void estimateMessageCharsForAssistantWithThinking() {
        ThinkingBlock thinking = new ThinkingBlock("Let me think about this...");
        TextBlock text = new TextBlock("Here's my answer.");
        AssistantMessage msg = new AssistantMessage("a1",
                AssistantContent.of(List.of(thinking, text)));

        long chars = estimator.estimateMessageChars(msg);

        // "Let me think about this..." = 26 chars, "Here's my answer." = 17 chars
        assertEquals(26 + 17, chars);
    }

    @Test
    void estimateMessageCharsForNullContent() {
        AssistantMessage msg = new AssistantMessage("a1", null);
        assertEquals(0, estimator.estimateMessageChars(msg));
    }

    @Test
    void estimateMessageCharsIgnoresNonUserAssistant() {
        // SystemMessage and other types return 0
        SystemMessage sys = new SystemMessage("s1", "info", "info", "System message");
        assertEquals(0, estimator.estimateMessageChars(sys));
    }
}
