package com.claudecode.services.compact;

import com.claudecode.api.ApiException;
import com.claudecode.core.engine.MessageCompactor;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.TokenEstimator;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.claudecode.core.serialization.JsonUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-level assembly tests for the reactive auto-compact path.
 */
class ReactiveAutoCompactTest {

    private static final String MEDIA_API_ERROR =
        "API request failed: {\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
            + "\"message\":\"image exceeds 5 MB maximum\"}}";
    private static final String RELEASED_IMAGE_PLACEHOLDER =
        "[Image removed: the API could not process this image "
            + "(400 {\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
            + "\"message\":\"image exceeds 5 MB maximum). "
            + "The file may be unsupported or corrupt; do not retry reading it. "
            + "If you need to inspect it, use a shell command instead.]";

    @Test
    void autoCompactRetriesMediaSizeFailureWithReleased197StrippedSecondAttempt() {
        List<List<Message>> summarized = new ArrayList<>();
        CompactSummarizer summarizer = new CompactSummarizer() {
            @Override
            public String summarize(List<Message> messages, String compactPrompt) {
                return summarizeWithUsage(messages, compactPrompt).text();
            }

            @Override
            public SummaryResult summarizeWithUsage(List<Message> messages, String compactPrompt) {
                summarized.add(List.copyOf(messages));
                if (summarized.size() == 1) {
                    throw new ApiException(MEDIA_API_ERROR, 400);
                }
                return new SummaryResult("media retry summary", Usage.EMPTY);
            }
        };
        CompactService service = new CompactService(TokenEstimator.getInstance(), summarizer, true);

        MessageCompactor.CompactionResult result = service.compactConversation(
            reactiveMessagesWithOldImage(), true, null, "claude-sonnet-4-6");

        assertEquals("media retry summary", result.rawSummary());
        assertEquals(2, summarized.size());
        assertTrue(hasImage(summarized.getFirst()),
            "released 2.1.197 sends the original media on the first reactive attempt");
        assertTrue(hasText(summarized.get(1), RELEASED_IMAGE_PLACEHOLDER),
            "the retry must use 2.1.197's sanitized API-error diagnostic placeholder");
    }

    @Test
    void autoCompactFallsBackToGenericImageMarkerAfterReleasedRetryAlsoFails() {
        List<List<Message>> summarized = new ArrayList<>();
        CompactSummarizer summarizer = new CompactSummarizer() {
            @Override
            public String summarize(List<Message> messages, String compactPrompt) {
                return summarizeWithUsage(messages, compactPrompt).text();
            }

            @Override
            public SummaryResult summarizeWithUsage(List<Message> messages, String compactPrompt) {
                summarized.add(List.copyOf(messages));
                if (summarized.size() <= 2) {
                    throw new ApiException(MEDIA_API_ERROR, 400);
                }
                return new SummaryResult("generic media retry summary", Usage.EMPTY);
            }
        };
        CompactService service = new CompactService(TokenEstimator.getInstance(), summarizer, true);

        MessageCompactor.CompactionResult result = service.compactConversation(
            reactiveMessagesWithOldImage(), true, null, "claude-sonnet-4-6");

        assertEquals("generic media retry summary", result.rawSummary());
        assertEquals(3, summarized.size());
        assertTrue(hasImage(summarized.getFirst()));
        assertTrue(hasText(summarized.get(1), RELEASED_IMAGE_PLACEHOLDER));
        assertTrue(hasText(summarized.get(2), "[image]"),
            "the compact-level fallback restarts from the original split with generic markers");
    }

    @Test
    void autoCompactReportsReleased197MediaUnstrippableCodeAfterStrippedRetryFails() {
        List<List<Message>> summarized = new ArrayList<>();
        CompactSummarizer summarizer = new CompactSummarizer() {
            @Override
            public String summarize(List<Message> messages, String compactPrompt) {
                summarized.add(List.copyOf(messages));
                throw new ApiException(MEDIA_API_ERROR, 400);
            }
        };
        CompactService service = new CompactService(TokenEstimator.getInstance(), summarizer, true);

        CompactException failure = assertThrows(CompactException.class,
            () -> service.compactConversation(
                reactiveMessagesWithOldImage(), true, null, "claude-sonnet-4-6"));

        assertEquals("media_unstrippable", failure.getMessage());
        assertEquals(3, summarized.size());
        assertTrue(hasImage(summarized.getFirst()));
        assertTrue(hasText(summarized.get(1), RELEASED_IMAGE_PLACEHOLDER));
        assertTrue(hasText(summarized.get(2), "[image]"));
    }

    @Test
    void autoCompactFallbackOnlyStripsTheLatestUserCarrierWithBase64Media() {
        List<List<Message>> summarized = new ArrayList<>();
        CompactSummarizer summarizer = new CompactSummarizer() {
            @Override
            public String summarize(List<Message> messages, String compactPrompt) {
                return summarizeWithUsage(messages, compactPrompt).text();
            }

            @Override
            public SummaryResult summarizeWithUsage(List<Message> messages, String compactPrompt) {
                summarized.add(List.copyOf(messages));
                if (summarized.size() == 1) throw new ApiException(MEDIA_API_ERROR, 400);
                return new SummaryResult("latest carrier summary", Usage.EMPTY);
            }
        };
        CompactService service = new CompactService(TokenEstimator.getInstance(), summarizer, true);

        service.compactConversation(
            reactiveMessagesWithTwoOldImages(), true, null, "claude-sonnet-4-6");

        assertEquals(2, summarized.size());
        assertTrue(userHasImage(summarized.get(1), "u0"),
            "2.1.197 fallback leaves older carriers untouched");
        assertTrue(userHasText(summarized.get(1), "u1", RELEASED_IMAGE_PLACEHOLDER),
            "2.1.197 fallback replaces media only in the latest matching user carrier");
    }

    @Test
    void autoCompactReportsReleased197EmptySummaryDetail() {
        CompactSummarizer summarizer = new CompactSummarizer() {
            @Override
            public String summarize(List<Message> messages, String compactPrompt) {
                return null;
            }
        };
        CompactService service = new CompactService(TokenEstimator.getInstance(), summarizer, true);

        CompactException failure = assertThrows(CompactException.class,
            () -> service.compactConversation(
                reactiveMessages(), true, null, "claude-sonnet-4-6"));

        assertEquals("summarization produced empty response", failure.getMessage());
    }

    @Test
    void autoCompactKeepsPreservingGroupsPastTraditionalThreeRetryLimit() {
        int[] attempts = {0};
        CompactSummarizer summarizer = new CompactSummarizer() {
            @Override
            public String summarize(List<Message> messages, String compactPrompt) {
                attempts[0]++;
                return attempts[0] <= 4
                    ? CompactService.PROMPT_TOO_LONG_MARKER + ": deterministic"
                    : "summary after four reactive retries";
            }
        };
        CompactService service = new CompactService(TokenEstimator.getInstance(), summarizer, true);
        List<Message> messages = new ArrayList<>();
        messages.add(user("u0", "start"));
        for (int index = 0; index < 6; index++) {
            messages.add(assistant("a" + index, "msg-" + index,
                "answer " + index, Usage.EMPTY));
            messages.add(user("u" + (index + 1), "turn " + (index + 1)));
        }

        MessageCompactor.CompactionResult result = service.compactConversation(
            messages, true, null, "claude-sonnet-4-6");

        assertEquals("summary after four reactive retries", result.rawSummary());
        assertEquals(5, attempts[0],
            "released reactive compact is bounded by API-round groups, not the traditional 3-retry cap");
    }

    @Test
    void promptTooLongThenNoSmallerCompletedPrefixReportsExhausted() {
        int[] attempts = {0};
        CompactSummarizer summarizer = new CompactSummarizer() {
            @Override
            public String summarize(List<Message> messages, String compactPrompt) {
                attempts[0]++;
                return CompactService.PROMPT_TOO_LONG_MARKER + ": deterministic";
            }
        };
        CompactService service = new CompactService(TokenEstimator.getInstance(), summarizer, true);

        CompactException failure = assertThrows(CompactException.class,
            () -> service.compactConversation(
                reactiveMessages(), true, null, "claude-sonnet-4-6"));

        assertEquals(1, attempts[0]);
        assertEquals("Reactive compact exhausted all API-round groups", failure.getMessage(),
            "after a PTL response, running out of completed prefixes is exhausted, not too_few_groups");
    }

    @Test
    void autoCompactSummarizesOlderGroupsAndPreservesTheNewestApiRound() {
        List<List<Message>> summarized = new ArrayList<>();
        CompactSummarizer summarizer = new CompactSummarizer() {
            @Override
            public String summarize(List<Message> messages, String compactPrompt) {
                summarized.add(List.copyOf(messages));
                return "reactive summary";
            }

            @Override
            public SummaryResult summarizeWithUsage(List<Message> messages, String compactPrompt) {
                summarized.add(List.copyOf(messages));
                return new SummaryResult("reactive summary", new Usage(500, 20, 0, 0));
            }
        };
        CompactService service = new CompactService(TokenEstimator.getInstance(), summarizer, true);
        service.setAgentListingSupplier(() -> """
            Available agent types for the Agent tool:
            - Explore: Read-only search. (Tools: Read)

            When you launch multiple agents for independent work, send them in a single message \
            with multiple tool uses so they run concurrently.""");

        UserMessage firstUser = user("u1", "first");
        AssistantMessage firstAssistant = assistant("a1", "msg-1", "first answer", Usage.EMPTY);
        UserMessage secondUser = user("u2", "second");
        AssistantMessage latestAssistant = assistant(
            "a2", "msg-2", "latest answer", new Usage(12_000, 1, 0, 0));
        UserMessage currentUser = user("u3", "next");

        MessageCompactor.CompactionResult result = service.compactConversation(
            List.of(firstUser, firstAssistant, secondUser, latestAssistant, currentUser),
            true,
            null,
            "claude-sonnet-4-6");

        assertEquals(List.of(firstUser, firstAssistant, secondUser), summarized.getFirst(),
            "2.1.197 reactive compact summarizes every group except the newest API round");
        assertEquals(2, result.messagesToKeep().size());
        assertEquals("a2", result.messagesToKeep().getFirst().uuid());
        assertEquals("u3", result.messagesToKeep().get(1).uuid());

        AssistantMessage preservedAssistant = (AssistantMessage) result.messagesToKeep().getFirst();
        assertEquals(Usage.EMPTY, preservedAssistant.message().usage(),
            "preserved assistant usage is zeroed so the compacted context does not immediately retrigger");

        assertEquals(12_002, result.preCompactTokenCount(),
            "preTokens uses latest API usage plus the current user message estimate");
        assertNotNull(result.boundaryMarker().compactMetadata());
        var metadata = result.boundaryMarker().compactMetadata();
        assertEquals("auto", metadata.trigger());
        assertEquals(12_002L, metadata.preTokens());
        List<Message> postCompactMessages = new ArrayList<>(result.summaryMessages());
        postCompactMessages.addAll(result.messagesToKeep());
        postCompactMessages.addAll(result.attachments());
        assertEquals(1, result.attachments().size(),
            "the post-compact estimate must include the rendered agent-listing attachment");
        long expectedPostTokens = TokenEstimator.getInstance()
            .estimateTokenCount(postCompactMessages);
        assertEquals(expectedPostTokens, metadata.postTokens(),
            "postTokens estimates the actual post-compact context, not the summary-call usage");
        assertNotEquals(520L, metadata.postTokens(),
            "the summarizer's input/output usage is not the post-compact context size");
        assertEquals(12_002L - expectedPostTokens, metadata.cumulativeDroppedTokens());
        assertNotNull(metadata.durationMs());
        assertEquals("a2", metadata.preservedSegment().headUuid());
        assertEquals("u3", metadata.preservedSegment().tailUuid());
        assertEquals(result.summaryMessages().getLast().uuid(),
            metadata.preservedSegment().anchorUuid());
        assertEquals(result.summaryMessages().getLast().uuid(), metadata.preservedMessages().anchorUuid());
        assertEquals(List.of("a2", "u3"), metadata.preservedMessages().uuids());
        assertEquals(List.of("a2", "u3"), metadata.preservedMessages().allUuids());
    }

    private static UserMessage user(String uuid, String text) {
        return new UserMessage(uuid, MessageContent.ofText(text));
    }

    private static List<Message> reactiveMessages() {
        return List.of(
            user("u1", "first"),
            assistant("a1", "msg-1", "first answer", Usage.EMPTY),
            user("u2", "second"),
            assistant("a2", "msg-2", "latest answer", new Usage(12_000, 1, 0, 0)),
            user("u3", "next"));
    }

    private static List<Message> reactiveMessagesWithOldImage() {
        ObjectNode source = JsonUtils.getMapper().createObjectNode();
        source.put("type", "base64");
        source.put("media_type", "image/png");
        source.put("data", "iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB");
        return List.of(
            new UserMessage("u1", MessageContent.ofBlocks(List.of(
                new TextBlock("first"), new ImageBlock(source)))),
            assistant("a1", "msg-1", "first answer", Usage.EMPTY),
            user("u2", "second"),
            assistant("a2", "msg-2", "latest answer", new Usage(12_000, 1, 0, 0)),
            user("u3", "next"));
    }

    private static List<Message> reactiveMessagesWithTwoOldImages() {
        ObjectNode source = JsonUtils.getMapper().createObjectNode();
        source.put("type", "base64");
        source.put("media_type", "image/png");
        source.put("data", "iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB");
        return List.of(
            new UserMessage("u0", MessageContent.ofBlocks(List.of(
                new TextBlock("oldest"), new ImageBlock(source.deepCopy())))),
            assistant("a0", "msg-0", "oldest answer", Usage.EMPTY),
            new UserMessage("u1", MessageContent.ofBlocks(List.of(
                new TextBlock("newer"), new ImageBlock(source.deepCopy())))),
            assistant("a1", "msg-1", "newer answer", Usage.EMPTY),
            user("u2", "second"),
            assistant("a2", "msg-2", "latest answer", new Usage(12_000, 1, 0, 0)),
            user("u3", "next"));
    }

    private static boolean hasImage(List<Message> messages) {
        return messages.stream()
            .filter(UserMessage.class::isInstance)
            .map(UserMessage.class::cast)
            .map(UserMessage::message)
            .filter(content -> content != null && content.blocks() != null)
            .flatMap(content -> content.blocks().stream())
            .anyMatch(ImageBlock.class::isInstance);
    }

    private static boolean hasText(List<Message> messages, String expected) {
        return messages.stream()
            .filter(UserMessage.class::isInstance)
            .map(UserMessage.class::cast)
            .map(UserMessage::message)
            .filter(content -> content != null && content.blocks() != null)
            .flatMap(content -> content.blocks().stream())
            .filter(TextBlock.class::isInstance)
            .map(TextBlock.class::cast)
            .map(TextBlock::text)
            .anyMatch(expected::equals);
    }

    private static boolean userHasImage(List<Message> messages, String uuid) {
        return messages.stream()
            .filter(UserMessage.class::isInstance)
            .map(UserMessage.class::cast)
            .filter(message -> uuid.equals(message.uuid()))
            .map(UserMessage::message)
            .filter(content -> content != null && content.blocks() != null)
            .flatMap(content -> content.blocks().stream())
            .anyMatch(ImageBlock.class::isInstance);
    }

    private static boolean userHasText(List<Message> messages, String uuid, String expected) {
        return messages.stream()
            .filter(UserMessage.class::isInstance)
            .map(UserMessage.class::cast)
            .filter(message -> uuid.equals(message.uuid()))
            .map(UserMessage::message)
            .filter(content -> content != null && content.blocks() != null)
            .flatMap(content -> content.blocks().stream())
            .filter(TextBlock.class::isInstance)
            .map(TextBlock.class::cast)
            .map(TextBlock::text)
            .anyMatch(expected::equals);
    }

    private static AssistantMessage assistant(String uuid, String apiId, String text, Usage usage) {
        return new AssistantMessage(uuid,
            AssistantContent.of(apiId, List.of(new TextBlock(text)), usage));
    }
}
