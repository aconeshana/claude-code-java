package com.claudecode.tools.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.StructuredToolOutput;
import com.claudecode.core.engine.ToolExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;


class FileReadLargeRangeMemoryTest {

    private static final int FILE_MIB = 96;
    private static final int LINE_BYTES = 1024;
    private static final int LINES_PER_MIB = 1024 * 1024 / LINE_BYTES;

    @TempDir
    Path tempDir;

    @Test
    void rangedReadOfLargeFileUsesBoundedMemory() throws Exception {
        Path file = tempDir.resolve("large-range.txt");
        writeLargeLineFile(file);
        int requestedLine = FILE_MIB * LINES_PER_MIB;

        String testClasspath = System.getProperty(
            "surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx64m",
            "-cp",
            testClasspath,
            RangeReadProbe.class.getName(),
            file.toString(),
            Integer.toString(requestedLine))
            .redirectErrorStream(true)
            .start();

        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(finished, "large ranged read did not finish within 30 seconds; output:\n" + output);
        assertEquals(0, process.exitValue(),
            "a ranged read must not load the 96 MiB file into the 64 MiB heap; child output:\n" + output);
    }

    private static void writeLargeLineFile(Path file) throws IOException {
        byte[] block = new byte[1024 * 1024];
        Arrays.fill(block, (byte) 'x');
        for (int newline = LINE_BYTES - 1; newline < block.length; newline += LINE_BYTES) {
            block[newline] = '\n';
        }

        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(block);
            for (int i = 0; i < FILE_MIB; i++) {
                buffer.rewind();
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
            }
        }
    }

    /** Runs in a deliberately small heap so whole-file reads fail observably. */
    public static final class RangeReadProbe {
        private RangeReadProbe() {}

        public static void main(String[] args) {
            Path file = Path.of(args[0]);
            int requestedLine = Integer.parseInt(args[1]);
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode input = mapper.createObjectNode();
            input.put("file_path", file.toString());
            input.put("offset", requestedLine);
            input.put("limit", 1);

            ToolExecutionContext context = ToolExecutionContext.builder(new AbortController(), "large-range-probe").workingDirectory(file.getParent().toString()).build();
            Object raw = new FileReadTool().call(input, context);
            if (!(raw instanceof StructuredToolOutput result)) {
                throw new AssertionError("expected text output, got: " + raw);
            }

            String expectedContent = "x".repeat(LINE_BYTES - 1);
            String expectedRendered = requestedLine + "\t" + expectedContent;
            if (!expectedRendered.equals(result.text())) {
                throw new AssertionError("unexpected rendered range: " + result.text());
            }

            ObjectNode payload = mapper.valueToTree(result.toolUseResult());
            ObjectNode returnedFile = (ObjectNode) payload.path("file");
            if (!expectedContent.equals(returnedFile.path("content").asText())
                    || returnedFile.path("startLine").asInt() != requestedLine
                    || returnedFile.path("numLines").asInt() != 1
                    || returnedFile.path("totalLines").asInt() != requestedLine + 1) {
                throw new AssertionError("unexpected structured range: " + payload);
            }
        }
    }
}
