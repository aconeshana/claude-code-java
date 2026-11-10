package com.claudecode.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.gateway.GatewayWorkspaceFilePort;
import com.claudecode.gateway.GatewayWorkspaceFilePort.TextFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliWorkspaceFileReaderTest {

    @TempDir
    Path workspace;

    private Optional<TextFile> read(String path) {
        return CliWorkspaceFileReader.read(workspace, path);
    }

    @Test
    void readsUtf8TextInsideWorkspace() throws IOException {
        Files.writeString(workspace.resolve("hello.js"), "console.log(1);\n");

        TextFile file = read("hello.js").orElseThrow();

        assertThat(file.kind()).isEqualTo(TextFile.Kind.TEXT);
        assertThat(file.text()).isEqualTo("console.log(1);\n");
    }

    @Test
    void reportsNulBytesAsBinaryWithoutText() throws IOException {
        Files.write(workspace.resolve("data.bin"), new byte[] {'a', 0, 'b'});

        TextFile file = read("data.bin").orElseThrow();

        assertThat(file.kind()).isEqualTo(TextFile.Kind.BINARY);
        assertThat(file.text()).isEmpty();
    }

    @Test
    void reportsInvalidUtf8AsBinary() throws IOException {
        Files.write(workspace.resolve("latin1.txt"), new byte[] {(byte) 0xC3, (byte) 0x28});

        assertThat(read("latin1.txt").orElseThrow().kind()).isEqualTo(TextFile.Kind.BINARY);
    }

    @Test
    void reportsOversizedFileWithoutReturningIt() throws IOException {
        byte[] big = new byte[(int) CliWorkspaceFileReader.MAX_TEXT_BYTES + 1];
        Arrays.fill(big, (byte) 'x');
        Files.write(workspace.resolve("big.txt"), big);

        TextFile file = read("big.txt").orElseThrow();

        assertThat(file.kind()).isEqualTo(TextFile.Kind.OVERSIZED);
        assertThat(file.text()).isEmpty();
    }

    @Test
    void refusesPathEscapingWorkspace() throws IOException {
        Path outside = Files.createTempFile("outside", ".txt");
        Files.writeString(outside, "secret", StandardCharsets.UTF_8);

        assertThat(read("../" + outside.getFileName())).isEmpty();
    }

    @Test
    void refusesSymlinkEscapingWorkspace() throws IOException {
        Path outside = Files.createTempFile("outside", ".txt");
        Files.writeString(outside, "secret", StandardCharsets.UTF_8);
        Files.createSymbolicLink(workspace.resolve("link.txt"), outside);

        assertThat(read("link.txt")).isEmpty();
    }

    @Test
    void refusesMissingBlankAndNulPaths() {
        assertThat(read("missing.txt")).isEmpty();
        assertThat(read("  ")).isEmpty();
        assertThat(read(null)).isEmpty();
        assertThat(read("a\u0000b.txt")).isEmpty();
    }

    @Test
    void resolvesTheRootPerSession() throws IOException {
        Path other = Files.createTempDirectory("other-root");
        Files.writeString(other.resolve("only-there.txt"), "elsewhere");
        GatewayWorkspaceFilePort port = new CliWorkspaceFileReader(id ->
            "other".equals(id) ? Optional.of(other) : Optional.empty()).port();

        assertThat(port.read("other", "only-there.txt")).isPresent();
        assertThat(port.read("other", "hello.js")).isEmpty();
        assertThat(port.read("unknown", "only-there.txt")).isEmpty();
    }
}
