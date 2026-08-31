package com.claudecode.core.paste;

/**
 * Backend adapter for the in-process macOS NSPasteboard reader.
 */
final class MacNativeClipboardImageBackend implements ClipboardImageBackend {

    @Override
    public String name() {
        return "macos-native";
    }

    @Override
    public ClipboardReadResult read() {
        return MacNativeClipboardImageReader.read();
    }
}
