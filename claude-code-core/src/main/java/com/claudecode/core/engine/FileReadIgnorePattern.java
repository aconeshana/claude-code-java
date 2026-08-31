package com.claudecode.core.engine;

import org.apache.commons.lang3.Strings;

/**
 * A neutral, permissions-free carrier for one file-read deny rule's glob pattern, already resolved
 * to a root directory.
 */
public record FileReadIgnorePattern(String relativePattern, String rootPath) {


    public static FileReadIgnorePattern anywhere(String relativePattern) {
        return new FileReadIgnorePattern(stripDotSlash(relativePattern), null);
    }

    /** A pattern resolved relative to a concrete absolute root directory. */
    public static FileReadIgnorePattern atRoot(String relativePattern, String rootPath) {
        return new FileReadIgnorePattern(stripDotSlash(relativePattern), rootPath);
    }

    private static String stripDotSlash(String pattern) {
        return Strings.CS.startsWith(pattern, "./") ? pattern.substring(2) : pattern;
    }
}
