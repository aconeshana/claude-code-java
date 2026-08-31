package com.claudecode.core.util;

import org.apache.commons.lang3.Strings;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * Cryptographically random Claude-style word slug generator.
 */
public final class WordSlugGenerator {

    private static final String RESOURCE = "/claude-code-word-slugs.txt";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final WordLists WORDS = loadWords();

    private WordSlugGenerator() {}

    public static String generateWordSlug() {
        return pick(WORDS.adjectives()) + "-" + pick(WORDS.verbs()) + "-" + pick(WORDS.nouns());
    }

    public static String generateShortWordSlug() {
        return pick(WORDS.adjectives()) + "-" + pick(WORDS.nouns());
    }

    private static String pick(List<String> words) {
        byte[] bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        long value = ((long) (bytes[0] & 0xff) << 24)
            | ((long) (bytes[1] & 0xff) << 16)
            | ((long) (bytes[2] & 0xff) << 8)
            | (bytes[3] & 0xffL);
        return words.get((int) (value % words.size()));
    }

    private static WordLists loadWords() {
        InputStream stream = WordSlugGenerator.class.getResourceAsStream(RESOURCE);
        if (stream == null) throw new IllegalStateException("Missing " + RESOURCE);
        List<String> adjectives = new ArrayList<>();
        List<String> nouns = new ArrayList<>();
        List<String> verbs = new ArrayList<>();
        List<String> active = null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            for (String raw; (raw = reader.readLine()) != null;) {
                String line = raw.trim();
                if (line.isEmpty() || Strings.CS.startsWith(line, "#")) continue;
                active = switch (line) {
                    case "[adjectives]" -> adjectives;
                    case "[nouns]" -> nouns;
                    case "[verbs]" -> verbs;
                    default -> {
                        if (active == null) {
                            throw new IllegalStateException("Word outside a section: " + line);
                        }
                        active.add(line);
                        yield active;
                    }
                };
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + RESOURCE, e);
        }
        if (adjectives.isEmpty() || nouns.isEmpty() || verbs.isEmpty()) {
            throw new IllegalStateException("Incomplete " + RESOURCE);
        }
        return new WordLists(List.copyOf(adjectives), List.copyOf(nouns), List.copyOf(verbs));
    }

    private record WordLists(List<String> adjectives, List<String> nouns, List<String> verbs) {}
}
