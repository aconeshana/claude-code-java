package com.claudecode.core.message;

/**
 * A CLAUDE.md memory file auto-injected because the model read or {@code @}-mentioned
 * a file under its directory chain. matches the {@code type: 'nested_memory'}
 * attachment produced by {@code memoryFilesToAttachments}
 * and rendered into the request as a
 * {@code <system-reminder>} block so the model is aware of the memory "in scope"
 * for what it is editing.
 *
 * <p>The header shape matches {@code MemoryPromptBuilder.renderFile} so nested
 * memory and the eager claudeMd tail read identically to the model.
 */
public record NestedMemoryAttachment(
    /** Absolute path of the memory file on disk. */
    String path,
    /** File content after frontmatter + HTML-comment stripping (matches what
     *  the eager claudeMd channel shows). */
    String content,
    /** Scope description, e.g. "(project instructions, checked into the codebase)". */
    String scopeDescription
) implements AttachmentPayload {
}
