package com.claudecode.tools.files;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.text.FormatUtils;
import com.claudecode.core.process.ProcessResult;
import com.claudecode.core.process.ProcessRunner;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * PDF validation, page-count, extraction, and page-range helpers.
 *
 * <ul>
 *   <li>Empty/20MB/header validation and
 *       the structured PDF success/error union.</li>
 *   <li>{@code pdfinfo} page-count
 *       probing.</li>
 *   <li>
 *       {@code extractPDFPages} — 100-DPI persisted JPEG extraction plus exact
 *       unavailable/password/corrupt/unknown/zero-output error classification.</li>
 *   <li>Single, closed, and
 *       open-ended page range parsing.</li>
 * </ul>
 */
final class PdfUtils {

/** Max pages per explicit Read request. */
    static final int PDF_MAX_PAGES_PER_READ = 20;
    /** Full reads above this page count require an explicit range. */
    static final int PDF_AT_MENTION_INLINE_THRESHOLD = 10;

    static final long PDF_EXTRACT_SIZE_THRESHOLD = 3L * 1024 * 1024;
    /** Raw full-document limit before base64/request overhead. */
    static final long PDF_TARGET_RAW_SIZE = 20L * 1024 * 1024;
    /** Page-rasterization input limit. */
    static final long PDF_MAX_EXTRACT_SIZE = 100L * 1024 * 1024;

    private static final Duration PDFINFO_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration AVAILABILITY_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration EXTRACTION_TIMEOUT = Duration.ofSeconds(120);
    private static final CommandRunner DEFAULT_COMMAND_RUNNER = ProcessRunner::run;
    private static volatile Boolean pdftoppmAvailable;

    private PdfUtils() {}

    @FunctionalInterface
    interface CommandRunner {
        ProcessResult run(List<String> command, Path cwd, Duration timeout);
    }

    enum PdfErrorReason {
        EMPTY,
        TOO_LARGE,
        PASSWORD_PROTECTED,
        CORRUPTED,
        UNKNOWN,
        UNAVAILABLE
    }

    record PdfError(PdfErrorReason reason, String message) {}

    sealed interface PdfResult<T> permits PdfSuccess, PdfFailure {}

    record PdfSuccess<T>(T data) implements PdfResult<T> {}

    record PdfFailure<T>(PdfError error) implements PdfResult<T> {}

    record FullPdf(String filePath, byte[] bytes, long originalSize) {}

    /** Inclusive 1-based page range. {@link Integer#MAX_VALUE} represents Infinity. */
    record PageRange(int first, int last) {}

    /** Persisted page images plus replay metadata. */
    record ExtractedPages(
        String filePath, long originalSize, List<byte[]> images, Path outputDir) {}

    static CommandRunner defaultCommandRunner() {
        return DEFAULT_COMMAND_RUNNER;
    }

    /**
     * matches {@code readPDF}: validates before base64 encoding so an invalid
     * document block can never poison subsequent requests in the session.
     */
    static PdfResult<FullPdf> readPdf(Path pdf, String displayPath) {
        try {
            long originalSize = Files.size(pdf);
            if (originalSize == 0) {
                return failure(PdfErrorReason.EMPTY, "PDF file is empty: " + displayPath);
            }
            if (originalSize > PDF_TARGET_RAW_SIZE) {
                return failure(PdfErrorReason.TOO_LARGE,
                    "PDF file exceeds maximum allowed size of "
                        + FormatUtils.formatFileSize(PDF_TARGET_RAW_SIZE) + ".");
            }

            byte[] bytes = Files.readAllBytes(pdf);
            String header = new String(bytes, 0, Math.min(5, bytes.length),
                StandardCharsets.US_ASCII);
            if (!Strings.CS.startsWith(header, "%PDF-")) {
                return failure(PdfErrorReason.CORRUPTED,
                    "File is not a valid PDF (missing %PDF- header): " + displayPath);
            }
            return new PdfSuccess<>(new FullPdf(displayPath, bytes, originalSize));
        } catch (Exception e) {
            return failure(PdfErrorReason.UNKNOWN, errorMessage(e));
        }
    }


    static PageRange parsePageRange(String pages) {
        if (pages == null) return null;
        String trimmed = pages.trim();
        if (trimmed.isEmpty()) return null;
        try {
            if (Strings.CS.endsWith(trimmed, "-")) {
                int first = Integer.parseInt(trimmed.substring(0, trimmed.length() - 1).trim());
                if (first < 1) return null;
                return new PageRange(first, Integer.MAX_VALUE);
            }
            int dash = trimmed.indexOf('-');
            if (dash >= 0) {
                int first = Integer.parseInt(trimmed.substring(0, dash).trim());
                int last = Integer.parseInt(trimmed.substring(dash + 1).trim());
                if (first < 1 || last < 1 || last < first) return null;
                return new PageRange(first, last);
            }
            int page = Integer.parseInt(trimmed);
            if (page < 1) return null;
            return new PageRange(page, page);
        } catch (NumberFormatException _) {
            return null;
        }
    }

    static boolean isPopplerAvailable() {
        return isPopplerAvailable(DEFAULT_COMMAND_RUNNER);
    }

    /** Testable equivalent of {@code isPdftoppmAvailable}. */
    static boolean isPopplerAvailable(CommandRunner runner) {
        if (runner == DEFAULT_COMMAND_RUNNER) {
            Boolean cached = pdftoppmAvailable;
            if (cached != null) return cached;
            boolean available = probePoppler(runner);
            pdftoppmAvailable = available;
            return available;
        }
        return probePoppler(runner);
    }

    private static boolean probePoppler(CommandRunner runner) {
        ProcessResult result = runner.run(
            List.of("pdftoppm", "-v"), null, AVAILABILITY_TIMEOUT);
        return result.exitCode() == 0 || !result.stderr().isEmpty();
    }

    /** Resets the production availability cache for tests that exercise PATH. */
    static void resetPopplerCache() {
        pdftoppmAvailable = null;
    }

    static Integer getPDFPageCount(Path pdf) {
        return getPDFPageCount(pdf, DEFAULT_COMMAND_RUNNER);
    }

    static Integer getPDFPageCount(Path pdf, CommandRunner runner) {
        ProcessResult result = runner.run(
            List.of("pdfinfo", pdf.toAbsolutePath().toString()), null, PDFINFO_TIMEOUT);
        if (result.exitCode() != 0) return null;
        for (String line : result.stdout().split("\n", -1)) {
            if (!Strings.CS.startsWith(line, "Pages:")) continue;
            try {
                return Integer.parseInt(line.substring("Pages:".length()).trim());
            } catch (NumberFormatException _) {
                return null;
            }
        }
        return null;
    }

    static PdfResult<ExtractedPages> extractPages(
            Path pdf, String displayPath, Integer first, Integer last, Path toolResultsDir) {
        return extractPages(pdf, displayPath, first, last, toolResultsDir,
            DEFAULT_COMMAND_RUNNER);
    }

    /**
     * matches {@code extractPDFPages}; every failure retains its reason and
     * exact model-facing text instead of collapsing to an empty image list.
     */
    static PdfResult<ExtractedPages> extractPages(
            Path pdf, String displayPath, Integer first, Integer last,
            Path toolResultsDir, CommandRunner runner) {
        try {
            long originalSize = Files.size(pdf);
            if (originalSize == 0) {
                return failure(PdfErrorReason.EMPTY, "PDF file is empty: " + displayPath);
            }
            if (originalSize > PDF_MAX_EXTRACT_SIZE) {
                return failure(PdfErrorReason.TOO_LARGE,
                    "PDF file exceeds maximum allowed size for text extraction ("
                        + FormatUtils.formatFileSize(PDF_MAX_EXTRACT_SIZE) + ").");
            }
            if (!isPopplerAvailable(runner)) {
                return failure(PdfErrorReason.UNAVAILABLE,
                    "pdftoppm is not installed. Install poppler-utils (e.g. `brew install poppler` "
                        + "or `apt-get install poppler-utils`) to enable PDF page rendering.");
            }

            Path outputDir = toolResultsDir.resolve("pdf-" + UUID.randomUUID());
            Files.createDirectories(outputDir);

            List<String> command = new ArrayList<>();
            command.add("pdftoppm");
            command.add("-jpeg");
            command.add("-r");
            command.add("100");
            if (first != null) {
                command.add("-f");
                command.add(Integer.toString(first));
            }
            if (last != null && last != Integer.MAX_VALUE) {
                command.add("-l");
                command.add(Integer.toString(last));
            }
            command.add(pdf.toAbsolutePath().toString());
            command.add(outputDir.resolve("page").toAbsolutePath().toString());

            ProcessResult result = runner.run(command, null, EXTRACTION_TIMEOUT);
            if (result.exitCode() != 0) {
                String stderr = result.stderr();
                String normalized = stderr.toLowerCase(Locale.ROOT);
                if (Strings.CS.contains(normalized, "password")) {
                    return failure(PdfErrorReason.PASSWORD_PROTECTED,
                        "PDF is password-protected. Please provide an unprotected version.");
                }
                if (Strings.CS.contains(normalized, "damaged") || Strings.CS.contains(normalized, "corrupt")
                        || Strings.CS.contains(normalized, "invalid") || Strings.CS.contains(normalized, "syntax error")
                        || Strings.CS.contains(normalized, "xref") || Strings.CS.contains(normalized, "trailer dictionary")) {
                    return failure(PdfErrorReason.CORRUPTED,
                        "PDF file is corrupted or invalid.");
                }
                return failure(PdfErrorReason.UNKNOWN,
                    "pdftoppm failed: " + stderr.stripTrailing());
            }

            List<Path> imageFiles;
            try (Stream<Path> entries = Files.list(outputDir)) {
                imageFiles = entries
                    .filter(path -> Strings.CS.endsWith(path.getFileName().toString(), ".jpg"))
                    .sorted()
                    .toList();
            }
            if (imageFiles.isEmpty()) {
                return failure(PdfErrorReason.CORRUPTED,
                    "pdftoppm produced no output pages. The PDF may be invalid.");
            }

            List<byte[]> images = new ArrayList<>(imageFiles.size());
            for (Path imageFile : imageFiles) {
                images.add(Files.readAllBytes(imageFile));
            }
            return new PdfSuccess<>(new ExtractedPages(
                displayPath, originalSize, List.copyOf(images), outputDir));
        } catch (Exception e) {
            return failure(PdfErrorReason.UNKNOWN, errorMessage(e));
        }
    }

    private static <T> PdfFailure<T> failure(PdfErrorReason reason, String message) {
        return new PdfFailure<>(new PdfError(reason, message));
    }

    private static String errorMessage(Exception e) {
        String message = e.getMessage();
        return StringUtils.isBlank(message) ? e.getClass().getSimpleName() : message;
    }
}
