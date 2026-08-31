package com.claudecode.ui.lanterna.components;

import com.claudecode.ui.lanterna.transcript.MessagePanel;
import com.googlecode.lanterna.TextColor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnsiSpriteParserTest {

    @Test
    void parsesPikachuAsTwentyOneByTenTrueColorSprite() throws IOException {
        AnsiSpriteParser.Sprite sprite = AnsiSpriteParser.parse(resource("/welcome/pikachu.ansi"));

        assertEquals(21, sprite.width());
        assertEquals(10, sprite.height());
        assertTrue(sprite.rows().stream().flatMap(Collection::stream)
            .anyMatch(segment -> new TextColor.RGB(246, 213, 49).equals(segment.color())));
        assertTrue(sprite.rows().stream().flatMap(Collection::stream)
            .anyMatch(segment -> new TextColor.RGB(246, 98, 82).equals(segment.bgColor())));
    }

    @Test
    void resetRestoresDefaultForegroundAndTransparentBackground() {
        AnsiSpriteParser.Sprite sprite = AnsiSpriteParser.parse(
            "\\u001B[38;2;1;2;3;48;2;4;5;6m▀\\u001B[0m x");

        MessagePanel.Segment colored = sprite.rows().getFirst().getFirst();
        MessagePanel.Segment reset = sprite.rows().getFirst().getLast();
        assertEquals(new TextColor.RGB(1, 2, 3), colored.color());
        assertEquals(new TextColor.RGB(4, 5, 6), colored.bgColor());
        assertEquals(TextColor.ANSI.DEFAULT, reset.color());
        assertNull(reset.bgColor());
    }

    @Test
    void spriteWidthHandlesSupplementaryWideGlyphWithoutLoadingGeneralFormatter() {
        AnsiSpriteParser.Sprite sprite = AnsiSpriteParser.parse("A😀中");

        assertEquals(5, sprite.width());
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = AnsiSpriteParserTest.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException("missing resource " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
