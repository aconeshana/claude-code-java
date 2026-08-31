package com.claudecode.ui.lanterna.components;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;


public class ModelDisplayNameTest {

    @Test
    public void rendersSonnet46() {
        assertEquals("Sonnet 4.6", ModelDisplayName.render("claude-sonnet-4-6"));
        assertEquals("Sonnet 4.6", ModelDisplayName.render("claude-sonnet-4-6-20250514"));
    }

    @Test
    public void rendersSonnet46With1MContext() {
        assertEquals("Sonnet 4.6 (1M context)", ModelDisplayName.render("claude-sonnet-4-6[1m]"));
        assertEquals("Sonnet 4.6 (1M context)", ModelDisplayName.render("claude-sonnet-4-6-1m"));
    }

    @Test
    public void rendersSonnet45() {
        assertEquals("Sonnet 4.5", ModelDisplayName.render("claude-sonnet-4-5"));
    }

    @Test
    public void rendersOpus46() {
        assertEquals("Opus 4.6", ModelDisplayName.render("claude-opus-4-6"));
    }

    @Test
    public void rendersOpus47() {
        assertEquals("Opus 4.7", ModelDisplayName.render("claude-opus-4-7"));
    }

    @Test
    public void rendersHaiku45() {
        assertEquals("Haiku 4.5", ModelDisplayName.render("claude-haiku-4-5"));
    }

    @Test
    public void returnsUnknownForNull() {
        assertEquals("unknown", ModelDisplayName.render(null));
        assertEquals("unknown", ModelDisplayName.render(""));
    }

    @Test
    public void returnsOriginalForUnknownModel() {
        assertEquals("custom-model", ModelDisplayName.render("custom-model"));
    }
}
