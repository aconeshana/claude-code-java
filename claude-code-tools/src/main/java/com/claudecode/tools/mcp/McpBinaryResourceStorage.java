package com.claudecode.tools.mcp;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.mcp.McpNameNormalizer;
import com.claudecode.mcp.McpOutputStorage;
import com.claudecode.session.SessionManager;
import java.nio.file.Path;
import java.util.Base64;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Persists binary MCP resource bodies outside the model context.
 */
final class McpBinaryResourceStorage {

    @FunctionalInterface
    interface ToolResultsDirectoryResolver {
        Path resolve(String workingDirectory, String sessionId);
    }

    private final ToolResultsDirectoryResolver directoryResolver;
    private final LongSupplier currentTimeMillis;
    private final Supplier<String> randomSuffix;

    McpBinaryResourceStorage() {
        this((cwd, sessionId) -> new SessionManager(cwd).getToolResultsDir(sessionId),
            System::currentTimeMillis, McpOutputStorage::randomBase36Suffix);
    }

    McpBinaryResourceStorage(ToolResultsDirectoryResolver directoryResolver,
                             LongSupplier currentTimeMillis,
                             Supplier<String> randomSuffix) {
        this.directoryResolver = directoryResolver;
        this.currentTimeMillis = currentTimeMillis;
        this.randomSuffix = randomSuffix;
    }

    McpOutputStorage.PersistResult persist(String base64, String mimeType, int index,
                                           ToolExecutionContext context) {
        String filenameBase = "mcp-resource-" + currentTimeMillis.getAsLong()
            + "-" + index + "-" + randomSuffix.get();
        return persistWithId(base64, mimeType, filenameBase, context);
    }

    /**
     * Persists a binary block using the MCP tool-result identifier protocol.
     */
    McpOutputStorage.PersistResult persistMcpToolBinary(
            String base64, String mimeType, String serverName,
            ToolExecutionContext context) {
        String filenameBase = "mcp-" + McpNameNormalizer.normalize(serverName) + "-blob-"
            + currentTimeMillis.getAsLong() + "-" + randomSuffix.get();
        return persistWithId(base64, mimeType, filenameBase, context);
    }

    private McpOutputStorage.PersistResult persistWithId(
            String base64, String mimeType, String filenameBase,
            ToolExecutionContext context) {
        if (context == null) {
            return McpOutputStorage.PersistResult.failure(
                "Tool execution context is unavailable");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException _) {
            return McpOutputStorage.PersistResult.failure("invalid base64 data");
        }
        Path directory;
        try {
            directory = directoryResolver.resolve(
                context.workingDirectory(), context.sessionId());
        } catch (RuntimeException error) {
            String detail = error.getMessage();
            return McpOutputStorage.PersistResult.failure(
                StringUtils.isBlank(detail)
                    ? error.getClass().getSimpleName() : detail);
        }
        return McpOutputStorage.persistBinaryContent(
            directory, bytes, mimeType, filenameBase);
    }
}
