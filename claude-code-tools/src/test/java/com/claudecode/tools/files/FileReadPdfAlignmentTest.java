package com.claudecode.tools.files;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.process.ProcessResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.claudecode.tools.ToolRegistry;
import com.claudecode.tools.ValidationResult;

/**
 * Wire-level Read/PDF behavior pinned to.
 */
class FileReadPdfAlignmentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void semanticPageValidationUsesOfficialMessagesAndRegistryEnvelope() throws IOException {
        Files.writeString(tempDir.resolve("doc.pdf"), "%PDF-1.7\n");
        FileReadTool tool = tool(noProcess());

        assertValidation(tool, "abc",
            "Invalid pages parameter: \"abc\". Use formats like \"1-5\", \"3\", or \"10-20\". "
                + "Pages are 1-indexed.");
        assertValidation(tool, "1-21",
            "Page range \"1-21\" exceeds maximum of 20 pages per request. Please use a smaller range.");
        assertValidation(tool, "3-",
            "Page range \"3-\" exceeds maximum of 20 pages per request. Please use a smaller range.");

        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        ToolResult result = registry.execute("Read", input("doc.pdf", "1-21"), ctx(null));
        assertTrue(result.isError());
        assertEquals("<tool_use_error>Page range \"1-21\" exceeds maximum of 20 pages per request. "
                + "Please use a smaller range.</tool_use_error>", text(result));
        assertEquals("Error: Page range \"1-21\" exceeds maximum of 20 pages per request. "
            + "Please use a smaller range.", result.toolUseResult());
    }

    @Test
    void blankOptionalPagesIsTreatedAsAbsentAcrossProtocolAdapters() throws IOException {
        FileReadTool tool = tool(noProcess());
        Files.writeString(tempDir.resolve("notes.txt"), "alpha\nbeta\n");

        assertInstanceOf(ValidationResult.Valid.class,
            tool.validateInput(input("notes.txt", ""), ctx("gpt-5.6-sol")));
        assertInstanceOf(ValidationResult.Valid.class,
            tool.validateInput(input("notes.txt", "   "), ctx("claude-sonnet-5")));

        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        ToolResult result = registry.execute("Read", input("notes.txt", ""), ctx("gpt-5.6-sol"));

        assertFalse(result.isError());
        assertTrue(Strings.CS.contains(text(result), "alpha"));
        assertTrue(Strings.CS.contains(text(result), "beta"));
    }

    @Test
    void fullReadErrorsMatch197Exactly() throws IOException {
        FileReadTool tool = tool(noProcess());

        Files.write(tempDir.resolve("empty.pdf"), new byte[0]);
        assertOfficialError(tool.call(input("empty.pdf", null), ctx(null)),
            "PDF file is empty: empty.pdf");

        Files.writeString(tempDir.resolve("missing-header.pdf"), "not a pdf");
        assertOfficialError(tool.call(input("missing-header.pdf", null), ctx(null)),
            "File is not a valid PDF (missing %PDF- header): missing-header.pdf");

        sparsePdf(tempDir.resolve("over20.pdf"), PdfUtils.PDF_TARGET_RAW_SIZE + 1);
        assertOfficialError(tool.call(input("over20.pdf", null), ctx(null)),
            "PDF file exceeds maximum allowed size of 20MB.");
    }

    @Test
    void moreThanTenPagesUsesOfficialRuntimeError() throws IOException {
        Files.writeString(tempDir.resolve("eleven.pdf"), "%PDF-1.7\n");
        PdfUtils.CommandRunner runner = (command, _, _) ->
            Strings.CS.equals(command.getFirst(), "pdfinfo")
                ? new ProcessResult("Pages:          11\n", "", 0, false)
                : ProcessResult.failure();

        assertOfficialError(tool(runner).call(input("eleven.pdf", null), ctx(null)),
            "This PDF has 11 pages, which is too many to read at once. "
                + "Use the pages parameter to read specific page ranges (e.g., pages: \"1-5\"). "
                + "Maximum 20 pages per request.");
    }

    @Test
    void supportedModelOverThreeMbStillReturnsFullPdfAfterBestEffortExtraction() throws IOException {
        Path pdf = tempDir.resolve("over3.pdf");
        sparsePdf(pdf, PdfUtils.PDF_EXTRACT_SIZE_THRESHOLD + 1);
        AtomicInteger extractionCalls = new AtomicInteger();
        PdfUtils.CommandRunner runner = (command, _, _) -> {
            if (Strings.CS.equals(command.getFirst(), "pdfinfo")) {
                return new ProcessResult("Pages: 1\n", "", 0, false);
            }
            if (isVersionProbe(command)) {
                return new ProcessResult("", "pdftoppm version 24", 0, false);
            }
            extractionCalls.incrementAndGet();
            return new ProcessResult("", "synthetic extraction failure", 1, false);
        };

        ToolResult result = assertInstanceOf(ToolResult.class,
            tool(runner).call(input("over3.pdf", null), ctx("claude-sonnet-4-6")));
        assertFalse(result.isError());
        JsonNode output = MAPPER.valueToTree(result.toolUseResult());
        assertEquals("pdf", output.path("type").asText());
        assertEquals(PdfUtils.PDF_EXTRACT_SIZE_THRESHOLD + 1,
            output.path("file").path("originalSize").asLong());
        assertEquals(1, extractionCalls.get(),
            ">3MB keeps the extraction side effect but discards its result on supported models");
    }

    @Test
    void haikuFullPdfReturnsExplicitUnsupportedModelError() throws IOException {
        Files.writeString(tempDir.resolve("doc.pdf"), "%PDF-1.7\n");
        PdfUtils.CommandRunner runner = (command, _, _) -> {
            if (Strings.CS.equals(command.getFirst(), "pdfinfo")) {
                return new ProcessResult("Pages: 1\n", "", 0, false);
            }
            return ProcessResult.failure();
        };

        assertOfficialError(tool(runner).call(input("doc.pdf", null),
                ctx("claude-3-haiku-20240307")),
            "Reading full PDFs is not supported with this model. Use a newer model (Sonnet 3.5 v2 or later), "
                + "or use the pages parameter to read specific page ranges (e.g., pages: \"1-5\", maximum "
                + "20 pages per request). Page extraction requires poppler-utils: install with `brew install "
                + "poppler` on macOS or `apt-get install poppler-utils` on Debian/Ubuntu.");
    }

    @Test
    void firstRequestedPagePastDocumentIsRejectedButTrailingEndMayClip() throws IOException {
        Files.writeString(tempDir.resolve("doc.pdf"), "%PDF-1.7\n");
        PdfUtils.CommandRunner runner = (command, _, _) ->
            Strings.CS.equals(command.getFirst(), "pdfinfo")
                ? new ProcessResult("Pages: 1\n", "", 0, false)
                : ProcessResult.failure();

        assertOfficialError(tool(runner).call(input("doc.pdf", "2"), ctx(null)),
            "Requested page 2 is outside the document (PDF has 1 page). Use a range within 1-1, "
                + "maximum 20 pages per request (e.g. pages: \"1-1\").");
        assertOfficialError(tool(runner).call(input("doc.pdf", "2-3"), ctx(null)),
            "Requested pages 2-3 is outside the document (PDF has 1 page). Use a range within 1-1, "
                + "maximum 20 pages per request (e.g. pages: \"1-1\").");
    }

    private FileReadTool tool(PdfUtils.CommandRunner runner) {
        return new FileReadTool(FileReadTool.DEFAULT_MAX_OUTPUT_TOKENS, null,
            (_, _) -> tempDir.resolve("tool-results"), runner);
    }

    private ToolExecutionContext ctx(String model) {
        return ToolExecutionContext.builder(new AbortController(), "test-session")
            .workingDirectory(tempDir.toString())
            .currentModel(model)
            .build();
    }

    private static ObjectNode input(String file, String pages) {
        ObjectNode input = MAPPER.createObjectNode().put("file_path", file);
        if (pages != null) input.put("pages", pages);
        return input;
    }

    private void assertValidation(FileReadTool tool, String pages, String expected) {
        ValidationResult.Invalid invalid = assertInstanceOf(ValidationResult.Invalid.class,
            tool.validateInput(input("doc.pdf", pages), ctx(null)));
        assertEquals(expected, invalid.message());
    }

    private static void assertOfficialError(Object raw, String expected) {
        ToolResult result = assertInstanceOf(ToolResult.class, raw);
        assertTrue(result.isError());
        assertEquals(expected, text(result));
        assertEquals("Error: " + expected, result.toolUseResult());
    }

    private static String text(ToolResult result) {
        return assertInstanceOf(TextBlock.class, result.content().getFirst()).text();
    }

    private static PdfUtils.CommandRunner noProcess() {
        return (_, _, _) -> ProcessResult.failure();
    }

    private static boolean isVersionProbe(List<String> command) {
        return command.size() == 2 && Strings.CS.equals(command.getFirst(), "pdftoppm")
            && Strings.CS.equals(command.get(1), "-v");
    }

    private static void sparsePdf(Path path, long size) throws IOException {
        try (FileChannel channel = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap("%PDF-1.7\n".getBytes()));
            channel.position(size - 1);
            channel.write(ByteBuffer.wrap(new byte[]{0}));
        }
    }
}
