package com.claudecode.core.paste;

/**
 * Distinguishes an empty clipboard from an unavailable clipboard backend.
 */
sealed interface ClipboardReadResult {

    record Image(ImagePaste.ImageWithDimensions image) implements ClipboardReadResult {
        public Image {
            if (image == null) throw new IllegalArgumentException("image must not be null");
        }
    }

    record Empty() implements ClipboardReadResult {}

    record Unavailable(Throwable cause, boolean permanent) implements ClipboardReadResult {
        static Unavailable transientFailure(Throwable cause) {
            return new Unavailable(cause, false);
        }

        static Unavailable permanent(Throwable cause) {
            return new Unavailable(cause, true);
        }
    }
}
