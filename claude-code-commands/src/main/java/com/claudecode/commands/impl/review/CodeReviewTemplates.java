package com.claudecode.commands.impl.review;

import org.apache.commons.lang3.Strings;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads the frozen 236 code-review prompt templates from
 * {@code command-text/official/2.1.236/code-review/} — the wire-extracted
 * segment files the command assembles per effort level, mirroring the
 * {@code tool-text} resource layout (provenance/version directories keep the
 * 236 extracts separate from any future baseline).
 *
 * <p>Files are read verbatim: no trailing-newline stripping, no indent
 * processing — the blank lines around every section head are part of the
 * frozen wire contract, so the resource bytes are the single source of truth
 * (the file is exactly what goes on the wire).
 */
final class CodeReviewTemplates {

    private static final String ROOT = "/command-text/official/2.1.236/code-review/";
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Z0-9_]+)}}");

    private CodeReviewTemplates() {}

    /** Returns one frozen template segment verbatim. */
    static String template(String name) {
        return CACHE.computeIfAbsent(name, CodeReviewTemplates::read);
    }

    /** Renders a segment's {@code {{PLACEHOLDER}}} slots; unused values are rejected. */
    static String render(String name, Map<String, String> values) {
        String template = template(name);
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder(template.length());
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            if (value == null) {
                throw new IllegalArgumentException(
                    "Missing code-review template value: " + matcher.group(1));
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private static String read(String name) {
        if (Strings.CS.contains(name, "/") || Strings.CS.contains(name, "..")) {
            throw new IllegalArgumentException("Invalid code-review template name: " + name);
        }
        String path = ROOT + name + ".txt";
        try (InputStream in = CodeReviewTemplates.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing code-review template resource: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read code-review template: " + path, e);
        }
    }
}
