package com.claudecode.api;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Caption reuse contract for the text-only-endpoint image fallback.
 *
 * <p>The router rewrites only the wire copy of the history, so an uncached router
 * re-describes every image on every turn — wasting one image round trip per image
 * per turn and, because captions are non-deterministic, invalidating the main
 * endpoint's prompt cache from the image's position onwards. These tests pin both
 * halves: exactly one request per distinct image, and byte-identical caption text
 * across requests.
 */
class ImageContentRouterCacheTest {

    private static final String IMAGE_MODEL = "vision-custom";

    @Test
    void sameImageIsCaptionedOnceAndReusedVerbatim() {
        CountingImageClient client = distinctCaptionsPerCall();
        ImageCaptionCache cache = new ImageCaptionCache();
        List<CreateMessageRequest.RequestMessage> messages =
            userMessage(base64Image("image/png", "aGk="), textBlock("what is this?"));

        List<CreateMessageRequest.RequestMessage> first = route(messages, client, cache);
        List<CreateMessageRequest.RequestMessage> second = route(messages, client, cache);

        assertEquals(1, client.calls(), "the second turn must not re-describe a known image");
        assertEquals(blockText(first, 0), blockText(second, 0),
            "caption text must be byte-identical across turns or the prompt cache breaks");
        assertTrue(Strings.CS.startsWith(blockText(first, 0),
            ImageContentRouter.CAPTIONED_IMAGE_PREFIX));
        assertTrue(Strings.CS.contains(blockText(second, 0), "caption-1"),
            "the remembered description is reused, not a fresh one");
        assertEquals("what is this?", blockText(first, 1), "sibling text blocks are untouched");
    }

    @Test
    void distinctImagesAreCaptionedOnceEachAndNumberedByRequestOrder() {
        CountingImageClient client = distinctCaptionsPerCall();
        ImageCaptionCache cache = new ImageCaptionCache();
        List<CreateMessageRequest.RequestMessage> messages = userMessage(
            base64Image("image/png", "b25l"), base64Image("image/png", "dHdv"));

        List<CreateMessageRequest.RequestMessage> first = route(messages, client, cache);
        List<CreateMessageRequest.RequestMessage> second = route(messages, client, cache);

        assertEquals(2, client.calls(), "one request per distinct image, none on the second turn");
        assertEquals(2, cache.size());
        assertEquals(ImageContentRouter.CAPTIONED_IMAGE_PREFIX + IMAGE_MODEL + "] [1 of 2]:\n"
            + "caption-1", blockText(first, 0));
        assertEquals(ImageContentRouter.CAPTIONED_IMAGE_PREFIX + IMAGE_MODEL + "] [2 of 2]:\n"
            + "caption-2", blockText(first, 1));
        assertEquals(blockText(first, 0), blockText(second, 0));
        assertEquals(blockText(first, 1), blockText(second, 1));
    }

    @Test
    void sequenceSuffixFollowsThisRequestNotTheRememberedOne() {
        // The suffix is per-request positional state, so it is regenerated on a
        // cache hit: the image cached as "[2 of 2]" must render "[1 of 2]" when it
        // leads the next request.
        CountingImageClient client = distinctCaptionsPerCall();
        ImageCaptionCache cache = new ImageCaptionCache();
        Object trailing = base64Image("image/png", "dHdv");

        route(userMessage(base64Image("image/png", "b25l"), trailing), client, cache);
        List<CreateMessageRequest.RequestMessage> reordered =
            route(userMessage(trailing, base64Image("image/png", "dGhyZWU=")), client, cache);

        assertEquals(3, client.calls(), "only the newly introduced image is described");
        assertEquals(ImageContentRouter.CAPTIONED_IMAGE_PREFIX + IMAGE_MODEL + "] [1 of 2]:\n"
            + "caption-2", blockText(reordered, 0));
        assertEquals(ImageContentRouter.CAPTIONED_IMAGE_PREFIX + IMAGE_MODEL + "] [2 of 2]:\n"
            + "caption-3", blockText(reordered, 1));
    }

    @Test
    void jsonNodeAndMapShapesOfTheSameImageShareOneCaption() {
        CountingImageClient client = distinctCaptionsPerCall();
        ImageCaptionCache cache = new ImageCaptionCache();
        JsonNode jsonBlocks = JsonUtils.parseTree(
            "[{\"type\":\"image\",\"source\":{\"type\":\"base64\","
                + "\"media_type\":\"image/png\",\"data\":\"aGk=\"}}]");

        List<CreateMessageRequest.RequestMessage> fromJson = route(
            List.of(new CreateMessageRequest.RequestMessage("user", jsonBlocks)), client, cache);
        List<CreateMessageRequest.RequestMessage> fromMap = route(
            userMessage(base64Image("image/png", "aGk=")), client, cache);

        assertEquals(1, client.calls(), "the key is the image content, not its Java shape");
        assertEquals(blockText(fromJson, 0), blockText(fromMap, 0));
    }

    @Test
    void switchingTheImageModelRedescribesTheImage() {
        CountingImageClient client = distinctCaptionsPerCall();
        ImageCaptionCache cache = new ImageCaptionCache();
        List<CreateMessageRequest.RequestMessage> messages =
            userMessage(base64Image("image/png", "aGk="));

        route(messages, client, cache, IMAGE_MODEL);
        route(messages, client, cache, "other-vision");

        assertEquals(2, client.calls(), "a different image model must describe the image itself");
        assertEquals(2, cache.size());
    }

    @Test
    void failedCaptionIsNotRememberedAndKeepsTheImageBlock() {
        AtomicInteger attempts = new AtomicInteger();
        CountingImageClient client = new CountingImageClient(request -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("image endpoint down");
            return ApiMessage.stub(request.model(), "recovered caption");
        });
        ImageCaptionCache cache = new ImageCaptionCache();
        Object imageBlock = base64Image("image/png", "aGk=");
        List<CreateMessageRequest.RequestMessage> messages = userMessage(imageBlock);

        List<CreateMessageRequest.RequestMessage> failed = route(messages, client, cache);
        assertSame(messages, failed, "a failed caption leaves the request untouched");
        assertSame(imageBlock, ((List<?>) failed.getFirst().content()).getFirst());
        assertEquals(0, cache.size(), "failures must never be remembered");

        List<CreateMessageRequest.RequestMessage> retried = route(messages, client, cache);
        assertEquals(2, client.calls(), "the next turn retries the failed image");
        assertEquals(ImageContentRouter.CAPTIONED_IMAGE_PREFIX + IMAGE_MODEL + "]:\n"
            + "recovered caption", blockText(retried, 0));
    }

    @Test
    void emptyCaptionIsNotRememberedAndKeepsTheImageBlock() {
        CountingImageClient client =
            new CountingImageClient(request -> ApiMessage.stub(request.model(), ""));
        ImageCaptionCache cache = new ImageCaptionCache();
        Object imageBlock = base64Image("image/png", "aGk=");
        List<CreateMessageRequest.RequestMessage> messages = userMessage(imageBlock);

        assertSame(messages, route(messages, client, cache));
        assertSame(messages, route(messages, client, cache));

        assertEquals(2, client.calls(), "an empty caption is a failure, so it is retried");
        assertEquals(0, cache.size());
    }

    private static List<CreateMessageRequest.RequestMessage> route(
            List<CreateMessageRequest.RequestMessage> messages,
            LlmClient client, ImageCaptionCache cache) {
        return route(messages, client, cache, IMAGE_MODEL);
    }

    private static List<CreateMessageRequest.RequestMessage> route(
            List<CreateMessageRequest.RequestMessage> messages,
            LlmClient client, ImageCaptionCache cache, String imageModel) {
        return ImageContentRouter.routeImages(messages, false,
            new ImageContentRouter.ImageEndpoint(client, true), imageModel, cache);
    }

    private static List<CreateMessageRequest.RequestMessage> userMessage(Object... blocks) {
        return List.of(new CreateMessageRequest.RequestMessage("user", List.of(blocks)));
    }

    private static Object base64Image(String mediaType, String data) {
        return Map.of("type", "image",
            "source", Map.of("type", "base64", "media_type", mediaType, "data", data));
    }

    private static Object textBlock(String text) {
        return Map.of("type", "text", "text", text);
    }

    private static String blockText(List<CreateMessageRequest.RequestMessage> messages, int index) {
        List<?> blocks = (List<?>) messages.getFirst().content();
        return (String) ((Map<?, ?>) blocks.get(index)).get("text");
    }

    /** Numbers its captions so a reused one is distinguishable from a fresh one. */
    private static CountingImageClient distinctCaptionsPerCall() {
        AtomicInteger issued = new AtomicInteger();
        return new CountingImageClient(
            request -> ApiMessage.stub(request.model(), "caption-" + issued.incrementAndGet()));
    }

    /** Image endpoint stand-in that counts how many descriptions were really requested. */
    private static final class CountingImageClient implements LlmClient {
        private final Function<CreateMessageRequest, ApiMessage> responder;
        private final AtomicInteger calls = new AtomicInteger();

        CountingImageClient(Function<CreateMessageRequest, ApiMessage> responder) {
            this.responder = responder;
        }

        int calls() {
            return calls.get();
        }

        @Override
        public ApiMessage createMessage(CreateMessageRequest request, long timeoutMillis) {
            calls.incrementAndGet();
            return responder.apply(request);
        }

        @Override
        public ApiMessage createMessage(CreateMessageRequest request) {
            return createMessage(request, 0L);
        }

        @Override
        public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
            throw new UnsupportedOperationException("captioning never streams");
        }

        @Override
        public String getModel() {
            return IMAGE_MODEL;
        }
    }
}
