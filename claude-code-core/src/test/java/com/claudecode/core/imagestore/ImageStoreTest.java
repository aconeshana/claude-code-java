package com.claudecode.core.imagestore;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImageStoreTest {

    @Test
    void imagePathUsesMediaSubtypeVerbatimLikeTypeScript() {
        assertEquals("7.jpg",
            ImageStore.imagePath("session", 7, "image/jpg").getFileName().toString());
        assertEquals("8.png",
            ImageStore.imagePath("session", 8, null).getFileName().toString());
    }
}
