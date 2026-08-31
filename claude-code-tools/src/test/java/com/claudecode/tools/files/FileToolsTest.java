package com.claudecode.tools.files;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.diff.FileChangeResult;
import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.FileHistoryManager;
import com.claudecode.core.engine.FileStateCache;
import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.engine.StructuredToolOutput;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.DocumentBlock;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.io.FileUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;
import com.claudecode.tools.ToolRegistry;

class FileToolsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir
    Path tempDir;

    private ToolExecutionContext ctx() {
        return ToolExecutionContext.builder(new AbortController(), "test-session").workingDirectory(tempDir.toString()).build();
    }

    private static String modelText(Object raw) {
        return raw instanceof StructuredToolOutput output ? output.text() : String.valueOf(raw);
    }

    @Test
    void readMaxResultSizeChars_matchesReleased197Infinity() {
        assertEquals(Integer.MAX_VALUE, new FileReadTool().maxResultSizeChars());
    }

    /**
     * Reads {@code relativePath} through {@code ctx} so FileWrite/FileEdit's
     * read-before-write check (backed by {@code ctx}'s shared
     * {@code FileStateCache}) sees it as read. Tests that Write/Edit a
     * pre-existing file must call this first — a fresh {@link #ctx} per
     * call would give Read and Write/Edit unrelated caches.
     */
    private void readThrough(ToolExecutionContext ctx, String relativePath) {
        ObjectNode readInput = mapper.createObjectNode();
        readInput.put("file_path", relativePath);
        new FileReadTool().call(readInput, ctx);
    }

    // --- FileReadTool tests ---

    @Test
    void readExistingFile() throws IOException {
        Files.writeString(tempDir.resolve("test.txt"), "hello world", StandardCharsets.UTF_8);

        FileReadTool tool = new FileReadTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "test.txt");

        Object raw = tool.call(input, ctx());
        StructuredToolOutput output = assertInstanceOf(StructuredToolOutput.class, raw);
        String result = output.text();

        assertEquals("1\thello world", result);

        ObjectNode payload = mapper.valueToTree(output.toolUseResult());
        assertEquals("text", payload.path("type").asText());
        assertEquals("test.txt", payload.path("file").path("filePath").asText());
        assertEquals("hello world", payload.path("file").path("content").asText());
        assertEquals(1, payload.path("file").path("numLines").asInt());
        assertEquals(1, payload.path("file").path("startLine").asInt());
        assertEquals(1, payload.path("file").path("totalLines").asInt());
    }

    @Test
    void readNonexistentFile() {
        FileReadTool tool = new FileReadTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "nonexistent.txt");

        String result = modelText(tool.call(input, ctx()));
        assertTrue(Strings.CS.contains(result, "Error"));
        assertTrue(Strings.CS.contains(result, "File does not exist"), "got: " + result);
        assertTrue(Strings.CS.contains(result, FileUtils.FILE_NOT_FOUND_CWD_NOTE), "got: " + result);
    }

    @Test
    void readFileWithLineRange() throws IOException {
        Files.writeString(tempDir.resolve("lines.txt"), "line1\nline2\nline3\nline4\nline5");

        FileReadTool tool = new FileReadTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "lines.txt");

        input.put("offset", 2);
        input.put("limit", 3);

        String result = modelText(tool.call(input, ctx()));
        String expected = "2\tline2\n3\tline3\n4\tline4";
        assertEquals(expected, result);
    }

    /**
     * HIGH gap #9: read dedup must be range-aware.
     */
    @Test
    void readDedupIsRangeAware() throws IOException {
        Files.writeString(tempDir.resolve("dedup.txt"), "line1\nline2\nline3\nline4\nline5");

        FileReadTool tool = new FileReadTool();
        ToolExecutionContext ctx = ctx();

        // First full read → returns content (not deduped).
        ObjectNode first = readInput("dedup.txt");
        String firstResult = modelText(tool.call(first, ctx));
        assertTrue(Strings.CS.contains(firstResult, "line1"), "first full read returns content, got: " + firstResult);

        // Second full read (same range) → deduped to [file_unchanged].
        ObjectNode second = readInput("dedup.txt");
        String secondResult = modelText(tool.call(second, ctx));
        assertTrue(Strings.CS.contains(secondResult, "[file_unchanged]"),
            "second full read of same range must be deduped, got: " + secondResult);

        // Targeted read of a different range → must NOT be deduped.
        ObjectNode targeted = readInput("dedup.txt");
        targeted.put("offset", 2);
        targeted.put("limit", 2);
        String targetedResult = modelText(tool.call(targeted, ctx));
        assertTrue(Strings.CS.contains(targetedResult, "line2"),
            "targeted read returns its own content, got: " + targetedResult);
        assertFalse(Strings.CS.contains(targetedResult, "[file_unchanged]"),
            "different range must not be deduped, got: " + targetedResult);

        // Back to the full range → content is returned again. The original
        // stores only the latest range in the session-scoped readFileState; it
        // does not keep a process-global history of every range ever read.
        ObjectNode again = readInput("dedup.txt");
        String againResult = modelText(tool.call(again, ctx));
        assertTrue(Strings.CS.contains(againResult, "line1"),
            "a non-latest range must be read again, got: " + againResult);
        assertFalse(Strings.CS.contains(againResult, "[file_unchanged]"),
            "dedup tracks only the latest session range, got: " + againResult);
    }

    @Test
    void readDedupDoesNotLeakAcrossSessions() throws IOException {
        Files.writeString(tempDir.resolve("session-dedup.txt"), "session scoped");
        FileReadTool tool = new FileReadTool();

        String first = modelText(tool.call(readInput("session-dedup.txt"), ctx()));
        String otherSession = modelText(tool.call(readInput("session-dedup.txt"), ctx()));

        assertTrue(Strings.CS.contains(first, "session scoped"));
        assertTrue(Strings.CS.contains(otherSession, "session scoped"),
            "a fresh ToolExecutionContext must not inherit another session's Read dedup");
        assertFalse(Strings.CS.contains(otherSession, "[file_unchanged]"));
    }


    @Test
    void readOffsetPastEndReturnsWarning() throws IOException {
        Files.writeString(tempDir.resolve("short.txt"), "a\nb\nc");

        FileReadTool tool = new FileReadTool();
        ObjectNode input = readInput("short.txt");
        input.put("offset", 10); // file has only 3 lines

        String result = modelText(tool.call(input, ctx()));
        assertTrue(Strings.CS.contains(result, "Warning"), "got: " + result);
        assertTrue(Strings.CS.contains(result, "shorter than the provided offset"),
            "got: " + result);
        assertFalse(Strings.CS.startsWith(result, "Error:"),
            "offset-past-end must be a warning, not an error, got: " + result);
    }

    @Test
    void readBinaryFileReturnsError() throws IOException {
        byte[] binary = new byte[]{0x00, 0x01, 0x02, 0x03};
        Files.write(tempDir.resolve("binary.bin"), binary);

        FileReadTool tool = new FileReadTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "binary.bin");

        String result = modelText(tool.call(input, ctx()));
        assertTrue(Strings.CS.contains(result, "Error"));
        assertTrue(Strings.CS.contains(result, "binary"));
    }

    /**
     * Fix #11: the extension-based binary guard must reject a binary extension
     * ({@code .class}) even when the file has no NUL bytes, proving that the
     * extension guard runs independently of the content scan.
     */
    @Test
    void readBinaryExtensionRejectedWithoutNulBytes() throws IOException {
        // .class is a binary extension; write plain text (no NUL bytes) so the
        // NUL-byte scan alone would NOT catch it.
        Files.writeString(tempDir.resolve("Sample.class"), "public class Sample {}");

        FileReadTool tool = new FileReadTool();
        ObjectNode input = readInput("Sample.class");

        String result = tool.call(input, ctx()).toString();
        assertTrue(Strings.CS.contains(result, "Error"), "got: " + result);
        assertTrue(Strings.CS.contains(result, "binary"), "got: " + result);
        assertTrue(Strings.CS.contains(result, ".class"),
            "message must name the offending extension, got: " + result);
    }

    /**
     * Verifies that a PDF is delivered as a base64 {@code document} block in a
     * meta user message while {@code tool_result} remains a one-line metadata stub.
     */
    @Test
    void readPdfFileDeliversBase64DocumentBlock() throws IOException {
        // Minimal valid PDF header + a couple of bytes so Files.readAllBytes works.
        byte[] pdf = new byte[]{'%', 'P', 'D', 'F', '-', '1', '.', '4', '\n', 0x0A};
        Files.write(tempDir.resolve("doc.pdf"), pdf);

        FileReadTool tool = new FileReadTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "doc.pdf");

        Object raw = tool.call(input, ctx());
        assertInstanceOf(ToolResult.class, raw, "PDF read must return a ToolResult, got: " + raw);

        ToolResult tr = (ToolResult) raw;
        JsonNode payload = mapper.valueToTree(tr.toolUseResult());
        assertEquals("pdf", payload.path("type").asText());
        assertEquals("doc.pdf", payload.path("file").path("filePath").asText());
        assertEquals(pdf.length, payload.path("file").path("originalSize").asInt());
        assertEquals(Base64.getEncoder().encodeToString(pdf),
            payload.path("file").path("base64").asText());
        // tool_result content: one-line metadata stub, no extracted text.
        List<ContentBlock> content = tr.content();
        assertEquals(1, content.size());
        assertInstanceOf(TextBlock.class, content.getFirst());
        assertTrue(Strings.CS.contains(((TextBlock) content.getFirst()).text(), "PDF file read: doc.pdf"));

        // newMessages carries the isMeta user message with the document block.
        List<?> newMessages = tr.newMessages();
        assertEquals(1, newMessages.size());
        UserMessage docMsg = (UserMessage) newMessages.getFirst();
        assertTrue(docMsg.isMeta(), "PDF document message must be meta (isMeta=true)");

        ContentBlock block = docMsg.message().blocks().getFirst();
        assertInstanceOf(DocumentBlock.class, block);
        DocumentBlock doc = (DocumentBlock) block;
        assertEquals("base64", doc.source().get("type").asText());
        assertEquals("application/pdf", doc.source().get("media_type").asText());
        byte[] decoded = Base64.getDecoder().decode(doc.source().get("data").asText());
        assertArrayEquals(pdf, decoded, "data must be the base64 of the original PDF");
        assertNull(docMsg.origin(), "supplemental PDF content is internal, not human input");
    }


    @Test
    void readImageReturnsImageBlock() throws IOException {
        // 1x1 transparent PNG (valid signature so detectImageFormat + ImageResizer accept it).
        byte[] png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
        Files.write(tempDir.resolve("pixel.png"), png);

        FileReadTool tool = new FileReadTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "pixel.png");

        Object raw = tool.call(input, ctx());
        assertInstanceOf(ToolResult.class, raw, "image read must return a ToolResult, got: " + raw);

        ToolResult tr = (ToolResult) raw;

        List<ContentBlock> content = tr.content();
        assertEquals(1, content.size());
        assertInstanceOf(ImageBlock.class, content.getFirst());
        ImageBlock img = (ImageBlock) content.getFirst();
        assertEquals("base64", img.source().get("type").asText());
        assertTrue(Strings.CS.startsWith(img.source().get("media_type").asText(), "image/"),
            "media_type must be an image MIME, got: " + img.source().get("media_type").asText());
        byte[] decoded = Base64.getDecoder().decode(img.source().get("data").asText());
        assertTrue(decoded.length > 0, "image data must not be empty");

        ObjectNode toolUseResult = assertInstanceOf(
            ObjectNode.class, tr.toolUseResult(),
            "Read persists its discriminated image output separately from model-facing content");
        assertEquals("image", toolUseResult.path("type").asText());
        assertEquals(img.source().get("data").asText(),
            toolUseResult.path("file").path("base64").asText());
        assertEquals(img.source().get("media_type").asText(),
            toolUseResult.path("file").path("type").asText());
        assertEquals(png.length, toolUseResult.path("file").path("originalSize").asInt());
        assertEquals(1, toolUseResult.path("file").path("dimensions").path("originalWidth").asInt());
        assertEquals(1, toolUseResult.path("file").path("dimensions").path("displayWidth").asInt());
    }

    @Test
    void readImageExtensionWithUnrecognizedBytesReturnsOfficial197Diagnostic() throws IOException {
        Files.writeString(tempDir.resolve("not-really-an-image.png"),
            "this is not an image\n", StandardCharsets.UTF_8);

        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "not-really-an-image.png");
        ToolResult result = assertInstanceOf(
            ToolResult.class, new FileReadTool().call(input, ctx()));

        assertTrue(result.isError());
        TextBlock error = assertInstanceOf(TextBlock.class, result.content().getFirst());
        assertEquals(
            "File has an image extension but its content is not a valid PNG/JPEG/GIF/WebP. "
                + "Detected: unrecognized bytes (hex: 74 68 69 73 20 69 73 20). "
                + "This usually means a download saved an error/login page instead of the image. "
                + "Use `file \"not-really-an-image.png\"` to confirm, or read it as text with Bash "
                + "(e.g. `head -c 500`).",
            error.text());
        assertEquals("Error: " + error.text(), result.toolUseResult(),
            "2.1.197 persists the diagnostic with an Error: prefix in JSONL toolUseResult");
    }

    @Test
    void readCorruptPngAboveApiLimitReturnsOfficial197CompressionFailure() throws IOException {
        byte[] png = new byte[4_000_000];
        byte[] truncatedHeader = new byte[]{
            (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n',
            0, 0, 0, 13, 'I', 'H', 'D', 'R', 0, 0, 0, 120, 0, 0, 0, 120
        };
        System.arraycopy(truncatedHeader, 0, png, 0, truncatedHeader.length);
        Files.write(tempDir.resolve("truncated-over-limit.png"), png);

        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "truncated-over-limit.png");
        ToolResult result = assertInstanceOf(
            ToolResult.class, new FileReadTool().call(input, ctx()));

        assertTrue(result.isError());
        TextBlock error = assertInstanceOf(TextBlock.class, result.content().getFirst());
        assertEquals(
            "Unable to resize image (3.8MB raw, 5.1MB base64). "
                + "The image exceeds the 5MB API limit and compression failed. "
                + "Please resize the image manually or use a smaller image.",
            error.text());
        assertEquals("Error: " + error.text(), result.toolUseResult());
    }

    @Test
    void readEmptyImageReturnsOfficial197ErrorAndTranscriptPayload() throws IOException {
        Files.write(tempDir.resolve("empty.png"), new byte[0]);

        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "empty.png");
        ToolResult result = assertInstanceOf(
            ToolResult.class, new FileReadTool().call(input, ctx()));

        assertTrue(result.isError());
        TextBlock error = assertInstanceOf(TextBlock.class, result.content().getFirst());
        assertEquals("Image file is empty: empty.png", error.text());
        assertEquals("Error: Image file is empty: empty.png", result.toolUseResult());
    }

    @Test
    void readOversizedImageUsesApiWireBudgetAndInjectsDimensionMetadata() throws Exception {
        byte[] png = quantizedNoisePng(2100, 2100);
        assertTrue(png.length < 5 * 1024 * 1024,
            "fixture must pass the Read input-size guard, got " + png.length);
        Files.write(tempDir.resolve("oversized.png"), png);

        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "oversized.png");
        ToolResult result = assertInstanceOf(
            ToolResult.class, new FileReadTool().call(input, ctx()));

        ImageBlock image = assertInstanceOf(ImageBlock.class, result.content().getFirst());
        assertEquals("image/jpeg", image.source().get("media_type").asText());
        byte[] decoded = Base64.getDecoder().decode(
            image.source().get("data").asText());
        assertTrue(decoded.length <= 512_000,
            "Read image blocks must use the 2.1.197 API wire budget");

        assertNotNull(result.newMessages());
        UserMessage metadata = assertInstanceOf(
            UserMessage.class, result.newMessages().getFirst());
        assertTrue(metadata.isMeta());
        assertEquals(
            "[Image: original 2100x2100, displayed at 2000x2000. "
                + "Multiply coordinates by 1.05 to map to original image.]",
            metadata.message().text());
    }

    @Test
    void readImageLargerThanFiveMegabytesCompressesInsteadOfRejectingInputBytes() throws Exception {
        byte[] png = fullNoiseRgbaPng(1300, 1300);
        assertTrue(png.length > 5 * 1024 * 1024,
            "fixture must exceed the obsolete Java input guard, got " + png.length);
        Files.write(tempDir.resolve("over-five-megabytes.png"), png);

        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "over-five-megabytes.png");
        ToolResult result = assertInstanceOf(
            ToolResult.class, new FileReadTool().call(input, ctx()));

        assertFalse(result.isError(), "2.1.197 compresses large input files before the API limit");
        ImageBlock image = assertInstanceOf(ImageBlock.class, result.content().getFirst());
        assertEquals("image/jpeg", image.source().get("media_type").asText());
        assertTrue(Base64.getDecoder().decode(
            image.source().get("data").asText()).length <= 512_000);
        assertNull(result.newMessages(), "unchanged 1300x1300 dimensions need no metadata block");
    }

    private static byte[] quantizedNoisePng(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int state = 0x197197;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                state ^= state << 13;
                state ^= state >>> 17;
                state ^= state << 5;
                image.setRGB(x, y, state & 0x00c0_c0c0);
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }

    private static byte[] fullNoiseRgbaPng(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int state = 0x2197197;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                state ^= state << 13;
                state ^= state >>> 17;
                state ^= state << 5;
                image.setRGB(x, y, state);
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }


    @Test
    void pdfPageRangeParsing() {
        assertEquals(new PdfUtils.PageRange(3, 3), PdfUtils.parsePageRange("3"));
        assertEquals(new PdfUtils.PageRange(1, 5), PdfUtils.parsePageRange("1-5"));
        assertEquals(new PdfUtils.PageRange(10, 20), PdfUtils.parsePageRange("10-20"));
        assertEquals(new PdfUtils.PageRange(3, Integer.MAX_VALUE),
            PdfUtils.parsePageRange("3-"),
            "2.1.197 parses an open-ended range before rejecting it via the 20-page limit");
        // blank / malformed / reversed / zero → null
        assertNull(PdfUtils.parsePageRange(null));
        assertNull(PdfUtils.parsePageRange(""));
        assertNull(PdfUtils.parsePageRange("  "));
        assertNull(PdfUtils.parsePageRange("abc"));
        assertNull(PdfUtils.parsePageRange("5-1"));
        assertNull(PdfUtils.parsePageRange("0-3"));
        assertNull(PdfUtils.parsePageRange("-3"));
    }

    @Test
    void readPdfPageUsesOfficial197RasterAndPartsContract() throws Exception {
        Assumptions.assumeTrue(
            PdfUtils.isPopplerAvailable(),
            "poppler-utils is unavailable; skipping the real page-rasterization contract test");

        Path pdf = tempDir.resolve("page.pdf");
        Files.write(pdf, singlePagePdf());
        Path toolResultsRoot = tempDir.resolve("sessions/test-session/tool-results");
        FileReadTool tool = new FileReadTool(
            FileReadTool.DEFAULT_MAX_OUTPUT_TOKENS,
            null,
            (_, _) -> toolResultsRoot);

        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", pdf.toString());
        input.put("pages", "1");

        ToolResult result = assertInstanceOf(ToolResult.class, tool.call(input, ctx()));
        assertFalse(result.isError(), "valid PDF page extraction must succeed: " + result.content());
        JsonNode output = mapper.valueToTree(result.toolUseResult());
        assertEquals("parts", output.path("type").asText());
        assertEquals(pdf.toString(), output.path("file").path("filePath").asText());
        assertEquals(Files.size(pdf), output.path("file").path("originalSize").asLong());
        assertEquals(1, output.path("file").path("count").asInt());

        Path outputDir = Path.of(output.path("file").path("outputDir").asText());
        assertEquals(toolResultsRoot, outputDir.getParent());
        assertTrue(Strings.CS.startsWith(outputDir.getFileName().toString(), "pdf-"));
        assertTrue(Files.isDirectory(outputDir), "2.1.197 keeps extracted pages for replay");
        try (var pages = Files.list(outputDir)) {
            assertEquals(1, pages.filter(p -> Strings.CS.endsWith(p.getFileName().toString(), ".jpg")).count());
        }

        UserMessage pageMessage = assertInstanceOf(UserMessage.class, result.newMessages().getFirst());
        assertTrue(pageMessage.isMeta());
        assertNull(pageMessage.origin(), "internally injected page images are not human prompts");
        ImageBlock imageBlock = assertInstanceOf(ImageBlock.class,
            pageMessage.message().blocks().getFirst());
        byte[] jpeg = Base64.getDecoder().decode(imageBlock.source().path("data").asText());
        BufferedImage rendered = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertNotNull(rendered);
        assertEquals(827, rendered.getWidth(), "A4 at the official 100 DPI raster density");
        assertEquals(1170, rendered.getHeight(), "A4 at the official 100 DPI raster density");
    }

    /**
     * The 20-page-per-read limit is enforced by the caller ({@code
     * FileReadTool#readPdfFile}), not {@link PdfUtils#parsePageRange} — so a
     * request like {@code "1-21"} must be rejected with a clear error before
     * poppler is even consulted (poppler-independent).
     */
    @Test
    void readPdfPagesExceedingLimitReturnsError() throws IOException {
        byte[] pdf = new byte[]{'%', 'P', 'D', 'F', '-', '1', '.', '4', '\n', 0x0A};
        Files.write(tempDir.resolve("doc.pdf"), pdf);

        FileReadTool tool = new FileReadTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "doc.pdf");
        input.put("pages", "1-21");

        Object raw = tool.call(input, ctx());
        assertInstanceOf(ToolResult.class, raw);
        ToolResult tr = (ToolResult) raw;
        assertTrue(tr.isError(), "expected an error for a >20-page range");
        assertTrue(Strings.CS.contains(tr.content().getFirst().toString(), "maximum of 20 pages"),
            "error must mention the 20-page limit, got: " + tr.content());
    }


    @Test
    void readPdfWithPagesRequiresPoppler() throws IOException {
        Assumptions.assumeTrue(
            !PdfUtils.isPopplerAvailable(),
            "poppler-utils is installed; skipping the unavailable-poppler error-path test");

        // Any file named .pdf is enough — isPopplerAvailable() is checked before pdftoppm runs.
        byte[] pdf = new byte[]{'%', 'P', 'D', 'F', '-', '1', '.', '4', '\n', 0x0A};
        Files.write(tempDir.resolve("doc.pdf"), pdf);

        FileReadTool tool = new FileReadTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "doc.pdf");
        input.put("pages", "1-2");

        Object raw = tool.call(input, ctx());
        assertInstanceOf(ToolResult.class, raw);
        ToolResult tr = (ToolResult) raw;
        assertTrue(tr.isError(), "expected an error when poppler is unavailable");
        assertTrue(Strings.CI.contains(tr.content().getFirst().toString(), "poppler"),
            "error must mention poppler-utils, got: " + tr.content());
    }

    private static byte[] singlePagePdf() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        out.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
        writePdfObject(out, offsets, 1, "<< /Type /Catalog /Pages 2 0 R >>");
        writePdfObject(out, offsets, 2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
        writePdfObject(out, offsets, 3,
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] "
                + "/Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>");
        String stream = "BT /F1 24 Tf 72 770 Td (PDF page) Tj ET\n";
        writePdfObject(out, offsets, 4,
            "<< /Length " + stream.getBytes(StandardCharsets.US_ASCII).length
                + " >>\nstream\n" + stream + "endstream");
        writePdfObject(out, offsets, 5,
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");

        int xref = out.size();
        out.write("xref\n0 6\n0000000000 65535 f \n".getBytes(StandardCharsets.US_ASCII));
        for (int offset : offsets) {
            out.write(String.format("%010d 00000 n %n", offset)
                .getBytes(StandardCharsets.US_ASCII));
        }
        out.write(("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n"
            + xref + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    private static void writePdfObject(ByteArrayOutputStream out, List<Integer> offsets,
                                       int number, String body) throws IOException {
        offsets.add(out.size());
        out.write((number + " 0 obj\n" + body + "\nendobj\n")
            .getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void readToolIsReadOnly() {
        assertTrue(new FileReadTool().isReadOnly());
    }

    @Test
    void readToolIsConcurrencySafe() {
        assertTrue(new FileReadTool().isConcurrencySafe());
    }

    /** Success paths now return {@link StructuredToolOutput}; extract the model text. */
    private static StructuredToolOutput structured(Object result) {
        assertInstanceOf(StructuredToolOutput.class, result,
            "expected StructuredToolOutput, got: " + result);
        return (StructuredToolOutput) result;
    }

    // --- FileWriteTool tests ---

    @Test
    void writeNewFile() {
        FileWriteTool tool = new FileWriteTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "new-file.txt");
        input.put("content", "hello world");

        String result = structured(tool.call(input, ctx())).text();
        assertEquals("File created successfully at: new-file.txt "
            + "(file state is current in your context — no need to Read it back)", result);
        assertTrue(Files.exists(tempDir.resolve("new-file.txt")));
    }

    @Test
    void writeCreatesParentDirectories() {
        FileWriteTool tool = new FileWriteTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "sub/dir/file.txt");
        input.put("content", "nested content");

        String result = structured(tool.call(input, ctx())).text();
        assertTrue(Strings.CS.contains(result, "created successfully"), "got: " + result);
        assertTrue(Files.exists(tempDir.resolve("sub/dir/file.txt")));
    }

    @Test
    void writeOverwritesExistingFile() throws IOException {
        Files.writeString(tempDir.resolve("existing.txt"), "old content");

        ToolExecutionContext ctx = ctx();
        readThrough(ctx, "existing.txt");

        FileWriteTool tool = new FileWriteTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "existing.txt");
        input.put("content", "new content");

        String result = structured(tool.call(input, ctx)).text();
        assertEquals("The file existing.txt has been updated successfully. "
            + "(file state is current in your context — no need to Read it back)", result);
        assertEquals("new content", Files.readString(tempDir.resolve("existing.txt")));
    }

    @Test
    void writeRejectsUnreadExistingFile() throws IOException {
        Files.writeString(tempDir.resolve("existing.txt"), "old content");

        FileWriteTool tool = new FileWriteTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "existing.txt");
        input.put("content", "new content");

        String result = (String) tool.call(input, ctx());
        assertTrue(Strings.CS.contains(result, "has not been read yet"), "got: " + result);
        assertEquals("old content", Files.readString(tempDir.resolve("existing.txt")),
            "rejected write must not touch the file");
    }

    @Test
    void writeNewFileCarriesCreateChangeResult() {
        FileWriteTool tool = new FileWriteTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "created.txt");
        input.put("content", "line1\nline2");

        StructuredToolOutput out = structured(tool.call(input, ctx()));
        FileChangeResult change = assertInstanceOf(FileChangeResult.class, out.toolUseResult());
        assertEquals("create", change.type());
        assertEquals("line1\nline2", change.content());
        assertTrue(change.structuredPatch().isEmpty(), "create carries no patch");
        assertEquals(tempDir.resolve("created.txt").toAbsolutePath().normalize().toString(),
            change.filePath());
        ObjectNode persisted = mapper.valueToTree(change);
        assertTrue(persisted.has("originalFile"),
            "released 2.1.197 persists an explicit originalFile:null for new files");
        assertTrue(persisted.get("originalFile").isNull());
        assertTrue(persisted.has("userModified"));
        assertFalse(persisted.path("userModified").asBoolean(true));
    }

    @Test
    void writeExistingFileCarriesUpdateChangeResultWithHunks() throws IOException {
        Files.writeString(tempDir.resolve("upd.txt"), "a\nb\nc");

        ToolExecutionContext ctx = ctx();
        readThrough(ctx, "upd.txt");

        FileWriteTool tool = new FileWriteTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "upd.txt");
        input.put("content", "a\nB\nc");

        StructuredToolOutput out = structured(tool.call(input, ctx));
        FileChangeResult change = assertInstanceOf(FileChangeResult.class, out.toolUseResult());
        assertEquals("update", change.type());
        assertEquals("a\nB\nc", change.content());
        ObjectNode persisted = mapper.valueToTree(change);
        assertEquals("a\nb\nc", persisted.path("originalFile").asText());
        assertTrue(persisted.has("userModified"));
        assertFalse(persisted.path("userModified").asBoolean(true));
        assertFalse(change.structuredPatch().isEmpty(), "update must carry patch hunks");
        assertTrue(change.structuredPatch().getFirst().lines().contains("-b"));
        assertTrue(change.structuredPatch().getFirst().lines().contains("+B"));
    }

    // --- FileEditTool tests ---

    @Test
    void editReplacesExactMatch() throws IOException {
        Files.writeString(tempDir.resolve("edit.txt"), "hello world\nfoo bar\nbaz");

        ToolExecutionContext ctx = ctx();
        readThrough(ctx, "edit.txt");

        FileEditTool tool = new FileEditTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "edit.txt");
        input.put("old_str", "foo bar");
        input.put("new_str", "replaced");

        String result = structured(tool.call(input, ctx)).text();
        assertTrue(Strings.CS.contains(result, "has been updated successfully"), "got: " + result);

        String content = Files.readString(tempDir.resolve("edit.txt"));
        assertTrue(Strings.CS.contains(content, "replaced"));
        assertFalse(Strings.CS.contains(content, "foo bar"));
    }

    @Test
    void editCarriesFileChangeResultWithHunks() throws IOException {
        Files.writeString(tempDir.resolve("hunks.txt"), "one\ntwo\nthree");

        ToolExecutionContext ctx = ctx();
        readThrough(ctx, "hunks.txt");

        FileEditTool tool = new FileEditTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "hunks.txt");
        input.put("old_str", "two");
        input.put("new_str", "TWO");

        StructuredToolOutput out = structured(tool.call(input, ctx));
        FileChangeResult change = assertInstanceOf(FileChangeResult.class, out.toolUseResult());
        assertNull(change.type(), "Edit results carry no Write-style type");
        assertEquals(tempDir.resolve("hunks.txt").toAbsolutePath().normalize().toString(),
            change.filePath());
        assertFalse(change.structuredPatch().isEmpty());
        assertTrue(change.structuredPatch().getFirst().lines().contains("-two"));
        assertTrue(change.structuredPatch().getFirst().lines().contains("+TWO"));
        ObjectNode persisted = mapper.valueToTree(change);
        assertEquals("one\ntwo\nthree", persisted.path("originalFile").asText());
        assertEquals("two", persisted.path("oldString").asText());
        assertEquals("TWO", persisted.path("newString").asText());
        assertTrue(persisted.has("userModified"));
        assertFalse(persisted.path("userModified").asBoolean(true));
        assertFalse(persisted.path("replaceAll").asBoolean(true));
    }

    @Test
    void editRejectsNonUniqueMatch() throws IOException {
        Files.writeString(tempDir.resolve("dup.txt"), "hello hello hello");

        ToolExecutionContext ctx = ctx();
        readThrough(ctx, "dup.txt");

        FileEditTool tool = new FileEditTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "dup.txt");
        input.put("old_str", "hello");
        input.put("new_str", "world");

        String result = (String) tool.call(input, ctx);
        assertTrue(Strings.CS.contains(result, "Error"));
        assertTrue(Strings.CS.contains(result, "matches"));
    }

    @Test
    void editRejectsNotFound() throws IOException {
        Files.writeString(tempDir.resolve("nf.txt"), "hello world");

        ToolExecutionContext ctx = ctx();
        readThrough(ctx, "nf.txt");

        FileEditTool tool = new FileEditTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "nf.txt");
        input.put("old_str", "nonexistent");
        input.put("new_str", "replacement");

        String result = (String) tool.call(input, ctx);
        assertTrue(Strings.CS.contains(result, "Error"));
        assertTrue(Strings.CS.contains(result, "String to replace not found"), "got: " + result);
    }

    @Test
    void editRejectsUnreadExistingFile() throws IOException {
        Files.writeString(tempDir.resolve("edit.txt"), "hello world");

        FileEditTool tool = new FileEditTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "edit.txt");
        input.put("old_str", "hello");
        input.put("new_str", "goodbye");

        String result = (String) tool.call(input, ctx());
        assertTrue(Strings.CS.contains(result, "has not been read yet"), "got: " + result);
        assertEquals("hello world", Files.readString(tempDir.resolve("edit.txt")),
            "rejected edit must not touch the file");
    }

    @Test
    void editRejectsFileModifiedSinceRead() throws IOException, InterruptedException {
        Path file = tempDir.resolve("stale.txt");
        Files.writeString(file, "hello world");

        ToolExecutionContext ctx = ctx();
        readThrough(ctx, "stale.txt");

        // Ensure a distinct mtime tick, then modify the file "externally"
        // (i.e. not through this tool/context) after it was read.
        Thread.sleep(1100);
        Files.writeString(file, "hello world — edited externally");

        FileEditTool tool = new FileEditTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "stale.txt");
        input.put("old_str", "hello");
        input.put("new_str", "goodbye");

        String result = (String) tool.call(input, ctx);
        assertTrue(Strings.CS.contains(result, "modified since read"), "got: " + result);
    }

    @Test
    void editRejectsEmptyOldStr() throws IOException {
        Files.writeString(tempDir.resolve("empty.txt"), "content");

        FileEditTool tool = new FileEditTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "empty.txt");
        input.put("old_str", "");
        input.put("new_str", "replacement");

        String result = (String) tool.call(input, ctx());

        // specific "Cannot create new file - file already exists." message.
        assertTrue(Strings.CS.contains(result, "file already exists"), "got: " + result);
        assertNotEquals("replacement", Files.readString(tempDir.resolve("empty.txt")), "rejected edit must not touch the file");
    }

    @Test
    void editNonexistentFile() {
        FileEditTool tool = new FileEditTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "nonexistent.txt");
        input.put("old_str", "old");
        input.put("new_str", "new");

        String result = (String) tool.call(input, ctx());
        assertTrue(Strings.CS.contains(result, "Error"));
        assertTrue(Strings.CS.contains(result, "File does not exist"), "got: " + result);
        assertTrue(Strings.CS.contains(result, FileUtils.FILE_NOT_FOUND_CWD_NOTE), "got: " + result);
    }

    @Test
    void readSchemaOffsetAndLimitMatchWireMaximum() {

        var props = new FileReadTool().inputSchema().get("properties");
        assertEquals(9007199254740991L, props.get("offset").get("maximum").asLong());
        assertEquals(9007199254740991L, props.get("limit").get("maximum").asLong());
    }

    @Test
    void editAndWriteFilePathDescriptionsMatchWireText() {

        assertEquals("The absolute path to the file to modify",
            new FileEditTool().inputSchema().get("properties").get("file_path").get("description").asText());
        assertEquals("The absolute path to the file to write (must be absolute, not relative)",
            new FileWriteTool().inputSchema().get("properties").get("file_path").get("description").asText());
    }

    // --- /rewind "Restore code" checkpoint hook (FileHistoryManager) ---

    private ToolExecutionContext ctxWithFileHistory(FileHistoryManager fhm, String messageId) {
        return ToolExecutionContext.builder(new AbortController(), "test-session")
            .workingDirectory(tempDir.toString())
            .fileStateCache(new FileStateCache())
            .fileHistoryManager(fhm)
            .currentUserMessageId(messageId)
            .nestedMemoryAttachmentTriggers(ConcurrentHashMap.newKeySet())
            .loadedNestedMemoryPaths(ConcurrentHashMap.newKeySet())
            .build();
    }

    private FileHistoryManager newFileHistoryManager(Path backupRoot) {
        return new FileHistoryManager(
            SessionIdentity.of("test-session"), tempDir, backupRoot);
    }

    @Test
    void edit_withFileHistoryManager_backsUpBeforeWrite(@TempDir Path backupRoot) throws IOException {
        Files.writeString(tempDir.resolve("edit.txt"), "before edit");
        var fhm = newFileHistoryManager(backupRoot);
        fhm.makeSnapshot("msg-1");

        ToolExecutionContext ctx = ctxWithFileHistory(fhm, "msg-1");
        readThrough(ctx, "edit.txt");

        FileEditTool tool = new FileEditTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "edit.txt");
        input.put("old_string", "before edit");
        input.put("new_string", "after edit");
        tool.call(input, ctx);

        assertEquals("after edit", Files.readString(tempDir.resolve("edit.txt")));
        var backup = fhm.snapshotsView().getFirst().trackedFileBackups().get("edit.txt");
        assertNotNull(backup, "trackEdit must have backed up the file before the write");
        Path backupFile = backupRoot.resolve("test-session").resolve(backup.backupFileName());
        assertEquals("before edit", Files.readString(backupFile), "backup must hold pre-edit content");
    }

    @Test
    void edit_withNullFileHistoryManager_worksNormally() throws IOException {
        Files.writeString(tempDir.resolve("edit.txt"), "before edit");
        ToolExecutionContext ctx = ctx(); // fileHistoryManager() == null
        readThrough(ctx, "edit.txt");

        FileEditTool tool = new FileEditTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "edit.txt");
        input.put("old_string", "before edit");
        input.put("new_string", "after edit");

        assertDoesNotThrow(() -> tool.call(input, ctx));
        assertEquals("after edit", Files.readString(tempDir.resolve("edit.txt")));
    }

    @Test
    void write_withFileHistoryManager_backsUpBeforeWrite(@TempDir Path backupRoot) throws IOException {
        Files.writeString(tempDir.resolve("w.txt"), "old content");
        var fhm = newFileHistoryManager(backupRoot);
        fhm.makeSnapshot("msg-1");

        ToolExecutionContext ctx = ctxWithFileHistory(fhm, "msg-1");
        readThrough(ctx, "w.txt");

        FileWriteTool tool = new FileWriteTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "w.txt");
        input.put("content", "new content");
        tool.call(input, ctx);

        var backup = fhm.snapshotsView().getFirst().trackedFileBackups().get("w.txt");
        assertNotNull(backup);
        Path backupFile = backupRoot.resolve("test-session").resolve(backup.backupFileName());
        assertEquals("old content", Files.readString(backupFile));
    }

    @Test
    void write_withFileHistoryManager_newFile_recordsNullBackup(@TempDir Path backupRoot) {
        var fhm = newFileHistoryManager(backupRoot);
        fhm.makeSnapshot("msg-1");
        ToolExecutionContext ctx = ctxWithFileHistory(fhm, "msg-1");

        FileWriteTool tool = new FileWriteTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "brand-new.txt");
        input.put("content", "hello");
        tool.call(input, ctx);

        var backup = fhm.snapshotsView().getFirst().trackedFileBackups().get("brand-new.txt");
        assertNotNull(backup);
        assertNull(backup.backupFileName(), "file didn't exist before this write");
    }

    @Test
    void write_withNullFileHistoryManager_worksNormally() {
        FileWriteTool tool = new FileWriteTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "created.txt");
        input.put("content", "hello");

        assertDoesNotThrow(() -> tool.call(input, ctx()));
        assertTrue(Files.exists(tempDir.resolve("created.txt")));
    }

    private static final String MINIMAL_NOTEBOOK = """
        {"nbformat":4,"nbformat_minor":5,"cells":[
          {"id":"c1","cell_type":"code","source":"print(1)","metadata":{},"outputs":[],"execution_count":null}
        ],"metadata":{}}""";

    /**
     * Marks {@code relativePath} as read for the read-before-write gate,
     * bypassing {@link FileReadTool}. No longer required to make
     * {@code NotebookEditTool} tests pass — {@code FileReadTool#readNotebookFile}
     * now registers the read in {@code context.fileStateCache()} too (see
     * {@code notebookEdit_afterRealFileReadToolRead_passesReadBeforeWriteCheck}
     * below, which exercises the real {@link #readThrough} path instead).
     * Kept as a lighter-weight setup helper for tests below that only care
     * about file-history/backup behavior, not the Read path itself.
     */
    private void markNotebookRead(ToolExecutionContext ctx, Path path, String absolutePath) throws IOException {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        ctx.fileStateCache().set(absolutePath, new FileStateCache.FileState(
            content, Files.getLastModifiedTime(path).toMillis(), null, null, false));
    }

    @Test
    void notebookEdit_withFileHistoryManager_backsUpBeforeWrite(@TempDir Path backupRoot) throws IOException {
        Path nb = tempDir.resolve("nb.ipynb");
        Files.writeString(nb, MINIMAL_NOTEBOOK);
        var fhm = newFileHistoryManager(backupRoot);
        fhm.makeSnapshot("msg-1");

        ToolExecutionContext ctx = ctxWithFileHistory(fhm, "msg-1");
        markNotebookRead(ctx, nb, nb.toAbsolutePath().normalize().toString());

        NotebookEditTool tool = new NotebookEditTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("notebook_path", "nb.ipynb");
        input.put("cell_id", "c1");
        input.put("new_source", "print(2)");
        tool.call(input, ctx);

        var backup = fhm.snapshotsView().getFirst().trackedFileBackups().get("nb.ipynb");
        assertNotNull(backup, "trackEdit must have backed up the notebook before the write");
        Path backupFile = backupRoot.resolve("test-session").resolve(backup.backupFileName());
        assertEquals(MINIMAL_NOTEBOOK, Files.readString(backupFile), "backup must hold pre-edit notebook content");
    }

    @Test
    void notebookEdit_withNullFileHistoryManager_worksNormally() throws IOException {
        Path nb = tempDir.resolve("nb.ipynb");
        Files.writeString(nb, MINIMAL_NOTEBOOK);
        ToolExecutionContext ctx = ctx();
        markNotebookRead(ctx, nb, nb.toAbsolutePath().normalize().toString());

        NotebookEditTool tool = new NotebookEditTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("notebook_path", "nb.ipynb");
        input.put("cell_id", "c1");
        input.put("new_source", "print(2)");

        assertDoesNotThrow(() -> tool.call(input, ctx));
    }


    @Test
    void notebookEdit_afterRealFileReadToolRead_passesReadBeforeWriteCheck() throws IOException {
        Path nb = tempDir.resolve("nb.ipynb");
        Files.writeString(nb, MINIMAL_NOTEBOOK);
        ToolExecutionContext ctx = ctx();
        readThrough(ctx, "nb.ipynb");

        NotebookEditTool tool = new NotebookEditTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("notebook_path", "nb.ipynb");
        input.put("cell_id", "c1");
        input.put("new_source", "print(2)");

        String result = tool.call(input, ctx).toString();

        assertFalse(Strings.CS.startsWith(result, "Error: File has not been read yet"),
            "FileReadTool.call must register the notebook read in fileStateCache, "
                + "but got: " + result);
        assertTrue(Strings.CS.startsWith(result, "Updated cell"), "unexpected result: " + result);
    }

    /**
     * Staleness detection must still work after the fix above: reading a notebook, then modifying it
     * externally (bumping mtime past the read timestamp) with genuinely different content, must still
     * be rejected.
     */
    @Test
    void notebookEdit_afterRealFileReadToolRead_thenExternalModification_isRejectedAsStale() throws IOException {
        Path nb = tempDir.resolve("nb.ipynb");
        Files.writeString(nb, MINIMAL_NOTEBOOK);
        ToolExecutionContext ctx = ctx();
        readThrough(ctx, "nb.ipynb");

        // Simulate an external modification (user/linter) after the Read:
        // different content AND a strictly later mtime.
        String externallyModified = MINIMAL_NOTEBOOK.replace("print(1)", "print(999)");
        Files.writeString(nb, externallyModified);
        Files.setLastModifiedTime(nb,
            FileTime.fromMillis(
                Files.getLastModifiedTime(nb).toMillis() + 5000));

        NotebookEditTool tool = new NotebookEditTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("notebook_path", "nb.ipynb");
        input.put("cell_id", "c1");
        input.put("new_source", "print(2)");

        String result = tool.call(input, ctx).toString();

        assertTrue(Strings.CS.startsWith(result, "Error: File has been modified since read"),
            "external modification after Read must still be rejected as stale, but got: " + result);
    }



    private ObjectNode readInput(String path) {
        ObjectNode n = mapper.createObjectNode();
        n.put("file_path", path);
        return n;
    }

    /**
     * Below the cap → normal read, no token error, content returned verbatim
     * (with cat -n numbering). Uses a pinned cap so the fixture stays small.
     */
    @Test
    void readWithinTokenBudgetIsNotTruncated() throws IOException {
        // 300 chars / 4 ≈ 75 tokens < 100 cap (and > cap/4 = 25, so the gate runs).
        Files.writeString(tempDir.resolve("small.txt"), "x".repeat(300));

        Object raw = new FileReadTool(100).call(readInput("small.txt"), ctx());
        assertFalse(raw instanceof ToolResult tr && tr.isError(),
            "300-char .txt must not trip the 100-token cap, got: " + raw);
        String result = raw.toString();
        assertTrue(Strings.CS.contains(result, "x".repeat(20)), "content must be present, got: " + result);
        assertFalse(Strings.CS.contains(result, "exceeds maximum allowed tokens"),
            "no token error expected, got: " + result);
    }


    @Test
    void readExceedingTokenBudgetReturnsError() throws IOException {
        // 500 chars / 4 = 125 tokens > 100 cap.
        Files.writeString(tempDir.resolve("big.txt"), "x".repeat(500));

        Object raw = new FileReadTool(100).call(readInput("big.txt"), ctx());
        assertInstanceOf(ToolResult.class, raw, "oversized read must return a ToolResult, got: " + raw);
        ToolResult tr = (ToolResult) raw;
        assertTrue(tr.isError(), "oversized read must be a tool error");
        String text = tr.content().getFirst().toString();
        assertTrue(Strings.CS.contains(text, "exceeds maximum allowed tokens"),
            "TS error message expected, got: " + text);
        assertTrue(Strings.CS.contains(text, "(125 tokens)"),
            "error must report the estimated token count, got: " + text);
        assertTrue(Strings.CS.contains(text, "(100)"),
            "error must report the cap, got: " + text);
        assertTrue(Strings.CS.contains(text, "offset and limit parameters"),
            "error must suggest offset/limit, got: " + text);
    }


    @Test
    void readTokenBudgetBoundary() throws IOException {
        // 400 chars / 4 = 100 tokens == cap 100 → no error.
        Files.writeString(tempDir.resolve("at.txt"), "x".repeat(400));
        Object at = new FileReadTool(100).call(readInput("at.txt"), ctx());
        assertFalse(at instanceof ToolResult tr && tr.isError(),
            "exactly-at-cap read must not error, got: " + at);

        // 404 chars / 4 = 101 tokens > cap 100 → error.
        Files.writeString(tempDir.resolve("over.txt"), "x".repeat(404));
        Object over = new FileReadTool(100).call(readInput("over.txt"), ctx());
        assertInstanceOf(ToolResult.class, over);
        assertTrue(((ToolResult) over).isError(), "one-token-over read must error");
    }

    /**
     * The rough estimate keys on file type: json/jsonl/jsonc use a denser
     * 2-bytes/token ratio, so a 300-char .json (~150 tokens) trips the 100 cap
     * while a 300-char .txt (~75 tokens) does not.
     */
    @Test
    void readTokenGateJsonUsesDenserRatio() throws IOException {
        Files.writeString(tempDir.resolve("data.json"), "x".repeat(300));
        Object json = new FileReadTool(100).call(readInput("data.json"), ctx());
        assertInstanceOf(ToolResult.class, json);
        assertTrue(((ToolResult) json).isError(), ".json must trip the denser ratio, got: " + json);

        Files.writeString(tempDir.resolve("data.txt"), "x".repeat(300));
        Object txt = new FileReadTool(100).call(readInput("data.txt"), ctx());
        assertFalse(txt instanceof ToolResult tr && tr.isError(),
            "300-char .txt must not trip the 100-token cap, got: " + txt);
    }


    @Test
    void readNotebookExceedingTokenBudgetReturnsError() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"nbformat\":4,\"cells\":[");
        for (int i = 0; i < 200; i++) {
            sb.append("{\"cell_type\":\"code\",\"source\":\"").append("x".repeat(80)).append("\"}");
            if (i < 199) sb.append(',');
        }
        sb.append("],\"metadata\":{}}");
        Files.writeString(tempDir.resolve("big.ipynb"), sb.toString());

        Object raw = new FileReadTool(100).call(readInput("big.ipynb"), ctx());
        assertInstanceOf(ToolResult.class, raw);
        assertTrue(((ToolResult) raw).isError(), "oversized notebook must error, got: " + raw);
        assertTrue(Strings.CS.contains(((ToolResult) raw).content().getFirst().toString(), "exceeds maximum allowed tokens"));
    }

    /**
     * Default cap (25000) is wired through the public constructor: a single
     * ~100K-char line (~25001 tokens) trips it, while exactly 100K (~25000)
     * does not. Under the 256 KB pre-read size gate, so the size gate doesn't
     * mask the token gate.
     */
    @Test
    void readDefaultMaxTokensThreshold() throws IOException {
        Files.writeString(tempDir.resolve("huge.txt"), "x".repeat(100_004));
        Object over = new FileReadTool().call(readInput("huge.txt"), ctx());
        assertInstanceOf(ToolResult.class, over);
        assertTrue(((ToolResult) over).isError(), "100_004 chars must exceed the 25000 cap, got: " + over);

        Files.writeString(tempDir.resolve("exact.txt"), "x".repeat(100_000));
        Object exact = new FileReadTool().call(readInput("exact.txt"), ctx());
        assertFalse(exact instanceof ToolResult tr && tr.isError(),
            "exactly-100K chars must not error, got: " + exact);
    }
}
