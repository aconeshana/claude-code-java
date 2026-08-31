package com.claudecode.services.plugins.marketplace;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalUrlOpenerTest {

    @Test
    void rejectsNonHttpProtocolsBeforeLaunchingAProcess() {
        List<List<String>> commands = new ArrayList<>();
        ExternalUrlOpener.CommandRunner runner = command -> {
            commands.add(command);
            return 0;
        };
        assertFalse(ExternalUrlOpener.open("file:///tmp/secret", "Linux", null, runner));
        assertFalse(ExternalUrlOpener.open("javascript:alert(1)", "Linux", null, runner));
        assertFalse(ExternalUrlOpener.open("not a url", "Linux", null, runner));
        assertTrue(commands.isEmpty());
    }

    @Test
    void usesPlatformLaunchersWithoutAShell() {
        assertEquals(List.of("open", "https://example.com/path?q=one%20two"),
            commandFor("Mac OS X", null));
        assertEquals(List.of("xdg-open", "https://example.com/path?q=one%20two"),
            commandFor("Linux", null));
        assertEquals(List.of("rundll32", "url,OpenURL", "https://example.com/path?q=one%20two"),
            commandFor("Windows 11", null));
    }

    @Test
    void browserOverrideMirrorsTsWindowsQuotingRule() {
        assertEquals(List.of("custom-browser", "https://example.com/path?q=one%20two"),
            commandFor("Linux", "custom-browser"));
        assertEquals(List.of("custom-browser", "\"https://example.com/path?q=one%20two\""),
            commandFor("Windows 11", "custom-browser"));
    }

    @Test
    void returnsFalseWhenLauncherFailsOrThrows() {
        assertFalse(ExternalUrlOpener.open("https://example.com", "Linux", null,
            _ -> 1));
        assertFalse(ExternalUrlOpener.open("https://example.com", "Linux", null,
            _ -> { throw new IllegalStateException("boom"); }));
    }

    private static List<String> commandFor(String os, String browser) {
        List<List<String>> commands = new ArrayList<>();
        assertTrue(ExternalUrlOpener.open("https://example.com/path?q=one%20two", os, browser,
            command -> {
                commands.add(command);
                return 0;
            }));
        return commands.getFirst();
    }
}
