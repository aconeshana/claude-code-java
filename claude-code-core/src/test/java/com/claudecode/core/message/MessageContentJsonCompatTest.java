package com.claudecode.core.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;


class MessageContentJsonCompatTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void javaNativeTextShapeParses() throws Exception {
        MessageContent mc = mapper.readValue(
            "{\"text\":\"hello\"}", MessageContent.class);
        assertEquals("hello", mc.text());
        assertNull(mc.blocks());
    }

    @Test
    void javaNativeBlocksShapeParses() throws Exception {
        MessageContent mc = mapper.readValue(
            "{\"blocks\":[{\"type\":\"text\",\"text\":\"hi\"}]}", MessageContent.class);
        assertNull(mc.text());
        assertNotNull(mc.blocks());
        assertEquals(1, mc.blocks().size());
        assertInstanceOf(TextBlock.class, mc.blocks().getFirst());
    }

    @Test
    void tsNativeStringContentBecomesText() throws Exception {

        // Must appear as MessageContent.text — else renderUserTextBlock has
        // nothing to draw and the message goes invisible on /resume.
        MessageContent mc = mapper.readValue(
            "{\"role\":\"user\",\"content\":\"hi from TS\"}", MessageContent.class);
        assertEquals("hi from TS", mc.text());
        assertNull(mc.blocks());
    }

    @Test
    void tsNativeArrayContentBecomesBlocks() throws Exception {
        MessageContent mc = mapper.readValue(
            "{\"role\":\"user\",\"content\":["
                + "{\"type\":\"text\",\"text\":\"a\"},"
                + "{\"type\":\"tool_result\",\"tool_use_id\":\"tu1\",\"content\":[{\"type\":\"text\",\"text\":\"result\"}]}"
                + "]}",
            MessageContent.class);
        assertNull(mc.text());
        assertNotNull(mc.blocks());
        assertEquals(2, mc.blocks().size());
        assertInstanceOf(TextBlock.class, mc.blocks().getFirst());
        assertInstanceOf(ToolResultBlock.class, mc.blocks().get(1));
    }

    @Test
    void unknownBlockTypeIsSkippedNotFatal() throws Exception {

        // Java version does not model. Legacy behaviour: the whole message was
        // dropped with a Jackson error. Desired: the unknown block is
        // silently omitted while sibling blocks survive.
        MessageContent mc = mapper.readValue(
            "{\"role\":\"user\",\"content\":["
                + "{\"type\":\"future_block\",\"payload\":{\"value\":1}},"
                + "{\"type\":\"text\",\"text\":\"please summarise\"}"
                + "]}",
            MessageContent.class);
        assertNotNull(mc.blocks());
        assertEquals(1, mc.blocks().size(), "unknown block should be dropped, text kept");
        assertInstanceOf(TextBlock.class, mc.blocks().getFirst());
    }

    @Test
    void allUnknownBlocksLeavesEmptyBlocksNull() throws Exception {
// Every block is unrecognised → blocks returns null (not empty list),
        // which downstream renderUser interprets as "nothing to draw" — the
        // message is silently invisible instead of a stack-trace on resume.
        MessageContent mc = mapper.readValue(
            "{\"role\":\"user\",\"content\":["
                + "{\"type\":\"future_block\",\"payload\":{\"value\":1}}"
                + "]}",
            MessageContent.class);
        assertNull(mc.blocks());
        assertNull(mc.text());
    }

    @Test
    void emptyObjectYieldsEmptyContent() throws Exception {
        // Jackson short-circuits a literal `null` JSON to a Java null before the
        // creator runs, so the empty-object case is what our factory actually sees.
        MessageContent mc = mapper.readValue("{}", MessageContent.class);
        assertNull(mc.text());
        assertNull(mc.blocks());
    }

    @Test
    void embeddedInUserMessageRoundTripsTsShape() throws Exception {
        // The full path exercised by /resume: {type:"user", uuid:..., message:{role,content}}
// must land in UserMessage.message with correctly-parsed text.
        String tsLine = "{\"type\":\"user\",\"uuid\":\"u1\","
            + "\"message\":{\"role\":\"user\",\"content\":\"resume me\"}}";
        Message m = mapper.readValue(tsLine, Message.class);
        assertInstanceOf(UserMessage.class, m);
        UserMessage um = (UserMessage) m;
        assertNotNull(um.message());
        assertEquals("resume me", um.message().text());
    }
}
