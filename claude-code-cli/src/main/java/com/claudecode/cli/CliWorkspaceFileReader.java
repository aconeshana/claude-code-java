package com.claudecode.cli;

import org.apache.commons.lang3.StringUtils;

import com.claudecode.gateway.GatewayWorkspaceFilePort;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

/**
 * Reads one workspace file for the webui file-preview tab.
 *
 * <p>The session id chooses the workspace root; the path must then stay inside
 * that root once symbolic links are followed, so a file outside it is never
 * served. Files above {@link #MAX_TEXT_BYTES} are reported as oversized, and a
 * file that is not valid UTF-8 text or holds a NUL byte is reported as binary.
 */
final class CliWorkspaceFileReader {

    /** The size budget for one previewed file. */
    static final long MAX_TEXT_BYTES = 1024L * 1024L;

    private final Function<String, Optional<Path>> rootOf;

    /**
     * @param rootOf resolves a session id to its workspace root; empty when the
     *               session has no root this reader can serve
     */
    CliWorkspaceFileReader(Function<String, Optional<Path>> rootOf) {
        this.rootOf = rootOf;
    }

    GatewayWorkspaceFilePort port() {
        return (sessionId, path) -> rootOf.apply(sessionId).flatMap(root -> read(root, path));
    }

    static Optional<GatewayWorkspaceFilePort.TextFile> read(Path root, String path) {
        if (StringUtils.isBlank(path)) {
            return Optional.empty();
        }
        try {
            Path realRoot = root.toRealPath();
            Path target = realRoot.resolve(path).normalize().toRealPath();
            if (!target.startsWith(realRoot) || !Files.isRegularFile(target)) {
                return Optional.empty();
            }
            return Optional.of(content(path, target));
        } catch (IOException | InvalidPathException _) {
            return Optional.empty();
        }
    }

    private static GatewayWorkspaceFilePort.TextFile content(String path, Path target) throws IOException {
        byte[] bytes = readBounded(target);
        if (bytes.length > MAX_TEXT_BYTES) {
            return new GatewayWorkspaceFilePort.TextFile(path, GatewayWorkspaceFilePort.TextFile.Kind.OVERSIZED, "");
        }
        String text = decodeText(bytes);
        if (text == null) {
            return new GatewayWorkspaceFilePort.TextFile(path, GatewayWorkspaceFilePort.TextFile.Kind.BINARY, "");
        }
        return new GatewayWorkspaceFilePort.TextFile(path, GatewayWorkspaceFilePort.TextFile.Kind.TEXT, text);
    }

    /** Reads at most one byte past the budget, so a file that grew after a size check is still refused. */
    private static byte[] readBounded(Path target) throws IOException {
        try (InputStream in = Files.newInputStream(target)) {
            return in.readNBytes((int) MAX_TEXT_BYTES + 1);
        }
    }

    private static String decodeText(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) {
                return null;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException _) {
            return null;
        }
    }
}
