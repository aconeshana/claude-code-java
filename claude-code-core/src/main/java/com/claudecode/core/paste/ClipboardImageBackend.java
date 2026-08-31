package com.claudecode.core.paste;

/**
 * One platform-specific clipboard image capability.
 */
interface ClipboardImageBackend extends ClipboardImageReader {
    String name();
}
