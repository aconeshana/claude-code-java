package com.claudecode.core.text;

/**
 * Scans for a character that is not escaped by a preceding backslash.
 *
 * <p>A character is escaped when it is preceded by an odd number of backslashes; an even number
 * (including zero) leaves it unescaped, so {@code \\(} is an unescaped paren and {@code \(} is an
 * escaped one. This is the rule the permission-rule surface uses to find the parentheses that
 * delimit rule content in strings such as {@code Bash(git commit:*)}, whose content is itself
 * escape-encoded.
 *
 * <ul>
 *   <li>upstream {@code findFirstUnescapedChar} / {@code findLastUnescapedChar}, a module-private
 *       pair in {@code src/utils/permissions/permissionRuleParser.ts}. The original keeps exactly
 *       one copy and has hook {@code if} conditions reach it through
 *       {@code permissionRuleValueFromString} ({@code src/utils/hooks.ts}), rather than carrying a
 *       second scanner; this class exists so the Java port can share one copy the same way.</li>
 * </ul>
 */
public final class EscapedCharScanner {

    private EscapedCharScanner() {
    }

    /**
     * Returns the index of the first unescaped occurrence of {@code ch}, or {@code -1} when there
     * is none.
     */
    public static int findFirstUnescaped(String str, char ch) {
        if (str == null) return -1;
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == ch && isUnescaped(str, i)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns the index of the last unescaped occurrence of {@code ch}, or {@code -1} when there
     * is none.
     */
    public static int findLastUnescaped(String str, char ch) {
        if (str == null) return -1;
        for (int i = str.length() - 1; i >= 0; i--) {
            if (str.charAt(i) == ch && isUnescaped(str, i)) {
                return i;
            }
        }
        return -1;
    }

    /** Whether the character at {@code index} is preceded by an even number of backslashes. */
    private static boolean isUnescaped(String str, int index) {
        int backslashes = 0;
        for (int j = index - 1; j >= 0 && str.charAt(j) == '\\'; j--) {
            backslashes++;
        }
        return backslashes % 2 == 0;
    }
}