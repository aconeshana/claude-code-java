package com.claudecode.core.engine;

import com.claudecode.core.message.ContentBlock;

import java.util.List;

/**
 * Return type for a tool's {@code call} when the model-facing {@code tool_result} content must be
 * structured content blocks directly — not text.
 */
public record RawBlocksOutput(List<ContentBlock> blocks) {
}
