package com.claudecode.ui.lanterna.transcript;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.constants.Figures;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.apache.commons.lang3.Strings;

/**
 * Concurrency-safe tools (Read) are dispatched as one parallel batch, so several tool cards
 * are on screen before the first result arrives. These tests pin the two things that broke:
 * each result body must be filed under the card that owns its {@code tool_use_id} instead of
 * appended below the last card, and a Read whose content is an image must say what was read.
 */
class ToolResultPlacementTest {

    @Test
    void filesEachConcurrentResultUnderItsOwnToolCard() {
        MessagePanel panel = new MessagePanel();
        MessageCollapser collapser = collapser();
        panel.appendLine("> describe both screenshots", null);

        announceRead(collapser, panel, "toolu_1", "/tmp/ccdiag/shot1.png");
        // Stray text between tool_use blocks ends the collapsed Read run, so the results can
        // no longer be absorbed into the group row — the path that exposed the defect.
        collapser.dispatch(new SDKMessage.StreamEvent("content_block_delta", "\n"), panel);
        announceRead(collapser, panel, "toolu_2", "/tmp/ccdiag/shot2.png");
        announceBash(collapser, panel, "toolu_3", "ls -la /tmp/ccdiag");

        collapser.dispatch(new SDKMessage.User(imageResult("toolu_1", 4_425_649L)), panel);
        collapser.dispatch(new SDKMessage.User(imageResult("toolu_2", 4_846_665L)), panel);
        collapser.dispatch(new SDKMessage.User(
            textResult("toolu_3", "total 18152\ndrwxr-xr-x 9 xmly wheel 288 .")), panel);

        List<String> rows = rows(panel);
        int shot1 = indexOfRowContaining(rows, "Read(/tmp/ccdiag/shot1.png)");
        int shot2 = indexOfRowContaining(rows, "Read(/tmp/ccdiag/shot2.png)");
        int bash = indexOfRowContaining(rows, "Bash(ls -la /tmp/ccdiag)");
        int firstImage = indexOfRowContaining(rows, "Read image (4.2MB)");
        int secondImage = indexOfRowContaining(rows, "Read image (4.6MB)");
        int bashBody = indexOfRowContaining(rows, "total 18152");

        assertThat(rows).as("no tool result may degrade to the empty-body marker")
            .noneMatch(row -> Strings.CS.contains(row, "(no output)"));
        assertThat(rows.stream().filter(row -> Strings.CS.contains(row, "Bash(ls -la /tmp/ccdiag)")).count())
            .as("the completion repaint must land on the Bash card, not duplicate it")
            .isEqualTo(1);
        assertThat(firstImage).as("shot1's body belongs between its own card and shot2's")
            .isBetween(shot1 + 1, shot2 - 1);
        assertThat(secondImage).as("shot2's body belongs between its own card and Bash's")
            .isBetween(shot2 + 1, bash - 1);
        assertThat(bashBody).as("Bash's body stays under Bash").isGreaterThan(bash);
    }

    @Test
    void filesAResultUnderItsCardEvenWhenALaterCardResolvedFirst() {
        MessagePanel panel = new MessagePanel();
        MessageCollapser collapser = collapser();
        panel.appendLine("> read both", null);

        announceRead(collapser, panel, "toolu_1", "/tmp/ccdiag/shot1.png");
        collapser.dispatch(new SDKMessage.StreamEvent("content_block_delta", "\n"), panel);
        announceRead(collapser, panel, "toolu_2", "/tmp/ccdiag/shot2.png");
        announceBash(collapser, panel, "toolu_3", "ls");

        // Out-of-order completion: the last card resolves first, then the middle, then the first.
        collapser.dispatch(new SDKMessage.User(textResult("toolu_3", "shot1.png")), panel);
        collapser.dispatch(new SDKMessage.User(imageResult("toolu_2", 4_846_665L)), panel);
        collapser.dispatch(new SDKMessage.User(imageResult("toolu_1", 4_425_649L)), panel);

        List<String> rows = rows(panel);
        assertThat(indexOfRowContaining(rows, "Read image (4.2MB)"))
            .isBetween(indexOfRowContaining(rows, "Read(/tmp/ccdiag/shot1.png)") + 1,
                indexOfRowContaining(rows, "Read(/tmp/ccdiag/shot2.png)") - 1);
        assertThat(indexOfRowContaining(rows, "Read image (4.6MB)"))
            .isBetween(indexOfRowContaining(rows, "Read(/tmp/ccdiag/shot2.png)") + 1,
                indexOfRowContaining(rows, "Bash(ls)") - 1);
    }

    @Test
    void summarizesStructuredReadPayloadsTheWayTheOriginalDoes() {
        assertThat(readResultRow(imagePayload(4_425_649L))).isEqualTo(gutter("Read image (4.2MB)"));
        assertThat(readResultRow(payload("pdf", file -> file.put("originalSize", 1024))))
            .isEqualTo(gutter("Read PDF (1KB)"));
        assertThat(readResultRow(payload("parts", file -> {
            file.put("count", 1);
            file.put("originalSize", 2048);
        }))).isEqualTo(gutter("Read 1 page (2KB)"));
        assertThat(readResultRow(payload("parts", file -> {
            file.put("count", 3);
            file.put("originalSize", 3_145_728L);
        }))).isEqualTo(gutter("Read 3 pages (3MB)"));
        assertThat(readResultRow(payload("text", file -> file.put("numLines", 42))))
            .isEqualTo(gutter("Read 42 lines"));
        assertThat(readResultRow(payload("text", file -> file.put("numLines", 1))))
            .isEqualTo(gutter("Read 1 line"));
        assertThat(readResultRow(payload("file_unchanged", _ -> { })))
            .isEqualTo(gutter("Unchanged since last read"));
        assertThat(readResultRow(payload("notebook", file -> file.putArray("cells"))))
            .isEqualTo(gutter("No cells found in notebook"));
        // The notebook arm hard-codes the plural upstream, unlike the line count above.
        assertThat(readResultRow(payload("notebook", file -> file.putArray("cells").addObject())))
            .isEqualTo(gutter("Read 1 cells"));
    }

    private static String gutter(String text) {
        return Figures.RESULT_PREFIX + text;
    }

    /** A Read whose structured payload never arrived keeps the generic text body. */
    @Test
    void fallsBackToTheGenericBodyWithoutAStructuredPayload() {
        MessagePanel panel = new MessagePanel();
        MessageCollapser collapser = collapser();
        announceRead(collapser, panel, "toolu_1", "/tmp/notes.txt");
        collapser.dispatch(new SDKMessage.StreamEvent("content_block_delta", "\n"), panel);
        collapser.dispatch(new SDKMessage.User(new UserMessage("result",
            MessageContent.ofToolResult("toolu_1", List.of(new TextBlock("1→hello")), false),
            false, false, null, MessageOrigin.USER, null, Instant.now(), null, null)), panel);

        assertThat(rows(panel)).anyMatch(row -> Strings.CS.contains(row, "hello"));
    }

    private static String readResultRow(ObjectNode payload) {
        MessagePanel panel = new MessagePanel();
        MessageCollapser collapser = collapser();
        announceRead(collapser, panel, "toolu_1", "/tmp/ccdiag/shot1.png");
        // Ends the collapsed run so the result reaches the per-card renderer under test.
        collapser.dispatch(new SDKMessage.StreamEvent("content_block_delta", "\n"), panel);
        collapser.dispatch(new SDKMessage.User(new UserMessage("result",
            MessageContent.ofToolResult("toolu_1", List.of(new TextBlock("")), false),
            false, false, payload, MessageOrigin.USER, null, Instant.now(), null, null)), panel);
        return rows(panel).stream().filter(row -> Strings.CS.contains(row, "⎿")).reduce((_, last) -> last)
            .orElseThrow();
    }

    private static MessageCollapser collapser() {
        return new MessageCollapser(new LanternaMessageDispatcher(), false);
    }

    private static List<String> rows(MessagePanel panel) {
        return panel.snapshotStyledLines().stream().map(MessagePanel.StyledLine::text).toList();
    }

    private static int indexOfRowContaining(List<String> rows, String needle) {
        for (int i = 0; i < rows.size(); i++) {
            if (Strings.CS.contains(rows.get(i), needle)) return i;
        }
        throw new AssertionError("no row contains " + needle + " in " + rows);
    }

    private static void announceRead(MessageCollapser collapser, MessagePanel panel,
                                    String toolUseId, String path) {
        ObjectNode input = JsonUtils.getMapper().createObjectNode().put("file_path", path);
        announce(collapser, panel, "Read", toolUseId, input);
    }

    private static void announceBash(MessageCollapser collapser, MessagePanel panel,
                                     String toolUseId, String command) {
        ObjectNode input = JsonUtils.getMapper().createObjectNode().put("command", command);
        announce(collapser, panel, "Bash", toolUseId, input);
    }

    private static void announce(MessageCollapser collapser, MessagePanel panel, String toolName,
                                 String toolUseId, ObjectNode input) {
        collapser.dispatch(new SDKMessage.StreamEvent("tool_streaming_start",
            toolName + "|" + toolUseId + "|msg_1"), panel);
        collapser.dispatch(new SDKMessage.Assistant(new AssistantMessage("assistant-" + toolUseId,
            AssistantContent.of("msg_1", List.of(new ToolUseBlock(toolUseId, toolName, input)),
                Usage.EMPTY)), Usage.EMPTY), panel);
        collapser.dispatch(new SDKMessage.StreamEvent("tool_streaming_done",
            toolName + "|" + toolUseId + "|" + input), panel);
    }

    private static ObjectNode imagePayload(long originalSize) {
        return payload("image", file -> {
            file.put("type", "image/jpeg");
            file.put("originalSize", originalSize);
        });
    }

    private static ObjectNode payload(String type, Consumer<ObjectNode> file) {
        ObjectNode payload = JsonUtils.getMapper().createObjectNode().put("type", type);
        file.accept(payload.putObject("file"));
        return payload;
    }

    private static UserMessage imageResult(String toolUseId, long originalSize) {
        ObjectNode source = JsonUtils.getMapper().createObjectNode();
        source.put("type", "base64");
        source.put("media_type", "image/jpeg");
        source.put("data", "/9j/4AAQ");
        List<ContentBlock> blocks = List.of(new ImageBlock(source));
        return new UserMessage("result-" + toolUseId,
            MessageContent.ofToolResult(toolUseId, blocks, false),
            false, false, imagePayload(originalSize), MessageOrigin.USER, null, Instant.now(),
            null, null);
    }

    private static UserMessage textResult(String toolUseId, String text) {
        ObjectNode payload = JsonUtils.getMapper().createObjectNode();
        payload.put("stdout", text);
        return new UserMessage("result-" + toolUseId,
            MessageContent.ofToolResult(toolUseId, List.of(new TextBlock(text)), false),
            false, false, payload, MessageOrigin.USER, null, Instant.now(), null, null);
    }
}
