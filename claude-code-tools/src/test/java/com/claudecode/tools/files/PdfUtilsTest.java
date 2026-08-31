package com.claudecode.tools.files;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.process.ProcessResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;


class PdfUtilsTest {

    @TempDir
    Path tempDir;

    @Test
    void readPdfRejectsEmptyOversizeAndMissingHeaderWithOfficialMessages() throws IOException {
        Path empty = tempDir.resolve("empty.pdf");
        Files.write(empty, new byte[0]);
        assertPdfError(PdfUtils.readPdf(empty, empty.toString()),
            PdfUtils.PdfErrorReason.EMPTY,
            "PDF file is empty: " + empty);

        Path oversized = tempDir.resolve("oversized.pdf");
        sparsePdf(oversized, PdfUtils.PDF_TARGET_RAW_SIZE + 1);
        assertPdfError(PdfUtils.readPdf(oversized, oversized.toString()),
            PdfUtils.PdfErrorReason.TOO_LARGE,
            "PDF file exceeds maximum allowed size of 20MB.");

        Path renamedText = tempDir.resolve("renamed.pdf");
        Files.writeString(renamedText, "not actually a pdf");
        assertPdfError(PdfUtils.readPdf(renamedText, renamedText.toString()),
            PdfUtils.PdfErrorReason.CORRUPTED,
            "File is not a valid PDF (missing %PDF- header): " + renamedText);
    }

    @Test
    void extractPagesRejectsEmptyAndOver100MbBeforeSpawningPoppler() throws IOException {
        PdfUtils.CommandRunner mustNotRun = (command, _, _) -> {
            throw new AssertionError("process must not run for preflight failures: " + command);
        };

        Path empty = tempDir.resolve("empty.pdf");
        Files.write(empty, new byte[0]);
        assertPdfError(PdfUtils.extractPages(empty, empty.toString(), 1, 1,
                tempDir.resolve("results-empty"), mustNotRun),
            PdfUtils.PdfErrorReason.EMPTY,
            "PDF file is empty: " + empty);

        Path oversized = tempDir.resolve("over100.pdf");
        sparsePdf(oversized, PdfUtils.PDF_MAX_EXTRACT_SIZE + 1);
        assertPdfError(PdfUtils.extractPages(oversized, oversized.toString(), 1, 1,
                tempDir.resolve("results-large"), mustNotRun),
            PdfUtils.PdfErrorReason.TOO_LARGE,
            "PDF file exceeds maximum allowed size for text extraction (100MB).");
    }

    @Test
    void extractPagesClassifiesUnavailablePasswordCorruptionUnknownAndNoOutput() throws IOException {
        Path pdf = tempDir.resolve("doc.pdf");
        Files.writeString(pdf, "%PDF-1.7\n");

        assertPdfError(extractWith(pdf, "unavailable", (_, _, _) ->
                ProcessResult.failure()),
            PdfUtils.PdfErrorReason.UNAVAILABLE,
            "pdftoppm is not installed. Install poppler-utils (e.g. `brew install poppler` or "
                + "`apt-get install poppler-utils`) to enable PDF page rendering.");

        assertPdfError(extractWith(pdf, "password", failingExtraction("Incorrect password")),
            PdfUtils.PdfErrorReason.PASSWORD_PROTECTED,
            "PDF is password-protected. Please provide an unprotected version.");

        assertPdfError(extractWith(pdf, "corrupt",
                failingExtraction("Syntax Error: Couldn't find trailer dictionary\n")),
            PdfUtils.PdfErrorReason.CORRUPTED,
            "PDF file is corrupted or invalid.");

        assertPdfError(extractWith(pdf, "unknown", failingExtraction("unexpected renderer failure\n")),
            PdfUtils.PdfErrorReason.UNKNOWN,
            "pdftoppm failed: unexpected renderer failure");

        assertPdfError(extractWith(pdf, "empty-output", successfulExtractionWithoutFiles()),
            PdfUtils.PdfErrorReason.CORRUPTED,
            "pdftoppm produced no output pages. The PDF may be invalid.");
    }

    private PdfUtils.PdfResult<PdfUtils.ExtractedPages> extractWith(
            Path pdf, String label, PdfUtils.CommandRunner runner) {
        return PdfUtils.extractPages(pdf, pdf.toString(), 1, 1,
            tempDir.resolve("results-" + label), runner);
    }

    private static PdfUtils.CommandRunner failingExtraction(String stderr) {
        return (command, _, _) -> isVersionProbe(command)
            ? new ProcessResult("", "pdftoppm version 24", 0, false)
            : new ProcessResult("", stderr, 1, false);
    }

    private static PdfUtils.CommandRunner successfulExtractionWithoutFiles() {
        return (command, _, _) -> isVersionProbe(command)
            ? new ProcessResult("", "pdftoppm version 24", 0, false)
            : new ProcessResult("", "", 0, false);
    }

    private static boolean isVersionProbe(List<String> command) {
        return command.size() == 2 && Strings.CS.equals(command.getFirst(), "pdftoppm")
            && Strings.CS.equals(command.get(1), "-v");
    }

    private static void assertPdfError(
            PdfUtils.PdfResult<?> result, PdfUtils.PdfErrorReason reason, String message) {
        PdfUtils.PdfFailure<?> failure = assertInstanceOf(PdfUtils.PdfFailure.class, result);
        assertEquals(reason, failure.error().reason());
        assertEquals(message, failure.error().message());
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
