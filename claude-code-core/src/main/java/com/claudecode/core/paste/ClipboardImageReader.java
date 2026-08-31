package com.claudecode.core.paste;

/**
 * Reads one image from the system clipboard.
 */
@FunctionalInterface
interface ClipboardImageReader {
    ClipboardReadResult read();
}
